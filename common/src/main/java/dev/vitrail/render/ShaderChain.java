package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.shader.DiskShaderSource;
import dev.vitrail.shader.ShaderFiles;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A chain of full screen passes run once the world is done and before the interface goes on
 * top. The first pass reads what the game drew, the second reads the first, and the last one
 * puts the result back onto the main target.
 * <p>
 * Nothing is synchronised by hand. The Vulkan backend ends every render pass with a full
 * memory barrier and keeps every image in one layout for its whole life, so closing a pass
 * is all it takes for the next one to read what it wrote. The consequence is that the passes
 * have to be closed, which is why each one lives in a try-with-resources.
 */
public final class ShaderChain {

	private static final String SCENE_SAMPLER = "VitrailSceneSampler";
	private static final String FIRST_SAMPLER = "VitrailFirstSampler";
	private static final String SECOND_SAMPLER = "VitrailSecondSampler";

	// A clear value on the attachment turns into a load operation, so an intermediate target
	// starts from a known state without a clear command of its own.
	private static final Optional<Vector4fc> CLEAR_BLACK = Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F));
	// The main target already holds the world. Loading it rather than clearing it means a
	// composition shader that misses a pixel shows the world through, not a black hole.
	private static final Optional<Vector4fc> KEEP = Optional.empty();

	private static final Stage FIRST = new Stage(
			pipeline("pass1", ShaderFiles.FIRST, SCENE_SAMPLER, ColorTargetState.WRITE_ALL),
			SCENE_SAMPLER, () -> "Vitrail pass 1", CLEAR_BLACK);
	private static final Stage SECOND = new Stage(
			pipeline("pass2", ShaderFiles.SECOND, FIRST_SAMPLER, ColorTargetState.WRITE_ALL),
			FIRST_SAMPLER, () -> "Vitrail pass 2", CLEAR_BLACK);
	// WRITE_COLOR leaves the alpha of the main target alone; the intermediate targets are
	// written whole so they can carry alpha between passes later on.
	private static final Stage COMPOSE = new Stage(
			pipeline("compose", ShaderFiles.COMPOSE, SECOND_SAMPLER, ColorTargetState.WRITE_COLOR),
			SECOND_SAMPLER, () -> "Vitrail compose", KEEP);

	private static final List<Stage> STAGES = List.of(FIRST, SECOND, COMPOSE);

	private static final PassTargets TARGETS = new PassTargets();

	private static volatile DiskShaderSource shaders;
	private static volatile boolean disabled;

	private static boolean reportedCompileFailure;
	private static boolean reportedFirstFrame;

	private ShaderChain() {
	}

	/**
	 * Reads the GLSL from disk. Called once while the client starts up, off the render
	 * thread, so it does no more than file access.
	 */
	public static void loadShaders() {
		try {
			shaders = ShaderFiles.load();
		} catch (IOException | RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail chain disabled: could not read the shaders in {}",
					ShaderFiles.directory(), e);
		}
	}

	/** Called from the loader module once the world has been rendered. */
	public static void draw() {
		if (disabled) {
			return;
		}

		try {
			runChain();
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail chain disabled for this session after an error while drawing", e);
			TARGETS.close();
		}
	}

	/** Called when the client shuts down, while the device is still alive. */
	public static void close() {
		TARGETS.close();
	}

	private static void runChain() {
		DiskShaderSource source = shaders;
		if (source == null) {
			return;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null || !compile(device, source)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();
		GpuTextureView mainColor = main == null ? null : main.getColorTextureView();
		if (mainColor == null) {
			return;
		}

		// Sized from the main target rather than from the window, since that is what the last
		// pass draws into. It also has to happen before the first pass is open: creating a
		// texture records a barrier into the very command buffer a pass would be recording
		// into, and nothing on the Java side stops that from happening.
		if (!TARGETS.ensureSize(main.width, main.height)) {
			return;
		}

		GpuTextureView firstColor = TARGETS.first().getColorTextureView();
		GpuTextureView secondColor = TARGETS.second().getColorTextureView();
		if (firstColor == null || secondColor == null) {
			return;
		}

		if (!reportedFirstFrame) {
			reportedFirstFrame = true;
			Vitrail.logger().info("Vitrail chain running on the {} backend, {} passes at {}x{}",
					device.getDeviceInfo().backendName(), STAGES.size(), main.width, main.height);
		}

		// One encoder for the whole chain. Each call to createCommandEncoder hands back a
		// fresh wrapper over the same backend, and the guard that refuses to open a pass
		// while another is open lives on the wrapper, so two of them would let the passes
		// nest without a word.
		CommandEncoder encoder = device.createCommandEncoder();
		runStage(encoder, FIRST, mainColor, firstColor);
		runStage(encoder, SECOND, firstColor, secondColor);
		runStage(encoder, COMPOSE, secondColor, mainColor);
	}

	private static void runStage(CommandEncoder encoder, Stage stage, GpuTextureView input, GpuTextureView output) {
		try (RenderPass pass = encoder.createRenderPass(stage.label(), output, stage.clearColor())) {
			pass.setPipeline(stage.pipeline());
			RenderSystem.bindDefaultUniforms(pass);
			// Nearest, not linear: all three targets are the same size, so there is nothing
			// to interpolate, and blurring would smear the colour marks the second pass has
			// to recognise by an exact comparison.
			pass.bindTexture(stage.samplerName(), input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	/**
	 * Hands our sources to every pipeline, every frame. Setting a pipeline that was not
	 * precompiled this way makes the backend compile it against the game's own shader source
	 * instead, which knows nothing about our identifiers and yields a pipeline that throws
	 * when it is used. It is a lookup once the cache is warm, and it is also what puts the
	 * pipelines back after a resource reload empties that cache.
	 */
	private static boolean compile(GpuDevice device, DiskShaderSource source) {
		boolean valid = true;
		for (Stage stage : STAGES) {
			// Not short-circuiting, so that one run reports every unit that failed rather
			// than the first one.
			valid &= device.precompilePipeline(stage.pipeline(), source).isValid();
		}

		if (!valid) {
			if (!reportedCompileFailure) {
				reportedCompileFailure = true;
				Vitrail.logger().error(
						"Vitrail chain skipped: the shaders in {} did not compile. The compiler errors are logged just "
								+ "above; fix the files and restart. The game keeps running without the chain.",
						ShaderFiles.directory());
			}

			return false;
		}

		reportedCompileFailure = false;

		return true;
	}

	private static RenderPipeline pipeline(String name, Identifier fragment, String samplerName, int writeMask) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/" + name))
				.withVertexShader(ShaderFiles.SCREEN)
				.withFragmentShader(fragment)
				// A uniform block or a sampler the shader declares has to be named here too,
				// or compilation fails on it. The other way round is harmless, which is why
				// the composition pipeline can carry Globals without its shader using it.
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder().withSampler(samplerName).build())
				// No blending anywhere: the chain replaces what it reads, it does not lay
				// anything over it, so a wrong image cannot be mistaken for a dim one.
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, writeMask))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}

	// The label supplier is called twice per pass and per frame when the driver exposes
	// checkpoints, so it returns a constant rather than building a string.
	private record Stage(RenderPipeline pipeline, String samplerName, Supplier<String> label,
			Optional<Vector4fc> clearColor) {
	}
}
