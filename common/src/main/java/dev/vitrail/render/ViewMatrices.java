package dev.vitrail.render;

import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.ViewSource;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
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

	private final Vector4f convention = new Vector4f(ClipSpace.REVERSED);
	private float far;
	private int renderDistanceChunks;
	private boolean seeded;

	/**
	 * Takes this frame's view and projection, publishes them, and shifts the previous frame's
	 * copies down.
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
	}

	/** Which depth convention the target this pass draws into carries. */
	void convention(Vector4fc convention) {
		this.convention.set(convention);
	}

	/** Called when the world changes, so that no history crosses a dimension. */
	void reset() {
		this.seeded = false;
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
		return this.shadowModelView;
	}

	@Override
	public Matrix4fc shadowModelViewInverse() {
		return this.shadowModelViewInverse;
	}

	@Override
	public Matrix4fc shadowProjection() {
		return this.shadowProjection;
	}

	@Override
	public Matrix4fc shadowProjectionInverse() {
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
