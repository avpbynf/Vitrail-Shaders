package dev.vitrail.render;

import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.ViewSource;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * The thirteen matrices a pack reads, in the convention it expects rather than the one the game
 * draws in. {@link ClipSpace} carries the conversion and {@link CapturedProjection} decides which
 * matrix goes into it.
 * <p>
 * Every matrix here is stored, not computed on demand, and the store is advanced exactly once per
 * frame. That is not a cache: {@code gbufferPrevious*} is only meaningful if the whole engine
 * agrees on where one frame ends, and a supplier that shifts its history the first time somebody
 * reads it hands the second pass of a frame a "previous" that is the current one. Iris does
 * exactly that and gets away with it because it uploads per program; we write one block per pass,
 * so the point has to be named, and it is {@link FrameState#advance()}.
 * <p>
 * The inverses are inverses of the <em>published</em> matrix, never the inverse of the rendered
 * one converted afterwards. Those two are not the same thing and only the first one round trips.
 */
public final class ViewMatrices implements ViewSource {

	/** What the game itself uses, and the only value that is not a pack directive. */
	private static final float NEAR = 0.05F;

	/**
	 * What a shadow near or far plane of -1 means: the pack is asking for the render distance
	 * rather than a fixed number. Iris resolves it through Distant Horizons; without the mod that
	 * is the game's own render distance, which is what we resolve it to.
	 */
	private static final float RENDER_DISTANCE_SENTINEL = -1.0F;

	private final Matrix4f modelView = new Matrix4f();
	private final Matrix4f modelViewInverse = new Matrix4f();
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f projectionInverse = new Matrix4f();
	private final Matrix4f previousModelView = new Matrix4f();
	private final Matrix4f previousProjection = new Matrix4f();

	private final Matrix4f shadowModelView = new Matrix4f();
	private final Matrix4f shadowModelViewInverse = new Matrix4f();
	private final Matrix4f shadowProjection = new Matrix4f();
	private final Matrix4f shadowProjectionInverse = new Matrix4f();

	/**
	 * The pair the shadow map on hand was drawn with, which is the previous frame's, because the map
	 * is drawn at the end of a frame for the next one. It is what every sampling pass is told as
	 * {@code shadowModelView}, and it is the drawn pair moved onto this frame's camera.
	 * <p>
	 * <strong>That move is not a refinement, it is the difference between a lookup that lands and
	 * one that does not.</strong> These matrices act on player space, and player space is the world
	 * measured from wherever the camera of the frame doing the measuring stands. The map was drawn
	 * around where the camera stood a frame ago, so the drawn matrix handed over as it is asks about
	 * the point one frame of camera motion away from the one being shaded, and every shadow in the
	 * picture sits that far out of place for as long as the player is moving. Adding the motion back
	 * costs one translation and makes this pair say exactly what the map holds.
	 * <p>
	 * Publishing the fresh pair instead does not do it either, and that is worth saying because it
	 * looks like it should: the grid snap the pair carries is a function of the camera, so it lands
	 * on the same place while the camera stays inside a cell and jumps a whole cell the frame it
	 * leaves one, which is a map read a hundred texels out for that frame. What is left a frame late
	 * here is the sun angle alone.
	 */
	private final Matrix4f mapShadowModelView = new Matrix4f();
	private final Matrix4f mapShadowModelViewInverse = new Matrix4f();
	private final Matrix4f mapShadowProjection = new Matrix4f();
	private final Matrix4f mapShadowProjectionInverse = new Matrix4f();

	/** Where the camera stood when the pair above was built, so the re-origin has a distance. */
	private final Vector3d shadowCamera = new Vector3d();

	private final Vector4f convention = new Vector4f(ClipSpace.REVERSED);

	/**
	 * The pass's own model view and its inverse, standing empty until a pass sets one and empty
	 * again at every frame boundary, which {@link #advance} says why it has to be.
	 */
	private final Matrix4f passModelView = new Matrix4f();
	private final Matrix4f passModelViewInverse = new Matrix4f();
	private boolean passSet;

	/**
	 * The pass's own projection and its inverse, kept and dropped exactly as the pair above is. The
	 * hand is the one family that sets one; {@link dev.vitrail.uniform.ViewSource#passProjection()}
	 * says why it has to be a matrix of its own rather than the frame's.
	 */
	private final Matrix4f passProjection = new Matrix4f();
	private final Matrix4f passProjectionInverse = new Matrix4f();
	private boolean passProjectionSet;

	/**
	 * What was pre-multiplied into {@link #modelView} this frame, kept so that a pass handing in a
	 * matrix of its own is given the same treatment.
	 * <p>
	 * Held rather than asked for again. {@link CameraBob#taken()} answers only while the frame is
	 * being read; {@link FrameState#advance()} clears the capture on its last line, and a pass writes
	 * its block after that, so asking there would always be handed the identity.
	 */
	private final Matrix4f bob = new Matrix4f();

	/**
	 * The bob of the pass being drawn, for the one family whose geometry is not placed by the
	 * frame's, standing empty until a pass sets one and dropped at the same boundary the pass matrix
	 * is.
	 * <p>
	 * <strong>The hand, and only the hand, and the difference is the nausea and the portal.</strong>
	 * The frame's bob is all four effects, since all four are in the projection the level was drawn
	 * with; the hand is drawn under a projection this engine builds itself, and it builds it with
	 * {@link CameraBob#pose()}, the walk bob and the damage tilt alone, because that is the pose the
	 * game gives its own hand and the pose Iris gives its own
	 * ({@code pathways/HandRenderer.java:65-70}, a bob stack of {@code bobHurt} and, under the
	 * player's own option, {@code bobView}, multiplied into the hand's projection and nothing else).
	 * Handing the frame's four to a hand program would publish a spin the arm was never drawn with,
	 * and a hand program writes its clip position through this factor: measured out of game, the
	 * product it forms lands 0.34 away from the matrix the device was given under a full portal,
	 * where the walk and the tilt leave it exact.
	 */
	private final Matrix4f passBob = new Matrix4f();
	private boolean passBobSet;

	/** The colour the pass modulates its draw by, white until a pass says otherwise. */
	private final Vector4f passColour = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private float far;
	private int renderDistanceChunks;
	private boolean seeded;
	private boolean shadowSeeded;

	/**
	 * Takes this frame's view and projection, publishes them, and shifts the previous frame's
	 * copies down. It is also the boundary a pass's own matrix and colour are dropped at, for the
	 * reason written on the two lines that drop them.
	 *
	 * @param view                 the level's model view, a pure rotation in the same handedness and
	 *                             the same convention, so it needs no correction of its own
	 * @param bob                  the walk bob and the three effects beside it, which the game keeps
	 *                             in its projection and a pack expects here instead; the identity
	 *                             when {@link CameraBob} could not vouch for the split
	 * @param projection           the matrix to publish, reversed Z over 0..1, and the one that
	 *                             multiplied by {@code bob} gives back what the level was drawn with
	 * @param far                  the far plane as the pack is told it, which is not the one the
	 *                             game clips at, see {@link FrameState}
	 * @param renderDistanceChunks the render distance, used to resolve a shadow plane of -1
	 */
	void advance(Matrix4fc view, Matrix4fc bob, Matrix4fc projection, float far,
			int renderDistanceChunks) {
		if (this.seeded) {
			this.previousModelView.set(this.modelView);
			this.previousProjection.set(this.projection);
		}

		// Pre-multiplied and not appended: the bob is applied to the world after the camera has
		// turned, so it stands to the left. The other way round would bob along the player's own
		// axes and swing hardest when looking down.
		this.bob.set(bob);
		this.modelView.set(bob).mul(view);
		this.modelView.invert(this.modelViewInverse);

		ClipSpace.toLegacyDepth(projection, this.projection);
		this.projection.invert(this.projectionInverse);

		if (!this.seeded) {
			// The first frame has no history. Handing it a zero matrix would make every motion
			// vector in the first frame the whole screen.
			this.previousModelView.set(this.modelView);
			this.previousProjection.set(this.projection);
			this.seeded = true;
		}

		// The pass matrix and the pass colour go back to the camera's and to white here, because
		// they are set by whoever is about to write a block and nobody owes them a clear
		// afterwards. A geometry program that has locked broken never
		// reaches its writeBlock, so the last matrix any pass set, which is the moon's on a frame
		// that drew the sky, would stand in for the camera's in everything read after it, the
		// decoded dump first of all.
		//
		// Here and not beside the two things FrameState clears at the same boundary, which are
		// cleared on both its paths: a frame with no level returns before this is reached, and
		// nothing draws in one, so there is nothing for a stale matrix to reach. The chain drops
		// these same two again before it writes its own blocks, and that is not this one written
		// twice: this one covers whatever reads between the boundary and the first geometry pass,
		// and that one covers the composites, which run after every geometry family has written its
		// own. The render stage is the third value a pass sets and is dropped by the chain alone,
		// the only reader left holding a stale one being the decoded dump, and that is said where
		// the dump is taken.
		this.passSet = false;
		this.passBobSet = false;
		this.passProjectionSet = false;
		this.passColour.set(1.0F, 1.0F, 1.0F, 1.0F);

		this.far = far;
		this.renderDistanceChunks = renderDistanceChunks;
	}

	/**
	 * The four shadow matrices, which are worth computing even though no shadow pass runs: they
	 * are read by the composite stage of all eight packs of the corpus, and everything they need
	 * is the camera position and the sun angle.
	 * <p>
	 * Ported from Iris {@code shadows/ShadowMatrices.java} and {@code shadows/ShadowRenderer.java}
	 * at b0ae41c, with the pose stack and {@code com.mojang.math.Axis} written out in JOML.
	 * {@code mulPose} and {@code rotate} both multiply on the right, so the order carries over as
	 * it stands.
	 *
	 * @param shadowAngle the angle of whichever body is casting, already divided by 360
	 */
	void advanceShadow(float shadowAngle, float sunPathRotation, float intervalSize,
			Vector3dc camera, float distance, float nearPlane, float farPlane, boolean endFlash,
			float flashXAngle, float flashYAngle) {
		// Shifted down before the fresh pair is built, the same move advance makes for previous:
		// what was drawn with last frame is what the map on hand holds.
		if (this.shadowSeeded) {
			// On the right, where the grid snap already stands, so that the motion is added to the
			// point before the light turns it rather than after: both offsets are distances in
			// player space, and one applied on the far side of the rotation would be a different
			// place on the ground.
			this.mapShadowModelView.set(this.shadowModelView)
					.translate((float) (camera.x() - this.shadowCamera.x),
							(float) (camera.y() - this.shadowCamera.y),
							(float) (camera.z() - this.shadowCamera.z));
			this.mapShadowModelView.invert(this.mapShadowModelViewInverse);
			this.mapShadowProjection.set(this.shadowProjection);
			this.mapShadowProjectionInverse.set(this.shadowProjectionInverse);
		}

		this.shadowCamera.set(camera);

		this.shadowModelView.identity();
		if (endFlash) {
			this.shadowModelView.rotateX((float) Math.toRadians(-flashXAngle));
			this.shadowModelView.rotateY((float) Math.toRadians(flashYAngle));
		} else {
			float skyAngle = shadowAngle < 0.25F ? shadowAngle + 0.75F : shadowAngle - 0.25F;
			this.shadowModelView.rotateX((float) Math.toRadians(90.0F));
			this.shadowModelView.rotateZ((float) Math.toRadians(skyAngle * -360.0F));
			this.shadowModelView.rotateX((float) Math.toRadians(sunPathRotation));
		}

		snapToGrid(this.shadowModelView, intervalSize, camera);
		this.shadowModelView.invert(this.shadowModelViewInverse);

		float near = plane(nearPlane, -this.renderDistanceChunks * 16.0F);
		float far = plane(farPlane, this.renderDistanceChunks * 16.0F);
		// False, and deliberately so. Iris asks the device whether it wants 0..1 here, which is
		// right for the matrix it draws the shadow map with and wrong for the one it publishes.
		// We only publish, so this one is always the legacy volume.
		this.shadowProjection.setOrthoSymmetric(distance * 2.0F, distance * 2.0F, near, far, false);
		this.shadowProjection.invert(this.shadowProjectionInverse);

		if (!this.shadowSeeded) {
			// The first frame has no map and no history, so the published pair is the fresh one:
			// wrong by nothing, since there is nothing to sample yet.
			this.mapShadowModelView.set(this.shadowModelView);
			this.mapShadowModelViewInverse.set(this.shadowModelViewInverse);
			this.mapShadowProjection.set(this.shadowProjection);
			this.mapShadowProjectionInverse.set(this.shadowProjectionInverse);
			this.shadowSeeded = true;
		}
	}

	/** Which depth convention the target this pass draws into carries. */
	void convention(Vector4fc convention) {
		this.convention.set(convention);
	}

	/**
	 * The model view the pass about to write its block is drawn with, or null for the camera's.
	 * <p>
	 * The inverse is taken here once: the pack reads it as often as the matrix itself, and there is
	 * nothing else to derive it from, the matrix having been built on the game's own stack.
	 * <p>
	 * <strong>The bob goes on the front, and the whole correctness of the sky hangs off that one
	 * multiplication.</strong> The game does not put the walk bob in its model view stack: 26.2
	 * multiplies it into the projection, {@code GameRenderer.renderLevel} doing
	 * {@code projectionMatrix.mul(bobStack.last().pose())} while the stack the sky renderer writes
	 * its transform from is {@code viewRotationMatrix} and whatever rotation the element pushed on
	 * top of it. This engine publishes the projection without the bob and puts it in the model view
	 * instead, which is where a pack expects it, so a pass matrix handed straight through would be
	 * the one matrix of the frame the bob had fallen out of: the world would swing as the player
	 * walked and the sun would stand still against it. Multiplied here, the pack's
	 * {@code gl_ProjectionMatrix * gl_ModelViewMatrix} is again the matrix the element was really
	 * drawn with, and {@code gbufferModelViewInverse * gl_ModelViewMatrix}, which is how BSL reaches
	 * world space, cancels down to the rotation of the day alone, exactly as it did under OptiFine.
	 * <p>
	 * The frame that could not be split is right for free: {@link CameraBob#taken()} answers the
	 * identity then, and the projection published is the one the level was drawn with, bob included.
	 * <p>
	 * <strong>Which bob goes on the front is the pass's to say</strong>, and the two arrive in one
	 * call so that neither can be read against the other one's. {@link #passBob} carries why the
	 * hand's is not the frame's; every other pass hands in nothing and is multiplied by the frame's,
	 * as it always was.
	 * <p>
	 * A bob without a matrix is dropped rather than kept, and that is the conservative half of the
	 * pair: a pass with no matrix of its own is drawn under the frame's camera, which the frame's own
	 * bob is the left factor of. Taking one there would publish a bob against a matrix it did not
	 * place.
	 *
	 * @param matrix the matrix the game drew this pass with, or null for the frame's camera
	 * @param bob    the left factor that geometry was really placed by, or null for the frame's
	 */
	void passModelView(Matrix4fc matrix, Matrix4fc bob) {
		this.passSet = matrix != null;
		this.passBobSet = this.passSet && bob != null;
		if (this.passBobSet) {
			this.passBob.set(bob);
		}

		if (this.passSet) {
			this.passModelView.set(cameraBob()).mul(matrix);
			this.passModelView.invert(this.passModelViewInverse);
		}
	}

	/**
	 * The projection the pass about to write its block is drawn under, or null for the frame's.
	 * <p>
	 * Through the same conversion the frame's goes through, and that is the whole of what this method
	 * does beyond storing: what a pass hands in is the volume the game draws in, reversed over 0..1,
	 * and what a pack reads is the legacy one. Converted here rather than by the caller, so that the
	 * two projections a pack can read cannot end up in two different volumes.
	 * <p>
	 * <strong>The bob is deliberately NOT put on the front, where {@link #passModelView} puts
	 * it.</strong> The two are one decision: this engine publishes the bob in the model view, so a
	 * pass that sets both hands in a projection without it and a model view the bob is multiplied
	 * into. A bob in both would apply it twice, and the hand would swing at double the walk.
	 */
	void passProjection(Matrix4fc matrix) {
		this.passProjectionSet = matrix != null;
		if (this.passProjectionSet) {
			ClipSpace.toLegacyDepth(matrix, this.passProjection);
			this.passProjection.invert(this.passProjectionInverse);
		}
	}

	/** The colour the pass modulates its draw by, or null for white. */
	void passColour(Vector4fc colour) {
		this.passColour.set(colour == null ? new Vector4f(1.0F, 1.0F, 1.0F, 1.0F) : colour);
	}

	/** Called when the world changes, so that no history crosses a dimension. */
	void reset() {
		this.seeded = false;
		this.shadowSeeded = false;
	}

	private static float plane(float declared, float fallback) {
		return declared == RENDER_DISTANCE_SENTINEL ? fallback : declared;
	}

	/**
	 * Moves the shadow view onto a grid, so that it does not shimmer as the camera moves inside a
	 * cell. Reproduced with its rounding as it stands: a negative remainder stays negative in
	 * Java, so the offsets do not land in the range the original algorithm reads as if they did,
	 * and packs are written against that.
	 */
	private static void snapToGrid(Matrix4f target, float intervalSize, Vector3dc camera) {
		if (Math.abs(intervalSize) == 0.0F) {
			return;
		}

		float half = intervalSize / 2.0F;
		float offsetX = (float) camera.x() % intervalSize - half;
		float offsetY = (float) camera.y() % intervalSize - half;
		float offsetZ = (float) camera.z() % intervalSize - half;

		target.translate(offsetX, offsetY, offsetZ);
	}

	@Override
	public Matrix4fc gbufferModelView() {
		return this.modelView;
	}

	@Override
	public Matrix4fc gbufferModelViewInverse() {
		return this.modelViewInverse;
	}

	@Override
	public Matrix4fc passModelView() {
		return this.passSet ? this.passModelView : this.modelView;
	}

	/**
	 * The bob on its own, which is the left factor {@link #passModelView(Matrix4fc, Matrix4fc)}
	 * multiplies every pass matrix onto.
	 * <p>
	 * The same field that multiplication reads, so the two cannot say different things: a shader
	 * forming {@code cameraBob() * m} gets exactly what handing {@code m} in would have stored, which
	 * is the whole point of publishing it. The pass's own when it set one, {@link #passBob} saying
	 * which pass that is.
	 */
	@Override
	public Matrix4fc cameraBob() {
		return this.passBobSet ? this.passBob : this.bob;
	}

	@Override
	public Matrix4fc passModelViewInverse() {
		return this.passSet ? this.passModelViewInverse : this.modelViewInverse;
	}

	@Override
	public Matrix4fc passProjection() {
		return this.passProjectionSet ? this.passProjection : this.projection;
	}

	@Override
	public Matrix4fc passProjectionInverse() {
		return this.passProjectionSet ? this.passProjectionInverse : this.projectionInverse;
	}

	@Override
	public Vector4fc passColour() {
		return this.passColour;
	}

	@Override
	public Matrix4fc gbufferProjection() {
		return this.projection;
	}

	@Override
	public Matrix4fc gbufferProjectionInverse() {
		return this.projectionInverse;
	}

	@Override
	public Matrix4fc gbufferPreviousModelView() {
		return this.previousModelView;
	}

	@Override
	public Matrix4fc gbufferPreviousProjection() {
		return this.previousProjection;
	}

	@Override
	public Matrix4fc shadowModelView() {
		return this.mapShadowModelView;
	}

	@Override
	public Matrix4fc shadowModelViewInverse() {
		return this.mapShadowModelViewInverse;
	}

	@Override
	public Matrix4fc shadowProjection() {
		return this.mapShadowProjection;
	}

	@Override
	public Matrix4fc shadowProjectionInverse() {
		return this.mapShadowProjectionInverse;
	}

	@Override
	public Matrix4fc drawnShadowModelView() {
		return this.shadowModelView;
	}

	@Override
	public Matrix4fc drawnShadowModelViewInverse() {
		return this.shadowModelViewInverse;
	}

	@Override
	public Matrix4fc drawnShadowProjection() {
		return this.shadowProjection;
	}

	@Override
	public Matrix4fc drawnShadowProjectionInverse() {
		return this.shadowProjectionInverse;
	}

	/**
	 * Distant Horizons is not loaded, and these are answered anyway. Iris registers them without a
	 * condition and falls back the same way, which is why a pack that reads {@code dhProjection}
	 * outside the mod still compiles there and did not here.
	 */
	@Override
	public Matrix4fc dhProjection() {
		return this.projection;
	}

	@Override
	public Matrix4fc dhProjectionInverse() {
		return this.projectionInverse;
	}

	@Override
	public Matrix4fc dhPreviousProjection() {
		return this.previousProjection;
	}

	@Override
	public float dhNearPlane() {
		return 0.01F;
	}

	@Override
	public float dhFarPlane() {
		return 0.01F;
	}

	@Override
	public int dhRenderDistanceChunks() {
		return this.renderDistanceChunks;
	}

	@Override
	public float near() {
		return NEAR;
	}

	@Override
	public float far() {
		return this.far;
	}

	@Override
	public Vector4fc depthConvention() {
		return this.convention;
	}
}
