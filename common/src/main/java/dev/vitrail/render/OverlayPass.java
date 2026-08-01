package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.shader.DiskShaderSource;
import dev.vitrail.shader.ShaderFiles;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.Optional;

/**
 * A single full screen triangle drawn into whatever the game is currently rendering into,
 * once the world is done and before the interface goes on top.
 * <p>
 * The pass owns no GPU resource of its own: the only thing it allocates is the pipeline,
 * and that lives in the device cache rather than here. Everything else is looked up again
 * on every frame, so a window resize, a resource reload or a backend restart need no
 * bookkeeping.
 */
public final class OverlayPass {

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/overlay"))
			.withVertexShader(DiskShaderSource.SHADER_ID)
			.withFragmentShader(DiskShaderSource.SHADER_ID)
			// The fragment shader reads ScreenSize out of this block, so the frame it draws
			// stays the same thickness in pixels whatever the window size is.
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withColorTargetState(new ColorTargetState(
					Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.build();

	private static volatile DiskShaderSource shaders;
	private static volatile boolean disabled;

	private static boolean reportedCompileFailure;
	private static boolean reportedFirstFrame;

	private OverlayPass() {
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
			Vitrail.logger().error("Vitrail pass disabled: could not read the shaders in {}",
					ShaderFiles.directory(), e);
		}
	}

	/** Called from the loader module once the world has been rendered. */
	public static void draw() {
		if (disabled) {
			return;
		}

		try {
			drawTriangle();
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail pass disabled for this session after an error while drawing", e);
		}
	}

	private static void drawTriangle() {
		DiskShaderSource source = shaders;
		if (source == null) {
			return;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}

		// Asking again every frame costs an identity lookup once the pipeline sits in the
		// device cache, and it is what puts the pipeline back after a resource reload
		// clears that cache. Without it the backend would fall back to its own shader
		// source, find nothing under our identifier and hand out a broken pipeline.
		CompiledRenderPipeline compiled = device.precompilePipeline(PIPELINE, source);
		if (!compiled.isValid()) {
			if (!reportedCompileFailure) {
				reportedCompileFailure = true;
				Vitrail.logger().error(
						"Vitrail pass skipped: {} and {} did not compile. The compiler error is logged just above; "
								+ "fix the files in {} and restart. The game keeps running without the pass.",
						ShaderFiles.VERTEX_FILE, ShaderFiles.FRAGMENT_FILE, ShaderFiles.directory());
			}

			return;
		}

		reportedCompileFailure = false;

		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget target = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();
		GpuTextureView colorView = target == null ? null : target.getColorTextureView();
		if (colorView == null) {
			return;
		}

		if (!reportedFirstFrame) {
			reportedFirstFrame = true;
			Vitrail.logger().info("Vitrail pass drawing on the {} backend, target {}x{}",
					device.getDeviceInfo().backendName(), target.width, target.height);
		}

		CommandEncoder encoder = device.createCommandEncoder();
		try (RenderPass pass = encoder.createRenderPass(() -> "Vitrail overlay", colorView, Optional.empty())) {
			pass.setPipeline(PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.draw(3, 1, 0, 0);
		}
	}
}
