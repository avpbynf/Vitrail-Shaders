package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.ChainPlan;
import dev.vitrail.pack.OptionValue;
import dev.vitrail.pack.PackLoader;
import dev.vitrail.pack.SamplerPlan;
import dev.vitrail.pack.TargetName;
import dev.vitrail.pack.TargetPlan;
import dev.vitrail.settings.PackSession;
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
	private static volatile List<String> removed = List.of();
	private static volatile Path settingsFile;
	private static volatile boolean packsFirst = true;
	private static volatile boolean chainWanted = true;

	/**
	 * Whether this frame's values have been moved on yet.
	 * <p>
	 * The frame used to begin where the chain draws, which is after the world. A terrain program runs
	 * during the world and reads the same block, so whichever of the two comes first opens the frame
	 * and {@link #draw} closes it. Two advances in one frame would shift the previous frame's
	 * matrices twice and make every {@code smooth()} in the pack fade at twice the speed, with
	 * nothing on screen to say so.
	 */
	private static boolean advanced;

	/**
	 * Whether this frame's colour targets have been allocated and cleared yet. Held beside
	 * {@link #advanced} and reset with it: the two answer the same question, "has this frame been
	 * opened", for the values and for the targets.
	 */
	private boolean opened;

	/** Whether the half of the chain that belongs before the world's translucents has run. */
	private boolean early;

	/** Whether this frame's uniform blocks have been written and its notes said. */
	private boolean filled;

	/** Whether the split of the chain into two halves has been said. Once a load, not once a frame. */
	private boolean split;

	private final PackProgram.Chain chain;
	private final PackValues values;
	private final String world;
	private final ColorTargets targets;
	private final SceneSeed seed;
	private final boolean seedEnabled;
	private final int load;
	private final TerrainDraw terrain;

	private List<PackPass> programs;
	private PackPass last;
	private MappableRingBuffer block;
	private GpuBuffer quad;
	private CompiledRenderPipeline head;
	private int blockBytes;
	private int warmed;
	private boolean announced;

	private PackChain(PackProgram.Chain chain, PackValues values, String world, boolean seedEnabled,
			Path packPath, Map<String, OptionValue> chosen, String profile) {
		this.chain = chain;
		this.values = values;
		this.world = world;
		this.seedEnabled = seedEnabled;
		this.load = LOADS.incrementAndGet();

		// None of this touches the device: the textures are allocated by the first frame and this
		// runs while the client is still starting up, off the render thread.
		this.targets = new ColorTargets(chain.targets(), values.noiseResolution());
		this.seed = chain.chain().seed()
				.filter(where -> this.targets.has(where.target()))
				.map(where -> new SceneSeed(where, this.targets.format(where.target())))
				.orElse(null);
		// Held rather than read: the terrain program is compiled against the chunk mesh format, and
		// nothing knows that format until the renderer asks for its shader.
		//
		// It is handed THIS chain's plan and schedule and never ones of its own, which would be
		// built without the user's pass filter. The half a program writes comes from the schedule,
		// and a schedule walked over a different set of passes hands out a different parity: the
		// terrain would then write one half while the chain read the other, and neither side would
		// say a word. Whether the chain runs travels with them, because a translucent pass that
		// takes draw buffer nought for the pack is drawing for a final: without one, the water
		// would leave the screen and reach nothing.
		this.terrain = new TerrainDraw(this, packPath, chain.place(), chosen, profile, values,
				this.load, chain.chain(), chain.targets(), chainWanted, this.targets);
	}

	/**
	 * Reads the chosen pack and translates every program of its chain. Runs while the client
	 * starts up, off the render thread, so it touches files and nothing else.
	 */
	public static void load(Path gameDirectory) {
		session = null;
		settingsFile = null;
		lastError = null;
		removed = List.of();
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
			EngineOptions.Read engine = EngineOptions.take(chosen);
			packsFirst = engine.packsFirst();
			chainWanted = engine.chain();
			TerrainDraw.wanted(engine.terrain());
			PackDump.configure(engine.dump(),
					gameDirectory.resolve(Vitrail.MOD_ID).resolve("dump.txt"));

			// The world decides the directory, and the pack decides which world that is: a folder
			// may be named anything and mapped in dimension.properties, so the name is read from
			// the pack rather than composed from the dimension.
			String place = PackPlace.place(pack);
			String world = PackPlace.world();

			// Before the translation and not after: this is what installs the machine's own
			// symbols, and the biome ones among them decide which branch of the pack compiles.
			PackValues values = PackValues.read(pack, place, chosen, settings.profile());

			long began = System.nanoTime();
			Optional<PackProgram.Chain> read = PackProgram.loadChain(pack, place, chosen,
					settings.profile(), engine.passes());
			if (read.isEmpty()) {
				String where = place.isEmpty() ? "at its root" : "in " + place + " or at its root";
				lastError = pack.getFileName() + " serves no final with both stages " + where;
				Vitrail.logger().warn("{} serves no final with both stages {}, nothing to draw",
						pack.getFileName(), where);
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

			announceRemoved(chain);
			active = new PackChain(chain, values, world, engine.seed(), pack, chosen,
					settings.profile());
			if (!chainWanted) {
				EngineOptions.announceChainOff();
			}
		} catch (IOException | RuntimeException e) {
			disabled = true;
			lastError = "Could not prepare this pack: " + e;
			Vitrail.logger().error("Vitrail could not prepare a pack's chain", e);
		}
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

		EngineOptions.announceForced(gameDirectory, opened);

		return opened.settings();
	}


	/**
	 * The passes no pipeline could have been built for, said once, with the type spelled out.
	 * <p>
	 * What each one costs the picture is deliberately not repeated here. The plan has already walked
	 * the frame without them and names, target by target, every half that is now read before
	 * anything writes it; these lines exist so that the list of targets has a cause, and so that the
	 * cause is not a message from the SPIR-V compiler naming a sampler and no program.
	 */
	private static void announceRemoved(PackProgram.Chain chain) {
		Map<String, List<TranslatedUnit.Uniform>> refused = chain.removed();
		if (refused.isEmpty()) {
			return;
		}

		List<String> said = new ArrayList<>();
		refused.forEach((program, samplers) -> said.add(
				(chain.place().isEmpty() ? program : chain.place() + "/" + program)
						+ " is not run: it declares " + PackPass.describe(samplers)
						+ ", and a pipeline of this game carries two dimensional and cube samplers "
						+ "and nothing else"));

		removed = List.copyOf(said);
		said.forEach(line -> Vitrail.logger().warn("{}", line));
		Vitrail.logger().warn("{} passes of {} are out for that reason and the rest of the chain "
				+ "runs, rebuilt on what is left", refused.size(), chain.packName());
	}

	/** The pack being drawn, if there is one, with everything a screen needs to show it. */
	public static Optional<PackSession> session() {
		return Optional.ofNullable(session);
	}

	/**
	 * Passes this engine took out of the loaded chain, in whole sentences, for a screen to show
	 * beside the pack rather than for the log to keep to itself.
	 * <p>
	 * Empty is the ordinary answer, and it stays empty when the pack was refused outright: that case
	 * is {@link #lastError()}, since nothing at all is drawn and naming one pass would be the
	 * smaller half of the truth.
	 */
	public static List<String> removedPasses() {
		return removed;
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
		// Asked apart from the symbols above and not folded into them: the stamp those hang on is
		// the registry the level carries, and walking through a portal in single player leaves it
		// the very same object, so half of what a pack is would change under a chain that noticed
		// nothing.
		boolean moved = PackPlace.moved();
		if ((stamp == lastStamp || first) && !stale && !moved) {
			lastStamp = stamp;
			return;
		}

		if (moved) {
			Vitrail.logger().info("Left {} for {}: a dimension replaces the root rather than layering "
					+ "over it, so the whole pack is read, translated and its colour targets allocated "
					+ "again, which is the hitch at the portal", PackPlace.settled(), PackPlace.world());
		} else {
			Vitrail.logger().info(stale
					? "The world's own symbols are known now, reloading the pack against them"
					: "Settings changed on disk, reloading the pack");
		}

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
		// A load that gave up before reading a pack settled nothing, and without these the folder
		// with no pack in it would be looked at again a second later, and every second after that.
		// The world is taken with the symbols and for the same reason: a pack that cannot be read
		// at all must not be read again every second for as long as the player stays in the Nether.
		PackDefines.settle();
		PackPlace.settle();

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
			// The frame is closed whatever happened, and outside the try: a terrain program may have
			// opened it during the world even when nothing of the chain is drawn afterwards, and a
			// flag left standing would stop the values ever moving again, or the targets ever being
			// cleared again if this pack is turned back on.
			advanced = false;
			if (chain != null) {
				chain.closeFrame();
			}

			return false;
		}

		try {
			if (chainWanted) {
				chain.run();
			} else {
				chain.rotate();
			}
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail stopped drawing this pack after an error", e);
			chain.release();
		}

		advanced = false;
		chain.closeFrame();

		return chainWanted;
	}

	/**
	 * The loaded chain's terrain program, or null when there is nothing to draw with.
	 * <p>
	 * The one place {@link TerrainDraw} reaches the running chain from, so that which pack is loaded
	 * and whether it is still drawable have a single answer rather than two that could part company.
	 */
	static TerrainDraw terrain() {
		PackChain chain = active;

		return disabled || chain == null ? null : chain.terrain;
	}

	/**
	 * Opens the frame if nothing has yet, and takes the dump with it. The one point the frame
	 * boundary hangs off; see {@link #advanced} for what a second advance would cost.
	 */
	void beginFrame() {
		if (!advanced) {
			advanced = true;
			this.values.advance();
			PackDump.take(this.chain.place(), this.load, this.terrain.programs(),
					this.programs == null ? List.of() : this.programs, this.values.world());
		}
	}

	/**
	 * Allocates the colour targets if they are not there and clears them, once a frame, and answers
	 * whether they can be drawn into.
	 * <p>
	 * <strong>This has to happen before the world and not where the chain starts.</strong> The chain
	 * runs once the world is finished; the terrain writes its targets during it. Clearing where the
	 * chain starts would therefore throw away everything the terrain had just written, and it would
	 * do it silently, the targets reading exactly as they do when no geometry runs at all.
	 * <p>
	 * Called from both sides for that reason, whichever comes first: from the terrain, at the point
	 * the chunk renderer asks for its shader, which is the last moment before it opens a pass; and
	 * from the chain, for the frames and the configurations where no terrain runs at all. The second
	 * call is free.
	 */
	boolean openTargets(GpuDevice device) {
		if (this.opened) {
			return true;
		}

		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();
		if (main == null || !this.targets.ensure(main.width, main.height)) {
			return false;
		}

		this.opened = true;
		this.targets.clear(device.createCommandEncoder());

		return true;
	}

	/**
	 * Closes the frame. Every per frame flag is reset here and nowhere else, so that a frame that
	 * failed halfway leaves nothing standing: a flag left set stops the targets ever being cleared
	 * again, or the values ever moving again, and neither shows on screen as itself.
	 */
	private void closeFrame() {
		this.opened = false;
		this.early = false;
		this.filled = false;
	}

	/** Everything the end of a frame owes when the chain itself is not drawn. */
	private void rotate() {
		beginFrame();
		this.terrain.rotate();
	}

	/** Called when the client shuts down, while the device is still alive. */
	public static void close() {
		PackChain chain = active;
		if (chain != null) {
			chain.release();
		}
	}

	/**
	 * Everything a frame owes before any pass of the chain can be drawn, or null when it cannot be
	 * drawn at all. Cheap and idempotent, so both halves of the frame may ask.
	 */
	private Ready ready(GpuDevice device) {
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();
		if (main == null || main.getColorTexture() == null) {
			return null;
		}

		if (this.programs == null) {
			build(device);
		}

		// One pipeline a frame at most, and nothing is drawn until every one of them is ready: the
		// game keeps its own image for the handful of frames that takes, which is a fade rather
		// than the three second freeze compiling nine programs at once would be, and that freeze
		// would be paid again at every resource reload.
		//
		// Outside any render pass, both of them: creating a texture or a buffer records a barrier
		// into the very command buffer a pass would be recording into, and the clears refuse
		// outright while one is open.
		if (!warm(device) || !prepare(device, main)) {
			return null;
		}

		GpuTextureView mainView = main.getColorTextureView();
		if (mainView == null) {
			return null;
		}

		// Every frame here as well, and for the same reason as the pipelines of the chain.
		boolean seeding = this.seed != null && this.seedEnabled && this.seed.prepare(device);

		// Once a frame, whichever half asks first. The blocks above all: the passes of the early half
		// have already been recorded against this very buffer by the time the late half runs, and
		// mapping it again under them is a write to memory a command is holding, even when the bytes
		// that go back are the same.
		if (!this.filled) {
			this.filled = true;
			announce(main, seeding);
			writeBlocks();
		}

		openTargets(device);

		return new Ready(main, mainView, main.useDepth ? main.getDepthTextureView() : null, seeding);
	}

	/**
	 * Draws the passes of one half of the frame, in order, painting the seed where the plan puts it.
	 * <p>
	 * The two halves share one order and one parity: the range is a window onto the same list, so
	 * every pass still runs where the schedule put it and the ping pong is untouched. What changes
	 * is only the moment the commands are recorded.
	 */
	private void drawRange(GpuDevice device, Ready ready, int from, int to) {
		int seedAt = ready.seeding()
				? this.chain.chain().seed().map(ChainPlan.Seed::at).orElse(-1)
				: -1;

		// Each pass opens and closes its own render pass. Closing one is what makes the next able
		// to read it: the Vulkan backend ends a pass with a full memory barrier, so the cost of
		// the chain is one whole serialisation of the GPU per program and there is no way around
		// it short of knowing which passes do not overlap.
		CommandEncoder encoder = device.createCommandEncoder();
		GpuBuffer buffer = this.block.currentBuffer();
		for (int at = from; at < to; at++) {
			if (at == seedAt) {
				drawSeed(encoder, ready.mainView());
			}

			PackPass pass = this.programs.get(at);
			GpuBufferSlice uniforms = buffer.slice(pass.uniformOffset(), pass.uniformSize());
			if (pass == this.last) {
				pass.drawFinal(encoder, ready.mainView(), this.targets, ready.depthView(), this.quad,
						uniforms);
			} else {
				pass.draw(encoder, this.targets, ready.depthView(), this.quad, uniforms,
						ready.main().width, ready.main().height);
			}
		}

		// A place whose whole chain runs before the world still paints it, once everything has run.
		if (to >= this.programs.size() && seedAt >= this.programs.size()) {
			drawSeed(encoder, ready.mainView());
		}
	}

	/** What one frame of the chain is drawn against, settled once and read by both halves. */
	private record Ready(RenderTarget main, GpuTextureView mainView, GpuTextureView depthView,
			boolean seeding) {
	}

	/**
	 * The half of the chain that belongs before the world's translucents: the begins, the prepares,
	 * the scene seed and the whole deferred stage.
	 * <p>
	 * <strong>This is where the deferred stage really belongs, and it is not a refinement.</strong>
	 * The OptiFine frame runs shadow, prepare, opaque geometry, deferred, translucent geometry,
	 * composite, final, and packs are written against exactly that: BSL's {@code gbuffers_water}
	 * reads {@code gaux1}, which its own {@code deferred} writes, and discards every fragment where
	 * it reads nought. Run after the world, as the whole chain used to be, that read finds a clear
	 * colour and the water is thrown away in its entirety.
	 * <p>
	 * Called from the terrain, at the point the chunk renderer asks for the shader of its
	 * translucent pass, which is the last moment before it opens that pass. Called from
	 * {@link #run} as well, for the frames and the configurations where no terrain draws at all, and
	 * the second call is free.
	 */
	public static void drawBeforeTranslucents() {
		PackChain chain = active;
		GpuDevice device = RenderSystem.tryGetDevice();
		if (disabled || chain == null || device == null || !chainWanted) {
			return;
		}

		try {
			chain.drawEarly(device);
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail stopped drawing this pack after an error", e);
			chain.release();
		}
	}

	private void drawEarly(GpuDevice device) {
		if (this.early) {
			return;
		}

		Ready ready = ready(device);
		if (ready == null) {
			return;
		}

		this.early = true;

		// The depth of the opaque world, taken before anything translucent is drawn, which is what
		// the OptiFine model calls depthtex1. Iris takes the same copy at the same moment,
		// beginTranslucents, before running its deferreds; the deferreds read it too, and nothing
		// between here and the world's translucents writes the game's depth, so one copy serves the
		// whole rest of the frame.
		this.targets.copyDepth(device.createCommandEncoder(), ready.main().getDepthTexture());

		int end = deferredEnd();
		if (!this.split) {
			this.split = true;
			// Said once, because the alternative is a chain that silently runs entirely after the
			// world again: nothing on screen tells the two apart, and the pack that needs the split
			// is the one whose water disappears.
			Vitrail.logger().info("{} of this chain run before the world's translucents, {} after: {}",
					end, this.programs.size() - end,
					this.programs.stream().limit(end).map(PackPass::path).toList());
		}

		drawRange(device, ready, 0, end);
	}

	/**
	 * How many passes of the chain belong before the world's translucents. They are a prefix because
	 * the running order is the frame order, and the boundary is read off each program's own name
	 * rather than off a position: a stage boundary held as an index shifts the moment one program is
	 * refused, and it shifts in silence, every pass still running in the right order at the wrong
	 * moment.
	 */
	private int deferredEnd() {
		List<ChainPlan.Pass> planned = this.chain.chain().passes();
		int end = 0;
		while (end < planned.size() && planned.get(end).frameRank()
				<= ChainPlan.DEFERRED_RANK) {
			end++;
		}

		return Math.min(end, this.programs.size());
	}

	private void run() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}

		drawEarly(device);

		Ready ready = ready(device);
		if (ready == null) {
			return;
		}

		drawRange(device, ready, deferredEnd(), this.programs.size());

		RenderTarget main = ready.main();
		GpuTextureView mainView = ready.mainView();
		CommandEncoder encoder = device.createCommandEncoder();

		// Outside any pass, and after the last one. Only the targets the pack keeps between frames
		// and that the chain left on the far half are copied: the next frame walks from an empty
		// flipped set and would otherwise be handed what was written two frames ago.
		this.targets.swapBack(encoder, this.chain.chain().swapBack());

		this.block.rotate();
		this.terrain.rotate();
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
		// The clocks, the counters and the previous frame's matrices move at the frame boundary and
		// never here: two passes of one frame have to be handed the same numbers, or the second one
		// silently reprojects against itself and a smooth() of the pack fades at twice the speed.
		// A terrain program runs during the world, so it may already have opened this frame.
		beginFrame();

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

		// The world is printed beside the place because the fallback is silent otherwise: a pack
		// that serves no Nether draws it from the root, which is a line that reads as ordinary
		// until it is read next to the world it was chosen for.
		Vitrail.logger().info("Drawing {} from {} for {}, at {}x{}, {} full screen passes before the "
				+ "final", this.chain.packName(), place(), this.world, main.width, main.height,
				unfolded.passes().size());

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
			Vitrail.logger().info("{} carries the game's finished frame, drawn in for {}: nothing of "
					+ "the pack writes into a colour target of its own yet, the chunk passes included, "
					+ "so what lands here is already tone mapped and already holds the sky, the weather "
					+ "and the hand", TargetName.canonical(where.get().target()), where.get().from());
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
				named(byKind, SamplerPlan.Kind.UNBINDABLE,
				"are declared under a type this backend cannot bind, and should have gone with "
						+ "their pass");

		List<String> copies = byKind.getOrDefault(SamplerPlan.Kind.DEPTH, Set.of()).stream()
				.filter(SamplerPlan::depthCopy)
				.toList();
		if (!copies.isEmpty()) {
			Vitrail.logger().info("{} read the copy of the depth taken before the world's "
					+ "translucents", copies);
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

		this.terrain.release();

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
