package dev.vitrail.neoforge.sodium;

import dev.vitrail.neoforge.mixin.MixinSodiumWorldRenderer;
import dev.vitrail.neoforge.mixin.RenderSectionManagerAccessor;
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
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.SimpleFrustum;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.world.phys.Vec3;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;

/**
 * Draws the chunk renderer once more, at the very end of the frame, into the shadow map the next
 * frame will read.
 * <p>
 * The stage stands at the end of a frame and not at its top, and that placement is the culling
 * design. The world is walked a second time under the light's frustum, which reuses the very lists
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

	private static Vec3 camera;

	private static boolean measured;

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
			return;
		}

		RenderSectionManager manager =
				((MixinSodiumWorldRenderer) renderer).vitrail$renderSectionManager();
		Matrix4f light = TerrainDraw.shadowFrustum(LIGHT);
		if (manager == null || light == null) {
			return;
		}

		int seen = sections(manager.getRenderLists());

		// The frame counter first, and it is the piece the three failed designs before this one
		// were missing: the per region lists only reset themselves for the first walk of a frame,
		// so a second walk under the same number appends to the camera's lists until it overflows
		// them. The walk itself is the synchronous fallback, which is the one path that neither
		// consults the asynchronous occlusion tree, empty for a viewport it has never seen, nor
		// waits for it.
		manager.prepareRender();
		try {
			FogParameters fog = ((MixinSodiumWorldRenderer) renderer).vitrail$lastFogParameters();
			Viewport viewport = new Viewport(new SimpleFrustum(new FrustumIntersection(light)),
					new Vector3d(camera.x, camera.y, camera.z));
			manager.finalizeRenderLists(minecraft.gameRenderer.mainCamera(), viewport,
					fog == null ? FogParameters.NONE : fog, true);

			// Once, and never on a frame where the camera saw nothing. Two equal numbers mean the
			// cull did not happen, and nothing on screen would say so.
			if (!measured && seen > 0) {
				measured = true;
				Vitrail.logger().info("Shadow cull walked {} sections for the light against {} "
						+ "for the camera", sections(manager.getRenderLists()), seen);
			}

			draw(renderer, minecraft, camera);
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

		TerrainDraw.shadowPass(() -> renderer.drawChunkLayer(ChunkSectionLayerGroup.OPAQUE, matrices,
				camera.x, camera.y, camera.z, sampler));

		// Between the two groups and nowhere else: this is the one moment shadowtex0 and shadowtex1
		// hold different things, and what separates them is exactly the draw that comes next. The
		// renderer closes its own render pass before returning, so a copy here is outside one.
		TerrainDraw.copyShadowDepth();

		TerrainDraw.shadowPass(() -> renderer.drawChunkLayer(ChunkSectionLayerGroup.TRANSLUCENT,
				matrices, camera.x, camera.y, camera.z, sampler));
	}

	private static int sections(SortedRenderLists lists) {
		int count = 0;
		var iterator = lists.iterator(false);
		while (iterator.hasNext()) {
			ChunkRenderList list = iterator.next();
			count += list.size();
		}

		return count;
	}
}
