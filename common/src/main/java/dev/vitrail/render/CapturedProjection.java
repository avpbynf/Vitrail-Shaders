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
	 * What it tests matters. The obvious reading of a perspective is {@code m23 == -1}, and it is
	 * the one term of the w row that the four effects destroy: every one of them is a right hand
	 * multiplication by a rigid transform B, and that turns {@code m23} into {@code -B.m22}, which
	 * is the cosine of the bob's own angle. Testing it would reject the capture in precisely the
	 * four cases this class exists for and accept it only when the capture and the fallback are
	 * the same matrix. {@code m33} is the term that survives: it comes out as minus the z
	 * translation of B, and none of the four translates in z, so it stays at zero through all of
	 * them and is one on the orthographic and quad matrices this is meant to keep out.
	 */
	public static void capture(Matrix4fc rendered) {
		if (rendered.m00() == 0.0F || rendered.m33() != 0.0F) {
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

	/** Forgets the capture, so that a frame nothing captured falls back rather than repeating. */
	public static void clear() {
		captured = false;
	}
}
