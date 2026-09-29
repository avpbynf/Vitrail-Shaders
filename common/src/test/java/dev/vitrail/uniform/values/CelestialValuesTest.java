package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.FakeWorld;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.Val;

import java.util.Random;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Holds the sky, {@link CelestialValues}, to rotations worked out again in double precision from
 * their definitions, and to a handful of positions worked out by hand.
 * <p>
 * The unit of the reference is the one the class documents: the sun, the moon and the shadow light
 * are all {@code (0, 100, 0)} turned by the same three rotations (a fixed quarter turn about Y, the
 * pack's path rotation about Z, the sky angle about X), placed by the camera's model view; the
 * sun and moon are places (w of one) and the up direction and the End flash are directions (w of
 * nought). Each of the traps the class lists is a case here: the one step of wrapping in the angle a
 * pack reads, the moon that starts from the sun's vector, and an up that ignores the sky.
 */
class CelestialValuesTest {

	private static final double TOLERANCE = 1.0e-3;

	private final UniformCatalog engine = UniformCatalog.engine();

	// The reference: rotations by their definition, in double precision, right handed.

	private static double[][] rotationX(double degrees) {
		double c = Math.cos(Math.toRadians(degrees));
		double s = Math.sin(Math.toRadians(degrees));

		return new double[][] { { 1, 0, 0 }, { 0, c, -s }, { 0, s, c } };
	}

	private static double[][] rotationY(double degrees) {
		double c = Math.cos(Math.toRadians(degrees));
		double s = Math.sin(Math.toRadians(degrees));

		return new double[][] { { c, 0, s }, { 0, 1, 0 }, { -s, 0, c } };
	}

	private static double[][] rotationZ(double degrees) {
		double c = Math.cos(Math.toRadians(degrees));
		double s = Math.sin(Math.toRadians(degrees));

		return new double[][] { { c, -s, 0 }, { s, c, 0 }, { 0, 0, 1 } };
	}

	private static double[] apply(double[][] m, double[] v) {
		double[] out = new double[3];
		for (int row = 0; row < 3; row++) {
			out[row] = m[row][0] * v[0] + m[row][1] * v[1] + m[row][2] * v[2];
		}

		return out;
	}

	/** {@code a * b}, so that {@code b} is applied first. */
	private static double[][] product(double[][] a, double[][] b) {
		double[][] out = new double[3][3];
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				for (int k = 0; k < 3; k++) {
					out[row][column] += a[row][k] * b[k][column];
				}
			}
		}

		return out;
	}

	/** The sun's rotation: a quarter turn about Y, the path about Z, the angle about X, in that order. */
	private static double[] skyDirection(double path, double angle) {
		double[][] rotation = product(rotationY(-90.0), product(rotationZ(path), rotationX(angle)));

		return apply(rotation, new double[] { 0.0, 100.0, 0.0 });
	}

	private static double[] flashDirection(double xAngle, double yAngle) {
		double[][] rotation = product(rotationY(180.0 - yAngle), rotationX(-90.0 - xAngle));

		return apply(rotation, new double[] { 0.0, 100.0, 0.0 });
	}

	/** The model view, as a row major 4x4 with the rotation in the upper left and a translation at the right. */
	private record View(double[][] m) {

		/** Places a vector: the rotation always, the translation only when {@code w} is one. */
		double[] place(double[] v, double w) {
			double[] out = new double[3];
			for (int row = 0; row < 3; row++) {
				out[row] = this.m[row][0] * v[0] + this.m[row][1] * v[1] + this.m[row][2] * v[2] + this.m[row][3] * w;
			}

			return out;
		}

		void into(FakeWorld world) {
			float[] columns = new float[16];
			for (int column = 0; column < 4; column++) {
				for (int row = 0; row < 4; row++) {
					columns[column * 4 + row] = (float) this.m[row][column];
				}
			}

			world.gbufferModelView.set(columns);
		}
	}

	private static View view(double yaw, double pitch, double tx, double ty, double tz) {
		double[][] rotation = product(rotationX(pitch), rotationY(yaw));

		return new View(new double[][] {
				{ rotation[0][0], rotation[0][1], rotation[0][2], tx },
				{ rotation[1][0], rotation[1][1], rotation[1][2], ty },
				{ rotation[2][0], rotation[2][1], rotation[2][2], tz },
				{ 0, 0, 0, 1 } });
	}

	private static final View[] VIEWS = {
			view(0, 0, 0, 0, 0),
			view(37, -25, 0, 0, 0),
			view(-140, 60, 0, 0, 0),
			view(12, 8, 10, -4, 7) };

	private static void assertVector(double[] expected, Val actual, String what) {
		assertEquals(3, actual.rank(), what);
		for (int i = 0; i < 3; i++) {
			assertEquals(expected[i], actual.f(i), TOLERANCE, what + " component " + i);
		}
	}

	// the angle a pack reads

	@Test
	void theSunAngleIsTheRawAnglePlusNinetyWrappedOnceOverThreeHundredAndSixty() {
		// {raw attribute, expected in turns}. Nought is a quarter, and 270 is exactly one: the wrap
		// is a strict "past 360", so the discontinuity sits between 270 and 270.5 and not at 270.
		double[][] cases = { { 0, 90.0 / 360 }, { 90, 180.0 / 360 }, { 180, 270.0 / 360 }, { 270, 360.0 / 360 },
				{ 270.5, 0.5 / 360 }, { 271, 1.0 / 360 }, { 360, 90.0 / 360 }, { -90, 0.0 },
				{ -91, 359.0 / 360 }, { -100, 350.0 / 360 },
				// One step and not a modulus: the values a modulus would fold, this does not.
				{ 1000, 730.0 / 360 }, { -500, -50.0 / 360 } };

		for (double[] pair : cases) {
			FakeWorld world = new FakeWorld();
			world.sunAngleDegrees = (float) pair[0];

			assertEquals(pair[1], read("sunAngle", world).f(0), 1.0e-6, "raw " + pair[0]);
		}
	}

	@Test
	void theShadowAngleFollowsTheSunByDayAndTheMoonByNight() {
		FakeWorld world = new FakeWorld();
		world.moonAngleDegrees = 270.0F;

		// Day: the sun's own wrapped angle, the moon's ignored.
		world.sunAngleDegrees = 0.0F;
		assertEquals(0.25, read("shadowAngle", world).f(0), 1.0e-6);
		world.sunAngleDegrees = 89.99F;
		assertEquals(179.99 / 360, read("shadowAngle", world).f(0), 1.0e-6);
		world.sunAngleDegrees = -89.0F;
		assertEquals(1.0 / 360, read("shadowAngle", world).f(0), 1.0e-6, "the sun's angle of 1 is day");

		// Night starts where the wrapped sun angle reaches 180: the moon's angle, wrapped the same way.
		world.sunAngleDegrees = 90.0F;
		assertEquals(360.0 / 360, read("shadowAngle", world).f(0), 1.0e-6, "the moon at 270");
		world.moonAngleDegrees = 0.0F;
		assertEquals(0.25, read("shadowAngle", world).f(0), 1.0e-6, "the moon at 0");
		world.sunAngleDegrees = -100.0F;
		world.moonAngleDegrees = 100.0F;
		assertEquals(190.0 / 360, read("shadowAngle", world).f(0), 1.0e-6, "sun at 350 is night");
	}

	// positions

	@Test
	void putsTheSunWhereThreeRotationsOfTheFixedVectorPutIt() {
		// By hand, with the identity view. Nought is straight up; a quarter turn of the angle lays
		// the vector along Z and the quarter turn about Y sends it to minus X; and so on round.
		FakeWorld world = new FakeWorld();
		double[][] anchors = { { 0, 0, 0, 100, 0 }, { 90, 0, -100, 0, 0 }, { 180, 0, 0, -100, 0 },
				{ 270, 0, 100, 0, 0 }, { 0, 90, 0, 0, -100 } };

		for (double[] anchor : anchors) {
			world.sunAngleDegrees = (float) anchor[0];
			world.sunPathRotation = (float) anchor[1];

			assertVector(new double[] { anchor[2], anchor[3], anchor[4] }, read("sunPosition", world),
					"angle " + anchor[0] + " path " + anchor[1]);
		}
	}

	@Test
	void putsTheSunAndTheMoonWhereTheReferenceRotationsPutThemUnderEveryView() {
		double[] angles = { 0, 30, 45, 90, 135, 180, 225, 270, 315, 359.5, -20, 400 };
		double[] paths = { 0, 20, -35, 90 };

		for (View view : VIEWS) {
			for (double path : paths) {
				for (double angle : angles) {
					FakeWorld world = new FakeWorld();
					view.into(world);
					world.sunPathRotation = (float) path;
					world.sunAngleDegrees = (float) angle;
					world.moonAngleDegrees = (float) (angle + 180.0);

					// A place: the view's translation counts.
					assertVector(view.place(skyDirection(path, angle), 1.0), read("sunPosition", world),
							"sun at " + angle + " path " + path);
					assertVector(view.place(skyDirection(path, angle + 180.0), 1.0), read("moonPosition", world),
							"moon at " + (angle + 180.0) + " path " + path);
				}
			}
		}
	}

	@Test
	void theMoonStartsFromTheSameVectorAsTheSunAndIsTheSunTurnedHalfWayRound() {
		// Honouring the minus one hundred the reference passes and never reads would start the moon
		// at the opposite end and put it at the sun's place: a 180 degree error the tests below
		// would otherwise agree with, since they use the moon's own angle.
		FakeWorld world = new FakeWorld();
		world.sunAngleDegrees = 33.0F;
		world.moonAngleDegrees = 33.0F + 180.0F;

		Val sun = read("sunPosition", world);
		Val moon = read("moonPosition", world);
		for (int i = 0; i < 3; i++) {
			assertEquals(-sun.f(i), moon.f(i), TOLERANCE, "component " + i);
		}

		world.moonAngleDegrees = 33.0F;
		assertVector(new double[] { sun.f(0), sun.f(1), sun.f(2) }, read("moonPosition", world), "the same angle");
	}

	@Test
	void hasLengthOneHundredAndIsNotNormalised() {
		FakeWorld world = new FakeWorld();
		view(20, 15, 0, 0, 0).into(world);
		world.sunPathRotation = 25.0F;
		world.sunAngleDegrees = 61.0F;

		Val sun = read("sunPosition", world);

		assertEquals(100.0, Math.sqrt(sun.f(0) * sun.f(0) + sun.f(1) * sun.f(1) + sun.f(2) * sun.f(2)), TOLERANCE);
	}

	@Test
	void theUpDirectionIsTheViewsSecondColumnTimesOneHundredAndIgnoresTheSkyAndTheTranslation() {
		for (View view : VIEWS) {
			FakeWorld world = new FakeWorld();
			view.into(world);
			world.sunAngleDegrees = 123.0F;
			world.moonAngleDegrees = 303.0F;
			world.sunPathRotation = 40.0F;

			// A direction: w is nought, so the view's translation does not move it.
			assertVector(view.place(new double[] { 0.0, 100.0, 0.0 }, 0.0), read("upPosition", world), "view");
		}

		// By hand for the identity view: up is (0, 100, 0).
		assertVector(new double[] { 0, 100, 0 }, read("upPosition", new FakeWorld()), "identity");
	}

	// the shadow light and the End flash

	@Test
	void theShadowLightIsTheSunByDayAndTheMoonByNight() {
		FakeWorld world = new FakeWorld();
		view(50, -10, 0, 0, 0).into(world);
		world.sunPathRotation = 15.0F;
		world.sunAngleDegrees = 10.0F;
		world.moonAngleDegrees = 190.0F;

		assertVector(unpack(read("sunPosition", world)), read("shadowLightPosition", world), "day");

		world.sunAngleDegrees = 120.0F;
		world.moonAngleDegrees = 300.0F;
		assertVector(unpack(read("moonPosition", world)), read("shadowLightPosition", world), "night");
	}

	private static double[] unpack(Val val) {
		return new double[] { val.f(0), val.f(1), val.f(2) };
	}

	@Test
	void theEndFlashIsAPlaceOnlyInTheEndAndTheShadowLightOnlyWhenThePackAsked() {
		for (View view : VIEWS) {
			FakeWorld world = new FakeWorld();
			view.into(world);
			world.endFlashXAngleDegrees = 33.0F;
			world.endFlashYAngleDegrees = -70.0F;
			double[] flash = view.place(flashDirection(33.0, -70.0), 0.0);
			double[] zero = { 0.0, 0.0, 0.0 };
			double[] sun = unpack(read("sunPosition", world));

			// Not the End, or no flash: the flash is nowhere, and the light is the sun.
			assertVector(zero, read("endFlashPosition", world), "no flash");
			world.dimensionOrdinal = 1;
			assertVector(zero, read("endFlashPosition", world), "the End with no flash");
			world.dimensionOrdinal = 0;
			world.hasEndFlash = true;
			world.endFlashShadows = true;
			assertVector(zero, read("endFlashPosition", world), "a flash outside the End");
			assertVector(sun, read("shadowLightPosition", world), "and outside the End the light is the sun");

			// The End with a flash: it is published either way, and lights the shadows only on request.
			world.dimensionOrdinal = 1;
			world.endFlashShadows = false;
			assertVector(flash, read("endFlashPosition", world), "the flash, published");
			assertVector(sun, read("shadowLightPosition", world), "a pack that never asked keeps the sun");

			world.endFlashShadows = true;
			assertVector(flash, read("shadowLightPosition", world), "and one that asked is lit by the flash");
		}
	}

	@Test
	void theEndFlashPositionIsADirectionSoTheViewsTranslationDoesNotMoveIt() {
		FakeWorld world = new FakeWorld();
		world.dimensionOrdinal = 1;
		world.hasEndFlash = true;
		world.endFlashXAngleDegrees = 10.0F;
		world.endFlashYAngleDegrees = 20.0F;

		Val atOrigin = read("endFlashPosition", world);
		view(0, 0, 500, 600, 700).into(world);

		assertVector(unpack(atOrigin), read("endFlashPosition", world), "translated");
	}

	@Test
	void theWorldSpaceLightVectorIsTheUnitVectorOfTheSameRotationsWithNoView() {
		Random random = new Random(5);
		for (int i = 0; i < 50; i++) {
			FakeWorld world = new FakeWorld();
			world.sunPathRotation = random.nextFloat(-45.0F, 45.0F);
			world.sunAngleDegrees = random.nextFloat(-400.0F, 400.0F);
			world.moonAngleDegrees = random.nextFloat(-400.0F, 400.0F);
			boolean day = day(world.sunAngleDegrees);

			double[] expected = skyDirection(world.sunPathRotation,
					day ? world.sunAngleDegrees : world.moonAngleDegrees);
			normalise(expected);

			Vector3f dest = new Vector3f();
			assertSame(dest, CelestialValues.shadowLightVector(world, dest), "the destination is what comes back");
			assertVector3(expected, dest, "sample " + i);
			assertEquals(1.0, dest.length(), 1.0e-5);
		}
	}

	/** Day is the wrapped sun angle below 180, which is the sun's raw angle below 90. */
	private static boolean day(float rawSunAngle) {
		float angle = rawSunAngle + 90.0F;
		if (angle < 0.0F) {
			angle += 360.0F;
		} else if (angle > 360.0F) {
			angle -= 360.0F;
		}

		return angle < 180.0F;
	}

	private static void normalise(double[] v) {
		double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		for (int i = 0; i < 3; i++) {
			v[i] /= length;
		}
	}

	private static void assertVector3(double[] expected, Vector3f actual, String what) {
		assertEquals(expected[0], actual.x, 1.0e-5, what + " x");
		assertEquals(expected[1], actual.y, 1.0e-5, what + " y");
		assertEquals(expected[2], actual.z, 1.0e-5, what + " z");
	}

	@Test
	void theLightVectorInTheEndFlashIsTheFlashDirectionAndOnlyWhenThePackAsked() {
		FakeWorld world = new FakeWorld();
		world.dimensionOrdinal = 1;
		world.hasEndFlash = true;
		world.endFlashXAngleDegrees = 25.0F;
		world.endFlashYAngleDegrees = -140.0F;

		double[] flash = flashDirection(25.0, -140.0);
		normalise(flash);
		double[] sky = skyDirection(0.0, 0.0);
		normalise(sky);

		Vector3f dest = new Vector3f();
		CelestialValues.shadowLightVector(world, dest);
		assertVector3(sky, dest, "a pack that never asked");

		world.endFlashShadows = true;
		CelestialValues.shadowLightVector(world, dest);
		assertVector3(flash, dest, "a pack that asked");

		world.dimensionOrdinal = 0;
		CelestialValues.shadowLightVector(world, dest);
		assertVector3(sky, dest, "and outside the End");
	}

	@Test
	void turningTheCameraWithoutMovingItRotatesTheEyeSpaceLightAndLeavesTheWorldSpaceOneWhereItWas() {
		// The wrong-space neighbour: on a still frame the two are alike, and they part company only
		// when the head turns. In eye space the light swings with the view; in world space it does not.
		FakeWorld world = new FakeWorld();
		world.sunPathRotation = 20.0F;
		world.sunAngleDegrees = 35.0F;

		Vector3f before = CelestialValues.shadowLightVector(world, new Vector3f());
		Val eyeBefore = read("shadowLightPosition", world);

		View turned = view(90, 0, 0, 0, 0);
		turned.into(world);
		Vector3f after = CelestialValues.shadowLightVector(world, new Vector3f());
		Val eyeAfter = read("shadowLightPosition", world);

		assertEquals(0.0, before.distance(after), 1.0e-6, "the world space light did not move");
		assertVector(turned.place(unpack(eyeBefore), 0.0), eyeAfter, "the eye space light turned with the view");
		assertTrue(Math.abs(eyeBefore.f(0) - eyeAfter.f(0)) + Math.abs(eyeBefore.f(2) - eyeAfter.f(2)) > 1.0,
				"and by a lot");

		// The identity view: 100 times the world vector is the eye space position.
		FakeWorld still = new FakeWorld();
		still.sunPathRotation = 20.0F;
		still.sunAngleDegrees = 35.0F;
		Vector3f light = CelestialValues.shadowLightVector(still, new Vector3f());
		assertVector(new double[] { light.x * 100.0, light.y * 100.0, light.z * 100.0 },
				read("shadowLightPosition", still), "identity view");
	}

	// what the answers are remembered by

	@Test
	void everyInputTheSkyDependsOnReachesTheAnswerWhenItAloneMoves() {
		// The sky remembers its last inputs so as not to rebuild the rotations for every program of a
		// pack. A key that leaves one out hands back the answer to the question before: each input is
		// moved alone, in a random order, and the answer must be the reference's every time.
		Random random = new Random(11);
		FakeWorld world = new FakeWorld();
		double yaw = 0.0;
		double pitch = 0.0;
		double path = 0.0;
		double sun = 10.0;
		double moon = 190.0;
		double flashX = 0.0;
		double flashY = 0.0;
		world.dimensionOrdinal = 1;
		world.hasEndFlash = true;
		world.endFlashShadows = true;

		for (int step = 0; step < 300; step++) {
			switch (random.nextInt(7)) {
				case 0 -> yaw = random.nextDouble(-180.0, 180.0);
				case 1 -> pitch = random.nextDouble(-80.0, 80.0);
				case 2 -> path = random.nextDouble(-45.0, 45.0);
				case 3 -> sun = random.nextDouble(-360.0, 360.0);
				case 4 -> moon = random.nextDouble(-360.0, 360.0);
				case 5 -> flashX = random.nextDouble(-90.0, 90.0);
				default -> flashY = random.nextDouble(-180.0, 180.0);
			}

			View view = view(yaw, pitch, 0, 0, 0);
			view.into(world);
			world.sunPathRotation = (float) path;
			world.sunAngleDegrees = (float) sun;
			world.moonAngleDegrees = (float) moon;
			world.endFlashXAngleDegrees = (float) flashX;
			world.endFlashYAngleDegrees = (float) flashY;

			String what = "step " + step;
			assertVector(view.place(skyDirection(path, sun), 1.0), read("sunPosition", world), what + " sun");
			assertVector(view.place(skyDirection(path, moon), 1.0), read("moonPosition", world), what + " moon");
			assertVector(view.place(new double[] { 0, 100, 0 }, 0.0), read("upPosition", world), what + " up");
			assertVector(view.place(flashDirection(flashX, flashY), 0.0), read("endFlashPosition", world),
					what + " flash");
		}
	}

	@Test
	void answersAnUnchangedQuestionWithTheSameBitsEveryTime() {
		FakeWorld world = new FakeWorld();
		view(30, 20, 0, 0, 0).into(world);
		world.sunAngleDegrees = 77.0F;
		world.sunPathRotation = 12.0F;

		for (String name : new String[] { "sunPosition", "moonPosition", "upPosition", "shadowLightPosition" }) {
			float[] first = ValueReads.floats(read(name, world), 3);
			for (int i = 0; i < 5; i++) {
				assertArrayEquals(first, ValueReads.floats(read(name, world), 3), name);
			}
		}
	}

	@Test
	void theEndFlashIntensitiesAreThisFramesAndTheLastFramesAndNotEachOther() {
		FakeWorld world = new FakeWorld();
		world.endFlashIntensity = 0.75F;
		world.previousEndFlashIntensity = 0.25F;

		assertEquals(0.75F, read("endFlashIntensity", world).f(0));
		assertEquals(0.25F, read("previousEndFlashIntensity", world).f(0));
	}

	private Val read(String name, FakeWorld world) {
		return ValueReads.read(this.engine, name, world);
	}
}
