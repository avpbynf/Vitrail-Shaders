package dev.vitrail.render;

import dev.vitrail.dh.DhLods;
import dev.vitrail.glsl.LoadClock;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslationCache;
import dev.vitrail.mixin.GpuDeviceAccessor;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.PackLoader;
import dev.vitrail.pack.source.PackReport;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetDirectives;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.pack.texture.CustomStorage;
import dev.vitrail.settings.PackFile;
import dev.vitrail.settings.PackSession;
import dev.vitrail.settings.SettingsFile;
import dev.vitrail.settings.SettingsLayers;
import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.screen.ScreenText;
import dev.vitrail.HostReport;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import org.joml.Vector3dc;
import org.joml.Vector3i;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * What it cannot do yet has to be said rather than covered up. The families that still come from
 * the game reach the pack's first target through {@link SceneSeed}, carrying the game's finished
 * frame, and every other buffer starts from its clear colour underneath them: a pass reading
 * normals or a material id off one of those pixels reads nothing of the sort. Which families those
 * are is {@link #announceSeed}'s to say and not this comment's, a list written twice being a list
 * that drifts, and it is not always said at all, a place with no seed in its plan and a run with
 * the seed switched off both leaving the target on its clear colour instead. What the seed costs
 * the image is in {@code docs/frame.md}.
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

	private static final String BLOCK_LABEL = "Vitrail OfGlobals";
	private static final String QUAD_LABEL = "Vitrail quad";

	/**
	 * The name {@code pack.txt} takes that is not the name of a pack: draw none of them, and leave the
	 * game its own image. It is read after the whole names and before the fragments, so a folder really
	 * called {@code none} is still reachable and a pack whose name merely holds the word cannot answer
	 * for it.
	 */
	public static final String NO_PACK = PackFile.NONE;

	private static volatile PackChain active;
	private static volatile boolean disabled;

	/** The pack the report was last taken of, so that a portal does not pay for it a second time. */
	private static volatile Path reported;
	private static volatile PackSession session;
	private static volatile String lastError;
	private static volatile List<String> removed = List.of();
	private static volatile boolean packsFirst = true;
	private static volatile boolean chainWanted = true;
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

	/**
	 * Whether this frame's values have been moved on yet.
	 * <p>
	 * A terrain program runs during the world and reads the same block the chain reads after it, so
	 * whichever of the two comes first opens the frame and {@link #draw} closes it. Two advances in
	 * one frame would shift the previous frame's matrices twice and make every {@code smooth()} in
	 * the pack fade at twice the speed, with nothing on screen to say so.
	 * <p>
	 * The chain's own and not the class's, because the store it guards is the chain's own. A reload
	 * runs at the top of {@link #draw}, in the middle of a frame the terrain has already opened, and
	 * a flag held for the class would hand the chain that replaces it a value store still standing
	 * where it was built: identity matrices, and a view of no width at all.
	 */
	private boolean advanced;

	/**
	 * Whether this frame's colour targets have been allocated and cleared yet. Held beside
	 * {@link #advanced} and reset with it: the two answer the same question, "has this frame been
	 * opened", for the values and for the targets.
	 */
	private boolean opened;

	/**
	 * The block the camera stood in when the shadow stage last refilled the pack's cleared volumes,
	 * which is the origin the identities in them are measured from.
	 * <p>
	 * Not a per frame flag, and that is why it is not reset with the four below: it belongs to the
	 * volume rather than to a frame, and the frame that reads it is never the one that wrote it.
	 */
	private final Vector3i voxelAnchor = new Vector3i();

	/** Whether {@link #voxelAnchor} names a real fill rather than the zero it starts at. */
	private boolean voxelAnchored;

	/**
	 * Whether this chain has ever opened a frame, which is a different question from
	 * {@link #advanced} and is asked by exactly one caller.
	 * <p>
	 * A chain replacing another does so at the head of {@link #draw}, so it is BUILT in the middle
	 * of a frame whose shadow stage is still to come, and while it warms its pipelines it compiles
	 * one a frame and draws nothing at all, so its value store never moves. The camera it would
	 * report through those frames is the zero a fresh store starts at, and an anchor taken there
	 * names the origin of the world rather than where the player stands.
	 */
	private boolean everAdvanced;

	/** Whether the half of the chain that belongs before the world's translucents has run. */
	private boolean early;

	/** Whether this frame's uniform blocks have been written and its notes said. */
	private boolean filled;

	/**
	 * Whether the game's own frame has already been painted in this one. The seed's rank falls on
	 * the boundary between the two halves whenever a place ships no deferred, so both halves reach
	 * it, and it is the earlier one that has it: this is what keeps the later one from painting the
	 * world a second time, over everything drawn since.
	 */
	private boolean seeded;

	/**
	 * Whether this frame's whole scene depth has been kept yet.
	 * <p>
	 * <strong>There are two moments it can be kept at and they are not equivalent.</strong> The
	 * game's always-on-top pass clears the world's depth before it draws, so from the moment that
	 * pass exists the depth left standing after the world is the far plane over the whole screen:
	 * the fog, the depth of field, the ambient occlusion and the volumetric light of the pack then
	 * all work on a scene made entirely of sky, and not one of them fails. {@link #markSceneDepth}
	 * therefore keeps it at the head of that pass, and {@link #run} keeps it whenever nothing kept it
	 * there, which is normally the frames that pass does not exist on.
	 */
	private boolean sceneDepth;

	/** Whether the split of the chain into two halves has been said. Once a load, not once a frame. */
	private boolean split;

	/**
	 * Which of the two moments the scene's depth was kept at has been said. Once a load each, like
	 * {@link #split}, and one each rather than one saying whichever came last: the answer follows
	 * whether the game has a gizmo to draw over the world, so it moves with F3+G rather than
	 * settling, and a line at every change would be a line a frame.
	 */
	private boolean saidBeforeClear;
	private boolean saidAfterTheWorld;

	private final PackProgram.Chain chain;
	private final PackValues values;
	private final String world;
	private final ColorTargets targets;

	/** Fills the mip chains of the targets the programs of this place read at a lod. */
	private final MipmapReduction mipmaps = new MipmapReduction();

	/**
	 * The chains {@link #drawRange} has filled and nothing has written over since. Held by the
	 * surface itself and never by target and side, because the two spellings do not name surfaces
	 * one to one: a target nobody doubles answers its main surface for either side, and a write
	 * filed under one spelling has to evict what the other spelling filled. One object for the
	 * frame rather than one per range, cleared where the walk says so: what it remembers is only
	 * ever true inside one walk.
	 */
	private final Set<TargetSurface> currentChains = new HashSet<>();

	/** What colortex0 is emptied to, refilled once a frame. One object, because a clear is a frame. */
	private final Vector4f fogClear = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);

	private final SceneSeed seed;
	private final boolean seedEnabled;

	/** The game's translucent features, caught and composed onto the pack's image. Null when the
	 * pack serves no translucent pass to compose in front of. */
	private final FeatureLayer features;
	private final int load;
	private final TerrainDraw terrain;
	private final SkyDraw sky;
	private final EntityDraw entities;
	private final CloudDraw clouds;
	private final WeatherDraw weather;
	private final ParticleDraw particles;

	/** Distant Horizons' far terrain, drawn with the pack's own programs where DH is there at all. */
	private final DistantDraw distant;

	/** Shadow compute, dispatched after the shadow map, Complementary's floodfill among them. */
	private final PackCompute compute;

	private List<PackPass> programs;
	private PackPass last;

	/** Whether any program of this chain reads centerDepthSmooth, settled once the passes are built. */
	private boolean centerDepthRead;

	private MappableRingBuffer block;
	private GpuBuffer quad;
	private CompiledRenderPipeline head;
	private int blockBytes;
	private int warmed;
	private boolean familyPrefetchStarted;
	private volatile int familiesReady;

	/**
	 * Guards the six family program maps against the one pair of threads that ever touches
	 * them at once: the pack-load worker copying the family it has just read, and the render
	 * thread emptying every family when the pack goes. A plain map read while another thread
	 * clears it is undefined rather than merely unlucky, so neither side is left to chance.
	 */
	private final Object familyMaps = new Object();

	/**
	 * Raised by {@link #release()} and read by the pack-load worker on both of its stages, which is
	 * what stops a worker still working for a chain nothing will ever draw again:
	 * {@link #warmFamily} reads it between two programs and {@link #prefetchFamily} between two
	 * families. Volatile for those cross-thread reads; everything else about the release stays on
	 * the render thread.
	 */
	private volatile boolean released;

	/**
	 * Every pack-load worker still running, whichever chain started it, held for the one caller
	 * that must see them out: shutdown. A release only raises its chain's {@link #released} and
	 * moves on, the worker stopping at its next program; and a pack swap detaches its chain
	 * without waiting either, so the worker shutdown must see out is not always the active
	 * chain's. Shutdown tears the device down behind them, and a device call still in flight then
	 * is a crash at exit, so {@link #close} waits the set out, bounded.
	 */
	private static final Set<CompletableFuture<Void>> WARMUPS = ConcurrentHashMap.newKeySet();

	/**
	 * Whether this chain's pack-load workers are done, whatever they managed: what moves
	 * {@link #extractCompileIcon} from its pulse to its closing words. Raised on every road out
	 * of the workers, the refused and the stopped included, because a mark that can never go
	 * out is worse than one that goes out early.
	 */
	private volatile boolean familiesWarmed;

	/**
	 * When the workers finished, written just before {@link #familiesWarmed} on each of its
	 * roads and published by that volatile write: what the closing words are timed from, so a
	 * corner hidden long enough behind F3 misses the show instead of replaying it stale.
	 */
	private long warmedAt;

	/**
	 * The progress the corner's words carry: how many family programs the compile tasks have
	 * walked, out of how many the finished translations have put on their plates. The total
	 * grows family by family, the way a loading bar's does, from the one translation worker; it
	 * is atomic for the lint's peace of mind, the render thread only ever reading it. It stays
	 * nought when no task was spawned at all, which is what keeps the words bare rather than
	 * stuck at "0 of N".
	 */
	private final AtomicInteger warmWalked = new AtomicInteger();
	private final AtomicInteger warmServed = new AtomicInteger();
	private final AtomicInteger warmTotal = new AtomicInteger();
	private boolean geometryReady;
	private boolean announced;

	/**
	 * Whether {@link #openFeatures()} really posed the game's overrides, which is the one thing
	 * {@link #closeFeatures()} may take back down and compose on.
	 * <p>
	 * Held here rather than read back off {@code RenderSystem.outputColorTextureOverride}. That field
	 * is the game's own and the game sets it for its own always-on-top features, and
	 * {@link #openFeatures()} has a reason of its own to refuse, so the two questions are not one.
	 * Read off the game's, a refused frame composes a layer nothing drew into, the frame before's,
	 * the clear living in the open that refusal skipped.
	 */
	private boolean redirected;

	private PackChain(PackProgram.Chain chain, PackValues values, String world, boolean seedEnabled,
			OpenedPack opened, Path packPath, Map<String, OptionValue> chosen, String profile) {
		this.chain = chain;
		this.values = values;
		this.world = world;
		this.seedEnabled = seedEnabled;
		this.load = LOADS.incrementAndGet();

		// None of this touches the device: the textures are allocated by the first frame and this
		// runs while the client is still starting up, off the render thread.
		this.targets = new ColorTargets(chain.targets(), values.noiseResolution(),
				values.noiseImage(), values.packImages(), values.storageImages(),
				values.shadowResolution(), values.shadowColours());
		this.seed = chain.chain().seed()
				.filter(where -> this.targets.has(where.target()))
				.map(where -> new SceneSeed(where, this.targets.format(where.target()),
						seedExtras(chain, where)))
				.orElse(null);
		// Composed where the world's own translucents are about to blend, so it needs that pass to
		// exist: a pack serving no translucent geometry gets no layer, and the game's features stay
		// where the game drew them.
		this.features = chain.chain().geometry(TerrainPass.TRANSLUCENT)
				.map(ChainPlan.Pass::attachments)
				.filter(attachments -> !attachments.isEmpty())
				.map(attachments -> attachments.get(0))
				.filter(into -> this.targets.has(into.target()))
				.map(into -> new FeatureLayer(into, this.targets.format(into.target())))
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
		this.terrain = new TerrainDraw(this, packPath, chain.place(), values,
				this.load, chain.chain(), chain.targets(), chainWanted, this.targets);
		// The same plan and the same schedule again, and for the same reason. The sky is read on
		// demand too, since the game builds its meshes once at startup and a place that never draws
		// a sky should not pay for one.
		this.sky = new SkyDraw(this, packPath, chain.place(), chosen, profile, values, this.load,
				chain.chain(), chain.targets(), chainWanted, this.targets);
		// And again, for the same reason, and read on demand for a third one: a place the player
		// crosses without an entity in it should not pay for ten programs it never draws.
		// Handed the seed's own switch as well, which neither of the other two needs: it is the one
		// family whose first output has no road of its own into the pack's picture.
		this.entities = new EntityDraw(this, packPath, chain.place(), chosen, profile, values,
				this.load, chain.chain(), chain.targets(), chainWanted,
				seedEnabled && this.seed != null, this.targets);
		// And a fourth time, read on demand like the last two: a place with the clouds switched off,
		// which is every Nether and every player who turned them off, should not pay for a program
		// nothing draws.
		this.clouds = new CloudDraw(this, packPath, chain.place(), chosen, profile, values,
				this.load, chain.chain(), chain.targets(), chainWanted, this.targets);
		// And once more, read on demand for a fifth reason: a pack may be loaded for an hour before
		// it rains. It needs no switch of the seed's, being the one family here drawn WHOLLY after
		// the deferred stage: it blends onto what the chain has already put in the pack's target,
		// which is the position the world's own translucents are in. The particles below straddle
		// that stage instead, and that is why they need the switch and this does not.
		this.weather = new WeatherDraw(this, packPath, chain.place(), chosen, profile, values,
				this.load, chain.chain(), chain.targets(), chainWanted, this.targets);
		// And the sixth, which needs the seed's switch as the entities do and only for half of
		// itself: its opaque half is drawn among the game's solid features, before the deferred
		// stage, and that half's first output has the same one road into the pack's picture.
		this.particles = new ParticleDraw(this, packPath, chain.place(), chosen, profile, values,
				this.load, chain.chain(), chain.targets(), chainWanted,
				seedEnabled && this.seed != null, this.targets);
		// And the seventh, read on demand like the five before it and for the sharpest reason of
		// them: most sessions have no Distant Horizons at all, and the ones that do only reach this
		// on the frames DH really draws a far terrain.
		this.distant = new DistantDraw(this, packPath, chain.place(), chosen, profile, values,
				this.load, chain.chain(), chain.targets(), chainWanted, this.targets);
		this.compute = PackCompute.load(opened, chain.place(), chain.targets().computes(), this.load,
				values.shadowGeometryCatalog());
	}

	/**
	 * What the scene seed still has to carry across, named family by family, for the one line of the
	 * log that publishes this engine's scope.
	 * <p>
	 * <strong>Built from the switches and never written out</strong>, which is the whole point: the
	 * sentence this feeds is what {@code docs/} sends a reader to for what the engine draws, and a
	 * hand-written list goes false the day a family lands. It did, once, in both directions at
	 * once - it went on naming the weather after the weather had landed, and it never named the
	 * particles at all.
	 * <p>
	 * The entities are the one family that is here in both states, their blending half never having
	 * landed at all. Every other switch names its family only when it is off.
	 * <p>
	 * <strong>Every family the seed carries that has no line of its own, and the sky is one of
	 * them.</strong> It was left out of the first version of this method, which is the same fault as
	 * the hand-written list in a newer dress: with {@code sky=off} the game draws its own sky and the
	 * seed is what carries it, exactly as it carries the entities, the clouds, the weather and the
	 * particles, and a list that names four of those five is stale by construction rather than by
	 * editing.
	 * <p>
	 * <strong>The terrain is the switch that is deliberately not here</strong>, and it is the one
	 * exception the rule above needs. With {@code terrain=off} the seed does carry the world, but the
	 * two lines printed straight after this one already say exactly that: the mask is what stops the
	 * seed, a frame where no terrain program of the pack ran leaves it empty, and the second of those
	 * lines says so in the pack's own terms. Naming it here as well would be the same fact twice.
	 */
	private static String stillTheGame() {
		List<String> carried = new ArrayList<>();
		if (!EntityDraw.wanted()) {
			carried.add("the entities");
		}

		if (!HandDraw.wanted()) {
			// The one entry the seed does not in fact carry, and it is named all the same because the
			// sentence is about what the picture still owes the game. Off, the hand is drawn after the
			// chain rather than into it, so it is painted straight onto the finished image and no seed
			// is involved; EngineOptions.announceHandOff is where that difference is spelt out.
			carried.add("the hand");
		}

		if (!SkyDraw.wanted()) {
			carried.add("the sky");
		}

		if (!CloudDraw.wanted()) {
			carried.add("the clouds");
		}

		if (!WeatherDraw.wanted()) {
			carried.add("the weather");
		}

		if (!ParticleDraw.wanted()) {
			carried.add("the particles");
		}

		if (EntityDraw.wanted()) {
			// Named apart, because a reader who has just turned the entities on and still sees a flat
			// player would otherwise have nothing to go on: the opaque ones go through the pack and
			// the ones that blend do not.
			carried.add("the entities that blend, the player's own body among them,");
		}

		// The conjunction the hand-written sentence carried, kept: this is a line of prose in the log
		// and a bare comma list reads as a truncated one. The branch for a shorter list is not dead
		// and is in fact the ordinary answer with every switch on: the entities put one of their two
		// strings in either way, and every other family here names itself only when it is off.
		if (carried.size() < 2) {
			return String.join("", carried);
		}

		return String.join(", ", carried.subList(0, carried.size() - 1)) + " and "
				+ carried.get(carried.size() - 1);
	}

	/**
	 * The draw buffers the seed has to empty besides the one the scene itself goes into, which are
	 * the rest of the ones its geometry program declares.
	 * <p>
	 * A gbuffers program writes all of its targets at once and the seed stands in for one, so
	 * leaving the rest alone is what left a pixel carrying the game's colour over the gbuffer of
	 * whatever the pack had drawn behind it. The list is asked of the same walk that gave the seed
	 * its target, so the two agree by construction; when it disagrees all the same, which is a place
	 * where the terrain is served through a name the walk was never asked about, nothing is claimed
	 * and the seed writes the one target it always did.
	 * <p>
	 * Three kinds of draw buffer are left out, because the seed empties a target by writing the
	 * clear colour the pack declared for it and for these that value is not what an empty pixel
	 * holds: colortex0 when the pack named no colour of its own, the renderer starting that one at
	 * the fog of the frame; any target of an integer format, which a {@code vec4} output does not
	 * write at all; and any target the pack keeps from one frame to the next, where an empty pixel
	 * is last frame's pixel and {@code colortexNClear} says so.
	 */
	private List<SceneSeed.Extra> seedExtras(PackProgram.Chain chain, ChainPlan.Seed where) {
		List<ChainPlan.Attachment> attachments = chain.chain()
				.geometryOf(where.from(), false)
				.map(ChainPlan.Pass::attachments)
				.orElse(List.of());
		if (attachments.size() < 2 || attachments.get(0).target() != where.target()
				|| attachments.get(0).side() != where.side()) {
			return List.of();
		}

		// The clear colours are read off the plan and not off ColorTargets, which reads them off the
		// plan itself: the decision is the plan's, and asking it is asking the one place that holds
		// it rather than adding a second.
		TargetDirectives directives = chain.targets().directives();
		List<SceneSeed.Extra> extras = new ArrayList<>();
		for (ChainPlan.Attachment attachment : attachments.subList(1, attachments.size())) {
			int index = attachment.target();
			if (!this.targets.has(index) || !directives.clears(index)
					|| directives.format(index).used().integer()
					|| (index == 0 && !directives.declaresClearColour(index))) {
				continue;
			}

			TargetDirectives.Colour empty = directives.clearColour(index);
			extras.add(new SceneSeed.Extra(index, attachment.side(), this.targets.format(index),
					new Vector4f(empty.r(), empty.g(), empty.b(), empty.a())));
		}

		return extras;
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
		// Taken before anything reads the folder, so that the count printed at the end of the load
		// covers every opening the load made and not only the translation's.
		int openings = ShaderPackSource.openings();
		session = null;
		lastError = null;
		removed = List.of();
		packsFirst = true;
		packOff = false;
		packMissing = false;
		// Emptied before the read below rather than after it, so that a file that cannot be read at
		// all leaves the screen naming nothing rather than whatever the load before it asked for.
		askedFor = PackFile.EMPTY;
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

			// Where the pack is known, rather than over the whole folder before one was chosen, and
			// once per pack rather than at every load. This reading walks the whole archive, and
			// this method is the road every reload takes: a portal and an Apply both come back
			// through here, on the render thread, and neither has anything new to report.
			//
			// Never fatal either, and that is the whole reason for the catch. It is a report and not
			// the drawing, so its own failures stop here: letting one reach the catch below would
			// turn a diagnosis that could not be taken into a pack that is not drawn.
			if (!pack.equals(reported)) {
				try {
					PackReport.log(PackLoader.load(pack));
					reported = pack;
				} catch (IOException | RuntimeException e) {
					// No promise about what happens next, deliberately: an archive that cannot be
					// opened at all will not be opened by the lines below either, and the catch at
					// the end of this method is what will have the last word. What this line buys is
					// the failure that belongs to the report alone, in a measurement or a count, and
					// it is said by the report so that it keeps the report's own prefix.
					PackReport.couldNotRead(ShaderPackSource.nameOf(pack), e);
				}
			}

			SettingsLayers.Resolved settings = open(gameDirectory, pack);

			Map<String, OptionValue> chosen = new LinkedHashMap<>(settings.chosen());
			// Reserved keys rather than options: no pack declares a setting under any of these
			// names, and each names something this mod does rather than a value the pack has. The
			// fourth, profile, never reaches here: the settings layer takes it out and carries it
			// apart, because it is the side that writes it back into the pack's own file.
			EngineOptions.Read engine = EngineOptions.take(chosen);
			packsFirst = engine.packsFirst();
			chainWanted = engine.chain();

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
			// Only the names this engine has not built refuse the pack: custom images are served
			// now, so a pack that cannot draw without them is simply right about what it needs.
			if (CustomImages.served()) {
				required.remove("CUSTOM_IMAGES");
			}

			if (!required.isEmpty()) {
				disabled = true;
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

			try (OpenedPack opened = OpenedPack.open(pack, chosen, settings.profile())) {
				// The world decides the directory, and the pack decides which world that is: a folder
				// may be named anything and mapped in dimension.properties, so the name is read from
				// the pack rather than composed from the dimension.
				String place = PackPlace.place(opened.source());
				String world = PackPlace.world();

				// Before the translation and not after: this is what installs the machine's own
				// symbols, and the biome ones among them decide which branch of the pack compiles.
				PackValues values = PackValues.read(opened, place);

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
				active = new PackChain(chain, values, world, engine.seed(), opened, pack, chosen,
						settings.profile());
				// Terrain first, and it finishes before the other families start: two readers on one
				// zip at once is a race, and Sodium takes the mesh format here before any pass asks
				// for a shader. The other families then translate on a worker, so Complementary
				// Unbound's entities overlap the composite compiles and never sit on the render thread.
				active.terrain.read(opened);
				// After the two reads this thread makes and before the worker starts on the rest,
				// so the count on the line is a number rather than a snapshot of a tally still
				// moving.
				DriverTrig.announce();
				// The count, so that what a load costs is a figure in the log rather than a feeling.
				// It covers the whole of this method, the report and the settings reading included,
				// and the families are deliberately outside it: they translate on a worker, off the
				// thread the player is waiting on, and each opens the pack for itself.
				Vitrail.logger().info("Opened {} {} times to load it", chain.packName(),
						ShaderPackSource.openings() - openings);
				// Once per installed chain, whatever else this load does or fails to do later:
				// the second report, with the families in, only exists when a warmup fans out.
				// Translation only, because this method does no device work by design: the
				// modules follow on the first draws and the workers, and land in the next report.
				Vitrail.logger().info("Translating cost {} ms over {} translator calls before "
						+ "the chain went live; the modules follow on the draws and the workers, "
						+ "counted into the report that closes the warmup",
						LoadClock.translationMillis(), LoadClock.translated());
				// Both ways, whichever way it went. A cache that only speaks when it helps leaves
				// every later reading of a log ambiguous, since a silence would then mean either
				// that nothing was served or that nothing was asked. It rides the line above rather
				// than standing on its own, so a load this method turned back from is silent on
				// both counts rather than on one of the two.
				Vitrail.logger().info("Translation cache: {} programs served from disk, {} "
								+ "translated, counted at the same point of the load as the line "
								+ "above", TranslationCache.served(), TranslationCache.translated());
			}

			active.startFamilyPrefetch();
			turnOffImprovedTransparency();
			if (!chainWanted) {
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
			disabled = true;
			lastError = "Could not prepare this pack: " + e;
			Vitrail.logger().error("Vitrail could not prepare a pack's chain", e);
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
	 * Whether a pack is loaded and still drawable.
	 * <p>
	 * Iris asks its config; this engine asks the chain. Same question: is a pack drawing, so the
	 * game's own improved-transparency path must stay off.
	 */
	public static boolean drawingPack() {
		return active != null && !disabled;
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
		if (!drawingPack()) {
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
		if (error == null && disabled) {
			error = "This pack stopped drawing after an error, see the log";
		}

		return Optional.ofNullable(error);
	}

	/**
	 * Stops drawing this pack at all, for a reason found too late to refuse it at the load.
	 * <p>
	 * <strong>All of it and not the one family that cannot be served</strong>, because a pack drawing
	 * half a world is worse than a pack drawing none of it: the game's own picture and the pack's own
	 * are both credible on their own, and an image made of the two is credible and wrong. The one this
	 * exists for puts the sky in front of the trees, and it is read as a broken sky rather than as a
	 * family that never drew.
	 * <p>
	 * <strong>What this does NOT do is hand the colour targets back</strong>, where the two other
	 * places that stop a pack mid-session do. It cannot: it runs inside the chunk pass the renderer
	 * opened, and releasing a target there tears down what that pass is drawing into. So whatever was
	 * allocated stays held until the next load, and how much that is has not been measured, because
	 * whether the chain had warmed by then depends on how many frames drew no section at all.
	 *
	 * @param why said in the words a player reads on the settings screen, since that is where it goes
	 */
	void putAway(String why) {
		if (disabled) {
			return;
		}

		disabled = true;
		lastError = this.chain.packName() + " is not drawn at all: " + why;
		Vitrail.logger().error("{} is put away rather than drawn by halves, because {}",
				this.chain.packName(), why);
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
	private static void reloadIfTheWorldMoved(Path gameDirectory) {
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
	 * Throws away the current chain and reads it again from disk, everything included: the pack
	 * named by {@code pack.txt}, the engine's own options, and the pack's settings file.
	 * <p>
	 * Render thread only: {@link #release()} closes GPU buffers and hands back the colour targets,
	 * which has to happen where {@code draw} runs and outside any render pass. The settings screen
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
		PackChain previous = active;
		active = null;
		// Cleared, so that a pack that failed to compile can be fixed and tried again
		// without leaving the game.
		disabled = false;

		if (previous != null) {
			previous.release();
		}
		load(gameDirectory);
		// Taken whatever the load did with them. A pack that cannot be read at all settled nothing,
		// and without these it would be read again on the very next frame, and every frame after
		// that, for as long as the player stays where it failed.
		PackDefines.settle();
		PackPlace.settle();
	}

	/**
	 * Called from the loader module once the world has been rendered.
	 *
	 * @return whether a pack was drawn, so that the caller knows to fall back to its own chain.
	 *         The world check runs first and before the refusal below, or a pack that failed to
	 *         compile would never be read again against the registries joining a world gives it.
	 */
	public static boolean draw(Path gameDirectory) {
		reloadIfTheWorldMoved(gameDirectory);

		PackChain chain = active;
		if (disabled || chain == null) {
			// The frame is closed whatever happened: a terrain program may have opened it during the
			// world even when nothing of the chain is drawn afterwards, and a flag left standing
			// would stop the values ever moving again, or the targets ever being cleared again if
			// this pack is turned back on.
			if (chain != null) {
				chain.closeFrame();
			}

			return false;
		}

		try {
			if (chainWanted) {
				chain.run();
			} else {
				// All a frame owes when the chain itself is not drawn. The ring buffers are not part
				// of it: closeFrame turns them below, drawn or not.
				chain.beginFrame();
			}
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail stopped drawing this pack after an error", e);
			chain.release();
		}

		// Outside the try: a frame that failed halfway still owes its flags and its ring buffers.
		chain.closeFrame();
		if (chainWanted && chain.drawable()) {
			PassTimings.finishCensus();
		}

		return chainWanted;
	}

	/**
	 * A pack is loaded and still compiling, so the world must not be drawn. Skipping it is what
	 * keeps this wait from being a two-frame-per-second picture of the same vanilla terrain. The
	 * HUD stays up, and {@link #extractCompileIcon}'s mark and its words say why.
	 */
	public static boolean warming() {
		PackChain chain = active;
		if (disabled || !chainWanted || chain == null) {
			return false;
		}

		// An empty composite list will never become drawable. Skipping the world for it would
		// leave the player in a black pause with nothing left to compile.
		if (chain.programs != null && chain.programs.isEmpty()) {
			return false;
		}

		return !chain.drawable();
	}

	/**
	 * Compiles as many programs as a short budget on this frame will take, then returns. One
	 * program a frame was the wait the player sat through at two frames per second; several a
	 * frame, with the world not drawn, is the same work without that picture.
	 * <p>
	 * Complementary Unbound is still not compiled all at once. Its families translate on a worker
	 * while this thread compiles the composites and the terrain, and each family's pipelines then
	 * compile on a small pool of the engine's own; nothing of the leftovers holds the world back.
	 */
	public static void pumpWarmup() {
		PackChain chain = active;
		GpuDevice device = RenderSystem.tryGetDevice();
		Minecraft minecraft = Minecraft.getInstance();
		if (disabled || !chainWanted || chain == null || device == null || minecraft == null) {
			return;
		}

		if (chain.drawable()) {
			return;
		}

		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		if (main == null || main.getColorTexture() == null) {
			return;
		}

		long deadline = System.nanoTime() + WARM_BUDGET_NANOS;
		do {
			if (disabled) {
				return;
			}

			if (chain.programs == null) {
				chain.build(device);
			}

			if (!chain.prepare(device, main) || !chain.warm(device)) {
				continue;
			}

			return;
		} while (System.nanoTime() < deadline);
	}

	/** The mod's own mark, pulsing in a corner while the pack compiles. */
	private static final Identifier COMPILE_ICON =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "textures/gui/compiling.png");

	/** One pulse per second: full, down to half opacity, and back. */
	private static final long PULSE_MILLIS = 1000L;

	private static final int ICON_EDGE = 16;
	private static final int ICON_TEXTURE = 32;
	private static final int ICON_MARGIN = 3;

	/** How long the closing sentence stands beside the mark, then the fade that erases both. */
	private static final long DONE_MILLIS = 2500L;
	private static final long FADE_MILLIS = 800L;

	/** Every appearance eases in over this, the way the whole corner eases out at the end. */
	private static final long RAMP_MILLIS = 300L;

	/**
	 * The floor under which nothing is drawn at all: the font renderer keeps the old convention
	 * of reading a near-nought alpha as fully opaque, so a fade has to end by not drawing
	 * rather than by popping back to full.
	 */
	private static final float FAINT = 0.05F;

	/**
	 * When this chain's mark first showed, the fade-in's zero. Render thread only, nought until
	 * then, and per chain, so a reload eases in afresh.
	 */
	private long iconShownAt;

	/**
	 * Draws the mod's mark in the top-left corner for as long as the pack compiles, the way the
	 * old autosave floppy used to blink, with the words of the moment beside it: the mark
	 * pulses next to "Compiling shaders..." through the held world and the background compiles
	 * alike, the walked-out-of-total count riding along once the compile tasks have a plate,
	 * then stands still next to "Shaders compiled!" once the workers finish, and the whole
	 * corner fades away. The mark's first appearance eases in and the end eases out, a corner
	 * re-shown after F3 or F1 coming back plain; the closing show is timed from
	 * {@link #familiesWarmed} rising, so a corner hidden long enough behind those misses the
	 * show rather than replaying it stale.
	 * <p>
	 * Reached from the HUD's own extraction, after every vanilla layer, and quiet everywhere
	 * else: no chain, a chain that can never draw, the show over, the terrain loading screen
	 * (where vanilla extracts no HUD at all and the tail of the extraction still runs), and the
	 * F3 screen, whose first lines sit exactly where the mark does.
	 */
	public static void extractCompileIcon(GuiGraphicsExtractor graphics) {
		Minecraft minecraft = Minecraft.getInstance();
		PackChain chain = active;
		if (disabled || !chainWanted || chain == null || minecraft.gui.hud.isHidden()
				|| minecraft.gui.screen() instanceof LevelLoadingScreen) {
			return;
		}

		// Under F3 the mark bows out, its corner being where the debug block's first lines sit,
		// EXCEPT while the world is held back: F3 is exactly the key a player presses at a
		// screen with no world in it, and the corner is the only thing that says why. An overlap
		// read over debug lines beats an absence read as a defect.
		if (minecraft.gui.hud.getDebugOverlay().showDebugScreen() && !warming()) {
			return;
		}

		// In flight covers both moments of a load, the held world and the background compiles;
		// a chain that is neither in flight nor drawable was refused and shows nothing.
		boolean inFlight = !chain.familiesWarmed && (warming() || chain.drawable());
		if (!inFlight && !(chain.familiesWarmed && chain.drawable())) {
			return;
		}

		long now = Util.getMillis();
		if (chain.iconShownAt == 0L) {
			chain.iconShownAt = now;
		}

		float ease = ramp(now - chain.iconShownAt);
		Font font = minecraft.font;
		int textX = ICON_MARGIN + ICON_EDGE + 4;
		int textY = ICON_MARGIN + (ICON_EDGE - font.lineHeight) / 2 + 1;

		if (inFlight) {
			float phase = (now % PULSE_MILLIS) / (float) PULSE_MILLIS;
			float pulse = 0.75F + 0.25F * (float) Math.cos(2.0 * Math.PI * phase);
			icon(graphics, ease * pulse);

			// The count rides along once the tasks have a plate: walked out of total, the total
			// growing family by family the way a loading bar's does. Bare words before that,
			// rather than a "0 of 0" that reads as stuck.
			Component words = Component.translatable(ScreenText.COMPILING);
			int total = chain.warmTotal.get();
			if (total > 0) {
				words = words.copy()
						.append(" " + Math.min(chain.warmWalked.get(), total) + "/" + total);
			}

			word(graphics, font, words, textX, textY, ease);

			return;
		}

		long since = now - chain.warmedAt;
		if (since >= DONE_MILLIS + FADE_MILLIS) {
			return;
		}

		// The workers just finished: the mark stands still and the sentence lands with it on
		// the spot, no transition, then the whole corner eases out together. The one place the
		// ramp deliberately does not apply: the switch of words IS the news.
		float out = since <= DONE_MILLIS ? 1.0F
				: 1.0F - (since - DONE_MILLIS) / (float) FADE_MILLIS;
		icon(graphics, ease * out);
		word(graphics, font, Component.translatable(ScreenText.COMPILED), textX, textY, out);
	}

	/** How far into an appearance a thing is, nought to one over {@link #RAMP_MILLIS}. */
	private static float ramp(long sinceMillis) {
		return Math.min(1.0F, sinceMillis / (float) RAMP_MILLIS);
	}

	private static void icon(GuiGraphicsExtractor graphics, float alpha) {
		if (alpha < FAINT) {
			return;
		}

		graphics.blit(RenderPipelines.GUI_TEXTURED, COMPILE_ICON, ICON_MARGIN, ICON_MARGIN,
				0.0F, 0.0F, ICON_EDGE, ICON_EDGE, ICON_TEXTURE, ICON_TEXTURE, ICON_TEXTURE,
				ICON_TEXTURE, ARGB.colorFromFloat(alpha, 1.0F, 1.0F, 1.0F));
	}

	private static void word(GuiGraphicsExtractor graphics, Font font, Component words, int x,
			int y, float alpha) {
		if (alpha < FAINT) {
			return;
		}

		graphics.text(font, words, x, y, ARGB.colorFromFloat(alpha, 1.0F, 1.0F, 1.0F), true);
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
	 * Empties the pack's cleared storage images at the top of the shadow stage, matching Iris
	 * clearing custom images before the shadow map is drawn.
	 */
	public static void clearCustomImages() {
		PackChain chain = active;
		if (disabled || chain == null) {
			return;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}

		chain.targets.clearStorage(device.createCommandEncoder());

		// Taken with the clear rather than after the geometry, because the clear is the line that
		// says the volume from here on holds THIS frame's writes and nothing older. A pack anchors
		// what it stores on the block the camera stands in, so that block is what says which cell
		// each identity lands in, and the frame reading it is not this one.
		//
		// Not taken at all from a chain that has never opened a frame: see everAdvanced. The value
		// store would answer the origin of the world, and the next frame would read a move of the
		// player's whole coordinates, which reanchor answers by emptying the volume.
		if (chain.everAdvanced) {
			Vector3dc camera = chain.values.world().cameraPositionUnshifted();
			chain.voxelAnchor.set(block(camera.x()), block(camera.y()), block(camera.z()));
			chain.voxelAnchored = true;
		}
	}

	/**
	 * Dispatches {@code shadowcomp} at the head of the frame, over the shadow geometry the frame
	 * before it wrote.
	 * <p>
	 * Iris dispatches it inside its own shadow render ({@code ShadowRenderer.java:631-632}), which is
	 * the same moment relative to the READERS: there the map is drawn and read within one frame,
	 * here the shadow stage stands at the end of a frame and the map is one frame late, so the
	 * moment that shares the frame of the gbuffers reading the volumes is this one. The caller in
	 * {@code EngineStages} carries what putting it beside the shadow map instead would cost.
	 */
	public static void dispatchShadowCompute() {
		PackChain chain = active;
		if (disabled || chain == null) {
			return;
		}

		// The frame opens HERE, not at the first draw, and the compute's correctness hangs on it.
		// The values only move at beginFrame, so without this the dispatch reads the PREVIOUS
		// frame's numbers: the floodfill then runs under the old frameCounter parity and writes
		// the half this frame's gbuffers do not read, and every voxel light flickers as the
		// player moves. Idempotent for the rest of the frame, which sees the same numbers it
		// always did, only settled a moment earlier.
		chain.beginFrame();
		chain.reanchorCustomImages();
		chain.compute.dispatch(chain.values, chain.targets);
	}

	/**
	 * Moves the identity volume onto the anchor of the frame about to read it, before the compute
	 * and before any gbuffer samples it. {@link StorageImages#reanchor} carries the whole of why,
	 * against what Iris does. This half only works out how far.
	 */
	private void reanchorCustomImages() {
		if (!this.voxelAnchored) {
			return;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}

		Vector3dc camera = this.values.world().cameraPositionUnshifted();
		int x = block(camera.x());
		int y = block(camera.y());
		int z = block(camera.z());
		this.targets.reanchorStorage(device.createCommandEncoder(), x - this.voxelAnchor.x,
				y - this.voxelAnchor.y, z - this.voxelAnchor.z);

		// Where the volume stands now, and it stays there until the shadow stage refills it.
		// Written rather than a flag lowered, and the difference is a frame that draws no shadow
		// map at all: the identities in the volume are still the last ones written, they still have
		// to follow the camera, and a second call of the same frame moves them by nothing.
		this.voxelAnchor.set(x, y, z);
	}

	/**
	 * The block a coordinate stands in, which is the origin a pack measures its voxel grid from: it
	 * writes {@code scenePos + cameraPositionBestFract}, and this is the whole part that pair leaves
	 * out. Read off the UNSHIFTED position, because {@code cameraPositionFract} is unshifted too,
	 * and that is the name {@code cameraPositionBestFract} resolves to on anything this engine
	 * answers as.
	 */
	private static int block(double coordinate) {
		return (int) Math.floor(coordinate);
	}

	/** The same, for the sky. */
	static SkyDraw sky() {
		PackChain chain = active;

		return disabled || chain == null ? null : chain.sky;
	}

	/** The same, for the entities. */
	static EntityDraw entities() {
		PackChain chain = active;

		return disabled || chain == null ? null : chain.entities;
	}

	/** The same, for the clouds. */
	static CloudDraw clouds() {
		PackChain chain = active;

		return disabled || chain == null ? null : chain.clouds;
	}

	/** The same, for the rain and the snow. */
	static WeatherDraw weather() {
		PackChain chain = active;

		return disabled || chain == null ? null : chain.weather;
	}

	/** The same, for Distant Horizons' far terrain. */
	static DistantDraw distant() {
		PackChain chain = active;

		return disabled || chain == null ? null : chain.distant;
	}

	/** The same, for the quad particles. */
	static ParticleDraw particles() {
		PackChain chain = active;

		return disabled || chain == null ? null : chain.particles;
	}

	/**
	 * Opens the frame if nothing has yet, and takes the dump with it. The one point the frame
	 * boundary hangs off; see {@link #advanced} for what a second advance would cost.
	 */
	void beginFrame() {
		if (!this.advanced) {
			this.advanced = true;
			this.everAdvanced = true;
			PassTimings.armCensus();
			this.values.advance();
			PackDump.take(this.chain.place(), this.load,
					this.programs == null ? List.of() : this.programs, this.values.world(),
					this.terrain.programs(), this.sky.programs(), this.entities.programs(),
					this.clouds.programs(), this.weather.programs(), this.particles.programs(),
					this.distant.programs());
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

		// Taken here and not held from the load: it is the fog of THIS frame, and the whole value of
		// clearing colortex0 to it is that the sky the pack has not drawn over reads as distance
		// rather than as a hole. Read after beginFrame, which every caller of this does first, so the
		// state has already been advanced onto this frame.
		WorldState world = this.values.world();
		this.fogClear.set(world.fogR(), world.fogG(), world.fogB(), 1.0F);
		this.targets.clear(device.createCommandEncoder(), this.fogClear);
		// After the clear and not before. The clear is what pays the debt a fresh allocation owes,
		// and it is the one call here that can throw; raised first, the second call of the same frame
		// found the frame opened and skipped the clear it never got, and the debt died with the
		// exception rather than with the work. Of the same frame and not of the next one: closeFrame
		// lowers the flag on every pass through draw, so what this order protects is the pair of
		// callers above, and a frame whose throw escaped before draw was reached at all.
		this.opened = true;

		return true;
	}

	/**
	 * Closes the frame. Every per frame flag is reset here and nowhere else, so that a frame that
	 * failed halfway leaves nothing standing: a flag left set stops the targets ever being cleared
	 * again, or the values ever moving again, and neither shows on screen as itself.
	 * <p>
	 * The ring buffers turn here as well, and unconditionally, which is the only place that holds.
	 * The chain draws nothing at all while its pipelines are still being compiled, one a frame and
	 * again after every resource reload, and the terrain draws throughout: turned where the chain
	 * draws, they would stand still for those frames while the terrain rewrote the very buffer the
	 * previous frame is still being read for. Nothing else guards that memory, {@link
	 * MappableRingBuffer} fencing a buffer only where it turns, and the backend keeps two submissions
	 * in flight.
	 * <p>
	 * Before the shadow stage and not after, which is what the stage is built on: it writes its
	 * blocks into the buffer this turn moved on to, and the next frame's turn is what fences them.
	 */
	private void closeFrame() {
		GeometryHold.flush(() -> "the frame before it closing");
		this.advanced = false;
		this.opened = false;
		this.early = false;
		this.filled = false;
		this.seeded = false;
		this.sceneDepth = false;

		// The depth taken before the hand is a per frame fact too, and the only one of the three
		// images that is not refilled at a fixed point of every frame: it is taken while the engine
		// draws the hand and nowhere else, so left standing it would serve the last frame that drew
		// one to every frame that did not. The image itself is handed back with the family rather
		// than with the frame, which is what the argument is.
		this.targets.depth().forgetPreHand(HandDraw.diverted());

		// The far terrain's pair is on the same per frame rule, and PackDepth says why.
		this.targets.depth().forgetDistant();

		if (this.block != null) {
			this.block.rotate();
		}

		this.terrain.rotate();
		// Only families the worker has finished translating. drawable() is true once the
		// composites and the terrain are compiled, which is earlier than leftover families
		// have been read, and walking those maps while the worker still fills them is the
		// crash at world join.
		int ready = this.familiesReady;
		if (ready > 0) {
			this.sky.rotate();
		}

		if (ready > 1) {
			this.entities.rotate();
		}

		if (ready > 2) {
			this.clouds.rotate();
		}

		if (ready > 3) {
			this.weather.rotate();
		}

		if (ready > 4) {
			this.particles.rotate();
		}

		if (ready > 5) {
			this.distant.rotate();
		}
	}

	/** Called when the client shuts down, while the device is still alive. */
	public static void close() {
		PackChain chain = active;
		if (chain != null) {
			chain.release();
		}

		awaitFamilyWarmups();

		// The far terrain's two corner rings, the one-texel constants and the comparison sampler
		// survive every release on purpose, so the shutdown is the one caller that really frees
		// them.
		DistantDraw.close();
		ConstantTextures.close();
		ShadowCompare.close();
	}

	/**
	 * Called when the client leaves a world, on the render thread and between two frames.
	 * <p>
	 * Nothing of this engine hearing about it leaves the whole of what a pack costs allocated for as
	 * long as the player sits in the menu: the colour targets, the shadow map, the two
	 * depth images, the feature layer and every ring buffer, which is about a hundred megabytes of
	 * video memory on BSL at 1080p, held for a screen that draws a panorama. What is freed here is
	 * exactly what a reload frees, and everything of it is made again by the first frame of the next
	 * world; nothing about which pack is loaded moves, so the settings screen still has one to show.
	 * <p>
	 * The first frame of that next world pays for it twice, and it is worth knowing where. The sky,
	 * the terrain and the entities all open the frame while the world is being drawn, whichever of
	 * the three comes first, so they allocate everything back
	 * before {@code draw} reaches {@link #reloadIfTheWorldMoved} at the end of it; a world joined
	 * with registries this engine has not seen then reloads and makes the same work again. One extra
	 * allocation and one extra translation, on the frame the world appears, which is the frame
	 * already carrying every other first cost there is.
	 */
	public static void leaveWorld() {
		PackChain chain = active;
		if (chain == null) {
			return;
		}

		try {
			// The line is about what goes back, so it has to be able to say that nothing does. A
			// chain that threw was released where it threw and a world left before a frame was drawn
			// never allocated anything, and either way announcing nought mebibytes returning reads
			// as a leak rather than as the release that already happened.
			long megabytes = chain.targets.bytes() / (1024L * 1024L);
			if (megabytes > 0L) {
				Vitrail.logger().info("Left the world, so the {} MiB of colour targets, the shadow map "
						+ "and the buffers of {} go back until one is joined again", megabytes,
						chain.chain.packName());
			} else {
				Vitrail.logger().info("Left the world, and {} had nothing left to hand back",
						chain.chain.packName());
			}

			chain.release();
			chain.values.leaveWorld();

			// The volumes went back with the targets and the camera went back to nought, so what
			// the anchor names no longer exists. Lowered rather than left standing: this chain is
			// NOT replaced on the way out, so nothing else would ever reset it, and a world joined
			// again would be measured against where the player stood in the one they left.
			chain.voxelAnchored = false;
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail stopped drawing this pack after an error", e);
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

		// Pipelines compile before anything of the chain is drawn. The world is not drawn at all for
		// those frames and the HUD is what the player sees, the corner's mark and its words saying
		// the pack is still compiling; drawable() keeps the terrain aside or the world would be
		// drawn into a colour target this has no final ready to bring back.
		//
		// The targets and the buffers first and the compilation second, which is not the order this
		// had. What the warm up holds back is the drawing and not the frame, so the block ring and
		// the targets are standing by the time the last program compiles rather than being allocated
		// in that one frame on top of it. The quad is made in prepare with them, and quad() says why
		// it is asked for from elsewhere as well.
		//
		// Outside any render pass, both of them: creating a texture or a buffer records a barrier
		// into the very command buffer a pass would be recording into, and the clears refuse
		// outright while one is open.
		if (!prepare(device, main) || !warm(device)) {
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
	 *
	 * @param depth   what this half's programs read as {@code depthtex0}, in the pack's own window:
	 *                the opaque world before the translucents and the whole scene after
	 * @param distant what they read as {@code dhDepthTex0}, on the same split: the far terrain
	 *                before its water and with it after, or null for the far plane on the frames the
	 *                pack drew no far terrain
	 */
	private void drawRange(GpuDevice device, Ready ready, int from, int to, GpuTextureView depth,
			GpuTextureView distant) {
		// Clamped to the list, so that the rank of a chain whose every pass runs before the world is
		// one the walk below can reach rather than one nothing ever equals.
		int seedAt = ready.seeding()
				? Math.min(this.chain.chain().seed().map(ChainPlan.Seed::at).orElse(-1),
						this.programs.size())
				: -1;

		// Each pass opens and closes its own render pass. Closing one is what makes the next able
		// to read it: the Vulkan backend ends a pass with a full memory barrier, so the cost of
		// the chain is one whole serialisation of the GPU per program and there is no way around
		// it short of knowing which passes do not overlap.
		CommandEncoder encoder = device.createCommandEncoder();
		GpuBuffer buffer = this.block.currentBuffer();
		// The chains this walk has filled and nothing has written over since, by target and side.
		// Emptied at the head because the range starts after geometry the plan does not see, and
		// again wherever a write this loop cannot place lands: the seed paints targets of its own
		// list, and the deferred emptying inside a draw clears every target still owed one.
		this.currentChains.clear();
		for (int at = from; at < to; at++) {
			if (!this.seeded && at == seedAt) {
				paintSeed(encoder, ready);
				this.currentChains.clear();
			}

			PackPass pass = this.programs.get(at);

			// Right before the first program that reads them after a write: a chain is only true
			// of the level nought it was built from, and every pass between the two may have
			// written it. The reduction opens render passes of its own, which is why this is here
			// rather than inside the draw: a pass cannot be opened while another is recording. A
			// chain filled for an earlier reader and written over by nothing since is still true,
			// so it is not refilled: two readers in a row used to pay two whole chains for one
			// image.
			for (PackPass.LodRead read : pass.lodReads()) {
				TargetSurface surface = this.targets.surface(read.target(), read.side());
				if (surface != null && !this.currentChains.contains(surface)
						&& this.mipmaps.generate(encoder, device, this.quad, surface)) {
					this.currentChains.add(surface);
				}
			}

			boolean emptying = this.targets.hasPendingClears();
			GpuBufferSlice uniforms = buffer.slice(pass.uniformOffset(), pass.uniformSize());
			if (pass == this.last) {
				pass.drawFinal(encoder, ready.mainView(), this.targets, depth, distant, this.quad,
						uniforms);
			} else {
				pass.draw(encoder, this.targets, depth, distant, this.quad, uniforms,
						ready.main().width, ready.main().height);
			}

			if (emptying) {
				this.currentChains.clear();
			} else {
				for (ChainPlan.Attachment attachment : pass.attachments()) {
					this.currentChains.remove(
							this.targets.surface(attachment.target(), attachment.side()));
				}
			}
		}

		// A rank that falls exactly on the end of this half is painted here, at its tail, and never
		// at the head of the next one. The world is drawn before the deferred stage and not after
		// it: walked as a half open range alone, a seed whose rank equals deferredEnd() - which is
		// every place that ships no deferred at all, Body Camera's overworld among them - missed the
		// first half and led the second, and was painted at AfterLevel over the water, the terrain
		// and the particles the pack had just written.
		//
		// The same line still covers the place whose whole chain runs before the world: its rank is
		// the length of the list, so it equals the end of the last half.
		if (!this.seeded && seedAt >= from && seedAt <= to) {
			paintSeed(encoder, ready);
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
	 * it reads nought. Run after the world, that read finds a clear
	 * colour and the water is thrown away in its entirety.
	 * <p>
	 * Called once a frame, from {@code AfterOpaqueFeatures}, which is the moment the game has
	 * finished its opaque features and has drawn nothing translucent. Called from {@link #run} as
	 * well, for the frames where that moment came and went without this being able to draw, and the
	 * second call is free. <strong>Nothing calls it from the terrain</strong>, whatever an older
	 * reading of this line said: the moment this runs at is what decides which depth the scene seed
	 * is cut against, so a wrong sentence here sends a reader chasing the wrong frame.
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

	/**
	 * Takes the far terrain's depth as it stands with its opaque half in, converted into the window
	 * the pack reads, at the head of the moment the game has finished its opaque features.
	 * <p>
	 * <strong>The moment is Iris's.</strong> Its {@code dhDepthTex1} is a copy of DH's depth taken
	 * before the translucent LODs are drawn ({@code compat/dh/DHCompatInternal.java:260-269}), and
	 * that boundary falls here: the far terrain's opaque half was drawn from inside the game's own
	 * opaque chunk pass, long before this, and its water half is drawn from the head of the game's
	 * translucent chunk group, which is still ahead. Every reader of the early half is downstream
	 * too, the deferred stage first of all.
	 * <p>
	 * Only when a pack is being drawn, and only on the frames the pack really drew the far terrain:
	 * on every other frame the {@code dhDepthTex} names keep answering the far plane, and every
	 * Distant Horizons branch of the pack stays shut, exactly as without the mod.
	 */
	public static void takeDistantDepth() {
		PackChain chain = active;
		GpuDevice device = RenderSystem.tryGetDevice();
		Minecraft minecraft = Minecraft.getInstance();
		if (disabled || chain == null || device == null || minecraft == null || !chainWanted) {
			return;
		}

		// Where the far terrain is taken over, and it is here rather than where the take needs it:
		// this is the one point of the frame reached exactly when DH's far terrain matters to a
		// pack. What it costs is that a takeover lands on the NEXT frame, DH having drawn its LODs
		// long before this line; nothing of the picture turns on that, the frame before it being
		// what this engine draws with the door shut.
		DhLods.install();

		GpuTextureView served = chain.distant.served();
		if (served == null) {
			return;
		}

		// Caught like every other point the game calls this engine back at: an exception here reaches
		// the game through an event handler and comes back on the very next frame.
		try {
			chain.targets.depth().takeDistantOpaque(device.createCommandEncoder(), device,
					chain.quad(device), served, served.getWidth(0), served.getHeight(0));
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail stopped drawing this pack after an error", e);
			chain.release();
		}
	}

	/**
	 * Whether this pack may read the shadow map as it stood before the translucents, which is the
	 * only thing the copy of it is for.
	 * <p>
	 * Answered off the pack's TEXT and not off a sampler plan, because the copy is taken during the
	 * shadow stage and the programs that read it bind later in the same frame: at the moment the
	 * decision has to be made, six of the seven geometry families may not have been read at all. A
	 * name no source of the pack writes cannot be declared by any of them, which is the one half of
	 * the question that can be settled that early, and it is the safe half: a pack that spells the
	 * name anywhere keeps the copy it would have had.
	 */
	boolean mayReadShadowWithoutTranslucents() {
		return this.chain.mentions().maybe("shadowtex1")
				|| this.chain.mentions().maybe("watershadow");
	}

	/** The same question for the depth taken before the hand, which a pack reads as depthtex2. */
	boolean mayReadPreHandDepth() {
		return this.chain.mentions().maybe("depthtex2");
	}

	/**
	 * Keeps the world's depth as it stands before the player's own hand is drawn, which is what the
	 * pack reads as {@code depthtex2}. Called from the event the hand's solid half is drawn at and
	 * one line ahead of it.
	 * <p>
	 * The moment is Iris's and so is the reason. Its {@code beginHand} copies the depth and draws no
	 * hand ({@code pipeline/IrisRenderingPipeline.java:1050-1057}); the solid hand is drawn on the
	 * line after the call to it, by the mixin ({@code mixin/MixinLevelRenderer.java:279-280}); and the
	 * {@code beginTranslucents} that copies {@code depthtex1} comes one step behind both. So
	 * {@code depthtex2} is the only depth of the pair the hand is missing from. A pack reads it to
	 * see what the hand it is holding stands in front of, and served the image with the hand in it
	 * that read finds the hand.
	 * <p>
	 * <strong>Only on the frames this engine really draws a hand</strong>, which is
	 * {@link HandDraw#draws} and not {@link HandDraw#diverted}: the hand's solid pass is the one
	 * thing between this moment and the opaque image {@link #drawEarly} takes, so on every frame that
	 * pass draws nothing - the hand left to the game, and everything {@link HandDraw#draws} asks
	 * beyond that, which is the only list of those that cannot go stale - the two are the same image
	 * to the bit and {@code depthtex2} is answered from the pair. The frame's test and not the
	 * load's, because the load's would pay a full screen image and a conversion for that identical
	 * copy on every frame the player spent looking at himself, and would take a panorama capture
	 * through the 4096 square allocation {@link ColorTargets} says a machine can fail at. Iris pays
	 * it unconditionally instead, its copy moving depth to depth where this one is a draw
	 * ({@code gl/texture/DepthCopyStrategy.java:15-31} takes {@code glCopyImageSubData} wherever the
	 * entry point is there and a framebuffer path where it is not, and the first copy after a resize
	 * goes straight through {@code copyTexImage2D}, {@code targets/RenderTargets.java:235-239}).
	 * {@link PackDepth} carries what that saves, and {@code GeometryProgram.depth} what it leaves
	 * owing.
	 * <p>
	 * Deliberately not on the road {@link #drawBeforeTranslucents} takes: nothing here warms a
	 * pipeline, prepares a target or clears anything, all of which belong to the moment the chain
	 * runs.
	 */
	public static void markPreHandDepth() {
		PackChain chain = active;
		GpuDevice device = RenderSystem.tryGetDevice();
		Minecraft minecraft = Minecraft.getInstance();
		if (disabled || chain == null || device == null || minecraft == null || !chainWanted
				|| !HandDraw.draws() || !chain.mayReadPreHandDepth()) {
			return;
		}

		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		if (main == null) {
			return;
		}

		// Caught like every other point the game calls this engine back at: an exception here reaches
		// the game through an event handler and comes back on the very next frame.
		try {
			chain.targets.depth().takePreHand(device.createCommandEncoder(), device, chain.quad(device),
					main.getDepthTextureView(), main.width, main.height);
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail stopped drawing this pack after an error", e);
			chain.release();
		}
	}

	/**
	 * Keeps the depth of the whole scene while it still is the whole scene. Called at the head of the
	 * game's always-on-top pass, which is the last moment before that pass clears the world's depth
	 * so that its own gizmos draw over everything.
	 * <p>
	 * <strong>It is also the first moment at which the depth is whole</strong>, which is what rules
	 * out the obvious alternative. The pass is added after the clouds, the weather and the
	 * transparency chain, so a take at {@code AfterTranslucentParticles} would come before the clouds
	 * had written theirs, and {@code depthtex0} would stop seeing them on every setup without a
	 * transparency chain, which is every setup this engine runs on.
	 * <p>
	 * Deliberately not on the road {@link #drawBeforeTranslucents} takes, for the same reason
	 * {@link #markPreHandDepth} is not: nothing here warms a pipeline, prepares a target or clears
	 * anything, all of which belong to the moment the chain runs. This copies one image and answers
	 * for nothing else.
	 * <p>
	 * That is also why it runs on the frames the chain cannot draw on at all: a frame that still has
	 * a pipeline to warm allocates the pair of depth images here and converts one of them before a
	 * single pass of the pack has run. Named rather than guarded, because the guard would be a
	 * reading of the chain's warmth this callback is written not to need, because the pair is
	 * allocated once and {@link PackDepth} says its cost out loud, and because what is left after
	 * that is one full screen conversion on the frames the game had a gizmo to draw.
	 */
	public static void markSceneDepth() {
		PackChain chain = active;
		GpuDevice device = RenderSystem.tryGetDevice();
		Minecraft minecraft = Minecraft.getInstance();
		if (disabled || chain == null || device == null || minecraft == null || !chainWanted) {
			return;
		}

		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		if (main == null) {
			return;
		}

		// Caught like every other point the game calls this engine back at: an exception here reaches
		// the game inside its own frame graph and comes back on the very next frame.
		try {
			if (chain.keepScene(device, main.getDepthTextureView(), main.width, main.height)
					&& !chain.saidBeforeClear) {
				chain.saidBeforeClear = true;
				Vitrail.logger().info("The scene's depth is kept before the game clears it for its "
						+ "always-on-top features, so what this pack reads as depthtex0 is the world "
						+ "rather than the far plane");
			}
		} catch (RuntimeException e) {
			disabled = true;
			Vitrail.logger().error("Vitrail stopped drawing this pack after an error", e);
			chain.release();
		}
	}

	/**
	 * Keeps the depth of the whole scene, at most once a frame, whichever of its two moments asks
	 * first.
	 *
	 * @return whether this call is the one that kept it, so that only the caller that really took it
	 *         says so
	 */
	private boolean keepScene(GpuDevice device, GpuTextureView depth, int width, int height) {
		if (this.sceneDepth) {
			return false;
		}

		this.sceneDepth = this.targets.depth().takeScene(device.createCommandEncoder(), device,
				quad(device), depth, width, height);

		return this.sceneDepth;
	}

	/**
	 * Redirects the game's translucent features, the player's body among them, into the layer that
	 * will hand them to the pack's image. Called once the early half has run, and undone by
	 * {@link #closeFeatures()}: the pair brackets exactly the game's {@code executeTranslucent}.
	 * <p>
	 * The switch is the game's own, the one it throws around its always-on-top features. The colour
	 * goes to the layer; the depth keeps pointing at the world's, so the redirected draws still
	 * hide behind terrain and still leave the depth the water reads untouched.
	 * <p>
	 * Nothing is redirected while the chain is still compiling, and that refusal is the same one the
	 * terrain makes through {@code TerrainDraw.shown()}. What the layer is composed onto is a colour
	 * target of the pack, and only the final brings that back: caught during the warm up, the
	 * player's own body and every translucent feature of the game would be dropped for as many
	 * frames as it lasts, on a screen that otherwise looks entirely right.
	 */
	public static void openFeatures() {
		PackChain chain = active;
		GpuDevice device = RenderSystem.tryGetDevice();
		Minecraft minecraft = Minecraft.getInstance();
		if (disabled || chain == null || device == null || minecraft == null || !chainWanted
				|| chain.features == null) {
			return;
		}

		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		// The layer's own pipeline is compiled on the refused frames too, and deliberately before the
		// question below rather than after it: it is one more pipeline the frame that finally draws
		// would otherwise compile on top of the pack's last one.
		if (main == null || !chain.features.prepare(device) || !chain.drawable()) {
			return;
		}

		// Caught like every other entry point this bus calls. These two were the only ones without
		// it, and they are the worst place to be missing one: an exception here reaches the game
		// through an event handler and comes back on the very next frame, so what the player sees
		// is not a pack that stopped drawing but a game that will not run.
		try {
			GpuTextureView layer = chain.features.open(device, main.width, main.height);
			if (layer == null) {
				return;
			}

			RenderSystem.outputColorTextureOverride = layer;
			RenderSystem.outputDepthTextureOverride = main.getDepthTextureView();
			chain.redirected = true;
		} catch (RuntimeException e) {
			// The overrides are cleared on the way out rather than left half set: one standing past
			// this point swallows every later feature draw of the frame.
			RenderSystem.outputColorTextureOverride = null;
			RenderSystem.outputDepthTextureOverride = null;
			chain.redirected = false;
			disabled = true;
			Vitrail.logger().error("Vitrail stopped drawing this pack after an error", e);
			chain.release();
		}
	}

	/**
	 * Puts the game's overrides back and composes the layer onto the half of the pack's target the
	 * world's translucents are about to blend onto, which keeps vanilla's order: features first,
	 * then water.
	 * <p>
	 * <strong>That order was doubted and it is measured, so it is written down here rather than
	 * doubted again.</strong> {@code LevelRenderer.addMainPass} runs, in this order and in one lambda:
	 * the opaque chunk group, {@code AfterOpaqueBlocks}, {@code executeSolid}, {@code
	 * AfterOpaqueFeatures}, {@code executeTranslucent}, {@code AfterTranslucentFeatures}, {@code
	 * executeOutline}, the translucent chunk group, {@code AfterTranslucentBlocks}. The features
	 * really are drawn before the water, and the event this runs on really does fire before the one
	 * named after the blocks, whichever way round the two names read.
	 * <p>
	 * Composing after the water instead would also change nothing about a body that vanishes over
	 * water, which is what the doubt was about. The redirected draws keep the world's depth and write
	 * it: the game's entity pipelines carry {@code DepthStencilState.DEFAULT}, greater or equal with
	 * the write on, and so does the pack's translucent chunk pass. Water standing behind a body
	 * therefore fails the test before it can blend, whichever of the two was composed first. What
	 * washes the body out is on the pack's side and is milestone nine's, see {@link FeatureLayer}.
	 */
	public static void closeFeatures() {
		// Always put back, whatever else happens below: overrides left standing past this point
		// would swallow every later feature draw of the frame.
		RenderSystem.outputColorTextureOverride = null;
		RenderSystem.outputDepthTextureOverride = null;

		PackChain chain = active;
		if (chain == null || !chain.redirected) {
			return;
		}

		// Taken down before anything can throw, so the pair is balanced by the frame that opened it
		// and never by the next one. Whether the chain may draw cannot have moved between the two
		// halves of this bracket, the warm up turning on AfterOpaqueFeatures which is the event
		// openFeatures itself is called from, but the flag makes that a fact rather than a timing.
		chain.redirected = false;

		GpuDevice device = RenderSystem.tryGetDevice();
		if (disabled || device == null || chain.features == null) {
			return;
		}

		try {
			ChainPlan.Attachment into = chain.features.into();
			GpuTextureView view = chain.targets.view(into.target(), into.side());
			// This pass may be the frame's first to attach that target, so it takes the emptying
			// still owed, the way every attaching pass does: composed onto a debt left standing,
			// the layer would sit on stale texels and be erased with them at the deferred flush.
			chain.features.compose(device.createCommandEncoder(), chain.quad, view,
					chain.targets.takeClear(view));
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
		// the OptiFine model calls depthtex1. Iris takes it at the same moment, beginTranslucents,
		// before running its deferreds; the deferreds read it too, and nothing between here and the
		// world's translucents writes the game's depth, so one image serves the whole rest of the
		// frame. Outside any render pass, exactly as the copy this replaces had to be.
		this.targets.depth().takeOpaque(device.createCommandEncoder(), device, this.quad,
				ready.depthView(), ready.main().width, ready.main().height);

		// Here and not after the deferreds, because here is Iris's beginHand: it is called at the
		// head of iris$beginTranslucents, MixinLevelRenderer.java:277-279, which is before the solid
		// hand, before the world's translucents and before beginTranslucents runs the deferred
		// stage. So the depth it folds is the opaque world's, the image this line has just taken,
		// and the deferreds below read the value of THIS frame rather than of the one before.
		// Outside any render pass, since it opens one.
		//
		// The begins and the prepares are in the range below too, so they read this frame's texel
		// where under Iris they would read the previous frame's: it runs both before beginHand,
		// IrisRenderingPipeline.java:1022 and :1033, the prepares from inside renderShadows and so
		// before the opaque world exists at all. That difference is where this chain runs its begins
		// and prepares and not what this line decides, and no pack of the corpus declares the name
		// in either family.
		sampleCenterDepth(device);

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

		drawRange(device, ready, 0, end, this.targets.depth().opaque(),
				this.targets.depth().distantOpaque());
	}

	/**
	 * How many passes of the chain belong before the world's translucents, which the plan answers
	 * off the ranks and this only clamps.
	 * <p>
	 * The clamp is the one thing the plan cannot answer: a program it counted may have failed to
	 * build, so this list is the shorter of the two. The range below is walked by index, and a
	 * boundary past its end would ask for a pass that was never made.
	 */
	private int deferredEnd() {
		return Math.min(this.chain.chain().deferredEnd(), this.programs.size());
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

		// The depth of the whole scene, which by now carries the world's translucents and the
		// features that were redirected into the pack's image. Kept apart from the opaque world's
		// rather than once for both, because the two halves are not asking the same question: a
		// composite that read the opaque world would blur and fog straight through water, and nothing
		// about that fails.
		//
		// Reached when nothing kept it earlier in the frame, which is normally a frame with no
		// always-on-top pass to keep it at; see sceneDepth. Normally and not always, which is why the
		// line says what was seen and not why: a take refused at that pass, or a reload that put a
		// new chain under the frame the old one took it in, both land here on a depth already
		// cleared, and a cause said once a load would be wrong for the whole session.
		if (keepScene(device, ready.depthView(), ready.main().width, ready.main().height)
				&& !this.saidAfterTheWorld) {
			this.saidAfterTheWorld = true;
			Vitrail.logger().info("The scene's depth is kept after the world, nothing having kept it "
					+ "earlier in this frame");
		}

		// The far terrain's depth with its water in, which the composites read as dhDepthTex0. On a
		// frame the pack served only the opaque half this is that half over again, which is also
		// what Iris's live image holds then.
		GpuTextureView distantServed = this.distant.served();
		if (distantServed != null) {
			this.targets.depth().takeDistantScene(device.createCommandEncoder(), device, this.quad,
					this.distant.blendedServed(), this.distant.worldServed(), distantServed,
					distantServed.getWidth(0), distantServed.getHeight(0));
		}

		drawRange(device, ready, deferredEnd(), this.programs.size(), this.targets.depth().scene(),
				this.targets.depth().distantScene());

		// Outside any pass, and after the last one. Only the targets the pack keeps between frames
		// and that the chain left on the far half swap names: the next frame walks from an empty
		// flipped set and would otherwise be handed what was written two frames ago.
		this.targets.swapBack(this.chain.chain().swapBack());
	}

	/**
	 * Folds this frame's centre depth into the texel the pack reads it out of, on the packs that read
	 * it at all.
	 * <p>
	 * The opaque world's depth, and Iris reads the same image: what it hands
	 * {@code CenterDepthSampler} is the live depth attachment, sampled at a moment when nothing
	 * translucent and no hand has been drawn into it yet. The scene's depth would put the surface of
	 * water and glass under the focus point, so a pack looking through either would focus on the
	 * pane rather than on what is behind it.
	 * <p>
	 * Skipped where nothing reads the name, which is Iris's own rule rather than a saving of ours:
	 * {@code CenterDepthSampler.sampleCenterDepth} returns without drawing once it has seen that no
	 * program asked for the sampler. Most of the corpus meets that road at its own defaults, the
	 * name being written behind a depth of field the pack ships switched off;
	 * {@link PackPass#readsCenterDepth} says what this side keys on and where Iris keys on less.
	 */
	private void sampleCenterDepth(GpuDevice device) {
		if (!this.centerDepthRead) {
			return;
		}

		WorldState world = this.values.world();
		this.targets.centerDepth().sample(device.createCommandEncoder(), device, this.quad,
				this.targets.depth().opaque(), world.centerDepthHalfLife(), world.frameTime());
	}

	/**
	 * Paints the seed and remembers that this frame has had it, which is the whole of what
	 * {@link #seeded} is for: the rank the plan gives it is reached by both halves whenever it falls
	 * on their boundary, and the world belongs to the earlier one.
	 */
	private void paintSeed(CommandEncoder encoder, Ready ready) {
		this.seeded = true;
		drawSeed(encoder, ready.mainView(), ready.depthView());
	}

	/**
	 * Paints the game's finished frame where the world would have gone. After the clears and never
	 * before, or the clear would throw the scene away, and on the half the geometry program it
	 * stands in for would have written.
	 */
	private void drawSeed(CommandEncoder encoder, GpuTextureView mainView, GpuTextureView depthView) {
		// One pixel of the sentinel where no mask has been allocated yet, which reads as a screen the
		// pack wrote nowhere on and hides nothing. The mask itself is emptied at the head of every
		// frame, so a frame no program of the pack drew in is served an empty one rather than the
		// last one that was written.
		GpuTextureView covered = this.targets.coverage();
		// Whatever is still owed an emptying is owed it HERE at the latest, the mask above first
		// among them: it rides the load-op of the first geometry pass that attaches it, which is
		// free, but a frame in which the pack's geometry opens no pass at all never reaches that
		// load-op. The mask would then still hold the LAST frame's depths, compared against this
		// frame's with the camera moved in between, and the seed would repaint the pack's geometry
		// over most of the screen.
		this.targets.flushPending(encoder);

		// The far terrain's own depth rides the same fallback: a frame it drew nothing in reads as
		// nothing of the pack's standing there, through the sentinel sitting outside the range on
		// the clear's side.
		GpuTextureView distantServed = this.distant.served();
		this.seed.draw(encoder, this.quad, mainView,
				covered == null ? this.targets.unwritten() : covered, depthView,
				distantServed == null ? this.targets.unwritten() : distantServed, this.targets);
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
		this.centerDepthRead = built.stream().anyMatch(PackPass::readsCenterDepth);
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
	 * The first composite is asked for every frame whatever happens, and its compiled form is
	 * compared with the one from the frame before. That is the only way to notice that the device
	 * emptied its cache, which it does at every resource reload: the alternative is to ask for all
	 * of them every frame, which pays the whole compilation in the one frame after a reload.
	 * <p>
	 * After the composites, terrain pipelines compile a call. The other families translate AND
	 * compile on a worker: Complementary Unbound's leftover pipelines are the minute between
	 * packs, and holding the world for them is that minute. A first draw the worker has not
	 * reached yet still pays shaderc on the render thread, which is the fallback and no longer
	 * the rule. {@link #pumpWarmup} repeats the compiles here for as long as a short budget on
	 * the frame allows. The device cache itself is only ever written on the render thread: the
	 * worker builds the objects, {@code GeometryProgram.compile} hands them over.
	 *
	 * @return false while a program is still missing, in which case nothing of the chain is drawn.
	 *         What the screen holds for those frames is the terrain's answer and not this one, and
	 *         {@link #drawable} is where the two are joined
	 */
	@SuppressWarnings("ReferenceEquality")
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
			forgetGeometry();

			return false;
		}

		if (this.warmed < this.programs.size()) {
			PackPass pass = this.programs.get(this.warmed);
			if (!valid(pass.compile(device), pass)) {
				return false;
			}

			this.warmed++;

			return false;
		}

		startFamilyPrefetch();

		// Terrain is the world frame. Complementary Unbound's leftover families are the minute
		// between packs; the worker compiles them while the world is already being played, and a
		// first draw that outruns it falls back here. Terrain is a handful of pipelines, and the
		// first world frame hitches without them.
		//
		// THE LEFTOVERS ARE NOT COMPILED ON THIS THREAD, and that is a decision rather than an
		// omission. Doing it a program a frame, here, cost two minutes at two frames a second on
		// entering a world with Complementary Unbound: one shaderc compile is about half a second
		// and a frame is worth sixteen milliseconds, so there is no per-frame budget it fits in
		// and spreading it only spaces the stalls out. What removed the stalls rather than moving
		// them is the worker: precompilePipeline is not safe off the render thread, its caches
		// and its compiler being shared and unguarded, but the create calls under it are once
		// those are bypassed, and startFamilyPrefetch says how the two halves are split.
		if (compileNext(device, this.terrain.programs())) {
			return false;
		}

		if (!this.geometryReady) {
			Vitrail.logger().info("The pack's composites and terrain are compiled, the chain can draw");
		}

		this.geometryReady = true;

		return true;
	}

	private static final int FAMILIES = 6;

	/** Long enough for several compiles, short enough that the HUD still ticks. */
	private static final long WARM_BUDGET_NANOS = 80_000_000L;

	/**
	 * Translates the six families on a worker, and compiles each family's pipelines on a task of
	 * its own the moment that family's translation lands, so the entities compile while the
	 * clouds are still being read. Complementary Unbound's leftovers still cost their minute of
	 * work on a cold driver cache, spread across the pool's workers instead of queued on one;
	 * what they never cost again is the render thread, which only adopts what the workers
	 * finish, and the failed shape that DID cost it is engraved above compileNext.
	 * <p>
	 * The translations stay SEQUENTIAL on the one worker, deliberately: two readers on one zip
	 * race, which is the reason the terrain's own read finishes before this starts. Only the
	 * compiles fan out, one task and one compiler per family, on {@link #COMPILE_POOL} rather
	 * than on the game's shared pool. The sky's and the entities' tasks are spawned first:
	 * those two are on screen the moment the world is.
	 */
	private void startFamilyPrefetch() {
		synchronized (this) {
			if (this.familyPrefetchStarted) {
				return;
			}

			this.familyPrefetchStarted = true;
		}

		// Read here, before any worker exists, and carried in as a value: the arming file read
		// lazily from a worker would race the reset a pack swap does on the render thread.
		boolean keepOld = PassTimings.keepFirstDrawCompiles();
		AtomicBoolean fanned = new AtomicBoolean();
		AtomicLong start = new AtomicLong();
		CompletableFuture<Void> whole;
		try {
			whole = CompletableFuture.supplyAsync(() -> {
				start.set(System.nanoTime());
				Vitrail.logger().info("The pack-load worker starts on the six families");
				VulkanDevice device = compileDevice(keepOld);
				fanned.set(device != null);

				List<CompletableFuture<Void>> compiles = new ArrayList<>(FAMILIES);
				try {
					prefetchFamily(this.sky::prefetch);
					spawnFamilyCompiles(compiles, 0, device);
					prefetchFamily(this.entities::prefetch);
					spawnFamilyCompiles(compiles, 1, device);
					prefetchFamily(this.clouds::prefetch);
					spawnFamilyCompiles(compiles, 2, device);
					prefetchFamily(this.weather::prefetch);
					spawnFamilyCompiles(compiles, 3, device);
					prefetchFamily(this.particles::prefetch);
					spawnFamilyCompiles(compiles, 4, device);
					prefetchFamily(this.distant::prefetch);
					spawnFamilyCompiles(compiles, 5, device);
				} catch (Throwable e) {
					// prefetchFamily catches the RuntimeException of one translation; anything
					// harder would otherwise take this stage down EXCEPTIONALLY, and the whole
					// would then complete while the tasks already spawned still run, out of the
					// reach of the shutdown wait. Caught here, the spawned tasks stay tracked
					// and the families never reached keep their first-draw path.
					Vitrail.logger().error("The pack-load worker died", e);
				}

				return compiles;
			}, Util.backgroundExecutor()).thenCompose(compiles ->
					CompletableFuture.allOf(compiles.toArray(new CompletableFuture<?>[0])));
		} catch (RejectedExecutionException e) {
			// The executor only refuses while the client shuts down. The families keep their
			// first-draw path, and the flag closes the mark rather than leaving one that can
			// never go out.
			this.warmedAt = Util.getMillis();
			this.familiesWarmed = true;

			return;
		}

		CompletableFuture<Void> tracked = whole.handle((unused, e) -> {
			if (e != null) {
				// handle() and not a catch: the futures swallow what their runnables throw, so
				// anything that dies unlogged reads as the workers having finished. The families
				// they did not reach fall back to the first-draw path either way, and one
				// family's failure no longer stops the five others.
				Vitrail.logger().error("The pack-load worker died", e);
			}

			this.warmedAt = Util.getMillis();
			this.familiesWarmed = true;
			if (fanned.get()) {
				Vitrail.logger().info("{} of {} leftover pipelines compiled ahead of their "
						+ "first draw, {} ms of background work, translations included",
						this.warmServed.get(), this.warmWalked.get(),
						(System.nanoTime() - start.get()) / 1_000_000L);
				// Beside the total it explains. The spans are summed per program across workers,
				// so together they can pass the wall clock of the load; they compare with each
				// other, not with it.
				Vitrail.logger().info("With the families in, translating cost {} ms over {} "
						+ "translator calls and making modules cost {} ms over {} modules, "
						+ "shaderc and SPIRV-Cross together", LoadClock.translationMillis(),
						LoadClock.translated(), LoadClock.moduleMillis(), LoadClock.modules());
			}

			return (Void) null;
		});
		WARMUPS.add(tracked);
		tracked.whenComplete((unused, e) -> WARMUPS.remove(tracked));
	}

	/**
	 * The threads the family compile tasks run on, this engine's own and never the game's
	 * shared pool: a compile parks its thread in native shaderc for half a second at a time,
	 * six families land at once at world join, and threads parked like that would starve every
	 * other user of the shared executor for the length of the warm-up. Three at most, daemons,
	 * and the pool empties itself once the warm-up is over. Being daemons, they offer shutdown
	 * no barrier of their own: {@link #awaitFamilyWarmups} is the one thing standing between a
	 * compile in flight and the device teardown.
	 */
	private static final ThreadPoolExecutor COMPILE_POOL = compilePool();

	private static ThreadPoolExecutor compilePool() {
		AtomicInteger names = new AtomicInteger();
		ThreadPoolExecutor pool = new ThreadPoolExecutor(3, 3, 30L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), runnable -> {
					Thread thread = new Thread(runnable,
							"Vitrail compile worker " + names.incrementAndGet());
					thread.setDaemon(true);
					// The game outranks the warm-up by design: a window measured during play ran
					// six times longer than the same one behind a loading screen, which is these
					// threads yielding, and the priority writes that bargain down.
					thread.setPriority(Thread.MIN_PRIORITY);

					return thread;
				});
		pool.allowCoreThreadTimeOut(true);

		return pool;
	}

	/**
	 * The Vulkan backend the compile tasks build against, or null with the reason logged: no
	 * task is spawned then, and every family keeps its first-draw path. Resolved on the worker
	 * rather than at the call, because the load's own road can run before rendering is up.
	 */
	private static VulkanDevice compileDevice(boolean keepOld) {
		GpuDevice front = RenderSystem.tryGetDevice();
		if (front != null && !keepOld
				&& ((GpuDeviceAccessor) front).vitrail$backend() instanceof VulkanDevice device) {
			return device;
		}

		Vitrail.logger().info("The workers leave the leftover families to their first draw: {}",
				front == null ? "no device"
						: keepOld ? "keep-first-draw-compiles"
								: "the backend is not the Vulkan one");

		return null;
	}

	/**
	 * One compile task for one family, started the moment its translation landed. The pool it
	 * lands on is never shut down and its queue is unbounded, so the submit cannot be refused;
	 * the game's own executor, which can refuse one at shutdown, only ever carries the
	 * translation stage.
	 */
	private void spawnFamilyCompiles(List<CompletableFuture<Void>> compiles, int family,
			VulkanDevice device) {
		if (device == null) {
			return;
		}

		// Copied here, on the thread that has just filled this family, and never walked live:
		// the maps behind it are emptied on the render thread when the pack goes, and an
		// iterator standing in one then throws under the worker even though the released flag
		// it reads every step is already up. Under the lock, because the copy itself is a read
		// of the live map and the emptying is what it races.
		List<DumpedProgram> programs;
		synchronized (this.familyMaps) {
			programs = List.copyOf(familyPrograms(family));
		}

		// The plate grows before the task that will empty it exists, so the corner's count can
		// only ever run behind the truth, never past it.
		this.warmTotal.addAndGet(programs.size());
		compiles.add(CompletableFuture.runAsync(
				() -> warmFamily(programs, device), COMPILE_POOL));
	}

	/**
	 * Compiles every program one family read, with a compiler of this task's own: the device's
	 * precompile keeps its results in maps only the render thread may touch, so each pipeline is
	 * built through the same public steps instead and {@code GeometryProgram.compile} hands the
	 * finished object to the cache on the render thread. {@code GeometryProgram.warmAhead} says
	 * why every step of that is safe off the thread, and
	 * {@code vitrail/keep-first-draw-compiles} beside the pack keeps the old first-draw path for
	 * a measurement, the way {@code keep-redone-work} does.
	 */
	private void warmFamily(List<DumpedProgram> programs, VulkanDevice device) {
		try (GlslCompiler compiler = new GlslCompiler()) {
			for (DumpedProgram program : programs) {
				if (this.released || disabled) {
					return;
				}

				this.warmWalked.incrementAndGet();
				if (program.warmAhead(device, compiler)) {
					this.warmServed.incrementAndGet();
				}
			}
		}
	}

	/**
	 * One family read ahead, and the translation half of what {@link #released} stops.
	 * <p>
	 * {@link #warmFamily} read that flag between two programs and this did not, so a chain nothing
	 * would ever draw again went on translating its remaining families. That is wasted work, and it
	 * is also the one thing that writes to state no chain owns: every {@code PackProgram.load}
	 * reinstalls the {@code bufferObject} lines of the pack it is reading and the translator files
	 * its storage block names away as it goes, so a worker outliving its pack can put the outgoing
	 * pack's answers back after the next load has emptied them.
	 * <p>
	 * Read once per family rather than per program, which is what the counter below can express: it
	 * is a prefix bound, and skipping the rest without raising it leaves exactly the families that
	 * were really filled walkable. So a translation already under way still finishes and can still
	 * write, and that one family is the window this narrows the race to rather than closes it.
	 */
	private void prefetchFamily(Runnable prefetch) {
		if (this.released || disabled) {
			return;
		}

		try {
			prefetch.run();
		} catch (RuntimeException e) {
			Vitrail.logger().error("Translating a pack family failed", e);
		}

		this.familiesReady++;
	}

	/**
	 * Waits out, bounded, every worker still running at shutdown, whichever chain started it.
	 * Nothing device-side can START once a chain is released; what the bound really covers is the
	 * one compile possibly in flight, about half a second, and a worker still translating holds
	 * no device work at all. The bound itself is for a worker wedged in the driver, which may not
	 * be allowed to hold the quit.
	 */
	private static void awaitFamilyWarmups() {
		CompletableFuture<?>[] running = WARMUPS.toArray(new CompletableFuture<?>[0]);
		if (running.length == 0) {
			return;
		}

		try {
			CompletableFuture.allOf(running).get(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException ignored) {
			// The runnable logs its own failures, and a timeout leaves nothing to do but quit.
		}
	}

	private Collection<? extends DumpedProgram> familyPrograms(int index) {
		return switch (index) {
			case 0 -> this.sky.programs();
			case 1 -> this.entities.programs();
			case 2 -> this.clouds.programs();
			case 3 -> this.weather.programs();
			case 4 -> this.particles.programs();
			case 5 -> this.distant.programs();
			default -> List.of();
		};
	}

	private static boolean compileNext(GpuDevice device,
			Collection<? extends DumpedProgram> programs) {
		for (DumpedProgram program : programs) {
			if (!program.compiled()) {
				program.compile(device);

				return true;
			}
		}

		return false;
	}

	private void forgetGeometry() {
		this.geometryReady = false;
		forgetCompiled(this.terrain.programs());
		int ready = this.familiesReady;
		for (int i = 0; i < ready && i < FAMILIES; i++) {
			forgetCompiled(familyPrograms(i));
		}
	}

	private static void forgetCompiled(Collection<? extends DumpedProgram> programs) {
		programs.forEach(DumpedProgram::forgetCompiled);
	}

	/**
	 * Whether the chain is in a state to bring a colour target of the pack back to the screen, asked
	 * of the frame that is being drawn and not of the load.
	 * <p>
	 * The terrain sends draw buffer nought to the pack's own targets, and only the final of this
	 * chain brings that back. Whether it may was settled once at load, out of the engine option and
	 * the plan; whether it will is this, and the two are not the same question. {@link #warm}
	 * compiles one program a frame, so every load, every resource reload and every portal used to
	 * spend {@code programs.size()} frames with the world drawn into a target nothing read: three
	 * seconds of a screen with no world in it, at every F3+T. Terrain pipelines take that same
	 * road after the composites. Leftover families compile on the worker.
	 * <p>
	 * The empty chain answers no rather than yes on a vacuous count. A place with no program has no
	 * final either, so nothing would ever bring that target back, and {@link #warm} refuses it in
	 * its first line for the same reason.
	 */
	boolean drawable() {
		return this.programs != null && !this.programs.isEmpty()
				&& this.warmed == this.programs.size()
				&& this.geometryReady;
	}

	/**
	 * Releases as well as latching, which the two other places that set {@code disabled} already
	 * did and this one did not. A pack whose one bad program stops the chain kept every colour
	 * target it had allocated for the rest of the session, ninety nine megabytes of them on BSL,
	 * for an engine that had just decided to draw nothing.
	 */
	private boolean valid(CompiledRenderPipeline compiled, PackPass pass) {
		if (compiled.isValid()) {
			return true;
		}

		disabled = true;
		Vitrail.logger().error("{} did not compile, nothing of this pack will be drawn", pass.path());
		release();

		return false;
	}

	/**
	 * The quad every full screen pass of this engine draws, made the first time anything asks for it.
	 * <p>
	 * Its own method rather than a line of {@link #prepare}, because what needs it is reached from
	 * entry points that never go through prepare at all: {@link #markPreHandDepth} and
	 * {@link #markSceneDepth} answer events of their own, and a frame reaches either of them whether
	 * or not the chain drew. {@code DistantDraw} is the third, asking from inside the far terrain's
	 * own draw. Each asks here and gets the one buffer.
	 */
	GpuBuffer quad(GpuDevice device) {
		if (this.quad == null) {
			ByteBuffer vertices = ByteBuffer.allocateDirect(QUAD.length * Float.BYTES)
					.order(ByteOrder.nativeOrder());
			vertices.asFloatBuffer().put(QUAD);
			this.quad = device.createBuffer(() -> QUAD_LABEL,
					GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertices);
		}

		return this.quad;
	}

	/** @return false when the targets could not be prepared, in which case nothing may be drawn */
	private boolean prepare(GpuDevice device, RenderTarget main) {
		quad(device);

		if (this.block == null) {
			// Three buffers and a fence per turn, so a frame never writes over what the previous
			// one is still being read for.
			this.block = new MappableRingBuffer(() -> BLOCK_LABEL,
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

		// Said rather than inherited. Every pass of the chain draws into a target the game rasterised
		// under a reversed Z, and the shadow programs, which draw into one of ours and flip this to
		// the forward window, have already run by the time this does.
		this.values.convention(ClipSpace.REVERSED);

		// The same, and for a sharper reason: renderStage is in the table a full screen pass shares
		// with a geometry one, and the sky, the terrain and the entities have all written theirs by the time
		// this runs. Left standing, every composite of the frame would be told it was drawing the moon.
		this.values.renderStage(RenderStage.NONE);

		// And the same again for the three values a pass writes beside it. Two geometry families
		// set a matrix of their own: the sky pushes the rotation of the day, and the hand is drawn
		// under an identity model view and sets a whole projection besides, the head-up volume with
		// its clip depth squeezed to an eighth. Whichever drew last would
		// otherwise stand in for the camera in every composite of the frame - the hand's case is
		// the worst of the three, the squeezed volume reprojecting the whole screen - and in the
		// decoded dump beside it. The frame boundary drops them as
		// well and that is not the same guard: it drops them at the head of the frame, and every one
		// of those families writes after it and before this.
		this.values.modelView(null, null);
		this.values.passColour(null);
		this.values.projection(null);

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
			Vitrail.logger().info("{} targets swap their two halves at the end of every frame, because "
					+ "the pack keeps them and the chain left them on the far one: {}",
					back.size(), back);
		}

		// Beside the copy above, which is the other half of the same subject: what this pack carries
		// from one frame to the next. Said at info and not with the warnings below, though they are
		// the same walk's answers, because a pack reading a target it keeps is a mechanism of the
		// pack rather than a hole in this engine, and Iris hands it exactly the same one. Read among
		// the warnings, BSL's colortex2 line was taken for a defect of the temporal antialiasing and
		// stayed a suspect for a fortnight.
		unfolded.history().forEach(note -> Vitrail.logger().info("{}", note));

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
			// What the seed still carries is not a constant, and this line is where docs/ sends a
			// reader for it. Composed from the switches rather than written out, or it goes stale
			// the day the next family lands, in the one place that must not.
			//
			// The switch and NOT what the pack turned out to serve, which is a real limit and not a
			// shortcut: this runs when the chain is built and the entity programs are read at the
			// first entity drawn, so on a pack that serves none, or whose targets the family refuses,
			// this line has already spoken. Those refusals each say so on a line of their own, which
			// is where a reader finds out; making this one wait for them would mean not printing the
			// scope at load at all.
			Vitrail.logger().info("{} carries the game's opaque frame, drawn in for {}: {} still come "
					+ "from the game, already tone mapped, and the translucent chunk pass blends onto "
					+ "this seed afterwards",
					TargetName.canonical(where.get().target()), where.get().from(), stillTheGame());
			// The number is worth printing on its own: it is the whole difference between a begin
			// that reads the world of this frame and one that reads what the clear left.
			Vitrail.logger().info("It is painted where the world would be drawn, after {} passes of "
					+ "the chain", where.get().at());
			// The pair to the terrain's own line. A frame where no program of the pack ran leaves
			// the mask on its sentinel and this covers the target whole, which is what it always did.
			Vitrail.logger().info("It paints where the world's depth stands in front of the one the "
					+ "pack's own geometry left, which is what the coverage mask carries: everywhere "
					+ "the pack wrote nothing, and everywhere the game has drawn over what it wrote");
			// Named because it is the difference between a gbuffer that agrees with itself and one
			// that carries the game's colour over the pack's normals, and neither shows as itself.
			List<Integer> emptied = this.seed.emptied();
			if (!emptied.isEmpty()) {
				Vitrail.logger().info("Where it paints over that terrain, which is where the game drew "
						+ "in front of it, the rest of that program's draw buffers are emptied: {}. "
						+ "They would otherwise keep the gbuffer of the block behind, and the deferred "
						+ "stage would light the game's colour with it",
						emptied.stream().map(TargetName::canonical).toList());
			}
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
					byKind.computeIfAbsent(kind, _ -> new LinkedHashSet<>()).addAll(names));
		}

		named(byKind, SamplerPlan.Kind.COLORTEX, "read a real colour target");
		named(byKind, SamplerPlan.Kind.DEPTH, "read the world's depth");
		String map = this.targets.shadow().depth() == null
				? "read white, no shadow map is allocated"
				: "read the shadow map";
		named(byKind, SamplerPlan.Kind.SHADOW_DEPTH, map);
		named(byKind, SamplerPlan.Kind.SHADOW_COLOUR, map);
		named(byKind, SamplerPlan.Kind.CENTER_DEPTH,
				"read the smoothed depth at the centre of the screen, which is what a depth of field "
						+ "focuses on");
		named(byKind, SamplerPlan.Kind.DISTANT_DEPTH,
				"read the far terrain's own depth, kept beside the world's as Iris keeps it, on the "
						+ "frames this pack draws the far terrain, and the far plane on the rest");
		named(byKind, SamplerPlan.Kind.CUSTOM_IMAGE,
				"read a storage image the pack declared with image.NAME");
		named(byKind, SamplerPlan.Kind.UNBINDABLE,
				"are declared under a type this backend cannot bind, and should have gone with "
						+ "their pass");

		Set<String> depths = byKind.getOrDefault(SamplerPlan.Kind.DEPTH, Set.of());
		List<String> copies = depths.stream()
				.filter(name -> SamplerPlan.depthCopy(name) && !SamplerPlan.preHandCopy(name))
				.toList();
		if (!copies.isEmpty()) {
			Vitrail.logger().info("{} read the depth of the world as it stood before its "
					+ "translucents", copies);
		}

		// Named apart, because the two are one image whenever the hand is left to the game and two
		// whenever it is not, and which of the two a pack is reading is the whole of what it asked
		// for by writing depthtex2.
		List<String> pastTheHand = depths.stream().filter(SamplerPlan::preHandCopy).toList();
		if (!pastTheHand.isEmpty()) {
			Vitrail.logger().info("{} read the depth of the world from before the hand was drawn, "
					+ "which is an image of its own only while this engine draws the hand", pastTheHand);
		}

		// Said whichever way it went, because what is worth reading here is the work NOT done and
		// a silence would look the same as a guard that never fired. Off the whole pack's text
		// rather than off this chain's programs: that is the reading the two guards go by, and a
		// line quoting a narrower one would send whoever chases a missing shadow to the wrong
		// place.
		if (!mayReadShadowWithoutTranslucents()) {
			Vitrail.logger().info("No source of this pack names shadowtex1 or watershadow, so the "
					+ "copy of the shadow map taken before its translucents is not taken at all");
		}

		if (!mayReadPreHandDepth()) {
			Vitrail.logger().info("No source of this pack names depthtex2, so the depth of the "
					+ "world from before the hand is neither converted nor kept");
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
		// The worker's flag first, so a compile still running for this chain stores nothing more
		// into programs nothing will ever draw again; what it already stored is destroyed with the
		// walk below, which is safe for objects nothing ever bound. Only families the worker
		// finished translating are walked, for the reason rotate() gives.
		this.released = true;
		int ready = this.familiesReady;
		for (int family = 0; family < ready && family < FAMILIES; family++) {
			familyPrograms(family).forEach(DumpedProgram::discardAhead);
		}

		CustomImages.clear();
		this.compute.close();
		this.targets.release();
		if (this.features != null) {
			this.features.release();
		}

		// DH takes its far terrain back with the pack, its own frame order included: DhLods.handBack
		// says why nothing else would ever return it.
		DhLods.handBack();

		this.terrain.release();
		synchronized (this.familyMaps) {
			this.sky.release();
			this.entities.release();
			this.clouds.release();
			this.weather.release();
			this.particles.release();
			this.distant.release();
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
