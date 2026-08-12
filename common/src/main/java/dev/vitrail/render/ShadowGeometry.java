package dev.vitrail.render;

import dev.vitrail.pack.source.ShadowCasters;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import java.util.List;

/**
 * Walks the world a second time, for the light, and submits everything that moves into the shadow
 * map.
 * <p>
 * <strong>A second submission and not a second reading of the first one, and that is forced rather
 * than chosen.</strong> The game clears the two lists this would have read the moment it has
 * submitted them: {@code LevelRenderer.submitFeatures} calls
 * {@code levelRenderState.entityRenderStates.clear()} on the line after {@code submitEntities} and
 * does the same for the block entities ({@code LevelRenderer.java:284-287}). The shadow stage stands
 * at the very end of the frame, so by the time it runs both are empty. Iris resubmits for a
 * different reason that holds here too: the camera's lists were culled against the CAMERA's frustum,
 * and what has to be in a shadow map is what the LIGHT can see, which is mostly what the camera
 * cannot ({@code shadows/ShadowRenderer.java:124-125,181-182,684,659}).
 * <p>
 * <strong>The storage and the dispatcher are ours and are built once.</strong> A dispatcher holds
 * one {@code PreparedFrame} and reuses it ({@code feature/FeatureRenderDispatcher.java:35}), so
 * borrowing the game's would re-enter the frame it is in the middle of. Built lazily and kept,
 * rather than per frame: it allocates a {@code RenderBuffers} and thirteen feature renderers.
 * <p>
 * <strong>What is not served here is not drawn here, and that is a divergence from Iris.</strong>
 * Iris binds the shadow framebuffer for the whole of its stage, so a pipeline its shadow table has
 * no key for keeps the game's own shader and still writes the map ({@code pipeline/IrisPipelines
 * .java:85-134} names no {@code ENTITY_SHADOW}, and a null key leaves the vanilla shader standing).
 * Here the target is chosen per draw, off {@code PreparedRenderType.outputTarget()}, and the game's
 * pipeline declares one colour state at the main target's format: steering it onto the map's
 * attachments is refused by name at {@code setPipeline}, in the middle of the draw. So a draw the
 * table has no row for is DROPPED for the length of this walk instead of being handed back.
 * <strong>What it costs the image</strong> is that a caster this engine has no row for casts no
 * shadow at all, where under Iris it would cast one drawn with the game's shader. The alternative
 * was handing it back, and that is worse than a missing shadow by the measure this repository uses:
 * the pass open at that moment carries the finished picture, so the caster would be painted across
 * the frame the player is looking at.
 */
public final class ShadowGeometry {

	/**
	 * The buffers, the storage and the dispatcher this walk submits through, built at the first walk
	 * and kept for the session. Null until then, and all three stand or fall together.
	 */
	private static RenderBuffers buffers;
	private static SubmitNodeStorage storage;
	private static FeatureRenderDispatcher dispatcher;

	/** Where this walk's own extraction lands, which is never the one the frame was drawn from. */
	private static final LevelRenderState STATE = new LevelRenderState();

	/**
	 * The block table this walk was last counted against, or -1 for none.
	 * <p>
	 * Counted rather than latched, which is the idiom the terrain's own cull line already uses and
	 * for a reason this repository has paid for: a flag of the process reports the FIRST pack of the
	 * session and says nothing for any pack loaded after it, and a pack loaded after it is exactly
	 * when the reading is worth having.
	 */
	private static int counted = -1;

	private ShadowGeometry() {
	}

	/**
	 * Submits and draws everything that moves into the map, between the two chunk groups.
	 *
	 * @param light  the light's own view projection, the matrix the terrain was just culled against
	 * @param camera where the frame was drawn from, which the light's own walk measures against as
	 *               well: the map is built around the camera and the submissions are posed relative
	 *               to it, exactly as the game poses its own
	 * @param casters which families the pack asked for
	 */
	public static void draw(Matrix4f light, Vec3 camera, ShadowCasters casters) {
		// The entity switch and not one of its own, which is the convention: what enters the map here
		// is the same geometry that door serves, read from the same tables, and a family does not take
		// a second switch without a reason. It is also what keeps the walk honest when the switch is
		// off: the door would refuse every draw, and refusing inside this walk means dropping it, so
		// the walk would cost a full extraction and a submission to draw nothing and say so sixteen
		// times.
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || !EntityDraw.wanted()
				|| !casters.anyFeature()) {
			return;
		}

		LevelRenderer renderer = minecraft.levelRenderer;
		Camera view = minecraft.gameRenderer.mainCamera();
		if (renderer == null || view == null || !ensure(minecraft)) {
			return;
		}

		// The light's frustum, prepared about the camera because that is what the whole map is built
		// around: the terrain was culled against this very matrix a few lines earlier, and an entity
		// measured against another one would be dropped out of a map its own shadow belongs in.
		Frustum frustum = new Frustum(new Matrix4f(), light);
		frustum.prepare(camera.x, camera.y, camera.z);

		STATE.reset();
		// The frame's own, borrowed rather than built: it carries where the camera stands and which
		// way it faces, and the submissions are posed against it. The level renderer takes it from
		// the same place (LevelRenderer.java:151), and building a second one here would give the
		// feature renderers a camera the frame was not drawn from.
		STATE.cameraRenderState =
				minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;

		int entities = extractEntities(minecraft, view, frustum, casters);
		int blockEntities = extractBlockEntities(minecraft, renderer, view, casters);
		if (entities == 0 && blockEntities == 0) {
			return;
		}

		submit(minecraft, camera);
		say(entities, blockEntities);

		EntityDraw.shadowFeatures(true);
		try {
			dispatcher.renderAllFeatures(storage);
		} finally {
			// Lowered whatever happened, and this is the one flag of the three that nothing else
			// would lower: the other two are closed by the game's own events, and there is no event
			// after this one. Left standing, every entity of the next frame would be drawn with the
			// shadow program into the picture.
			EntityDraw.shadowFeatures(false);
			STATE.reset();
		}
	}

	/**
	 * Builds the three pieces at the first walk, and answers whether they are there.
	 * <p>
	 * One section builder and not the processor count Iris asks for
	 * ({@code shadows/ShadowRenderer.java:180}): what this storage is used for is submissions, and
	 * the section builders of a {@code RenderBuffers} are for the chunk meshes, which this walk never
	 * touches. Sodium meshes the sections here and the map is drawn off its own lists.
	 */
	private static boolean ensure(Minecraft minecraft) {
		if (dispatcher != null) {
			return true;
		}

		try {
			buffers = new RenderBuffers(1);
			storage = new SubmitNodeStorage();
			dispatcher = new FeatureRenderDispatcher(buffers, minecraft.getModelManager(),
					minecraft.getAtlasManager(), minecraft.font,
					minecraft.gameRenderer.gameRenderState());

			return true;
		} catch (RuntimeException e) {
			buffers = null;
			storage = null;
			dispatcher = null;
			Vitrail.logger().error("Vitrail could not build the second submission the shadow map is "
					+ "filled from, so nothing that moves casts a shadow this session", e);

			return false;
		}
	}

	/**
	 * Extracts what the light can see, and answers how many.
	 * <p>
	 * <strong>The player flag is read as Iris reads it, which is not as a flag.</strong> Where the
	 * pack allows the entities, they are all extracted and the player is one of them; where it
	 * refuses them, {@code shadowPlayer} is the whole of what is left, and Iris takes that branch as
	 * an {@code else} rather than as a second test ({@code shadows/ShadowRenderer.java:548-550}).
	 * Read additively instead, the player would be kept out of every default map there is, its own
	 * directive being off by default.
	 */
	private static int extractEntities(Minecraft minecraft, Camera view, Frustum frustum,
			ShadowCasters casters) {
		EntityRenderDispatcher entities = minecraft.levelRenderer.entityRenderDispatcher();
		DeltaTracker delta = minecraft.getDeltaTracker();
		Vec3 at = view.position();

		if (casters.entities()) {
			for (Entity entity : minecraft.level.entitiesForRendering()) {
				if (visible(minecraft, entities, entity, frustum, at)) {
					STATE.entityRenderStates.add(entities.extractEntity(entity,
							delta.getGameTimeDeltaPartialTick(true)));
				}
			}
		} else if (casters.player()) {
			Player player = minecraft.player;
			if (player != null && !player.isSpectator() && !player.isInvisible()) {
				STATE.entityRenderStates.add(entities.extractEntity(player,
						delta.getGameTimeDeltaPartialTick(false)));
			}
		}

		return STATE.entityRenderStates.size();
	}

	/**
	 * The same test the game makes of its own camera, made of the light instead. A spectator is left
	 * out for the reason Iris leaves it out: it is not in the world to be lit.
	 */
	private static boolean visible(Minecraft minecraft, EntityRenderDispatcher entities, Entity entity,
			Frustum frustum, Vec3 at) {
		if (entity instanceof Player player && player.isSpectator()) {
			return false;
		}

		if (!entities.shouldRender(entity, frustum, at.x, at.y, at.z)) {
			return false;
		}

		BlockPos block = entity.blockPosition();

		return minecraft.level.isOutsideBuildHeight(block.getY())
				|| minecraft.levelRenderer.isSectionCompiledAndVisible(block);
	}

	/**
	 * Extracts the block entities of the sections the CAMERA found, which is what Iris extracts too.
	 * <p>
	 * The camera's sections and not the light's, and it is a known bound rather than an oversight:
	 * the block entities of a section are held on the section's mesh, and the only walk that has a
	 * list of sections at this moment is the one the camera made. Iris is in the same position and
	 * takes the same list ({@code shadows/ShadowRenderer.java:667}, through the level renderer's own
	 * extraction). What it costs is a chest behind the camera casting no shadow, on both engines.
	 */
	private static int extractBlockEntities(Minecraft minecraft, LevelRenderer renderer, Camera view,
			ShadowCasters casters) {
		if (!casters.blockEntities()) {
			return 0;
		}

		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (SectionRenderDispatcher.RenderSection section : renderer.visibleSections()) {
			List<BlockEntity> renderable = section.getSectionMesh().getRenderableBlockEntities();
			for (BlockEntity blockEntity : renderable) {
				// No crumbling overlay: it is the picture's business, and all it would add to a depth
				// buffer is the geometry that is already there.
				//
				// The four argument call and not the five argument one, which takes a cull frustum.
				// That overload is NOT on the classpath this compiles against, whatever a newer copy
				// of the sources may show; the frustum would have been null here anyway, the light's
				// own being no use against a bounding box measured for the camera's sections.
				BlockEntityRenderState state = renderer.blockEntityRenderDispatcher()
						.tryExtractRenderState(blockEntity, partial, null, false);
				if (state != null) {
					STATE.blockEntityRenderStates.add(state);
				}
			}
		}

		return STATE.blockEntityRenderStates.size();
	}

	/** Poses everything extracted about the camera and hands it to our own storage. */
	private static void submit(Minecraft minecraft, Vec3 camera) {
		PoseStack pose = new PoseStack();

		for (EntityRenderState entity : STATE.entityRenderStates) {
			minecraft.levelRenderer.entityRenderDispatcher().submit(entity, STATE.cameraRenderState,
					entity.x - camera.x, entity.y - camera.y, entity.z - camera.z, pose, storage);
		}

		for (BlockEntityRenderState blockEntity : STATE.blockEntityRenderStates) {
			BlockPos at = blockEntity.blockPos;
			pose.pushPose();
			pose.translate(at.getX() - camera.x, at.getY() - camera.y, at.getZ() - camera.z);
			minecraft.levelRenderer.blockEntityRenderDispatcher().submit(blockEntity, pose, storage,
					STATE.cameraRenderState);
			pose.popPose();
		}
	}

	/**
	 * Says once what the light's walk found, beside the line the terrain's own cull prints. Two
	 * counts and not one: they are gathered by two different walks and a pack can refuse either.
	 */
	private static void say(int entities, int blockEntities) {
		if (counted != BlockStateIds.generation()) {
			counted = BlockStateIds.generation();
			Vitrail.logger().info("The light's walk submitted {} entities and {} block entities into "
					+ "the shadow map, on block table {}", entities, blockEntities, counted);
		}
	}
}
