package dev.vitrail.sodium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

/**
 * The camera's volume swept along the light, held against two things that share no code with it.
 * <p>
 * One is the worked numbers of {@link ShadowCullFrustum}'s class comment, computed there by hand: a
 * ninety degree camera at the origin looking down minus Z, the light straight overhead, and three
 * sections of half size 9.125 that must be kept, dropped and kept. The other is a geometric reading of
 * what a swept volume IS: a caster at {@code P} matters exactly when some point {@code P - t * light}
 * with {@code t >= 0} lies in the camera's frustum, which is a small linear feasibility problem over
 * {@code t} once the frustum is six half spaces. Those six come from JOML's own extraction
 * ({@code Matrix4f.frustumPlane}), not from the class under test. A box no bigger than a point makes
 * the class's test exact, so the two are compared point by point over seeded random points, with a
 * band a thousandth of a block wide either side of the boundary where floating point may differ.
 */
class SweptVolumeTest {

	/** Sodium's padded half size of a section, which the section tests of the shape add to the origin. */
	private static final float HALF = 9.125F;

	private static Matrix4f camera(double fovDegrees, float aspect, float near, float far) {
		return new Matrix4f().perspective((float) Math.toRadians(fovDegrees), aspect, near, far);
	}

	private static Matrix4f classNoteCamera() {
		return camera(90.0, 1.0F, 0.05F, 512.0F);
	}

	private static boolean point(SweptVolume volume, float x, float y, float z) {
		return volume.testAab(x, y, z, x, y, z);
	}

	private static boolean section(SweptVolume volume, float x, float y, float z) {
		return volume.testAab(x - HALF, y - HALF, z - HALF, x + HALF, y + HALF, z + HALF);
	}

	private static Vector3f unit(Random random) {
		Vector3f direction;
		do {
			direction = new Vector3f((float) random.nextGaussian(), (float) random.nextGaussian(),
					(float) random.nextGaussian());
		} while (direction.lengthSquared() < 0.05F);

		return direction.normalize();
	}

	/** Six inward unit half spaces {@code (a, b, c, d)}, {@code a x + b y + c z + d >= 0} inside. */
	private static double[][] planesOf(Matrix4f camera) {
		double[][] planes = new double[6][];
		Vector4f plane = new Vector4f();
		for (int which = 0; which < 6; which++) {
			camera.frustumPlane(which, plane);
			double length = Math.sqrt((double) plane.x * plane.x + (double) plane.y * plane.y
					+ (double) plane.z * plane.z);
			planes[which] = new double[] {plane.x / length, plane.y / length, plane.z / length,
					plane.w / length};
		}

		return planes;
	}

	/**
	 * Whether {@code P - t * light} is inside all six half spaces for some {@code t >= 0}, with every
	 * half space moved out by {@code tolerance} (a negative one moves it in). Each half space bounds
	 * {@code t} from one side or, when the light runs along it, decides on its own.
	 */
	private static boolean swept(double[][] planes, Vector3f light, double x, double y, double z,
			double tolerance) {
		double low = 0.0;
		double high = Double.POSITIVE_INFINITY;
		for (double[] plane : planes) {
			double slack = plane[0] * x + plane[1] * y + plane[2] * z + plane[3] + tolerance;
			double along = plane[0] * light.x + plane[1] * light.y + plane[2] * light.z;
			if (Math.abs(along) < 1.0E-9) {
				if (slack < 0.0) {
					return false;
				}
			} else if (along > 0.0) {
				high = Math.min(high, slack / along);
			} else {
				low = Math.max(low, slack / along);
			}
		}

		return low <= high;
	}

	// -- the class note's own numbers ---------------------------------------------------------

	@Test
	void theThreeSectionsOfTheClassNoteUnderAnOverheadLight() {
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(0, 1, 0), -1.0F);

		assertTrue(section(volume, 0, -40, -100), "in front and below, in plain sight");
		assertFalse(section(volume, 0, -40, 100), "behind the camera, which the light's own box would keep");
		assertTrue(section(volume, 0, 80, -20), "above the camera and out of its view, but it shadows into it");
	}

	@Test
	void aSectionTwoThousandBlocksOutIsDroppedByTheFarFace() {
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(0, 1, 0), -1.0F);

		assertFalse(section(volume, 0, -40, -2000));
		assertTrue(section(volume, 0, -40, -500), "well inside the far face at 512");
		assertFalse(section(volume, 0, -40, -540), "the whole section is past 512");
		assertTrue(section(volume, 0, -40, -515), "its near half still reaches 512");
	}

	@Test
	void theFourSidesOfANinetyDegreeCameraAreWhereTheirEquationsPutThem() {
		// No light, so the volume is the plain frustum: x >= z, x <= -z, y >= z, y <= -z, z <= -0.05,
		// z >= -512, worked from the perspective matrix by hand. A point box makes each test exact.
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(), -1.0F);

		assertTrue(point(volume, 9.98F, 0, -10));
		assertFalse(point(volume, 10.02F, 0, -10));
		assertTrue(point(volume, -9.98F, 0, -10));
		assertFalse(point(volume, -10.02F, 0, -10));
		assertTrue(point(volume, 0, 9.98F, -10));
		assertFalse(point(volume, 0, 10.02F, -10));
		assertTrue(point(volume, 0, -9.98F, -10));
		assertFalse(point(volume, 0, -10.02F, -10));

		assertTrue(point(volume, 0, 0, -0.06F));
		assertFalse(point(volume, 0, 0, -0.04F), "in front of the near face");
		// The far face is rowW - rowZ, a difference of two floats near one, so it sits within about
		// a quarter of a block of 512 at this ratio of far to near; the checks stay well clear of it.
		assertTrue(point(volume, 0, 0, -500.0F));
		assertFalse(point(volume, 0, 0, -530.0F), "past the far face");
		assertFalse(point(volume, 0, 0, 1.0F), "behind the eye");
	}

	@Test
	void theVolumeFollowsTheRotationOfTheCameraMatrix() {
		Matrix4f looking = camera(90.0, 1.0F, 0.05F, 512.0F).rotateY((float) -Math.PI / 2);
		SweptVolume volume = new SweptVolume(looking, new Vector3f(), -1.0F);

		// A camera turned to look along minus X: what stood in front of it in world space is minus X.
		assertTrue(point(volume, -100, 0, 0));
		assertFalse(point(volume, 100, 0, 0));
		assertFalse(point(volume, 0, 0, -100));
	}

	// -- sweeping ----------------------------------------------------------------------------

	@Test
	void aCasterBetweenTheCameraVolumeAndTheLightIsKeptAndOneBehindItIsNot() {
		// Light straight up: anything straight above the visible region shadows into it, anything
		// straight below it is on the far side of the light's travel and shadows nothing visible.
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(0, 1, 0), -1.0F);

		assertTrue(point(volume, 0, 500, -20), "a tall caster above the view");
		assertTrue(point(volume, 0, 5, -20), "in the view itself");
		assertFalse(point(volume, 0, -60, -20), "below the bottom face, which the sweep cannot reach up to");
		assertFalse(point(volume, 50, 500, -20), "above but outside the sides, which are kept as they stand");
	}

	@Test
	void aLightAlongTheViewDirectionSweepsTheNearFaceAwayAndKeepsTheFarOne() {
		// The light stands behind the camera, at +Z, so casters between the camera and the light can
		// shadow the view and everything past the far face cannot.
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(0, 0, 1), -1.0F);

		assertTrue(point(volume, 0, 0, 200), "behind the camera, in the light's path to the view");
		assertTrue(point(volume, 0, 0, -100));
		assertFalse(point(volume, 0, 0, -600));
		assertTrue(point(volume, 300, 0, 200), "the volume widens with distance, so the sweep keeps this too");
		assertFalse(point(volume, 700, 0, 200), "wider than the far face is, so no path of the light gets in");
	}

	@Test
	void aLightAlongXIsExactlyAcrossFourFacesAndTheyAreKeptAsTheyStand() {
		// The degenerate case the comment describes: a normal exactly across the light bounds nothing
		// and cuts nothing, so it is kept. Along +X the top, bottom, near and far normals have no X
		// component at all, so their dot product with the light is an exact zero.
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(1, 0, 0), -1.0F);
		double[][] planes = planesOf(classNoteCamera());
		Vector3f light = new Vector3f(1, 0, 0);
		Random random = new Random(11);
		for (int i = 0; i < 5000; i++) {
			float x = random.nextFloat() * 300 - 150;
			float y = random.nextFloat() * 300 - 150;
			float z = random.nextFloat() * 300 - 150;
			if (swept(planes, light, x, y, z, -1.0E-3)) {
				assertTrue(point(volume, x, y, z), x + "," + y + "," + z);
			} else if (!swept(planes, light, x, y, z, 1.0E-3)) {
				assertFalse(point(volume, x, y, z), x + "," + y + "," + z);
			}
		}

		assertTrue(point(volume, 400, 0, -300), "swept towards +X from inside the far reach");
		assertFalse(point(volume, 400, 150, -100), "above the top face, which a sweep along X does not lift");
	}

	// -- against the geometric reading, point by point ---------------------------------------

	/** Compares point by point and answers how many of the sample were kept and how many dropped. */
	private int[] compareWithTheGeometricReading(Matrix4f camera, Vector3f light, long seed, float spread) {
		SweptVolume volume = new SweptVolume(camera, light, -1.0F);
		double[][] planes = planesOf(camera);
		Random random = new Random(seed);
		int decided = 0;
		int keptCount = 0;
		int droppedCount = 0;
		int samples = 6000;
		for (int i = 0; i < samples; i++) {
			float x = (random.nextFloat() * 2 - 1) * spread;
			float y = (random.nextFloat() * 2 - 1) * spread;
			float z = (random.nextFloat() * 2 - 1) * spread;
			boolean certainlyIn = swept(planes, light, x, y, z, -1.0E-3);
			boolean possiblyIn = swept(planes, light, x, y, z, 1.0E-3);
			String at = "point " + x + "," + y + "," + z + " light " + light;
			if (certainlyIn) {
				assertTrue(point(volume, x, y, z), at);
				decided++;
				keptCount++;
			} else if (!possiblyIn) {
				assertFalse(point(volume, x, y, z), at);
				decided++;
				droppedCount++;
			}
		}

		assertTrue(decided > samples * 0.95, "the band near the boundary is thin: decided " + decided);

		return new int[] {keptCount, droppedCount};
	}

	private static void assertStraddles(int[] kept, int[] dropped) {
		int totalKept = 0;
		int totalDropped = 0;
		for (int i = 0; i < kept.length; i++) {
			totalKept += kept[i];
			totalDropped += dropped[i];
		}

		assertTrue(totalKept > 1000 && totalDropped > 1000,
				"the samples straddle the volume: kept " + totalKept + ", dropped " + totalDropped);
	}

	@Test
	void everyPointAgreesWithTheGeometricReadingForLightsAlongTheAxes() {
		Matrix4f camera = camera(70.0, 16.0F / 9.0F, 0.05F, 512.0F).rotateXYZ(0.3F, -0.7F, 0.1F);
		Vector3f[] lights = {new Vector3f(0, 1, 0), new Vector3f(0, -1, 0), new Vector3f(1, 0, 0),
				new Vector3f(-1, 0, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)};
		int[] kept = new int[lights.length];
		int[] dropped = new int[lights.length];
		for (int i = 0; i < lights.length; i++) {
			int[] counts = compareWithTheGeometricReading(camera, lights[i], 1 + i, 300.0F);
			kept[i] = counts[0];
			dropped[i] = counts[1];
		}

		assertStraddles(kept, dropped);
	}

	@Test
	void everyPointAgreesWithTheGeometricReadingForSeededRandomCamerasAndLights() {
		Random random = new Random(0xC0FFEE);
		int[] kept = new int[40];
		int[] dropped = new int[40];
		for (int scenario = 0; scenario < 40; scenario++) {
			double fov = 40.0 + random.nextInt(70);
			float aspect = 1.0F + random.nextFloat();
			Matrix4f camera = camera(fov, aspect, 0.05F, 256.0F + random.nextInt(512))
					.rotateXYZ(random.nextFloat() * 6.0F - 3.0F, random.nextFloat() * 6.0F - 3.0F,
							random.nextFloat() * 6.0F - 3.0F);

			int[] counts = compareWithTheGeometricReading(camera, unit(random), 100 + scenario, 250.0F);
			kept[scenario] = counts[0];
			dropped[scenario] = counts[1];
		}

		assertStraddles(kept, dropped);
	}

	@Test
	void everyPointAgreesWithTheGeometricReadingForEveryTiltOfALightAgainstASymmetricCamera() {
		// Every way a light can be tilted against the six faces of a symmetric camera, components of
		// nought, one and two in either direction, which is what puts each pair of neighbouring faces on
		// either side of the silhouette in turn: with a ninety degree camera an x face and the near face
		// only part when the light leans two to one across them. The second camera has a near face eight
		// blocks from the eye, so the edges round it are long enough for a random point to fall in the
		// sliver a wrong silhouette would leave; at a twentieth of a block none ever would.
		tiltsAgainst(classNoteCamera(), 200.0F);
		tiltsAgainst(camera(90.0, 1.0F, 8.0F, 200.0F), 60.0F);
	}

	private void tiltsAgainst(Matrix4f camera, float spread) {
		int[] kept = new int[124];
		int[] dropped = new int[124];
		int index = 0;
		for (int x = -2; x <= 2; x++) {
			for (int y = -2; y <= 2; y++) {
				for (int z = -2; z <= 2; z++) {
					if (x == 0 && y == 0 && z == 0) {
						continue;
					}

					int[] counts = compareWithTheGeometricReading(camera, new Vector3f(x, y, z).normalize(),
							500 + index, spread);
					kept[index] = counts[0];
					dropped[index] = counts[1];
					index++;
				}
			}
		}

		assertEquals(124, index);
		assertStraddles(kept, dropped);
	}

	@Test
	void aLightWithNoLengthLeavesTheCameraFrustumAlone() {
		Matrix4f camera = camera(80.0, 1.5F, 0.1F, 300.0F).rotateXYZ(0.2F, 0.4F, -0.3F);

		int[] counts = compareWithTheGeometricReading(camera, new Vector3f(), 7, 250.0F);

		assertTrue(counts[0] > 100 && counts[1] > 100, "kept " + counts[0] + ", dropped " + counts[1]);
	}

	// -- boxes ------------------------------------------------------------------------------

	@Test
	void boxAnswersAgreeWithEachOtherAndWithTheCornersOfTheBox() {
		Random random = new Random(424242);
		for (int scenario = 0; scenario < 12; scenario++) {
			Matrix4f camera = camera(60.0 + random.nextInt(40), 1.0F + random.nextFloat(), 0.05F, 400.0F)
					.rotateXYZ(random.nextFloat() * 6.0F - 3.0F, random.nextFloat() * 6.0F - 3.0F, 0.0F);
			Vector3f light = unit(random);
			SweptVolume volume = new SweptVolume(camera, light, -1.0F);
			double[][] planes = planesOf(camera);

			int outside = 0;
			int inside = 0;
			int intersect = 0;
			for (int i = 0; i < 1500; i++) {
				float cx = random.nextFloat() * 500 - 250;
				float cy = random.nextFloat() * 500 - 250;
				float cz = random.nextFloat() * 500 - 250;
				float hx = random.nextFloat() * 30 + 0.1F;
				float hy = random.nextFloat() * 30 + 0.1F;
				float hz = random.nextFloat() * 30 + 0.1F;

				int answer = volume.intersectAab(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
				boolean visible = volume.testAab(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
				String at = "box " + cx + "," + cy + "," + cz + " half " + hx + "," + hy + "," + hz;

				assertEquals(answer != FrustumIntersection.OUTSIDE, visible, at);

				boolean allCornersIn = true;
				boolean anyCornerIn = false;
				boolean allCornersSurelyIn = true;
				for (int corner = 0; corner < 8; corner++) {
					double x = cx + ((corner & 1) == 0 ? -hx : hx);
					double y = cy + ((corner & 2) == 0 ? -hy : hy);
					double z = cz + ((corner & 4) == 0 ? -hz : hz);
					allCornersSurelyIn &= swept(planes, light, x, y, z, -1.0E-3);
					allCornersIn &= swept(planes, light, x, y, z, 1.0E-3);
					anyCornerIn |= swept(planes, light, x, y, z, -1.0E-3);
				}

				if (allCornersSurelyIn) {
					assertEquals(FrustumIntersection.INSIDE, answer, at + " has every corner in");
				}

				if (!allCornersIn) {
					assertTrue(answer != FrustumIntersection.INSIDE, at + " has a corner out");
				}

				if (anyCornerIn) {
					assertTrue(answer != FrustumIntersection.OUTSIDE, at + " has a corner in");
				}

				switch (answer) {
					case FrustumIntersection.OUTSIDE -> outside++;
					case FrustumIntersection.INSIDE -> inside++;
					default -> intersect++;
				}
			}

			assertTrue(outside > 50 && inside > 5 && intersect > 5,
					"every answer comes up: " + outside + " outside, " + inside + " inside, " + intersect
							+ " intersect");
		}
	}

	@Test
	void aBoxWithACornerInsideIsNeverDroppedEvenWhenItsCentreIsOutside() {
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(), -1.0F);

		// A slab across the whole view whose centre is far to the left of it. The left face is
		// x >= z, so at z = -30 it starts at x = -30: a slab reaching x = -25 is in there by five
		// blocks and one reaching x = -35 is wholly outside.
		assertTrue(volume.testAab(-500, -1, -30, -25, 1, -20));
		assertEquals(FrustumIntersection.INTERSECT, volume.intersectAab(-500, -1, -30, -25, 1, -20));
		assertFalse(volume.testAab(-500, -1, -30, -35, 1, -20));
		assertEquals(FrustumIntersection.OUTSIDE, volume.intersectAab(-500, -1, -30, -35, 1, -20));
		assertEquals(FrustumIntersection.INSIDE, volume.intersectAab(-1, -1, -30, 1, 1, -20));
	}

	@Test
	void aBoxWithAFaceExactlyOnAPlaneIsKeptBecauseTheTestIsInclusive() {
		// Near 1 and far 3 make the near face z <= -1 with every number exact in a float.
		SweptVolume volume = new SweptVolume(camera(90.0, 1.0F, 1.0F, 3.0F), new Vector3f(), -1.0F);

		assertTrue(volume.testAab(-0.5F, -0.5F, -1.0F, 0.5F, 0.5F, 0.0F), "min z exactly on the face");
		assertFalse(volume.testAab(-0.5F, -0.5F, -0.999F, 0.5F, 0.5F, 0.0F), "a hair in front of it");
	}

	@Test
	void aBoxWithACornerExactlyOnAPlaneIsStillWhollyInside() {
		SweptVolume volume = new SweptVolume(camera(90.0, 1.0F, 1.0F, 3.0F), new Vector3f(), -1.0F);

		assertEquals(FrustumIntersection.INSIDE, volume.intersectAab(-0.5F, -0.5F, -2.0F, 0.5F, 0.5F, -1.0F),
				"max z exactly on the near face");
		assertEquals(FrustumIntersection.INTERSECT, volume.intersectAab(-0.5F, -0.5F, -2.0F, 0.5F, 0.5F, -0.999F));
	}

	// -- the safe zone ------------------------------------------------------------------------

	@Test
	void aBoxReachingIntoTheSafeZoneIsKeptWhateverTheSweepSaysOfIt() {
		// Behind the camera, where the plain frustum drops everything.
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(), 16.0F);

		assertTrue(volume.testAab(-2, -2, 10, 2, 2, 14), "inside the cube of half side 16");
		assertTrue(volume.testAab(-30, -2, 10, -16, 2, 14), "touching the cube: the test is inclusive");
		assertFalse(volume.testAab(-30, -2, 10, -16.001F, 2, 14), "a whisker short of it");
		assertFalse(volume.testAab(-2, -2, 16.001F, 2, 2, 20), "the third axis counts as well");
		assertFalse(volume.testAab(-2, 16.001F, 10, 2, 20, 14), "and the second");
	}

	@Test
	void aBoxWhollyInsideTheSafeZoneIsInsideAndOneReachingIntoItOnlyIntersects() {
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(), 16.0F);

		assertEquals(FrustumIntersection.INSIDE, volume.intersectAab(-16, -16, -16, 16, 16, 16));
		assertEquals(FrustumIntersection.INSIDE, volume.intersectAab(-3, -3, 3, 3, 3, 9));
		assertEquals(FrustumIntersection.INTERSECT, volume.intersectAab(-16.001F, -16, -16, 16, 16, 16));
		assertEquals(FrustumIntersection.INTERSECT, volume.intersectAab(-30, -2, 10, -10, 2, 14));
		assertEquals(FrustumIntersection.OUTSIDE, volume.intersectAab(-30, -2, 10, -17, 2, 14));
	}

	@Test
	void noSafeZoneMeansNoSuchBoxAtAllEvenAtTheOrigin() {
		SweptVolume none = new SweptVolume(classNoteCamera(), new Vector3f(), -1.0F);
		SweptVolume zero = new SweptVolume(classNoteCamera(), new Vector3f(), 0.0F);

		// Straight behind the eye: outside the plain frustum, and only a safe zone keeps it.
		assertFalse(none.testAab(-1, -1, 1, 1, 1, 3));
		assertFalse(zero.testAab(-1, -1, 1, 1, 1, 3), "a cube of side nought holds only the origin");
		assertTrue(zero.testAab(-1, -1, -1, 1, 1, 1), "and a box that covers the origin reaches it");
		assertTrue(zero.testAab(-1, -1, 0, 1, 1, 3), "even one whose only contact is the origin's plane");
		assertFalse(none.testAab(-1, -1, 0, 1, 1, 3), "which nothing else keeps, behind the eye");
		assertEquals(FrustumIntersection.INSIDE, zero.intersectAab(0, 0, 0, 0, 0, 0));
	}

	// -- ugly numbers ------------------------------------------------------------------------

	@Test
	void aLightThatIsNotANumberLeavesNoPlanesAndKeepsEverything() {
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(Float.NaN, 0, 0), -1.0F);

		assertTrue(point(volume, 0, 0, 1000), "behind the eye, far past everything");
		assertTrue(point(volume, 1.0E9F, 0, 0));
		assertEquals(FrustumIntersection.INSIDE, volume.intersectAab(-1, -1, -1, 1, 1, 1));
	}

	@Test
	void aCameraMatrixOfNaNsKeepsEverythingRatherThanDroppingIt() {
		Matrix4f broken = new Matrix4f().set(new float[16]);
		broken.m00(Float.NaN);
		SweptVolume volume = new SweptVolume(broken, new Vector3f(0, 1, 0), -1.0F);

		assertTrue(point(volume, 3, 4, 5));
		assertTrue(volume.testAab(-1, -1, -1, 1, 1, 1));
	}

	@Test
	void aBoxWithNanCoordinatesIsKeptByTheTestAndIsNotOutside() {
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(0, 1, 0), -1.0F);

		// Every comparison against NaN is false, so no plane rejects it: the walk keeps what it cannot
		// place, which costs a draw and not a caster.
		assertTrue(volume.testAab(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN));
	}

	@Test
	void hugeBoxesAndInfinitiesDoNotThrowAndKeepTheWholeVolume() {
		SweptVolume volume = new SweptVolume(classNoteCamera(), new Vector3f(0, 1, 0), -1.0F);

		assertTrue(volume.testAab(-1.0E30F, -1.0E30F, -1.0E30F, 1.0E30F, 1.0E30F, 1.0E30F));
		assertTrue(volume.testAab(Float.NEGATIVE_INFINITY, -1, -100, Float.POSITIVE_INFINITY, 1, -50));
		assertFalse(volume.testAab(-1, -1, 1000, 1, 1, 2000), "behind the eye and out of the light's path");
	}

	@Test
	void theScratchVectorsAreSharedButTwoVolumesDoNotDisturbEachOther() {
		SweptVolume up = new SweptVolume(classNoteCamera(), new Vector3f(0, 1, 0), -1.0F);
		SweptVolume side = new SweptVolume(classNoteCamera(), new Vector3f(1, 0, 0), -1.0F);

		// Built one after the other over the same static scratch; each keeps its own planes.
		assertTrue(point(up, 0, 500, -20));
		assertFalse(point(up, 500, 0, -20));
		assertTrue(point(side, 500, 0, -20));
		assertFalse(point(side, 0, 500, -20));
	}
}
