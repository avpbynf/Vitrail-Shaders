package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Holds which branch of a chain of conditional directives is live: the one stack that pack sources and
 * {@code shaders.properties} both read, so that the two cannot disagree about what is on.
 */
class ConditionStackTest {

	@Test
	void anEmptyStackIsLive() {
		ConditionStack stack = new ConditionStack();

		assertTrue(stack.active());
		assertEquals(0, stack.depth());
	}

	@Test
	void takesTheFirstTrueBranchOfAChainAndNoLaterOne() {
		ConditionStack stack = new ConditionStack();
		stack.ifDirective(false);
		assertFalse(stack.active());
		stack.elifDirective(() -> true);
		assertTrue(stack.active());
		stack.elifDirective(() -> true);
		assertFalse(stack.active());
		stack.elseDirective();
		assertFalse(stack.active());
		stack.endifDirective();
		assertTrue(stack.active());
	}

	@Test
	void anElseIsLiveOnlyWhenNothingBeforeItWas() {
		ConditionStack none = new ConditionStack();
		none.ifDirective(false);
		none.elseDirective();
		assertTrue(none.active());

		ConditionStack taken = new ConditionStack();
		taken.ifDirective(true);
		taken.elseDirective();
		assertFalse(taken.active());
	}

	@Test
	void doesNotAskAnElifWhenAnEarlierBranchWasTaken() {
		AtomicInteger asked = new AtomicInteger();
		ConditionStack stack = new ConditionStack();
		stack.ifDirective(true);

		stack.elifDirective(() -> {
			asked.incrementAndGet();
			return true;
		});

		assertEquals(0, asked.get());
		assertFalse(stack.active());
	}

	@Test
	void doesNotAskAConditionInsideAGroupThatIsOffAndCountsItAsTaken() {
		AtomicInteger asked = new AtomicInteger();
		ConditionStack stack = new ConditionStack();
		stack.ifDirective(false);

		stack.ifDirective(() -> {
			asked.incrementAndGet();
			return false;
		});

		assertEquals(0, asked.get());
		assertFalse(stack.active());
		assertEquals(2, stack.depth());
		// Counted as taken: its else does not switch anything on under a parent that is off.
		stack.elseDirective();
		assertFalse(stack.active());
		stack.endifDirective();
		stack.elseDirective();
		assertTrue(stack.active());
	}

	@Test
	void aBranchStaysOffUnderAParentThatIsOffWhateverItsOwnConditionSays() {
		ConditionStack stack = new ConditionStack();
		stack.ifDirective(false);
		stack.ifDirective(true);
		assertFalse(stack.active());
		stack.elseDirective();
		assertFalse(stack.active());
		stack.endifDirective();
		stack.endifDirective();
		assertTrue(stack.active());
	}

	@Test
	void ignoresAnElifAnElseAndAnEndifNothingIsOpenFor() {
		ConditionStack stack = new ConditionStack();

		stack.elifDirective(() -> false);
		stack.elseDirective();
		stack.endifDirective();

		assertTrue(stack.active());
		assertEquals(0, stack.depth());
	}

	@Test
	void countsTheGroupsAReaderRewroteThatAreStillOpen() {
		ConditionStack stack = new ConditionStack();
		stack.rewrittenIfDirective(true);
		stack.ifDirective(true);
		stack.rewrittenIfDirective(false);
		assertEquals(2, stack.unclosedRewritten());

		stack.endifDirective();
		assertEquals(1, stack.unclosedRewritten());
	}

	@Test
	void aRewrittenElifIsTheAnswerAlreadyGivenAndAnEarlierTakenBranchStillWins() {
		ConditionStack stack = new ConditionStack();
		stack.rewrittenIfDirective(false);
		stack.rewrittenElifDirective(true);
		assertTrue(stack.active());
		stack.rewrittenElifDirective(true);
		assertFalse(stack.active());
	}
}
