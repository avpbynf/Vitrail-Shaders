package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Holds what a pack's expression means when it is only operators and numbers: how tightly each one
 * binds, which way a run of equal ones groups, and what type the answer has.
 * <p>
 * Every expected value here is worked out by hand, with the arithmetic written out beside it where
 * the answer is not obvious; none was copied from a run. The cases that pin a rule the reader would
 * not guess (division is always floating, {@code 08} is a float) say so in their names, because a
 * change to one of them changes a pack's picture.
 */
class ExprOperatorsTest {

	private final ExprRig rig = new ExprRig();

	// precedence and associativity

	@Test
	void multiplicationBindsTighterThanAddition() {
		assertEquals(7.0F, this.rig.floatOf("1 + 2 * 3"), "1 + (2 * 3)");
		assertEquals(7.0F, this.rig.floatOf("2 * 3 + 1"), "(2 * 3) + 1");
		assertEquals(9.0F, this.rig.floatOf("(1 + 2) * 3"), "brackets win");
		assertEquals(-5.0F, this.rig.floatOf("1 - 2 * 3"), "1 - (2 * 3)");
	}

	@Test
	void operatorsOfOneLevelGroupFromTheLeft() {
		// A right-leaning grouping would give 9, 50, 1.333 and 7 for these four.
		assertEquals(3.0F, this.rig.floatOf("10 - 4 - 3"), "(10 - 4) - 3");
		assertEquals(2.0F, this.rig.floatOf("100 / 10 / 5"), "(100 / 10) / 5");
		assertEquals(12.0F, this.rig.floatOf("8 / 2 * 3"), "(8 / 2) * 3");
		assertEquals(6.0F, this.rig.floatOf("7 % 4 * 2"), "(7 % 4) * 2");
	}

	@Test
	void unaryMinusBindsTighterThanEveryBinaryOperator() {
		assertEquals(-6.0F, this.rig.floatOf("-2 * 3"), "(-2) * 3");
		assertEquals(-4.0F, this.rig.floatOf("-2 - 2"), "(-2) - 2");
		assertEquals(5.0F, this.rig.floatOf("2 - -3"));
		assertEquals(5.0F, this.rig.floatOf("2--3"), "no space needed between the two minus signs");
		assertEquals(2.0F, this.rig.floatOf("- -2"));
		assertEquals(-3.0F, this.rig.floatOf("-(1 + 2)"));
		assertEquals(-7.0F, this.rig.floatOf("-ia"), "an engine int, negated");
	}

	@Test
	void comparisonsBindLooserThanArithmetic() {
		assertTrue(this.rig.boolOf("1 + 2 == 3"), "(1 + 2) == 3");
		assertTrue(this.rig.boolOf("2 * 3 > 5"), "(2 * 3) > 5");
		assertTrue(this.rig.boolOf("1 + 1 != 3"), "(1 + 1) != 3");
		assertFalse(this.rig.boolOf("10 - 4 - 3 < 3"), "3 < 3");
		// The comparison written first, so that a comparison as tight as the sum would take the
		// 3 == 1 first and find a bool to add 2 to.
		assertTrue(this.rig.boolOf("3 == 1 + 2"), "3 == (1 + 2)");
		assertTrue(this.rig.boolOf("4 < 2 + 3"), "4 < (2 + 3)");
		assertTrue(this.rig.boolOf("6 >= 2 * 3"), "6 >= (2 * 3)");
		assertTrue(this.rig.boolOf("1 != 4 - 2 - 2 + 3"), "1 != (((4 - 2) - 2) + 3)");
	}

	@ParameterizedTest
	@CsvSource(delimiter = ';', value = {
			"1 < 2 ; true", "2 < 2 ; false", "3 < 2 ; false",
			"1 > 2 ; false", "2 > 2 ; false", "3 > 2 ; true",
			"1 <= 2 ; true", "2 <= 2 ; true", "3 <= 2 ; false",
			"1 >= 2 ; false", "2 >= 2 ; true", "3 >= 2 ; true",
			"2 == 2 ; true", "2 == 3 ; false",
			"2 != 2 ; false", "2 != 3 ; true",
			"1 < 2.5 ; true", "2.5 < 2 ; false",
			"2 == 2.0 ; true", "0.5 != 0.5 ; false",
	})
	void comparesIntsAndFloats(String expression, boolean expected) {
		assertEquals(expected, this.rig.boolOf(expression), expression);
	}

	@Test
	void comparesFloatsByIeee() {
		assertFalse(this.rig.boolOf("0 / 0 == 0 / 0"), "NaN equals nothing, itself included");
		assertTrue(this.rig.boolOf("0 / 0 != 0 / 0"));
		assertFalse(this.rig.boolOf("0 / 0 < 1"));
		assertFalse(this.rig.boolOf("0 / 0 >= 1"));
		assertTrue(this.rig.boolOf("1 / 0 > 3.4e38"), "+infinity is above the largest float");
	}

	@Test
	void readsTheUnicodeSpellingsOfThreeComparisons() {
		assertTrue(this.rig.boolOf("1 \u2260 2"));
		assertFalse(this.rig.boolOf("2 \u2260 2"));
		assertTrue(this.rig.boolOf("2 \u2264 2"));
		assertFalse(this.rig.boolOf("3 \u2264 2"));
		assertTrue(this.rig.boolOf("2 \u2265 2"));
		assertFalse(this.rig.boolOf("1 \u2265 2"));
	}

	@Test
	void comparisonsDoNotChain() {
		// (1 < 2) is a bool, and nothing compares a bool with 3 by <.
		assertTrue(this.rig.refusal("bool", "1 < 2 < 3").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("bool", "3 > 2 > 1").contains("Couldn't resolve"));
	}

	@Test
	void equalityOfBoolsGroupsFromTheLeftAndReadsAnIntAsABool() {
		// (2 == 2) is true, and true == 1 compares as bools because a bare int is read as one:
		// so 2 == 2 == 1 is true, where a reading of "1 is a number" would say false.
		assertTrue(this.rig.boolOf("2 == 2 == 1"));
		assertTrue(this.rig.boolOf("(1 == 1) == (2 == 2)"));
		assertFalse(this.rig.boolOf("(1 == 1) != (2 == 2)"));
	}

	// boolean operators

	@ParameterizedTest
	@CsvSource(delimiter = ';', value = {
			"1 < 2 && 2 < 3 ; true", "1 < 2 && 3 < 2 ; false", "2 < 1 && 2 < 3 ; false",
			"2 < 1 && 3 < 2 ; false",
			"1 < 2 || 3 < 2 ; true", "2 < 1 || 2 < 3 ; true", "2 < 1 || 3 < 2 ; false",
			"!(1 > 2) ; true", "!(1 < 2) ; false", "!(1 < 2) || 1 < 2 ; true",
	})
	void combinesBooleans(String expression, boolean expected) {
		assertEquals(expected, this.rig.boolOf(expression), expression);
	}

	@Test
	void andBindsTighterThanOr() {
		// Documented in ExprGrammar: && is one level tighter than ||, as in OptiFine, C, GLSL and
		// Java. Two operators sharing one level and grouping from the left, as they do in Iris,
		// would read the first of these as (T || F) && F, false, and fail it.
		assertTrue(this.rig.boolOf("1 < 2 || 1 > 2 && 1 > 2"), "T || (F && F)");
		assertTrue(this.rig.boolOf("1 > 2 && 1 < 2 || 1 < 2"), "(F && T) || T");
		// Where the two readings agree nothing distinguishes them.
		assertFalse(this.rig.boolOf("1 > 2 || 2 > 1 && 1 > 2"), "F || (T && F)");
	}

	@Test
	void notBindsTighterThanEverythingIncludingComparison() {
		// (!0) is true; true == 1 reads the 1 as a bool.
		assertTrue(this.rig.boolOf("!0 == 1"));
		assertTrue(this.rig.boolOf("!!ia"), "an engine int 7 is true, and !! keeps it true");
		assertFalse(this.rig.boolOf("!ia"));
	}

	@Test
	void singleAmpersandAndBarAreNotOperators() {
		assertTrue(this.rig.refusal("bool", "1 < 2 & 2 < 3").contains("Expected to read '&'"));
		assertTrue(this.rig.refusal("bool", "1 < 2 | 2 < 3").contains("Expected to read '|'"));
	}

	// numbers

	@Test
	void readsIntegersInFourBases() {
		assertEquals(42, this.rig.intOf("42"));
		assertEquals(16, this.rig.intOf("0x10"), "hexadecimal");
		assertEquals(255, this.rig.intOf("0xff"));
		assertEquals(5, this.rig.intOf("0b101"), "binary");
		assertEquals(8, this.rig.intOf("010"), "a leading zero is OCTAL, as it is in C");
		assertEquals(0, this.rig.intOf("00"));
		assertEquals(0, this.rig.intOf("0"));
	}

	@Test
	void anIntegerLiteralThatIsNotOctalFallsThroughToAFloat() {
		// "08" fails as octal and is then read as the float 8.0, so it cannot stand for an int.
		assertEquals(8.0F, this.rig.floatOf("08"));
		assertTrue(this.rig.refusal("int", "08").contains("Couldn't resolve"));
		// An integer past the int range is a float too, and rounds to one.
		assertEquals(2147483648.0F, this.rig.floatOf("2147483648"));
		assertTrue(this.rig.refusal("int", "2147483648").contains("Couldn't resolve"));
		assertEquals(16777216.0F, this.rig.floatOf("16777217"), "float has 24 bits of mantissa");
	}

	@Test
	void readsFloatLiteralsJavaReads() {
		assertEquals(0.5F, this.rig.floatOf("0.5"));
		assertEquals(0.5F, this.rig.floatOf(".5"), "no leading digit");
		assertEquals(1.0F, this.rig.floatOf("1."), "no trailing digit");
		assertEquals(1000.0F, this.rig.floatOf("1e3"), "an exponent");
		assertEquals(1.5F, this.rig.floatOf("1.5f"), "and Java's f suffix, which GLSL also writes");
		assertEquals(Float.POSITIVE_INFINITY, this.rig.floatOf("1e400"), "past the largest float");
	}

	@Test
	void anExponentWithASignIsNotOneNumber() {
		// The tokenizer ends a number at the sign, so 1e-2 is "1e" minus 2, and 1e is no number.
		assertTrue(this.rig.refusal("float", "1e-2").contains("Illegal number: 1e"));
		assertTrue(this.rig.refusal("float", "1e+2").contains("Illegal number: 1e"));
	}

	// types

	@Test
	void divisionIsAlwaysFloating() {
		assertEquals(3.5F, this.rig.floatOf("7 / 2"), "two ints divide as floats, not as C would");
		assertEquals(3.5F, this.rig.floatOf("ia / 2"), "engine int 7");
		assertEquals(0.25F, this.rig.floatOf("1 / 4"));
		assertTrue(this.rig.refusal("int", "7 / 2").contains("Couldn't resolve"),
				"so there is no int result to declare, and toInt(7 / 2) or floor is what a pack writes");
		assertEquals(3, this.rig.intOf("toInt(7 / 2)"));
		assertEquals(3, this.rig.intOf("floor(7 / 2)"));
	}

	@Test
	void divisionByZeroIsIeeeForFloats() {
		assertEquals(Float.POSITIVE_INFINITY, this.rig.floatOf("1 / 0"));
		assertEquals(Float.NEGATIVE_INFINITY, this.rig.floatOf("-1 / 0"));
		assertEquals(Float.NaN, this.rig.floatOf("0 / 0"));
		assertEquals(Float.NaN, this.rig.floatOf("1 % 0.0"), "the float remainder by zero");
	}

	@Test
	void integerArithmeticStaysIntAndWrapsAsJavaDoes() {
		assertEquals(Integer.MIN_VALUE, this.rig.intOf("2147483647 + 1"));
		assertEquals(0, this.rig.intOf("65536 * 65536"), "2^32 wraps to zero");
		assertEquals(Integer.MAX_VALUE, this.rig.intOf("0 - 2147483647 - 1 - 1"), "and wraps down too");
		assertEquals(14, this.rig.intOf("ia * 2"));
		assertEquals(8, this.rig.intOf("ia + 1"));
		assertEquals(-3, this.rig.intOf("4 - ia"));
	}

	@Test
	void remainderKeepsTheSignOfTheDividend() {
		assertEquals(-3, this.rig.intOf("-7 % 4"), "Java's rem; fmod gives 1 for the same pair");
		assertEquals(3, this.rig.intOf("7 % -4"));
		assertEquals(3, this.rig.intOf("7 % 4"));
		assertEquals(1.5F, this.rig.floatOf("5.5 % 2"));
		assertEquals(-1.5F, this.rig.floatOf("-5.5 % 2"));
	}

	@Test
	void anIntIsWidenedToAFloatWhereAFloatIsWanted() {
		assertEquals(3.0F, this.rig.floatOf("3"));
		assertEquals(7.0F, this.rig.floatOf("ia"), "an engine int");
		assertEquals(9.5F, this.rig.floatOf("ia + 2.5"), "mixed operands take the float overload");
		assertEquals(16777216.0F, this.rig.floatOf("16777216 + 1"), "the sum is an int and widens after");
	}

	@Test
	void aFloatIsNeverNarrowedToAnIntWithoutSayingSo() {
		assertTrue(this.rig.refusal("int", "1.5").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("int", "fa").contains("Couldn't resolve"), "an engine float");
		assertTrue(this.rig.refusal("int", "ia + 0.0").contains("Couldn't resolve"));
	}

	@Test
	void toIntTruncatesTowardZeroAndSaturates() {
		assertEquals(1, this.rig.intOf("toInt(1.9)"));
		assertEquals(-1, this.rig.intOf("toInt(-1.9)"), "truncation, not floor");
		assertEquals(Integer.MAX_VALUE, this.rig.intOf("toInt(1e10)"));
		assertEquals(Integer.MIN_VALUE, this.rig.intOf("toInt(-1e10)"));
		assertEquals(0, this.rig.intOf("toInt(0 / 0)"), "NaN is zero");
		assertEquals(2.0F, this.rig.floatOf("toFloat(2)"));
	}

	@Test
	void anIntIsABoolWhenItIsNotZero() {
		assertTrue(this.rig.boolOf("2"));
		assertTrue(this.rig.boolOf("-1"));
		assertFalse(this.rig.boolOf("0"));
		assertTrue(this.rig.boolOf("ia"), "so an engine flag stored as an int fits an if()");
		assertTrue(this.rig.boolOf("toBoolean(2)"));
		assertFalse(this.rig.boolOf("toBoolean(0)"));
		assertTrue(this.rig.refusal("bool", "toBoolean(0.5)").contains("Couldn't resolve"),
				"a float is not read as a bool");
		assertTrue(this.rig.refusal("bool", "fa").contains("Couldn't resolve"));
	}

	@Test
	void aBoolIsNeitherAnIntNorAFloat() {
		assertTrue(this.rig.refusal("float", "1 == 1").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("int", "1 == 1").contains("Couldn't resolve"));
		assertEquals(1, this.rig.intOf("if(1 == 1, 1, 0)"), "so a pack writes if() to turn one into a number");
	}

	@Test
	void trueAndFalseAreNotWords() {
		// A gap: OptiFine's expressions read them. Here they are names nothing answers.
		assertEquals("r: Unknown variable: true", this.rig.refusal("bool", "true"));
		assertEquals("r: Unknown variable: false", this.rig.refusal("bool", "false"));
		assertTrue(this.rig.refusal("bool", "if(fa > 1, true, false)").contains("Unknown variable"));
	}

	// text

	@Test
	void ignoresWhitespaceAndAllowsOneTrailingComma() {
		assertEquals(3.0F, this.rig.floatOf("  1 +   2  "));
		assertEquals(1.0F, this.rig.floatOf("abs (-1)"), "a space before the bracket of a call");
		assertEquals(1.0F, this.rig.floatOf("abs(-1,)"), "a trailing comma is skipped");
		assertEquals(4.0F, this.rig.floatOf("((2)) * (2)"));
	}

	@Test
	void namesWhyAnExpressionDoesNotParse() {
		List<String[]> cases = new ArrayList<>();
		cases.add(new String[] {"+1", "Read an unexpected character '+'"});
		cases.add(new String[] {"1 +", "right side of the operator"});
		cases.add(new String[] {"(1 + 2", "Expected a closing bracket"});
		cases.add(new String[] {"1 + 2)", "Expected an opening bracket"});
		cases.add(new String[] {"", "The input seems to be empty"});
		cases.add(new String[] {"   ", "The input seems to be empty"});
		cases.add(new String[] {"1 2", "The stack of tokens isn't empty"});
		cases.add(new String[] {"()", "empty brackets"});
		cases.add(new String[] {"(1, 2)", "too many expressions in brackets"});
		cases.add(new String[] {"2 ^ 3", "Read an unexpected character '^'"});
		cases.add(new String[] {"1 ? 2 : 3", "Read an unexpected character '?'"});
		cases.add(new String[] {"1 // 2", "Read an unexpected character '/'"});
		cases.add(new String[] {"a b", "The stack of tokens isn't empty"});
		cases.add(new String[] {"fa.", "can't end with '.'"});
		cases.add(new String[] {"1.5.5", "Illegal number: 1.5.5"});
		cases.add(new String[] {"$x", "Read an unexpected character '$'"});
		cases.add(new String[] {",", "Expected an expression before a comma"});

		for (String[] one : cases) {
			String reason = this.rig.refusal("float", one[0]);
			assertTrue(reason.contains(one[1]), "'" + one[0] + "' was refused as: " + reason);
		}
	}

	@Test
	void aParseFailureEchoesTheExpressionAfterTheReason() {
		assertEquals("r: Read an unexpected character '^' at index 2 (= 2 ^ 3)",
				this.rig.refusal("float", "2 ^ 3"));
	}
}
