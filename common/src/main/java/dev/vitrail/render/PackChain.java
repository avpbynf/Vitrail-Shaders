package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.ChainFilter;
import dev.vitrail.pack.ChainPlan;
import dev.vitrail.pack.OptionValue;
import dev.vitrail.pack.PackLoader;
import dev.vitrail.pack.SamplerPlan;
import dev.vitrail.pack.TargetName;
import dev.vitrail.pack.TargetPlan;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs one pack's chain over the finished world: every full screen program the pack keeps on, in
 * frame order, and then its {@code final} onto the game's own target.
 * <p>
 * Nothing here decides what runs or which half of a target a pass touches. The plan walked the
 * frame once when the pack was read, {@link ChainPlan} unfolded that walk into attachments, and a
 * frame replays the result without working any of it out again. That is the whole discipline of
 * this class: two answers to "which half does this pass write" produce no error at all, only a
 * picture that is plausible and wrong.
 * <p>
 * What it cannot do yet has to be said rather than covered up. No geometry program runs, so
 * colortex0, or whichever target the pack's terrain program writes first, is painted with the
 * game's finished frame and every other buffer starts from its clear colour. A pass reading
 * normals or a material id out of one of those reads nothing of the sort, and the log names each
 * one before the first frame is drawn.
 * <p>
 * Two lifecycle traps are paid for here rather than rediscovered. The device caches a compiled
 * module under an identifier, a stage and a set of defines, never under the source, so every load
 * numbers its programs and no two loads name theirs alike. And a resource reload empties the
 * pipeline cache, F3+T included, after which a pipeline drawn without being compiled again would
 * be rebuilt from the game's own shader sources, which hold no line of this pack.
 */
public final class PackChain {

	/**
	 * Counts loads, so that no two of them name their shaders alike.
	 * <p>
	 * Reloading is how this stage is worked on, and the failure this prevents is silent: the old
	 * programs keep drawing while the targets, the formats, the clear colours and every line of
	 * the log come from the new pack. A chain multiplies it by the number of programs.
	 */
	private static final AtomicInteger LOADS = new AtomicInteger();

	/** Which dimension's programs are used. The plan falls back to the root when it ships none. */
	private static final String OVERWORLD = "world0";

	/** The line in options.txt that names a whole set of settings rather than one of them. */
	private static final String PROFILE_KEY = "profile";

	/**
	 * The line in options.txt that turns the scene seed off. Reserved like {@code profile}, and
	 * for the same reason: no pack declares a setting under either name. Turning it off leaves the
	 * seeded target holding its clear colour, which is what proves the clears work on their own.
	 */
	private static final String SEED_KEY = "seed";

	/**
	 * The line in options.txt that cuts the chain down: {@code passes=0}, {@code passes=6}, or
	 * {@code passes=composite4,composite5}. Reserved for the same reason as the other two.
	 * <p>
	 * This is how a broken picture is bisected, and it is also the only honest way to price the
	 * chain: {@code passes=0} is the final alone, which is the image every earlier milestone was
	 * measured on. The schedule is rebuilt on what it leaves, never trimmed afterwards.
	 */
	private static final String PASSES_KEY = "passes";

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

	private static final Supplier<String> BLOCK_LABEL = () -> "Vitrail OfGlobals";
	private static final Supplier<String> QUAD_LABEL = () -> "Vitrail quad";

	private static volatile PackChain active;
	private static volatile boolean disabled;
	private static long lastCheckNanos;
	private static long lastStamp;
	private static boolean checked;

	private final PackProgram.Chain chain;
	private final ColorTargets targets;
	private final SceneSeed seed;
	private final boolean seedEnabled;
	private final int load;

	private List<PackPass> programs;
	private PackPass last;
	private MappableRingBuffer block;
	private GpuBuffer quad;
	private CompiledRenderPipeline head;
	private int blockBytes;
	private int warmed;
	private long firstFrameNanos;
	private boolean announced;

	private PackChain(PackProgram.Chain chain, boolean seedEnabled) {
		this.chain = chain;
		this.seedEnabled = seedEnabled;
		this.load = LOADS.incrementAndGet();

		// None of this touches the device: the textures are allocated by the first frame and this
		// runs while the client is still starting up, off the render thread.
		this.targets = new ColorTargets(chain.targets());
		this.seed = chain.chain().seed()
				.filter(where -> this.targets.has(where.target()))
				.map(where -> new SceneSeed(where, this.targets.format(where.target())))
				.orElse(null);
	}

	/**
	 * Reads the chosen pack and translates every program of its chain. Runs while the client
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
			// Reserved keys rather than options: no pack declares a setting under any of the three,
			// and a profile is a different thing from a value, it is a whole set of them.
			OptionValue profile = chosen.remove(PROFILE_KEY);
			OptionValue seed = chosen.remove(SEED_KEY);
			ChainFilter filter = filterOf(chosen.remove(PASSES_KEY));
			if (!chosen.isEmpty()) {
				Vitrail.logger().info("Forcing {} pack settings from {}: {}", chosen.size(),
						optionsFile(gameDirectory), chosen.keySet());
			}

			long began = System.nanoTime();
			Optional<PackProgram.Chain> read = PackProgram.loadChain(pack, OVERWORLD, chosen,
					textOf(profile), filter);
			if (read.isEmpty()) {
				Vitrail.logger().warn("{} serves no final with both stages, in {} or at its root, "
						+ "nothing to draw", pack.getFileName(), OVERWORLD);
				return;
			}

			PackProgram.Chain chain = read.get();
			Vitrail.logger().info("Read {} programs of {} in {} ms", chain.programs().size(),
					chain.packName(), (System.nanoTime() - began) / 1_000_000L);

			// A refusal is a rule of the API this engine cannot bend, named with the program that
			// broke it. Dropping that program instead would move the half every later pass reads.
			List<String> refusals = chain.chain().refusals();
			if (!refusals.isEmpty()) {
				disabled = true;
				refusals.forEach(refusal -> Vitrail.logger().error("{}", refusal));
				Vitrail.logger().error("{} cannot be drawn as it stands, nothing will be drawn",
						chain.packName());
				return;
			}

			active = new PackChain(chain, seed == null || !seed.isBoolean() || seed.asBoolean());
		} catch (IOException | RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail could not prepare a pack's chain", e);
		}
	}

	/** A reserved line read as text, whichever of the two shapes a value happens to have taken. */
	private static String textOf(OptionValue value) {
		if (value == null || value.isBoolean()) {
			return "";
		}

		return value.text();
	}

	/**
	 * A line of {@code passes=} is a count, a list of names, or a word. Anything else keeps the
	 * whole chain and is said so, because a typo that silently draws something else is exactly
	 * what this line exists to rule out.
	 */
	private static ChainFilter filterOf(OptionValue value) {
		if (value == null) {
			return ChainFilter.ALL;
		}

		// off means none of them, which is the final alone, and on means the lot. A count and a
		// list of names go through untouched.
		String text = value.isBoolean() ? (value.asBoolean() ? "" : "0") : value.text();
		ChainFilter filter = ChainFilter.parse(text);
		if (filter == ChainFilter.ALL && !text.isBlank()) {
			Vitrail.logger().warn("'{}={}' is neither a count nor a list of program names, so the "
					+ "whole chain runs", PASSES_KEY, text);
		} else if (filter != ChainFilter.ALL) {
			Vitrail.logger().info("Running only part of the chain, {}={}", PASSES_KEY, text);
		}

		return filter;
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
	 * This exists to make the chain provable. A pack's own passes are often nearly identities with
	 * their settings at their defaults, which looks exactly like a pass that never ran; turning one
	 * of the pack's own features on settles it without touching the pack or writing a test shader
	 * that proves only itself. It is also the only way to move the ping pong: switching a pass on
	 * changes the half every pass after it reads, and nothing else in the engine can do that.
	 */
	private static Map<String, OptionValue> settings(Path gameDirectory) throws IOException {
		Path file = optionsFile(gameDirectory);
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

		// Said by the caller and not here: the three reserved lines are taken out first, and a line
		// naming them among the pack's own settings would be naming something the pack never had.
		return chosen;
	}

	private static Path optionsFile(Path gameDirectory) {
		return gameDirectory.resolve(Vitrail.MOD_ID).resolve("options.txt");
	}

	/**
	 * Rebuilds everything when {@code pack.txt} or {@code options.txt} changes on disk, looked at
	 * once a second at most.
	 * <p>
	 * Watching the files rather than binding a key is not laziness. Forcing a pack's own setting
	 * turned out to be the only honest way to prove a pass does what it should, so it is done
	 * constantly, and a restart between attempts costs a minute every time. The price is the
	 * second a chain takes to read and translate, which shows as a hitch and is the right trade.
	 */
	private static void reloadIfChanged(Path gameDirectory) {
		long now = System.nanoTime();
		if (now - lastCheckNanos < 1_000_000_000L) {
			return;
		}

		lastCheckNanos = now;
		long stamp = stampOf(gameDirectory.resolve(Vitrail.MOD_ID).resolve("pack.txt"))
				+ stampOf(optionsFile(gameDirectory));
		boolean first = lastStamp == 0L && !checked;
		checked = true;
		if (stamp == lastStamp || first) {
			lastStamp = stamp;
			return;
		}

		lastStamp = stamp;
		Vitrail.logger().info("Settings changed on disk, reloading the pack");

		PackChain previous = active;
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

		PackChain chain = active;
		if (disabled || chain == null) {
			return false;
		}

		try {
			chain.run();
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail stopped drawing this pack after an error", e);
			chain.release();
		}

		return true;
	}

	/** Called when the client shuts down, while the device is still alive. */
	public static void close() {
		PackChain chain = active;
		if (chain != null) {
			chain.release();
		}
	}

	private void run() {
		GpuDevice device = RenderSystem.tryGetDevice();
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();
		if (device == null || main == null || main.getColorTexture() == null) {
			return;
		}

		if (this.programs == null) {
			build(device);
		}

		// One pipeline a frame at most, and nothing is drawn until every one of them is ready: the
		// game keeps its own image for the handful of frames that takes, which is a fade rather
		// than the three second freeze compiling nine programs at once would be, and that freeze
		// would be paid again at every resource reload.
		if (!warm(device)) {
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

		// Every frame here as well, and for the same reason as the pipelines of the chain.
		boolean seeding = this.seed != null && this.seedEnabled && this.seed.prepare(device);

		announce(main, seeding);
		writeBlocks(main);

		// One encoder for the whole frame. A second one would be a fresh wrapper carrying its own
		// idea of whether a pass is open, which is the guard that keeps allocations out of one.
		CommandEncoder encoder = device.createCommandEncoder();
		this.targets.clear(encoder);

		// Where the world would have been drawn, which the plan works out and this only reads. A
		// begin or a prepare runs ahead of it and the schedule gave the seed its half on that
		// footing, so painting it first would put this frame's world under a pass the walk says
		// reads a clear colour, and over one the walk says writes after it.
		int seedAt = seeding ? this.chain.chain().seed().map(ChainPlan.Seed::at).orElse(-1) : -1;

		// Each pass opens and closes its own render pass. Closing one is what makes the next able
		// to read it: the Vulkan backend ends a pass with a full memory barrier, so the cost of
		// the chain is one whole serialisation of the GPU per program and there is no way around
		// it short of knowing which passes do not overlap.
		GpuBuffer buffer = this.block.currentBuffer();
		for (int at = 0; at < this.programs.size(); at++) {
			if (at == seedAt) {
				drawSeed(encoder, mainView);
			}

			PackPass pass = this.programs.get(at);
			GpuBufferSlice uniforms = buffer.slice(pass.uniformOffset(), pass.uniformSize());
			if (pass == this.last) {
				pass.drawFinal(encoder, mainView, this.targets, depthView, this.quad, uniforms);
			} else {
				pass.draw(encoder, this.targets, depthView, this.quad, uniforms, main.width, main.height);
			}
		}

		// A place whose whole chain runs before the world still paints it, once everything has run.
		if (seedAt >= this.programs.size()) {
			drawSeed(encoder, mainView);
		}

		// Outside any pass, and after the last one. Only the targets the pack keeps between frames
		// and that the chain left on the far half are copied: the next frame walks from an empty
		// flipped set and would otherwise be handed what was written two frames ago.
		this.targets.swapBack(encoder, this.chain.chain().swapBack());

		this.block.rotate();
	}

	/**
	 * Paints the game's finished frame where the world would have gone. After the clears and never
	 * before, or the clear would throw the scene away, and on the half the geometry program it
	 * stands in for would have written.
	 */
	private void drawSeed(CommandEncoder encoder, GpuTextureView mainView) {
		this.seed.draw(encoder, this.quad, mainView,
				this.targets.view(this.seed.target(), this.seed.side()));
	}

	/**
	 * Lays every program's uniform block out in one buffer and builds the passes.
	 * <p>
	 * Done here rather than in the constructor because the offsets have to be rounded to what the
	 * device asks for, and the constructor runs off the render thread. One ring buffer and not N:
	 * each one costs a fence and a wait of its own per frame.
	 */
	private void build(GpuDevice device) {
		int alignment = Math.max(16, device.getDeviceInfo().limits().minUniformOffsetAlignment());
		ChainPlan plan = this.chain.chain();
		List<PackPass> built = new ArrayList<>();
		int offset = 0;

		for (ChainPlan.Pass pass : ordered(plan)) {
			PackProgram.Loaded loaded = this.chain.programs().get(pass.program());
			if (loaded == null) {
				// The plan and the programs come out of one reading of the pack, so this is the
				// pack disagreeing with itself rather than anything a pack can cause.
				throw new IllegalStateException(pass.program() + " is in the chain of "
						+ this.chain.packName() + " and was never translated");
			}

			built.add(new PackPass(this.chain.place(), pass.program(), loaded, pass, this.targets,
					this.load, offset));
			offset += Mth.roundToward(PackPass.uniformSizeOf(loaded), alignment);
		}

		this.programs = List.copyOf(built);
		this.last = built.isEmpty() ? null : built.get(built.size() - 1);
		this.blockBytes = Math.max(alignment, offset);
	}

	/** The chain in frame order, the final last, which is the order everything downstream keeps. */
	private static List<ChainPlan.Pass> ordered(ChainPlan plan) {
		List<ChainPlan.Pass> all = new ArrayList<>(plan.passes());
		plan.last().ifPresent(all::add);

		return all;
	}

	/**
	 * Compiles at most one program a frame, and says whether the chain may be drawn.
	 * <p>
	 * The first program is asked for every frame whatever happens, and its compiled form is
	 * compared with the one from the frame before. That is the only way to notice that the device
	 * emptied its cache, which it does at every resource reload: the alternative is to ask for all
	 * of them every frame, which pays the whole compilation in the one frame after a reload.
	 *
	 * @return false while a program is still missing, in which case the game keeps its own image
	 */
	private boolean warm(GpuDevice device) {
		if (this.programs.isEmpty()) {
			return false;
		}

		CompiledRenderPipeline first = this.programs.get(0).compile(device);
		if (!valid(first, this.programs.get(0))) {
			return false;
		}

		if (first != this.head) {
			this.head = first;
			this.warmed = 1;

			return this.warmed == this.programs.size();
		}

		if (this.warmed < this.programs.size()) {
			PackPass pass = this.programs.get(this.warmed);
			if (!valid(pass.compile(device), pass)) {
				return false;
			}

			this.warmed++;
		}

		return this.warmed == this.programs.size();
	}

	private static boolean valid(CompiledRenderPipeline compiled, PackPass pass) {
		if (compiled.isValid()) {
			return true;
		}

		disabled = true;
		Vitrail.logger().error("{} did not compile, nothing of this pack will be drawn", pass.path());

		return false;
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
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, this.blockBytes);
		}

		// Compared against the window every frame rather than driven by an event: the resize event
		// fires too early, fires again when only the interface scale moved, and the panorama
		// capture takes the main target to 4096 without going through the game's resize at all.
		return this.targets.ensure(main.width, main.height);
	}

	/**
	 * Fills every program's block in one mapping. A builder aligns from where it was handed the
	 * buffer, so each block is written at its own offset and measured as though it started there.
	 */
	private void writeBlocks(RenderTarget main) {
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
			ByteBuffer data = view.data();
			for (PackPass pass : this.programs) {
				data.position(pass.uniformOffset());
				pass.write(Std140Builder.intoBuffer(data), frame);
			}
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
		TargetPlan plan = this.chain.targets();
		ChainPlan unfolded = this.chain.chain();

		Vitrail.logger().info("Drawing {} from {} at {}x{}, {} full screen passes before the final",
				this.chain.packName(), place(), main.width, main.height, unfolded.passes().size());

		for (PackPass pass : this.programs) {
			Vitrail.logger().info("{}", pass.describe());
		}

		// Already whole sentences, and already the pack's own words where it has any: promotions,
		// sizes the pack asked for, the passes the pack keeps off, mipmaps nothing can generate,
		// and what this engine will not do.
		plan.notes().forEach(note -> Vitrail.logger().info("{}", note));
		this.targets.notes().forEach(note -> Vitrail.logger().warn("{}", note));

		announceSeed(seeding);
		announceResting(seeding);

		List<Integer> back = unfolded.swapBack();
		if (!back.isEmpty()) {
			Vitrail.logger().info("{} targets are copied back from their far half at the end of every "
					+ "frame, because the pack keeps them and the chain left them there: {}",
					back.size(), back);
		}

		// What the picture will be wrong about, in the pack's own terms and naming the pass that
		// reads it. This is the list that has to be read before the image is, or the image gets
		// read as though the chain were complete.
		unfolded.notes().forEach(note -> Vitrail.logger().warn("{}", note));
		this.programs.forEach(pass -> pass.notes().forEach(note -> Vitrail.logger().warn("{}", note)));

		announceSamplers();
	}

	private void announceSeed(boolean seeding) {
		Optional<ChainPlan.Seed> where = this.chain.chain().seed();
		if (seeding && where.isPresent()) {
			Vitrail.logger().info("{} carries the game's finished frame, drawn in for {}, which does "
					+ "not run yet, so it is already tone mapped and already holds the translucents, "
					+ "the weather and the hand", TargetName.canonical(where.get().target()),
					where.get().from());
			// The number is worth printing on its own: it is the whole difference between a begin
			// that reads the world of this frame and one that reads what the clear left.
			Vitrail.logger().info("It is painted where the world would be drawn, after {} passes of "
					+ "the chain", where.get().at());
		} else if (where.isEmpty()) {
			Vitrail.logger().info("Nothing carries the game's frame in {}, so every program of the "
					+ "chain starts from a clear colour", place());
		} else if (!this.seedEnabled) {
			Vitrail.logger().info("The scene seed is off, {} holds its clear colour as well",
					TargetName.canonical(where.get().target()));
		}
	}

	private void announceResting(boolean seeding) {
		Set<Integer> filled = new TreeSet<>();
		if (seeding) {
			this.chain.chain().seed().ifPresent(where -> filled.add(where.target()));
		}

		this.chain.chain().passes().forEach(pass -> filled.addAll(pass.targets()));

		List<String> resting = this.chain.targets().ordered().stream()
				.filter(index -> !filled.contains(index))
				.map(TargetName::canonical)
				.toList();
		if (!resting.isEmpty()) {
			Vitrail.logger().info(
					"No pass of this chain writes these, so they hold nothing but their clear colour: {}",
					resting);
		}
	}

	/**
	 * Once for the whole chain rather than once per program. Nine programs declaring much the same
	 * samplers would say the same five things nine times, and the thing worth reading is which
	 * names the engine has no answer for at all.
	 */
	private void announceSamplers() {
		Map<SamplerPlan.Kind, Set<String>> byKind = new EnumMap<>(SamplerPlan.Kind.class);
		for (PackProgram.Loaded loaded : this.chain.programs().values()) {
			loaded.samplers().byKind().forEach((kind, names) ->
					byKind.computeIfAbsent(kind, ignored -> new LinkedHashSet<>()).addAll(names));
		}

		named(byKind, SamplerPlan.Kind.COLORTEX, "read a real colour target");
		named(byKind, SamplerPlan.Kind.DEPTH, "read the world's depth");
		named(byKind, SamplerPlan.Kind.SHADOW_DEPTH, "read white, no shadow map is drawn yet");
		named(byKind, SamplerPlan.Kind.SHADOW_COLOUR, "read white, no shadow map is drawn yet");
		named(byKind, SamplerPlan.Kind.NOISE, "read mid grey, no noise texture is built yet");

		// The two copies of the depth taken before the translucents and before the hand cannot be
		// made from a hook that fires once the world is finished, so both read the final depth.
		List<String> copies = byKind.getOrDefault(SamplerPlan.Kind.DEPTH, Set.of()).stream()
				.filter(name -> name.equals("depthtex1") || name.equals("depthtex2"))
				.toList();
		if (!copies.isEmpty()) {
			Vitrail.logger().info("{} read the finished depth rather than the copies taken before "
					+ "the translucents and before the hand", copies);
		}
	}

	private static void named(Map<SamplerPlan.Kind, Set<String>> byKind, SamplerPlan.Kind kind,
			String what) {
		Set<String> names = byKind.getOrDefault(kind, Set.of());
		if (!names.isEmpty()) {
			Vitrail.logger().info("{} samplers this chain {}: {}", names.size(), what, names);
		}
	}

	private String place() {
		return this.chain.place().isEmpty() ? "the root" : this.chain.place();
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

		// The one thing that cannot be handed back. A compiled pipeline lives in a cache keyed by
		// the pipeline object, and the only way to remove one is to empty the whole cache, which
		// waits for the queue to go idle and destroys the game's own pipelines with ours. Doing
		// that from here would do it in the middle of a frame whose commands are already recorded
		// against them. The reload's cost is therefore named and left: it is a few hundred
		// kilobytes of SPIR-V a reload, against the hundred megabytes of targets freed above, and
		// the next resource reload clears it.
		if (this.programs != null && !this.programs.isEmpty()) {
			Vitrail.logger().info("{} pipelines and {} shader modules of load {} stay in the device "
					+ "cache until the next resource reload", this.programs.size(),
					2 * this.programs.size(), this.load);
		}
	}
}
