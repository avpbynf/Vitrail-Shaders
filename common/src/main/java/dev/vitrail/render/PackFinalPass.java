package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.OptionValue;
import dev.vitrail.pack.PackLoader;
import dev.vitrail.pack.ProgramStage;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs one pack's {@code final} program over the finished world.
 * <p>
 * This is the narrowest thing that turns a translation into an image. A {@code final} is drawn
 * over a quad, so nothing has to be intercepted: it reads what the game drew and writes back
 * onto the same target, which is the shape milestones 1 and 2 already proved. The programs that
 * need the world's geometry come later.
 * <p>
 * What it does not do yet is supply every value a pack reads. The block is written in full, so
 * the layout is always right, but a name the engine has no answer for is written as zeroes and
 * named in the log once. That is the difference between a gap you can see and a wrong image.
 */
public final class PackFinalPass {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/final_vertex");
	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/final_fragment");

	/** The block name the translator writes into every program. */
	private static final String UNIFORM_BLOCK = "OfGlobals";

	/** Which dimension's programs are used. One is enough to put something on screen. */
	private static final String OVERWORLD = "world0";

	/** The line in options.txt that names a whole set of settings rather than one of them. */
	private static final String PROFILE_KEY = "profile";

	/**
	 * The quad a pack expects under a full screen pass, from (0,0) to (1,1), as two triangles.
	 * Vulkan has no quad topology and going through an index buffer to get one would add a
	 * moving part for four vertices.
	 */
	private static final float[] QUAD = {
			0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
			1.0F, 0.0F, 0.0F, 1.0F, 0.0F,
			1.0F, 1.0F, 0.0F, 1.0F, 1.0F,
			0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
			1.0F, 1.0F, 0.0F, 1.0F, 1.0F,
			0.0F, 1.0F, 0.0F, 0.0F, 1.0F };

	/** Carries that quad from (0,1) to clip space. Iris uses this one, so packs are written for it. */
	private static final Matrix4f QUAD_PROJECTION = new Matrix4f().set(
			2.0F, 0.0F, 0.0F, 0.0F,
			0.0F, 2.0F, 0.0F, 0.0F,
			0.0F, 0.0F, 0.0F, 0.0F,
			-1.0F, -1.0F, 0.0F, 1.0F);

	private static final Supplier<String> LABEL = () -> "Vitrail pack final";
	private static final Supplier<String> BLOCK_LABEL = () -> "Vitrail OfGlobals";
	private static final Supplier<String> QUAD_LABEL = () -> "Vitrail quad";

	private static volatile PackFinalPass active;
	private static volatile boolean disabled;
	private static long lastCheckNanos;
	private static long lastStamp;
	private static boolean checked;

	private final PackProgram.Loaded loaded;
	private final RenderPipeline pipeline;
	private final PackUniforms uniforms;
	private final ShaderSource source;
	private final List<String> samplers;

	private MappableRingBuffer block;
	private GpuBuffer quad;
	private TextureTarget scene;
	private long firstFrameNanos;
	private boolean announced;

	private PackFinalPass(PackProgram.Loaded loaded) {
		this.loaded = loaded;
		this.uniforms = new PackUniforms(loaded.program().uniforms());
		this.samplers = loaded.program().samplers().stream().map(TranslatedUnit.Uniform::name).toList();

		String vertex = loaded.program().stages().get(ProgramStage.VERTEX).text();
		String fragment = loaded.program().stages().get(ProgramStage.FRAGMENT).text();
		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				return FRAGMENT_ID.equals(id) ? fragment : null;
			}

			return VERTEX_ID.equals(id) ? vertex : null;
		};

		BindGroupLayout.Builder bindings = BindGroupLayout.builder()
				.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER);
		this.samplers.forEach(bindings::withSampler);

		this.pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/pack_final"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(bindings.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				// The alpha of the main target is left alone: the interface is drawn over it
				// afterwards and reads it.
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
						ColorTargetState.WRITE_COLOR))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}

	/**
	 * Reads the first pack in the folder and translates its {@code final}. Runs while the client
	 * starts up, off the render thread, so it touches files and nothing else.
	 */
	public static void load(Path gameDirectory) {
		try {
			List<Path> packs = PackLoader.candidates(gameDirectory);
			if (packs.isEmpty()) {
				Vitrail.logger().info("No shader pack in {}, nothing to draw",
						PackLoader.directory(gameDirectory));
				return;
			}

			Path pack = choose(gameDirectory, packs);
			Map<String, OptionValue> chosen = new LinkedHashMap<>(settings(gameDirectory));
			// A reserved key rather than an option: no pack declares a setting called profile,
			// and a profile is a different thing from a value, it is a whole set of them.
			OptionValue profile = chosen.remove(PROFILE_KEY);
			Optional<PackProgram.Loaded> program = PackProgram.load(pack, OVERWORLD + "/final", true,
					chosen, profile == null ? "" : profile.text());
			if (program.isEmpty()) {
				Vitrail.logger().warn("{} does not serve {}/final with both stages, nothing to draw",
						pack.getFileName(), OVERWORLD);
				return;
			}

			active = new PackFinalPass(program.get());
		} catch (IOException | RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail could not prepare a pack's final pass", e);
		}
	}

	/**
	 * Which pack to draw. A line in {@code vitrail/pack.txt} naming one, or any part of one, wins;
	 * otherwise the first in the folder does.
	 * <p>
	 * A text file is not a settings screen and is not meant to become one. It exists because
	 * eight packs sit in that folder and switching between them is most of the work of supplying
	 * the values they read, so needing to rename files to do it would be a tax on every attempt.
	 */
	private static Path choose(Path gameDirectory, List<Path> packs) throws IOException {
		Path chosen = gameDirectory.resolve(Vitrail.MOD_ID).resolve("pack.txt");
		if (!Files.isRegularFile(chosen)) {
			return packs.get(0);
		}

		String wanted = Files.readString(chosen).trim().toLowerCase(Locale.ROOT);
		if (wanted.isEmpty()) {
			return packs.get(0);
		}

		for (Path pack : packs) {
			if (pack.getFileName().toString().toLowerCase(Locale.ROOT).contains(wanted)) {
				return pack;
			}
		}

		Vitrail.logger().warn("No pack in the folder matches '{}' from {}, using the first instead",
				wanted, chosen);

		return packs.get(0);
	}

	/**
	 * Settings to force on the pack, one {@code NAME=value} per line in {@code vitrail/options.txt}.
	 * A value of {@code on} or {@code off} toggles, anything else is written as it stands.
	 * <p>
	 * This exists to make the pass provable. A pack's {@code final} is often nearly an identity
	 * with its settings at their defaults, which looks exactly like a pass that never ran; turning
	 * on one of the pack's own features settles it without touching the pack or writing a test
	 * shader that proves only itself. It also exercises the settings resolved at milestone 3,
	 * which nothing else does yet.
	 */
	private static Map<String, OptionValue> settings(Path gameDirectory) throws IOException {
		Path file = gameDirectory.resolve(Vitrail.MOD_ID).resolve("options.txt");
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}

		Map<String, OptionValue> chosen = new LinkedHashMap<>();
		for (String line : Files.readAllLines(file)) {
			String trimmed = line.trim();
			int equals = trimmed.indexOf('=');
			if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 1) {
				continue;
			}

			String name = trimmed.substring(0, equals).trim();
			String value = trimmed.substring(equals + 1).trim();
			chosen.put(name, switch (value.toLowerCase(Locale.ROOT)) {
				case "on", "true" -> OptionValue.on();
				case "off", "false" -> OptionValue.off();
				default -> OptionValue.of(value);
			});
		}

		if (!chosen.isEmpty()) {
			Vitrail.logger().info("Forcing {} pack settings from {}: {}", chosen.size(), file,
					chosen.keySet());
		}

		return chosen;
	}

	/**
	 * Rebuilds everything when {@code pack.txt} or {@code options.txt} changes on disk, looked at
	 * once a second at most.
	 * <p>
	 * Watching the files rather than binding a key is not laziness. Forcing a pack's own setting
	 * turned out to be the only honest way to prove a pass does what it should, so it is done
	 * constantly, and a restart between attempts costs a minute every time. The price is the half
	 * second a pack takes to read and translate, which shows as a hitch and is the right trade.
	 */
	private static void reloadIfChanged(Path gameDirectory) {
		long now = System.nanoTime();
		if (now - lastCheckNanos < 1_000_000_000L) {
			return;
		}

		lastCheckNanos = now;
		long stamp = stampOf(gameDirectory.resolve(Vitrail.MOD_ID).resolve("pack.txt"))
				+ stampOf(gameDirectory.resolve(Vitrail.MOD_ID).resolve("options.txt"));
		boolean first = lastStamp == 0L && !checked;
		checked = true;
		if (stamp == lastStamp || first) {
			lastStamp = stamp;
			return;
		}

		lastStamp = stamp;
		Vitrail.logger().info("Settings changed on disk, reloading the pack");

		PackFinalPass previous = active;
		if (previous != null) {
			previous.release();
		}

		active = null;
		// Cleared as well, so that a pack that failed to compile can be fixed and tried again
		// without leaving the game.
		disabled = false;
		load(gameDirectory);
	}

	private static long stampOf(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
		} catch (IOException e) {
			return 0L;
		}
	}

	/**
	 * Called from the loader module once the world has been rendered.
	 *
	 * @return whether a pack was drawn, so that the caller knows to fall back to its own chain.
	 *         The reload check runs first and unconditionally, or a pack that failed once could
	 *         never be retried.
	 */
	public static boolean draw(Path gameDirectory) {
		reloadIfChanged(gameDirectory);

		PackFinalPass pass = active;
		if (disabled || pass == null) {
			return false;
		}

		try {
			pass.run();
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail stopped drawing this pack after an error", e);
			pass.release();
		}

		return true;
	}

	/** Called when the client shuts down, while the device is still alive. */
	public static void close() {
		PackFinalPass pass = active;
		if (pass != null) {
			pass.release();
		}
	}

	private void run() {
		GpuDevice device = RenderSystem.tryGetDevice();
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();
		if (device == null || main == null || main.getColorTexture() == null) {
			return;
		}

		// Every frame, because a resource reload empties the pipeline cache and a pipeline that
		// was not compiled against our own source is compiled against the game's instead.
		if (!device.precompilePipeline(this.pipeline, this.source).isValid()) {
			disabled = true;
			Vitrail.logger().error("{} did not compile its {}, nothing will be drawn",
					this.loaded.packName(), this.loaded.path());
			return;
		}

		// Outside any render pass: creating a texture or a buffer records a barrier into the very
		// command buffer a pass would be recording into.
		prepare(device, main);

		GpuTextureView sceneView = this.scene.getColorTextureView();
		GpuTextureView mainView = main.getColorTextureView();
		GpuTextureView depthView = main.useDepth ? main.getDepthTextureView() : null;
		if (sceneView == null || mainView == null) {
			return;
		}

		announce(main);
		writeBlock(main);

		CommandEncoder encoder = device.createCommandEncoder();
		// The pass writes into the main target while reading what the main target held, so the
		// copy has to happen first and cannot be skipped by sampling the target itself.
		encoder.copyTextureToTexture(main.getColorTexture(), this.scene.getColorTexture(), 0,
				0, 0, 0, 0, main.width, main.height);

		try (RenderPass pass = encoder.createRenderPass(LABEL, mainView, Optional.empty())) {
			pass.setPipeline(this.pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(UNIFORM_BLOCK, this.block.currentBuffer());
			pass.setVertexBuffer(0, this.quad.slice());

			// Every sampler the layout names has to be bound or the draw throws. A pack asking
			// for a buffer this pass does not own yet reads the scene, which is wrong but visible.
			for (String sampler : this.samplers) {
				GpuTextureView bound = sampler.startsWith("depthtex") && depthView != null ? depthView : sceneView;
				pass.bindTexture(sampler, bound,
						RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			}

			pass.draw(QUAD.length / 5, 1, 0, 0);
		}

		this.block.rotate();
	}

	private void prepare(GpuDevice device, RenderTarget main) {
		if (this.scene == null) {
			this.scene = new TextureTarget("Vitrail colortex0", main.width, main.height, false,
					GpuFormat.RGBA8_UNORM);
		} else if (this.scene.width != main.width || this.scene.height != main.height) {
			this.scene.resize(main.width, main.height);
		}

		if (this.quad == null) {
			ByteBuffer vertices = ByteBuffer.allocateDirect(QUAD.length * Float.BYTES)
					.order(ByteOrder.nativeOrder());
			vertices.asFloatBuffer().put(QUAD);
			this.quad = device.createBuffer(QUAD_LABEL,
					GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertices);
		}

		if (this.block == null) {
			// Three buffers and a fence per turn, so a frame never writes over what the previous
			// one is still being read for.
			this.block = new MappableRingBuffer(BLOCK_LABEL,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
					Math.max(16, this.uniforms.size()));
		}
	}

	private void writeBlock(RenderTarget main) {
		if (this.firstFrameNanos == 0L) {
			this.firstFrameNanos = System.nanoTime();
		}

		Minecraft minecraft = Minecraft.getInstance();
		// OptiFine's counter is seconds since the world was entered and wraps at an hour, which
		// keeps the precision of a float usable for the packs that drive noise with it.
		float seconds = (float) (((System.nanoTime() - this.firstFrameNanos) / 1.0E9D) % 3600.0D);
		float rain = minecraft.level == null ? 0.0F : minecraft.level.getRainLevel(1.0F);
		boolean sneaking = minecraft.player != null && minecraft.player.isShiftKeyDown();

		PackUniforms.Frame frame = new PackUniforms.Frame(main.width, main.height, seconds, rain,
				sneaking, QUAD_PROJECTION);

		try (GpuBufferSlice.MappedView view = this.block.currentBuffer().map(false, true)) {
			this.uniforms.write(Std140Builder.intoBuffer(view.data()), frame);
		}
	}

	private void announce(RenderTarget main) {
		if (this.announced) {
			return;
		}

		this.announced = true;
		List<String> missing = new ArrayList<>(this.uniforms.unsupplied());
		Vitrail.logger().info("Drawing {} of {} at {}x{}, {} uniforms and {} samplers",
				this.loaded.path(), this.loaded.packName(), main.width, main.height,
				this.loaded.program().uniforms().size(), this.samplers.size());

		if (!missing.isEmpty()) {
			Vitrail.logger().warn(
					"{} values this program reads are written as zeroes because nothing supplies them yet: {}",
					missing.size(), missing);
		}
	}

	private void release() {
		if (this.scene != null) {
			this.scene.destroyBuffers();
			this.scene = null;
		}

		if (this.block != null) {
			this.block.close();
			this.block = null;
		}

		if (this.quad != null) {
			this.quad.close();
			this.quad = null;
		}
	}
}
