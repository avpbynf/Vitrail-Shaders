package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds the evaluator that decides which branch of a pack is live: C's arithmetic on whole numbers, the
 * one divergence for fractional ones, the ladder a name is resolved down, and every way an expression is
 * refused. An expression this cannot decide answers empty and the callers take that as true, so the empty
 * answers here are as much a part of the contract as the true and false ones.
 */
class PreprocessorExpressionTest {

	private static final Map<String, String> NONE = Map.of();

	private static Optional<Boolean> eval(String expression) {
		return PreprocessorExpression.evaluate(expression, NONE);
	}

	private static Optional<Boolean> eval(String expression, String... pairs) {
		Map<String, String> defines = new java.util.LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			defines.put(pairs[i], pairs[i + 1]);
		}

		return PreprocessorExpression.evaluate(expression, defines);
	}

	private static void assertTrueFor(String expression) {
		assertEquals(Optional.of(true), eval(expression), expression);
	}

	private static void assertFalseFor(String expression) {
		assertEquals(Optional.of(false), eval(expression), expression);
	}

	private static void assertUndecided(String expression) {
		assertEquals(Optional.empty(), eval(expression), expression);
	}

	@Test
	void doesIntegerArithmeticWithCsRulesAndPrecedence() {
		assertTrueFor("1 + 2 * 3 == 7");
		assertTrueFor("(1 + 2) * 3 == 9");
		assertTrueFor("7 / 2 == 3");
		assertTrueFor("-7 / 2 == -3");
		assertTrueFor("7 % 2 == 1");
		assertTrueFor("-7 % 2 == -1");
		assertTrueFor("10 - 3 - 2 == 5");
		assertTrueFor("2 * 3 % 4 == 2");
		assertFalseFor("0");
		assertTrueFor("+3");
		assertTrueFor("-3 < 0");
	}

	@Test
	void comparesAndCombinesTruthValuesAsNoughtAndOne() {
		assertTrueFor("3 > 2 && 2 >= 2 && 1 <= 1 && 1 < 2 && 1 != 2");
		assertTrueFor("(3 > 2) == 1");
		assertFalseFor("1 && 0");
		assertTrueFor("1 || 0");
		assertFalseFor("0 || 0");
		assertTrueFor("!0");
		assertFalseFor("!5");
		assertTrueFor("!!5");
	}

	@Test
	void givesTheBitwiseOperatorsTheirCPrecedenceBelowTheComparisons() {
		// In C the comparison binds tighter than the bitwise and, so this is 6 & (3 == 2), which is 0.
		assertFalseFor("6 & 3 == 2");
		assertTrueFor("(6 & 3) == 2");
		assertTrueFor("(6 | 1) == 7");
		assertTrueFor("(6 ^ 3) == 5");
		assertTrueFor("~0 == -1");
		assertTrueFor("1 << 4 == 16");
		assertTrueFor("256 >> 4 == 16");
		assertTrueFor("(1 << 2 + 1) == 8");
	}

	@Test
	void refusesADivisionByNoughtAndShiftsOrBitsOnAnythingButWholeNumbers() {
		assertUndecided("1 / 0");
		assertUndecided("1 % 0");
		assertUndecided("1 << 64");
		assertUndecided("1 << -1");
		assertUndecided("1.0 << 2");
		assertUndecided("2 & 1.5");
		assertUndecided("~1.5");
		assertUndecided("5.0 % 2");
		assertTrueFor("1 << 63 < 0");
	}

	@Test
	void doesNotEvaluateTheOperandAShortCircuitSparesButStillParsesIt() {
		assertEquals(Optional.of(false), eval("X != 0 && 100 / X > 5", "X", "0"));
		assertEquals(Optional.of(true), eval("X != 0 && 100 / X > 5", "X", "10"));
		assertTrueFor("1 || 1 / 0");
		assertFalseFor("0 && 1 / 0");
		// Nothing decides the right side here, so its failure is the answer's.
		assertUndecided("0 || 1 / 0");
		assertUndecided("1 && 1 / 0");
		// The spared operand still has to be well formed.
		assertUndecided("1 || (");
	}

	@Test
	void takesAFractionalNumberAsTheNumberItIsAndWidensTheArithmeticAroundIt() {
		assertTrueFor("0.5 > 0.0");
		assertTrueFor("0.5 < 1");
		assertTrueFor("7.0 / 2 == 3.5");
		assertTrueFor("7 / 2.0 > 3");
		assertTrueFor("1e3 == 1000");
		assertTrueFor("1.5f > 1");
		assertTrueFor("0.1 + 0.2 > 0.29");
		assertTrueFor("-0.5 < 0");
		assertTrueFor("1.0 == 1");
	}

	@Test
	void saysWhetherAnythingFractionalWasReadSoThatALoosePreprocessorLineCanBeAnswered() {
		Map<String, String> defines = Map.of("MOTION_BLUR", "0.5", "FALLOFF", "1.0", "WHOLE", "2");

		PreprocessorExpression.Verdict motion = PreprocessorExpression.decide("MOTION_BLUR > 0.0", defines);
		assertEquals(Optional.of(true), motion.taken());
		assertTrue(motion.fractional());

		// Pegasus: the macro is 1.0 and the line compares it to a whole 1.
		PreprocessorExpression.Verdict pegasus = PreprocessorExpression.decide("FALLOFF == 1", defines);
		assertEquals(Optional.of(true), pegasus.taken());
		assertTrue(pegasus.fractional());

		PreprocessorExpression.Verdict whole = PreprocessorExpression.decide("WHOLE * 2 == 4", defines);
		assertEquals(Optional.of(true), whole.taken());
		assertFalse(whole.fractional());

		// Read wherever it stands, including in an operand a short circuit spares.
		assertTrue(PreprocessorExpression.decide("1 || 0.5", defines).fractional());
		assertEquals(Optional.of(true), PreprocessorExpression.decide("1 || 0.5", defines).taken());
	}

	@Test
	void parsesHexadecimalAndSuffixedNumbersWithoutLosingADigit() {
		assertTrueFor("0x1F == 31");
		assertTrueFor("0X10 == 16");
		assertTrueFor("0xFFu == 255");
		assertTrueFor("10u == 10");
		assertTrueFor("10UL == 10");
		assertUndecided("0x");
		assertUndecided("12abc");
	}

	@Test
	void resolvesANameDownTheLadderAnUnknownNameIsNoughtAndABareOneIsOne() {
		assertEquals(Optional.of(false), eval("NEVER"));
		assertEquals(Optional.of(true), eval("!NEVER"));
		assertEquals(Optional.of(true), eval("SWITCH", "SWITCH", ""));
		assertEquals(Optional.of(true), eval("SWITCH == 1", "SWITCH", "   "));
		assertEquals(Optional.of(true), eval("LEVEL == 3", "LEVEL", "3"));
		assertEquals(Optional.of(true), eval("LEVEL == 3", "LEVEL", "  3  "));
		assertEquals(Optional.of(true), eval("A == 3", "A", "B", "B", "C", "C", "3"));
		assertEquals(Optional.of(true), eval("A == 3", "A", "B", "B", "3"));
	}

	@Test
	void anExpressionKeepsItsValueRatherThanCollapsingToAOneOrANought() {
		// SHADOW_RES is a size, and the comparison is between sizes: as a truth value it would read 1.
		assertEquals(Optional.of(true), eval("SHADOW_RES > 256", "SHADOW_RES", "QUALITY * 512", "QUALITY", "2"));
		assertEquals(Optional.of(false), eval("SHADOW_RES > 2048", "SHADOW_RES", "QUALITY * 512", "QUALITY", "2"));
		assertEquals(Optional.of(true), eval("SHADOW_RES == 1024", "SHADOW_RES", "(QUALITY * 512)", "QUALITY", "2"));
		// A value that cannot be read stands for one, so that the branch it guards is not lost.
		assertEquals(Optional.of(true), eval("BROKEN == 1", "BROKEN", "1 +"));
	}

	@Test
	void stopsANameThatLeadsBackToItselfAtANoughtWithoutRunningOutOfStack() {
		Map<String, String> loop = Map.of("A", "(B)", "B", "(A)", "SELF", "SELF");

		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			assertEquals(Optional.of(false), PreprocessorExpression.evaluate("A", loop));
			assertEquals(Optional.of(true), PreprocessorExpression.evaluate("!A", loop));
			assertEquals(Optional.of(false), PreprocessorExpression.evaluate("SELF", loop));
		});
	}

	@Test
	void answersTheDefinedOperatorInBothSpellingsAndRefusesAMalformedOne() {
		assertEquals(Optional.of(true), eval("defined(X)", "X", ""));
		assertEquals(Optional.of(true), eval("defined X", "X", "0"));
		assertEquals(Optional.of(false), eval("defined(X)"));
		assertEquals(Optional.of(true), eval("!defined(X) && defined(Y)", "Y", "1"));
		assertUndecided("defined(X");
		assertUndecided("defined 3");
		assertUndecided("defined");
		assertUndecided("defined()");
	}

	@Test
	void stripsTheCommentsOfALineBeforeReadingIt() {
		assertTrueFor("1 /* one */ == 1 // and the rest");
		assertTrueFor("/* lead */ 1");
		assertUndecided("// nothing but a comment");
		assertUndecided("");
		assertUndecided("   ");
	}

	@Test
	void refusesWhatIsNotAnExpression() {
		assertUndecided("1 +");
		assertUndecided(")");
		assertUndecided("1 1");
		assertUndecided("@");
		assertUndecided("(1");
		assertUndecided("1 ==");
		assertUndecided("1 2 3");
	}

	@Test
	void boundsTheNestingOfBracketsAndPrefixOperatorsTogether() {
		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			assertTrueFor("(".repeat(64) + "1" + ")".repeat(64));
			assertUndecided("(".repeat(65) + "1" + ")".repeat(65));
			assertFalseFor("!".repeat(64) + "0");
			assertUndecided("!".repeat(65) + "0");
			// They share one budget: thirty two of each is sixty four in all.
			assertFalseFor("!(".repeat(32) + "0" + ")".repeat(32));
			assertUndecided("!(".repeat(33) + "0" + ")".repeat(33));
			// And ten thousand of them, which is the shape that would exhaust a stack, is refused not thrown.
			assertUndecided("!".repeat(10_000) + "0");
		});
	}

	@Test
	void keepsWholeNumbersExactBeyondWhatADoubleCanHold() {
		assertFalseFor("9007199254740993 == 9007199254740992");
		assertTrueFor("(9007199254740993 & 1) == 1");
		assertTrueFor("9007199254740993 > 9007199254740992");
	}

	@Test
	void evaluateIsTheTakenHalfOfDecide() {
		for (String expression : new String[] {"1", "0", "1 / 0", "((", "0.5 > 0.1", "defined(A)"}) {
			assertEquals(PreprocessorExpression.decide(expression, NONE).taken(), eval(expression), expression);
		}
	}
}
