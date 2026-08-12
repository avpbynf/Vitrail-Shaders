package dev.vitrail.render;

import dev.vitrail.pack.source.ShadowCasters;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

/**
 * Walks the world a second time, for the light, and submits everything that moves into the shadow
 * map.
 * <p>
 * <strong>A second submission and not a second reading of the first one, and that is forced rather
 * than chosen.</strong> The game clears the two lists this would have read the moment it has
 * submitted them: {@code LevelRenderer.submitFeatures} calls
 * {@code levelRenderState.entityRenderStates.clear()} on the line after {@code submitEntities} and
 * does the same for the block entities ({@code LevelRenderer.java:284-287}). The shadow stage stands
 * at the very end of the frame, so by the time it runs both are empty. Iris does not read the
 * frame's lists either: it keeps a level render state, a storage and a dispatcher of its own
 * ({@code shadows/ShadowRenderer.java:180-182}) and submits into them ({@code :659} and
 * {@code :684}). The reason it does holds here too: the camera's lists were culled against the
 * CAMERA's frustum, and what has to be in a shadow map is what the LIGHT can see, which is mostly
 * what the camera cannot.
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
	 * What the pack asked of the block entities on the walk {@link #gather} last made: whether they
	 * are wanted at all, and whether what is wanted is the emitters alone. The two halves of this
	 * extraction run at different moments of the stage and only the first one is handed the pack's
	 * answer, so it leaves it here for the second.
	 */
	private static boolean blockEntitiesAsked;
	private static boolean emittersOnly;

	/**
	 * How far a caster that moves may stand from the camera and still reach the map, or a value that
	 * is not positive where the pack asks for no bound beyond the light's own.
	 */
	private static float reach = -1.0F;


	private ShadowGeometry() {
	}

	/**
	 * Works out which entities the light can see, before the light's own walk of the sections runs.
	 * <p>
	 * <strong>For the entities the order settles nothing, and it is worth saying why.</strong> A
	 * caster is kept or dropped by {@code LevelRenderer.isSectionCompiledAndVisible}, which Sodium
	 * replaces outright ({@code mixin/core/render/world/LevelRendererMixin}) and answers off its own
	 * section state; the game's own answer, a fade since the section's own upload
	 * ({@code chunk/SectionRenderDispatcher.java:223-225}), is not the one that runs here. Neither
	 * that state nor the frustum this builds is moved by the walk below, so asked before or after it
	 * the test answers the same thing. Iris carries the same test
	 * ({@code shadows/ShadowRenderer.java:703-705}). This sits here because it is the plainer place
	 * to ask, and for no other reason.
	 * <p>
	 * <strong>For the block entities the order decides everything</strong>, and they are taken in
	 * {@link #gatherBlockEntities} instead, which says why.
	 *
	 * @param light   the light's own view projection, the matrix the terrain is culled against
	 * @param camera  where the frame was drawn from, which the map is built around
	 * @param casters which families the pack asked for
	 */
	public static void gather(Matrix4f light, Vec3 camera, ShadowCasters casters) {
		STATE.reset();
		gathered = 0;
		gatheredBlocks = 0;
		blockEntitiesAsked = false;
		emittersOnly = false;

		// The entity switch and not one of its own, which is the convention: what enters the map here
		// is the same geometry that door serves, read from the same tables, and a family does not take
		// a second switch without a reason. It is also what keeps the walk honest when the switch is
		// off: the door would refuse every draw, and refusing inside this walk means dropping it, so
		// the walk would cost a full extraction and a submission to draw nothing and say so once per
		// row of the shadow table.
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || !EntityDraw.wanted()
				|| !casters.anyFeature()) {
			return;
		}

		Camera view = minecraft.gameRenderer.mainCamera();
		if (minecraft.levelRenderer == null || view == null) {
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

		// The light's own camera, built the way Iris builds it and NOT the frame's borrowed: a state
		// extracted fresh from the player camera (shadows/ShadowRenderer.java:390) and then
		// overwritten on the two matrices that make it a camera at all, the view rotation with the
		// light's model view (:418) and the projection with the light's ortho (:431). The rest of
		// the state stays the camera's, position and orientation included, because that is what the
		// submissions are posed relative to. Aliasing the frame's object instead handed the feature
		// renderers the CAMERA's view rotation and projection: anything oriented from those faces
		// the player inside the map and swings as the player turns, and the alias outlived the frame.
		view.extractRenderState(STATE.cameraRenderState,
				view.getCameraEntityPartialTicks(minecraft.getDeltaTracker()));
		if (!TerrainDraw.drawnShadowPair(STATE.cameraRenderState.viewRotationMatrix,
				STATE.cameraRenderState.projectionMatrix)) {
			return;
		}

		gathered = extractEntities(minecraft, view, frustum, casters);

		// The block entities are not taken here, and it is not an order this walk chose: the list
		// they are read off does not exist yet at this point of the stage. What the pack asked is
		// left for the half that runs once it does.
		blockEntitiesAsked = casters.anyBlockEntity();
		emittersOnly = casters.emittersOnly();
	}

	/**
	 * Adds the block entities of the sections the LIGHT walked, once its own render lists exist.
	 * <p>
	 * <strong>Apart from {@link #gather} because the only list that answers is made after it.</strong>
	 * The block entities of a section are held on the section's mesh, and what says which sections to
	 * ask is a walk's render lists; the light's are filled by the cull that runs after this walk was
	 * worked out, so asked at gather time the question has the camera's answer or none.
	 * <p>
	 * <strong>And the game's own list is empty for the whole session here.</strong>
	 * {@code LevelRenderer.visibleSections()} is filled by {@code LevelExtractor.applyFrustum}, which
	 * Sodium redirects to an empty method ({@code mixin/core/render/world/LevelExtractorMixin}, a
	 * {@code @Redirect} on {@code applyFrustum}, read at the shipped 0.9.1 jar). Walked, it yields
	 * nothing, once, for ever, and the count printed beside it reads as a world with no chests in it.
	 * Iris is NOT in that position and never was: it calls the game's
	 * {@code extractVisibleBlockEntities} ({@code shadows/ShadowRenderer.java:668}), which the same
	 * Sodium mixin cancels at head and serves from {@code SodiumWorldRenderer.extractBlockEntities},
	 * off the render lists of whatever walk last filled them. That is the door taken here, and inside
	 * the light's scope it is the light's sections it hands back.
	 *
	 * @param walk the platform's way to that door. Held open rather than called from here, this module
	 *             naming no Sodium type
	 */
	public static void gatherBlockEntities(BlockEntityWalk walk) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!blockEntitiesAsked || minecraft == null || minecraft.level == null) {
			return;
		}

		walk.into(STATE, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		if (emittersOnly) {
			keepEmitters(minecraft.level);
		}

		gatheredBlocks = STATE.blockEntityRenderStates.size();
	}

	/**
	 * Cuts what was just extracted down to the block entities that give off light, which is the
	 * narrower of the pack's two words standing alone.
	 * <p>
	 * Iris cuts the same list in the same place and by the same test, after the extraction rather
	 * than inside it ({@code shadows/ShadowRenderer.java:670-677}): there is one door into the block
	 * entities and it does not take a filter. <strong>The emission is read off the level and not off
	 * the render state</strong>, which is where Iris reads it: 26.2 made
	 * {@code BlockEntityRenderState.blockState} private with no accessor
	 * ({@code blockentity/state/BlockEntityRenderState.java:19}). Both answers are the same block's,
	 * the extraction that filled the list having run microseconds earlier in this same frame.
	 */
	private static void keepEmitters(Level level) {
		STATE.blockEntityRenderStates
				.removeIf(state -> level.getBlockState(state.blockPos).getLightEmission() == 0);
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
			// THE FRAME OF THIS FAMILY'S OWN BUFFERS ENDS HERE whether anything was drawn or not,
			// and it is not tidiness: RenderBuffers.endFrame is the ONLY path that recycles what
			// StagedVertexBuffer took, so without it every frame's acquire misses the pool and falls
			// through to a fresh device allocation that nothing ever hands back. The cost is per
			// FRAME and not per caster, so it is paid in full by a world holding two entities, and
			// it was measured on this machine as an order of magnitude off the frame rate.
			// HandDraw.drawTranslucent ends its own on both paths for the same reason.
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

			// IRIS'S OWN CONSTANT HERE, and not the entity's own tick. Its player
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
	 * The game's own visibility test, made of the light instead of the camera: {@code shouldRender}
	 * against a frustum, then the section behind it ({@code extract/LevelExtractor.java:259-269}).
	 * The passenger arm is the game's and Iris's alike: what carries the player is kept even where
	 * the frustum refuses it, or the boat under a player casting a shadow would cast none.
	 * <p>
	 * <strong>Two of the game's arms are deliberately not here and one is added.</strong> Its walk
	 * also drops whatever the camera is riding and the local player when the camera is not on it
	 * ({@code extract/LevelExtractor.java:241-243}), both of which are about not drawing the thing
	 * the world is being looked out of; in a map drawn from the sun the player is a caster like any
	 * other, and Iris keeps it too. What is added is the spectator, left out for the reason Iris
	 * leaves it out: it is not in the world to be lit.
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

	/**
	 * The way to the block entities of the sections a walk has left in its render lists, which only
	 * the platform half knows how to open.
	 */
	@FunctionalInterface
	public interface BlockEntityWalk {

		/**
		 * Extracts them into the state the light's own submission is built from.
		 *
		 * @param state       where the render states are added, beside the entities already in it
		 * @param partialTick how far the frame stands between two ticks, which is what a block entity
		 *                    animates on
		 */
		void into(LevelRenderState state, float partialTick);
	}
}
