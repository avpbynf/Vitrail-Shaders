package dev.vitrail.pack.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Holds what a user's list of passes to run, and the passes this engine refuses, keep of a chain. The
 * parse is a debugging knob and its lopsided corners are pinned as they are: moving one changes which
 * pass a bisection lands on.
 */
class ChainFilterTest {

	@Test
	void readsANumberAsALimitOnTheRankAPassMayHave() {
		ChainFilter three = ChainFilter.parse("3");

		assertEquals(List.of(), three.only());
		assertEquals(3, three.limit());
		assertTrue(three.accepts("composite2", 2));
		assertFalse(three.accepts("composite3", 3));
		assertFalse(ChainFilter.parse("0").accepts("composite", 0));
	}

	@Test
	void readsACommaListAsTheOnlyPassesToKeepWhateverTheirRank() {
		ChainFilter only = ChainFilter.parse(" composite4 , composite5 ");

		assertEquals(List.of("composite4", "composite5"), only.only());
		assertEquals(-1, only.limit());
		assertTrue(only.accepts("composite4", 99));
		assertFalse(only.accepts("composite6", 0));
	}

	@Test
	void keepsAFilterWithATrailingCommaAndDropsOneWithALeadingComma() {
		// Lopsided on purpose: "composite4," is a filter and ",composite4" is no filter at all.
		assertEquals(List.of("composite4"), ChainFilter.parse("composite4,").only());
		assertSame(ChainFilter.ALL, ChainFilter.parse(",composite4"));
		assertSame(ChainFilter.ALL, ChainFilter.parse("a,,b"));
	}

	@Test
	void anythingItCannotReadIsNoFilter() {
		assertSame(ChainFilter.ALL, ChainFilter.parse(null));
		assertSame(ChainFilter.ALL, ChainFilter.parse(""));
		assertSame(ChainFilter.ALL, ChainFilter.parse("   "));
		assertSame(ChainFilter.ALL, ChainFilter.parse("-1"));
		assertSame(ChainFilter.ALL, ChainFilter.parse("composite-1"));
		assertSame(ChainFilter.ALL, ChainFilter.parse("two words"));
		assertSame(ChainFilter.ALL, ChainFilter.parse("99999999999999999999"));
		assertSame(ChainFilter.ALL, ChainFilter.parse("1a"));
		assertSame(ChainFilter.ALL, ChainFilter.parse("world0/composite1"));
	}

	@Test
	void theWholeChainKeepsEverything() {
		assertTrue(ChainFilter.ALL.accepts("composite", 0));
		assertTrue(ChainFilter.ALL.accepts("anything", 1_000_000));
		assertEquals(-1, ChainFilter.ALL.limit());
		assertFalse(ChainFilter.ALL.refuses("composite"));
	}

	@Test
	void aNameNoProgramCarriesKeepsNothingAndIsNotAnError() {
		ChainFilter only = ChainFilter.parse("nothere");

		assertFalse(only.accepts("composite", 0));
		assertFalse(only.accepts("composite1", 1));
	}

	@Test
	void whatTheEngineRefusesIsRefusedWhateverTheUserAskedFor() {
		ChainFilter refused = ChainFilter.parse("composite4,composite5").without(List.of("composite4"));

		assertTrue(refused.refuses("composite4"));
		assertFalse(refused.accepts("composite4", 0));
		assertTrue(refused.accepts("composite5", 0));
		assertEquals(List.of("composite4"), refused.without());
		// Told apart from what the user chose, so that a log can say which took a pass out.
		assertEquals(List.of("composite4", "composite5"), refused.only());
		assertFalse(ChainFilter.parse("composite4").refuses("composite4"));
	}

	@Test
	void addsRefusedProgramsWithoutRepeatingOneAndKeepsTheLimitAndTheRanks() {
		ChainFilter base = ChainFilter.parse("6");
		ChainFilter once = base.without(List.of("a", "b"));
		ChainFilter twice = once.without(List.of("b", "c"));

		assertEquals(List.of("a", "b"), once.without());
		assertEquals(List.of("a", "b", "c"), twice.without());
		assertEquals(6, twice.limit());
		assertSame(base, base.without(List.of()));
	}

	@Test
	void copiesItsListsSoThatACallerCannotChangeAFilterAfterwards() {
		List<String> names = new ArrayList<>(List.of("a"));
		ChainFilter filter = new ChainFilter(names, -1);
		names.add("b");

		assertEquals(List.of("a"), filter.only());
		assertThrows(UnsupportedOperationException.class, () -> filter.only().add("c"));
		assertEquals(List.of(), filter.without());
	}
}
