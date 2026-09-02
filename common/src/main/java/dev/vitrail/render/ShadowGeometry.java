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
import net.minecraft.world.phys.AABB;
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
 * no key for keeps the game's own shader and still writes the map (a null key of
 * {@code pipeline/IrisPipelines.java:85-134} leaves the vanilla shader standing).
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
		// The player is in the guard because the extraction below dereferences it without one
		// (Camera.java:124), and what an exception here costs is out of all proportion to the frame
		// that threw it: ShadowTerrain hands it to TerrainDraw.shadowStageFailed, which lowers the
		// stage for the whole session. One frame drawn before the player exists would end the
		// shadows for the rest of the run.
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || minecraft.player == null
				|| !EntityDraw.wanted() || !casters.anyFeature()) {
			return;
		}

		Camera view = minecraft.gameRenderer.mainCamera();
		if (minecraft.levelRenderer == null || view == null) {
			return;
		}

		// The light's frustum with the pack's reach cut out of it, prepared about the camera because
		// that is what the whole map is built around: the terrain is culled against this very
		// matrix, and an entity measured against another one would be dropped out of a map its own
		// shadow belongs in. The reach is the pack's bound on the casters that move, read once for
		// the walk, and Reached says what it is tested on.
		//
		// It is one bound and not two: the shadow distance the player set is folded into this same
		// number rather than applied beside it, because Iris builds the entity frustum out of the
		// PRODUCT of the two multipliers (shadows/ShadowRenderer.java:540) and the world's own is
		// negative whenever the player governs. PackValues.entityShadowDistance carries that
		// arithmetic and its sign.
		Frustum frustum = new Reached(light, TerrainDraw.entityShadowDistance());
		frustum.prepare(camera.x, camera.y, camera.z);

		// The light's own camera, built the way Iris builds it and NOT the frame's borrowed: a state
		// extracted fresh from the player camera (shadows/ShadowRenderer.java:390) and then
		// overwritten on the two matrices that make it a camera at all, the view rotation with the
		// light's model view (:418) and the projection with the light's ortho (:431). The rest of
		// the state stays the camera's, position and orientation included, because that is what the
		// submissions are posed relative to.
		//
		// WHAT IT FIXES TODAY IS THE ALIAS AND NOT THE MATRICES. The old line assigned the frame's
		// own object, which then outlived the frame it was borrowed from. The two matrices are
		// nobody's business at this moment: in 26.2 the only readers of viewRotationMatrix and
		// projectionMatrix are LevelRenderer and GameRenderer, and no entity, block entity or feature
		// renderer touches either. They are written to Iris's shape so that a reader added later
		// finds the light's answer rather than the camera's.
		//
		// The z sense is the one thing to know before adding such a reader: the game fills that field
		// from Projection.getMatrix, which SWAPS the two planes (Projection.java:70-71) and so hands
		// out a reversed volume, while what goes in here is the pack's shadow pair as it is published,
		// a forward ortho. A consumer that assumes the field's usual convention would read this one
		// inside out.
		//
		// The partial tick is the camera's own accessor, which is what the game passes for this same
		// extraction (GameRenderer.java:391); Iris passes its captured tick delta. It reaches only the
		// hurt and death timers of the camera entity's state (Camera.java:143-144).
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
	 * {@code @Redirect} on {@code applyFrustum}, identical in the shipped 0.9.1 and 0.9.2-alpha.4
	 * jars). Walked, it yields nothing, once, for ever, and the count printed beside it reads as a
	 * world with no chests in it.
	 * Iris is NOT in that position and never was: it asks the game for them
	 * ({@code shadows/ShadowRenderer.java:668}, through an invoker on the game's own
	 * {@code extractVisibleBlockEntities}), and Sodium cancels that method at head and answers it from
	 * {@code SodiumWorldRenderer.extractBlockEntities}, off the render lists of whatever walk last
	 * filled them. Which class carries that cancel moved between the shipped jars and the newer
	 * checkout, so it is the same behaviour rather than the same mixin. This walk asks the same object directly, and inside the
	 * light's scope what it hands back is the light's sections.
	 * <p>
	 * <strong>Asked once per stage, and nothing enforces that but the call site.</strong> The door
	 * appends and never clears, so a second call would put every block entity in the map twice. The
	 * flag is lowered here rather than at the next {@link #gather} for that reason.
	 *
	 * @param walk the platform's way to that door. Held open rather than called from here, this module
	 *             naming no Sodium type
	 */
	public static void gatherBlockEntities(BlockEntityWalk walk) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!blockEntitiesAsked || minecraft == null || minecraft.level == null) {
			return;
		}

		blockEntitiesAsked = false;
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
			// FRAME and not per caster, so a world holding two entities pays it in full, and it is
			// large enough to be the first thing seen rather than something to look for.
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
	 * also drops whatever the camera is mounted on ({@code extract/LevelExtractor.java:242}), which is
	 * about not drawing the thing the world is being looked out of; in a map drawn from the sun that
	 * body is a caster like any other, and Iris keeps it too. Its third arm ({@code :243}) drops the
	 * local player, narrowly: a NeoForge disjunct keeps it whenever it is the player and not
	 * spectating, so all that arm still refuses is a spectating one - which the spectator test below
	 * refuses here anyway, by a different road. What is added is that test, and it is Iris's
	 * ({@code shadows/ShadowRenderer.java:701}): a spectator is not in the world to be lit.
	 * <p>
	 * <strong>The pack's reach is measured on the caster's culling box, as Iris measures it, and the
	 * shape beside it is not yet Iris's.</strong> Where the pack asked its movers to stop short,
	 * Iris rebuilds a whole second shadow frustum for them at that distance
	 * ({@code shadows/ShadowRenderer.java:536-541}): the camera's volume swept along the light with
	 * an axis-aligned cube about the camera cut out of it, and the game's own {@code shouldRender}
	 * hands both the caster's culling box inflated by half a block ({@code :703} there,
	 * {@code entity/EntityRenderer.java:73-78}). {@link Reached} is that cube asked of that box.
	 * What stands beside it is the light's own frustum rather than the sweep, and that is the open
	 * gap: a caster inside the cube and the light's volume but outside the sweep is kept here and
	 * dropped there, which costs a draw and never a pixel, the sweep being built so that nothing
	 * outside it can shadow what the camera sees. The other way round is empty: Iris's entity
	 * frustum never asks the light's volume, so a caster outside it is kept there and dropped here,
	 * but outside the light's volume is outside the map, and what Iris keeps of it draws nothing.
	 * Nothing makes Iris's shape impossible here.
	 */
	private static boolean visible(Minecraft minecraft, EntityRenderDispatcher entities, Entity entity,
			Frustum frustum, Vec3 at) {
		if (entity instanceof Player spectator && spectator.isSpectator()) {
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
	 * <p>
	 * <strong>The line says which frame it is the reading of, and that word carries weight.</strong>
	 * It is said once per block table, so what it reports is one frame's sample held out for the
	 * whole load, and either count may legitimately be nought on the frame that happens to speak. A
	 * nought said as though it were the session's reading is exactly what this walk printed while no
	 * block entity reached the map at all, and nothing let the reader tell the two apart. Suppressing
	 * the line until a frame finds something is worse: a world that never puts a chest in the light's
	 * box would then lose the entity count too.
	 */
	private static void say(int entities, int blockEntities) {
		if (counted != BlockStateIds.generation()) {
			counted = BlockStateIds.generation();
			Vitrail.logger().info("On this frame the light's walk submitted {} entities and {} block "
					+ "entities into the shadow map, on block table {}", entities, blockEntities,
					counted);
		}
	}

	/**
	 * The light's frustum with the pack's reach cut out of it, which is what a caster that moves is
	 * measured against.
	 * <p>
	 * <strong>The reach is a cube about the camera, asked of the caster's culling box and not of its
	 * position, and the box is what decides.</strong> Iris hangs a {@code BoxCuller} off its entity
	 * frustum and asks it of the box the game's {@code shouldRender} hands over, the caster's culling
	 * box inflated by half a block ({@code shadows/frustum/BoxCuller.java}, {@code isCulled(AABB)},
	 * reached from {@code shadows/ShadowRenderer.java:703} through
	 * {@code entity/EntityRenderer.java:73-78}). A caster is kept as long as any of that box reaches
	 * back inside the reach on every axis, so a wide one standing past the distance still lays its
	 * shadow where a small one would not. Asked of the position instead, as this walk once did, the
	 * difference is the caster's half width: under a block for most mobs, a few for the largest, and
	 * seven for the test caster this was measured with, a cow scaled sixteen times, which cast under
	 * Iris four blocks past the reach and cast nothing here.
	 * <p>
	 * <strong>Asked inside the frustum, so what the game never asks the frustum about escapes the
	 * reach, exactly as it does under Iris.</strong> A renderer that answers
	 * {@code affectedByCulling} false, a leash holder's box, the vehicle carrying the player: the
	 * game settles those before or beside {@code isVisible} ({@code entity/EntityRenderer.java:69,
	 * 82-88}), and the old position test, standing in front of {@code shouldRender}, bounded them
	 * all. What it also costs is the game's own preamble, a distance test and an inflated box built
	 * for every caster within the game's render distance before the cube can refuse it, where three
	 * subtractions used to refuse first. That is Iris's cost too.
	 * <p>
	 * <strong>Negative means no reach, and not "not positive"</strong>, because zero is a bound like
	 * any other: a pack that writes {@code entityShadowDistanceMul 0}, or a player who drags the
	 * shadow distance to the bottom, is asking for no moving caster in the map at all, and Iris hands
	 * both of them a box culler built at zero rather than no culler
	 * ({@code shadows/ShadowRenderer.java:333-354}, and the safe zone's own distance culler at
	 * {@code :370} likewise).
	 * <p>
	 * The cube is asked first and the planes after, which is Iris's order under both of its shapes
	 * ({@code AdvancedShadowCullingFrustum.isVisible(AABB)}, and the distance culler of
	 * {@code SafeZoneCullingFrustum.isVisible(AABB)}), so a box the cube refuses never reaches the
	 * planes. The planes are the light's own and not the sweep Iris tests, and {@link #visible}
	 * carries what that costs.
	 */
	private static final class Reached extends Frustum {

		/** Half the side of the cube, in blocks, or negative where nothing bounds the casters. */
		private final float reach;

		Reached(Matrix4f light, float reach) {
			super(new Matrix4f(), light);
			this.reach = reach;
		}

		@Override
		public boolean isVisible(AABB box) {
			return !culled(box) && super.isVisible(box);
		}

		/**
		 * Iris's {@code BoxCuller.isCulled}: wholly past the reach on any one axis is out. Centred
		 * on what {@code prepare} was handed, read back off the frustum so that the cube and the
		 * planes can never be centred apart.
		 */
		private boolean culled(AABB box) {
			if (this.reach < 0.0F) {
				return false;
			}

			double x = getCamX();
			double y = getCamY();
			double z = getCamZ();

			return box.maxX < x - this.reach || box.minX > x + this.reach
					|| box.maxY < y - this.reach || box.minY > y + this.reach
					|| box.maxZ < z - this.reach || box.minZ > z + this.reach;
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
