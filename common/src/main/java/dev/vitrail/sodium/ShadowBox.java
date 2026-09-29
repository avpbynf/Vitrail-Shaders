package dev.vitrail.sodium;

import org.joml.FrustumIntersection;

/**
 * The cube round the camera that a shadow distance bounds a walk with, and what it says of a box:
 * the part of {@link ShadowCull} that touches no Sodium type.
 * <p>
 * Split off so that the comparisons can be run where Sodium is not on the class path, which is every
 * unit test. Without the split, whether a box that only touches the cube is kept, and whether a box
 * the cube holds whole is answered {@code INSIDE} (which lets Sodium stop testing the subtree) or
 * {@code INTERSECT}, can only be seen as a shadow map that grows or loses casters in a running game.
 * The comments below that speak of the Iris source are the ones {@link ShadowCull} carries in full.
 */
final class ShadowBox {

	private final float distance;

	/** Half the side of the box the shape keeps whole whatever the distance says, or negative. */
	private final float safeZone;

	ShadowBox(float distance, float safeZone) {
		this.distance = distance;
		this.safeZone = safeZone;
	}

	/** Whether a box the distance reaches is held whole by it or by the safe zone, or only in part. */
	int wholeOrPart(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		// The safe zone outranks the distance on a box it holds whole, which is Iris's order and not
		// a softening of the line above: its safe zone frustum answers INSIDE off that box alone the
		// moment the distance is anything but OUTSIDE (SafeZoneCullingFrustum.java:74-78).
		return within(this.distance, minX, minY, minZ, maxX, maxY, maxZ)
				|| (this.safeZone >= 0.0F
						&& within(this.safeZone, minX, minY, minZ, maxX, maxY, maxZ))
				? FrustumIntersection.INSIDE
				: FrustumIntersection.INTERSECT;
	}

	/** Whether any part of a camera-relative box is still within the distance, on all three axes. */
	boolean inside(float minX, float minY, float minZ,
			float maxX, float maxY, float maxZ) {
		return maxX >= -this.distance && minX <= this.distance
				&& maxY >= -this.distance && minY <= this.distance
				&& maxZ >= -this.distance && minZ <= this.distance;
	}

	/** Whether a camera-relative box is wholly within a half size, on all three axes. */
	private static boolean within(float half, float minX, float minY, float minZ,
			float maxX, float maxY, float maxZ) {
		return minX >= -half && maxX <= half
				&& minY >= -half && maxY <= half
				&& minZ >= -half && maxZ <= half;
	}
}
