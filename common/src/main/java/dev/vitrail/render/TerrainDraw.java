package dev.vitrail.render;

import dev.vitrail.dh.DhLods;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShadowCasters;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.settings.ShadowRefresh;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The door the chunk renderer comes in by, and the one place a pack's terrain program is read.
 * <p>
 * It sits apart from the chain because it runs at a different moment and against different state.
 * The chain draws once the world is finished; this is asked for while the world is being drawn, from
 * where the chunk renderer picks its shader, and that is also where the frame boundary now hangs:
 * whichever of the two comes first opens the frame, or the terrain stage would be handed the
 * previous frame's matrices.
 * <p>
 * The programs are read on demand rather than with the chain, and all three at once the first time
 * any pass asks. That costs one plan build for the three, which is the price of not making every
 * place that never draws terrain pay for programs only this step uses, and the vertex format they
 * are compiled against is only known here.
 */
public final class TerrainDraw {

	/**
	 * What the world's opaque geometry does about the mask the scene seed is cut with. Three answers
	 * and not two, because the question is asked at the head of a frame and the world is drawn in the
	 * middle of one.
	 */
	public enum Mask {

		/**
		 * No chunk pass has asked for a program yet, so nothing has been read and nobody has
		 * answered. The first frame of a world is here, and so is every frame of a place with no
		 * chunk in it at all.
		 */
		UNREAD,

		/** Both opaque halves are the pack's and mark the pixels they wrote. */
		WRITTEN,

		/**
		 * At least one opaque half does not, whether because the pack serves no program for it,
		 * because the plan gave it no target of its own, or because the option is off. Its picture
		 * then reaches the pack's colour target through the scene seed and nowhere else.
		 */
		ABSENT
	}

	/** The two halves of the world that write outright, which are the two the mask is about. */
	private static final List<TerrainPass> OPAQUE = List.of(TerrainPass.SOLID, TerrainPass.CUTOUT);

	/**
	 * Off unless {@code options.txt} asks for it, and read again at every load. It is also what an
	 * error latches: a terrain program that threw once stops being offered rather than throwing again
	 * inside the pass the chunk renderer opened, where it would read as a failure of that renderer.
	 */
	private static volatile boolean wanted;

	/**
	 * Off unless the options ask for it, and worth nothing without {@link #wanted}. It is also what
	 * a shadow program that threw latches, for the reason {@link #wanted} gives: the stage and not
	 * the pack, because a shadow program failing says nothing about the three the camera draws with.
	 */
	private static volatile boolean shadowWanted;

	/**
	 * Bumped on every pack load and every stage failure, so a skip cannot keep a map that was
	 * destroyed or never drawn.
	 */
	private static int shadowEpoch;

	private static int seenShadowEpoch = -1;

	/** Whether a walk has filled the map since {@link #shadowEpoch} last moved. */
	private static boolean shadowMapFilled;

	/** Toggled by {@link ShadowRefresh#EVERY_TWO_FRAMES} so the skip lands on alternate frames. */
	private static boolean skipAlternate;

	private static Vec3 lastShadowCamera;

	private static final Matrix4f LAST_SHADOW_VIEW = new Matrix4f();

	/**
	 * How far the camera has to move, in blocks, before {@link ShadowRefresh#WHEN_CAMERA_MOVES}
	 * records again. Standing still is identical; this is only float noise.
	 */
	private static final float CAMERA_MOVE = 1.0e-4F;

	/**
	 * Whether the renderer is drawing the shadow map rather than the world at this instant.
	 * <p>
	 * A flag and not an argument because the three doors below are the renderer's own calls and it
	 * knows nothing of a shadow: it is asked for the solid pass twice in one frame and only the
	 * caller can say which of the two it is. Set for the length of one draw and cleared in a finally,
	 * because a flag left standing would draw the world into the shadow map.
	 */
	private static boolean shadowing;

	/**
	 * What the chunk mesh has to carry for the pack in force: Sodium's own elements first, then the
	 * ones this engine appends that the pack's chunk programs really read. Empty where no pack's
	 * terrain program is wanted, and then the mesh keeps Sodium's own format.
	 * <p>
	 * <strong>Read by the mesh at the one instant it may change and written only by
	 * {@link #carries}</strong>, which has the world rebuilt when the answer moves. Volatile because
	 * the two sides are two threads: a pack is loaded off the render thread while the game starts up,
	 * and Sodium builds its chunk renderer on the render thread.
	 */
	private static volatile List<String> carried = List.of();

	private final PackChain owner;
	private final Path packPath;
	private final String place;
	private final PackValues values;
	private final int load;
	private final ChainPlan plan;
	private final TargetPlan chainTargets;
	private final boolean chainRuns;
	private final ColorTargets targets;

	/**
	 * The pack's chunk programs as the translation left them, read when the pack was loaded. Empty
	 * where the pack serves none, and where the reading threw.
	 */
	private Map<TerrainPass, PackProgram.Loaded> loaded = Map.of();

	/** The elements those programs declare, which is what this pack published through {@link #carries}. */
	private List<String> declares = List.of();

	private Map<TerrainPass, TerrainProgram> programs = Map.of();

	/** The last pipeline {@link #owner} was asked about, and the answer it gave. */
	private RenderPipeline lastBound;

	private TerrainProgram lastOwner;
	private boolean read;

	TerrainDraw(PackChain owner, Path packPath, String place, PackValues values, int load,
			ChainPlan plan, TargetPlan chainTargets, boolean chainRuns, ColorTargets targets) {
		this.owner = owner;
		this.packPath = packPath;
		this.place = place;
		this.values = values;
		this.load = load;
		this.plan = plan;
		this.chainTargets = chainTargets;
		this.chainRuns = chainRuns;
		this.targets = targets;
	}

	/**
	 * Whether a pack's terrain program takes over the opaque chunk pass, from the loaded options.
	 * <p>
	 * <strong>Switched off, this empties what the chunk mesh carries</strong>, the mesh only carrying
	 * what a pack reads while a pack wants it. So a change here is worth a rebuilt world: the sections
	 * standing at this instant were meshed at the other stride, and the renderer would bind a layout
	 * that does not describe them. What that costs when it is skipped is a world drawn by the game
	 * while the sky is drawn by the pack, which reads as the sky being in front of the world rather
	 * than as a terrain program that never ran. Switched on it empties nothing and settles nothing:
	 * {@link #read(OpenedPack)} is what fills the answer in, once the pack's own programs have been read.
	 * <p>
	 * The door is the one F3+A uses, {@code LevelExtractor.allChanged}, and it raises a flag the next
	 * extract consumes rather than tearing sections down inside a frame. That extract calls
	 * {@code LevelRenderer.invalidateCompiledGeometry}, where Sodium builds its chunk renderer again
	 * from nothing, and that is where the format is taken.
	 * <p>
	 * <strong>Asking is all this does</strong>, and it is what makes the one place that writes the
	 * field directly safe: a terrain program that threw stops being offered at once, without a
	 * rebuilt world, and the mesh goes on carrying what it carried until something else rebuilds it.
	 * The format cannot fall out of step with a living builder that way, because it is not this field
	 * that the format follows but the reading taken at the rebuild.
	 * <p>
	 * Silent before a world is joined, where nothing has been meshed and the first reading answers
	 * itself.
	 */
	static void wanted(boolean asked) {
		if (!asked) {
			// Whether or not the flag itself moved. A pack refused after this was raised leaves the
			// flag standing with no chain behind it, and the elements are the half of the answer the
			// mesh really reads.
			carries(List.of());
		}

		if (wanted == asked) {
			return;
		}

		wanted = asked;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return;
		}

		Vitrail.logger().info("The pack's own terrain program is {} now, and the chunk mesh carries "
				+ "what it reads only while it does, so the sections are all built again",
				asked ? "wanted" : "no longer wanted");
		minecraft.levelExtractor.allChanged();
	}

	/**
	 * Reads the pack's own chunk programs and publishes the mesh they were written against.
	 * <p>
	 * <strong>Here and not at the first chunk draw, and the reason is the ORDER.</strong> What the
	 * mesh carries is the union of what those six programs read, and Sodium takes the format where it
	 * builds its chunk renderer, which is long before any pass asks for a shader. Read late, the
	 * format would be settled from the pack before it and every section would be meshed at a stride
	 * these programs do not declare.
	 * <p>
	 * <strong>This is one half of a contract, and {@code TerrainMesh.settle} is the other.</strong>
	 * That method reads what this publishes and decides the layout out of it, at the head of Sodium's
	 * {@code initRenderer}; it cannot check that this has run, and nothing between the two makes them
	 * agree by construction. What keeps them in order is where each is reached from and not a test:
	 * this runs from {@code PackChain.load}, at client setup before any level exists and afterwards
	 * on the render thread, and Sodium reaches {@code initRenderer} only with a level and only on
	 * that same thread. Reversed with a pack in force, the mesh answers with Sodium's own twenty
	 * bytes for a pack that declares more, and what the player is left with is a pack put away a
	 * world later, over the two lists {@code TerrainProgram.carries} prints rather than over the
	 * order that parted them. A dimension change does reverse them, harmlessly and for one frame,
	 * and {@code TerrainMesh.settle} is where that road is written out.
	 * <p>
	 * Nothing here touches the device, so it runs wherever the load runs, off the render thread while
	 * the client starts up. What it costs a place that never draws a chunk is those six translations;
	 * what the laziness bought was exactly that, and it cannot be had at the same time as a format
	 * that follows the pack.
	 * <p>
	 * A reading that throws takes the terrain down rather than the pack: the sky and the entities are
	 * read from the same archive and have their own answer about it, and a world drawn by the game
	 * under a pack's sky is what this would otherwise leave behind.
	 *
	 * @param pack the opening the load holds, which stays open for exactly as long as this call. The
	 *             six programs are read from it rather than from an opening of this class's own,
	 *             which is why nothing here may keep a {@code Path} taken from the pack
	 */
	void read(OpenedPack pack) {
		if (!wanted) {
			return;
		}

		try {
			PackProgram.Terrain read = TerrainProgram.read(pack, this.place, this.values);
			this.loaded = read.programs();
			this.declares = read.carried();
		} catch (IOException | RuntimeException e) {
			wanted = false;
			Vitrail.logger().error("Could not read the terrain programs of "
					+ this.packPath.getFileName() + ", so the world keeps the game's own shader", e);
		}

		carries(this.declares);
	}

	/**
	 * Takes the elements the mesh has to carry from here on, and has the world rebuilt when they
	 * move.
	 * <p>
	 * The same door and the same reason as {@link #wanted(boolean)}: the sections standing at this
	 * instant were meshed at the other stride and carry their words at the other offsets, so a
	 * renderer binding the new layout over them reads each element out of its neighbour. It is
	 * {@code LevelExtractor.allChanged}, which raises a flag the next extract consumes rather than
	 * tearing sections down inside a frame, and that extract is where Sodium builds its chunk
	 * renderer again and where {@code TerrainMesh.settle} takes this answer.
	 * <p>
	 * <strong>Two packs that both draw the terrain are the case this exists for.</strong> The flag
	 * above does not move between them, and a rebuild that hung on the ids moving would not fire:
	 * two packs reading different names of the mesh with the same {@code block.properties} would
	 * leave the sections at a stride nothing writes.
	 * <p>
	 * Silent before a world is joined, where nothing has been meshed and the first reading answers
	 * itself.
	 */
	private static void carries(List<String> asked) {
		if (carried.equals(asked)) {
			return;
		}

		carried = List.copyOf(asked);
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return;
		}

		Vitrail.logger().info("The chunk mesh carries {} now, so the sections are all built again",
				carried);
		minecraft.levelExtractor.allChanged();
	}

	/**
	 * The elements the chunk mesh has to carry, for the side that builds the format.
	 * <p>
	 * Read at one instant of that side's own choosing and not per frame, for the reason
	 * {@link #asked()} gives: the format may only change where nothing is left holding the old one.
	 */
	public static List<String> carried() {
		return carried;
	}

	/** Whether the shadow map is drawn, from the loaded options. */
	static void shadowWanted(boolean asked) {
		shadowWanted = asked;
		shadowEpoch++;
	}

	/**
	 * Takes the stage down after something in it threw, and empties the map on the way.
	 * <p>
	 * The stage and not the pack, which is the call {@link #shadowsPrepared} already makes and for the
	 * same reason: a shadow program failing says nothing about the three the camera draws with, and a
	 * map that stops being drawn is a picture without shadows rather than no picture at all. Left
	 * uncaught the throw reaches the game through an event handler and comes back on the very next
	 * frame, so what the player would see is not a shadow that stopped but a game that will not run.
	 * <p>
	 * The map is emptied here rather than by the branch of {@link #openShadowStage} that empties every
	 * other refusal, because that branch is out of reach once this flag is down: the frame graph stops
	 * capturing the camera as soon as {@link #shadows()} answers no, so the stage returns before it
	 * gets there. Left as the failed frame put it, the pack would keep reading a half drawn map for
	 * the rest of the session, and half a map looks exactly like a shadow. Both names the depth is
	 * read under go with it, which is {@link ShadowTargets#clear}'s own doing and is the whole reason
	 * the promise below can be made: emptying the map alone would leave {@code shadowtex1} on the copy
	 * the failed frame took, frozen for the session.
	 * <p>
	 * Nothing is emptied when there is no chain or no device, and nothing needs to be: the samplers
	 * are bound by the chain's own programs, and {@link PackChain#terrain()} answers null exactly when
	 * there is no chain left to bind them.
	 * <p>
	 * Public because the stage itself is driven from the loader module, which is where the bus is.
	 */
	public static void shadowStageFailed(RuntimeException e) {
		shadowWanted = false;
		shadowEpoch++;
		shadowMapFilled = false;
		Vitrail.logger().error("Vitrail stopped drawing the shadow map after an error in the stage, "
				+ "so every shadowtex lookup of the pack reads the far plane", e);

		TerrainDraw self = PackChain.terrain();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (self == null || device == null) {
			return;
		}

		try {
			self.targets.shadow().clear(device.createCommandEncoder());
		} catch (RuntimeException second) {
			// Not rethrown: this is the handler the bus called, and the error above is the one worth
			// reading. A pass left open by whatever threw there is enough to refuse a clear, so the
			// second one is said rather than swallowed - it is what makes the line above stop being
			// true.
			Vitrail.logger().error("The shadow map could not be emptied either, so what the pack reads "
					+ "as a shadow is whatever the frame that failed left in it", second);
		}
	}

	/**
	 * Which families the loaded pack draws into its shadow map, and everything but the player alone
	 * where no pack is loaded at all.
	 * <p>
	 * Answered here rather than read off the pack by each caller, because the stage has one of them
	 * and the door has another: two readings of one directive are two answers waiting to disagree,
	 * and the disagreement would be a family drawn into a map the pack asked to keep it out of.
	 */
	public static ShadowCasters shadowCasters() {
		TerrainDraw self = PackChain.terrain();

		return self == null ? DEFAULT_CASTERS : self.values.shadowCasters();
	}

	/**
	 * How far from the camera a caster that moves may still be and reach the map, or a value that is
	 * not positive where the pack sets no bound of its own. Answered here for the same reason the
	 * caster set is: one directive, one reading.
	 */
	public static float entityShadowDistance() {
		TerrainDraw self = PackChain.terrain();

		return self == null ? -1.0F
				: self.values.entityShadowDistance(PackChain.shadowDistance(), renderDistanceChunks());
	}

	/**
	 * How far from the camera the light still gathers the world, in BLOCKS, or minus one where
	 * nothing bounds it beyond the light's own frustum.
	 * <p>
	 * The arithmetic and the two units are {@link PackValues#shadowRenderDistance}'s and are said
	 * there; what happens here is that the two numbers it needs are fetched from the two places
	 * that hold them, the player's setting from {@code pack.txt} and the render distance from the
	 * game's own options.
	 */
	public static float shadowRenderDistance() {
		TerrainDraw self = PackChain.terrain();

		return self == null ? -1.0F
				: self.values.shadowRenderDistance(PackChain.shadowDistance(), renderDistanceChunks());
	}

	/**
	 * How far the light reaches once the pack has had its say, in CHUNKS: the pack's own number
	 * where it forces one, and the player's otherwise. Iris's
	 * {@code IrisVideoSettings.getOverriddenShadowDistance} ({@code gui/option/IrisVideoSettings.java:61-65}),
	 * and read for the two things that number decides rather than a distance: what the slider shows,
	 * and whether the shadow stage runs at all.
	 */
	public static int shadowDistanceChunks() {
		return forcedShadowDistanceChunks().orElseGet(PackChain::shadowDistance);
	}

	/**
	 * How far the loaded pack itself insists the light gathers, in chunks, or empty where it leaves
	 * the distance to the player. What the video settings grey their slider out on.
	 */
	public static OptionalInt forcedShadowDistanceChunks() {
		TerrainDraw self = PackChain.terrain();

		return self == null ? OptionalInt.empty() : self.values.forcedShadowRenderDistanceChunks();
	}

	/**
	 * As far as there is a world to gather, in chunks. Zero where the game has no options yet, which
	 * bounds nothing and is what a frame drawn before there is a world should get.
	 */
	private static int renderDistanceChunks() {
		Minecraft minecraft = Minecraft.getInstance();

		return minecraft == null ? 0 : minecraft.options.getEffectiveRenderDistance();
	}

	/**
	 * What a frame with no pack loaded answers, taken from the reading rather than spelled again:
	 * one directive has one default, and two literals of it are two answers waiting to disagree.
	 */
	private static final ShadowCasters DEFAULT_CASTERS = ShadowCasters.DEFAULT;

	/**
	 * Whether the shadow map is drawn at all, asked of the LOAD rather than of this frame.
	 * <p>
	 * The question {@link #shadows} asks needs a chain already standing, which is not true at the
	 * moment another family is deciding what to read the pack for: what a load has to know is
	 * whether the map will be drawn this session, so that a family reads its shadow programs then
	 * and not in the middle of a frame.
	 */
	static boolean shadowsAsked() {
		return wanted && shadowWanted;
	}

	/**
	 * Whether a shadow pass may run at all: the pack's own geometry has to be drawing, or the map
	 * would be filled by the game's chunk shader, which writes the world's colours into it.
	 */
	public static boolean shadows() {
		return wanted && shadowWanted && PackChain.terrain() != null;
	}

	/**
	 * Whether the shadow map is being drawn: the stage is on and every shadow pass has a program
	 * that can still be served.
	 * <p>
	 * What it decides is whether a mob keeps the game's own ground oval. Iris takes that oval away
	 * for as long as its shadow renderer stands ({@code pipeline/IrisRenderingPipeline.java:1101-1104}),
	 * and the renderer is made with the shadow targets ({@code :451-469}), whatever the distance is
	 * set to. One edge of that condition is not read the same way here, and it is on the side
	 * of the stage rather than of this line. Iris allocates the targets for a pack that merely
	 * samples {@code shadowtex} from a composite ({@code :767-768}), with no shadow program at all,
	 * and takes the oval away under it; here a pack without a shadow program gets no stage and keeps
	 * its oval. A pack's {@code shadow.enabled=false} closes the stage on both engines the same way:
	 * Iris nulls its renderer ({@code :464}) and here no shadow program is loaded at all. What the
	 * remaining edge costs is one oval more or less under a pack of which the corpus has none.
	 */
	public static boolean shadowMapServed() {
		TerrainDraw self = PackChain.terrain();

		return self != null && shadows() && self.shadowsServed();
	}

	/**
	 * Takes the copy the pack reads as {@code shadowtex1}. The caller invokes it between the opaque
	 * halves of the shadow map and the translucent one, which is the only moment the two names mean
	 * different things, and outside any render pass: the renderer closes its own before returning.
	 * <p>
	 * <strong>Skipped whole where no source of the pack names what it feeds.</strong> The copy is
	 * the map at one resolution and four bytes a texel, taken every frame, and it answers exactly
	 * one name: a pack that never reads it pays sixty-four mebibytes a frame at a shadow map of
	 * 4096 for an image nothing samples. Of the eight packs measured, Mellow is the one that gets
	 * there, naming {@code shadowtex0} and nothing else. Bliss declares the name in no program
	 * either, its {@code shadowtex1} sitting behind a setting it ships switched off, but it WRITES
	 * that name in eight files and what is read here is text, so it keeps the copy: the pack turns
	 * that setting on from its own screen, and the copy is there when it does.
	 * <p>
	 * The question is asked of the pack's text rather than of a sampler plan because of WHEN it
	 * has to be answered: here, during the shadow stage, while the programs that would read the
	 * copy bind later in the same frame and six of the seven geometry families may not have been
	 * read at all. What that reading can prove is only ever the absence of a name, which is the
	 * half that makes this safe.
	 */
	public static void copyShadowDepth() {
		TerrainDraw self = PackChain.terrain();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (self != null && device != null && self.owner.mayReadShadowWithoutTranslucents()) {
			self.targets.shadow().copyWithoutTranslucents(device.createCommandEncoder());
		}
	}

	/**
	 * Whether this frame should re-record the shadow map, or keep the last one.
	 * <p>
	 * Default is every frame, which is Iris. The player can ask for every other frame, or only when
	 * the camera has moved. The first frame of a pack, a load or a stage that emptied the map always
	 * records, so a skip cannot leave an uninitialised or destroyed image.
	 * <p>
	 * Distant Horizons writes the same map after it is cleared, and there is no separate skip path
	 * that would refresh the far terrain alone. While DH is usable the map is always recorded, the
	 * conservative answer: a skip would freeze near and far shadows together, and updating DH
	 * without clearing would need a second command buffer this batch must not open.
	 */
	public static boolean shouldRecordShadow(Vec3 camera, Matrix4fc modelView) {
		if (seenShadowEpoch != shadowEpoch) {
			seenShadowEpoch = shadowEpoch;
			shadowMapFilled = false;
			skipAlternate = false;
			lastShadowCamera = null;
		}

		if (!shadowMapFilled) {
			return true;
		}

		if (DhLods.usable()) {
			return true;
		}

		return switch (PackChain.shadowRefresh()) {
			case EVERY_FRAME -> true;
			case EVERY_TWO_FRAMES -> {
				skipAlternate = !skipAlternate;
				yield !skipAlternate;
			}
			case WHEN_CAMERA_MOVES -> cameraMoved(camera, modelView);
		};
	}

	/** The walk filled the map. Later skips may keep it. */
	public static void shadowMapFilled(Vec3 camera, Matrix4fc modelView) {
		shadowMapFilled = true;
		skipAlternate = false;
		lastShadowCamera = camera;
		LAST_SHADOW_VIEW.set(modelView);
	}

	/** The map was emptied or never drawn. The next frame must record. */
	public static void shadowMapDropped() {
		shadowMapFilled = false;
		skipAlternate = false;
		lastShadowCamera = null;
	}

	private static boolean cameraMoved(Vec3 camera, Matrix4fc modelView) {
		if (lastShadowCamera == null) {
			return true;
		}

		double dx = camera.x - lastShadowCamera.x;
		double dy = camera.y - lastShadowCamera.y;
		double dz = camera.z - lastShadowCamera.z;
		if (dx * dx + dy * dy + dz * dz > (double) CAMERA_MOVE * CAMERA_MOVE) {
			return true;
		}

		return !LAST_SHADOW_VIEW.equals(modelView, CAMERA_MOVE);
	}

	/**
	 * Opens the end-of-frame shadow stage: makes the map exist, empties it, and settles that its
	 * three programs will really be served, once and before any group is drawn into it. Must run
	 * outside any render pass, which the end of the frame is.
	 * <p>
	 * The map is cleared here and not with the colour targets, and that is the point of the stage
	 * running where it does. The stage draws at the very end of a frame, for the next one, so the
	 * map has to survive the frame boundary: the gbuffers read all frame long what the previous
	 * stage drew, and a clear where the frame opens would hand them an empty map every time.
	 * <p>
	 * Nothing here opens the frame itself. By this point the terrain or the chain has opened it,
	 * and calling {@code beginFrame} again would advance the value store a second time, which turns
	 * every {@code gbufferPrevious*} of the next frame into the current one.
	 */
	public static boolean openShadowStage() {
		TerrainDraw self = PackChain.terrain();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (self == null || device == null) {
			return false;
		}

		ShadowTargets shadow = self.targets.shadow();
		if (!shadows() || !self.shadowsServed() || shadowDistanceChunks() == 0) {
			// A shadow distance of nought is not a very short walk, it is no stage at all, and the
			// test is on the CHUNKS rather than on the blocks the walk is bounded by. That is Iris's
			// own: renderShadows returns at its first line when the distance the pack or the player
			// settled on is zero (shadows/ShadowRenderer.java:384-387), before a frustum is built or
			// a target is touched. Bounding the walk at zero blocks instead would cost a full walk
			// and a full clear to draw nothing.
			//
			// What the pack reads then is the DEPTH emptied to the far plane, both names of it,
			// because the clear below empties the depth whichever way the pack's own keep directive
			// reads (ShadowTargets.java:236-241). A shadowcolor the pack asked to KEEP is a
			// different matter and is NOT emptied (:243-248 through :256-264): it holds the last
			// frame the stage drew, for as long as the setting stays at nought. Iris returns before
			// touching any of its targets, so there both halves keep the last frame.
			//
			// A pack that serves no shadow program gets no shadow pass, which is Iris's rule, and
			// at the end of a frame it is also the only safe answer: with nothing of ours to hand
			// the renderer, the pass it opens for itself is the game's own target, and the stage
			// would paint the world over the finished image. The map is emptied rather than left
			// standing, so a program broken mid-session reads as no shadow and not as the last
			// map it ever drew, frozen.
			//
			// The stage switched off by the engine option does not come through here, whatever the
			// shadows() test above may suggest: the frame graph stops capturing the camera at the
			// head of the frame, so the stage returns long before it reaches this. Its map is the
			// one ShadowTargets.ensure empties where it allocates, which is the same answer at no
			// cost per frame.
			shadow.clear(device.createCommandEncoder());

			return false;
		}

		if (!shadow.ensure()) {
			return false;
		}

		shadow.defer();

		// After the defer, so that a refusal below still empties the map, and before the stage is
		// declared open, which is the whole point of the step.
		if (!self.shadowsPrepared(device)) {
			shadow.flushPending(device.createCommandEncoder());
			return false;
		}

		return shadow.depth() != null;
	}

	/**
	 * Compiles the three shadow programs and equips them, outside any render pass and before the
	 * stage is declared open.
	 * <p>
	 * {@link #shadowsServed} is a promise the first compilation can still break, because
	 * {@code broken} is raised by that compilation, and raising it inside the pass the renderer has
	 * already opened is what this avoids. A refusal there is safe for a camera pass and is the
	 * opposite here: with nothing of ours handed back, Sodium opens its own pass on the target
	 * {@code vitrail$target} gave it, the game's own, and the shadow half repaints the whole opaque
	 * world over the finished image, coplanar and under the same reversed Z so nothing stops it.
	 * Asked here, the same refusal only closes the stage.
	 * <p>
	 * Everything it does is done again by the real prepare a few lines of the renderer later, and
	 * every bit of it is idempotent: {@code precompilePipeline} is a computeIfAbsent, the three
	 * constant textures and the ring buffer are made once, {@code announce} is latched, and the
	 * block is written again with the same values into the same buffer of the ring, which turns at
	 * the frame boundary and not here. The atlas is null because there is none to hand yet, and the
	 * real prepare puts it back well before anything is bound.
	 * <p>
	 * Called once {@link #shadowsServed} has said yes, so every shadow pass has a program. That is a
	 * precondition and not a hope the catch below covers: the catch names the program it failed on,
	 * so a missing one would throw a second time from inside it and leave the stage half open.
	 */
	private boolean shadowsPrepared(GpuDevice device) {
		for (TerrainPass pass : TerrainPass.values()) {
			if (!pass.shadow()) {
				continue;
			}

			TerrainProgram program = this.programs.get(pass);
			try {
				if (program.prepare(device, null) == null) {
					// The line this step exists for. Nothing on screen can say it: the artefact lasts
					// one frame, and a stage that never opens leaves no trace at all.
					Vitrail.logger().warn("{} refused to prepare, so the shadow stage does not open: "
							+ "with nothing of ours to hand the renderer, the pass it opens for itself "
							+ "is the game's own target and the stage would paint the world over the "
							+ "finished image", program.path());

					return false;
				}
			} catch (RuntimeException e) {
				shadowWanted = false;
				Vitrail.logger().error("Vitrail stopped drawing the shadow map after an error in "
						+ program.path(), e);

				return false;
			}
		}

		return true;
	}

	/**
	 * Whether every shadow pass has a program that can still be served. All or nothing: the three
	 * passes draw into one map, and a stage that drew the opaque half and refused the translucent
	 * one would read as a pack behaviour rather than as the refusal it is.
	 * <p>
	 * True is a promise about the programs as they stand and not about the ones that have yet to be
	 * compiled, which is what {@link #shadowsPrepared} settles before the stage opens.
	 */
	private boolean shadowsServed() {
		for (TerrainPass pass : TerrainPass.values()) {
			if (pass.shadow()) {
				TerrainProgram program = this.programs.get(pass);
				if (program == null || !program.servable()) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Draws one group of the shadow map, by running the caller back over the chunk renderer with the
	 * flag set. The caller is the only side that can name a Sodium pass, which is why the draw
	 * arrives as a runnable rather than this module reaching for one.
	 * <p>
	 * The draw is refused outright when there is no map. That refusal is the whole safety of this
	 * step: with the flag set and no map to draw into, the descriptor would come back null, the
	 * renderer would open its own pass on the game's target, and the pack's shadow program would
	 * paint the screen with whatever it writes.
	 */
	public static void shadowPass(Runnable draw) {
		TerrainDraw self = PackChain.terrain();
		if (self == null || !shadows() || self.targets.shadow().depth() == null) {
			return;
		}

		shadowing = true;
		try {
			draw.run();
		} finally {
			shadowing = false;
		}
	}

	/**
	 * The matrix that culls the world for the light, the shadow pair multiplied through, or null
	 * when no pack is drawing. This frame's pair, which is also the pair the map is drawn with.
	 */
	public static Matrix4f shadowFrustum(Matrix4f dest) {
		TerrainDraw self = PackChain.terrain();

		return self == null ? null : self.values.shadowFrustum(dest);
	}

	/**
	 * What the light's walk needs to choose a shape to measure a section against, or null when no
	 * pack is drawing. The two scratch objects handed in are written and carried into the answer.
	 * <p>
	 * It carries the bound {@link #shadowRenderDistance} would have answered, so the two are never
	 * asked for separately: the states that step outside that arbitration do it inside
	 * {@link PackValues#shadowCullPlan}, and a caller taking the shape from one and the distance
	 * from the other would put a pack's own bound under a shape that asked for the player's.
	 */
	public static ShadowCullPlan shadowCullPlan(Vector3f light, Matrix4f camera) {
		TerrainDraw self = PackChain.terrain();

		return self == null ? null : self.values.shadowCullPlan(PackChain.shadowDistance(),
				renderDistanceChunks(), light, camera);
	}

	/**
	 * The two halves of that pair, written into the matrices handed in, and false with both left
	 * alone when no pack is drawing.
	 */
	public static boolean drawnShadowPair(Matrix4f modelView, Matrix4f projection) {
		TerrainDraw self = PackChain.terrain();
		if (self == null) {
			return false;
		}

		self.values.drawnShadowPair(modelView, projection);

		return true;
	}

	/** Whether the renderer is drawing the shadow map at this instant, for the loader side. */
	public static boolean drawingShadow() {
		return shadowing;
	}

	/**
	 * Which pass a call from the renderer really means, given where in the frame it arrives. Null
	 * when the shadow stage has no counterpart for it, and then the renderer keeps its own shader.
	 */
	private static TerrainPass drawn(TerrainPass pass) {
		return shadowing ? pass.inShadow() : pass;
	}

	/**
	 * The same, for the side that has to decide what the chunk mesh carries.
	 * <p>
	 * Read at one instant of that side's own choosing and not per frame: the format may only change
	 * where nothing is left holding the old one, which is when the chunk renderer is being built
	 * again from nothing. {@link #wanted(boolean)} is what asks for that to happen.
	 */
	public static boolean asked() {
		return wanted;
	}

	/**
	 * What the world's own opaque geometry does about the mask the scene seed is cut with, which
	 * anything else meaning to claim a pixel against that seed has to know before it claims one.
	 * <p>
	 * Both opaque halves and not one: a half the pack serves nothing for keeps the renderer's own
	 * shader, draws into the game's target and reaches the pack's colour target through the seed,
	 * exactly like a half that has no mask. Either of them in that position is enough to make a
	 * claim elsewhere on the screen take the world away.
	 */
	public static Mask opaqueMask() {
		TerrainDraw draw = PackChain.terrain();
		if (draw == null || !wanted) {
			return Mask.ABSENT;
		}

		if (!draw.read) {
			return Mask.UNREAD;
		}

		for (TerrainPass pass : OPAQUE) {
			TerrainProgram program = draw.programs.get(pass);
			if (program == null || !program.covers()) {
				return Mask.ABSENT;
			}
		}

		return Mask.WRITTEN;
	}

	/**
	 * Answers the pipeline to draw one chunk pass with, reading and translating the pack's programs
	 * the first time it is asked.
	 *
	 * @param pass   which of the three passes is being drawn, named in this engine's own terms
	 *               because nothing in this module is allowed to name Sodium
	 * @param format the chunk mesh format, handed in rather than looked up, for the same reason
	 * @param atlas  the block atlas of the pass being drawn
	 * @return the pipeline to draw with, or null to leave the game's own shader alone
	 */
	public static RenderPipeline pipeline(TerrainPass pass, VertexFormat format,
			GpuTextureView atlas) {
		TerrainDraw draw = PackChain.terrain();
		TerrainPass drawn = drawn(pass);
		if (draw == null || !wanted || drawn == null) {
			return null;
		}

		try {
			return draw.prepare(drawn, format, atlas);
		} catch (RuntimeException e) {
			wanted = false;
			Vitrail.logger().error("Vitrail stopped drawing the terrain after an error", e);

			return null;
		}
	}

	/** The sampler the game configured for the block atlas, taken where a chunk pass begins. */
	public static void sampler(GpuSampler sampler) {
		TerrainDraw draw = PackChain.terrain();
		if (draw != null) {
			draw.programs.values().forEach(program -> program.sampler(sampler));
		}
	}

	/**
	 * Binds the terrain program's block and samplers, inside the pass the chunk renderer opened.
	 * <p>
	 * Called for every chunk pass and not only ours, so the pipeline that is really bound decides.
	 * Binding into a pass drawing the renderer's own shader would be harmless, since the descriptor
	 * flush walks the layout of the bound pipeline, but it would also mean this engine had stopped
	 * knowing which of the two was drawing.
	 */
	public static void bind(RenderPass pass, RenderPipeline bound) {
		TerrainDraw draw = PackChain.terrain();
		if (draw == null) {
			return;
		}

		TerrainProgram program = draw.owner(bound);
		if (program != null) {
			program.bind(pass);
		}
	}

	/**
	 * Which of the chunk programs owns the pipeline Sodium has bound, or null when none does.
	 * <p>
	 * Asked once per region drawn, and Sodium draws a region at a time: the map is three entries, but
	 * walking it meant an iterator built and three virtual calls made for every one of them, twice
	 * over on a frame with a shadow map, to reach the same answer as the region before. A pipeline
	 * belongs to one program and to no other, so the last pair answers the whole run.
	 * <p>
	 * The no is remembered too, and it is the half that pays: the renderer's own shader is bound for
	 * every chunk pass a pack does not take over, and a run of those used to walk the map to the end
	 * each time to find nothing.
	 */
	private TerrainProgram owner(RenderPipeline bound) {
		if (this.lastBound == bound && !PassTimings.keepRedoneWork()) {
			return this.lastOwner;
		}

		PassTimings.censusProgramWalk();
		this.lastBound = bound;
		this.lastOwner = null;
		for (TerrainProgram program : this.programs.values()) {
			if (program.owns(bound)) {
				this.lastOwner = program;
				break;
			}
		}

		return this.lastOwner;
	}

	/**
	 * The render pass one chunk pass wants opened, or null to leave the chunk renderer's own alone.
	 * <p>
	 * Asked for every chunk pass and not only ours, so the pass being drawn decides. A program that
	 * writes one draw buffer never answers: it wants exactly the pass Sodium was going to open, and
	 * building an identical one of our own would only be a way of getting it wrong later.
	 */
	public static RenderPassDescriptor descriptor(TerrainPass pass, GpuTextureView colour,
			GpuTextureView depth) {
		TerrainDraw draw = PackChain.terrain();
		TerrainPass drawn = drawn(pass);
		if (draw == null || !wanted || drawn == null) {
			return null;
		}

		// The same answer the pipeline gives, and it has to be the same one: the pipeline carries
		// a colour state per attachment this names, and one of the two refusing alone is setPipeline
		// throwing by name in the middle of Sodium's own draw. The two are asked at two points of one
		// pass, begin and render, and nothing between them moves the answer: it turns where the chain
		// warms up, which is either side of the whole pass and never inside it.
		if (!shadowing && !draw.shown()) {
			return null;
		}

		TerrainProgram program = draw.programs.get(drawn);

		return program == null ? null : program.descriptor(colour, depth);
	}

	/**
	 * Whether what a camera pass writes still reaches the screen this frame.
	 * <p>
	 * Read by the pipeline and by the descriptor and by nothing else. A pass whose draw buffer nought
	 * goes to the pack's own target has no way of its own back to the screen; the chain's final is
	 * that way, and the chain draws nothing at all while it is still compiling, one program a frame
	 * after every load and every resource reload. Those frames drew the world into a colour target
	 * nothing read, which is a screen with no world in it for as long as the warm up lasts.
	 * <p>
	 * A pack whose chain is switched off never asks: {@code chainRuns} already keeps draw buffer
	 * nought on the game's target, and the world is drawn where it has always been drawn.
	 * <p>
	 * The shadow stage never asks either, and that is the one refusal that would not be safe: with
	 * nothing of ours handed back, Sodium opens its own pass on the game's target and the shadow half
	 * paints the world seen from the light over the finished image. {@link #openShadowStage} settles
	 * that question once, where it can be settled outside a pass.
	 */
	private boolean shown() {
		return !this.chainRuns || this.owner.drawable();
	}

	private RenderPipeline prepare(TerrainPass pass, VertexFormat format, GpuTextureView atlas) {
		if (!this.read) {
			this.read = true;
			// Asked of the programs and not of the mesh, because a pack that serves no chunk program
			// at all declares nothing and asked for nothing: the mesh was never extended for it, and
			// comparing an empty list against Sodium's own four would put the whole pack away over a
			// chunk pass it never wanted. It keeps the game's own shader for the six passes, which is
			// a normal thing for a pack to do, and its sky and its chain go on being drawn.
			if (!this.loaded.isEmpty() && !TerrainProgram.carries(format, this.declares)) {
				this.owner.putAway("the chunk mesh does not carry what a terrain program reads");

				return null;
			}

			this.programs = TerrainProgram.build(this.loaded, this.values, this.load, format,
					this.plan, this.chainTargets, this.chainRuns, this.targets);
			// With the map it answers out of. A pipeline that outlived a rebuild would otherwise be
			// answered with the program of the map before it.
			this.lastBound = null;
			this.lastOwner = null;
		}

		TerrainProgram program = this.programs.get(pass);
		if (program == null) {
			return null;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return null;
		}

		if (!program.compile(device)) {
			return null;
		}

		// Not during the shadow stage, and that is not an optimisation. The stage runs once the
		// chain has closed the frame, so the per frame guards are down again: opening here would
		// advance the value store a second time, which turns every gbufferPrevious* of the next
		// frame into the current one, and clear the colour targets over what the chain just wrote.
		// Everything these two calls provide, the stage already has: the values were advanced when
		// this frame opened, and the map is ensured and emptied by openShadowStage.
		if (!shadowing) {
			this.owner.beginFrame();

			// Before the pipeline and before the pass, which is the whole point: the clears belong
			// ahead of the world now that something writes the pack's targets during it. And a frame
			// where the targets cannot be opened keeps the game's own shader outright: the pipeline
			// carries one colour state per attachment the descriptor would have named, and Sodium's
			// own pass, the only one left to bind it into, carries exactly one.
			if (!this.owner.openTargets(device)) {
				return null;
			}

			// After the two calls above and not with the guards at the top of the two doors, which is
			// the whole reason it sits here. The frame boundary hangs off whichever of the terrain and
			// the chain comes first, and while the chain is warming up the chain never gets far enough
			// to open one: refused at the door, these frames would leave the value store standing, and
			// the frame that finally drew would call the camera of several frames ago its previous one.
			// The clears are owed for the same reason, or the first frame drawn would compose targets
			// holding whatever the warm up left in them.
			if (!shown()) {
				return null;
			}
		}

		return program.prepare(device, atlas);
	}

	/** The programs once they have been read, for the decoded dump. Empty until then. */
	Collection<TerrainProgram> programs() {
		return this.programs.values();
	}

	/** Rotates the ring buffers. Called once the frame's terrain draws have been recorded. */
	void rotate() {
		this.programs.values().forEach(TerrainProgram::rotate);
	}

	void release() {
		this.programs.values().forEach(TerrainProgram::release);
		// The same forgetting a rebuild does: nothing reaches a released holder today, but a memo
		// that outlives the map it answers out of is exactly the shape of trap the rebuild guards.
		this.lastBound = null;
		this.lastOwner = null;
	}
}
