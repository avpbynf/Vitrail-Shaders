package dev.vitrail.dh;

import static dev.vitrail.dh.DhFakes.BUFFER_CONTAINER;
import static dev.vitrail.dh.DhFakes.BUFFER_WRAPPER;
import static dev.vitrail.dh.DhFakes.DELAYED;
import static dev.vitrail.dh.DhFakes.DH_API;
import static dev.vitrail.dh.DhFakes.LOD_RENDERER;
import static dev.vitrail.dh.DhFakes.RENDER_PROXY;
import static dev.vitrail.dh.DhFakes.SORTED_SET;
import static dev.vitrail.dh.DhFakes.TERRAIN_RENDERER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.dh.DhWorld.Edit;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds how {@code DhLods} finds Distant Horizons: what it looks up, what one that answers gives,
 * and that every way a DH can fail to answer ends in {@code usable() == false} with at most one
 * line, said once and not once a frame.
 * <p>
 * Each test builds a world with a Distant Horizons that is wrong in exactly one way, so the failure
 * it reports is the one that was planted.
 */
class DhLodsResolveTest {

	/** How many frames a failure is asked about, which is what would multiply a line per frame. */
	private static final int FRAMES = 25;

	@Test
	void aCompleteDhResolvesOnTheFirstInstallAndSaysSoOnce() {
		DhWorld world = DhWorld.standard();

		assertFalse(world.lodsUsable());
		world.install();

		assertTrue(world.lodsUsable());
		assertOneLine(world, "INFO", "Distant Horizons found");
		for (int frame = 0; frame < FRAMES; frame++) {
			world.install();
		}
		assertEquals(1, world.log().size());
	}

	@Test
	void resolvingInitialisesDhsRendererClassAndNothingElse() {
		DhWorld world = DhWorld.standard();

		world.install();

		// Not the projection holder DhDepth reads: that class is deliberately left uninitialised there.
		assertEquals(List.of("LodRenderer.init"), world.events());
	}

	@Test
	void askingWhetherItIsUsableDoesNotResolve() {
		DhWorld world = DhWorld.standard();

		assertFalse(world.lodsUsable());
		assertFalse(world.lodsUsable());

		assertEquals(List.of(), world.events());
		assertEquals(List.of(), world.log());
	}

	@ParameterizedTest
	@ValueSource(strings = { TERRAIN_RENDERER, LOD_RENDERER })
	void aDhThatIsNotThereIsNotAnError(String absent) {
		assertFailedQuietly(DhWorld.missing(absent), List.of());
	}

	/**
	 * A ClassNotFoundException anywhere in the lookups reads as "DH is not installed", so a DH that
	 * is there (its renderer class is even initialised) and lacks one class the bridge needs says
	 * nothing at all, where every other way of not fitting gets a line.
	 */
	@ParameterizedTest
	@ValueSource(strings = { DELAYED, BUFFER_CONTAINER, SORTED_SET, BUFFER_WRAPPER })
	void knownBug_anInstalledDhThatLacksAClassIsSilent(String absent) {
		assertFailedQuietly(DhWorld.missing(absent), List.of("LodRenderer.init"));
	}

	@Test
	void aClassWhoseOwnDependencyIsMissingIsAnInfoLineNotSilence() {
		// The container is found, and the type of one of its fields is not: a LinkageError, not a
		// ClassNotFoundException, so it is told.
		DhWorld world = DhWorld.missing(BUFFER_CONTAINER + "$Pos");

		assertFailedWithOneLine(world);
		assertTrue(world.log().get(0).contains("NoClassDefFoundError"), world.log().get(0));
	}

	@Test
	void aRendererClassThatCannotBeInitialisedIsAnInfoLine() {
		DhWorld world = DhWorld.standard();
		world.stage("failOn", "LodRenderer.init");

		assertFailedWithOneLine(world);
		assertTrue(world.log().get(0).contains("ExceptionInInitializerError"), world.log().get(0));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("misshapenDh")
	void aDhOfTheWrongShapeIsGivenUpOnceWithOneLine(String what, Edit[] edits) {
		DhWorld world = DhWorld.edited(edits);

		assertFailedWithOneLine(world);
	}

	static Stream<Arguments> misshapenDh() {
		return Stream.of(
				shape("no INSTANCE on the renderer", LOD_RENDERER, "INSTANCE", "SINGLETON"),
				shape("no terrainRenderer field", LOD_RENDERER, "terrainRenderer", "renderer"),
				shape("no renderProxy", DH_API, "renderProxy", "proxy"),
				shape("no setDeferTransparentRendering", RENDER_PROXY, "setDeferTransparentRendering",
						"setDeferred"),
				shape("no vboOpaqueWrappers", BUFFER_CONTAINER, "vboOpaqueWrappers", "opaqueWrappers"),
				shape("no vboTransparentWrappers", BUFFER_CONTAINER, "vboTransparentWrappers",
						"translucentWrappers"),
				shape("no minCornerBlockPos", BUFFER_CONTAINER, "minCornerBlockPos", "corner"),
				shape("no getX", BUFFER_CONTAINER, "getX", "getBlockX"),
				shape("no getY", BUFFER_CONTAINER, "getY", "getBlockY"),
				shape("no getZ", BUFFER_CONTAINER, "getZ", "getBlockZ"),
				shape("no size on the set", SORTED_SET, "public int size()", "public int count()"),
				shape("no get on the set", SORTED_SET, "public Object get(int index)",
						"public Object at(int index)"),
				shape("no vertexGpuBuffer", BUFFER_WRAPPER, "vertexGpuBuffer", "vertexBuffer"),
				shape("no getIndexGpuBuffer", BUFFER_WRAPPER, "getIndexGpuBuffer", "getIndexBuffer"),
				shape("no indexCount", BUFFER_WRAPPER, "indexCount", "drawCount"),
				shape("no vertexCount", BUFFER_WRAPPER, "vertexCount", "vertexTotal"),
				shape("no uploaded", BUFFER_WRAPPER, "uploaded", "ready"));
	}

	private static Arguments shape(String what, String type, String from, String to) {
		return Arguments.of(what, new Edit[] { new Edit(type, from, to) });
	}

	/**
	 * Failed, told nothing, and nothing of DH was touched by asking again or by handing back beyond
	 * what the first look already did.
	 */
	private static void assertFailedQuietly(DhWorld world, List<String> events) {
		for (int frame = 0; frame < FRAMES; frame++) {
			world.install();
		}
		world.handBack();

		assertFalse(world.lodsUsable());
		assertEquals(List.of(), world.log());
		assertEquals(events, world.events());
	}

	/** Failed with exactly one INFO line however often it is asked, and handing back is harmless. */
	private static void assertFailedWithOneLine(DhWorld world) {
		world.install();
		List<String> events = world.events();
		for (int frame = 0; frame < FRAMES; frame++) {
			world.install();
		}
		world.handBack();

		assertFalse(world.lodsUsable());
		assertOneLine(world, "INFO", "Distant Horizons is installed but not in a shape");
		assertEquals(events, world.events());
	}

	static void assertOneLine(DhWorld world, String level, String fragment) {
		List<String> log = world.log();

		assertEquals(1, log.size(), () -> "expected one line, got " + log);
		assertLine(world, 0, level, fragment);
	}

	/** The line at that place of the log is of that level and says that. */
	static void assertLine(DhWorld world, int index, String level, String fragment) {
		List<String> log = world.log();

		assertTrue(index < log.size(), () -> "no line " + index + " in " + log);
		assertTrue(log.get(index).startsWith(level + " "), log.get(index));
		assertTrue(log.get(index).contains(fragment), log.get(index));
	}
}
