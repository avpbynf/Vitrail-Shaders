package dev.vitrail.dh;

import static dev.vitrail.dh.DhFakes.CLIENT_API;
import static dev.vitrail.dh.DhFakes.CONFIG;
import static dev.vitrail.dh.DhFakes.DELAYED;
import static dev.vitrail.dh.DhFakes.DH_API;
import static dev.vitrail.dh.DhLodsResolveTest.assertLine;
import static dev.vitrail.dh.DhLodsResolveTest.assertOneLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.dh.DhWorld.Edit;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds {@code DhDepth}: the arithmetic of the planes, the answers of a Distant Horizons that is
 * absent, misshapen, unlinkable or healthy, and the latches that turn a failed read into a
 * permanent fallback.
 */
class DhDepthTest {

	private static boolean zRow(DhWorld world, Vector2f dest) {
		return (Boolean) world.call(DhWorld.DEPTH, "zRow", dest);
	}

	private static int distance(DhWorld world) {
		return (Integer) world.call(DhWorld.DEPTH, "renderDistanceBlocks");
	}

	private static boolean present(DhWorld world) {
		return (Boolean) world.call(DhWorld.DEPTH, "present");
	}

	private static boolean usable(DhWorld world) {
		return (Boolean) world.call(DhWorld.DEPTH, "usable");
	}

	private static Vector2f sentinel() {
		return new Vector2f(-1.0F, -2.0F);
	}

	private static void assertUntouched(Vector2f dest) {
		assertEquals(-1.0F, dest.x);
		assertEquals(-2.0F, dest.y);
	}

	// The arithmetic.

	@Test
	void planesAreWhereTheRowReachesTheTwoEndsOfTheRange() {
		Vector2f dest = new Vector2f();

		// near = offset / (1 + scale), far = offset / scale, worked out by hand.
		assertTrue(DhDepth.planes(0.5F, 12.0F, dest));
		assertEquals(8.0F, dest.x);
		assertEquals(24.0F, dest.y);

		assertTrue(DhDepth.planes(1.0F, 2.0F, dest));
		assertEquals(1.0F, dest.x);
		assertEquals(2.0F, dest.y);

		assertTrue(DhDepth.planes(3.0F, 6.0F, dest));
		assertEquals(1.5F, dest.x);
		assertEquals(2.0F, dest.y);
	}

	@Test
	void aRowWithNoPerspectiveInItLeavesTheDestinationAlone() {
		for (float scale : new float[] { 0.0F, -0.0F, -1.0F, -1.0E-6F, Float.NEGATIVE_INFINITY }) {
			Vector2f dest = sentinel();

			assertFalse(DhDepth.planes(scale, 12.0F, dest), "scale " + scale);
			assertUntouched(dest);
		}
	}

	@Test
	void planesRecoverTheClipPlanesTheRowWasMadeFrom() {
		// An independent reading: the row a plane pair (n, f) makes is scale n / (f - n) and offset
		// n f / (f - n), worked out in doubles, and planes must give the pair back.
		Random random = new Random(7);
		Vector2f dest = new Vector2f();

		for (int round = 0; round < 500; round++) {
			double near = 0.5 + random.nextDouble() * 32.0;
			double far = near * (2.0 + random.nextDouble() * 4000.0);
			float scale = (float) (near / (far - near));
			float offset = (float) (near * far / (far - near));

			assertTrue(DhDepth.planes(scale, offset, dest));
			assertEquals(near, dest.x, near * 1.0E-4, "near of " + near + " " + far);
			assertEquals(far, dest.y, far * 1.0E-4, "far of " + near + " " + far);
		}
	}

	// A Distant Horizons that is not there, or not in the shape.

	@ParameterizedTest
	@ValueSource(strings = { DELAYED, CLIENT_API })
	void anAbsentDhAnswersNothingAndSaysNothing(String absent) {
		DhWorld world = DhWorld.missing(absent);
		Vector2f dest = sentinel();

		for (int frame = 0; frame < 10; frame++) {
			assertFalse(zRow(world, dest));
			assertEquals(-1, distance(world));
			assertFalse(present(world));
		}

		assertUntouched(dest);
		assertFalse(usable(world));
		assertEquals(List.of(), world.log());
		assertEquals(List.of(), world.events());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("misshapenDh")
	void aDhOfTheWrongShapeIsGivenUpOnceWithOneLineAndNothingIsServed(String what, Edit edit) {
		DhWorld world = DhWorld.edited(edit);
		world.stage("publishConfigs");
		world.stage("matrix", 0.5F, 12.0F);
		Vector2f dest = sentinel();

		for (int frame = 0; frame < 10; frame++) {
			assertFalse(zRow(world, dest));
			assertEquals(-1, distance(world));
			assertFalse(present(world));
		}

		assertUntouched(dest);
		assertFalse(usable(world));
		assertOneLine(world, "INFO", "installed but not in a shape a projection can be read out of");
	}

	static Stream<Arguments> misshapenDh() {
		return Stream.of(
				Arguments.of("no RENDER_PARAMS", new Edit(CLIENT_API, "RENDER_PARAMS", "PARAMS")),
				Arguments.of("no dhProjectionMatrix", new Edit(CLIENT_API, "dhProjectionMatrix", "projection")),
				Arguments.of("no m22", new Edit(CLIENT_API, "m22", "m21")),
				Arguments.of("no m23", new Edit(CLIENT_API, "m23", "m32")),
				Arguments.of("no configs", new Edit(DH_API, "configs", "settings")),
				Arguments.of("no chunkRenderDistance", new Edit(CONFIG, "chunkRenderDistance", "renderDistance")),
				Arguments.of("no renderingEnabled", new Edit(CONFIG, "renderingEnabled", "renderingOn")),
				Arguments.of("no getValue", new Edit(CONFIG, "Object getValue();", "Object value();")));
	}

	/**
	 * A lookup that fails with a LinkageError is given up the way a DH of the wrong shape is: the
	 * first frame that asks gets an answer of nothing and one line naming the error, and every later
	 * one the same answer and no line. What asks first is a frame publishing its values or a pack
	 * being read, so an error let through there would reach the game loop.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("unlinkableDh")
	void aLinkageErrorInResolveIsGivenUpOnceWithOneLineAndNothingIsServed(String error, DhWorld world) {
		Vector2f dest = sentinel();

		for (int frame = 0; frame < 10; frame++) {
			assertFalse(present(world));
			assertFalse(zRow(world, dest));
			assertEquals(-1, distance(world));
		}

		assertUntouched(dest);
		assertFalse(usable(world));
		assertOneLine(world, "INFO", "installed but not in a shape a projection can be read out of");
		assertLine(world, 0, "INFO", error);
	}

	static Stream<Arguments> unlinkableDh() {
		return Stream.of(
				// The type of the parameter field cannot be loaded, which the field lookup answers.
				Arguments.of("NoClassDefFoundError", DhWorld.missing(CLIENT_API + "$Params")),
				// The holder the first lookup names throws as it initialises, which forName answers.
				Arguments.of("ExceptionInInitializerError", DhWorld.edited(new Edit(DH_API,
						"public static IDhApiConfig configs;",
						"public static IDhApiConfig configs = refuse(); private static IDhApiConfig refuse() "
								+ "{ throw new IllegalStateException(\"fake DH failed at Delayed.init\"); }"))));
	}

	// A healthy one.

	@Test
	void askingWhetherItIsUsableIsOnlyTheFlagAndResolvesNothing() {
		DhWorld world = DhWorld.standard();

		assertFalse(usable(world));
		assertFalse(usable(world));

		assertEquals(List.of(), world.events());
		assertEquals(List.of(), world.log());
	}

	@Test
	void resolvingLeavesTheProjectionHolderUninitialisedUntilTheFirstRead() {
		DhWorld world = DhWorld.standard();
		world.stage("publishConfigs");

		assertTrue(present(world));

		assertTrue(usable(world));
		assertOneLine(world, "INFO", "Distant Horizons found");
		assertEquals(List.of("rendering.get"), world.events());

		Vector2f dest = sentinel();
		// Nothing published yet: the read initialises the class and finds no parameter.
		assertFalse(zRow(world, dest));
		assertFalse(zRow(world, dest));
		assertEquals(List.of("rendering.get", "ClientApi.init"), world.events());
		assertUntouched(dest);

		world.stage("matrix", 0.5F, 12.0F);
		assertTrue(zRow(world, dest));
		assertEquals(0.5F, dest.x);
		assertEquals(12.0F, dest.y);
		assertEquals(1, world.events().stream().filter("ClientApi.init"::equals).count());
	}

	@Test
	void theRowIsReadEveryFrameAndNothingIsServedBeforeDhHasDrawn() {
		DhWorld world = DhWorld.standard();
		world.stage("publishConfigs");
		Vector2f dest = sentinel();

		world.stage("paramsWithoutMatrix");
		assertFalse(zRow(world, dest));

		// The identity DH makes its matrix as: no offset yet.
		world.stage("matrix", 1.0F, 0.0F);
		assertFalse(zRow(world, dest));
		assertUntouched(dest);

		world.stage("matrix", 0.5F, 12.0F);
		assertTrue(zRow(world, dest));
		assertEquals(0.5F, dest.x);
		assertEquals(12.0F, dest.y);

		world.stage("matrix", 0.25F, 20.0F);
		assertTrue(zRow(world, dest));
		assertEquals(0.25F, dest.x);
		assertEquals(20.0F, dest.y);

		world.stage("noParams");
		assertFalse(zRow(world, dest));
		assertTrue(usable(world));
	}

	@Test
	void theRenderDistanceIsTheSettingInChunksTimesSixteen() {
		DhWorld world = DhWorld.standard();
		world.stage("publishConfigs");

		assertEquals(192, distance(world));
		assertEquals(List.of("rendering.get", "distance.get"), world.events());

		world.stage("player", "distance", 20);
		assertEquals(320, distance(world));
	}

	@Test
	void configsNotPublishedYetAreNotAFailureAndAreAskedAgain() {
		DhWorld world = DhWorld.standard();

		assertEquals(-1, distance(world));
		assertFalse(present(world));
		assertTrue(usable(world));
		assertOneLine(world, "INFO", "Distant Horizons found");

		world.stage("publishConfigs");

		assertTrue(present(world));
		assertEquals(192, distance(world));
		assertEquals(1, world.log().size());
	}

	@Test
	void theRenderingSwitchIsReadLiveAndNeverLatched() {
		DhWorld world = DhWorld.standard();
		world.stage("publishConfigs");
		assertTrue(present(world));
		assertEquals(192, distance(world));

		world.stage("player", "rendering", Boolean.FALSE);
		assertFalse(present(world));
		assertEquals(-1, distance(world));

		world.stage("player", "rendering", Boolean.TRUE);
		assertTrue(present(world));
		assertEquals(192, distance(world));

		// Anything that is not a Boolean is off, and is not a failure.
		world.stage("player", "rendering", "yes");
		assertFalse(present(world));
		world.stage("player", "rendering", null);
		assertFalse(present(world));
		world.stage("player", "rendering", Boolean.TRUE);
		assertTrue(present(world));
		assertEquals(1, world.log().size(), world.log().toString());
	}

	@Test
	void aDistanceThatIsNotANumberIsTheGamesOwnAndIsNotLatched() {
		DhWorld world = DhWorld.standard();
		world.stage("publishConfigs");

		world.stage("player", "distance", "far");
		assertEquals(-1, distance(world));

		world.stage("player", "distance", 8);
		assertEquals(128, distance(world));
		assertEquals(1, world.log().size(), world.log().toString());
	}

	// The latches.

	@Test
	void aDistanceThatCannotBeReadLosesTheDistanceAloneAndForever() {
		DhWorld world = DhWorld.standard();
		world.stage("publishConfigs");
		world.stage("matrix", 0.5F, 12.0F);
		world.stage("failOn", "distance.get");
		Vector2f dest = sentinel();

		for (int frame = 0; frame < 5; frame++) {
			assertEquals(-1, distance(world));
		}

		world.stage("heal", "distance.get");
		assertEquals(-1, distance(world));

		// Read once and never again; the projection and the switch are not touched by the latch.
		assertEquals(1, world.events().stream().filter("distance.get"::equals).count());
		assertTrue(usable(world));
		assertTrue(present(world));
		assertTrue(zRow(world, dest));
		assertEquals(2, world.log().size(), world.log().toString());
		assertLine(world, 1, "WARN", "render distance cannot be read");
		assertLine(world, 1, "WARN", "InvocationTargetException / java.lang.IllegalStateException");
	}

	@Test
	void aSwitchThatCannotBeReadLosesEverythingAndForever() {
		DhWorld world = DhWorld.standard();
		world.stage("publishConfigs");
		world.stage("matrix", 0.5F, 12.0F);
		world.stage("failOn", "rendering.get");
		Vector2f dest = sentinel();

		assertFalse(present(world));
		world.stage("heal", "rendering.get");

		for (int frame = 0; frame < 5; frame++) {
			assertFalse(present(world));
			assertFalse(zRow(world, dest));
			assertEquals(-1, distance(world));
		}

		assertFalse(usable(world));
		assertUntouched(dest);
		assertEquals(1, world.events().stream().filter("rendering.get"::equals).count());
		assertEquals(2, world.log().size(), world.log().toString());
		assertLine(world, 1, "WARN", "rendering switch cannot be read");
	}

	@Test
	void aProjectionHolderThatCannotBeInitialisedLosesEverythingAndForever() {
		DhWorld world = DhWorld.standard();
		world.stage("publishConfigs");
		world.stage("failOn", "ClientApi.init");
		Vector2f dest = sentinel();

		assertFalse(zRow(world, dest));

		for (int frame = 0; frame < 5; frame++) {
			assertFalse(zRow(world, dest));
			assertFalse(present(world));
			assertEquals(-1, distance(world));
		}

		assertFalse(usable(world));
		assertUntouched(dest);
		assertEquals(2, world.log().size(), world.log().toString());
		assertLine(world, 1, "WARN", "projection cannot be read");
		assertLine(world, 1, "WARN", "ExceptionInInitializerError");
		assertEquals(1, world.events().stream().filter("ClientApi.init"::equals).count());
	}
}
