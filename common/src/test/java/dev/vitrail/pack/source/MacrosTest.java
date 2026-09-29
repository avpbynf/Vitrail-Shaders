package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Holds the substitution a properties value goes through before it is read, since no compiler stands
 * behind that file to do it: names replaced by what they stand for, and every shape that must be left
 * alone so that a reader can say which name it did not know.
 */
class MacrosTest {

	private static Map<String, String> table(String... pairs) {
		Map<String, String> table = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			table.put(pairs[i], pairs[i + 1]);
		}

		return table;
	}

	@Test
	void replacesAWholeNameByItsValueAndNothingInsideALongerOne() {
		Map<String, String> defines = table("BIOME_GROVE", "5", "FOO", "1");

		assertEquals("in(biome, 5, 5)", Macros.expand("in(biome, BIOME_GROVE, BIOME_GROVE)", defines));
		assertEquals("FOO_BAR + FOOD + xFOO + 1", Macros.expand("FOO_BAR + FOOD + xFOO + FOO", defines));
		assertEquals("", Macros.expand("", defines));
		assertEquals("1.5 + 2", Macros.expand("1.5 + 2", defines));
	}

	@Test
	void leavesAnEmptyValueASelfReferenceAndAnUnknownNameWhereTheyStand() {
		Map<String, String> defines = table("BLANK", "", "SPACES", "   ", "SELF", "SELF");

		assertEquals("BLANK + SPACES + SELF + UNKNOWN", Macros.expand("BLANK + SPACES + SELF + UNKNOWN", defines));
	}

	@Test
	void doesNotSubstituteAComponentAfterADot() {
		Map<String, String> defines = table("x", "1", "y", "2");

		assertEquals("sunPosition.x + 1", Macros.expand("sunPosition.x + x", defines));
		assertEquals("v . y", Macros.expand("v . y", defines));
		assertEquals("2 + v.x", Macros.expand("y + v.x", defines));
	}

	@Test
	void takesAValueLiterallyEvenWhenItCarriesReplacementSyntax() {
		assertEquals("cost $1 and \\n", Macros.expand("cost PRICE", table("PRICE", "$1 and \\n")));
	}

	@Test
	void putsAValueInWithoutBracketsAsAPreprocessorWould() {
		assertEquals("1 + 1 * 3", Macros.expand("TWO * 3", table("TWO", "1 + 1")));
	}

	@Test
	void followsASymbolDefinedInTermsOfAnotherForFourRoundsAndNoFurther() {
		assertEquals("3", Macros.expand("A", table("A", "B", "B", "C", "C", "3")));
		// One round each: F is the sixth name, and four rounds reach the fifth.
		Map<String, String> chain = table("A", "B", "B", "C", "C", "D", "D", "E", "E", "F");

		assertEquals("E", Macros.expand("A", chain));
		assertEquals("F", Macros.expand("D", chain));
	}

	@Test
	void stopsASymbolThatRefersToItselfThroughAnotherAfterFourRounds() {
		// Two names that stand for each other swap every round, so four rounds land where they began.
		assertEquals("A", Macros.expand("A", table("A", "B", "B", "A")));
		assertEquals("B", Macros.expand("B", table("A", "B", "B", "A")));
	}

	/**
	 * Each round replaces every name by its whole value, so the text is multiplied by the length of the
	 * values once per round and four rounds turn one word into ten thousand. Pins the growth as it stands;
	 * a fix is coming elsewhere (fix/pack-reading-limits, a work budget), and this goes with it. Kept small
	 * enough to finish at once, and run under a timeout in case it does not.
	 */
	@Test
	void knownBug_everyRoundMultipliesTheTextByTheLengthOfTheValues() {
		String ten = " %s".repeat(10).substring(1);
		Map<String, String> defines = table("A", ten.formatted((Object[]) "B B B B B B B B B B".split(" ", -1)),
				"B", ten.formatted((Object[]) "C C C C C C C C C C".split(" ", -1)),
				"C", ten.formatted((Object[]) "D D D D D D D D D D".split(" ", -1)),
				"D", ten.formatted((Object[]) "E E E E E E E E E E".split(" ", -1)));

		String expanded = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> Macros.expand("A", defines));

		assertEquals(10_000, expanded.split(" ", -1).length);
		assertEquals(19_999, expanded.length());
	}
}
