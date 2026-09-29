package dev.vitrail.dh;

import static dev.vitrail.dh.DhFakes.BUFFER_CONTAINER;
import static dev.vitrail.dh.DhFakes.GEOMETRY;
import static dev.vitrail.dh.DhLodsResolveTest.assertLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.dh.DhWorld.Edit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Holds what happens in a pass once DH's renderer is the bridge's proxy: what the pack is asked,
 * what DH is handed back, what a listing of DH's becomes, what is kept from one half and one frame
 * to the next, and every way the read of a listing can fail.
 * <p>
 * The stand-in for the pack's far terrain draw records every list it is handed as text, so the
 * expected values below are the records the bridge builds, written out by hand from the containers
 * each test lists.
 */
class DhLodsPassTest {

	private static final int STRIDE = 16;

	private static final List<String> RESTORED = List.of("proxy.defer(false)", "ao.clear", "fog.clear");

	private static final String OPAQUE = "[Section[x=16, y=64, z=-32, pieces=["
			+ "Piece[vertices=a-v, indices=a-i, indexCount=6]]], "
			+ "Section[x=0, y=0, z=0, pieces=["
			+ "Piece[vertices=b1-v, indices=b1-i, indexCount=3], "
			+ "Piece[vertices=b2-v, indices=b2-i, indexCount=9]]]]";

	private static final String TRANSLUCENT = "[Section[x=16, y=64, z=-32, pieces=["
			+ "Piece[vertices=a-tv, indices=a-ti, indexCount=12]]]]";

	/** A world with the bridge in DH's place and a scripted pack. */
	private static final class Rig {

		final DhWorld world;
		final List<String> drawn = new ArrayList<>();
		final List<List<?>> lists = new ArrayList<>();
		Supplier<Boolean> answer = () -> Boolean.FALSE;

		Rig() {
			this(DhWorld.standard());
		}

		Rig(DhWorld world) {
			this.world = world;
			world.dhStarted();
			world.onDraw((opaque, sections) -> {
				drawn.add(opaque + " " + sections);
				lists.add(sections);
				return answer.get();
			});
			world.install();
			world.clearEvents();
			world.clearLog();
		}

		Object vertices(String name, int count) {
			return world.buffer(name, (long) STRIDE * count);
		}

		Object indices(String name) {
			return world.buffer(name, 24);
		}

		Object wrapper(String name, int indexCount, int vertexCount) {
			return world.wrapper(vertices(name + "-v", vertexCount), indices(name + "-i"), indexCount, vertexCount);
		}

		/** Two containers: A with both halves, B with two opaque wrappers and no translucent array. */
		Object[] standardScene() {
			Object a = world.container(16, 64, -32, new Object[] { wrapper("a", 6, 4) },
					new Object[] { world.wrapper(vertices("a-tv", 8), indices("a-ti"), 12, 8) });
			Object b = world.container(0, 0, 0, new Object[] { wrapper("b1", 3, 2), wrapper("b2", 9, 6) }, null);
			world.list(a, b);

			return new Object[] { a, b };
		}

		int pulls() {
			return (Integer) world.stage("pulls");
		}
	}

	// What the proxy does with a pass.

	@Test
	void aPassThePackDidNotDrawIsHandedToDhWithTheSameArguments() {
		Rig rig = new Rig();
		rig.standardScene();

		rig.world.frame(true);

		assertEquals(List.of("true " + OPAQUE), rig.drawn);
		assertEquals(List.of("original.render(true,set:2,true)"), rig.world.events());
	}

	@Test
	void aPassThePackDrewIsNotHandedToDh() {
		Rig rig = new Rig();
		rig.answer = () -> Boolean.TRUE;
		rig.standardScene();

		rig.world.frame(true);

		assertEquals(1, rig.drawn.size());
		assertEquals(List.of(), rig.world.events());
		assertTrue(rig.world.lodsUsable());
	}

	@Test
	void theTwoHalvesAreAskedWithTheirOwnFlagAndTheirOwnArrays() {
		Rig rig = new Rig();
		rig.standardScene();

		rig.world.frame(true);
		rig.world.frame(false);

		assertEquals(List.of("true " + OPAQUE, "false " + TRANSLUCENT), rig.drawn);
		assertEquals(List.of("original.render(true,set:2,true)", "original.render(false,set:2,true)"),
				rig.world.events());
	}

	@Test
	void everyOtherMethodIsForwardedWithoutAskingThePack() {
		Rig rig = new Rig();
		Object standing = rig.world.standing();

		assertEquals("original", rig.world.callOn(standing, "name"));
		rig.world.callOn(standing, "render", "single");

		assertEquals(List.of("original.render(single)"), rig.world.events());
		assertEquals(List.of(), rig.drawn);
		assertTrue(rig.world.lodsUsable());
	}

	@Test
	void dhsOwnFailuresComeBackToDhAsThemselves() {
		Rig rig = new Rig();
		Object standing = rig.world.standing();

		Throwable unchecked = rig.world.thrownBy(standing, "fail");
		assertNotNull(unchecked);
		assertSame(rig.world.stage("lastFailure"), unchecked);

		Throwable checked = rig.world.thrownBy(standing, "failChecked");
		assertNotNull(checked);
		assertSame(rig.world.stage("lastFailure"), checked);
		assertEquals("java.io.IOException", checked.getClass().getName());
	}

	@Test
	void aRuntimeFailureInThePackHandsEverythingBackOnceAndTheSamePassGoesToDh() {
		Rig rig = new Rig();
		rig.answer = () -> {
			throw new IllegalArgumentException("boom");
		};
		rig.standardScene();

		rig.world.frame(true);

		assertFalse(rig.world.lodsUsable());
		assertSame(rig.world.stage("original"), rig.world.standing());
		assertEquals(List.of("proxy.defer(false)", "ao.clear", "fog.clear", "original.render(true,set:2,true)"),
				rig.world.events());
		// The report comes before the pack is asked, so it is the first line and the warning the second.
		assertEquals(2, rig.world.log().size(), rig.world.log().toString());
		assertLine(rig.world, 0, "INFO", "is reachable");
		assertLine(rig.world, 1, "WARN", "cannot be read out of the pass it is drawn in");
		assertLine(rig.world, 1, "WARN", "IllegalArgumentException: boom");

		// DH now calls its own renderer: the pack is not asked, and nothing more is said.
		rig.world.clearEvents();
		rig.world.frame(true);
		rig.world.install();
		assertEquals(List.of("original.render(true,set:2,true)"), rig.world.events());
		assertEquals(1, rig.drawn.size());
		assertEquals(2, rig.world.log().size());
	}

	@Test
	void aLinkageErrorInThePackGetsTheSameTreatment() {
		Rig rig = new Rig();
		rig.answer = () -> {
			throw new NoClassDefFoundError("gone");
		};
		rig.standardScene();

		rig.world.frame(true);

		assertFalse(rig.world.lodsUsable());
		assertSame(rig.world.stage("original"), rig.world.standing());
		assertLine(rig.world, 1, "WARN", "NoClassDefFoundError: gone");
	}

	@Test
	void aLostDeviceIsRethrownUntouchedAndNothingDegrades() {
		Rig rig = new Rig();
		RuntimeException lost = DhWorld.deviceLoss("lost");
		rig.answer = () -> {
			throw lost;
		};
		rig.standardScene();

		Throwable thrown = assertThrows(RuntimeException.class, () -> rig.world.frame(true));

		assertSame(lost, thrown);
		assertTrue(rig.world.lodsUsable());
		assertTrue(rig.world.standingIsProxy());
		assertEquals(List.of(), rig.world.events());
		// Only the report, which is made before the pack is asked: no warning.
		assertEquals(1, rig.world.log().size(), rig.world.log().toString());
		assertLine(rig.world, 0, "INFO", "is reachable");
	}

	@Test
	void onlyTheThreeKindsOfFailureDegradeAnErrorOfAnyOtherKindEscapes() {
		Rig rig = new Rig();
		AssertionError other = new AssertionError("other");
		rig.answer = () -> {
			throw other;
		};
		rig.standardScene();

		Throwable thrown = assertThrows(AssertionError.class, () -> rig.world.frame(true));

		assertSame(other, thrown);
		assertTrue(rig.world.lodsUsable());
		assertTrue(rig.world.standingIsProxy());
	}

	@Test
	void aProxyDhStillHoldsAfterTheFailureForwardsWithoutAskingThePack() {
		Rig rig = new Rig();
		Object stale = rig.world.standing();
		rig.answer = () -> {
			throw new IllegalArgumentException("boom");
		};
		rig.standardScene();
		rig.world.frame(true);
		rig.world.clearEvents();

		rig.world.callOn(stale, "render", "params", true, rig.world.peek(GEOMETRY, "SET"), true);

		assertEquals(List.of("original.render(true,set:2,true)"), rig.world.events());
		assertEquals(1, rig.drawn.size());
	}

	// What a listing becomes.

	@Test
	void sectionsCarryTheirCornerAndTheirPiecesInTheOrderDhListedThem() {
		Rig rig = new Rig();
		rig.standardScene();

		rig.world.frame(true);
		rig.world.frame(false);

		assertEquals("true " + OPAQUE, rig.drawn.get(0));
		assertEquals("false " + TRANSLUCENT, rig.drawn.get(1));
	}

	@Test
	void whatCannotBeDrawnIsLeftOut() {
		Rig rig = new Rig();
		Object closedVertices = rig.vertices("closed-v", 4);
		rig.world.closeBuffer(closedVertices);
		Object closedIndices = rig.indices("closed-i");
		rig.world.closeBuffer(closedIndices);

		Object mixed = rig.world.container(1, 2, 3, new Object[] {
				null,
				rig.world.wrapper(rig.vertices("unuploaded", 4), rig.indices("i"), 6, 4, false),
				rig.world.wrapper(rig.vertices("empty", 4), rig.indices("i"), 6, 0),
				rig.world.wrapper("not a buffer", rig.indices("i"), 6, 4),
				rig.world.wrapper(rig.vertices("v", 4), "not a buffer", 6, 4),
				rig.world.wrapper(closedVertices, rig.indices("i"), 6, 4),
				// The index buffer is not looked at here: a closed one is DistantDraw's to drop.
				rig.world.wrapper(rig.vertices("k1", 4), closedIndices, 7, 4),
				rig.world.wrapper(rig.vertices("k2", 4), rig.indices("k2-i"), 8, 4) }, null);
		Object noArray = rig.world.container(9, 9, 9, null, null);
		Object nothingDrawable = rig.world.container(5, 5, 5,
				new Object[] { rig.world.wrapper(rig.vertices("x", 4), rig.indices("i"), 6, 4, false) }, null);
		rig.world.list(mixed, noArray, nothingDrawable);

		rig.world.frame(true);

		assertEquals("true [Section[x=1, y=2, z=3, pieces=["
				+ "Piece[vertices=k1, indices=closed-i, indexCount=7], "
				+ "Piece[vertices=k2, indices=k2-i, indexCount=8]]]]", rig.drawn.get(0));
		assertTrue(rig.world.lodsUsable());
	}

	@Test
	void anEmptyListingIsPassedOnAndReportsNothing() {
		Rig rig = new Rig();
		rig.world.list();

		rig.world.frame(true);

		assertEquals(List.of("true []"), rig.drawn);
		assertEquals(List.of(0), rig.world.census());
		assertEquals(List.of(), rig.world.log());
	}

	@Test
	void theFirstPassWithAnythingInItIsReportedOnceForTheSession() {
		Rig rig = new Rig();
		rig.standardScene();

		rig.world.frame(true);

		assertEquals(1, rig.world.log().size());
		assertLine(rig.world, 0, "INFO", "far terrain is reachable on this backend: "
				+ "2 sections, 3 buffers, 18 indices in the opaque half");

		rig.world.frame(false);
		rig.world.frame(true);
		rig.world.handBack();
		rig.world.install();
		rig.world.frame(true);

		long reports = rig.world.log().stream().filter(line -> line.contains("is reachable")).count();
		assertEquals(1, reports);
	}

	@Test
	void anEmptyHalfDoesNotSpendTheReport() {
		Rig rig = new Rig();
		Object onlyB = rig.world.container(0, 0, 0, new Object[] { rig.wrapper("b", 3, 2) }, null);
		rig.world.list(onlyB);

		rig.world.frame(false);
		assertEquals(List.of(), rig.world.log());

		rig.world.frame(true);
		assertEquals(1, rig.world.log().size());
		assertLine(rig.world, 0, "INFO", "1 sections, 1 buffers, 3 indices in the opaque half");
	}

	// What is kept.

	@Test
	void aListingThatDidNotMoveIsNotReadTwiceAndTheSameListAnswersAgain() {
		Rig rig = new Rig();
		rig.standardScene();

		rig.world.frame(true);
		rig.world.frame(false);

		assertEquals(List.of(2, 1), rig.world.census());
		// The listing is walked once a half, not once a frame and not once a container.
		assertEquals(4, rig.pulls());

		rig.world.install();
		rig.world.frame(true);
		rig.world.frame(false);

		assertEquals(List.of(2, 1), rig.world.census());
		assertEquals(8, rig.pulls());
		assertSame(rig.lists.get(0), rig.lists.get(2));
		assertSame(rig.lists.get(1), rig.lists.get(3));
	}

	@Test
	void aListingThatMovedIsReadAgainWhicheverWayItMoved() {
		Rig rig = new Rig();
		Object[] scene = rig.standardScene();
		Object a = scene[0];
		Object b = scene[1];
		rig.world.frame(true);
		rig.world.frame(false);
		assertEquals(List.of(2, 1), rig.world.census());

		// The same containers in the other order.
		rig.world.list(b, a);
		rig.world.frame(true);
		rig.world.frame(false);
		assertEquals(List.of(2, 1, 2, 1), rig.world.census());
		assertTrue(rig.drawn.get(2).startsWith("true [Section[x=0, y=0, z=0"), rig.drawn.get(2));

		// One container fewer: B alone has no translucent half.
		rig.world.list(b);
		rig.world.frame(true);
		rig.world.frame(false);
		assertEquals(List.of(2, 1, 2, 1, 1, 0), rig.world.census());

		// Settled again: nothing is read.
		rig.world.frame(true);
		rig.world.frame(false);
		assertEquals(List.of(2, 1, 2, 1, 1, 0), rig.world.census());

		// One more, and one that is another object with the same contents as one that left.
		Object again = rig.world.container(16, 64, -32, new Object[] { rig.wrapper("a2", 6, 4) }, null);
		rig.world.list(b, again);
		rig.world.frame(true);
		rig.world.frame(false);
		assertEquals(List.of(2, 1, 2, 1, 1, 0, 2, 0), rig.world.census());
	}

	@Test
	void theRedoSwitchReadsEveryHalfAgainButStillWalksTheListing() {
		Rig rig = new Rig();
		rig.world.keepRedoneWork(true);
		rig.standardScene();

		rig.world.frame(true);
		rig.world.frame(true);
		rig.world.frame(false);
		rig.world.frame(false);

		assertEquals(List.of(2, 2, 1, 1), rig.world.census());
		assertEquals(8, rig.pulls());
	}

	@Test
	void aFrameDhDidNotDrawLetsGoOfTheListingSoTheNextDrawReadsItAgain() {
		Rig rig = new Rig();
		rig.standardScene();
		rig.world.frame(true);
		rig.world.frame(false);

		// The boundary between two drawn frames keeps what was read.
		rig.world.install();
		assertEquals(2, rig.world.peek(DhWorld.LODS, "listedCount"));
		assertNotNull(rig.world.peek(DhWorld.LODS, "opaqueSections"));

		// A frame in which DH drew nothing (its renderer switched off): the next boundary lets go.
		rig.world.install();
		assertEquals(0, rig.world.peek(DhWorld.LODS, "listedCount"));
		assertEquals(0, ((Object[]) rig.world.peek(DhWorld.LODS, "listed")).length);
		assertNull(rig.world.peek(DhWorld.LODS, "opaqueSections"));
		assertNull(rig.world.peek(DhWorld.LODS, "translucentSections"));

		rig.world.frame(true);
		assertEquals(List.of(2, 1, 2), rig.world.census());
	}

	@Test
	void handBackLetsGoOfTheListingAndBothAnswers() {
		Rig rig = new Rig();
		rig.standardScene();
		rig.world.frame(true);
		rig.world.frame(false);
		assertNotNull(rig.world.peek(DhWorld.LODS, "translucentSections"));

		rig.world.handBack();

		assertEquals(0, rig.world.peek(DhWorld.LODS, "listedCount"));
		assertEquals(0, ((Object[]) rig.world.peek(DhWorld.LODS, "listed")).length);
		assertNull(rig.world.peek(DhWorld.LODS, "opaqueSections"));
		assertNull(rig.world.peek(DhWorld.LODS, "translucentSections"));
	}

	@Test
	void aShrunkListingLetsGoOfTheContainersThatLeftIt() {
		Rig rig = new Rig();
		Object[] scene = rig.standardScene();
		rig.world.frame(true);
		assertSame(scene[1], ((Object[]) rig.world.peek(DhWorld.LODS, "listed"))[1]);

		rig.world.list(scene[1]);
		rig.world.frame(true);

		Object[] listed = (Object[]) rig.world.peek(DhWorld.LODS, "listed");
		assertEquals(1, rig.world.peek(DhWorld.LODS, "listedCount"));
		assertSame(scene[1], listed[0]);
		assertNull(listed[1]);
	}

	// Every way a listing can fail to read.

	@Test
	void aContainerWhoseWrappersAreNotAnArrayDegradesTheClass() {
		Rig rig = new Rig(DhWorld.edited(
				new Edit(BUFFER_CONTAINER, "public Object[] vboOpaqueWrappers;",
						"public java.util.List<Object> vboOpaqueWrappers;"),
				new Edit(BUFFER_CONTAINER, "container.vboOpaqueWrappers = opaque;",
						"container.vboOpaqueWrappers = java.util.Arrays.asList(opaque);")));
		rig.standardScene();

		rig.world.frame(true);

		assertFalse(rig.world.lodsUsable());
		assertEquals(List.of("proxy.defer(false)", "ao.clear", "fog.clear", "original.render(true,set:2,true)"),
				rig.world.events());
		assertEquals(1, rig.world.log().size(), rig.world.log().toString());
		assertLine(rig.world, 0, "WARN", "ClassCastException");
		assertEquals(List.of(), rig.drawn);
	}

	@Test
	void aNullContainerInTheListingDegradesTheClass() {
		Rig rig = new Rig();
		rig.world.list((Object) null);

		rig.world.frame(true);

		assertFalse(rig.world.lodsUsable());
		assertSame(rig.world.stage("original"), rig.world.standing());
		assertLine(rig.world, 0, "WARN", "NullPointerException");
	}

	@Test
	void aSetThatThrowsWhenReadDegradesTheClass() {
		Rig rig = new Rig();
		rig.standardScene();
		rig.world.stage("failOn", "set.get");

		rig.world.frame(true);

		assertFalse(rig.world.lodsUsable());
		assertLine(rig.world, 0, "WARN", "IllegalStateException: fake DH failed at set.get");
		assertEquals(List.of(), rig.drawn);
	}

	@Test
	void aSetThatThrowsACheckedExceptionIsWrappedBeforeItIsCaught() {
		Rig rig = new Rig();
		rig.standardScene();
		rig.world.stage("failOn", "set.get.checked");

		rig.world.frame(true);

		assertFalse(rig.world.lodsUsable());
		assertLine(rig.world, 0, "WARN",
				"java.lang.reflect.InvocationTargetException / java.io.IOException: fake DH failed at set.get.checked");
		assertEquals("original.render(true,set:2,true)", rig.world.events().get(rig.world.events().size() - 1));
	}

	// The stride check.

	private static Object oneVertexBuffer(Rig rig, String name, long bytes, int count, boolean closed) {
		Object buffer = rig.world.buffer(name, bytes);
		if (closed) {
			rig.world.closeBuffer(buffer);
		}
		Object container = rig.world.container(1, 2, 3, new Object[] {
				rig.world.wrapper(buffer, rig.indices(name + "-i"), 6, count) }, null);
		rig.world.list(container);

		return buffer;
	}

	@Test
	void aVertexAsWideAsTheDeclaredFormatPasses() {
		Rig rig = new Rig();
		oneVertexBuffer(rig, "v16", 16 * 4, 4, false);

		rig.world.frame(true);

		assertTrue(rig.world.lodsUsable());
		assertEquals(1, rig.drawn.size());
		assertEquals(List.of("original.render(true,set:1,true)"), rig.world.events());
		assertEquals(1, rig.world.log().size());
	}

	@Test
	void theStrideIsMeasuredOnceAndALaterWiderBufferIsNotSeen() {
		Rig rig = new Rig();
		Object first = oneVertexBuffer(rig, "v16", 16 * 4, 4, false);
		rig.world.frame(true);

		Object second = rig.world.buffer("v20", 20 * 4);
		rig.world.list(rig.world.container(1, 2, 3, new Object[] {
				rig.world.wrapper(first, rig.indices("i1"), 6, 4),
				rig.world.wrapper(second, rig.indices("i2"), 6, 4) }, null));
		rig.world.frame(true);

		assertTrue(rig.world.lodsUsable());
		assertTrue(rig.drawn.get(1).contains("vertices=v20"), rig.drawn.get(1));
	}

	@Test
	void aWiderVertexGivesTheClassUpOnceAndHandsEverythingBack() {
		Rig rig = new Rig();
		oneVertexBuffer(rig, "v20", 20 * 4, 4, false);

		rig.world.frame(true);

		assertFalse(rig.world.lodsUsable());
		assertSame(rig.world.stage("original"), rig.world.standing());
		assertEquals(List.of("proxy.defer(false)", "ao.clear", "fog.clear", "original.render(true,set:1,true)"),
				rig.world.events());
		List<String> warnings = rig.world.log().stream().filter(line -> line.startsWith("WARN ")).toList();
		assertEquals(1, warnings.size(), rig.world.log().toString());
		assertTrue(warnings.get(0).contains("writes 20 bytes a vertex where this engine's format declares 16"),
				warnings.get(0));
		// The answer that walk built is not kept: the road was closed from inside it.
		assertNull(rig.world.peek(DhWorld.LODS, "opaqueSections"));
		assertEquals(0, rig.world.peek(DhWorld.LODS, "listedCount"));
	}

	/**
	 * The road is closed in the middle of the walk that found the fault, and the pass goes on with
	 * what that walk built: the pack still draws it, from buffers read through the wrong format, and
	 * the report still says the terrain is reachable after the warning.
	 */
	@Test
	void knownBug_theFrameThatFindsAWrongStrideIsStillDrawnFromIt() {
		Rig rig = new Rig();
		rig.answer = () -> Boolean.TRUE;
		oneVertexBuffer(rig, "v20", 20 * 4, 4, false);

		rig.world.frame(true);

		assertFalse(rig.world.lodsUsable());
		assertEquals(1, rig.drawn.size());
		assertTrue(rig.drawn.get(0).contains("vertices=v20"), rig.drawn.get(0));
		assertFalse(rig.world.events().contains("original.render(true,set:1,true)"));
		assertLine(rig.world, 0, "WARN", "writes 20 bytes a vertex");
		assertLine(rig.world, 1, "INFO", "is reachable");
	}

	@Test
	void aClosedBufferIsNotMeasuredSoTheCheckWaitsForALiveOne() {
		Rig rig = new Rig();
		oneVertexBuffer(rig, "closed20", 20 * 4, 4, true);
		rig.world.frame(true);

		assertTrue(rig.world.lodsUsable());
		assertEquals(List.of("true []"), rig.drawn);

		oneVertexBuffer(rig, "live20", 20 * 4, 4, false);
		rig.world.frame(true);

		assertFalse(rig.world.lodsUsable());
	}

	@Test
	void aBufferWithNoVerticesIsNotMeasured() {
		Rig rig = new Rig();
		oneVertexBuffer(rig, "none", 20 * 4, 0, false);

		rig.world.frame(true);

		assertTrue(rig.world.lodsUsable());
		assertEquals(List.of("true []"), rig.drawn);
	}

	@Test
	void aSlackShorterThanOneVertexIsNotSeenBecauseTheQuotientIsWhole() {
		Rig rig = new Rig();
		oneVertexBuffer(rig, "slack", 16 * 4 + 3, 4, false);

		rig.world.frame(true);

		assertTrue(rig.world.lodsUsable());
		assertEquals(1, rig.drawn.size());
	}
}
