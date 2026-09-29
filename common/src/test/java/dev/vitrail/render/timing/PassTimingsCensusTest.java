package dev.vitrail.render.timing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.vitrail.Vitrail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The count of what one frame of a pack costs, driven the way the render layer drives it and read
 * off the log line by line, which is the only place it says anything.
 * <p>
 * No device is needed: the census half of {@code PassTimings} is integers and a map, and its calls
 * take an encoder they only hand on to the timed half, which is off here (the JVM flag that arms it
 * is not set). The frame rate line is wall clock and is left out of every comparison; what is
 * compared is everything the counts decide: the header, the two totals lines, the rows and their
 * order, the fold of the tail, the reasons under a row, the missed shadow map line, and the
 * frame spread that prints once two hundred intervals are held.
 */
class PassTimingsCensusTest {

	private static final Pattern RATE = Pattern.compile("^ {2}[0-9.]+ frames a second over the last .*");

	private Capture capture;
	private Logger logger;
	private Level before;

	@BeforeEach
	void listen() {
		PassTimings.resetCensus();
		this.logger = (Logger) LogManager.getLogger(Vitrail.MOD_NAME);
		this.before = this.logger.getLevel();
		Configurator.setLevel(Vitrail.MOD_NAME, Level.INFO);
		this.capture = new Capture();
		this.capture.start();
		this.logger.addAppender(this.capture);
	}

	@AfterEach
	void stopListening() {
		this.logger.removeAppender(this.capture);
		this.capture.stop();
		Configurator.setLevel(Vitrail.MOD_NAME, this.before);
		PassTimings.resetCensus();
	}

	/** Every message the logger of this mod was asked to write, formatted. */
	private static final class Capture extends AbstractAppender {

		private final List<String> lines = new ArrayList<>();

		Capture() {
			super("census-capture", null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			this.lines.add(event.getMessage().getFormattedMessage());
		}
	}

	/** What was logged, without the frame rate line, which is a reading of the clock. */
	private List<String> printed() {
		List<String> lines = new ArrayList<>();
		for (String line : this.capture.lines) {
			if (!RATE.matcher(line).matches()) {
				lines.add(line);
			}
		}

		return lines;
	}

	private static void pass(String label) {
		PassTimings.open(null, () -> label);
		PassTimings.close(null);
	}

	private static void passes(String label, int times) {
		for (int i = 0; i < times; i++) {
			pass(label);
		}
	}

	/** One frame from arming to its close, printed if the chain was warm. */
	private static void frame(Runnable work, boolean warm) {
		PassTimings.armCensus();
		work.run();
		if (warm) {
			PassTimings.finishCensus();
		}

		PassTimings.endFrame();
	}

	private static void setCensusSeconds(int seconds) throws ReflectiveOperationException {
		Field field = PassTimings.class.getDeclaredField("censusSeconds");
		field.setAccessible(true);
		field.setInt(null, seconds);
	}

	/** Moves the moment the last census was printed, so that an interval can be up without waiting it. */
	private static void setLastCensus(long nanos) throws ReflectiveOperationException {
		Field field = PassTimings.class.getDeclaredField("lastCensus");
		field.setAccessible(true);
		field.setLong(null, nanos);
	}

	// -- the totals and the rows --------------------------------------------------------------

	@Test
	void theFirstFullFrameIsCountedAndPrintedAsTotalsThenRowsMostFrequentFirst() {
		frame(() -> {
			passes("terrain", 3);
			pass("final");
			passes("composite1", 2);
			PassTimings.censusClear();
			PassTimings.censusClear();
			PassTimings.censusCopy();
			for (int i = 0; i < 5; i++) {
				PassTimings.censusSubmit();
			}

			for (int i = 0; i < 7; i++) {
				PassTimings.censusSlice();
			}

			PassTimings.censusProgramWalk();
			PassTimings.censusProgramWalk();
			PassTimings.censusFarSections(11);
			PassTimings.censusFarSections(4);
		}, true);

		assertEquals(List.of(
				"This pack's first full frame opened 6 render passes, cleared 2 textures and copied 1, for 5 "
						+ "queue submits",
				"  and redid 7 uniform slices, 2 terrain program walks and 15 far terrain sections",
				"  x3  terrain",
				"  x2  composite1",
				"  x1  final"), printed());
	}

	@Test
	void anEmptyFrameStillSaysWhatItCounted() {
		frame(() -> { }, true);

		assertEquals(List.of(
				"This pack's first full frame opened 0 render passes, cleared 0 textures and copied 0, for 0 "
						+ "queue submits",
				"  and redid 0 uniform slices, 0 terrain program walks and 0 far terrain sections"), printed());
	}

	@Test
	void rowsAreSortedByCountAndEqualCountsAreAllThereInWhicheverOrder() {
		frame(() -> {
			passes("a", 1);
			passes("b", 4);
			passes("c", 2);
			passes("d", 4);
			passes("e", 3);
		}, true);

		List<String> rows = printed().subList(2, 7);
		assertTrue(rows.subList(0, 2).containsAll(List.of("  x4  b", "  x4  d")), rows.toString());
		assertEquals(List.of("  x3  e", "  x2  c", "  x1  a"), rows.subList(2, 5));
	}

	@Test
	void theTwentyFourMostFrequentLabelsAreListedAndTheRestIsOneLine() {
		frame(() -> {
			for (int label = 1; label <= 30; label++) {
				passes("label" + label, label);
			}
		}, true);

		List<String> lines = printed();
		// two totals lines, twenty-four rows, one fold
		assertEquals(2 + 24 + 1, lines.size(), lines.toString());
		assertEquals("  x30  label30", lines.get(2));
		assertEquals("  x7  label7", lines.get(25));
		// labels 1 to 6 are what is left: 1 + 2 + 3 + 4 + 5 + 6 passes under six labels
		assertEquals("  x21  6 other labels", lines.get(26));
	}

	@Test
	void exactlyTwentyFourLabelsNeedNoFoldAndTwentyFiveFoldTheLast() {
		frame(() -> {
			for (int label = 1; label <= 24; label++) {
				passes("label" + label, label);
			}
		}, true);
		assertEquals(2 + 24, printed().size());
		assertFalse(printed().get(printed().size() - 1).contains("other labels"));

		this.capture.lines.clear();
		PassTimings.resetCensus();
		frame(() -> {
			for (int label = 1; label <= 25; label++) {
				passes("label" + label, label);
			}
		}, true);
		List<String> lines = printed();
		assertEquals(2 + 24 + 1, lines.size());
		assertEquals("  x1  1 other labels", lines.get(lines.size() - 1), "the wording does not pluralise");
	}

	@Test
	void aLabelIsReadWhenThePassIsClosedAndNotWhenItIsOpened() {
		String[] name = {"before"};
		PassTimings.armCensus();
		PassTimings.open(null, () -> name[0]);
		name[0] = "after";
		PassTimings.close(null);
		PassTimings.finishCensus();
		PassTimings.endFrame();

		assertEquals("  x1  after", printed().get(2));
	}

	// -- what is counted and when -------------------------------------------------------------

	@Test
	void nothingIsCountedBeforeTheFrameIsArmed() {
		pass("early");
		PassTimings.censusClear();
		PassTimings.censusSubmit();
		PassTimings.censusSlice();
		PassTimings.censusFarSections(9);

		frame(() -> pass("real"), true);

		assertEquals(List.of(
				"This pack's first full frame opened 1 render passes, cleared 0 textures and copied 0, for 0 "
						+ "queue submits",
				"  and redid 0 uniform slices, 0 terrain program walks and 0 far terrain sections",
				"  x1  real"), printed());
	}

	@Test
	void aPassClosedWithoutBeingOpenedOrOpenedWithoutALabelIsNotCounted() {
		frame(() -> {
			PassTimings.close(null);
			PassTimings.open(null, null);
			PassTimings.close(null);
			pass("counted");
			PassTimings.close(null);
		}, true);

		assertEquals("This pack's first full frame opened 1 render passes, cleared 0 textures and copied 0, "
				+ "for 0 queue submits", printed().get(0));
		assertEquals("  x1  counted", printed().get(2));
	}

	@Test
	void aSecondOpenBeforeTheCloseReplacesTheLabelOfTheFirst() {
		frame(() -> {
			PassTimings.open(null, () -> "first");
			PassTimings.open(null, () -> "second");
			PassTimings.close(null);
		}, true);

		assertEquals("  x1  second", printed().get(2));
	}

	@Test
	void armingTwiceInOneFrameKeepsWhatWasCounted() {
		PassTimings.armCensus();
		pass("kept");
		PassTimings.armCensus();
		pass("kept");
		PassTimings.finishCensus();
		PassTimings.endFrame();

		assertEquals("  x2  kept", printed().get(2));
	}

	@Test
	void aFrameWhoseChainIsNotWarmPrintsNothingAndTheNextOneStartsFromNothing() {
		frame(() -> passes("compiling", 9), false);
		assertTrue(printed().isEmpty(), "nothing yet: " + printed());

		frame(() -> pass("warm"), true);
		assertEquals("  x1  warm", printed().get(2), "the nine of the cold frame are not carried over");
		assertEquals("This pack's first full frame opened 1 render passes, cleared 0 textures and copied 0, "
				+ "for 0 queue submits", printed().get(0));
	}

	@Test
	void theReasonsOfAFrameThatPrintedNothingAreNotCarriedIntoTheNext() {
		frame(() -> {
			pass("terrain");
			PassTimings.censusReopen(() -> "terrain", "the cold frame's reason");
		}, false);

		frame(() -> pass("terrain"), true);

		assertEquals(List.of("  x1  terrain"), printed().subList(2, printed().size()));
	}

	@Test
	void finishingWithoutBeingArmedPrintsNothing() {
		PassTimings.finishCensus();
		PassTimings.endFrame();

		assertTrue(printed().isEmpty());
	}

	@Test
	void theCensusIsOneShotUntilAPackLoadResetsIt() {
		frame(() -> pass("once"), true);
		int firstPrint = printed().size();
		assertTrue(firstPrint > 0);

		boolean[] armedDuring = {true};
		frame(() -> {
			armedDuring[0] = PassTimings.censusArmed();
			pass("twice");
		}, true);
		assertEquals(firstPrint, printed().size(), "the second full frame prints nothing");
		assertFalse(armedDuring[0], "and is not even counted");

		PassTimings.resetCensus();
		frame(() -> pass("again"), true);
		assertEquals(2 * firstPrint, printed().size(), "a pack load counts the next full frame again");
		assertEquals("  x1  again", printed().get(printed().size() - 1));
	}

	@Test
	void theCensusIsArmedFromTheFramesOpenAndDisarmedAtItsClose() {
		assertFalse(PassTimings.censusArmed());
		PassTimings.armCensus();
		assertTrue(PassTimings.censusArmed());
		PassTimings.endFrame();
		assertFalse(PassTimings.censusArmed());
	}

	@Test
	void aRepeatingCensusIsPrintedAsAFrameOfThePackAndWaitsItsIntervalOut() throws ReflectiveOperationException {
		setCensusSeconds(Integer.MAX_VALUE);

		frame(() -> pass("steady"), true);
		assertTrue(printed().get(0).startsWith("A frame of this pack opened 1 render passes"), printed().get(0));

		// The interval is not up, so the next frame is not counted.
		PassTimings.armCensus();
		assertFalse(PassTimings.censusArmed());
	}

	@Test
	void aRepeatingCensusIsArmedAgainOnceItsIntervalIsUp() throws ReflectiveOperationException {
		setCensusSeconds(1);
		frame(() -> pass("steady"), true);
		int firstPrint = printed().size();

		// Not up yet: the print above was an instant ago.
		PassTimings.armCensus();
		assertFalse(PassTimings.censusArmed());

		// Up: the last one is moved five seconds back, which reads no clock to decide anything.
		setLastCensus(System.nanoTime() - 5_000_000_000L);
		PassTimings.armCensus();
		assertTrue(PassTimings.censusArmed());
		pass("again");
		PassTimings.finishCensus();
		PassTimings.endFrame();

		assertEquals(2 * firstPrint, printed().size(), "printed a second time");
		assertEquals("  x1  again", printed().get(printed().size() - 1));
		assertTrue(printed().get(printed().size() - 3).startsWith("A frame of this pack opened 1 render passes"));
	}

	// -- why a family opened a pass -----------------------------------------------------------

	@Test
	void theReasonsAFamilyReopenedAPassAreListedUnderItsRowMostFrequentFirst() {
		frame(() -> {
			passes("terrain", 6);
			pass("final");
			PassTimings.censusReopen(() -> "terrain", "the blend changed");
			PassTimings.censusReopen(() -> "terrain", "the target changed");
			PassTimings.censusReopen(() -> "terrain", "the target changed");
			PassTimings.censusReopen(() -> "terrain", "the target changed");
			PassTimings.censusReopen(() -> "terrain", "the blend changed");
		}, true);

		assertEquals(List.of("  x6  terrain", "        x3  because the target changed",
				"        x2  because the blend changed", "  x1  final"), printed().subList(2, 6));
	}

	@Test
	void aReasonForALabelThatOpenedNoPassIsNeverPrintedAndAnUnnamedOneNeitherUnlessItIsARow() {
		frame(() -> {
			pass("terrain");
			PassTimings.censusReopen(() -> "ghost", "nobody drew it");
			PassTimings.censusReopen(null, "no label");
		}, true);

		assertEquals(List.of("  x1  terrain"), printed().subList(2, printed().size()));
	}

	@Test
	void aReasonIsNotKeptWhileNoCensusIsArmed() {
		PassTimings.censusReopen(() -> "terrain", "too early");
		frame(() -> pass("terrain"), true);

		assertEquals(List.of("  x1  terrain"), printed().subList(2, printed().size()));
	}

	@Test
	void reasonsAndRowsAreClearedForTheNextCensus() {
		frame(() -> {
			pass("terrain");
			PassTimings.censusReopen(() -> "terrain", "first census");
		}, true);
		PassTimings.resetCensus();
		this.capture.lines.clear();

		frame(() -> pass("terrain"), true);

		assertEquals(List.of("  x1  terrain"), printed().subList(2, printed().size()));
	}

	// -- the shadow map that was not drawn ---------------------------------------------------

	@Test
	void theMissedShadowMapLineIsSilentAtZeroAndOtherwiseCountsMissesAgainstDecisions() {
		for (int i = 0; i < 5; i++) {
			PassTimings.shadowMap(false);
		}

		frame(() -> pass("terrain"), true);
		assertFalse(String.join("\n", printed()).contains("shadow map"), "a healthy session is silent");

		PassTimings.resetCensus();
		this.capture.lines.clear();
		for (int i = 0; i < 3; i++) {
			PassTimings.shadowMap(false);
		}

		PassTimings.shadowMap(true);
		frame(() -> pass("terrain"), true);

		assertTrue(printed().contains("  1 of 4 frames this pack drew read a shadow map the frame that planned it "
				+ "never drew"), printed().toString());
	}

	@Test
	void aPackLoadForgetsTheMissedMapsOfThePackBefore() {
		PassTimings.shadowMap(true);
		PassTimings.shadowMap(true);
		PassTimings.resetCensus();

		frame(() -> pass("terrain"), true);

		assertFalse(String.join("\n", printed()).contains("shadow map"));
	}

	// -- the spread of the frames -----------------------------------------------------------

	private static final Pattern SPREAD = Pattern.compile(
			"^ {2}middle frame ([0-9.]+) ms, one in a hundred over ([0-9.]+) ms, worst ([0-9.]+) ms, "
					+ "([0-9]+) of the last ([0-9]+) frames late$");

	/** The frame spread line after this many frames have ended, the last of them the census frame. */
	private Matcher spreadAfter(int frames) {
		PassTimings.resetCensus();
		this.capture.lines.clear();
		for (int i = 0; i < frames - 1; i++) {
			PassTimings.endFrame();
		}

		frame(() -> pass("terrain"), true);

		for (String line : this.capture.lines) {
			Matcher matcher = SPREAD.matcher(line);
			if (matcher.matches()) {
				return matcher;
			}
		}

		return null;
	}

	@Test
	void theSpreadIsSilentUnderTwoHundredIntervalsAndPrintsAtTwoHundred() {
		// n frames make n - 1 intervals: the first one has nothing to measure from.
		assertNull(spreadAfter(200), "199 intervals");
		Matcher at = spreadAfter(201);

		assertNotNull(at, "200 intervals");
		assertEquals("200", at.group(5));
	}

	@Test
	void theSpreadWindowHoldsFourThousandAndNinetySixIntervalsAndNoMore() {
		Matcher full = spreadAfter(5000);

		assertNotNull(full);
		assertEquals("4096", full.group(5), "the ring keeps the last few thousand and drops the oldest");
	}

	@Test
	void theSpreadIsOrderedMiddleThenOneInAHundredThenWorstAndTheLateCountFitsTheWindow() {
		Matcher spread = spreadAfter(1000);

		assertNotNull(spread);
		double middle = Double.parseDouble(spread.group(1));
		double tail = Double.parseDouble(spread.group(2));
		double worst = Double.parseDouble(spread.group(3));
		assertTrue(middle <= tail && tail <= worst, spread.group());
		assertEquals("999", spread.group(5));
		assertTrue(Integer.parseInt(spread.group(4)) <= 999);
	}

	@Test
	void aPackLoadEmptiesTheWindowSoOnePacksSpreadIsNeverReadOffTheFramesOfTheOneBefore() {
		for (int i = 0; i < 300; i++) {
			PassTimings.endFrame();
		}

		PassTimings.resetCensus();
		this.capture.lines.clear();
		for (int i = 0; i < 50; i++) {
			PassTimings.endFrame();
		}

		frame(() -> pass("terrain"), true);

		for (String line : this.capture.lines) {
			assertFalse(SPREAD.matcher(line).matches(), "only 50 intervals since the load: " + line);
		}
	}

	// -- the switches ------------------------------------------------------------------------

	@Test
	void everyGeometryProgramSharesTheRingUnlessTheSwitchIsSet() {
		assumeFalse(Boolean.getBoolean("vitrail.ringPerProgram"),
				"the switch is on for this run, which is what is being tested");

		assertFalse(PassTimings.ringPerProgram());
	}

	// -- the appender itself -----------------------------------------------------------------

	@Test
	void theCaptureSeesWhatTheEngineLogs() {
		Vitrail.logger().info("a line of the test");

		assertEquals(List.of("a line of the test"), this.capture.lines);
	}
}
