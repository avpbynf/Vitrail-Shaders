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
import dev.vitrail.settings.PackSession;
import dev.vitrail.settings.SettingsFile;
import dev.vitrail.settings.SettingsLayers;

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

	/**
	 * The line in options.txt that turns the scene seed off. Reserved like {@code profile}, and
	 * for the same reason: no pack declares a setting under either name. Turning it off leaves the
	 * seeded target holding its clear colour, which is what proves the clears work on their own.
	 */
	private static final String SEED_KEY = "seed";

	/**
	 * The line in options.txt that cuts the chain down: {@code passes=0}, {@code passes=6}, or
	 * {@code passes=composite4,composite5}. Reserved for the same reason as the others.
	 * <p>
	 * This is how a broken picture is bisected, and it is also the only honest way to price the
	 * chain: {@code passes=0} is the final alone, which is the image every earlier milestone was
	 * measured on. The schedule is rebuilt on what it leaves, never trimmed afterwards.
	 */
	private static final String PASSES_KEY = "passes";

	/**
	 * The line in options.txt that says which of its two views the settings screen opens on. Also
	 * reserved, and read here rather than by the screen so that all four are taken out of the pack's
	 * settings in one place: a name left in would be written into the head of every unit as
	 * {@code #define screen settings}, which is a plausible identifier in someone's GLSL.
	 */
	private static final String SCREEN_KEY = "screen";

	/** The two words that line takes, the first being what it does when the line is missing. */
	private static final String ON_PACKS = "packs";
	private static final String ON_SETTINGS = "settings";

	/**
	 * The four lines of options.txt that name what this mod does rather than what the pack declares,
	 * kept together for the one place that has to tell them apart: the log that says what the file
	 * forces. {@code profile} is the settings layer's own, since that is the side that writes it
	 * back; the other three are read here and nowhere else.
	 */
	private static final Set<String> RESERVED =
			Set.of(SettingsFile.PROFILE_KEY, SEED_KEY, PASSES_KEY, SCREEN_KEY);

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

	private static final Supplier<String> BLOCK_LABEL = () -> "Vitrail OfGlobals";
	private static final Supplier<String> QUAD_LABEL = () -> "Vitrail quad";

	private static volatile PackChain active;
	private static volatile boolean disabled;
	private static long lastCheckNanos;
	private static long lastStamp;
	private static boolean checked;
	private static volatile PackSession session;
	private static volatile String lastError;
	private static volatile Path settingsFile;
	private static volatile boolean packsFirst = true;

	private final PackProgram.Chain chain;
	private final PackValues values;
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
	private boolean announced;

	private PackChain(PackProgram.Chain chain, PackValues values, boolean seedEnabled) {
		this.chain = chain;
		this.values = values;
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
		session = null;
		settingsFile = null;
		lastError = null;
		packsFirst = true;
		try {
			List<Path> packs = PackLoader.candidates(gameDirectory);
			if (packs.isEmpty()) {
				lastError = "No shader pack in " + PackLoader.directory(gameDirectory);
				Vitrail.logger().info("No shader pack in {}, nothing to draw",
						PackLoader.directory(gameDirectory));
				return;
			}

			Path pack = choose(gameDirectory, packs);
			SettingsLayers.Resolved settings = open(gameDirectory, pack);
			Map<String, OptionValue> chosen = new LinkedHashMap<>(settings.chosen());
			// Reserved keys rather than options: no pack declares a setting under any of these
			// names, and each names something this mod does rather than a value the pack has. The
			// fourth, profile, never reaches here: the settings layer takes it out and carries it
			// apart, because it is the side that writes it back into the pack's own file.
			OptionValue seed = chosen.remove(SEED_KEY);
			ChainFilter filter = filterOf(chosen.remove(PASSES_KEY));
			packsFirst = packsFirst(chosen.remove(SCREEN_KEY));

			// Before the translation and not after: this is what installs the machine's own
			// symbols, and the biome ones among them decide which branch of the pack compiles.
			PackValues values = PackValues.read(pack, OVERWORLD, chosen, settings.profile());

			long began = System.nanoTime();
			Optional<PackProgram.Chain> read = PackProgram.loadChain(pack, OVERWORLD, chosen,
					settings.profile(), filter);
			if (read.isEmpty()) {
				lastError = pack.getFileName() + " serves no final with both stages, in " + OVERWORLD
						+ " or at its root";
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
				// The first one, in the pack's own terms, so that the screen says why nothing is
				// drawn rather than sending the reader to the log for all of them.
				lastError = chain.packName() + " cannot be drawn as it stands: " + refusals.get(0);
				Vitrail.logger().error("{} cannot be drawn as it stands, nothing will be drawn",
						chain.packName());
				return;
			}

			active = new PackChain(chain, values,
					seed == null || !seed.isBoolean() || seed.asBoolean());
		} catch (IOException | RuntimeException e) {
			disabled = true;
			lastError = "Could not prepare this pack: " + e;
			Vitrail.logger().error("Vitrail could not prepare a pack's chain", e);
		}
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
	 * Which of its two views the settings screen opens on. A word that is neither opens the pack
	 * list and says so, rather than quietly opening the other one.
	 */
	private static boolean packsFirst(OptionValue value) {
		if (value == null) {
			return true;
		}

		// asText rather than text: a line written screen=on is a boolean, whose text is null.
		String text = value.asText().trim().toLowerCase(Locale.ROOT);
		if (ON_SETTINGS.equals(text)) {
			return false;
		}

		if (!ON_PACKS.equals(text)) {
			Vitrail.logger().warn("'{}={}' is neither {} nor {}, so the settings screen opens on the "
					+ "pack list", SCREEN_KEY, value.asText(), ON_PACKS, ON_SETTINGS);
		}

		return true;
	}

	/**
	 * Which pack to draw. A line in {@code vitrail/pack.txt} naming one, or any part of one, wins;
	 * otherwise the first in the folder does.
	 * <p>
	 * A text file is not a settings screen and is not meant to become one. It exists because
	 * eight packs sit in that folder and switching between them is most of the work of supplying
	 * the values they read, so needing to rename files to do it would be a tax on every attempt.
	 * <p>
	 * The whole name is tried before the fragment. Two packs of a folder can have one name inside
	 * the other, a version next to the version it replaces being the ordinary way that happens, and
	 * on a fragment the shorter one would answer for both: the settings screen writes the whole
	 * name for that reason and would otherwise be unable to reach the longer one at all.
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
			if (pack.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted)) {
				return pack;
			}
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
	 * Everything a pack is configured by, read in one go: its own file under
	 * {@code vitrail/settings/}, then {@code vitrail/options.txt} forced over it.
	 * <p>
	 * The reading is published as a {@link PackSession} before anything is translated. A screen can
	 * then be opened on a pack whose GLSL does not compile and used to repair it, and what that
	 * screen shows is what the image was built from rather than a second reading of the same files
	 * that could disagree with it.
	 * <p>
	 * The top layer stays a file edited by hand, and it wins over the screen rather than the other
	 * way round, because it is the only way to move the ping pong: switching one of the pack's own
	 * passes on changes the half every pass after it reads, and nothing else in the engine can do
	 * that.
	 */
	private static SettingsLayers.Resolved open(Path gameDirectory, Path pack) throws IOException {
		Minecraft minecraft = Minecraft.getInstance();
		PackSession opened = PackSession.read(gameDirectory, pack,
				minecraft == null ? "en_us" : minecraft.options.languageCode);
		session = opened;
		settingsFile = opened.settingsFile();

		Vitrail.logger().info("{} lays out {} settings pages and {} settings, named by {}",
				opened.packFileName(), opened.menu().pages().size(), opened.menu().optionCount(),
				opened.menu().lang().file().isEmpty()
						? "nothing, so its own identifiers are shown"
						: opened.menu().lang().file());

		// Named rather than counted: each one is a name the pack forgot to declare or a page it
		// forgot to write, which is one line to fix in the pack and nothing we can do about here.
		List<String> unshown = opened.menu().warnings();
		if (!unshown.isEmpty()) {
			Vitrail.logger().warn("{} entries of this pack's menu name nothing it has, and are shown"
					+ " blank or greyed: {}", unshown.size(), unshown);
		}

		if (!opened.readFrom().equals(opened.settingsFile())) {
			Vitrail.logger().info("Reading the settings Iris left for this pack in {}, which is read"
					+ " and never written back", opened.readFrom());
		}

		List<String> stale = opened.stale();
		if (!stale.isEmpty()) {
			Vitrail.logger().info("{} settings in {} name nothing {} shows and are kept as they are:"
					+ " {}", stale.size(), opened.readFrom(), opened.packFileName(), stale);
		}

		announceForced(gameDirectory, opened);

		return opened.settings();
	}

	/**
	 * What {@code options.txt} holds, split in two. The reserved lines are counted apart rather than
	 * with the rest: a line calling {@code passes} a setting of the pack would send whoever reads
	 * the log looking through the pack for a setting it never had.
	 */
	private static void announceForced(Path gameDirectory, PackSession opened) {
		List<String> settings = opened.forced().keySet().stream()
				.filter(name -> !RESERVED.contains(name))
				.toList();
		List<String> reserved = opened.forced().keySet().stream()
				.filter(RESERVED::contains)
				.toList();

		if (!settings.isEmpty()) {
			Vitrail.logger().info("Forcing {} pack settings from {}: {}", settings.size(),
					SettingsLayers.file(gameDirectory), settings);
		}

		if (!reserved.isEmpty()) {
			Vitrail.logger().info("{} lines of {} name this engine rather than a setting of the "
					+ "pack: {}", reserved.size(), SettingsLayers.file(gameDirectory), reserved);
		}
	}

	/** The pack being drawn, if there is one, with everything a screen needs to show it. */
	public static Optional<PackSession> session() {
		return Optional.ofNullable(session);
	}

	/**
	 * Why the last load failed, for a screen to show rather than for the log to swallow.
	 * <p>
	 * A pack that read and translated and only then failed to compile leaves nothing behind but the
	 * flag that stopped it being drawn, so that case is reported as itself rather than as nothing at
	 * all.
	 */
	public static Optional<String> lastError() {
		String error = lastError;
		if (error == null && disabled) {
			error = "This pack stopped drawing after an error, see the log";
		}

		return Optional.ofNullable(error);
	}

	/**
	 * Whether the settings screen opens on the pack list rather than on the loaded pack's settings.
	 * <p>
	 * True until a pack has been read, and true again whenever one is read without
	 * {@code screen=settings} in the file. The list is the view that has something to say when
	 * nothing is loaded, so it is also the answer when the file was never reached.
	 */
	public static boolean opensOnPacks() {
		return packsFirst;
	}

	/**
	 * Rebuilds everything when {@code pack.txt}, {@code options.txt} or the loaded pack's own
	 * settings file changes on disk, looked at once a second at most.
	 * <p>
	 * Watching the files rather than binding a key is not laziness. Forcing a pack's own setting
	 * turned out to be the only honest way to prove a pass does what it should, so it is done
	 * constantly, and a restart between attempts costs a minute every time. The price is the
	 * second a chain takes to read and translate, which shows as a hitch and is the right trade.
	 * <p>
	 * It keeps running while the settings screen is open, which is the point: that is exactly when
	 * one wants to see the world change under it. A file edited by hand and a value clicked in the
	 * screen then compose, because the screen holds what it has pending rather than a copy of the
	 * file.
	 */
	private static void reloadIfChanged(Path gameDirectory) {
		long now = System.nanoTime();
		if (now - lastCheckNanos < 1_000_000_000L) {
			return;
		}

		lastCheckNanos = now;
		long stamp = stamp(gameDirectory);
		boolean first = lastStamp == 0L && !checked;
		checked = true;
		// A stale machine is not a first sighting and is never skipped. The pack was read while
		// the client was starting up, so it had no biome symbol to compile against and took the
		// branch meant for an engine that cannot answer them; joining a world is what makes those
		// symbols exist, and it happens after the first look at these files.
		boolean stale = PackDefines.stale();
		if ((stamp == lastStamp || first) && !stale) {
			lastStamp = stamp;
			return;
		}

		Vitrail.logger().info(stale
				? "The world's own symbols are known now, reloading the pack against them"
				: "Settings changed on disk, reloading the pack");
		reload(gameDirectory);
	}

	/**
	 * Throws away the current chain and reads it again from disk, then resynchronises the watcher
	 * above on what is now on disk, or it would reload a second time within the second.
	 * <p>
	 * Render thread only: {@link #release()} closes GPU buffers and hands back the colour targets,
	 * which has to happen where {@code draw} runs and outside any render pass. The settings screen
	 * calls this from a button; the watcher calls it when a file changes. Both go through here
	 * rather than each having a path of its own, so that what the screen applies and what a hand
	 * edit applies cannot drift apart.
	 */
	public static void reload(Path gameDirectory) {
		PackChain previous = active;
		if (previous != null) {
			previous.release();
		}

		active = null;
		// Cleared as well, so that a pack that failed to compile can be fixed and tried again
		// without leaving the game.
		disabled = false;
		load(gameDirectory);
		// A load that gave up before reading a pack settled nothing, and without this the folder
		// with no pack in it would be looked at again a second later, and every second after that.
		PackDefines.settle();

		lastStamp = stamp(gameDirectory);
		checked = true;
	}

	/**
	 * The three files a reload watches, folded in order rather than added up. A sum of two
	 * timestamps could already be cancelled out by an edit to each; with three it would become a
	 * reason for a reload not to happen that nobody would ever find.
	 */
	private static long stamp(Path gameDirectory) {
		long pack = stampOf(gameDirectory.resolve(Vitrail.MOD_ID).resolve("pack.txt"));
		long options = stampOf(SettingsLayers.file(gameDirectory));
		long settings = settingsFile == null ? 0L : stampOf(settingsFile);

		return 31L * (31L * pack + options) + settings;
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
		writeBlocks();

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
					this.values, this.load, offset));
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
	private void writeBlocks() {
		// The one point in the frame where the clocks, the counters and the previous frame's
		// matrices move, and it is before the first block is written on purpose: two passes of one
		// frame have to be handed the same numbers, or the second one silently reprojects against
		// itself and a smooth() of the pack fades at twice the speed.
		this.values.advance();

		try (GpuBufferSlice.MappedView view = this.block.currentBuffer().map(false, true)) {
			ByteBuffer data = view.data();
			for (PackPass pass : this.programs) {
				data.position(pass.uniformOffset());
				pass.write(Std140Builder.intoBuffer(data), this.values.world());
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

		// The values the pack declares for itself, once for the whole chain: every program was
		// built against the one catalogue, so a pass saying it would say it once per program.
		this.values.notes().forEach(note -> Vitrail.logger().info("{}", note));

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
