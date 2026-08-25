package dev.vitrail.render;

import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.ViewSource;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
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

	/**
	 * What the two Distant Horizons planes answer while there is no far terrain. Iris's own number,
	 * and it is deliberately one no pack can mistake for a real distance.
	 */
	static final float FALLBACK_PLANE = 0.01F;

	private final Matrix4f modelView = new Matrix4f();
	private final Matrix4f modelViewInverse = new Matrix4f();
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f projectionInverse = new Matrix4f();

	/**
	 * The frame's projection as the device takes it, reversed Z over zero to one, kept because the
	 * distant volume is built out of it: DH overwrites the z row of the RENDERED matrix, so the base
	 * that row lands in has to be the rendered one, and converting the composition once is what
	 * keeps the published pair singly converted whether or not a row arrived.
	 */
	private final Matrix4f rendered = new Matrix4f();
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
	private static final Vector4f OPAQUE_WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private final Vector4f passColour = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private float far;
	private int renderDistanceChunks;
	private boolean seeded;
	private boolean shadowSeeded;

	/**
	 * The volume DH rasterises in, as the device takes it, and the same volume published the way a
	 * pack reads it. All three stand at the frame's own until {@link #advanceDistantVolume} is handed
	 * a row of DH's, which is every frame of every session without a far terrain.
	 */
	private final Matrix4f distantProjection = new Matrix4f();
	private final Matrix4f publishedDistant = new Matrix4f();
	private final Matrix4f publishedDistantInverse = new Matrix4f();

	/**
	 * The published distant volume of the frame before, seeded like the camera's own history and for
	 * the same reason: the first frame has none, and a zero matrix would make every reprojection
	 * through it the whole screen.
	 */
	private final Matrix4f previousPublishedDistant = new Matrix4f();
	private boolean distantSeeded;

	/**
	 * Whether the volume above is really Distant Horizons' this frame, rather than the frame's own
	 * standing in for it. Kept apart from {@link #distantSeeded}, which is about there having been a
	 * frame before rather than about there being a row now: this one falls back to false the moment
	 * that mod stops drawing, and {@link #distantDepthPair} is the one answer that must not be given
	 * out of two volumes that are the same one.
	 */
	private boolean distantVolume;

	/**
	 * What Distant Horizons drew this frame with, or nothing when it drew nothing. See
	 * {@link #advanceDistant} for why the three move together.
	 */
	private float dhNear = FALLBACK_PLANE;
	private float dhFar = FALLBACK_PLANE;
	private int dhRenderDistanceBlocks = -1;

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

		this.rendered.set(projection);
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
	 * Takes what Distant Horizons drew this frame with, or puts the three values back where they
	 * stand when it drew nothing.
	 * <p>
	 * The three move together on purpose, and {@code render/FrameState} takes them that way. A pack
	 * that has been told the far terrain is there works its fog out of the render distance and
	 * rebuilds a position out of the planes, so a frame that served two of the three from DH and the
	 * third from the game would fog the far terrain against a distance a fifteenth of the one it
	 * stands at. The render distance is the one to watch, and the two packs read most closely here
	 * do different things with it: BSL v10.1.3 widens the fog it already had,
	 * {@code fogFar = max(fogFar, float(dhRenderDistance))} at
	 * {@code shaders/lib/atmospherics/fog.glsl:137}, while Complementary Unbound r5.8.1 replaces the
	 * distance outright, {@code float renderDistance = float(dhRenderDistance);} at
	 * {@code shaders/lib/common.glsl:708}, which is what the whole of its fog is measured against.
	 *
	 * @param near     DH's own near plane in blocks, or {@link #FALLBACK_PLANE} for none
	 * @param far      DH's own far plane in blocks, or {@link #FALLBACK_PLANE} for none
	 * @param distance how far DH draws in blocks, or -1 for none, in which case the game's own
	 *                 render distance in CHUNKS is published instead. The change of unit is Iris's
	 *                 own and it is what packs are written against: without the mod the name
	 *                 answers {@code getEffectiveRenderDistance}, and with it, chunks times sixteen
	 */
	void advanceDistant(float near, float far, int distance) {
		this.dhNear = near;
		this.dhFar = far;
		this.dhRenderDistanceBlocks = distance;
	}

	/**
	 * The volume Distant Horizons rasterises its far terrain in, out of the frame's own matrix and
	 * the z row DH really drew with.
	 * <p>
	 * <strong>The frame's matrix with its z row replaced, which is DH's matrix and not a rebuild of
	 * it, and the rebuild is Iris's road, so this is a divergence and is written as one.</strong>
	 * What Iris does: it rebuilds a perspective out of the field of view read back off the game's
	 * matrix and two planes it asks DH for ({@code compat/dh/DHCompat.java:54} and
	 * {@code compat/dh/LodRendererEvents.java:318}), and the near plane both of those roads answer
	 * is {@code RenderUtil.getNearClipPlaneInBlocks()}, the value BEFORE the clamp to seven and a
	 * half blocks the drawn matrix is really built with ({@code dh/DhDepth} carries the factor).
	 * What is done here: the row DH really drew with, read off its render parameter, so the volume
	 * published is the volume the image holds. What the difference costs the picture: nothing that
	 * reaches it - the two volumes part only in how they spread depth within a few blocks of the
	 * near plane, and every LOD fragment there was discarded by the pack's own overdraw cut a
	 * hundred blocks further out - while the rebuild read as a depth would put the whole band a
	 * factor out. {@code RenderUtil.setDhProjectionMatrix} overwrites that one row of the game's own
	 * matrix and nothing else, so the two share every other term by construction.
	 * <p>
	 * <strong>What is replaced is the CLEAN projection's row, and the walk bob is what that
	 * costs.</strong> This engine publishes a projection with the bob taken out of it and multiplied
	 * into the model view instead, which is where a pack expects both; DH overwrites the row of the
	 * composed matrix, bob included. The two agree exactly while the bob is the identity, and part by
	 * the terms the bob puts into that row while the player is walking. Nothing else could be done
	 * here without taking the bob back out of the model view for this one family, which would move
	 * the far terrain rather than its depth.
	 *
	 * @param scale  the m22 of DH's own matrix, which is the row's z term
	 * @param offset its m23, the row's w term. Zero while DH has drawn no frame, and then there is no
	 *               volume to be had and the frame's own stands in
	 */
	void advanceDistantVolume(float scale, float offset) {
		if (this.distantSeeded) {
			this.previousPublishedDistant.set(this.publishedDistant);
		}

		// The RENDERED matrix and not the published one, and the difference is a defect a review
		// caught: the published matrix is already in the pack's window, so building the volume on
		// it and converting below handed a doubly converted pair to every frame without a row,
		// which is every session without Distant Horizons. On the frames with a row the two bases
		// agree, the row being replaced either way.
		this.distantProjection.set(this.rendered);
		this.distantVolume = offset != 0.0F;
		if (this.distantVolume) {
			// Row z, column z and column w, in JOML's column first spelling: what DH calls m22 and
			// m23 are m22 and m32 here, and the two conventions number the pair the other way round.
			this.distantProjection.m22(scale);
			this.distantProjection.m32(offset);
			// The rest of the row is nought in every perspective the game builds, and DH leaves it
			// alone as well; set here rather than assumed, so that a matrix carrying anything there
			// does not leave half of DH's row standing beside half of the game's.
			this.distantProjection.m02(0.0F);
			this.distantProjection.m12(0.0F);
		}

		ClipSpace.toLegacyDepth(this.distantProjection, this.publishedDistant);
		this.publishedDistant.invert(this.publishedDistantInverse);

		if (!this.distantSeeded) {
			this.previousPublishedDistant.set(this.publishedDistant);
			this.distantSeeded = true;
		}
	}

	/**
	 * The four shadow matrices, which are read by the composite stage of all eight packs of the
	 * corpus whether or not the light's own pass drew anything this frame: everything they need is
	 * the camera position and the sun angle.
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

		// Both planes off the same reach, and the reach is the one Iris resolves a -1 with:
		// ShadowMatrices.createOrthoMatrix is handed -DHCompat.getRenderDistance() * 16 and
		// +the same (shadows/ShadowRenderer.java:429), and that call answers the game's render
		// distance in CHUNKS while Distant Horizons is not drawing and that mod's own in BLOCKS
		// while it is (compat/dh/DHCompatInternal.java:102-109, reached through :111-113). The
		// sixteen therefore multiplies
		// two different units, which is exactly what dhRenderDistance already carries for the
		// uniform of the same name, so the two cannot part company here.
		//
		// It is not a rounding: without the mod this is the render distance in blocks, as it has
		// always been, and with it the box along the light grows by that mod's own reach times
		// sixteen. AND THE PACKS ASK FOR IT. A shadow plane of -1 is what a pack writes to hand the
		// choice back, and Bliss writes both of them as -1 in the one branch its Distant Horizons
		// shadow map setting opens: at the game's own render distance the box would reach a hundred
		// and twenty eight blocks either side of the camera, and a far terrain that BEGINS where the
		// game's chunks end would be clipped out of the map the pack just asked to have it in.
		float reach = dhRenderDistance() * 16.0F;
		float near = plane(nearPlane, -reach);
		float far = plane(farPlane, reach);
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
		this.passColour.set(colour == null ? OPAQUE_WHITE : colour);
	}

	/** Called when the world changes, so that no history crosses a dimension. */
	void reset() {
		this.seeded = false;
		this.shadowSeeded = false;
		this.distantSeeded = false;
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
	 * Answered whether or not Distant Horizons is loaded. Iris registers them without a condition
	 * and falls back the same way, which is why a pack that reads {@code dhProjection} outside the
	 * mod still compiles there and did not here.
	 * <p>
	 * <strong>One volume for every pass, and it is DH's.</strong> Iris serves the three names once,
	 * per frame and to every program alike, and what it serves is the game's own perspective with
	 * DH's two planes in it ({@code uniforms/MatrixUniforms.java:23} registering
	 * {@code DHCompat::getProjection}, and {@code compat/dh/DHCompat.java:53-54} building the
	 * volume). That one answer holds because everything a pack does with these names lives in that
	 * volume: a {@code dh_} program writes its clip position through {@code dhProjection}, BSL's
	 * {@code program/dh_terrain.glsl} ending on
	 * {@code gl_Position = dhProjection * gbufferModelView * position}, and everything else
	 * unprojects {@code dhDepthTex} through {@code dhProjectionInverse}, which
	 * {@link dev.vitrail.render.PackDepth} now serves out of the image the far terrain was really
	 * drawn into. Answering the game's volume anywhere would hand one of those two roads a depth in
	 * the wrong volume - a hill a thousand blocks off read at arm's length.
	 * <p>
	 * While DH has drawn nothing there is no volume to be had, and the frame's own stands in:
	 * {@link #advanceDistantVolume} is handed a zero row then and publishes the frame's projection,
	 * which is Iris's fallback for the same case ({@code compat/dh/DHCompat.java:49-51}).
	 */
	@Override
	public Matrix4fc dhProjection() {
		return this.publishedDistant;
	}

	@Override
	public Matrix4fc dhProjectionInverse() {
		return this.publishedDistantInverse;
	}

	@Override
	public Matrix4fc drawnDistantProjection() {
		return this.distantProjection;
	}

	/**
	 * Read off the RENDERED matrix and the row spliced into it, which are the two the images were
	 * really written with. The published pair is no use here: it has been through
	 * {@link ClipSpace#toLegacyDepth} and describes the window a pack reads, where both of the
	 * images this pair stands between are the device's own.
	 */
	@Override
	public boolean distantDepthPair(Vector2f dest) {
		return this.distantVolume && ClipSpace.distantDepth(this.rendered,
				this.distantProjection.m22(), this.distantProjection.m32(), dest);
	}

	/**
	 * The distant volume of the frame before, which is Iris's answer as well: its {@code dhPrevious}
	 * names hold what the same supplier answered a frame ago
	 * ({@code uniforms/MatrixUniforms.java:45}), not the camera's previous projection.
	 */
	@Override
	public Matrix4fc dhPreviousProjection() {
		return this.previousPublishedDistant;
	}

	@Override
	public float dhNearPlane() {
		return this.dhNear;
	}

	@Override
	public float dhFarPlane() {
		return this.dhFar;
	}

	@Override
	public int dhRenderDistance() {
		return this.dhRenderDistanceBlocks < 0 ? this.renderDistanceChunks
				: this.dhRenderDistanceBlocks;
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
