package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.FakeWorld;
import dev.vitrail.uniform.UniformCatalog;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * Holds the wall clock values to what a pack reads: one instant, sampled once a frame, from which
 * the date, the time and the seconds of the year are all taken.
 * <p>
 * The clock is the machine's, so two kinds of check are made. The first pins an instant into the
 * clock's sample by reflection (the sample and the frame it was taken in are private fields) and
 * compares the three names with numbers worked out by hand, leap years included; that is also how
 * "sampled once a frame" is shown, since a pinned instant only survives a read that does not sample
 * again. The second reads the live clock and holds the three names to each other and to the
 * calendar, so that a frame straddling midnight on the machine running the tests cannot fail them:
 * the three are read together, from one sample, and the checks are on that sample.
 */
class TimeValuesTest {

	private final UniformCatalog engine = UniformCatalog.engine();

	private int[] read(String name, FakeWorld world, int rank) {
		return ValueReads.ints(ValueReads.read(this.engine, name, world), rank);
	}

	/** Makes the clock believe that {@code instant} was sampled in frame {@code frame}. */
	private static void pin(int frame, LocalDateTime instant) throws ReflectiveOperationException {
		Field clock = TimeValues.class.getDeclaredField("WALL_CLOCK");
		clock.setAccessible(true);
		Object wallClock = clock.get(null);

		Field lastFrame = wallClock.getClass().getDeclaredField("lastFrame");
		lastFrame.setAccessible(true);
		lastFrame.setInt(wallClock, frame);

		Field sampled = wallClock.getClass().getDeclaredField("sampled");
		sampled.setAccessible(true);
		sampled.set(wallClock, instant);
	}

	private void expect(FakeWorld world, int[] date, int[] time, int[] year, String what) {
		assertArrayEquals(date, read("currentDate", world, 3), what + " date");
		assertArrayEquals(time, read("currentTime", world, 3), what + " time");
		assertArrayEquals(year, read("currentYearTime", world, 2), what + " year time");
	}

	@Test
	void theStartOfANonLeapYearHasNothingElapsedAndAWholeYearToGo() throws ReflectiveOperationException {
		FakeWorld world = new FakeWorld();
		world.frameCounter = 700_001;
		pin(700_001, LocalDateTime.of(1999, 1, 1, 0, 0, 0));

		expect(world, new int[] { 1999, 1, 1 }, new int[] { 0, 0, 0 }, new int[] { 0, 365 * 86_400 }, "1 January");
	}

	@Test
	void theFirstOfMarchOfANonLeapYearHasFiftyNineDaysBehindIt() throws ReflectiveOperationException {
		FakeWorld world = new FakeWorld();
		world.frameCounter = 700_002;
		pin(700_002, LocalDateTime.of(1999, 3, 1, 0, 0, 0));

		// January 31 and February 28: 59 days of 86400 seconds elapsed, the rest of 365 days to go.
		expect(world, new int[] { 1999, 3, 1 }, new int[] { 0, 0, 0 },
				new int[] { 59 * 86_400, 306 * 86_400 }, "1 March");
	}

	@Test
	void theLastSecondOfALeapYearLeavesOneSecondOfThreeHundredAndSixtySixDays() throws ReflectiveOperationException {
		FakeWorld world = new FakeWorld();
		world.frameCounter = 700_003;
		pin(700_003, LocalDateTime.of(2000, 12, 31, 23, 59, 59));

		// Day 366: 365 whole days behind it, then 86399 seconds of the last.
		expect(world, new int[] { 2000, 12, 31 }, new int[] { 23, 59, 59 },
				new int[] { 365 * 86_400 + 86_399, 1 }, "31 December 2000");
	}

	@Test
	void aTimeOfDayCountsHoursMinutesAndSecondsInThatOrder() throws ReflectiveOperationException {
		FakeWorld world = new FakeWorld();
		world.frameCounter = 700_004;
		pin(700_004, LocalDateTime.of(1999, 1, 2, 3, 4, 5));

		// One day, then 3 * 3600 + 4 * 60 + 5 = 11045 seconds.
		expect(world, new int[] { 1999, 1, 2 }, new int[] { 3, 4, 5 },
				new int[] { 86_400 + 11_045, 365 * 86_400 - 86_400 - 11_045 }, "2 January, 03:04:05");
	}

	@Test
	void everyReadOfAFrameGetsThePinnedInstantAndNeverTheClockAgain() throws ReflectiveOperationException {
		FakeWorld world = new FakeWorld();
		world.frameCounter = 700_005;
		pin(700_005, LocalDateTime.of(1999, 6, 7, 8, 9, 10));

		// 7 June is the 158th day: 157 whole days behind it, and 08:09:10 into the 158th.
		int elapsed = 157 * 86_400 + 8 * 3600 + 9 * 60 + 10;
		for (int pass = 0; pass < 100; pass++) {
			expect(world, new int[] { 1999, 6, 7 }, new int[] { 8, 9, 10 },
					new int[] { elapsed, 365 * 86_400 - elapsed }, "pass " + pass);
		}
	}

	@Test
	void aNewFrameTakesANewSampleFromTheClock() throws ReflectiveOperationException {
		FakeWorld world = new FakeWorld();
		world.frameCounter = 700_006;
		pin(700_006, LocalDateTime.of(1999, 6, 7, 8, 9, 10));
		assertEquals(1999, read("currentDate", world, 3)[0]);

		world.frameCounter = 700_007;

		assertNotEquals(1999, read("currentDate", world, 3)[0], "the machine is not in 1999");
	}

	@Test
	void theLiveClockGivesADateATimeAndAYearTimeThatDescribeOneInstant() {
		FakeWorld world = new FakeWorld();
		world.frameCounter = 500_001;

		int[] date = read("currentDate", world, 3);
		int[] time = read("currentTime", world, 3);
		int[] year = read("currentYearTime", world, 2);

		LocalDate day = LocalDate.of(date[0], date[1], date[2]);
		int elapsed = (day.getDayOfYear() - 1) * 86_400 + time[0] * 3600 + time[1] * 60 + time[2];

		assertEquals(elapsed, year[0], "seconds since the start of the year");
		assertEquals(day.lengthOfYear() * 86_400 - elapsed, year[1], "and seconds left in it");
	}

	@Test
	void theLiveComponentsAreInRangeAndInTheOrderTheNamesSay() {
		FakeWorld world = new FakeWorld();
		world.frameCounter = 500_002;

		int[] date = read("currentDate", world, 3);
		int[] time = read("currentTime", world, 3);

		assertTrue(date[0] >= 2000, "year first: " + date[0]);
		assertTrue(date[1] >= 1 && date[1] <= 12, "then month: " + date[1]);
		assertTrue(date[2] >= 1 && date[2] <= 31, "then day: " + date[2]);
		assertTrue(time[0] >= 0 && time[0] <= 23, "hour first: " + time[0]);
		assertTrue(time[1] >= 0 && time[1] <= 59, "then minute: " + time[1]);
		assertTrue(time[2] >= 0 && time[2] <= 59, "then second: " + time[2]);
	}
}
