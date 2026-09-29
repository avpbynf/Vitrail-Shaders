package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds the counters a load prints and the one word that summarises them: a unit is clean when no
 * include was missing, cyclic, too deep or cut by the budget, and merely skipping an include is not an
 * error.
 */
class ExpansionStatsTest {

	private static final ExpansionStats A = new ExpansionStats(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
	private static final ExpansionStats B = new ExpansionStats(10, 20, 30, 40, 50, 60, 3, 80, 90, 100, 110);

	@Test
	void addsEveryCounterAndTakesTheDeeperOfTheTwoDepths() {
		assertEquals(new ExpansionStats(11, 22, 33, 44, 55, 66, 7, 88, 99, 110, 121), A.plus(B));
		assertEquals(new ExpansionStats(11, 22, 33, 44, 55, 66, 7, 88, 99, 110, 121), B.plus(A));
		assertEquals(A, A.plus(ExpansionStats.NONE));
		assertEquals(A, ExpansionStats.NONE.plus(A));
	}

	@Test
	void isCleanUnlessAnIncludeWasMissingCyclicTooDeepOrCutByTheBudget() {
		assertTrue(ExpansionStats.NONE.clean());
		assertTrue(new ExpansionStats(5, 3, 2, 0, 9, 0, 4, 0, 7, 6, 0).clean());
		assertFalse(new ExpansionStats(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0).clean());
		assertFalse(new ExpansionStats(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0).clean());
		assertFalse(new ExpansionStats(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0).clean());
		assertFalse(new ExpansionStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1).clean());
	}

	@Test
	void writesEveryCounterInTheOrderTheLogLineHasThem() {
		assertEquals("seen 1, followed 2, skipped 3, missing 4, duplicates 5, cycles 6, max depth 7, too deep 8,"
				+ " conditionals 9, undecidable 10, budget exhausted 11", A.toString());
	}

	@Test
	void theShadowCullingWordsMeanWhatIrisMapsThemTo() {
		assertEquals(ShadowCullState.DISTANCE, ShadowCullState.of("false"));
		assertEquals(ShadowCullState.ADVANCED, ShadowCullState.of("true"));
		assertEquals(ShadowCullState.SAFE_ZONE, ShadowCullState.of("reversed"));
		assertEquals(ShadowCullState.SAFE_ZONE, ShadowCullState.of("safe_zone"));
		assertNull(ShadowCullState.of("TRUE"));
		assertNull(ShadowCullState.of(""));
		assertNull(ShadowCullState.of("default"));
	}

	@Test
	void theShadowCastersSayWhatAFeaturePassHasToDraw() {
		assertFalse(new ShadowCasters(true, true, false, false, false, false).anyFeature());
		assertTrue(new ShadowCasters(false, false, false, true, false, false).anyFeature());
		assertTrue(new ShadowCasters(false, false, false, false, false, true).anyBlockEntity());
		assertTrue(new ShadowCasters(false, false, false, false, false, true).emittersOnly());
		assertFalse(new ShadowCasters(false, false, false, false, true, true).emittersOnly());
		assertFalse(new ShadowCasters(false, false, false, false, false, false).anyBlockEntity());
	}
}
