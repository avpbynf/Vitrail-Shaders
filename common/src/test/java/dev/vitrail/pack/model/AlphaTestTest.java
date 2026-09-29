package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds how {@code alphaTest.PROGRAM} is read and what test is written into a fragment shader from
 * it: the comparison is written negated, {@code if (!(a > r)) discard}, as Iris writes it.
 */
class AlphaTestTest {

	private static AlphaTest parsed(String value) {
		return AlphaTest.parse(value).orElseThrow(() -> new AssertionError("'" + value + "' was refused"));
	}

	@Test
	void offAndFalseInAnyCaseMeanNoTest() {
		for (String word : new String[] {"off", "OFF", "Off", "false", "FALSE", " off ", "\tfalse"}) {
			AlphaTest test = parsed(word);

			assertSame(AlphaTest.OFF, test, word);
			assertFalse(test.tests(), word);
			assertEquals("", test.discard("a"), word);
		}
	}

	@Test
	void aFunctionAndAReferenceMakeATest() {
		AlphaTest test = parsed("GREATER 0.5");

		assertEquals(AlphaTest.Function.GREATER, test.function());
		assertEquals(0.5F, test.reference());
		assertTrue(test.tests());
		assertEquals("if (!(a > 0.5)) { discard; }", test.discard("a"));
		assertEquals(AlphaTest.CUTOUT, test);
	}

	@Test
	void everyFunctionHasItsOwnGlslOperator() {
		Map<String, String> operators = Map.of("LESS", "<", "EQUAL", "==", "LEQUAL", "<=", "GREATER", ">",
				"NOTEQUAL", "!=", "GEQUAL", ">=");

		for (Map.Entry<String, String> entry : operators.entrySet()) {
			assertEquals("if (!(alpha " + entry.getValue() + " 0.25)) { discard; }",
					parsed(entry.getKey() + " 0.25").discard("alpha"), entry.getKey());
		}
		assertEquals(8, AlphaTest.Function.values().length);
	}

	@Test
	void neverDiscardsEverythingAndAlwaysDiscardsNothing() {
		assertEquals("discard;", parsed("NEVER 0").discard("a"));
		assertTrue(parsed("NEVER 0").tests());
		assertEquals("", parsed("ALWAYS 0").discard("a"));
		assertFalse(parsed("ALWAYS 0").tests());
		assertEquals("", parsed("GL_ALWAYS 0").discard("a"));
	}

	@Test
	void theFunctionMayBeWrittenWithGlPrefixOrInAnyCase() {
		assertEquals(AlphaTest.Function.GEQUAL, parsed("GL_GEQUAL 0.1").function());
		assertEquals(AlphaTest.Function.GREATER, parsed("greater 0.1").function());
		assertEquals(AlphaTest.Function.GREATER, parsed("Greater 0.1").function());
		// Only the upper-case prefix is stripped, so a lower-case one leaves a name nothing matches.
		assertTrue(AlphaTest.parse("gl_greater 0.1").isEmpty());
		assertTrue(AlphaTest.parse("GL_ 0.1").isEmpty());
	}

	@Test
	void aThirdWordIsIgnoredSoATrailingCommentCostsNothing() {
		assertEquals(0.5F, parsed("GREATER 0.5 // cutout").reference());
		assertEquals(0.5F, parsed("GREATER 0.5 x y z").reference());
		assertEquals(0.5F, parsed(" GREATER 0.5 ").reference());
	}

	@Test
	void aLineThatIsNeitherIsRefusedNotGuessedAt() {
		for (String value : new String[] {"", " ", "GREATER", "ALWAYS", "BOGUS 0.5", "GREATER abc", "GREATER  0.5",
			"GREATER\t0.5", "0.5 GREATER", "GREATER 0,5", "on", "true", "GREATER 0.5x"}) {
			assertTrue(AlphaTest.parse(value).isEmpty(), "'" + value + "'");
		}
	}

	@Test
	void aReferenceIsWhatFloatParseAccepts() {
		assertEquals(0.5F, parsed("GREATER .5").reference());
		assertEquals(0.5F, parsed("GREATER 5e-1").reference());
		assertEquals(1.0F, parsed("GREATER 1f").reference());
		assertEquals(0.5F, parsed("GREATER +0.5").reference());
		assertEquals(0.5F, parsed("GREATER 0x1p-1").reference());
		assertEquals(-0.5F, parsed("GREATER -0.5").reference());
	}

	@Test
	void theReferenceIsWrittenOutInFullNeverInExponentForm() {
		assertEquals("if (!(a > 0.5)) { discard; }", new AlphaTest(AlphaTest.Function.GREATER, 0.5F).discard("a"));
		assertEquals("if (!(a > 1.0)) { discard; }", new AlphaTest(AlphaTest.Function.GREATER, 1.0F).discard("a"));
		assertEquals("if (!(a > 0.0)) { discard; }", new AlphaTest(AlphaTest.Function.GREATER, 0.0F).discard("a"));
		assertEquals("if (!(a > 0.1)) { discard; }", new AlphaTest(AlphaTest.Function.GREATER, 0.1F).discard("a"));
		assertEquals("if (!(a > 0.001)) { discard; }", new AlphaTest(AlphaTest.Function.GREATER, 0.001F).discard("a"));
		assertEquals("if (!(a > 100.0)) { discard; }", new AlphaTest(AlphaTest.Function.GREATER, 100.0F).discard("a"));
		assertEquals("if (!(a > 10000000.0)) { discard; }",
				new AlphaTest(AlphaTest.Function.GREATER, 1.0E7F).discard("a"));
		assertEquals("if (!(a > -0.5)) { discard; }", new AlphaTest(AlphaTest.Function.GREATER, -0.5F).discard("a"));
		// Float.toString gives 1.0E-4, which BigDecimal keeps at one significant digit and a zero.
		assertEquals("if (!(a > 0.00010)) { discard; }",
				new AlphaTest(AlphaTest.Function.GREATER, 0.0001F).discard("a"));
		assertEquals("if (!(a > 0.000010)) { discard; }",
				new AlphaTest(AlphaTest.Function.GREATER, 1.0E-5F).discard("a"));
	}

	@Test
	void theNamedDefaultsAreIrisNumbers() {
		assertEquals(new AlphaTest(AlphaTest.Function.ALWAYS, 0.0F), AlphaTest.OFF);
		assertEquals(new AlphaTest(AlphaTest.Function.GREATER, 0.5F), AlphaTest.CUTOUT);
		assertEquals(new AlphaTest(AlphaTest.Function.GREATER, 0.1F), AlphaTest.ONE_TENTH);
		assertEquals(new AlphaTest(AlphaTest.Function.GREATER, 0.0001F), AlphaTest.NON_ZERO);
		assertEquals("if (!(a > 0.5)) { discard; }", AlphaTest.CUTOUT.discard("a"));
		assertEquals("if (!(a > 0.1)) { discard; }", AlphaTest.ONE_TENTH.discard("a"));
	}

	@Test
	void theAlphaExpressionIsWrittenAsHandedIn() {
		assertEquals("if (!(color.a >= 0.5)) { discard; }",
				new AlphaTest(AlphaTest.Function.GEQUAL, 0.5F).discard("color.a"));
		assertEquals(Optional.of(new AlphaTest(AlphaTest.Function.LESS, 2.0F)), AlphaTest.parse("less 2"));
	}

	/** A reference of NaN parses, and writing it out fails with the message of {@code BigDecimal}. */
	@Test
	void knownBug_aNonFiniteReferenceParsesAndThenCannotBeWritten() {
		AlphaTest nan = parsed("GREATER NaN");
		AlphaTest infinite = parsed("GREATER Infinity");

		assertTrue(nan.tests());
		assertThrows(NumberFormatException.class, () -> nan.discard("a"));
		assertThrows(NumberFormatException.class, () -> infinite.discard("a"));
	}
}
