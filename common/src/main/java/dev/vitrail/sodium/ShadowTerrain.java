package dev.vitrail.sodium;

import dev.vitrail.mixin.MixinSodiumWorldRenderer;
import dev.vitrail.mixin.RenderSectionManagerAccessor;
import dev.vitrail.pack.source.ShadowCasters;
import dev.vitrail.render.BlockStateIds;
import dev.vitrail.render.DistantDraw;
import dev.vitrail.render.RingTimings;
import dev.vitrail.render.ShadowCullPlan;
import dev.vitrail.render.ShadowGeometry;
import dev.vitrail.render.PackChain;
import dev.vitrail.render.TerrainDraw;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Draws the chunk renderer once more, at the very end of the frame, into the shadow map the next
 * frame will read.
 * <p>
 * The stage stands at the end of a frame and not at its top, and that placement is the culling
 * design. The world is walked a second time for the light, under the shape
 * {@link ShadowCullFrustum} chooses for it, which reuses the very lists
 * the camera's walk just filled: {@code ChunkRenderList} is one persistent object per region, so
 * there is nothing to save and restore, only an order to respect. Advancing the frame counter first
 * is what lets the second walk of a frame reset those lists instead of overflowing them, and raising
 * {@code needsRenderListUpdate} afterwards is what makes the camera's walk at the top of the next
 * frame take them back. One extra walk per frame, no bookkeeping.
 * <p>
 * <strong>The price is that the shadows are one frame late</strong>, drawn with this frame's sun for
 * the next frame's picture. That is a deliberate divergence from Iris, which culls, draws and reads
 * within one frame at the cost of restoring every piece of walk state it touched; a design this
 * project measured three failed attempts against. A sub-frame of sun motion is invisible - but only
 * the sun is allowed to be late, and the camera is not: the map is drawn around wherever the camera
 * stood on this frame, and the next frame measures its own player space from somewhere else. That
 * difference is put back where the pair is published, {@code ViewMatrices}, and without it every
 * shadow in the picture sits one frame of camera motion out of place.
 * <p>
 * Nothing of the draw itself is ours. {@code drawChunkLayer} is Sodium's own public entry; what
 * changes is that our two mixins answer differently while {@link TerrainDraw#shadowPass} holds its
 * flag, so the pipeline is the pack's {@code shadow} program and the render pass is opened on the
 * shadow map. The geometry, the regions and the push constants stay exactly where they were, which
 * is the only way to touch the most internal code Sodium has under a licence this project may not
 * copy from.
 * <p>
 * <strong>The matrices handed over are the camera's, deliberately, and they are not what the shadow
 * is drawn with.</strong> They go into Sodium's own {@code u_Globals}, which our programs never
 * read: they take their matrices from their own block, where the shadow pair is. Handing the shadow
 * pair here would leave that uniform holding the light's view for any chunk pass the pack does not
 * serve, and the game's own shader would then draw it from the sun.
 */
public final class ShadowTerrain {

	/** The frame's own model view, taken where the frame graph was handed it. */
	private static final Matrix4f MODEL_VIEW = new Matrix4f();

	/** Scratch for the light's cull matrix, one per process rather than one per frame. */
	private static final Matrix4f LIGHT = new Matrix4f();

	/** The same, for the camera's volume and the light's direction the walk's shape is built from. */
	private static final Matrix4f CAMERA = new Matrix4f();
	private static final Vector3f LIGHT_VECTOR = new Vector3f();

	private static Vec3 camera;

	/**
	 * The block table the cull was last measured against, or -1 for none. Counted rather than
	 * latched: a flag of the process would report the first pack of the session and say nothing for
	 * any pack loaded after it, which is where the reading is worth having.
	 */
	private static int measured = -1;

	/**
	 * What the last walk for the light kept, drew and measured against, held for the F3 line.
	 * <p>
	 * Held rather than asked for where it is shown, because by then the render lists belong to the
	 * camera again: this stage walks at the END of a frame and hands them straight back, so a count
	 * taken from the overlay would be the camera's under a shadow heading. Iris holds the same thing
	 * for the same reason, a string taken inside its shadow scope and read outside it
	 * ({@code shadows/ShadowRenderer.java:119} and {@code :606}).
	 * <p>
	 * A null {@link #walkCulling} means no walk has run under the pack now loaded, which the line
	 * says in those words rather than showing the numbers of a pack that is no longer drawn.
	 */
	private static int walkKept;

	private static int walkDrawn;

	private static int walkTotal;

	private static boolean walkTerrain;

	private static @Nullable String walkCulling;

	private ShadowTerrain() {
	}

	/**
	 * Takes what the frame was set up with, at the moment the frame graph is built. Read there and
	 * used at the end of the frame: by then the camera state has been walked over by everything in
	 * between, and the draw has to agree with what this frame's chunk passes were given.
	 */
	public static void capture(Matrix4fc modelView, Vec3 cameraPosition) {
		MODEL_VIEW.set(modelView);
		camera = cameraPosition;
	}

	/**
	 * What a frame left behind for the published shadow pair to measure itself from.
	 * <p>
	 * <strong>The published pair moves itself the camera motion separating it from the map, and it
	 * can only do that if it knows how much motion that is.</strong> The walk below returns without
	 * drawing on four roads and its throws are caught, so a frame drawing no map says nothing in any
	 * log. The three cases are not one: a stage that opened and gave up leaves a map older than the
	 * frame, while a stage that never opened leaves no map at all, and telling those apart is the
	 * difference between a correction that tracks and one that freezes on the first frame of the
	 * session.
	 */
	public enum MapState {

		/** The stage opened and drew, so the map on hand is the ending frame's own. */
		DREW,

		/** The stage opened and gave up before drawing, so the map on hand is older than one frame. */
		SKIPPED,

		/**
		 * No stage ran, so nothing is sampling a map of ours and there is nothing to stay faithful
		 * to. The pack still reads the four matrices, the whole corpus doing so from its composites,
		 * so the fresh pair is what they are given and the sun goes on turning in them.
		 */
		NONE
	}

	private static boolean served;

	private static boolean drew;

	/**
	 * Reads the state and clears it, so one map cannot be counted fresh by two frames. Called once a
	 * frame at the head of the frame, where the flags still carry what the previous frame's tail
	 * did.
	 */
	public static MapState takeMapState() {
		MapState taken = drew ? MapState.DREW : served ? MapState.SKIPPED : MapState.NONE;
		served = false;
		drew = false;
		return taken;
	}

	/**
	 * Walks the world for the light and draws the shadow map, using the state captured when this
	 * frame was set up. One draw per capture: a frame that never set a graph up draws no map.
	 * <p>
	 * Caught like every other entry point the bus calls into, and this was the one that was not. What
	 * it latches is the stage rather than the pack, see {@link TerrainDraw#shadowStageFailed}.
	 */
	public static void draw() {
		try {
			walk();
		} catch (RuntimeException e) {
			TerrainDraw.shadowStageFailed(e);
		}
	}

	private static void walk() {
		Vec3 camera = ShadowTerrain.camera;
		ShadowTerrain.camera = null;

		SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
		Minecraft minecraft = Minecraft.getInstance();
		if (camera == null || renderer == null || minecraft == null) {
			return;
		}

		// The same refusal the pipeline mixin makes, and it has to be made here too: under OpenGL
		// nothing of ours is ever served, so the stage would walk and draw the whole world a second
		// time with the game's own shader, into the game's own target. This is exactly the state a
		// failed Vulkan boot leaves the machine in.
		if (DrawBackend.BACKEND == DrawBackend.OPENGL) {
			return;
		}

		// Ordered so that a stage that cannot open leaves the render lists untouched: the walk
		// below hands them to the light, and from that point on the camera has to be given them
		// back whatever else happens.
		if (!TerrainDraw.openShadowStage()) {
			// Complementary Ultra still samples floodfill and WSR from gbuffers. The shadow
			// programs may be refused so the stage never opens, but the clear still has to run
			// or the voxel volume keeps stale writes; the compute itself runs at the head of
			// the frame, from the frame graph setup, whatever this stage does.
			PackChain.clearCustomImages();
			walkCulling = null;
			return;
		}

		// Past this line a stage is open and the pack is sampling a map of ours, so a frame that
		// gives up below leaves one older than itself rather than none at all.
		served = true;

		RenderSectionManager manager =
				((MixinSodiumWorldRenderer) renderer).vitrail$renderSectionManager();
		Matrix4f light = TerrainDraw.shadowFrustum(LIGHT);
		// Read here, in the same breath as the light's own matrix and off the same frame: the shape
		// the terrain is walked against is built from the camera's volume and the light's direction,
		// and the record's own note says what taking one of them a frame later would keep and drop.
		ShadowCullPlan plan = TerrainDraw.shadowCullPlan(LIGHT_VECTOR, CAMERA);
		if (manager == null || light == null || plan == null) {
			return;
		}

		// Counted only on the frame that will print it. It has to be taken HERE, the walk below
		// replacing the camera's render lists with the light's, but every other frame was walking
		// those lists for a line printed once per block table.
		//
		// Read once rather than again at the print, so a table installed between the two is named
		// on the next frame instead of at once. That is the right way round: the count in hand was
		// taken against the table that was standing when it was taken.
		boolean measuring = measured != BlockStateIds.generation();
		int seen = measuring ? sections(manager.getRenderLists()) : 0;

		// Which entities the light can see is worked out here, and for them the position settles
		// nothing: they are kept or dropped by a frustum and by a section's own state, neither of
		// which this walk moves. The block entities are the opposite case and are taken further
		// down, once the light has render lists of its own.
		ShadowCasters casters = TerrainDraw.shadowCasters();
		ShadowGeometry.gather(light, camera, casters);

		// The frame counter first, and it is the piece easiest to miss: the per region lists only
		// reset themselves for the first walk of a frame, so a second walk under the same number
		// appends to the camera's lists until it overflows
		// them. The walk itself is the synchronous fallback, which is the one path that neither
		// consults the asynchronous occlusion tree, empty for a viewport it has never seen, nor
		// waits for it.
		//
		// prepareRender would then rotate the indirect command ring. The camera already did that
		// at the top of the frame; a second rotate in the same frame is the stall #115 names.
		// The steps live here rather than as a default on the accessor: Mixin treats an interface
		// mixin with a default method as targeting an interface, and RenderSectionManager is a
		// class, so the config fails to prepare. The check is Mixin's own, seen on the Fabric boot.
		// keepShadowRotate puts the old call back so the two paths can be timed on the same jar.
		if (RingTimings.keepSecondRotate()) {
			manager.prepareRender();
		} else {
			RenderSectionManagerAccessor access = (RenderSectionManagerAccessor) manager;
			access.vitrail$setFrame(access.vitrail$getFrame() + 1);
			if (access.vitrail$cameraChanged()) {
				access.vitrail$invalidateRenderLists();
			}
		}
		try {
			FogParameters fog = ((MixinSodiumWorldRenderer) renderer).vitrail$lastFogParameters();
			// The shape the pack asked for, and a box around the camera cut out of it wherever a
			// shadow distance bounds the walk. By default that shape is the camera's own volume swept
			// along the light rather than the light's own: a section that cannot drop anything onto
			// what the camera can see is thrown away before it is drawn. Whether a bound applies at
			// all is the pack's business at least as often as the player's, most of the corpus
			// declaring a render multiplier and being held at its own half plane whatever the slider
			// says, and the plan carries the arbitration already made.
			ShadowCullFrustum.Chosen cull =
					ShadowCullFrustum.of(plan, new FrustumIntersection(light));
			Viewport viewport = new Viewport(cull.frustum(),
					new Vector3d(camera.x, camera.y, camera.z));
			manager.finalizeRenderLists(minecraft.gameRenderer.mainCamera(), viewport,
					fog == null ? FogParameters.NONE : fog, true);

			// The block entities, HERE and not with the entities above: this is the first line of the
			// stage at which the light has render lists of its own, and they are what says which
			// sections to ask. Sodium's door onto them is the one Iris reaches through the game's
			// extraction (shadows/ShadowRenderer.java:668, cancelled and served by the same mixin);
			// the game's own visible sections are never filled at all under Sodium, so the walk that
			// read them found a world with no chests in it.
			ClientLevel level = minecraft.level;
			if (level != null) {
				ShadowGeometry.gatherBlockEntities((state, partial) -> renderer.extractBlockEntities(
						minecraft.gameRenderer.mainCamera(), partial, level.destructionProgress(),
						state));
			}

			// Once per block table, and never on a frame where the camera saw nothing. Two equal
			// numbers mean the cull did not happen, and nothing on screen would say so. The table is
			// named because a second load of the same pack prints this again, word for word: it is
			// what tells the two readings apart, not a property of the cull itself.
			//
			// Two counts of the light's list rather than one, because they answer different
			// questions and only the second one compares with anything outside this engine. The
			// walked count is every section with something to render that the cull kept, which is
			// what says how tight the cull was; the drawn count leaves out those carrying no block
			// geometry, which are the ones a draw costs nothing for.
			count(manager.getRenderLists());
			walkTotal = manager.getTotalSections();
			walkTerrain = casters.terrain();
			walkCulling = cull.culling();

			if (measuring && seen > 0) {
				measured = BlockStateIds.generation();
				Vitrail.logger().info("Shadow cull walked {} sections for the light against {} "
						+ "for the camera, {} of the light's with geometry to draw, on block "
						+ "table {}, culling {}",
						walkKept, seen, walkDrawn, measured, walkCulling);
			}

			draw(renderer, minecraft, camera);
			drew = true;
		} finally {
			// The flag finalizeRenderLists just lowered, back up whatever happened above: the
			// camera's walk at the top of the next frame has to rebuild, or the world would be
			// drawn from the sun.
			((RenderSectionManagerAccessor) manager).vitrail$setNeedsRenderListUpdate(true);
		}
	}

	private static void draw(SodiumWorldRenderer renderer, Minecraft minecraft, Vec3 camera) {
		// Sodium's own source for it, so that what reaches u_Globals is what would have reached it
		// anyway: this one carries the walk bob and the camera state's does not.
		Matrix4fc projection =
				((GameRendererStorage) minecraft.gameRenderer).sodium$getProjectionMatrix();
		ChunkRenderMatrices matrices = new ChunkRenderMatrices(projection, MODEL_VIEW);

		// Mipmapped and clamped, which is the game's own chunk sampler short of its anisotropy. It
		// matters here rather than being tidiness: the cutout half of the shadow discards on the
		// atlas's alpha, and a leaf sampled without mipmaps casts a shadow that crawls at distance.
		GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true);

		ShadowCasters casters = TerrainDraw.shadowCasters();

		// Before geometry writes, matching Iris clearing custom images at the head of the
		// shadow stage. Complementary's voxel volume is marked clear; the floodfill is not.
		PackChain.clearCustomImages();

		// Refused by the pack rather than skipped for cheapness.
		if (casters.terrain()) {
			// Distant Horizons' far terrain goes first and INSIDE this word, both of which are
			// Iris's. DH hangs its LOD draws off the HEAD of ChunkSectionsToRender.renderGroup
			// (neoforge/mixins/client/MixinChunkSectionsToRender.java:67-74), and the only call to
			// that method in Iris's shadow stage is the one inside its own shadowTerrain test
			// (shadows/ShadowRenderer.java:508-511). So a pack that keeps the opaque world out of
			// its map keeps the far terrain out with it, and getting this wrong is not a nuance: a
			// pack that asked for neither would see LODs in its map here and none under Iris.
			DistantDraw.shadow(false, camera);
			TerrainDraw.shadowPass(() -> renderer.drawChunkLayer(ChunkSectionLayerGroup.OPAQUE,
					matrices, camera.x, camera.y, camera.z, sampler));
		}

		// Everything that moves, between the opaque world and the copy, which is where Iris puts it
		// (shadows/ShadowRenderer.java:584 then :588). It matters that it is before the copy and not
		// after: shadowtex1 is the map WITHOUT the translucent half, and a mob belongs in it. Drawn
		// after the copy, every caster that moves would be missing from the one name half the corpus
		// reads its shadows through.
		ShadowGeometry.draw(camera);

		// Between the translucent group and everything else, and nowhere else: this is the one moment
		// shadowtex0 and shadowtex1 hold different things, and what separates them is exactly the
		// draw that comes next. The renderer closes its own render pass before returning, and the
		// walk above closes its last one, so a copy here is outside one.
		TerrainDraw.copyShadowDepth();

		if (casters.translucent()) {
			// And its water half here, after the copy and inside the word that governs the world's
			// own translucent group, for the two reasons the opaque half is where it is: DH's hook
			// is the head of this very call, and Iris makes it inside its own shadowTranslucent test
			// (shadows/ShadowRenderer.java:598-601). shadowtex1 is the map WITHOUT the translucents,
			// and far water belongs on the same side of it as near water.
			DistantDraw.shadow(true, camera);
			TerrainDraw.shadowPass(() -> renderer.drawChunkLayer(ChunkSectionLayerGroup.TRANSLUCENT,
					matrices, camera.x, camera.y, camera.z, sampler));
		}
	}

	/** Every section a walk kept, which is what says how tight the shape it measured against was. */
	private static int sections(SortedRenderLists lists) {
		int count = 0;
		var iterator = lists.iterator(false);
		while (iterator.hasNext()) {
			ChunkRenderList list = iterator.next();
			count += list.size();
		}

		return count;
	}

	/**
	 * Both counts of the light's list, in one pass because the F3 line asks for them every frame
	 * and not once a block table any more.
	 * <p>
	 * <strong>The drawn count is the one that compares with the reference, and the kept count is
	 * not.</strong> Only the sections carrying block geometry reach a draw, which walks
	 * {@code sectionsWithGeometryIterator} and steps over the rest
	 * ({@code render/chunk/DefaultChunkRenderer}), and it is that count Iris puts on the F3 screen:
	 * it takes {@code getSectionStatistics} inside its own shadow render list scope
	 * ({@code shadows/ShadowRenderer.java:606}, the scope opened at {@code :477}), which Sodium
	 * answers from {@code getSectionsWithGeometryCount} and not from the list's size
	 * ({@code render/chunk/RenderSectionManager.getVisibleChunkCount}).
	 * <p>
	 * What the two differ by is narrow, and naming it is what says how far apart the numbers may
	 * stand. A render list holds every section with anything to render at all, block entities and
	 * animated sprites counted in ({@code RenderSectionFlags.MASK_NEEDS_RENDER}); one bit of that
	 * mask is what reaches a draw. So a section with nothing in it is on neither side of the
	 * comparison, and the gap is the sections whose only content is a block entity or an animated
	 * sprite.
	 * <p>
	 * One pass and not two, where Iris pays one for the single count it shows: the two sums walk
	 * the same handful of per region lists, so asking for them apart would double a walk this
	 * stage now makes on every frame.
	 */
	private static void count(SortedRenderLists lists) {
		int kept = 0;
		int drawn = 0;
		var iterator = lists.iterator(false);
		while (iterator.hasNext()) {
			ChunkRenderList list = iterator.next();
			kept += list.size();
			drawn += list.getSectionsWithGeometryCount();
		}

		walkKept = kept;
		walkDrawn = drawn;
	}

	/**
	 * The last walk for the light, or null where none has run under the pack now loaded.
	 *
	 * @param kept    every section with something to render that the walk kept
	 * @param drawn   those of them carrying block geometry, which is the count Iris shows
	 * @param total   every section loaded, which is the denominator Sodium's own line carries
	 * @param terrain whether the pack takes the world's own geometry into its map at all. The walk
	 *                runs either way, the entities and the far terrain being decided apart from it,
	 *                so a count without this flag beside it would announce sections that no draw
	 *                ever reads. Iris says the same thing in the same place, {@code (no terrain)}
	 *                appended to its own line ({@code shadows/ShadowRenderer.java:776})
	 * @param culling the shape the walk measured against, in the words the log line uses
	 */
	public record Walk(int kept, int drawn, int total, boolean terrain, String culling) {
	}

	/** The last walk, for the one line that shows it. */
	public static @Nullable Walk lastWalk() {
		return walkCulling == null ? null
				: new Walk(walkKept, walkDrawn, walkTotal, walkTerrain, walkCulling);
	}
}
