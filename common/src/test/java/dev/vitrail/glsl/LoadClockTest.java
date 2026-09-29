package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link LoadClock} to what a load report reads off it: three posts each with a span in
 * milliseconds and a count, and a second number for the units an opening handed back.
 * <p>
 * The tallies are statics that a pack load empties at its head, so every test does the same before
 * and after itself: another test that translated something has counted into them.
 */
class LoadClockTest {

	@BeforeEach
	void startEmpty() {
		LoadClock.reset();
	}

	@AfterEach
	void leaveEmpty() {
		LoadClock.reset();
	}

	@Test
	void aTallyStartsEmptyAndEveryPostCountsItsOwnSpansAndItsOwnCalls() {
		assertEquals(0L, LoadClock.expansionMillis());
		assertEquals(0, LoadClock.expanded());
		assertEquals(0, LoadClock.expansionsServed());
		assertEquals(0L, LoadClock.translationMillis());
		assertEquals(0, LoadClock.translated());
		assertEquals(0L, LoadClock.moduleMillis());
		assertEquals(0, LoadClock.modules());

		LoadClock.expansion(3_000_000L);
		LoadClock.expansion(4_000_000L);
		LoadClock.translation(10_000_000L);
		LoadClock.module(20_000_000L);
		LoadClock.module(1_000_000L);
		LoadClock.module(1_000_000L);

		assertEquals(7L, LoadClock.expansionMillis());
		assertEquals(2, LoadClock.expanded());
		assertEquals(10L, LoadClock.translationMillis());
		assertEquals(1, LoadClock.translated());
		assertEquals(22L, LoadClock.moduleMillis());
		assertEquals(3, LoadClock.modules());
	}

	@Test
	void aUnitHandedBackIsCountedApartFromTheOnesBuilt() {
		LoadClock.expansionServed();
		LoadClock.expansionServed();
		LoadClock.expansion(1_000_000L);

		assertEquals(2, LoadClock.expansionsServed());
		assertEquals(1, LoadClock.expanded());
		assertEquals(1L, LoadClock.expansionMillis());
	}

	@Test
	void millisecondsAreWholeAndRoundDownWhereTheSpansAddUpToMoreThanTheyShowSeparately() {
		LoadClock.expansion(999_999L);
		assertEquals(0L, LoadClock.expansionMillis());

		LoadClock.expansion(999_999L);
		// Summed in nanoseconds and divided once, so two spans that each read as nothing read as one.
		assertEquals(1L, LoadClock.expansionMillis());
		assertEquals(2, LoadClock.expanded());
	}

	@Test
	void resetEmptiesAllSevenTalliesAtOnce() {
		LoadClock.expansion(5_000_000L);
		LoadClock.expansionServed();
		LoadClock.translation(5_000_000L);
		LoadClock.module(5_000_000L);

		LoadClock.reset();

		assertEquals(0L, LoadClock.expansionMillis() + LoadClock.translationMillis() + LoadClock.moduleMillis());
		assertEquals(0, LoadClock.expanded() + LoadClock.expansionsServed() + LoadClock.translated() + LoadClock.modules());
	}
}
