package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.FakeWorld;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link CameraValues} to which position each name carries, which is not a detail: the two
 * a pack differences are the SHIFTED ones, and the integer and fractional splits are the UNSHIFTED
 * ones, because their purpose is to carry a real world coordinate at full precision.
 * <p>
 * The world here keeps the two apart by a thousand blocks in x and two thousand in z, so a name
 * that reads the other one's cannot pass.
 */
class CameraValuesTest {

	private final UniformCatalog engine = UniformCatalog.engine();

	/** A camera at an exactly representable position, shifted by (1000, 0, 2000) from where it stands. */
	private static FakeWorld world() {
		FakeWorld world = new FakeWorld();
		world.cameraPositionUnshifted.set(12345.75, -0.25, -1234567.125);
		world.cameraPosition.set(12345.75 - 1000.0, -0.25, -1234567.125 + 2000.0);
		world.previousCameraPositionUnshifted.set(12344.5, 64.5, -1234566.875);
		world.previousCameraPosition.set(12344.5 - 1000.0, 64.5, -1234566.875 + 2000.0);
		world.eyePosition.set(12343.25, 65.625, -1234571.875);

		return world;
	}

	private float[] floats(String name, FakeWorld world, int rank) {
		return ValueReads.floats(ValueReads.read(this.engine, name, world), rank);
	}

	private int[] ints(String name, FakeWorld world, int rank) {
		return ValueReads.ints(ValueReads.read(this.engine, name, world), rank);
	}

	@Test
	void theTwoPositionsAPackDifferencesAreTheShiftedOnes() {
		FakeWorld world = world();

		assertArrayEquals(new float[] { 11345.75F, -0.25F, -1232567.125F }, floats("cameraPosition", world, 3));
		assertArrayEquals(new float[] { 11344.5F, 64.5F, -1232566.875F }, floats("previousCameraPosition", world, 3));
	}

	@Test
	void theAltitudeIsTheHeightOfTheShiftedPositionWhichIsTheRawOne() {
		FakeWorld world = world();

		assertEquals(-0.25F, ValueReads.read(this.engine, "eyeAltitude", world).f(0));
		assertEquals(UniformShape.FLOAT, this.engine.natural("eyeAltitude"));
	}

	@Test
	void theIntegerAndFractionalSplitsAreTheUnshiftedPositionAtFullPrecision() {
		FakeWorld world = world();

		// Floor, not truncation: -0.25 is -1 and 0.75, and -1234567.125 is -1234568 and 0.875.
		assertArrayEquals(new int[] { 12345, -1, -1234568 }, ints("cameraPositionInt", world, 3));
		assertArrayEquals(new float[] { 0.75F, 0.75F, 0.875F }, floats("cameraPositionFract", world, 3));
		assertArrayEquals(new int[] { 12344, 64, -1234567 }, ints("previousCameraPositionInt", world, 3));
		assertArrayEquals(new float[] { 0.5F, 0.5F, 0.125F }, floats("previousCameraPositionFract", world, 3));
	}

	@Test
	void theSplitKeepsWhatAFloatCannotHoldOfAPositionFarFromTheOrigin() {
		FakeWorld world = new FakeWorld();
		world.cameraPositionUnshifted.set(29_999_999.5, 100.0, -29_999_998.75);

		assertArrayEquals(new int[] { 29_999_999, 100, -29_999_999 }, ints("cameraPositionInt", world, 3));
		assertArrayEquals(new float[] { 0.5F, 0.0F, 0.25F }, floats("cameraPositionFract", world, 3));

		// A float holds 29999999.5 as 30000000, which is what the split exists to avoid.
		assertEquals(30_000_000.0F, (float) 29_999_999.5);
	}

	@Test
	void theIntegerSplitAndTheFractionAddBackToThePosition() {
		FakeWorld world = new FakeWorld();
		double[] xs = { 0.0, 0.5, -0.5, 1.0, -1.0, 123.456, -123.456, 29_999_999.99, -29_999_999.99 };

		for (double x : xs) {
			world.cameraPositionUnshifted.set(x, x, x);

			int whole = ints("cameraPositionInt", world, 3)[0];
			float fraction = floats("cameraPositionFract", world, 3)[0];

			assertEquals(x, whole + (double) fraction, 1.0e-6, "x " + x);
			assertTrue(fraction >= 0.0F && fraction < 1.0F, "the fraction of " + x + " is " + fraction);
		}
	}

	@Test
	void pastTheRangeOfAnIntegerTheSplitSaturatesAsACastDoes() {
		FakeWorld world = new FakeWorld();
		world.cameraPositionUnshifted.set(3.0e9, -3.0e9, 0.0);

		assertArrayEquals(new int[] { Integer.MAX_VALUE, Integer.MIN_VALUE, 0 }, ints("cameraPositionInt", world, 3));
	}

	@Test
	void theEyeIsWhereTheEyeIsAndTheRelativePositionIsTheCameraMinusIt() {
		FakeWorld world = world();

		assertArrayEquals(new float[] { 12343.25F, 65.625F, -1234571.875F }, floats("eyePosition", world, 3));
		// Camera minus eye, unshifted, in double precision: (12345.75 - 12343.25, -0.25 - 65.625,
		// -1234567.125 + 1234571.875). The other way round is a sign error visible only in third person.
		assertArrayEquals(new float[] { 2.5F, -65.875F, 4.75F }, floats("relativeEyePosition", world, 3));
	}

	@Test
	void theRelativePositionDoesNotUseTheShiftedCamera() {
		FakeWorld world = world();
		world.cameraPosition.set(0.0, 0.0, 0.0);

		assertArrayEquals(new float[] { 2.5F, -65.875F, 4.75F }, floats("relativeEyePosition", world, 3));
	}

	@Test
	void theRelativePositionKeepsThePrecisionOfTheSubtractionFarFromTheOrigin() {
		FakeWorld world = new FakeWorld();
		world.cameraPositionUnshifted.set(29_999_999.5, 70.0, 29_999_990.25);
		world.eyePosition.set(29_999_999.25, 71.5, 29_999_992.0);

		// Subtracted before the narrowing: floats would have lost the quarters.
		assertArrayEquals(new float[] { 0.25F, -1.5F, -1.75F }, floats("relativeEyePosition", world, 3));
	}

	@Test
	void theClipPlanesAreThePassThroughsTheyAreNamedAs() {
		FakeWorld world = new FakeWorld();
		world.near = 0.05F;
		world.far = 1024.0F;

		assertEquals(0.05F, ValueReads.read(this.engine, "near", world).f(0));
		assertEquals(1024.0F, ValueReads.read(this.engine, "far", world).f(0));
	}
}
