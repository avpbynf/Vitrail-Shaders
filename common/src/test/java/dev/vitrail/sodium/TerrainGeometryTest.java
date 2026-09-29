package dev.vitrail.sodium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The tangent frame of a chunk quad once it is seven floats, {@code (nx, ny, nz, tx, ty, tz, h)},
 * and the offset of a vertex from the middle of its block: the arithmetic that decides how every
 * normal map on the terrain is lit and where a pack that voxelises the world puts each block.
 * <p>
 * Expected values are worked by hand on the axes and on a 3-4-5 triangle, and everywhere else the
 * frame is held to what a frame is for: a unit tangent at a right angle to the unit normal, computed
 * again in double precision from the definition. Vectors are compared with a delta so that a negative
 * zero is a zero.
 */
class TerrainGeometryTest {

	private static final float NEAR = 1.0E-6F;

	private static float[] frame(float nx, float ny, float nz, float tx, float ty, float tz, float h) {
		return new float[] {nx, ny, nz, tx, ty, tz, h};
	}

	private static void assertVector(float x, float y, float z, float[] frame, int at, float delta,
			String what) {
		assertEquals(x, frame[at], delta, what + " x");
		assertEquals(y, frame[at + 1], delta, what + " y");
		assertEquals(z, frame[at + 2], delta, what + " z");
	}

	private static double length(float[] v, int at) {
		return Math.sqrt((double) v[at] * v[at] + (double) v[at + 1] * v[at + 1] + (double) v[at + 2] * v[at + 2]);
	}

	private static double dot(float[] frame) {
		return (double) frame[0] * frame[3] + (double) frame[1] * frame[4] + (double) frame[2] * frame[5];
	}

	private static float[] randomUnitNormal(Random random) {
		double x;
		double y;
		double z;
		double squared;
		do {
			x = random.nextGaussian();
			y = random.nextGaussian();
			z = random.nextGaussian();
			squared = x * x + y * y + z * z;
		} while (squared < 0.01);

		double length = Math.sqrt(squared);

		return new float[] {(float) (x / length), (float) (y / length), (float) (z / length)};
	}

	// -- normalise ---------------------------------------------------------------------------

	@Test
	void normaliseScalesAThreeFourFiveVectorToItsHandWorkedUnit() {
		float[] frame = frame(3, 4, 0, 9, 9, 9, 1);

		TerrainGeometry.normalise(frame, 0);

		assertVector(0.6F, 0.8F, 0.0F, frame, 0, 0.0F, "normal");
		assertVector(9, 9, 9, frame, 3, 0.0F, "tangent untouched");
		assertEquals(1.0F, frame[6]);
	}

	@Test
	void normaliseWorksOnTheSecondVectorOfTheFrameAndLeavesTheRest() {
		float[] frame = frame(1, 2, 3, 0, 5, 12, -1);

		TerrainGeometry.normalise(frame, 3);

		assertVector(1, 2, 3, frame, 0, 0.0F, "normal untouched");
		assertVector(0.0F, 5.0F / 13.0F, 12.0F / 13.0F, frame, 3, 0.0F, "tangent");
		assertEquals(-1.0F, frame[6]);
	}

	@Test
	void aVectorWithNoLengthBecomesTheUpAxisRatherThanANaN() {
		float[] frame = frame(0, 0, 0, 0, 0, 0, 1);

		TerrainGeometry.normalise(frame, 0);
		TerrainGeometry.normalise(frame, 3);

		assertVector(0, 1, 0, frame, 0, 0.0F, "normal");
		assertVector(0, 1, 0, frame, 3, 0.0F, "tangent");
	}

	@Test
	void aVectorShorterThanAThousandthOfAMillionthIsNoVectorAndOneJustLongerIsScaled() {
		float[] tiny = frame(-0.5E-9F, 0, 0, 0, 0, 0, 1);
		float[] small = frame(2.0E-9F, 0, 0, 0, 0, 0, 1);

		TerrainGeometry.normalise(tiny, 0);
		TerrainGeometry.normalise(small, 0);

		assertVector(0, 1, 0, tiny, 0, 0.0F, "below 1e-9");
		assertVector(1, 0, 0, small, 0, NEAR, "above 1e-9");
	}

	@Test
	void aNegativeVectorKeepsItsSignsAndAnyScaleComesOutAsUnitLength() {
		float[] frame = frame(-3, 0, -4, 1000, -2000, 2000, 1);

		TerrainGeometry.normalise(frame, 0);
		TerrainGeometry.normalise(frame, 3);

		assertVector(-0.6F, 0.0F, -0.8F, frame, 0, 0.0F, "normal");
		assertEquals(1.0, length(frame, 3), 1.0E-6);
		assertVector(1.0F / 3.0F, -2.0F / 3.0F, 2.0F / 3.0F, frame, 3, 1.0E-6F, "tangent");
	}

	@Test
	void aVectorWithANaNInItStaysNaNBecauseNothingGuardsForIt() {
		float[] frame = frame(Float.NaN, 0, 0, 0, 0, 0, 1);

		TerrainGeometry.normalise(frame, 0);

		assertTrue(Float.isNaN(frame[0]) && Float.isNaN(frame[1]) && Float.isNaN(frame[2]));
	}

	@Test
	void knownBug_normaliseOfAVectorWhoseSquaresOverflowAFloatComesBackAsNoVectorAtAll() {
		// The length is taken from float squares, so 1e30 squares to infinity and divides to zero.
		// Nothing meshed is anywhere near that big, so it is a weakness and not a case.
		float[] frame = frame(1.0E30F, 0, 0, 0, 0, 0, 1);

		TerrainGeometry.normalise(frame, 0);

		assertVector(0, 0, 0, frame, 0, 0.0F, "a zero, not (1, 0, 0)");
	}

	// -- handedness --------------------------------------------------------------------------

	@Test
	void handednessIsMinusTheSignOfTheTextureArea() {
		assertEquals(1.0F, TerrainGeometry.handedness(-1.0F));
		assertEquals(1.0F, TerrainGeometry.handedness(-Float.MIN_VALUE));
		assertEquals(-1.0F, TerrainGeometry.handedness(1.0F));
		assertEquals(-1.0F, TerrainGeometry.handedness(Float.MIN_VALUE));
		assertEquals(1.0F, TerrainGeometry.handedness(Float.NEGATIVE_INFINITY));
		assertEquals(-1.0F, TerrainGeometry.handedness(Float.POSITIVE_INFINITY));
	}

	@Test
	void handednessOfNothingAndOfNoNumberIsTheMinusOneOfAForwardMapping() {
		assertEquals(-1.0F, TerrainGeometry.handedness(0.0F));
		assertEquals(-1.0F, TerrainGeometry.handedness(-0.0F));
		assertEquals(-1.0F, TerrainGeometry.handedness(Float.NaN));
	}

	// -- perpendicular -----------------------------------------------------------------------

	@Test
	void perpendicularOfTheThreeAxesIsTheHandWorkedAxisAtARightAngle() {
		// |ny| < |nx| takes (-nz, 0, nx), otherwise (0, nz, -ny), both to unit length.
		float[] x = frame(1, 0, 0, 7, 7, 7, 1);
		float[] y = frame(0, 1, 0, 7, 7, 7, 1);
		float[] z = frame(0, 0, 1, 7, 7, 7, 1);

		TerrainGeometry.perpendicular(x);
		TerrainGeometry.perpendicular(y);
		TerrainGeometry.perpendicular(z);

		assertVector(0, 0, 1, x, 3, 0.0F, "x axis");
		assertVector(0, 0, -1, y, 3, 0.0F, "y axis");
		assertVector(0, 1, 0, z, 3, 0.0F, "z axis");
		assertEquals(1.0F, x[6], "the handedness is not touched");
	}

	@Test
	void perpendicularCrossesWithTheLessAlignedOfTheTwoAxesAndTiesGoToTheSecond() {
		float[] tilted = frame(0.6F, 0.0F, 0.8F, 0, 0, 0, 1);
		float[] steep = frame(0.0F, 0.6F, 0.8F, 0, 0, 0, 1);
		float[] tie = frame(0.6F, -0.6F, 0.52915F, 0, 0, 0, 1);

		TerrainGeometry.perpendicular(tilted);
		TerrainGeometry.perpendicular(steep);
		TerrainGeometry.perpendicular(tie);

		assertVector(-0.8F, 0.0F, 0.6F, tilted, 3, NEAR, "|ny| < |nx|");
		assertVector(0.0F, 0.8F, -0.6F, steep, 3, NEAR, "|ny| > |nx|");
		// (0, 0.52915, 0.6) has length 0.8, so the unit vector is (0, 0.661438, 0.75)
		assertVector(0.0F, 0.661438F, 0.75F, tie, 3, 1.0E-5F, "a tie is not upright, so (0, nz, -ny)");
	}

	@Test
	void perpendicularIsAlwaysAUnitVectorAtARightAngleToAnyUnitNormal() {
		Random random = new Random(0xF4A3E);
		for (int i = 0; i < 5000; i++) {
			float[] n = randomUnitNormal(random);
			float[] frame = frame(n[0], n[1], n[2], 0, 0, 0, 1);

			TerrainGeometry.perpendicular(frame);

			assertEquals(1.0, length(frame, 3), 1.0E-6, "unit for " + n[0] + "," + n[1] + "," + n[2]);
			assertEquals(0.0, dot(frame), 2.0E-6, "at a right angle to " + n[0] + "," + n[1] + "," + n[2]);
		}
	}

	@Test
	void perpendicularOfNoNormalIsTheUpAxisAndNeverAZero() {
		float[] frame = frame(0, 0, 0, 5, 5, 5, 1);

		TerrainGeometry.perpendicular(frame);

		assertVector(0, 1, 0, frame, 3, 0.0F, "no normal, so up");
	}

	// -- orthogonalise -----------------------------------------------------------------------

	@Test
	void aTangentAlreadyAtARightAngleIsLeftAlone() {
		float[] frame = frame(0, 0, 1, 1, 0, 0, -1);

		TerrainGeometry.orthogonalise(frame);

		assertVector(1, 0, 0, frame, 3, NEAR, "tangent");
		assertVector(0, 0, 1, frame, 0, 0.0F, "normal untouched");
		assertEquals(-1.0F, frame[6]);
	}

	@Test
	void aTangentLeaningOnTheNormalIsPulledFlatOntoTheNormalsPlane() {
		// n = +Z, t = (1, 0, 1) / sqrt 2 minus its Z part is +X. And n = +Y, t = (0.6, 0.8, 0): the
		// 0.8 along Y comes out and 0.6 is scaled to 1.
		float[] z = frame(0, 0, 1, 0.70710678F, 0, 0.70710678F, 1);
		float[] y = frame(0, 1, 0, 0.6F, 0.8F, 0, 1);

		TerrainGeometry.orthogonalise(z);
		TerrainGeometry.orthogonalise(y);

		assertVector(1, 0, 0, z, 3, NEAR, "n +Z");
		assertVector(1, 0, 0, y, 3, NEAR, "n +Y");
	}

	@Test
	void aTangentPointingAgainstTheNormalInTheirSharedPlaneIsFlattenedToo() {
		float[] frame = frame(0, 0, 1, -0.6F, 0, -0.8F, 1);

		TerrainGeometry.orthogonalise(frame);

		assertVector(-1, 0, 0, frame, 3, NEAR, "the -X that is left");
	}

	@Test
	void aTangentParallelToTheNormalTakesFrisvadsBasisAxisInstead() {
		// The branchless basis of Duff et al. at both poles and on the other two axes, worked by hand:
		// side = +1 or -1 by the sign of nz, scale = -1 / (side + nz), and (1 + side nx^2 scale,
		// side nx ny scale, -side nx).
		float[] north = frame(0, 0, 1, 0, 0, 3, 1);
		float[] south = frame(0, 0, -1, 0, 0, -2, 1);
		float[] xAxis = frame(1, 0, 0, 4, 0, 0, 1);
		float[] yAxis = frame(0, 1, 0, 0, 9, 0, 1);

		TerrainGeometry.orthogonalise(north);
		TerrainGeometry.orthogonalise(south);
		TerrainGeometry.orthogonalise(xAxis);
		TerrainGeometry.orthogonalise(yAxis);

		assertVector(1, 0, 0, north, 3, NEAR, "n +Z");
		assertVector(1, 0, 0, south, 3, NEAR, "n -Z");
		// nx = 1: side = +1, scale = -1 / (1 + 0) = -1, so (1 + 1 * 1 * -1, 0, -1) = (0, 0, -1)
		assertVector(0, 0, -1, xAxis, 3, NEAR, "n +X");
		// ny = 1: side = +1, scale = -1, (1, 0, 0)
		assertVector(1, 0, 0, yAxis, 3, NEAR, "n +Y");
	}

	@Test
	void theBasisIsUsedBelowTheFlattenedThresholdAndTheProjectionAboveIt() {
		// n = +Z. The leftover of t is (0, e, 0): with e = 1e-11 its square is 1e-22, under the 1e-20
		// threshold, so the basis answers (1, 0, 0); with e = 1e-8 the square is 1e-16, over it, and the
		// leftover is scaled to (0, 1, 0).
		float[] below = frame(0, 0, 1, 0, 1.0E-11F, 1, 1);
		float[] above = frame(0, 0, 1, 0, 1.0E-8F, 1, 1);

		TerrainGeometry.orthogonalise(below);
		TerrainGeometry.orthogonalise(above);

		assertVector(1, 0, 0, below, 3, NEAR, "below");
		assertVector(0, 1, 0, above, 3, NEAR, "above");
	}

	@Test
	void aZeroOrNaNTangentIsReplacedByTheBasisAndNeverKeptAsItIs() {
		float[] zero = frame(0, 0, 1, 0, 0, 0, 1);
		float[] nan = frame(0, 0, 1, Float.NaN, Float.NaN, Float.NaN, 1);

		TerrainGeometry.orthogonalise(zero);
		TerrainGeometry.orthogonalise(nan);

		assertVector(1, 0, 0, zero, 3, NEAR, "zero tangent");
		assertVector(1, 0, 0, nan, 3, NEAR, "a NaN fails the length test and takes the basis");
	}

	@Test
	void orthogonaliseKeepsTheHandednessAndTheNormal() {
		float[] frame = frame(0.36F, 0.48F, 0.8F, 1, 2, 3, -1);

		TerrainGeometry.orthogonalise(frame);

		assertEquals(-1.0F, frame[6]);
		assertVector(0.36F, 0.48F, 0.8F, frame, 0, 0.0F, "normal");
	}

	@Test
	void everyTangentEndsUnitLengthAtARightAngleToTheNormalAndMatchesTheDefinition() {
		Random random = new Random(0x0B7A5);
		for (int i = 0; i < 20_000; i++) {
			float[] n = randomUnitNormal(random);
			float[] t;
			double tolerance;
			if (i % 5 == 0) {
				// nearly parallel to the normal, a thousandth off it, where the leftover is small but
				// well over the rounding of the subtraction
				double scale = random.nextBoolean() ? 1.0 : -3.0;
				t = new float[] {(float) (n[0] * scale + random.nextGaussian() * 1.0E-3),
						(float) (n[1] * scale + random.nextGaussian() * 1.0E-3),
						(float) (n[2] * scale + random.nextGaussian() * 1.0E-3)};
				tolerance = 5.0E-3;
			} else {
				t = new float[] {(float) (random.nextGaussian() * 3), (float) (random.nextGaussian() * 3),
						(float) (random.nextGaussian() * 3)};
				tolerance = 5.0E-5;
			}

			float[] frame = frame(n[0], n[1], n[2], t[0], t[1], t[2], 1);
			TerrainGeometry.orthogonalise(frame);

			String at = "n " + n[0] + "," + n[1] + "," + n[2] + " t " + t[0] + "," + t[1] + "," + t[2];
			assertEquals(1.0, length(frame, 3), 2.0E-6, "unit, " + at);
			assertEquals(0.0, dot(frame), tolerance, "at a right angle, " + at);

			// The definition, in double precision: subtract the part along the normal.
			double along = (double) n[0] * t[0] + (double) n[1] * t[1] + (double) n[2] * t[2];
			double rx = t[0] - n[0] * along;
			double ry = t[1] - n[1] * along;
			double rz = t[2] - n[2] * along;
			double left = Math.sqrt(rx * rx + ry * ry + rz * rz);
			assertTrue(left > 1.0E-5, "the sample never sits on the collapse: " + at);
			assertEquals(rx / left, frame[3], tolerance, "definition x, " + at);
			assertEquals(ry / left, frame[4], tolerance, "definition y, " + at);
			assertEquals(rz / left, frame[5], tolerance, "definition z, " + at);
		}
	}

	@Test
	void knownBug_aTangentParallelToTheNormalUpToRoundingKeepsTheRoundingNoiseInsteadOfTheBasis() {
		// The collapse threshold is 1e-20 on the squared leftover, but subtracting n * (n . t) in
		// floats from a unit-sized t leaves noise of about 1e-8 (squared 1e-16), so a tangent that is
		// parallel to a normal off the axes is not seen to have collapsed. What it normalises is that
		// noise, and here it comes out along the normal itself instead of at a right angle to it.
		// A tangent is never parallel to the normal of a planar quad, so this needs a degenerate one.
		float[] frame = frame(-0.88510656F, -0.22966799F, -0.4047703F, 2.6553197F, 0.68900394F, 1.2143109F, 1);

		TerrainGeometry.orthogonalise(frame);

		assertEquals(1.0, length(frame, 3), 2.0E-6);
		assertTrue(Math.abs(dot(frame)) > 0.99, "the tangent that comes out leans on the normal: " + dot(frame));
	}

	// -- offset ------------------------------------------------------------------------------

	@Test
	void theOffsetOfAVertexFromTheMiddleOfItsBlockIsThirtyTwoMinusItsSixtyFourthsInTheBlock() {
		// A vertex at a fraction f of its block is 32 - 64 f sixty-fourths from the middle, exact for
		// every f on the sixty-fourths grid, and out to a whole block either side.
		for (int block = 0; block < 16; block++) {
			for (int sixtyFourths = -64; sixtyFourths <= 128; sixtyFourths++) {
				float vertex = block + sixtyFourths / 64.0F;

				assertEquals(32 - sixtyFourths, TerrainGeometry.offset(block, vertex),
						"block " + block + " vertex " + vertex);
			}
		}
	}

	@Test
	void theOffsetIsFlooredAndNotTruncatedNorRounded() {
		// Between two grid steps: 31.5 floors to 31, -31.5 to -32 (truncation would say -31, and a
		// round half up -31).
		assertEquals(31, TerrainGeometry.offset(3, 3.0078125F));
		assertEquals(-32, TerrainGeometry.offset(3, 3.9921875F));
		assertEquals(31, TerrainGeometry.offset(3, 3.0078126F - 1.0E-7F));
		assertEquals(-1, TerrainGeometry.offset(3, 3.5078125F), "-0.5 floors to -1, truncation says 0");
		assertEquals(0, TerrainGeometry.offset(3, 3.5F));
	}

	@Test
	void theOffsetOfANaNVertexIsNoOffsetAtAll() {
		assertEquals(0, TerrainGeometry.offset(5, Float.NaN));
	}

	@Test
	void theOffsetKeepsTheSignAndTheBlockAsTheOriginWordCarriesThem() {
		// Whatever block of the section the origin says, the same fraction gives the same offset.
		for (int block = 0; block < 16; block++) {
			assertEquals(TerrainGeometry.offset(0, 0.25F), TerrainGeometry.offset(block, block + 0.25F));
		}

		assertEquals(16, TerrainGeometry.offset(0, 0.25F));
	}
}
