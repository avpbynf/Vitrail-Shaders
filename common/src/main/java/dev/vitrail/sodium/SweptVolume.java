package dev.vitrail.sodium;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * The half spaces a shadow walk is measured against, and the tests of a camera relative box against
 * them: everything of {@link ShadowCullFrustum} that touches no Sodium type.
 * <p>
 * Split off so that the plane arithmetic can be run where Sodium is not on the class path, which is
 * every unit test. Without the split the only proof that the six camera faces come out as the volume
 * they claim, that the silhouette is swept along the light the right way round and that a box on a
 * plane is kept is a shadow map in a running game, where a wrong plane drops a caster or keeps a
 * section too many and nothing on screen says which.
 * <p>
 * The comments below that speak of the class note mean the class comment of
 * {@link ShadowCullFrustum}, which carries the derivation and the worked numbers.
 */
final class SweptVolume {

	/** Six faces, and one swept plane per edge between a kept face and a dropped one. */
	private static final int MAX_PLANES = 13;

	/**
	 * The four faces that share an edge with each face, by axis pair, which is Iris's
	 * {@code NeighboringPlaneSet} written as a table ({@code shadows/frustum/advanced}). Indexed by
	 * the face's own index shifted right once, so the two faces of an axis share a row: a face never
	 * shares an edge with its opposite, and does share one with all four of the others.
	 */
	private static final int[][] NEIGHBOURS = {
		{2, 3, 4, 5},
		{0, 1, 4, 5},
		{0, 1, 2, 3},
	};

	private static final Matrix4f TRANSPOSED = new Matrix4f();
	private static final Vector4f[] FACES = {
			new Vector4f(), new Vector4f(), new Vector4f(),
			new Vector4f(), new Vector4f(), new Vector4f()
	};
	private static final Vector3f EDGE = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();
	private static final Vector3f POINT = new Vector3f();
	private static final Vector3f FRONT_NORMAL = new Vector3f();

	private final float[][] planes = new float[MAX_PLANES][4];
	private final boolean[] back = new boolean[6];
	private final Vector3f light = new Vector3f();

	/** Half the side of the box the safe zone keeps whatever the sweep says, or negative for none. */
	private final float safeZone;

	private int planeCount;

	SweptVolume(Matrix4fc camera, Vector3fc light, float safeZone) {
		this.safeZone = safeZone;
		this.light.set(light);

		build(camera);
	}

	private void build(Matrix4fc camera) {
		Vector4f[] faces = faces(camera);

		// The faces whose inward normal points towards the light, which are the far side of the
		// camera's volume as the light sees it and the only ones that still bound the casters. A
		// normal exactly across the light bounds nothing and cuts nothing either, so it is kept
		// without being counted as back, which is Iris's reading of the degenerate case.
		for (int index = 0; index < faces.length; index++) {
			Vector4f face = faces[index];
			float towards = face.x * this.light.x + face.y * this.light.y + face.z * this.light.z;
			this.back[index] = towards > 0.0F;

			if (towards >= 0.0F) {
				add(face.x, face.y, face.z, face.w);
			}
		}

		// And the silhouette: every edge between a kept face and a dropped one, swept along the light.
		for (int index = 0; index < faces.length; index++) {
			if (!this.back[index]) {
				continue;
			}

			for (int neighbour : NEIGHBOURS[index >>> 1]) {
				if (!this.back[neighbour]) {
					edge(faces[index], faces[neighbour]);
				}
			}
		}
	}

	/**
	 * The camera's six faces, as plane equations in camera relative world space.
	 * <p>
	 * {@code (a, b, c, w)} stands for {@code ax + by + cz + w >= 0} being inside, which is what makes
	 * the test below a dot product against {@code (x, y, z, 1)}. The four sides are Iris's lines as
	 * they stand; the two z faces are this engine's volume, and the class note carries the difference
	 * and what ignoring it costs.
	 * <p>
	 * The normalisation is over all four components, which is Iris's as well
	 * ({@code BaseClippingPlanes.java:16}). It scales a plane rather than moving it, so no test
	 * changes; what it is not is a plane in the metric sense, and no distance may be read off one.
	 */
	private static Vector4f[] faces(Matrix4fc camera) {
		TRANSPOSED.set(camera).transpose();
		face(FACES[0], TRANSPOSED, -1.0F, 0.0F, 0.0F, 1.0F);
		face(FACES[1], TRANSPOSED, 1.0F, 0.0F, 0.0F, 1.0F);
		face(FACES[2], TRANSPOSED, 0.0F, -1.0F, 0.0F, 1.0F);
		face(FACES[3], TRANSPOSED, 0.0F, 1.0F, 0.0F, 1.0F);
		// The far face, rowW - rowZ, and the near one, rowW + rowZ. Iris's own two lines, and
		// they are right here because the matrix is already in Iris's volume. The class note
		// carries what a second conversion would move and what the drawn matrix would lose.
		face(FACES[4], TRANSPOSED, 0.0F, 0.0F, -1.0F, 1.0F);
		face(FACES[5], TRANSPOSED, 0.0F, 0.0F, 1.0F, 1.0F);

		return FACES;
	}

	private static void face(Vector4f into, Matrix4fc transposed, float x, float y, float z,
			float w) {
		into.set(x, y, z, w).mul(transposed).normalize();
	}

	/**
	 * The plane that sweeps the edge shared by two faces along the light.
	 * <p>
	 * Its normal has to stand across the light, which is what makes it a sweep rather than a tilt, so
	 * it is the edge direction crossed with the light. Its distance is then fixed by asking for a
	 * point on the edge, which is where the two faces and a third plane through the origin normal to
	 * the edge meet. That last step is Iris's, itself taken from Graphics Gems by way of a Stack
	 * Overflow answer it credits in place ({@code AdvancedShadowCullingFrustum.addEdgePlane}).
	 */
	private void edge(Vector4f back, Vector4f front) {
		EDGE.set(back.x, back.y, back.z).cross(front.x, front.y, front.z);
		NORMAL.set(EDGE).cross(this.light);
		FRONT_NORMAL.set(front.x, front.y, front.z);
		POINT.set(EDGE).cross(back.x, back.y, back.z).mul(-front.w)
				.add(FRONT_NORMAL.cross(EDGE).mul(-back.w))
				.mul(1.0F / EDGE.lengthSquared());

		add(NORMAL.x, NORMAL.y, NORMAL.z, -NORMAL.dot(POINT));
	}

	private void add(float x, float y, float z, float w) {
		float[] plane = this.planes[this.planeCount];
		plane[0] = x;
		plane[1] = y;
		plane[2] = z;
		plane[3] = w;
		this.planeCount += 1;
	}

	boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		// The safe zone is a KEEP and not a bound, so it stands beside the sweep rather than in front
		// of it, which is the shape Iris gives it (SafeZoneCullingFrustum.testAab): a section reaching
		// into the pack's own voxel grid is drawn whatever the sweep says of it, because the pack
		// samples that grid from places the sweep knows nothing about.
		return within(minX, minY, minZ, maxX, maxY, maxZ)
				|| visible(minX, minY, minZ, maxX, maxY, maxZ);
	}

	int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		// Wholly inside the safe zone answers INSIDE and not INTERSECT, which is Iris's own line
		// (SafeZoneCullingFrustum.intersectAab:76-78). It buys the walk the right to stop testing a
		// whole subtree; answered INTERSECT it would still keep everything, one test at a time.
		if (this.safeZone >= 0.0F && minX >= -this.safeZone && maxX <= this.safeZone
				&& minY >= -this.safeZone && maxY <= this.safeZone
				&& minZ >= -this.safeZone && maxZ <= this.safeZone) {
			return FrustumIntersection.INSIDE;
		}

		if (within(minX, minY, minZ, maxX, maxY, maxZ)) {
			return FrustumIntersection.INTERSECT;
		}

		return corners(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/**
	 * Whether a camera relative box reaches into the safe zone at all, which is false outright where
	 * there is no safe zone: nothing reaches into a cube that is not there.
	 */
	private boolean within(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return this.safeZone >= 0.0F
				&& maxX >= -this.safeZone && minX <= this.safeZone
				&& maxY >= -this.safeZone && minY <= this.safeZone
				&& maxZ >= -this.safeZone && minZ <= this.safeZone;
	}

	/**
	 * Whether a box reaches into the swept volume, which is the hot path: it is what the walk asks,
	 * once per section per frame, and it stops at the first plane that rejects.
	 * <p>
	 * The dot product is written out rather than fused. Iris reaches for {@code Math.fma} and falls
	 * back to exactly this when the machine has told the virtual machine it has no instruction for
	 * it, and an unfused machine pays a software emulation for the fused call, so the plain form is
	 * the one that is never the slow answer. What it costs is a rounding, on a section sitting
	 * exactly on a plane.
	 */
	private boolean visible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		for (int index = 0; index < this.planeCount; index++) {
			float[] plane = this.planes[index];
			float x = plane[0] < 0.0F ? minX : maxX;
			float y = plane[1] < 0.0F ? minY : maxY;
			float z = plane[2] < 0.0F ? minZ : maxZ;

			if (plane[0] * x + plane[1] * y + plane[2] * z < -plane[3]) {
				return false;
			}
		}

		return true;
	}

	/**
	 * The same, answering whether the box is wholly inside as well, for the callers that ask.
	 * <p>
	 * The two halves of the loop are not symmetric and must not be made so. The near corner is
	 * tested against EVERY plane, because that is the test that culls and any plane may still be the
	 * one that rejects. The far corner only decides between INSIDE and INTERSECT, and one plane
	 * having put it outside already settles that, so the rest of its dot products answer a question
	 * nobody is asking any more. Written as a guarded assignment rather than the {@code &=} it reads
	 * like, since that operator on booleans does not short circuit: it would evaluate the second dot
	 * product for all thirteen planes of every box the shadow walk hands over.
	 */
	private int corners(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		boolean inside = true;

		for (int index = 0; index < this.planeCount; index++) {
			float[] plane = this.planes[index];
			float x = plane[0] < 0.0F ? minX : maxX;
			float y = plane[1] < 0.0F ? minY : maxY;
			float z = plane[2] < 0.0F ? minZ : maxZ;

			if (plane[0] * x + plane[1] * y + plane[2] * z < -plane[3]) {
				return FrustumIntersection.OUTSIDE;
			}

			if (inside) {
				inside = plane[0] * (plane[0] < 0.0F ? maxX : minX)
						+ plane[1] * (plane[1] < 0.0F ? maxY : minY)
						+ plane[2] * (plane[2] < 0.0F ? maxZ : minZ) + plane[3] >= 0.0F;
			}
		}

		return inside ? FrustumIntersection.INSIDE : FrustumIntersection.INTERSECT;
	}
}
