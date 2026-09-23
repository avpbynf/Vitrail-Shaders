package dev.vitrail.render;

import dev.vitrail.Vitrail;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The projection the level was actually drawn with, kept for one frame.
 * <p>
 * The conversion to the form a pack reads lives in {@link dev.vitrail.uniform.ClipSpace}; what is
 * here is only the question of <em>which</em> matrix goes into it, and that turns out to matter
 * more than it looks. {@code GameRenderer.renderLevel} takes the camera's projection, multiplies
 * in the walk bob, the damage tilt, the nausea rotation and the portal skew, and hands the result
 * to {@code ProjectionMatrixBuffer.getBuffer(Matrix4f)} without storing it anywhere. The camera's
 * own {@code projectionMatrix} field is the version before all four.
 * <p>
 * The difference is invisible standing still and wrong as soon as the player walks: anything that
 * rebuilds a world position out of {@code depthtex0}, which is most of what a composite does,
 * drifts against the image it is reading. So the drawn matrix is copied on its way past, and the
 * camera's own is the fallback, said out loud once rather than discovered as a shimmer.
 * <p>
 * Rebuilding the bob instead was considered and is worse: {@code spinningEffectTime} and
 * {@code spinningEffectSpeed} are private with no accessor, so the reconstruction would be
 * complete except for the two terms that move fastest.
 */
public final class CapturedProjection {

	private static final Matrix4f CAPTURED = new Matrix4f();

	private static boolean captured;
	private static boolean warned;

	private CapturedProjection() {
	}

	/**
	 * Takes a copy of the matrix the level is about to be drawn with, and changes nothing.
	 * <p>
	 * The test is a belt and not a filter. The overload this comes from has one caller in the
	 * whole game and it is the one that draws the world, but a matrix that is not a perspective
	 * has no business being published as {@code gbufferProjection}.
	 * <p>
	 * What it tests matters. The four effects are a right hand multiplication by a transform B, which
	 * turns the w row of a perspective, {@code (0, 0, -1, 0)}, into minus the z row of B. No single
	 * term of that row survives them. {@code m23} becomes the cosine of the bob's own angle, and
	 * {@code m33} becomes minus the z translation of B, which is NOT zero: the damage tilt turns
	 * about an axis leaning by the direction of the hit and stands to the left of the walk bob's
	 * translation, so a player hit while walking carries some of that translation in z. Refusing the
	 * capture there left the bob check comparing against the camera's own matrix, which fails, and
	 * the split was dropped for the session. What survives all four is the length of the w row's
	 * first three terms: a row of a rotation, one, or more under the nausea's stretch, against
	 * nought on the orthographic and quad matrices this is meant to keep out. The reversed depth of
	 * this backend only rewrites the z row, so it does not enter.
	 */
	public static void capture(Matrix4fc rendered) {
		// The hand binds a perspective of its own, through the same overload, and it is not the
		// level's: a head-up field of view and a clip depth squeezed to an eighth. It passes the test
		// below, so the test cannot be what keeps it out. Taken as the frame's, it would be published
		// as gbufferProjection to everything drawn after it, and every composite would rebuild the
		// world through a volume nothing was drawn in.
		if (HandDraw.drawing() || rendered.m00() == 0.0F || !perspective(rendered)) {
			return;
		}

		CAPTURED.set(rendered);
		captured = true;
	}

	/**
	 * The matrix the world was drawn with this frame, or the fallback when nothing captured it.
	 *
	 * @param fallback the camera's own projection, which is the state before the four right hand
	 *                 multiplications of {@code GameRenderer.renderLevel}
	 */
	public static Matrix4fc rendered(Matrix4fc fallback) {
		if (captured) {
			return CAPTURED;
		}

		if (!warned) {
			warned = true;
			Vitrail.logger().warn("Nothing captured the level's projection, so the walk bob, the "
					+ "damage tilt, the nausea rotation and the portal are missing from every "
					+ "matrix a pack reads. Anything rebuilding a world position from depth will "
					+ "drift while the player moves");
		}

		return fallback;
	}

	private static boolean perspective(Matrix4fc matrix) {
		return matrix.m03() * matrix.m03() + matrix.m13() * matrix.m13()
				+ matrix.m23() * matrix.m23() > 0.25F;
	}

	/** Forgets the capture, so that a frame nothing captured falls back rather than repeating. */
	public static void clear() {
		captured = false;
	}
}
