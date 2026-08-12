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
import net.minecraft.world.TickRateManager;
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

	/** Whether building them has already failed, which is settled for the session and not retried. */
	private static boolean broken;

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

	/** What the last {@link #gather} found, held across the light's own walk of the sections. */
	private static int gathered;
	private static int gatheredBlocks;

	/**
	 * How far a caster that moves may stand from the camera and still reach the map, or a value that
	 * is not positive where the pack asks for no bound beyond the light's own.
	 */
	private static float reach = -1.0F;


	private ShadowGeometry() {
	}

	/**
	 * Works out what the light can see, before the light's own walk of the sections runs.
	 * <p>
	 * <strong>The order settles nothing, and the sentence that used to stand here was wrong.</strong>
	 * A caster is kept or dropped by {@code LevelRenderer.isSectionCompiledAndVisible}
	 * ({@code LevelRenderer.java:975-984}), which ends in
	 * {@code getVisibility(Util.getMillis()) >= 0.3F}. That reads
	 * {@code (now - uploadedTime) / fadeDuration} off the section itself
	 * ({@code chunk/SectionRenderDispatcher.java:223-225}): a fade since the section's own upload, in
	 * which no viewport, no list and no frustum appears, and which {@code finalizeRenderLists} never
	 * touches. Asked before or after the light's walk it answers the same thing to within the
	 * microseconds between the two, and the shadows of mobs went on blinking at a walk when this
	 * moved. It stays here because it is the plainer place to ask, not because it fixes anything.
	 * <p>
	 * Iris carries the same test ({@code shadows/ShadowRenderer.java:703-705}). The blink that sent
	 * three readings through this method was not in it at all: it was the pack's frame opened a
	 * second time by the door this walk draws through, which made every reprojected value of the
	 * next frame its current one. Nothing here needed changing, and this order was kept only because
	 * it is the plainer place to ask.
	 *
	 * @param light   the light's own view projection, the matrix the terrain is culled against
	 * @param camera  where the frame was drawn from, which the map is built around
	 * @param casters which families the pack asked for
	 */
	public static void gather(Matrix4f light, Vec3 camera, ShadowCasters casters) {
		STATE.reset();
		gathered = 0;
		gatheredBlocks = 0;

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
		if (renderer == null || view == null) {
			return;
		}

		// The light's frustum, prepared about the camera because that is what the whole map is built
		// around: the terrain is culled against this very matrix, and an entity measured against
		// another one would be dropped out of a map its own shadow belongs in.
		Frustum frustum = new Frustum(new Matrix4f(), light);
		frustum.prepare(camera.x, camera.y, camera.z);

		// The pack's own bound on the casters that move, read once for the walk. Iris measures them
		// against a SECOND, shorter frustum where the pack asked for one, built at the shadow
		// distance times entityShadowDistanceMul (shadows/ShadowRenderer.java:536-541); here the
		// shape stays the light's and only the reach is cut, which is what the multiplier means.
		reach = TerrainDraw.entityShadowDistance();

		// The frame's own, borrowed rather than built: it carries where the camera stands and which
		// way it faces, and the submissions are posed against it. The level renderer takes it from
		// the same place (LevelRenderer.java:151), and building a second one here would give the
		// feature renderers a camera the frame was not drawn from.
		STATE.cameraRenderState =
				minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;

		gathered = extractEntities(minecraft, view, frustum, casters);
		gatheredBlocks = extractBlockEntities(minecraft, renderer, casters);
	}

	/**
	 * Submits and draws what {@link #gather} found, between the two chunk groups of the map.
	 *
	 * @param camera where the frame was drawn from. The submissions are posed relative to it,
	 *               exactly as the game poses its own
	 */
	public static void draw(Vec3 camera) {
		try {
			walk(camera);
		} finally {
			// <strong>The frame of this family's own buffers ends here whether anything was drawn or
			// not</strong>, and it is not tidiness: {@code RenderBuffers.endFrame} is the ONLY path
			// that recycles what {@code StagedVertexBuffer} took, so without it every frame's
			// acquire misses the pool and falls through to a fresh device allocation that nothing
			// ever hands back. Measured before this line existed: 144 frames a second became ten to
			// fifteen, on two entities, because the cost is per FRAME and not per caster.
			// {@link HandDraw#drawTranslucent} ends its own on both paths for the same reason.
			if (buffers != null) {
				buffers.endFrame();
			}
		}
	}

	/** Hands back the buffers and the dispatcher, at the end of the client and nowhere else. */
	public static void close() {
		FeatureRenderDispatcher held = dispatcher;
		RenderBuffers owned = buffers;
		dispatcher = null;
		storage = null;
		buffers = null;
		if (held != null) {
			held.close();
		}

		if (owned != null) {
			owned.close();
		}
	}

	private static void walk(Vec3 camera) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || (gathered == 0 && gatheredBlocks == 0) || !ensure(minecraft)) {
			return;
		}

		submit(minecraft, camera);
		say(gathered, gatheredBlocks);

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
	 * No section builders at all, which is what {@link HandDraw} asks for and for the same reason:
	 * what this storage is used for is submissions, and the section builders of a
	 * {@code RenderBuffers} are for the chunk meshes, which this walk never touches. Sodium meshes
	 * the sections here and the map is drawn off its own lists.
	 * <p>
	 * <strong>A failure is settled for the session rather than retried.</strong> The buffers are
	 * built before the dispatcher and are a device allocation, so the one that succeeded goes back
	 * before the fields are dropped; and the latch is what makes the message true, a walk that
	 * retried would allocate and log once a frame for the rest of the run.
	 */
	private static boolean ensure(Minecraft minecraft) {
		if (dispatcher != null) {
			return true;
		}

		if (broken) {
			return false;
		}

		RenderBuffers owned = null;
		try {
			owned = new RenderBuffers(0);
			storage = new SubmitNodeStorage();
			dispatcher = new FeatureRenderDispatcher(owned, minecraft.getModelManager(),
					minecraft.getAtlasManager(), minecraft.font,
					minecraft.gameRenderer.gameRenderState());
			buffers = owned;

			return true;
		} catch (RuntimeException e) {
			if (owned != null) {
				owned.close();
			}

			buffers = null;
			storage = null;
			dispatcher = null;
			broken = true;
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
		TickRateManager ticks = minecraft.level.tickRateManager();
		DeltaTracker delta = minecraft.getDeltaTracker();
		Vec3 at = view.position();

		if (casters.entities()) {
			for (Entity entity : minecraft.level.entitiesForRendering()) {
				if (visible(minecraft, entities, entity, frustum, at)) {
					STATE.entityRenderStates.add(extract(entities, delta, ticks, entity));
				}
			}
		} else if (casters.player()) {
			Player player = minecraft.player;
			if (player == null) {
				return STATE.entityRenderStates.size();
			}

			// <strong>Iris's own constant here, and not the entity's own tick.</strong> Its player
			// branch takes one getGameTimeDeltaPartialTick(false) for the pair
			// (shadows/ShadowRenderer.java:553) where its entity branch asks per caster (:712). The
			// split is Iris's and it is not ours to tidy: made uniform, a frozen world poses the
			// player one way here and another way there, and the packs are written against there.
			float partial = delta.getGameTimeDeltaPartialTick(false);
			if (!player.isSpectator() && !player.isInvisible()) {
				STATE.entityRenderStates.add(entities.extractEntity(player, partial));
			}

			// What carries the player, extracted beside it as Iris extracts it (:557-560) and
			// outside the test above, as there too: a rider whose own shadow falls on a horse
			// casting none is worse than neither casting one.
			Entity vehicle = player.getVehicle();
			if (vehicle != null) {
				STATE.entityRenderStates.add(entities.extractEntity(vehicle, partial));
			}
		}

		return STATE.entityRenderStates.size();
	}

	/**
	 * Extracts one caster the way the game and Iris both extract it, which is two things this walk
	 * was getting wrong.
	 * <p>
	 * <strong>The old position is settled first, on the frame an entity is born.</strong> A state is
	 * built by interpolating {@code xOld} towards where the entity stands, and one that has never
	 * ticked carries an old position nothing ever set, so its shadow is laid somewhere else for a
	 * frame. The game does this ({@code extract/LevelExtractor.java:244-248}) and so does Iris
	 * ({@code shadows/ShadowRenderer.java:706-710}).
	 * <p>
	 * <strong>And the partial tick is the entity's own.</strong> Both ask
	 * {@code getGameTimeDeltaPartialTick(!isEntityFrozen(entity))}, per caster, because a frozen
	 * entity stands still and one interpolated as though it ran drags its shadow off it. This walk
	 * passed {@code true} for the lot, which is that same value for everything the tick rate manager
	 * has not frozen and the wrong one for everything it has.
	 * <p>
	 * <strong>For the walk over the entities only</strong>, which is where both of them ask it. The
	 * player branch of Iris takes a constant instead ({@code shadows/ShadowRenderer.java:553}) and
	 * carries no old position guard, so that branch is written out there rather than sent here: a
	 * split that exists in the reference is not a tidiness to remove.
	 */
	private static EntityRenderState extract(EntityRenderDispatcher entities, DeltaTracker delta,
			TickRateManager ticks, Entity entity) {
		if (entity.tickCount == 0) {
			entity.xOld = entity.getX();
			entity.yOld = entity.getY();
			entity.zOld = entity.getZ();
		}

		return entities.extractEntity(entity,
				delta.getGameTimeDeltaPartialTick(!ticks.isEntityFrozen(entity)));
	}

	/**
	 * The same test the game makes of its own camera, made of the light instead: {@code shouldRender}
	 * against a frustum, then the section behind it ({@code extract/LevelExtractor.java:259-269}).
	 * The passenger arm is the game's and Iris's alike: what carries the player is kept even where
	 * the frustum refuses it, or the boat under a player casting a shadow would cast none. A
	 * spectator is left out for the reason Iris leaves it out: it is not in the world to be lit.
	 * <p>
	 * <strong>The pack's own reach is a second bound and not a second shape.</strong> Iris builds a
	 * whole shadow frustum for the casters that move where the pack asked for one, at the shadow
	 * distance times {@code entityShadowDistanceMul} ({@code shadows/ShadowRenderer.java:536-541}),
	 * and keeps the terrain's where it did not. Here the light's frustum is kept in both cases and
	 * the reach alone is cut, about the camera, which is what a multiplier of a distance means. What
	 * it costs against Iris is a caster inside the box but outside its frustum: kept there and here
	 * alike, since both bounds are the light's own beyond this one.
	 */
	private static boolean visible(Minecraft minecraft, EntityRenderDispatcher entities, Entity entity,
			Frustum frustum, Vec3 at) {
		if (entity instanceof Player spectator && spectator.isSpectator()) {
			return false;
		}

		if (reach > 0.0F && (Math.abs(entity.getX() - at.x) > reach
				|| Math.abs(entity.getY() - at.y) > reach
				|| Math.abs(entity.getZ() - at.z) > reach)) {
			return false;
		}

		Player player = minecraft.player;
		if (!entities.shouldRender(entity, frustum, at.x, at.y, at.z)
				&& (player == null || !entity.hasIndirectPassenger(player))) {
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
	private static int extractBlockEntities(Minecraft minecraft, LevelRenderer renderer,
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
