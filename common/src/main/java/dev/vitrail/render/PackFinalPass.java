package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.OptionValue;
import dev.vitrail.pack.PackLoader;
import dev.vitrail.pack.ProgramStage;
import dev.vitrail.pack.SamplerPlan;
import dev.vitrail.pack.TargetName;
import dev.vitrail.pack.TargetPlan;
import dev.vitrail.pack.TargetSchedule;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs one pack's {@code final} program over the finished world.
 * <p>
 * This is the narrowest thing that turns a translation into an image. A {@code final} is drawn
 * over a quad, so nothing has to be intercepted: it reads what the game drew and writes back
 * onto the same target, which is the shape milestones 1 and 2 already proved. The programs that
 * need the world's geometry come later.
 * <p>
 * Every sampler it declares is now bound to the target the pack named, at the format the pack
 * declared, and nothing else. The buffers the chain has not filled in hold their clear colour,
 * which is a duller picture than the scene they used to be handed, and that is the point: an
 * image that looks plausible because every sampler was quietly given the finished frame is the
 * shape of failure this stage exists to remove. colortex0 is the one exception, and it is not a
 * fallback: it is by definition what the gbuffers drew, so the game's own frame is seeded into
 * it until they run.
 * <p>
 * What it still does not do is supply every value a pack reads. The block is written in full, so
 * the layout is always right, but a name the engine has no answer for is written as zeroes and
 * named in the log once. That is the difference between a gap you can see and a wrong image.
 */
public final class PackFinalPass {

	/**
	 * Counts loads, so that no two of them name their shaders alike.
	 * <p>
	 * The device keeps compiled modules under the name, the stage and the defines, and not under
	 * the source, so a second pack asking for the same two names is handed the first one's SPIR-V
	 * and never compiled at all. Reloading is how this stage is worked on, and the failure is
	 * silent whenever the two packs happen to declare the same samplers: the old program keeps
	 * drawing while the targets, the formats, the clear colours and every line of the log come
	 * from the new one. What a load leaves behind stays in that cache until a resource reload
	 * empties it, which is two modules a reload against an image drawn by the wrong program.
	 */
	private static final AtomicInteger LOADS = new AtomicInteger();

	/** The block name the translator writes into every program. */
	private static final String UNIFORM_BLOCK = "OfGlobals";

	/** Which dimension's programs are used. One is enough to put something on screen. */
	private static final String OVERWORLD = "world0";

	/** The line in options.txt that names a whole set of settings rather than one of them. */
	private static final String PROFILE_KEY = "profile";

	/**
	 * The line in options.txt that turns the scene seed off. Reserved like {@code profile}, and
	 * for the same reason: no pack declares a setting under either name. Turning it off leaves
	 * colortex0 holding its clear colour, which is what proves the clears work on their own.
	 */
	private static final String SEED_KEY = "seed";

	/** Which programs of the chain actually run, which is what decides what has to be doubled. */
	private static final Set<String> EXECUTING = Set.of(OVERWORLD + "/final");

	/**
	 * A common ceiling on a pushed descriptor set. Nothing in the game asks the device for its
	 * own, so a pack past this is warned about and still drawn: the failure, if it comes, is a
	 * driver error that this line makes readable.
	 */
	private static final int PUSH_DESCRIPTORS = 32;

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
	private final ColorTargets targets;
	private final SceneSeed seed;
	private final boolean seedEnabled;

	private MappableRingBuffer block;
	private GpuBuffer quad;
	private long firstFrameNanos;
	private boolean announced;

	private PackFinalPass(PackProgram.Loaded loaded, boolean seedEnabled) {
		this.loaded = loaded;
		this.seedEnabled = seedEnabled;
		this.uniforms = new PackUniforms(loaded.program().uniforms());
		this.samplers = loaded.program().samplers().stream().map(TranslatedUnit.Uniform::name).toList();

		// Neither of these touches the device: the textures are allocated by the first frame and
		// this runs while the client is still starting up, off the render thread.
		this.targets = new ColorTargets(loaded.targets(), EXECUTING);
		this.seed = this.targets.has(0) ? new SceneSeed(this.targets.format(0)) : null;

		String vertex = loaded.program().stages().get(ProgramStage.VERTEX).text();
		String fragment = loaded.program().stages().get(ProgramStage.FRAGMENT).text();
		int load = LOADS.incrementAndGet();
		Identifier vertexId =
				Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/final_vertex_" + load);
		Identifier fragmentId =
				Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/final_fragment_" + load);
		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				return fragmentId.equals(id) ? fragment : null;
			}

			return vertexId.equals(id) ? vertex : null;
		};

		BindGroupLayout.Builder bindings = BindGroupLayout.builder()
				.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER);
		this.samplers.forEach(bindings::withSampler);

		this.pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/pack_final"))
				.withVertexShader(vertexId)
				.withFragmentShader(fragmentId)
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
			// Reserved keys rather than options: no pack declares a setting under either name,
			// and a profile is a different thing from a value, it is a whole set of them.
			OptionValue profile = chosen.remove(PROFILE_KEY);
			OptionValue seed = chosen.remove(SEED_KEY);
			Optional<PackProgram.Loaded> program = PackProgram.load(pack, OVERWORLD + "/final", true,
					chosen, profile == null ? "" : profile.text());
			if (program.isEmpty()) {
				Vitrail.logger().warn("{} does not serve {}/final with both stages, nothing to draw",
						pack.getFileName(), OVERWORLD);
				return;
			}

			active = new PackFinalPass(program.get(), seed == null || !seed.isBoolean() || seed.asBoolean());
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
		// command buffer a pass would be recording into, and the clears refuse outright while one
		// is open.
		if (!prepare(device, main)) {
			return;
		}

		GpuTextureView mainView = main.getColorTextureView();
		GpuTextureView depthView = main.useDepth ? main.getDepthTextureView() : null;
		if (mainView == null) {
			return;
		}

		// Every frame here as well, and for the same reason as the pass's own pipeline.
		boolean seeding = this.seed != null && this.seedEnabled && this.seed.prepare(device);

		announce(main, seeding);
		writeBlock(main);

		// One encoder for the whole frame. A second one would be a fresh wrapper carrying its own
		// idea of whether a pass is open, which is the guard that keeps allocations out of one.
		CommandEncoder encoder = device.createCommandEncoder();
		this.targets.clear(encoder);

		if (seeding) {
			// After the clears and never before, or the clear would throw the scene away. The main
			// side and not the other one: the gbuffers this stands in for run before anything has
			// flipped, so they would write the half every later pass starts from.
			this.seed.draw(encoder, this.quad, mainView, this.targets.view(0, TargetSchedule.Side.MAIN));
		}

		try (RenderPass pass = encoder.createRenderPass(LABEL, mainView, Optional.empty())) {
			pass.setPipeline(this.pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(UNIFORM_BLOCK, this.block.currentBuffer());
			pass.setVertexBuffer(0, this.quad.slice());
			bindSamplers(pass, depthView);
			pass.draw(QUAD.length / 5, 1, 0, 0);
		}

		this.block.rotate();
	}

	/**
	 * Every name the layout carries has to be bound or the draw throws on the first one missing,
	 * so the plan answers for all of them and a name nothing serves gets one black pixel rather
	 * than being left out.
	 */
	private void bindSamplers(RenderPass pass, GpuTextureView depthView) {
		for (String sampler : this.samplers) {
			SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);
			GpuTextureView bound = switch (binding.kind()) {
				case COLORTEX -> this.targets.view(binding.index(), binding.side());
				// White is not black on purpose: a depth of one is the far plane, so a lookup that
				// finds nothing reads open sky. Black would put the whole world in shadow.
				case DEPTH -> depthView == null ? this.targets.white() : depthView;
				case SHADOW_DEPTH, SHADOW_COLOUR -> this.targets.white();
				case NOISE -> this.targets.grey();
				case UNSERVED -> this.targets.black();
			};

			FilterMode filter = binding.kind() == SamplerPlan.Kind.COLORTEX
					? this.targets.filter(binding.index())
					: FilterMode.NEAREST;

			pass.bindTexture(sampler, bound == null ? this.targets.black() : bound,
					RenderSystem.getSamplerCache().getClampToEdge(filter));
		}
	}

	/** @return false when the targets could not be prepared, in which case nothing may be drawn */
	private boolean prepare(GpuDevice device, RenderTarget main) {
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

		// Compared against the window every frame rather than driven by an event: the resize event
		// fires too early, fires again when only the interface scale moved, and the panorama
		// capture takes the main target to 4096 without going through the game's resize at all.
		return this.targets.ensure(main.width, main.height);
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

	/**
	 * Said once per pack, and grouped by cause rather than by target. A promoted format, a buffer
	 * nothing writes and a sampler nothing serves all produce a picture that looks entirely
	 * plausible, so none of the three can be found by looking at one. They are named here instead.
	 */
	private void announce(RenderTarget main, boolean seeding) {
		if (this.announced) {
			return;
		}

		this.announced = true;
		TargetPlan plan = this.loaded.targets();
		Vitrail.logger().info("Drawing {} of {} at {}x{}, {} uniforms and {} samplers",
				this.loaded.path(), this.loaded.packName(), main.width, main.height,
				this.loaded.program().uniforms().size(), this.samplers.size());

		// Already whole sentences, and already the pack's own words where it has any: promotions,
		// sizes the pack asked for, mipmaps nothing can generate, and what this engine will not do.
		plan.notes().forEach(note -> Vitrail.logger().info("{}", note));
		this.targets.notes().forEach(note -> Vitrail.logger().warn("{}", note));

		List<String> resting = plan.ordered().stream()
				.filter(index -> !(seeding && index == 0))
				.map(TargetName::canonical)
				.toList();
		if (!resting.isEmpty()) {
			Vitrail.logger().info(
					"No pass writes these yet, so they hold nothing but their clear colour: {}", resting);
		}

		if (seeding) {
			Vitrail.logger().info("colortex0 carries the game's finished frame, drawn in for the "
					+ "gbuffers stage that does not run yet, so it is already tone mapped and "
					+ "already holds the translucents, the weather and the hand");
		} else if (this.seed == null) {
			Vitrail.logger().info("{} declares no colortex0 in {}, so nothing carries the game's "
					+ "frame at all", this.loaded.packName(), plan.place().isEmpty() ? "its root" : plan.place());
		} else if (!this.seedEnabled) {
			Vitrail.logger().info("The scene seed is off, colortex0 holds its clear colour as well");
		}

		announceSamplers();

		int descriptors = this.samplers.size() + 1;
		if (descriptors > PUSH_DESCRIPTORS) {
			Vitrail.logger().warn("{} binds {} descriptors in one set, past the {} a device commonly "
					+ "allows pushed at once", this.loaded.path(), descriptors, PUSH_DESCRIPTORS);
		}

		List<String> missing = this.uniforms.unsupplied();
		if (!missing.isEmpty()) {
			Vitrail.logger().warn(
					"{} values this program reads are written as zeroes because nothing supplies them yet: {}",
					missing.size(), missing);
		}
	}

	private void announceSamplers() {
		Map<SamplerPlan.Kind, List<String>> byKind = this.loaded.samplers().byKind();
		named(byKind, SamplerPlan.Kind.COLORTEX, "read a real colour target");
		named(byKind, SamplerPlan.Kind.DEPTH, "read the world's depth");
		named(byKind, SamplerPlan.Kind.SHADOW_DEPTH, "read white, no shadow map is drawn yet");
		named(byKind, SamplerPlan.Kind.SHADOW_COLOUR, "read white, no shadow map is drawn yet");
		named(byKind, SamplerPlan.Kind.NOISE, "read mid grey, no noise texture is built yet");

		// The two copies of the depth taken before the translucents and before the hand cannot be
		// made from a hook that fires once the world is finished, so both read the final depth.
		List<String> copies = byKind.getOrDefault(SamplerPlan.Kind.DEPTH, List.of()).stream()
				.filter(name -> name.equals("depthtex1") || name.equals("depthtex2"))
				.toList();
		if (!copies.isEmpty()) {
			Vitrail.logger().info("{} read the finished depth rather than the copies taken before "
					+ "the translucents and before the hand", copies);
		}

		List<String> unserved = this.loaded.samplers().unserved();
		if (!unserved.isEmpty()) {
			Vitrail.logger().warn("{} samplers this program declares read one black pixel because "
					+ "nothing serves them: {}", unserved.size(), unserved);
		}
	}

	private static void named(Map<SamplerPlan.Kind, List<String>> byKind, SamplerPlan.Kind kind,
			String what) {
		List<String> names = byKind.getOrDefault(kind, List.of());
		if (!names.isEmpty()) {
			Vitrail.logger().info("{} samplers {}: {}", names.size(), what, names);
		}
	}

	private void release() {
		this.targets.release();
		if (this.seed != null) {
			this.seed.release();
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
