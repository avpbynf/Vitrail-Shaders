package dev.vitrail.render;

import dev.vitrail.glsl.LoadClock;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslationCache;
import dev.vitrail.HostReport;
import dev.vitrail.pack.load.PackLoader;
import dev.vitrail.pack.load.PackReport;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.pack.texture.CustomStorage;
import dev.vitrail.settings.PackFile;
import dev.vitrail.settings.PackSession;
import dev.vitrail.settings.SettingsFile;
import dev.vitrail.settings.SettingsLayers;
import dev.vitrail.Vitrail;
import com.mojang.blaze3d.GpuDeviceLossException;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.util.Util;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.RejectedExecutionException;

/**
 * Which pack is drawn, how it was chosen, and what the settings screen reads and writes about it.
 * <p>
 * This is the whole of a load short of the frame: {@code pack.txt} is read, the pack it names is
 * found among the candidates or reported missing, its settings are laid out and carried over, its
 * chain is read and handed to {@link PackChain}, and the four numbers the screen moves without a
 * load behind them, the shadow distance, the render scale, the shadow map scale and the improved
 * transparency, are kept and written back here. The frame reads one number of it, the shadow
 * distance the slider moves; everything else it needs is put up on {@link PackChain} once, and
 * what it finds wrong comes back through {@link #error}.
 * <p>
 * Everything is static and volatile because there is one pack for the whole game and the screen
 * asks about it from the render thread while a worker may still be loading it: each field is read
 * once into a local wherever two reads have to agree.
 */
public final class PackChoice {

	/**
	 * The name {@code pack.txt} takes that is not the name of a pack: draw none of them, and leave the
	 * game its own image. It is read after the whole names and before the fragments, so a folder really
	 * called {@code none} is still reachable and a pack whose name merely holds the word cannot answer
	 * for it.
	 */
	public static final String NO_PACK = PackFile.NONE;

	/** The pack the report was last taken of, so that a portal does not pay for it a second time. */
	private static volatile Path reported;
	private static volatile PackSession session;
	private static volatile String lastError;
	private static volatile List<String> removed = List.of();
	private static volatile boolean packsFirst = true;
	private static volatile boolean packOff;

	/**
	 * Whether {@code pack.txt} named a pack this folder does not hold, which is the one way of
	 * drawing nothing that is not a way of asking for nothing.
	 * <p>
	 * <strong>Held apart from {@link #packOff}, near enough to be folded into it and meaning the
	 * opposite.</strong> A file saying {@code none} asked for the game's own image and got it, so
	 * nothing is wrong and nothing is said. A file naming a pack that was renamed, deleted or
	 * mistyped asked for a picture and got the game's, and answering that one with {@code packOff}
	 * puts "No shader pack: the game draws its own image" on the screen, which tells the player they
	 * got what they asked for at the moment they did not.
	 */
	private static volatile boolean packMissing;

	/**
	 * What {@code pack.txt} asked for at the last load, which is not the same question as what is
	 * drawn: the screen highlights the pack that was chosen even while shaders are switched off, and
	 * it needs the name to do it.
	 */
	private static volatile PackFile askedFor = PackFile.EMPTY;

	/**
	 * How far the player asked the light to reach, in chunks, the one line of {@code pack.txt} that
	 * is not about which pack to draw.
	 * <p>
	 * Held apart from {@link #askedFor} rather than read off it, because the slider moves it with no
	 * load behind it: that one is what the last load was asked for, and writing a screen's number
	 * into it would have it answer for a pack choice nobody made. This one is written wherever the
	 * number comes from, the file at load and the slider afterwards, and the shadow walk reads it
	 * either way.
	 */
	private static volatile int shadowDistance = PackFile.DEFAULT_SHADOW_DISTANCE;

	private PackChoice() {
	}

	/**
	 * Reads the chosen pack and translates every program of its chain.
	 * <p>
	 * Two callers, on two different threads, and neither of them is a frame. Client setup reaches
	 * it once at start up, on a mod loading worker under NeoForge and on the render thread under
	 * Fabric; {@link #reload} reaches it again afterwards, on the render thread only. So it touches
	 * files, and the one option {@link #turnOffImprovedTransparency} lowers, and nothing else. No
	 * device, no pass, no target.
	 */
	public static void load(Path gameDirectory) {
		PassTimings.resetCensus();
		// Here and not lower down: it decides what the translation emits, and every road
		// below this point that reads a pack translates one.
		DriverTrig.read(gameDirectory);
		// Beside it, for the compiles rather than the translations: it decides the bytes every
		// module of this load is made of, and the key it is stored under.
		RawLocals.read(gameDirectory);
		// Beside the trig switch and for the same reason: what follows is what these tallies
		// are a tally of. The cache empties its own on the same line, so that its counts and the
		// clock's milliseconds are read off one load.
		//
		// Two loads can smear into each other here, in both directions, exactly as they do for
		// the clock: a family worker of the chain that has just been released lands its own
		// hits after this reset, and a swap empties the tallies while the outgoing chain's
		// workers are still translating.
		LoadClock.reset();
		TranslationCache.reset();
		// The storage blocks the translator files away, emptied at the head of a load and NOT beside
		// the CustomImages line in release(), though the two are installed on the same line.
		//
		// What parts them is not when they are asked. VulkanBindGroupLayoutMixin asks both in the
		// same method, while the layout is built. It is that only this one is asked again on the
		// OTHER side of the same decision: the layout takes VK_DESCRIPTOR_TYPE_STORAGE_BUFFER from
		// StorageBuffers.named and the descriptor write takes it from StorageBuffers.bound, and both
		// of those come back here. A layout is cached with its pipeline and a release does not empty
		// that cache, so emptying this between the two makes the write go in as a uniform buffer
		// against a slot the layout already declared storage. The image half cannot do that: the
		// write side reads StorageImages.bound, which walks an allocation of its own and never asks
		// CustomImages, so an empty table there costs a filter mode for a frame and no more. And the
		// road that would reach it is real: leaving a world releases the chain and does NOT replace
		// it, so the first frame of the next world is drawn by that same chain before draw() gets to
		// reloadIfTheWorldMoved.
		//
		// What emptying here buys is bounded, not absolute. The pack-load worker of the chain that
		// has just gone translates on a background thread and every program it reads reinstalls what
		// ITS pack declared, so it can put the outgoing answers back after this line. prefetchFamily
		// stops between two families once the chain is released, which leaves the one family already
		// under way, and that is the whole of what can still land here.
		CustomStorage.clear();
		// Down before the folder is read rather than raised only where a pack asks for it: every
		// road out of this method below has to leave the terrain sampler answering for the pack
		// that will draw, and most of them draw no pack at all.
		TerrainSampler.breaksAnisotropy(false);
		// Taken before anything reads the folder, so that the count printed at the end of the load
		// covers every opening the load made and not only the translation's.
		int openings = ShaderPackSource.openings();
		LegacyTerrainFilter.read(gameDirectory);
		session = null;
		lastError = null;
		removed = List.of();
		packsFirst = true;
		packOff = false;
		packMissing = false;
		// Emptied before the read below rather than after it, so that a file that cannot be read at
		// all leaves the screen naming nothing rather than whatever the load before it asked for.
		askedFor = PackFile.EMPTY;
		PackChain live = null;
		try {
			// Read here rather than inside choose(), which the empty folder road below returns ahead
			// of. That road is the one a player takes who has just renamed or deleted their only pack,
			// and without the name this far up it is the one road that cannot say which of the two
			// happened: the folder is empty, which is true, and is not what they did.
			askedFor = PackFile.read(packFile(gameDirectory));
			// Off that same read rather than a second one, and before anything can refuse the load:
			// this line belongs to the player and not to the pack, and every return below is reached
			// with no pack drawn and the shadow distance still theirs. The render scale rides the
			// same rule; whether it ENGAGES is asked per frame against drawingPack(), so the roads
			// below that draw nothing need no line of their own to keep the world at the window.
			shadowDistance = askedFor.shadowDistance();
			RenderScale.wanted(askedFor.renderScale());
			// Here and not lower down, because it is read during the expansion of the pack's very
			// first unit: the shadow map's size is a declaration of the pack's own text, and the
			// scale is applied by rewriting that declaration rather than by allocating something
			// else, so it has to be in force before a line is expanded.
			// Pushed here and said further down. This is the one place that knows the setting
			// before anything is expanded, which is what it has to be in force for, but the roads
			// below reach an empty folder and a pack switched off as well, and there is nothing
			// to rewrite on either.
			SettingSet.shadowMapScale(askedFor.shadowMapScale());

			// Both ways out of the next two blocks end with no pack drawing anything, and NEITHER mesh
			// carries what a pack reads once none wants it: said here as well as on the road that
			// loads a pack, or picking None after a pack would leave the extra bytes on every vertex
			// with nothing left to read them. The refusals further down do not say it, and the note on
			// the one at the head of the load prices what that costs.
			//
			// Three switches for two meshes: the hand is drawn from the entity mesh and carries a
			// switch of its own, so putting a pack away has to take that one down too or the entity
			// mesh goes on carrying for a family nothing serves.
			List<Path> packs = PackLoader.candidates(gameDirectory);
			if (packs.isEmpty()) {
				TerrainDraw.wanted(false);
				EntityDraw.wanted(false);
				HandDraw.wanted(false);
				// The empty folder is the smaller half of the truth whenever a pack was named: what a
				// player who has just deleted or renamed their only one needs told is which name went
				// unanswered, and the folder being empty is why.
				if (namedAPack()) {
					packIsMissing(gameDirectory);

					return;
				}

				lastError = "No shader pack in " + PackLoader.directory(gameDirectory);
				Vitrail.logger().info("No shader pack in {}, nothing to draw",
						PackLoader.directory(gameDirectory));
				return;
			}

			Path pack = choose(packs, askedFor).orElse(null);
			if (pack == null) {
				// Both roads out of here draw nothing, so both take the meshes down, and only then are
				// they told apart: what parts them is whether nothing was asked for or whether what was
				// asked for is not there.
				TerrainDraw.wanted(false);
				EntityDraw.wanted(false);
				HandDraw.wanted(false);
				if (namedAPack()) {
					packIsMissing(gameDirectory);

					return;
				}

				// Nothing is left behind and nothing is reported as an error: this is the same path as
				// an empty folder, taken because it was asked for rather than because nothing was found.
				packOff = true;
				Vitrail.logger().info("No pack asked for, so none of the {} in {} is read and the game "
						+ "draws its own image. Pick one in the settings screen, or name it in {}",
						packs.size(), PackLoader.directory(gameDirectory), packFile(gameDirectory));
				return;
			}

			SettingsLayers.Resolved settings = open(gameDirectory, pack);

			Map<String, OptionValue> chosen = new LinkedHashMap<>(settings.chosen());
			// Reserved keys rather than options: no pack declares a setting under any of these
			// names, and each names something this mod does rather than a value the pack has. The
			// fourth, profile, never reaches here: the settings layer takes it out and carries it
			// apart, because it is the side that writes it back into the pack's own file.
			EngineOptions.Read engine = EngineOptions.take(chosen);
			packsFirst = engine.packsFirst();
			PackChain.chainWanted(engine.chain());

			// Read and shown, and not drawn. On a backend this engine is not written for, the pack
			// is published to the screen above so that it can be picked and configured ahead of the
			// restart that will draw it, and every switch below stays down so that nothing of it
			// reaches a mesh or a frame: the programs are translated against Vulkan's depth and
			// clip conventions, and what they drew when let run elsewhere was a picture credible
			// and wrong, which reads as a pack fault. The game's own image is the better answer.
			// HostReport says it once in the log at startup and once in chat on entering a world;
			// what this road adds is the screen's bottom line, through lastError, and nothing else.
			if (HostReport.otherBackend()) {
				TerrainDraw.wanted(false);
				EntityDraw.wanted(false);
				HandDraw.wanted(false);
				lastError = ShaderPackSource.nameOf(pack) + " is not drawn on the "
						+ HostReport.backend() + " backend: set Graphics API to \"Prefer Vulkan "
						+ "(Experimental)\" under Options, Video Settings, and restart";
				return;
			}

			TerrainDraw.wanted(engine.terrain());
			TerrainDraw.shadowWanted(engine.shadow());
			SkyDraw.wanted(engine.sky());
			EntityDraw.wanted(engine.entities());
			HandDraw.wanted(engine.hand());
			CloudDraw.wanted(engine.clouds());
			WeatherDraw.wanted(engine.weather());
			ParticleDraw.wanted(engine.particles());
			PackDump.configure(engine.dump(),
					gameDirectory.resolve(Vitrail.MOD_ID).resolve("dump.txt"));

			// Refused by name, and before a single program is TRANSLATED. Required flags this
			// engine does not serve keep the pack on its fallback; optional CUSTOM_IMAGES is
			// posed from EngineDefines when the storage-image pipe is served, and is not a
			// required flag in the corpus.
			//
			// **Here and not one line earlier**, and TerrainDraw.wanted above is the reason. It settles
			// what the chunk mesh carries, and returning ahead of it would leave that answer at its
			// default, so a refused pack would decide the mesh for whichever pack was picked after
			// it. The mesh follows the pack, so what that costs is a rebuilt world rather than a
			// session. The session is published by then as well, so the
			// settings screen shows the pack it is refusing rather than an empty list, which is
			// where the two other refusals of this method stand.
			//
			// What this order does NOT do is take the terrain back from a pack refused below: the
			// mesh then carries what a pack reads while none is drawn, which costs twenty bytes a
			// vertex and one rebuild, and no picture. One pack of the corpus reaches it.
			//
			// A deliberate divergence, and worth naming because it is one: Iris refuses a required
			// flag only when the name is unknown to it or the hardware cannot serve it, so it draws
			// Reverie where this does not. It can afford to, having built all four.
			//
			// Read from the one file rather than from the report above, because a refusal has to
			// hold at every load and the report is taken once.
			List<String> required = new ArrayList<>(PackLoader.properties(pack).requiredFeatures());
			// Only the names this engine has not built refuse the pack: the block emission
			// attribute rides in the chunk element, a pack declaring HIGHER_SHADOWCOLOR draws the
			// light into the eight it asked for, a storage block is bound off the pack's own
			// bufferObject, and custom images are served now, so a pack that cannot draw without one
			// of those is simply right about what it needs. The list is the one EngineDefines poses
			// IRIS_FEATURE_ for, and the two have to move together: a define is a promise, and a
			// refusal is the same promise refused.
			//
			// Matched without regard to case, and that is not politeness: the list above is kept in
			// the pack's own spelling, and Iris never reads it that way. It upper-cases a declared
			// name before it looks the flag up (features/FeatureFlags.java:70), and the message it
			// prints to pack authors spells the name in lower case,
			// "iris.features.required/optional = ssbo" (shaderpack/ShaderPack.java:216). A pack
			// that wrote what that message told it to would be refused here on a name this engine
			// serves. ShaderProperties.declares already reads the same lists that way.
			Set<String> served = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
			served.addAll(List.of("BLOCK_EMISSION_ATTRIBUTE", PackDirectives.HIGHER_SHADOWCOLOR,
					"SSBO", "SEPARATE_HARDWARE_SAMPLERS"));
			if (CustomImages.served()) {
				served.add("CUSTOM_IMAGES");
			}

			required.removeIf(served::contains);

			if (!required.isEmpty()) {
				PackChain.stop();
				String names = String.join(", ", required);
				String named = ShaderPackSource.nameOf(pack);
				lastError = named + " requires " + names
						+ ", which this engine does not serve";
				Vitrail.logger().error("{} requires {}, which this engine does not serve, so "
						+ "nothing is drawn", named, names);
				return;
			}

			// ONE opening of the archive for everything below, and it is what a player feels. The
			// place, the values, the chain, the chunk programs and every shadow compute used to
			// open the pack for themselves: for a zip that mounts the archive again each time, and
			// each of them then walked every source file of the pack line by line to rebuild the
			// same index of the same settings. Half a dozen identical readings and one more per
			// compute program, on the thread that draws, is the freeze after picking a pack.
			//
			// Nothing below may keep a path taken from it. ShaderPackSource says why: closing the
			// zip invalidates every path out of it, and what that leaves is a
			// ClosedFileSystemException on an unrelated read much later.
			if (askedFor.shadowMapScale() < PackFile.MAX_SHADOW_MAP_SCALE) {
				// Under the pack rather than beside the allocation, because the map's own line knows
				// one number and not where it came from. What is said is what the engine does, and
				// the size in force is the pack's own setting for the name where it has one.
				Vitrail.logger().info("The shadow map scale stands at {}%, so the size in force for this pack's "
					+ "shadow map, its own setting for it included, is rewritten to that fraction wherever it "
					+ "is written as a plain number", askedFor.shadowMapScale());
			}

			// Before OpenedPack.open, not after. SettingSet.resolve copies EngineDefines.table as
			// it is built, and the expander's liveness is that copy. The translator writes the
			// live machine() table back out as #define lines. Installed after the copy, the two
			// disagree: DISTANT_HORIZONS lands in the header, the DH uniforms stay in the body
			// because liftUniforms will not move a dead line, and Vulkan refuses a non-opaque
			// uniform outside a block. Complementary's deferred1 died that way on a DH toggle.
			// Iris has one table for both jobs, StandardMacros.java:64-65.
			PackDefines.install();
			report(pack);

			try (OpenedPack opened = OpenedPack.open(pack, chosen, settings.profile())) {
				// The world decides the directory, and the pack decides which world that is: a folder
				// may be named anything and mapped in dimension.properties, so the name is read from
				// the pack rather than composed from the dimension.
				String place = PackPlace.place(opened.source());
				String world = PackPlace.world();

				PackValues values = PackValues.read(opened, place);
				// The terrain sampler is a static that outlives any one chain, and this is the
				// first point in the load where the pack's own answer exists. It has to be in force
				// before a chunk pass begins rather than when the chain is installed: the sampler is
				// settled from the renderer's begin, which the world's first draw reaches, and a
				// pack that cannot bear anisotropy would otherwise read one frame with it.
				TerrainSampler.breaksAnisotropy(values.breaksAnisotropy());

				long began = System.nanoTime();
				Optional<PackProgram.Chain> read =
						PackProgram.loadChain(opened, place, engine.passes(), engine.families());
				if (read.isEmpty()) {
					String where = place.isEmpty() ? "at its root" : "in " + place + " or at its root";
					String named = ShaderPackSource.nameOf(pack);
					lastError = named + " serves no final with both stages " + where;
					Vitrail.logger().warn("{} serves no final with both stages {}, nothing to draw",
							named, where);
					return;
				}

				PackProgram.Chain chain = read.get();
				Vitrail.logger().info("Read {} programs of {} in {} ms", chain.programs().size(),
						chain.packName(), (System.nanoTime() - began) / 1_000_000L);

				// A refusal is a rule of the API this engine cannot bend, named with the program that
				// broke it. Dropping that program instead would move the half every later pass reads.
				List<String> refusals = chain.chain().refusals();
				if (!refusals.isEmpty()) {
					PackChain.stop();
					refusals.forEach(refusal -> Vitrail.logger().error("{}", refusal));
					// The first one, in the pack's own terms, so that the screen says why nothing is
					// drawn rather than sending the reader to the log for all of them.
					lastError = chain.packName() + " cannot be drawn as it stands: " + refusals.get(0);
					Vitrail.logger().error("{} cannot be drawn as it stands, nothing will be drawn",
							chain.packName());
					return;
				}

				announceRemoved(chain);
				live = new PackChain(chain, values, world, engine.seed(), opened, pack, chosen,
						settings.profile());
				// Terrain first, and it finishes before the other families start: two readers on one
				// zip at once is a race, and Sodium takes the mesh format here before any pass asks
				// for a shader. The other families then translate on a worker, so Complementary
				// Unbound's entities overlap the composite compiles and never sit on the render thread.
				PackChain.activate(live);
				live.terrain.read(opened);
				// After the two reads this thread makes and before the worker starts on the rest,
				// so the count on the line is a number rather than a snapshot of a tally still
				// moving.
				DriverTrig.announce();
				// The count, so that what a load costs is a figure in the log rather than a feeling.
				// It covers the whole of this method, the settings reading included, and the report
				// and the families are deliberately outside it: both run on a worker, off the thread
				// the player is waiting on, the families through one opening of their own.
				Vitrail.logger().info("Opened {} {} times to load it", chain.packName(),
						ShaderPackSource.openings() - openings);
				// Once per installed chain, whatever else this load does or fails to do later:
				// the second report, with the families in, only exists when a warmup fans out.
				// Flattening and translating only, because this method does no device work by
				// design: the modules follow on the first draws and the workers, and land in the
				// next report. The flattening is said first because it is paid first, and because
				// it is what a load whose translations all come off disk is left with. It names
				// the chain's units and not the pack's, which is what parts it from the pack
				// report's own expansion line: that walk counts every entry point the pack ships
				// and is made once ever, this one counts what this load built and is made at each.
				Vitrail.logger().info("Flattening the chain's units cost {} ms over {} of them "
						+ "with {} more handed back by an opening that had already built them, the "
						+ "pack report's own walk not counted; translating cost {} ms over {} "
						+ "translator calls before the chain went live; the modules follow on the "
						+ "draws and the workers, counted into the report that closes the warmup",
						LoadClock.expansionMillis(), LoadClock.expanded(),
						LoadClock.expansionsServed(), LoadClock.translationMillis(),
						LoadClock.translated());
				// Both ways, whichever way it went. A cache that only speaks when it helps leaves
				// every later reading of a log ambiguous, since a silence would then mean either
				// that nothing was served or that nothing was asked. It rides the line above rather
				// than standing on its own, so a load this method turned back from is silent on
				// both counts rather than on one of the two.
				Vitrail.logger().info("Translation cache: {} programs served from disk, {} "
								+ "translated, counted at the same point of the load as the line "
								+ "above", TranslationCache.served(), TranslationCache.translated());
				// Said from here and not from there: the cache lives in a package that names
				// nothing the game brings, so it keeps the note and this takes it. Once a run is
				// the cache's own latch and not this take. Here is also as early as a load can
				// say it and no earlier: a load that turned back above, or a family translating
				// on the worker started below, leaves the note for the load after this one.
				String refused = TranslationCache.takeRefusal();
				if (!refused.isEmpty()) {
					Vitrail.logger().warn("In the translation cache, {}, so it was translated "
							+ "instead. Said once a run: nothing about it stops a pack loading",
							refused);
				}
			}

			live.startFamilyPrefetch();
			turnOffImprovedTransparency();
			if (!engine.chain()) {
				EngineOptions.announceChainOff();
			}

			if (!engine.entities()) {
				EngineOptions.announceEntitiesOff(gameDirectory);
			}

			if (!engine.hand()) {
				EngineOptions.announceHandOff(gameDirectory);
			}

			if (!engine.clouds()) {
				EngineOptions.announceCloudsOff(gameDirectory);
			}

			if (!engine.weather()) {
				EngineOptions.announceWeatherOff(gameDirectory);
			}
		} catch (IOException | RuntimeException e) {
			PackChain.stop();
			lastError = "Could not prepare this pack: " + e;
			Vitrail.logger().error("Vitrail could not prepare a pack's chain", e);
		}
	}

	/**
	 * The report of a pack, once per pack rather than at every load: this reading walks the
	 * whole archive, and the load is the road every reload takes, a portal and an Apply both
	 * coming back through it with nothing new to report.
	 * <p>
	 * Off the render thread, on an opening of its own: the walk takes a second and a half on
	 * Complementary and its only product is the [pack] block of the log, which held the title
	 * screen and every Apply for exactly that long. The lines land a little later than they
	 * did and in the same order among themselves. Asked after the machine table is installed,
	 * so the walk reads the same table as the load it describes, and outside the count of
	 * openings the load prints, which no longer includes it.
	 * <p>
	 * Never fatal, and that is the whole reason for the catches: it is a report and not the
	 * drawing, so its own failures stop here, and a failure gives the next load its chance to
	 * report again. An executor refusing the task at shutdown is a report not taken, and not
	 * a pack that is not drawn.
	 */
	private static void report(Path pack) {
		if (pack.equals(reported)) {
			return;
		}

		reported = pack;
		try {
			Util.backgroundExecutor().execute(() -> {
				try {
					PackReport.log(PackLoader.load(pack));
				} catch (IOException | RuntimeException e) {
					// Said by the report so that it keeps the report's own prefix: the failure
					// belongs to a measurement or a count, and the load has the last word on
					// whether the archive opens at all.
					PackReport.couldNotRead(ShaderPackSource.nameOf(pack), e);
					reported = null;
				}
			});
		} catch (RejectedExecutionException e) {
			reported = null;
		}
	}

	/**
	 * Whether {@code pack.txt} named a pack at all, as opposed to asking for none of them.
	 * <p>
	 * The one test the two roads out of a load that draws nothing are told apart by, so that they
	 * cannot answer differently: shaders switched off and the word {@code none} are both a player
	 * asking for the game's own image, and anything else in that file is a player asking for a
	 * picture. It repeats the two refusals {@link #choose} makes on the same grounds and has to: that
	 * method is not reached at all when the folder holds nothing to search.
	 */
	private static boolean namedAPack() {
		PackFile asked = askedFor;

		return asked.wantsPack() && !asked.namesNone();
	}

	/**
	 * Says, once and in one place, that the pack a player named is not there.
	 * <p>
	 * Two roads run into this and they are one fault to the player: the folder holds packs and none
	 * of them answers the name, or the folder holds nothing at all. Written twice, the two would say
	 * it in two ways and then drift, and the difference between them is not one a player can act on.
	 * <p>
	 * The reason is kept on {@link #lastError} rather than only logged, which is what carries it to
	 * the chat line the reload key says and to the screen's bottom line, both of which read that
	 * field and neither of which the log reaches.
	 */
	private static void packIsMissing(Path gameDirectory) {
		packMissing = true;
		lastError = "No pack named '" + askedFor.name() + "' in "
				+ PackLoader.directory(gameDirectory) + ", so nothing is drawn";
		Vitrail.logger().error("{} names '{}', and no pack of that name is in {}, so nothing is drawn."
				+ " Check the name, or pick a pack in the settings screen", packFile(gameDirectory),
				askedFor.name(), PackLoader.directory(gameDirectory));
	}

	/** The file naming the pack to draw, written by the settings screen and edited by hand. */
	public static Path packFile(Path gameDirectory) {
		return gameDirectory.resolve(Vitrail.MOD_ID).resolve("pack.txt");
	}

	/**
	 * Turns off improved transparency while a pack draws, which is Iris's
	 * {@code MixinDisableFabulousGraphics}.
	 * <p>
	 * That option opens a second colour target for translucent items
	 * ({@code ITEM_ENTITY_TARGET}). Each leftover feature that writes it is an Immediate draw, a
	 * GPU stop OpenGL never paid. Packs already composite their own translucency; Iris therefore
	 * refuses the option ({@code mixin/fabulous/MixinDisableFabulousGraphics.java:37-40}), and this
	 * engine does the same. Calling this at pack load is what Vitrail needs extra, because enabling
	 * a pack here is not a renderer reload.
	 * <p>
	 * What the setter does depends on when it is called, and the difference matters because one of
	 * the two callers is not on the render thread. It reaches {@code LevelExtractor.allChanged},
	 * which tears the target down and rebuilds around it, but only once there is a level: called
	 * from {@link #load} at client setup there is none, so nothing is rebuilt and the option is
	 * merely lowered before any of it is built. NeoForge runs that setup on a mod loading worker.
	 * The rebuild that does happen is the one the pack load AFTER a world join asks for, on the
	 * render thread, and {@code LevelExtractorMixin} is what catches the game's own reloads.
	 */
	public static void turnOffImprovedTransparency() {
		if (!PackChain.drawingPack()) {
			return;
		}

		Options options = Minecraft.getInstance().options;
		if (options.improvedTransparency().get()) {
			options.improvedTransparency().set(false);
			options.graphicsPreset().set(GraphicsPreset.CUSTOM);
		}
	}

	/**
	 * Which pack to draw: the one {@code vitrail/pack.txt} names, by whole or partial name, and none
	 * at all when it names nothing this folder has, when it says {@link #NO_PACK}, or when it is not
	 * there.
	 * <p>
	 * <strong>Those three empty answers are not one answer.</strong> Only the last two asked for the
	 * game's own image; the first asked for a picture and cannot be given one. Which of the three it
	 * was is not said here and is not returned either: {@link #namedAPack} tells them apart from the
	 * file alone, which is what lets the road that never reaches this method, an empty folder, answer
	 * the same way rather than nearly the same way.
	 * <p>
	 * <strong>Nothing is loaded until something is asked for</strong>, which is the rule every
	 * shader loader follows and the only one that does not surprise: dropping this mod into an
	 * instance that already has a shaderpacks folder must not light the world with whichever pack
	 * happens to sort first. The same answer covers a name that matches nothing, where the older
	 * behaviour of falling back to the first pack meant a typo drew something the player never
	 * picked and could not tell from the pack they meant.
	 * <p>
	 * A text file is not a settings screen and is not meant to become one. It exists because
	 * eight packs sit in that folder and switching between them is most of the work of supplying
	 * the values they read, so needing to rename files to do it would be a tax on every attempt.
	 * <p>
	 * The whole name is tried before the fragment. Two packs of a folder can have one name inside
	 * the other, a version next to the version it replaces being the ordinary way that happens, and
	 * on a fragment the shorter one would answer for both: the settings screen writes the whole
	 * name for that reason and would otherwise be unable to reach the longer one at all.
	 *
	 * @param asked what {@code pack.txt} says, read by the caller: the folder is searched here and
	 *              the file is not, so that the one road out of a load that never searches a folder
	 *              still knows what was asked of it
	 * @return empty when no pack is to be drawn
	 */
	private static Optional<Path> choose(List<Path> packs, PackFile asked) {
		if (!asked.wantsPack()) {
			return Optional.empty();
		}

		String wanted = asked.name().toLowerCase(Locale.ROOT);
		for (Path pack : packs) {
			if (pack.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted)) {
				return Optional.of(pack);
			}
		}

		if (asked.namesNone()) {
			return Optional.empty();
		}

		for (Path pack : packs) {
			if (pack.getFileName().toString().toLowerCase(Locale.ROOT).contains(wanted)) {
				return Optional.of(pack);
			}
		}

		return Optional.empty();
	}

	/**
	 * Everything a pack is configured by, read in one go: its own file in {@code shaderpacks/},
	 * which is the one Iris reads, then {@code vitrail/options.txt} forced over it.
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

		// At most once per pack, and three of the four answers move nothing at all. Worth a line because
		// a carried load is the only one where the values on screen were somewhere else a moment ago,
		// because a profile chosen before the move comes back as the values it named rather than as
		// its name.
		// Said here and not where the carry-over happens: the settings packages import no Minecraft
		// API, which is what lets them be run against the corpus without starting the game, and one
		// logger down there would end that.
		switch (opened.carried().carry()) {
			case MOVED -> Vitrail.logger().info("Moved {} of this pack's settings into {}, which is"
					+ " the file Iris reads too. They were in {}, which this engine kept before, and"
					+ " that file is now renamed aside. A profile chosen back then counts here as the"
					+ " values it named, the old file having stored only what differed from it",
					opened.saved().values().size(), opened.settingsFile(),
					SettingsFile.legacy(gameDirectory, opened.packFileName()));
			// Warn and not info: this is the one shape of old file that cannot be carried over, and
			// nothing else will say so. It is left exactly where it is.
			case UNKNOWN_PROFILE -> Vitrail.logger().warn("{} holds settings this engine kept before"
					+ " they moved, under the profile {}, which this version of the pack no longer"
					+ " declares. What it stored is only the difference from that profile, so it"
					+ " cannot be completed and nothing has been moved. Pick the settings again and"
					+ " press Apply, or edit {} by hand",
					opened.carried().file(), opened.carried().profile(), opened.settingsFile());
			// The exception itself is lost to the rule above, so the path carries the diagnosis: it
			// is the file that could not be read where that is what failed, and the shared one where
			// it is the folder that cannot be written.
			case FAILED -> Vitrail.logger().warn("Could not move this pack's settings, and {} is what"
					+ " could not be read or written. Nothing has been changed on disk, the pack is"
					+ " drawn with its own defaults, and the next load tries again",
					opened.carried().file());
			case NOTHING -> { }
		}

		List<String> stale = opened.stale();
		if (!stale.isEmpty()) {
			Vitrail.logger().info("{} settings in {} name nothing {} shows and are kept as they are:"
					+ " {}", stale.size(), opened.settingsFile(), opened.packFileName(), stale);
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
		Map<String, PackProgram.Refusal> refused = chain.removed();
		if (refused.isEmpty()) {
			return;
		}

		List<String> said = new ArrayList<>();
		refused.forEach((program, refusal) -> said.add(
				(chain.place().isEmpty() ? program : chain.place() + "/" + program)
						+ " is not run: it " + refusal.reason()));

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
		if (error == null && PackChain.stopped()) {
			error = "This pack stopped drawing after an error, see the log";
		}

		return Optional.ofNullable(error);
	}

	/** The reason the frame stopped drawing the pack, said by the frame; see {@link #lastError}. */
	static void error(String error) {
		lastError = error;
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
	 * Whether {@code pack.txt} asks for no pack at all, which is a different answer from having none
	 * to draw: nothing failed and nothing is missing, the game draws its own image because that is
	 * what was asked for. A screen needs the two apart to say which it is showing.
	 */
	public static boolean noPackWanted() {
		return packOff;
	}

	/**
	 * Whether the pack {@code pack.txt} names is one the folder no longer holds, which is the third
	 * answer to "why is nothing being drawn" and the only one of the three that is a fault.
	 * <p>
	 * The name that was asked for is {@link #askedFor}'s to give, which it does whether or not it was
	 * found: a screen saying this has to be able to name it, or it says no more than the empty folder
	 * it is not.
	 */
	public static boolean packMissing() {
		return packMissing;
	}

	/**
	 * What {@code pack.txt} asked for at the last load: the name it carries, whether or not a pack of
	 * that name was found, and whether shaders are switched on.
	 * <p>
	 * The name survives shaders being switched off, which is the reason the file carries two facts
	 * rather than one: the screen's toggle has to be able to come back to the pack the player had.
	 */
	public static PackFile askedFor() {
		return askedFor;
	}

	/**
	 * How far the player asked the light to reach, in CHUNKS. Read every frame the shadow map is
	 * walked, so it answers from memory rather than from the file.
	 */
	public static int shadowDistance() {
		return shadowDistance;
	}

	/**
	 * Takes a new one and puts it away, and takes effect on the next shadow walk rather than on a
	 * reload: nothing about a pack changes, only how far the walk that is already happening goes.
	 * <p>
	 * The file is READ BEFORE IT IS WRITTEN, and it is not tidiness: the settings screen writes the
	 * two pack lines of this same file, so building a record out of what this side holds would hand
	 * back whatever pack was chosen when this class last looked. Reading first also means a file
	 * edited by hand between two moves of the slider keeps its edit.
	 */
	public static void shadowDistance(Path gameDirectory, int chunks) {
		// Taken on first and put away second, so that a folder that cannot be written still moves
		// the image the player just asked to move. What they lose then is the next session, not
		// this one, and the line below is what says so.
		shadowDistance = PackFile.EMPTY.withShadowDistance(chunks).shadowDistance();

		Path file = packFile(gameDirectory);
		try {
			PackFile.write(file, PackFile.read(file).withShadowDistance(chunks));
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Vitrail could not write the shadow distance to {}", file, e);
		}
	}

	/**
	 * What fraction of the window the world renders at, as the percentage the player set. Answered
	 * from the last read of {@code pack.txt} rather than from the file, like the distance above.
	 */
	public static int renderScale() {
		return askedFor.renderScale();
	}

	/**
	 * Takes a new render scale and puts it away, on the shape of {@link #shadowDistance(Path, int)}
	 * and for its reasons: taken on first, so a folder that cannot be written still moves this
	 * session, and the file read before it is written, so the pack lines and a hand edit survive
	 * the slider. It takes effect at the next frame's swap rather than on a reload: nothing about
	 * the pack changes, only the size the world is handed.
	 */
	public static void renderScale(Path gameDirectory, int percent) {
		askedFor = askedFor.withRenderScale(percent);
		RenderScale.wanted(askedFor.renderScale());

		Path file = packFile(gameDirectory);
		try {
			PackFile.write(file, PackFile.read(file).withRenderScale(percent));
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Vitrail could not write the render scale to {}", file, e);
		}
	}

	/**
	 * How much of the shadow map the pack asked for is actually drawn, as the percentage the player
	 * set. Answered from the last read of {@code pack.txt} like the two above. Nothing about the
	 * shadow map is decided from this: the number is applied when the declaration of the size in
	 * force is rewritten, before a line of the pack is translated. What reads this is the screen
	 * that offers it, and the load that announces the setting and hands it to the translation.
	 */
	public static int shadowMapScale() {
		return askedFor.shadowMapScale();
	}

	/**
	 * Takes a new shadow map scale and puts it away, on the shape of {@link #renderScale(Path, int)}
	 * and for its reasons: taken on first, so a folder that cannot be written still moves this
	 * session, and the file read before it is written, so the pack lines and a hand edit survive the
	 * slider.
	 * <p>
	 * <strong>Unlike the render scale, this asks for the pack to be read again, and it has to.</strong>
	 * The size is applied by rewriting the declaration the pack makes of it, so it lives in
	 * translated text and not in an allocation a later frame could redo. A map remade under
	 * programs translated against the old number is the whole defect this setting exists to
	 * avoid. The reload is asked for rather than performed, on the shape the settings screen
	 * already uses, so nothing is rebuilt inside a slider's own frame.
	 */
	public static void shadowMapScale(Path gameDirectory, int percent) {
		askedFor = askedFor.withShadowMapScale(percent);
		// Off the record and not off the argument, which is what the two settings either side of
		// this one do: the record is where the range is enforced, so an out of range percentage
		// would otherwise rewrite the declaration at the raw number while every reader of the
		// setting, the allocation's own line included, named the bounded one.
		SettingSet.shadowMapScale(askedFor.shadowMapScale());

		Path file = packFile(gameDirectory);
		try {
			PackFile.write(file, PackFile.read(file).withShadowMapScale(percent));
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Vitrail could not write the shadow map scale to {}", file, e);
		}

		reload(gameDirectory);
	}

	/**
	 * Rebuilds everything when the world under the pack has changed, which is not the same thing as
	 * a file having changed and is the only reload nobody can ask for.
	 * <p>
	 * Two moments, and both are the pack being read against something it could not see before. The
	 * pack is read while the client starts up, when the data pack registries do not exist yet, so a
	 * {@code block.properties} naming a block TAG resolved it against nothing; joining a world is
	 * what makes those tags resolvable. And a dimension directory replaces the root rather than
	 * layering over it, so walking through a portal changes half of what a pack is.
	 * <p>
	 * The two are asked apart rather than folded together: the registry the tags hang on is the
	 * very same object on both sides of a portal in single player, so a dimension change would slip
	 * past a chain that only watched them.
	 * <p>
	 * <strong>Nothing here watches a file.</strong> An edit made by hand takes effect the next time
	 * the pack is read, which is Apply in the settings screen whenever that screen has something to
	 * write, and that is the whole of it: a reload costs a second of hitch, and one that nobody asked
	 * for is a second of hitch nobody asked for. The screen's pack list watches its own folder, which
	 * is a different question: it notices a pack arriving and reads none of them.
	 */
	static void reloadIfTheWorldMoved(Path gameDirectory) {
		boolean stale = PackDefines.stale();
		boolean moved = PackPlace.moved();
		if (!stale && !moved) {
			return;
		}

		if (moved) {
			Vitrail.logger().info("Left {} for {}: a dimension replaces the root rather than layering "
					+ "over it, so the whole pack is read, translated and its colour targets allocated "
					+ "again, which is the hitch at the portal", PackPlace.settled(), PackPlace.world());
		} else if (PackDefines.distantHorizonsMoved()) {
			Vitrail.logger().info("Distant Horizons is drawing a far terrain now, or has stopped, so "
					+ "the pack is read again against DISTANT_HORIZONS: the symbol is what a pack "
					+ "branches its distant land on, and it cannot be right for both");
		} else if (PackDefines.textureFormatMoved()) {
			Vitrail.logger().info("The resource packs {}, so the pack is read again against "
					+ "MC_TEXTURE_FORMAT_LAB_PBR and the revision beside it: those are what a "
					+ "pack branches its material decode on, and they cannot be right for both",
					textureFormatDeclared());
		} else {
			Vitrail.logger().info("The world's own registries are here now, reloading the pack so "
					+ "what names a tag resolves against them");
		}

		// Straight to the reading, without forgetting the report first, and this is the one road
		// where that is right: nobody asked for this reload, the world moved under the pack.
		//
		// What it costs is worth knowing: the archive IS read again, and an archive edited on the
		// disk under the same name is drawn with its new content and still carries the report of the
		// old one, until somebody asks for a reload. Comparing content rather than a path would cost
		// more than the report it saves.
		readAgain(gameDirectory);
	}

	/**
	 * How the line above names what the resource packs declare, and it has to name both directions:
	 * removing the pack that declared it moves the symbols exactly as installing one does.
	 */
	private static String textureFormatDeclared() {
		EngineDefines.TextureFormat format = PbrAtlases.format();
		if (format == null) {
			return "declare no texture format any more";
		}

		String version = format.version() == null ? "" : " " + format.version();

		return "declare " + format.name() + version + " now";
	}

	/**
	 * Throws away the current chain and reads it again from disk, everything included: the pack
	 * named by {@code pack.txt}, the engine's own options, and the pack's settings file.
	 * <p>
	 * Render thread only: {@link PackChain#release()} closes GPU buffers and hands back the colour
	 * targets, which has to happen where {@code draw} runs and outside any render pass. The
	 * settings screen
	 * calls this from Apply, which covers a pack being picked, from Reset, and from its own reload
	 * button; the key that reads a pack again calls it from the client tick; and the shadow map
	 * scale reaches it from its own setter, because the size it moves lives in translated text.
	 * That last one is applied from the video settings screen, which calls the setter once when
	 * the screen is applied rather than on each notch of the slider. All of them go through
	 * here rather than each having a path of its own, so that what the screen applies and what a hand
	 * edit applies cannot drift apart. The world moving under the pack reads again too, and it is the
	 * one caller that does not come through here: see {@link #readAgain}.
	 */
	public static void reload(Path gameDirectory) {
		// Forgotten before the reading and not restored after it, which is the only order that
		// works: the decision to print is taken inside load, so anything put back once load has
		// returned changes nothing but the name of a pack that has already been reported. Every
		// caller of this method is somebody asking for the pack to be read again, and a pack edited
		// on the disk under the same name would otherwise be reported once for the whole session.
		reported = null;
		readAgain(gameDirectory);
	}

	/**
	 * The reading itself, which knows nothing of the report: what each of its two callers has
	 * already forgotten is what decides whether one is printed.
	 */
	private static void readAgain(Path gameDirectory) {
		// The chain stops answering BEFORE anything of it is freed, and the order is the whole
		// of issue 111. Every accessor below reads this field, and a pass of the frame in
		// hand asks one of them: the sky asks for its own draw as it opens its render pass.
		// Freed first, that pass was handed a chain whose colour targets had just been closed
		// and built its descriptor on the views of them, which is a pointer into freed memory
		// and a lost device seconds later. Cleared first, the same pass finds nothing, and
		// falls back to the game's own shader for the one frame it takes to reload.
		// The stop is lifted with it, so that a pack that failed to compile can be fixed and
		// tried again without leaving the game.
		PackChain previous = PackChain.takeDown();

		if (previous != null) {
			// Guarded like the same call in leaveWorld, and the three lines below are why this road
			// needs it more than that one: the load and both settle() calls stand after it, and a
			// throw leaving here skips all three, which leaves a session drawing no pack at all with
			// the reload still asked for. Where the throw goes then depends on which caller asked.
			// The world moving under the pack reaches this from the first line of draw(), which
			// stands outside its try; the settings screen, the pack key and the shadow map scale
			// reach it from their own event, outside any frame. None of the four is under a catch.
			//
			// Nothing is disabled here, where leaveWorld's catch disables: that one keeps the chain
			// it failed to free and has to stop it being drawn, while this one has already dropped
			// its chain and is about to read a pack the failure says nothing about.
			try {
				previous.release();
			} catch (GpuDeviceLossException e) {
				// The one thing a failed free can raise that is not about the free. The game's
				// Vulkan backend raises it wherever a call comes back VK_ERROR_DEVICE_LOST
				// (VulkanUtils.crashIfFailure) and catches it in no place at all, which is what
				// makes it the end of the session rather than the end of a release: the load below
				// would allocate its targets against a device that is gone. Rethrown as it stands,
				// so the report names the driver rather than a line about video memory.
				throw e;
			} catch (RuntimeException e) {
				// Said here rather than left to whatever comes next, and said once: the chain is
				// unreachable by the time this is caught, so nothing frees the rest of it later and
				// no other line would ever name what it still holds. What it holds is buffers and
				// images; the pipelines and shader modules of that load are not part of it, since
				// the next resource reload empties the device cache whether a release reached them
				// or not.
				Vitrail.logger().error("Vitrail could not hand back everything the pack being "
						+ "replaced held, so the buffers and images its release had not reached "
						+ "stay allocated until the game is closed", e);
			}
		}
		load(gameDirectory);
		// Taken whatever the load did with them. A pack that cannot be read at all settled nothing,
		// and without these it would be read again on the very next frame, and every frame after
		// that, for as long as the player stays where it failed.
		PackDefines.settle();
		PackPlace.settle();
	}
}
