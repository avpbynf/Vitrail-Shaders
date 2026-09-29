package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link ClipSpace} to an independent projection implementation: the OpenGL matrix a pack is
 * written against, the reversed Z matrix over zero to one the game rasterises with, and the depth a
 * pack reads back out of the one and linearises with the textbook inverse.
 * <p>
 * Everything is worked out in double precision from the plane distances alone, then rounded to the
 * floats the game would hold. The bounds on what survives the float pipeline are derived, not
 * fitted: a window depth near one is held to a spacing of 2^-24, and a distance is recovered from it
 * to no better than that spacing divided by the slope of depth against distance. That slope is the
 * whole story of why the converted depth is no more precise than the reference's, and the tests say
 * so in numbers.
 */
class ClipSpaceTest {

	private static final int COL = 4;

	/** The plane distances and lens of one projection, and the matrices worked out from them. */
	private record Lens(double near, double far, double fovDegrees, double aspect) {

		/** OpenGL: the near plane at minus one, the far plane at one. Column major, [column * 4 + row]. */
		double[] opengl() {
			double f = 1.0 / Math.tan(Math.toRadians(this.fovDegrees) / 2.0);
			double[] m = new double[16];
			m[0] = f / this.aspect;
			m[COL + 1] = f;
			m[2 * COL + 2] = -(this.far + this.near) / (this.far - this.near);
			m[2 * COL + 3] = -1.0;
			m[3 * COL + 2] = -2.0 * this.far * this.near / (this.far - this.near);

			return m;
		}

		/** What the game draws with: planes swapped, zero to one, so the near plane is at one. */
		double[] reversed() {
			double f = 1.0 / Math.tan(Math.toRadians(this.fovDegrees) / 2.0);
			double[] m = new double[16];
			m[0] = f / this.aspect;
			m[COL + 1] = f;
			m[2 * COL + 2] = this.near / (this.far - this.near);
			m[2 * COL + 3] = -1.0;
			m[3 * COL + 2] = this.far * this.near / (this.far - this.near);

			return m;
		}

		/** The depth of a point {@code d} in front of the eye under the reversed matrix: 1 near, 0 far. */
		double reversedDepth(double d) {
			return this.near * (this.far - d) / ((this.far - this.near) * d);
		}

		/** The same under OpenGL, in normalised device coordinates. */
		double openglNdc(double d) {
			return (this.far + this.near) / (this.far - this.near)
					- 2.0 * this.far * this.near / ((this.far - this.near) * d);
		}

		/** The textbook inverse: the distance a window depth in zero to one stands for under OpenGL. */
		double distance(double window) {
			return 2.0 * this.near * this.far
					/ (this.far + this.near - (2.0 * window - 1.0) * (this.far - this.near));
		}

		/** How fast the OpenGL window depth moves with distance, per block, at distance {@code d}. */
		double slope(double d) {
			return this.far * this.near / ((this.far - this.near) * d * d);
		}
	}

	private static final Lens[] LENSES = {
			new Lens(0.05, 1024.0, 70.0, 16.0 / 9.0),
			new Lens(0.05, 4096.0, 90.0, 16.0 / 9.0),
			new Lens(0.01, 100_000.0, 30.0, 4.0 / 3.0),
			new Lens(0.3, 512.0, 110.0, 1.0),
			new Lens(0.001, 1_000_000.0, 70.0, 2.0),
			new Lens(1.0, 16.0, 60.0, 1.5),
			new Lens(0.05, 64.0, 70.0, 16.0 / 9.0) };

	private static Matrix4f matrix(double[] entries) {
		float[] floats = new float[16];
		for (int i = 0; i < 16; i++) {
			floats[i] = (float) entries[i];
		}

		return new Matrix4f().set(floats);
	}

	private static double[] entries(Matrix4f m) {
		float[] floats = m.get(new float[16]);
		double[] entries = new double[16];
		for (int i = 0; i < 16; i++) {
			entries[i] = floats[i];
		}

		return entries;
	}

	/** {@code a * b} for two column major matrices, in double precision. */
	private static double[] multiply(double[] a, double[] b) {
		double[] product = new double[16];
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				double sum = 0.0;
				for (int k = 0; k < 4; k++) {
					sum += a[k * COL + row] * b[column * COL + k];
				}

				product[column * COL + row] = sum;
			}
		}

		return product;
	}

	/** Clip coordinates of a point, then the depth the rasteriser keeps: clip z over clip w. */
	private static double depth(double[] m, double x, double y, double z) {
		double clipZ = m[2] * x + m[COL + 2] * y + m[2 * COL + 2] * z + m[3 * COL + 2];
		double clipW = m[3] * x + m[COL + 3] * y + m[2 * COL + 3] * z + m[3 * COL + 3];

		return clipZ / clipW;
	}

	@Test
	void turnsTheReversedMatrixIntoTheOpenGLOneEntryByEntry() {
		for (Lens lens : LENSES) {
			Matrix4f converted = ClipSpace.toLegacyDepth(matrix(lens.reversed()), new Matrix4f());
			double[] expected = lens.opengl();
			double[] actual = entries(converted);

			for (int i = 0; i < 16; i++) {
				assertEquals(expected[i], actual[i], 1.0e-6 * Math.abs(expected[i]) + 1.0e-30,
						lens + " entry " + i);
			}
		}
	}

	@Test
	void ourReferenceMatricesAreTheOnesJomlBuildsForTheSameLens() {
		// Held to something outside this file: the reversed matrix is JOML's zero to one perspective
		// with the planes swapped, which is how the game builds it, and the other is JOML's OpenGL one.
		for (Lens lens : LENSES) {
			float fov = (float) Math.toRadians(lens.fovDegrees());
			Matrix4f reversed = new Matrix4f().perspective(fov, (float) lens.aspect(),
					(float) lens.far(), (float) lens.near(), true);
			Matrix4f opengl = new Matrix4f().perspective(fov, (float) lens.aspect(),
					(float) lens.near(), (float) lens.far(), false);

			double[] ours = lens.reversed();
			double[] joml = entries(reversed);
			double[] oursGl = lens.opengl();
			double[] jomlGl = entries(opengl);
			for (int i = 0; i < 16; i++) {
				assertEquals(ours[i], joml[i], 1.0e-5 * Math.abs(ours[i]) + 1.0e-30, lens + " reversed " + i);
				assertEquals(oursGl[i], jomlGl[i], 1.0e-5 * Math.abs(oursGl[i]) + 1.0e-30, lens + " opengl " + i);
			}
		}
	}

	@Test
	void leavesTheXYAndWRowsAloneAndReplacesTheZRowWithWMinusTwoZ() {
		Matrix4f source = new Matrix4f().set(new float[] {
				1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 });
		Matrix4f dest = new Matrix4f();

		ClipSpace.toLegacyDepth(source, dest);

		// Column major: the numbers 1..16 fill column 0 first. Row 2 is 3, 7, 11, 15 and row 3 is
		// 4, 8, 12, 16, so the new row 2 is 4 - 6, 8 - 14, 12 - 22, 16 - 30.
		assertEquals(new Matrix4f().set(new float[] {
				1, 2, -2, 4, 5, 6, -6, 8, 9, 10, -10, 12, 13, 14, -14, 16 }), dest);
		assertEquals(3.0F, source.m02(), "the source is not written");
	}

	@Test
	void convertsAMatrixOntoItself() {
		Matrix4f in = matrix(LENSES[0].reversed());
		Matrix4f apart = ClipSpace.toLegacyDepth(in, new Matrix4f());
		Matrix4f inPlace = new Matrix4f(in);

		Matrix4f returned = ClipSpace.toLegacyDepth(inPlace, inPlace);

		assertEquals(apart, inPlace);
		assertSame(inPlace, returned, "the destination is what comes back");
	}

	@Test
	void mapsTheNearPlaneToMinusOneAndTheFarPlaneToOne() {
		// The float matrix entries are each off by up to 2^-24 relative, which is a few times 1e-7 in
		// the depth; 1e-5 is a wide margin over that and a narrow one against a wrong convention.
		for (Lens lens : LENSES) {
			double[] converted = entries(ClipSpace.toLegacyDepth(matrix(lens.reversed()), new Matrix4f()));

			assertEquals(-1.0, depth(converted, 0, 0, -lens.near()), 1.0e-5, lens + " near");
			assertEquals(1.0, depth(converted, 0, 0, -lens.far()), 1.0e-5, lens + " far");
			assertEquals(0.0, depth(converted, 0, 0, -lens.distance(0.5)), 1.0e-5, lens + " middle of the window");
		}
	}

	@Test
	void agreesWithTheIndependentOpenGLProjectionAtEveryDistance() {
		for (Lens lens : LENSES) {
			double[] converted = entries(ClipSpace.toLegacyDepth(matrix(lens.reversed()), new Matrix4f()));

			for (int step = 0; step <= 200; step++) {
				// From the near plane to the far one, evenly in the logarithm of the distance.
				double d = lens.near() * Math.pow(lens.far() / lens.near(), step / 200.0);

				assertEquals(lens.openglNdc(d), depth(converted, 0.0, 0.0, -d), 5.0e-7, lens + " at distance " + d);
			}
		}
	}

	@Test
	void theCorrectConversionIsNotTheOppositeOneThatCirculates() {
		// 2 * rowZ - rowW is the same swap the wrong way round: it sends the near plane to +1 and
		// the far plane to -1, a depth buffer read upside down. The bounds above tell the two apart.
		Lens lens = LENSES[0];
		double[] rendered = lens.reversed();
		double[] wrong = rendered.clone();
		for (int column = 0; column < 4; column++) {
			wrong[column * COL + 2] = 2.0 * rendered[column * COL + 2] - rendered[column * COL + 3];
		}

		assertEquals(1.0, depth(wrong, 0, 0, -lens.near()), 1.0e-6);
		assertEquals(-1.0, depth(wrong, 0, 0, -lens.far()), 1.0e-6);
	}

	@Test
	void theWindowDepthAPackReadsIsTheGamesDepthPutThroughTheReadPair() {
		// The pair is (clipA, clipB, readA, readB): a legacy clip z becomes clipA * z + clipB * w in the
		// target, and a depth read back becomes readA * depth + readB. The composition must be the
		// legacy window depth (z + 1) / 2, whichever convention the target has.
		for (float[] pair : new float[][] { toArray(ClipSpace.REVERSED), toArray(ClipSpace.FORWARD) }) {
			for (double z : new double[] { -1.0, -0.5, 0.0, 0.3, 1.0 }) {
				double target = pair[0] * z + pair[1];
				double read = pair[2] * target + pair[3];

				assertEquals((z + 1.0) / 2.0, read, 1.0e-12, "pair " + Arrays.toString(pair) + " at " + z);
			}
		}
	}

	private static float[] toArray(Vector4f v) {
		return new float[] { v.x, v.y, v.z, v.w };
	}

	@Test
	void theTwoConventionsAreTheOnesTheGameAndOurTargetsUse() {
		assertEquals(new Vector4f(-0.5F, 0.5F, -1.0F, 1.0F), ClipSpace.REVERSED);
		assertEquals(new Vector4f(0.5F, 0.5F, 1.0F, 0.0F), ClipSpace.FORWARD);
	}

	@Test
	void theGamesReversedDepthIsTheReadPairAppliedToTheLegacyDepth() {
		// Under the reversed matrix a point's depth is clipA * legacy ndc + clipB with the reversed
		// pair: one identity ties the matrix the game draws with to the matrix we publish.
		for (Lens lens : LENSES) {
			for (int step = 0; step <= 50; step++) {
				double d = lens.near() * Math.pow(lens.far() / lens.near(), step / 50.0);

				assertEquals(lens.reversedDepth(d),
						ClipSpace.REVERSED.x * lens.openglNdc(d) + ClipSpace.REVERSED.y, 1.0e-12, lens + " at " + d);
			}
		}
	}

	@Test
	void keepsWhatTheWalkBobAndTheTiltMultiplyOnTheRight() {
		// The conversion is a multiplication on the left by a matrix that changes only the z row, so
		// it commutes with whatever the game applies to the right: converting the composed matrix is
		// converting the projection and then composing.
		Random random = new Random(7);
		for (Lens lens : LENSES) {
			double[] projection = entries(matrix(lens.reversed()));
			double[] bob = bob(random);

			double[] composed = entries(matrix(multiply(projection, bob)));
			double[] convertedThenComposed = multiply(
					entries(ClipSpace.toLegacyDepth(matrix(projection), new Matrix4f())), entries(matrix(bob)));
			double[] composedThenConverted = entries(ClipSpace.toLegacyDepth(matrix(composed), new Matrix4f()));

			for (int i = 0; i < 16; i++) {
				assertEquals(convertedThenComposed[i], composedThenConverted[i],
						1.0e-5 * Math.abs(convertedThenComposed[i]) + 1.0e-5, lens + " entry " + i);
			}
		}
	}

	/** A small rotation about x and z and a small translation, the shape of a walk bob and a tilt. */
	private static double[] bob(Random random) {
		double ax = Math.toRadians(random.nextDouble(-2.0, 2.0));
		double az = Math.toRadians(random.nextDouble(-2.0, 2.0));
		double[] rx = { 1, 0, 0, 0, 0, Math.cos(ax), Math.sin(ax), 0, 0, -Math.sin(ax), Math.cos(ax), 0, 0, 0, 0, 1 };
		double[] rz = { Math.cos(az), Math.sin(az), 0, 0, -Math.sin(az), Math.cos(az), 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
		double[] t = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, random.nextDouble(-0.1, 0.1),
				random.nextDouble(-0.1, 0.1), random.nextDouble(-0.1, 0.1), 1 };

		return multiply(multiply(rx, rz), t);
	}

	@Test
	void recoversTheDistanceThroughTheFloatPipelineToWithinWhatAFloatNearOneCanHold() {
		// The whole pipeline in the precision the game and the pack work in. The game keeps the
		// reversed depth as a float; the pack reads readA * depth + readB, which is one minus it; the
		// pack linearises. Three roundings of at most 2^-24 each reach the window depth (the matrix
		// entries, the stored depth, the subtraction), and a window error comes back as a distance
		// error of that much divided by the slope of depth against distance. That is the bound.
		//
		// It is only a bound where it is smaller than the distance itself. Past that the window
		// depth no longer says where the surface is, which is what a lens with a near plane of a
		// millimetre does to everything beyond about 1400 blocks.
		for (Lens lens : LENSES) {
			double[] rendered = entries(matrix(lens.reversed()));

			int checked = 0;
			float previous = 0.0F;
			for (int step = 0; step <= 300; step++) {
				double d = lens.near() * Math.pow(lens.far() / lens.near(), step / 300.0);

				float stored = (float) depth(rendered, 0, 0, -d);
				float window = ClipSpace.REVERSED.z * stored + ClipSpace.REVERSED.w;
				assertTrue(window >= previous, lens + ": the window depth never falls as the distance grows");
				previous = window;

				double bound = 3.0 * Math.scalb(1.0, -24) / lens.slope(d);
				if (bound < 0.25 * d) {
					assertEquals(d, lens.distance(window), bound, lens + " at distance " + d);
					checked++;
				}
			}

			assertTrue(checked > 100, lens + " resolved only " + checked + " of 301 distances");
		}
	}

	@Test
	void theFarPlaneReadsBackAsExactlyOneSoAPacksSkyTestHolds() {
		for (Lens lens : LENSES) {
			double[] rendered = entries(matrix(lens.reversed()));

			float stored = (float) depth(rendered, 0, 0, -lens.far());
			float window = ClipSpace.REVERSED.z * stored + ClipSpace.REVERSED.w;

			assertEquals(1.0F, window, lens + ": the far plane is the sky, and a sky test is depth >= 1.0");
		}
	}

	@Test
	void surfacesWithinAFractionOfABlockOfTheFarPlaneAlsoReadAsTheSky() {
		// The price of the conversion, in blocks: the depth held is one minus a small number, and a
		// float cannot hold 1 - 3e-8. At the default lens that is 0.6 of a block from the far plane;
		// 0.3 of a block reads as exactly one, a block and a half does not.
		Lens lens = LENSES[0];
		double[] rendered = entries(matrix(lens.reversed()));

		float atThirdOfABlock = ClipSpace.REVERSED.z * (float) depth(rendered, 0, 0, -(lens.far() - 0.3))
				+ ClipSpace.REVERSED.w;
		float atOneAndAHalf = ClipSpace.REVERSED.z * (float) depth(rendered, 0, 0, -(lens.far() - 1.5))
				+ ClipSpace.REVERSED.w;

		assertEquals(1.0F, atThirdOfABlock);
		assertTrue(atOneAndAHalf < 1.0F);
		assertTrue(atOneAndAHalf > 1.0F - 1.0e-6F);
	}

	@Test
	void twoReversedDepthsApartByLessThanHalfAnUlpOfOneConvertToTheSameLegacyDepth() {
		// Sub-ulp: 1e-9 and 2e-9 are different floats in the game's depth buffer (that is what
		// reversed Z buys) and the same 1.0 to a pack.
		float near = 1.0e-9F;
		float far = 2.0e-9F;
		assertNotEquals(near, far);

		float readNear = ClipSpace.REVERSED.z * near + ClipSpace.REVERSED.w;
		float readFar = ClipSpace.REVERSED.z * far + ClipSpace.REVERSED.w;

		assertEquals(1.0F, readNear);
		assertEquals(readNear, readFar);
	}

	@Test
	void theConvertedDepthResolvesFewerDistancesThanTheReversedOneBySeveralOrdersOfMagnitude() {
		// The documented limit, measured: a pack's own linearisation is no more precise than the
		// reference's. A hundred blocks at a thousand out, sampled every twentieth of a block: the
		// game's depth tells every sample apart, and what a pack reads is one of roughly ninety.
		Lens lens = LENSES[0];
		double[] rendered = entries(matrix(lens.reversed()));

		Set<Float> game = new HashSet<>();
		Set<Float> pack = new HashSet<>();
		for (int i = 0; i <= 2000; i++) {
			float stored = (float) depth(rendered, 0, 0, -(900.0 + i * 0.05));
			game.add(stored);
			pack.add(ClipSpace.REVERSED.z * stored + ClipSpace.REVERSED.w);
		}

		assertEquals(2001, game.size());

		// The window spans lens.slope-integrated: fn / (f - n) * (1/900 - 1/1000) = 5.56e-6, which is
		// 93.2 floats of 2^-24; the samples can occupy at most that many plus the end.
		double span = lens.far() * lens.near() / (lens.far() - lens.near()) * (1.0 / 900.0 - 1.0 / 1000.0);
		double spacing = Math.scalb(1.0, -24);
		assertTrue(pack.size() <= Math.ceil(span / spacing) + 2, "a pack sees " + pack.size());
		assertTrue(pack.size() >= span / spacing - 2, "a pack sees " + pack.size());
	}

	// distantDepth

	/** The game's projection and one Distant Horizons draws its far terrain with, both reversed. */
	private record Volumes(Lens game, double dhNear, double dhFar) {

		Matrix4f rendered() {
			return matrix(this.game.reversed());
		}

		float scale() {
			return (float) (this.dhNear / (this.dhFar - this.dhNear));
		}

		float offset() {
			return (float) (this.dhFar * this.dhNear / (this.dhFar - this.dhNear));
		}

		double dhDepth(double d) {
			return this.dhNear * (this.dhFar - d) / ((this.dhFar - this.dhNear) * d);
		}
	}

	private static final Volumes[] VOLUMES = {
			new Volumes(new Lens(0.05, 1024.0, 70.0, 16.0 / 9.0), 0.5, 8192.0),
			new Volumes(new Lens(0.05, 256.0, 90.0, 16.0 / 9.0), 4.0, 65_536.0),
			new Volumes(new Lens(0.3, 2048.0, 50.0, 1.0), 16.0, 32_768.0) };

	@Test
	void carriesADepthTheGameRasterisedIntoTheVolumeTheFarTerrainIsDrawnIn() {
		for (Volumes volumes : VOLUMES) {
			Vector2f pair = new Vector2f();
			assertTrue(ClipSpace.distantDepth(volumes.rendered(), volumes.scale(), volumes.offset(), pair));

			for (int step = 0; step <= 200; step++) {
				double d = volumes.game().near() * Math.pow(volumes.game().far() / volumes.game().near(), step / 200.0);
				double expected = volumes.dhDepth(d);
				double actual = pair.x * volumes.game().reversedDepth(d) + pair.y;

				assertEquals(expected, actual, 2.0e-6 * (1.0 + Math.abs(expected)), volumes + " at " + d);
			}
		}
	}

	@Test
	void theAffineMapHoldsForAPlayerWhoseViewIsBobbing() {
		// The bob multiplies both projections on the right by the same matrix, so what stands where
		// the eye distance stood is the bobbed distance, the same number for both.
		Random random = new Random(99);
		for (Volumes volumes : VOLUMES) {
			Vector2f pair = new Vector2f();
			assertTrue(ClipSpace.distantDepth(volumes.rendered(), volumes.scale(), volumes.offset(), pair));

			double[] game = volumes.game().reversed();
			double[] dh = game.clone();
			dh[2 * COL + 2] = volumes.scale();
			dh[3 * COL + 2] = volumes.offset();

			for (int i = 0; i < 200; i++) {
				double[] bob = bob(random);
				double[] gameSeen = multiply(game, bob);
				double[] dhSeen = multiply(dh, bob);

				double x = random.nextDouble(-50.0, 50.0);
				double y = random.nextDouble(-50.0, 50.0);
				double z = -random.nextDouble(60.0, volumes.game().far());

				double expected = depth(dhSeen, x, y, z);
				double actual = pair.x * depth(gameSeen, x, y, z) + pair.y;

				assertEquals(expected, actual, 4.0e-6 * (1.0 + Math.abs(expected)), volumes + " " + i);
			}
		}
	}

	@Test
	void answersNoWhenThereIsNoVolumeToConvertTo() {
		Vector2f pair = new Vector2f(7.0F, 8.0F);
		Volumes volumes = VOLUMES[0];

		assertFalse(ClipSpace.distantDepth(volumes.rendered(), volumes.scale(), 0.0F, pair), "no frame drawn");
		assertEquals(new Vector2f(7.0F, 8.0F), pair, "and the destination is left alone");
	}

	@Test
	void answersNoWhenTheProjectionCarriesTermsTheDerivationHasNoPlaceFor() {
		Volumes volumes = VOLUMES[0];
		String[] which = { "no perspective in z", "z depends on x", "z depends on y", "w row not the perspective",
				"w has a constant term" };
		Matrix4f[] bad = new Matrix4f[5];
		for (int i = 0; i < bad.length; i++) {
			bad[i] = volumes.rendered();
		}

		bad[0].m32(0.0F);
		bad[1].m02(0.01F);
		bad[2].m12(0.01F);
		bad[3].m23(-0.5F);
		bad[4].m33(0.25F);

		for (int i = 0; i < bad.length; i++) {
			Vector2f pair = new Vector2f(7.0F, 8.0F);

			assertFalse(ClipSpace.distantDepth(bad[i], volumes.scale(), volumes.offset(), pair), which[i]);
			assertEquals(new Vector2f(7.0F, 8.0F), pair, which[i] + ": destination left alone");
		}
	}

	@Test
	void answersNoForAProjectionThatHasTheBobMultipliedIntoIt() {
		// The composed matrix has the bob's terms in its z row, which is exactly the frame the class
		// says it cannot convert.
		Volumes volumes = VOLUMES[0];
		double[] composed = multiply(volumes.game().reversed(), bob(new Random(3)));

		assertFalse(ClipSpace.distantDepth(matrix(composed), volumes.scale(), volumes.offset(), new Vector2f()));
	}

	@Test
	void theIdentityPairSendsEveryDepthToItselfWhenBothVolumesAreOneVolume() {
		// The volumes are the same when the far terrain's row is the game's own: scale = m22, offset =
		// m32. The map is then depth -> 1 * depth + 0, and nothing is ever moved.
		Lens lens = VOLUMES[0].game();
		Matrix4f rendered = matrix(lens.reversed());
		Vector2f pair = new Vector2f();

		assertTrue(ClipSpace.distantDepth(rendered, rendered.m22(), rendered.m32(), pair));

		assertEquals(1.0F, pair.x);
		assertEquals(0.0F, pair.y, 1.0e-30);
	}
}
