package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;

/**
 * Moves the player's own hand out of the game's late call and draws it inside the level, twice, so
 * that the pack's {@code gbuffers_hand} and {@code gbuffers_hand_water} can serve it.
 * <p>
 * <strong>What has to move is the hand and not the frame boundary.</strong> The game draws it in
 * {@code GameRenderer.renderLevel}, after {@code levelRenderer.render} has returned and therefore
 * after {@code AfterLevel}, which is where this engine runs the second half of a pack's chain: left
 * there, the hand is painted by the game's own shader on top of a finished, tone mapped image, and
 * no gbuffer of the pack ever hears about it. Iris answers by neutralising that late call
 * ({@code mixin/MixinGameRenderer.java:75}, a redirect on the submission) and calling the hand back
 * twice from inside the level, the solid half at {@code mixin/MixinLevelRenderer.java:280} and the
 * translucent one at {@code :170}. This class is that same move on the events this engine already
 * hangs off.
 * <p>
 * <strong>The two halves are two programs and the split is by PASS, not by pipeline.</strong>
 * Whatever is drawn while the solid half runs asks the pack for {@code gbuffers_hand} and whatever
 * is drawn while the translucent half runs asks for {@code gbuffers_hand_water}, which is exactly
 * how Iris keys it: every row of its table that can carry a hand answers
 * {@code isRenderingSolid() ? HAND_* : HAND_WATER_*} ({@code pipeline/IrisPipelines.java:191-218}),
 * and it is the pass and never the render type that decides. What separates the two passes is which
 * items go into them: a hand holding a translucent block model is drawn in the second and everything
 * else in the first, which {@link #skip} is.
 * <p>
 * <strong>It needs a dispatcher of its own, and that is an obstacle rather than a taste.</strong>
 * {@code FeatureRenderDispatcher} holds ONE {@code PreparedFrame} and
 * {@code PreparedFrame.begin} throws {@code "PreparedFrame already in use"} on a second entry; the
 * level's own frame is open from {@code LevelRenderer.render}'s {@code prepareFrame} until its
 * {@code featureFrame.close()}, which is the whole of the window both halves are drawn in. So the
 * game's dispatcher cannot be re-entered from inside the level and this builds a second one, as Iris
 * does ({@code pathways/HandRenderer.java:44-55}). Its buffers are its own for the same reason one
 * step down: {@code StagedVertexBuffer.upload} throws {@code "Already uploaded"} while the level's
 * upload is still standing.
 * <p>
 * <strong>The depth is squeezed to an eighth and the world's is left alone.</strong> The game clears
 * the whole depth buffer before it draws the hand, which it can afford because nothing of its own
 * reads that depth afterwards; here the chain has already read it and the composites will read it
 * again, so clearing is not available. Iris's answer is the one reproduced: scale the clip depth by
 * {@code 0.125} so the hand occupies a band of the depth range that only geometry closer than
 * about a tenth of a block reaches, which is what keeps an arm from being cut in half by the wall
 * it is held against. Packs know the number as {@code MC_HAND_DEPTH} and divide it back out; Bliss
 * and Reverie both do.
 * <p>
 * <strong>Scaling the clip depth is Iris's formula and NOT ours, because this backend rasterises
 * the other way up.</strong> Iris runs against an OpenGL clip volume, z from minus one to one with
 * the near plane at minus one, where multiplying clip z by an eighth lands the hand in the window
 * depths between {@code 0.4375} and {@code 0.5625}. Minecraft 26.2 rasterises with a reversed z
 * over zero to one, near at ONE, which {@link dev.vitrail.uniform.ClipSpace} sets out: the same
 * multiplication there divides the hand's depth by eight and lands it where geometry EIGHT TIMES
 * FARTHER sits, so the world draws over it. Measured in game on 12 August 2026, and it looks
 * exactly like what it is: the item shows against the sky and is cut away by any ground or water
 * within a few blocks.
 * <p>
 * The translation between the two conventions is {@code w_reversed = 1 - w_opengl}, and carrying
 * Iris's formula through it gives {@code z' = 0.125 z + 0.4375 w} rather than {@code z' = 0.125 z}.
 * That is not an approximation of his answer, it is his answer written in this volume: the band it
 * rasterises into is the same {@code 0.4375} to {@code 0.5625}, and the matrix a pack is handed
 * comes out identical once {@code ClipSpace} has converted it back to the OpenGL form, the term in
 * {@code w} included.
 */
public final class HandDraw {

	/**
	 * How far the clip depth is squeezed, which is Iris's {@code HandRenderer.DEPTH} and the value
	 * {@code EngineDefines} already publishes as {@code MC_HAND_DEPTH}. The two are one number and
	 * a pack undoing the squeeze reads the macro, so they may not drift apart.
	 */
	private static final float DEPTH = 0.125F;

	/** The near plane the game builds its own head-up projection with. */
	private static final float NEAR = 0.05F;

	/** Off unless {@code options.txt} asks otherwise, and read again at every load. */
	private static volatile boolean wanted;

	/**
	 * Which half is being drawn, or null between them. Written and read on the render thread alone,
	 * inside one method's try and finally, so it cannot outlive the pass that raised it.
	 */
	private static Half half;

	/** Built at the first hand of the session, since none of what it needs exists before then. */
	private static HandDraw instance;

	/** Whether building it threw, in which case the game keeps its own hand for the session. */
	private static boolean failed;

	/** The two passes, which are two programs of the pack and two moments of the frame. */
	private enum Half {
		SOLID,
		TRANSLUCENT
	}

	/**
	 * The second dispatcher and everything under it. One section buffer pack comes with the
	 * buffers and is never used: {@code FeatureRenderDispatcher} reads nothing of them but the
	 * staged vertex buffer, and {@code SectionBufferBuilderPool.allocate} floors its count at one,
	 * so nought is the least that can be asked for. Iris asks for one per processor.
	 */
	private final RenderBuffers buffers;
	private final SubmitNodeStorage submits;
	private final FeatureRenderDispatcher dispatcher;

	/** Where the matrix below is handed to the device, kept because building one costs a buffer. */
	private final ProjectionMatrixBuffer bound;

	/** The head-up perspective, rebuilt only when the window, the field of view or the far plane
	 * moves: {@code Projection} compares before it dirties. */
	private final Projection perspective = new Projection();

	/**
	 * The volume the hand is drawn in, WITHOUT the walk bob, which is the matrix the pack is handed
	 * as {@code gl_ProjectionMatrix}.
	 * <p>
	 * <strong>Without, and that is not the same as Iris's matrix.</strong> Iris multiplies the bob
	 * into the projection it binds and leaves its model view at the identity; this engine publishes
	 * the bob in the model view for every family, {@link CameraBob} saying why, so the hand puts it
	 * in the same place as its neighbours.
	 * <p>
	 * <strong>The product a pack computes lands on the device's matrix all the same, and the route
	 * is not the one it looks like.</strong> A hand program does not read this engine's pass model
	 * view at all: {@code gbuffers_hand} is one of the families
	 * {@code LegacyGlsl.readsDrawModelView} answers for, so its {@code gl_ModelViewMatrix} is
	 * rewritten to {@code of_CameraBob * of_GameModelView}, the second factor being what the game
	 * wrote for that draw. That member is the identity here, {@code RenderType.writeDynamicTransforms}
	 * copying the model view stack this class has just set to the identity, so the product comes to
	 * {@code volume * bob} - which is {@link #drawn}, exactly. Measured out of game over eight states
	 * of the player, and exact in every one of them.
	 * <p>
	 * That holds because {@link #bob()} hands the pack the pose this class draws with: the frame's
	 * bob would carry the nausea roll and the portal scale besides, and the arm is drawn without
	 * them. The two read {@link CameraBob#pose()} at two moments rather than sharing one copy, and
	 * what makes them the same matrix is that nothing writes it in between: it is written once per
	 * level render, from the mixin on the game's own multiplication, and both moments fall after it.
	 * It holds on a frame this engine could not split as well, which no other family manages:
	 * {@link CameraBob#pose()} is answered whatever the split says, so the hand keeps a product that
	 * multiplies back while the rest of the frame falls back on the drawn projection.
	 */
	private final Matrix4f volume = new Matrix4f();

	/** The same with the bob multiplied in, which is what the device is given: the model view stack
	 * is the identity while the hand draws, so nothing else would carry it. */
	private final Matrix4f drawn = new Matrix4f();

	/** Where {@link #perspective} writes, so that the two above are only ever whole matrices. */
	private final Matrix4f head = new Matrix4f();

	private HandDraw(Minecraft minecraft) {
		this.buffers = new RenderBuffers(0);
		this.submits = new SubmitNodeStorage();
		this.dispatcher = new FeatureRenderDispatcher(this.buffers, minecraft.getModelManager(),
				minecraft.getAtlasManager(), minecraft.font, minecraft.gameRenderer.gameRenderState());
		this.bound = new ProjectionMatrixBuffer("Vitrail hand");
	}

	/** Whether a pack's own hand programs take over the game's, from the loaded options. */
	static void wanted(boolean asked) {
		wanted = asked;
	}

	/**
	 * Whether this family is drawing, for the line of the log that names what the scene seed still
	 * carries across.
	 */
	static boolean wanted() {
		return wanted;
	}

	/**
	 * Whether the game's own late call is taken over this frame, which is the one question the
	 * mixin on {@code GameRenderer} asks and the one both halves below ask first.
	 * <p>
	 * The pack is half of it and not a refinement. With no pack loaded, or with one that could not
	 * be drawn, moving the hand up the frame buys nothing and changes where it lands relative to
	 * everything the game does between the two points; the game's own answer is then the better one.
	 * The entity door is where the loaded chain is reached from, this family being served through
	 * it.
	 */
	public static boolean diverted() {
		return wanted && !failed && PackChain.entities() != null;
	}

	/**
	 * Whether a hand is really drawn by this engine on THIS frame, which is {@link #diverted} and
	 * the game's own tests together.
	 * <p>
	 * The two are different questions and what separates them is a frame rather than a load.
	 * {@link #diverted} says the family is this engine's to draw at all; this says one is drawn.
	 * Third person, a hidden interface, a sleeping player, a spectator and a panorama capture all
	 * answer yes to the first and no to this, so anything paid per frame FOR the hand has to ask
	 * this one and not that one: {@link #shows} carries which tests those are and whose they are.
	 */
	public static boolean draws() {
		Minecraft minecraft = Minecraft.getInstance();
		GameRenderState state = minecraft.gameRenderer.gameRenderState();

		return diverted() && minecraft.player != null
				&& shows(minecraft, state, state.levelRenderState.cameraRenderState);
	}

	/** Whether the hand is being drawn at this instant, which is what tells its draws from a mob's. */
	static boolean drawing() {
		return half != null;
	}

	/** Which of the two halves, once {@link #drawing} has answered yes. */
	static boolean drawingSolid() {
		return half == Half.SOLID;
	}

	/**
	 * The volume the hand is being drawn in, or null when it is not being drawn, which is what a
	 * hand program is handed as its own projection.
	 */
	static Matrix4fc volume() {
		return half == null || instance == null ? null : instance.volume;
	}

	/**
	 * The bob the volume above was built against, or null when the hand is not being drawn, which is
	 * the left factor a hand program is handed as {@code of_CameraBob}.
	 * <p>
	 * <strong>The walk bob and the damage tilt, and not the frame's four.</strong> A hand program
	 * reads its model view out of the game's per draw block and multiplies this by it, so this factor
	 * and {@link #drawn} have to be built from the same pose or the pack's
	 * {@code gl_ProjectionMatrix * gl_ModelViewMatrix} stops being the matrix the device was given.
	 * The frame's is all four effects, the nausea and the portal included, and those two are a
	 * distortion of the world rather than of the arm: {@link CameraBob#pose()} carries why the game
	 * leaves them out of its own hand, and Iris leaves them out of its hand's projection too
	 * ({@code pathways/HandRenderer.java:65-70}, the damage tilt always and the walk bob under the
	 * player's own option, which is the game's own pair of conditions).
	 */
	static Matrix4fc bob() {
		return half == null || instance == null ? null : CameraBob.pose();
	}

	/**
	 * Whether one arm belongs to the other half than the one running, in which case the game is
	 * told to draw nothing for it.
	 * <p>
	 * Iris's rule and Iris's test, {@code mixin/MixinItemInHandRenderer.java:32-39}: a hand is
	 * translucent when it holds a block item whose model carries the translucent material flag, and
	 * a hand that is translucent is drawn in the translucent half and nowhere else. Two hands are
	 * asked separately, so a player holding a glass block and a sword has one arm in each half.
	 *
	 * @param held what that arm is holding
	 * @return whether the arm is to be skipped, false whenever the hand is not being drawn at all
	 */
	public static boolean skip(ItemStack held) {
		Half which = half;

		return which != null && translucent(held) != (which == Half.TRANSLUCENT);
	}

	/**
	 * Draws the half that writes outright, among the game's own solid features and before the
	 * deferred stage.
	 * <p>
	 * The moment is Iris's: it calls {@code renderSolid} between the solid features and the
	 * translucent ones ({@code mixin/MixinLevelRenderer.java:277-283}), which is after
	 * {@code executeSolid} and before its {@code beginTranslucents} runs the deferreds. The event
	 * this hangs off sits in the same gap.
	 */
	public static void drawSolid() {
		draw(Half.SOLID);
	}

	/**
	 * Draws the half that blends, at the end of the level and before the pack's composites.
	 * <p>
	 * Iris's moment again, {@code renderTranslucent} being called from the tail of the level render
	 * and immediately before {@code finalizeLevelRendering} ({@code mixin/MixinLevelRenderer.java:170-179}).
	 * Here that is the event the chain itself draws at, one line ahead of it.
	 * <p>
	 * The frame of this family's own buffers ends here whether anything was drawn or not, which is
	 * what hands their GPU memory back for reuse; Iris ends its own on both paths for the same
	 * reason.
	 */
	public static void drawTranslucent() {
		draw(Half.TRANSLUCENT);
		if (instance != null) {
			instance.buffers.endFrame();
		}
	}

	/** Hands back the buffers and the dispatcher, at the end of the client and nowhere else. */
	public static void close() {
		HandDraw hand = instance;
		instance = null;
		if (hand != null) {
			hand.dispatcher.close();
			hand.bound.close();
			hand.buffers.close();
		}
	}

	private static void draw(Half which) {
		if (!draws()) {
			return;
		}

		HandDraw hand = instance();
		if (hand != null) {
			hand.submit(which);
		}
	}

	private static HandDraw instance() {
		if (instance == null && !failed) {
			try {
				instance = new HandDraw(Minecraft.getInstance());
			} catch (RuntimeException e) {
				failed = true;
				Vitrail.logger().error("Vitrail could not build the renderer it draws the hand with, "
						+ "so the game keeps its own hand, drawn after the pack's image rather than "
						+ "into it", e);
			}
		}

		return instance;
	}

	/**
	 * Whether the game would have drawn a hand at all, which is its own condition read rather than
	 * a rule of ours: the late call this stands in for is wrapped in exactly these five tests
	 * ({@code GameRenderer.renderItemInHand}), and a hand drawn here that the game would not have
	 * drawn is a hand in the middle of a cutscene, a sleeping player's screen or a panorama.
	 */
	private static boolean shows(Minecraft minecraft, GameRenderState state,
			CameraRenderState camera) {
		return minecraft.gameMode != null
				&& !camera.isPanoramicMode
				&& state.optionsRenderState.cameraType.isFirstPerson()
				&& !camera.entityRenderState.isSleeping
				&& !state.guiRenderState.isHudHidden
				&& minecraft.gameMode.getPlayerMode() != GameType.SPECTATOR;
	}

	/**
	 * Whether an item held in hand is drawn in the translucent half, which is the block items whose
	 * model says so and nothing else. Iris's test, on the same flag.
	 */
	private static boolean translucent(ItemStack held) {
		if (!(held.getItem() instanceof BlockItem block)) {
			return false;
		}

		return Minecraft.getInstance().getModelManager().getBlockStateModelSet()
				.get(block.getBlock().defaultBlockState())
				.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT);
	}

	private void submit(Half which) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		GameRenderer gameRenderer = minecraft.gameRenderer;
		GameRenderState state = gameRenderer.gameRenderState();
		CameraRenderState camera = state.levelRenderState.cameraRenderState;
		// The state was settled by draws() before this was reached; what is left here is the type,
		// the submission below taking a player and not a player or nothing.
		if (player == null) {
			return;
		}

		// The same partial tick the game hands its own call, taken from the same place: the hand
		// animation is measured on the camera entity and not on the world clock, and the two part
		// company at every tick boundary.
		float partial = gameRenderer.mainCamera()
				.getCameraEntityPartialTicks(minecraft.getDeltaTracker());
		int light = minecraft.getEntityRenderDispatcher().getPackedLightCoords(player, partial);

		// Rebuilt every frame rather than cached: the field of view moves with the sprint and the
		// bow, and Projection itself compares before it dirties, so an unchanged frame costs a
		// handful of float comparisons.
		//
		// The far plane is the CAMERA's and not the hundred the game writes into its own head-up
		// projection, which is Iris's choice (pathways/HandRenderer.java:62) and the one that makes
		// MC_HAND_DEPTH mean what a pack uses it for: a pack divides the squeeze back out and then
		// linearises with the level's own near and far, and that only lands if the hand was drawn
		// through the level's own volume. The game can afford its hundred because nothing of its own
		// reads the hand's depth afterwards.
		this.perspective.setupPerspective(NEAR, camera.depthFar, camera.hudFov,
				state.windowRenderState.width, state.windowRenderState.height);
		// The squeeze, in the volume this backend really rasterises in rather than in the one Iris
		// writes it for. The class comment carries the conversion and why a bare scaling puts the
		// hand BEHIND the world here; what it comes to is that the term in w moves the squeezed band
		// back to the middle of the range, where a reversed z keeps everything the camera can see.
		this.volume.translation(0.0F, 0.0F, (1.0F - DEPTH) / 2.0F).scale(1.0F, 1.0F, DEPTH)
				.mul(this.perspective.getMatrix(this.head));
		this.drawn.set(this.volume).mul(CameraBob.pose());

		RenderSystem.backupProjectionMatrix();
		Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
		modelViewStack.pushMatrix();
		half = which;

		try {
			RenderSystem.setProjectionMatrix(this.bound.getBuffer(this.drawn),
					ProjectionType.PERSPECTIVE);
			// The identity on both, which is Iris's shape and not an omission. The game reaches the
			// same place the long way round, pushing the level's model view and handing the
			// submission its inverse; with the bob in the matrix above there is nothing left for
			// either of them to carry, and a draw this engine hands back to the game lands in the
			// same pixels as one it serves.
			modelViewStack.identity();
			gameRenderer.itemInHandRenderer.submitHandsWithItems(partial, new PoseStack(),
					this.submits, player, light);
			this.dispatcher.renderAllFeatures(this.submits);
		} finally {
			half = null;
			modelViewStack.popMatrix();
			RenderSystem.restoreProjectionMatrix();
		}
	}
}
