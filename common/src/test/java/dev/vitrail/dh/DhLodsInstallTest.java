package dev.vitrail.dh;

import static dev.vitrail.dh.DhFakes.DH_API;
import static dev.vitrail.dh.DhFakes.LOD_RENDERER;
import static dev.vitrail.dh.DhFakes.RENDER_PROXY;
import static dev.vitrail.dh.DhLodsResolveTest.assertLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.dh.DhWorld.Edit;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Holds what {@code DhLods.install} does to Distant Horizons frame by frame and what
 * {@code handBack} undoes: the substitution of DH's renderer, its water-defer switch, its two post
 * passes, and what each of them does when DH is not ready or does not cooperate.
 * <p>
 * The expected event lists are read off the order of the calls in {@code install}, {@code hold} and
 * {@code restore}: defer, then swap, then the two switches, and back in the same order.
 */
class DhLodsInstallTest {

	private static final List<String> TAKEN = List.of("proxy.defer(true)", "ao.set(false)", "fog.set(false)");
	private static final List<String> GIVEN_BACK = List.of("proxy.defer(false)", "ao.clear", "fog.clear");

	/** A world with DH up and its startup noise forgotten. */
	private static DhWorld started() {
		DhWorld world = DhWorld.standard();
		world.dhStarted();
		world.clearEvents();

		return world;
	}

	@Test
	void theFirstInstallTakesTheRendererAndHoldsOffTheTwoPostPasses() {
		DhWorld world = started();
		Object original = world.stage("original");

		world.install();

		assertEquals(TAKEN, world.events());
		assertTrue(world.standingIsProxy());
		assertNotSame(original, world.standing());
		assertEquals(Boolean.FALSE, world.stage("effective", "ao"));
		assertEquals(Boolean.FALSE, world.stage("effective", "fog"));

		assertEquals(3, world.log().size(), world.log().toString());
		assertLine(world, 0, "INFO", "Distant Horizons found");
		assertLine(world, 1, "INFO", "defers its water half");
		assertLine(world, 2, "INFO", "holds off its own ambient occlusion and fog");
	}

	@Test
	void aSettledInstallTouchesNothingOfDh() {
		DhWorld world = started();
		world.install();
		world.clearEvents();
		world.clearLog();

		for (int frame = 0; frame < 10; frame++) {
			world.install();
		}

		assertEquals(List.of(), world.events());
		assertEquals(List.of(), world.log());
		assertTrue(world.standingIsProxy());
	}

	@Test
	void handBackGivesTheRendererTheSwitchAndThePlayersValuesBack() {
		DhWorld world = started();
		world.install();
		world.clearEvents();

		world.handBack();

		assertEquals(GIVEN_BACK, world.events());
		assertSame(world.stage("original"), world.standing());
		assertEquals(Boolean.TRUE, world.stage("effective", "ao"));
		assertEquals(Boolean.TRUE, world.stage("effective", "fog"));
	}

	@Test
	void handBackReturnsTheSwitchesToTheMenuAndDoesNotSetThemTrue() {
		DhWorld world = started();
		world.stage("player", "fog", Boolean.FALSE);
		world.install();
		assertEquals(Boolean.FALSE, world.stage("effective", "fog"));

		world.handBack();

		assertEquals(Boolean.TRUE, world.stage("effective", "ao"));
		assertEquals(Boolean.FALSE, world.stage("effective", "fog"));
		world.stage("player", "fog", Boolean.TRUE);
		assertEquals(Boolean.TRUE, world.stage("effective", "fog"));
	}

	@Test
	void aSecondHandBackChangesNothing() {
		DhWorld world = started();
		world.install();
		world.handBack();
		world.clearEvents();

		world.handBack();
		world.handBack();

		assertEquals(List.of(), world.events());
		assertSame(world.stage("original"), world.standing());
	}

	@Test
	void installingAfterAHandBackTakesEverythingAgain() {
		DhWorld world = started();
		world.install();
		world.handBack();
		world.clearEvents();

		world.install();

		assertEquals(TAKEN, world.events());
		assertTrue(world.standingIsProxy());
		// The two lines that describe a takeover are said with every takeover; the found line, once.
		long found = world.log().stream().filter(line -> line.contains("Distant Horizons found")).count();
		long defers = world.log().stream().filter(line -> line.contains("defers its water half")).count();
		long holds = world.log().stream().filter(line -> line.contains("holds off its own ambient")).count();
		assertEquals(1, found);
		assertEquals(2, defers);
		assertEquals(2, holds);
	}

	@Test
	void handBackBeforeAnyInstallDoesNothingAndDoesNotResolve() {
		DhWorld world = started();

		world.handBack();

		assertFalse(world.lodsUsable());
		assertEquals(List.of(), world.events());
		assertEquals(List.of(), world.log());
		assertSame(world.stage("original"), world.standing());
	}

	@Test
	void dhWithNoProxyYetIsNotSubstitutedAndIsAskedAgainNextFrame() {
		DhWorld world = DhWorld.standard();
		world.stage("publishConfigs");
		world.stage("bindRenderer");
		world.clearEvents();

		world.install();
		world.install();

		assertTrue(world.lodsUsable());
		assertEquals(List.of(), world.events());
		assertFalse(world.standingIsProxy());

		world.stage("publishProxy");
		world.install();

		assertEquals(TAKEN, world.events());
		assertTrue(world.standingIsProxy());
	}

	@Test
	void dhThatHasBoundNoRendererYetIsNotSubstitutedAndTheNullIsNeverKept() {
		DhWorld world = DhWorld.standard();
		world.stage("publishProxy");
		world.stage("publishConfigs");
		world.install();

		assertEquals(List.of("LodRenderer.init", "proxy.defer(true)"), world.events());
		assertNull(world.standing());

		world.stage("bindRenderer");
		world.clearEvents();
		world.install();

		// The switch is thrown once; the substitution and the two post passes follow the renderer.
		assertEquals(List.of("ao.set(false)", "fog.set(false)"), world.events());
		assertTrue(world.standingIsProxy());
		world.handBack();
		assertSame(world.stage("original"), world.standing());
	}

	/**
	 * The water-defer switch is thrown before DH's renderer is read, so a pack released while DH
	 * has bound none yet still owes the switch back, and nothing else: no renderer was read, so
	 * none is written.
	 */
	@Test
	void handBackPutsTheDeferSwitchBackWhileDhHasNoRenderer() {
		DhWorld world = DhWorld.standard();
		world.stage("publishProxy");
		world.stage("publishConfigs");
		world.install();
		world.clearEvents();

		world.handBack();

		assertEquals(List.of("proxy.defer(false)"), world.events());
		assertNull(world.standing());
	}

	/** The same debt, reached by a read of the renderer that throws after the switch was thrown. */
	@Test
	void handBackPutsTheDeferSwitchBackWhenTheReadOfTheRendererFailed() {
		DhWorld world = DhWorld.edited(new Edit(LOD_RENDERER, "new LodRenderer()", "null"));
		world.stage("publishProxy");
		world.stage("publishConfigs");
		world.clearEvents();

		world.install();

		assertFalse(world.lodsUsable());
		assertEquals(List.of("LodRenderer.init", "proxy.defer(true)"), world.events());
		assertLine(world, 2, "WARN", "cannot be taken over");

		world.handBack();

		assertEquals(List.of("LodRenderer.init", "proxy.defer(true)", "proxy.defer(false)"), world.events());
	}

	@Test
	void configsNotPublishedYetHoldNothingOffAndAreAskedAgainEveryFrame() {
		DhWorld world = DhWorld.standard();
		world.stage("publishProxy");
		world.stage("bindRenderer");
		world.clearEvents();

		world.install();
		world.install();

		assertEquals(List.of("proxy.defer(true)"), world.events());
		assertTrue(world.standingIsProxy());
		assertTrue(world.log().stream().noneMatch(line -> line.contains("holds off")), world.log().toString());

		world.stage("publishConfigs");
		world.install();

		assertEquals(TAKEN, world.events());
		assertTrue(world.log().stream().anyMatch(line -> line.contains("holds off")), world.log().toString());
	}

	@Test
	void handBackWhileConfigsAreNotPublishedKeepsTheDebtForTheNext() {
		DhWorld world = started();
		world.install();
		world.stage("unpublishConfigs");
		world.clearEvents();

		world.handBack();

		assertEquals(List.of("proxy.defer(false)"), world.events());
		assertSame(world.stage("original"), world.standing());

		world.stage("publishConfigs");
		world.clearEvents();
		world.handBack();

		assertEquals(List.of("ao.clear", "fog.clear"), world.events());
		assertEquals(Boolean.TRUE, world.stage("effective", "ao"));
		world.clearEvents();
		world.handBack();
		assertEquals(List.of(), world.events());
	}

	@Test
	void aSwitchDhRefusesIsCountedAndBothAreStillHandedBack() {
		DhWorld world = started();
		world.stage("refuse", "fog");

		world.install();

		assertEquals(TAKEN, world.events());
		assertTrue(world.log().get(2).contains("lets 1 of its 2 post passes"), world.log().toString());

		world.clearEvents();
		world.handBack();

		assertEquals(GIVEN_BACK, world.events());
	}

	@Test
	void aFailureHoldingOffClearsWhatTookAndIsNeverTriedAgain() {
		DhWorld world = started();
		world.stage("failOn", "fog.set(false)");

		world.install();

		assertEquals(List.of("proxy.defer(true)", "ao.set(false)", "fog.set(false)", "ao.clear", "fog.clear"),
				world.events());
		assertTrue(world.lodsUsable());
		assertTrue(world.standingIsProxy());
		assertEquals(Boolean.TRUE, world.stage("effective", "ao"));
		assertLine(world, 2, "INFO", "keeps drawing its own ambient occlusion");
		assertLine(world, 2, "INFO", "InvocationTargetException");

		world.clearEvents();
		world.install();
		world.install();
		world.handBack();

		// mutable is false and muted never latched: no further attempt, and nothing left to clear.
		assertEquals(List.of("proxy.defer(false)"), world.events());
		assertEquals(3, world.log().size(), world.log().toString());
	}

	/** The class says a second throw "changes nothing": the switch that did take stays forced. */
	@Test
	void aSecondThrowWhileClearingLeavesTheForcedSwitchBehind() {
		DhWorld world = started();
		world.stage("failOn", "fog.set(false)");
		world.stage("failOn", "ao.clear");

		world.install();
		world.handBack();

		assertTrue(world.lodsUsable());
		assertEquals(Boolean.FALSE, world.stage("effective", "ao"));
		assertEquals(List.of("proxy.defer(true)", "ao.set(false)", "fog.set(false)", "ao.clear",
				"proxy.defer(false)"), world.events());
	}

	@Test
	void aConfigRoadThatCannotBeWalkedLeavesTheTerrainTakenOver() {
		DhWorld world = DhWorld.edited(new Edit(DH_API, "configs", "settings"));
		world.stage("publishProxy");
		world.stage("bindRenderer");
		world.clearEvents();

		world.install();
		world.install();

		assertTrue(world.lodsUsable());
		assertTrue(world.standingIsProxy());
		assertEquals(List.of("proxy.defer(true)"), world.events());
		assertEquals(3, world.log().size(), world.log().toString());
		assertLine(world, 2, "INFO", "keeps drawing its own ambient occlusion");
		assertLine(world, 2, "INFO", "NoSuchFieldException");
	}

	@Test
	void restoreGoesOnAfterTheSwitchThrowsAndIsNotRetried() {
		DhWorld world = started();
		world.install();
		world.stage("failOn", "proxy.defer(false)");
		world.clearEvents();
		world.clearLog();

		world.handBack();

		assertEquals(GIVEN_BACK, world.events());
		assertSame(world.stage("original"), world.standing());
		assertEquals(1, world.log().size());
		assertLine(world, 0, "DEBUG", "deferred water switch cannot be put back");

		world.stage("heal", "proxy.defer(false)");
		world.clearEvents();
		world.handBack();

		assertEquals(List.of(), world.events());
	}

	@Test
	void aFailedClearAtHandBackIsRetriedByTheNext() {
		DhWorld world = started();
		world.install();
		world.stage("failOn", "ao.clear");
		world.clearEvents();
		world.clearLog();

		world.handBack();

		assertEquals(List.of("proxy.defer(false)", "ao.clear"), world.events());
		assertEquals(Boolean.FALSE, world.stage("effective", "ao"));
		assertEquals(1, world.log().size());
		assertLine(world, 0, "DEBUG", "cannot be handed back");

		world.stage("heal", "ao.clear");
		world.clearEvents();
		world.handBack();

		assertEquals(List.of("ao.clear", "fog.clear"), world.events());
		assertEquals(Boolean.TRUE, world.stage("effective", "ao"));
		world.clearEvents();
		world.handBack();
		assertEquals(List.of(), world.events());
	}

	@Test
	void aSwitchThatThrowsWhenThrownFailsTheInstallOnceAndTakesNothing() {
		DhWorld world = started();
		world.stage("failOn", "proxy.defer(true)");

		world.install();
		world.install();

		assertFalse(world.lodsUsable());
		assertFalse(world.standingIsProxy());
		assertEquals(List.of("proxy.defer(true)"), world.events());
		assertEquals(2, world.log().size(), world.log().toString());
		assertLine(world, 1, "WARN", "cannot be taken over");
		assertLine(world, 1, "WARN", "InvocationTargetException / java.lang.IllegalStateException");

		world.handBack();
		assertEquals(List.of("proxy.defer(true)"), world.events());
	}

	@Test
	void aRendererFieldThatCannotBeWrittenFailsTheInstallAndHandBackStillPutsTheSwitchBack() {
		DhWorld world = DhWorld.edited(
				new Edit(LOD_RENDERER, "private IDhTerrainRenderer terrainRenderer;",
						"private static final IDhTerrainRenderer terrainRenderer ="
								+ " new com.seibel.distanthorizons.fake.OriginalRenderer();"),
				new Edit(LOD_RENDERER, "INSTANCE.terrainRenderer = (IDhTerrainRenderer) renderer;", ""));
		world.dhStarted();
		world.clearEvents();

		world.install();

		assertFalse(world.lodsUsable());
		assertFalse(world.standingIsProxy());
		assertEquals(List.of("proxy.defer(true)"), world.events());
		assertLine(world, 2, "WARN", "cannot be taken over");

		world.handBack();

		// The renderer had been read, so writing it back is tried and fails on a line of its own,
		// and the switch goes back all the same.
		assertEquals(List.of("proxy.defer(true)", "proxy.defer(false)"), world.events());
		assertLine(world, 3, "DEBUG", "far terrain renderer cannot be put back");
	}

	@Test
	void aRenderProxyWithNoSwitchIsAFailureOfResolveNotOfInstall() {
		DhWorld world = DhWorld.edited(new Edit(RENDER_PROXY, "setDeferTransparentRendering", "setDeferred"));
		world.dhStarted();
		world.clearEvents();

		world.install();

		assertFalse(world.lodsUsable());
		assertEquals(List.of(), world.events());
		assertFalse(world.standingIsProxy());
	}
}
