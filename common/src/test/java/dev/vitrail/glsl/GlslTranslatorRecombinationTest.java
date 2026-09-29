package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Holds what {@link GlslTranslator} makes of texts nobody wrote, which is where a change to it that
 * the hand written corpus does not reach shows first.
 * <p>
 * {@link GlslTranslatorCases#recombination} builds each text out of three to eight pieces of the
 * corpus in a random order, under the setup of a random case of the same stage, from a fixed seed. A
 * text like that carries two mains, a name declared under two types, a varying nobody writes and a
 * function the pack calls before it is declared: the ways a pack that took thirty includes goes
 * wrong, and no case is written to have them. What is recorded is the length of what comes out and
 * the head of its SHA-256, which is enough to say that it is the same text without keeping four
 * hundred of them. A mismatch names the index, and
 * {@code GlslTranslatorCases.recombination(index)} is the input behind it.
 */
class GlslTranslatorRecombinationTest {

	/** The file holding one recorded line per recombination. */
	static final String GOLDEN = "recombinations.txt";

	private static final Pattern FUNCTION_LIKE_MACRO = Pattern.compile("(?m)^\\s*#\\s*define\\s+\\w+\\(");

	@Test
	void everyRecombinationTranslatesToWhatWasRecorded() throws IOException {
		List<String> recorded = GlslTranslatorGoldenTest.golden(GOLDEN).lines().toList();
		assertEquals(GlslTranslatorCases.RECOMBINATIONS, recorded.size(), "recorded recombinations");

		for (int index = 0; index < recorded.size(); index++) {
			assertEquals(recorded.get(index), GlslTranslatorCases.recombinationLine(index),
					"recombination " + index);
		}
	}

	@Test
	void noRecombinationCarriesWhatTheFencedGoldensAreAbout() {
		for (int index = 0; index < GlslTranslatorCases.RECOMBINATIONS; index++) {
			String source = GlslTranslatorCases.recombination(index).source();

			assertFalse(source.contains("#version"), "a version line in recombination " + index);
			assertFalse(FUNCTION_LIKE_MACRO.matcher(source).find(), "a function-like macro in recombination " + index);
		}
	}

	@Test
	void theSeriesIsVariedInBothStages() throws IOException {
		List<String> recorded = GlslTranslatorGoldenTest.golden(GOLDEN).lines().toList();
		Set<String> texts = new HashSet<>();
		int vertex = 0;
		for (String line : recorded) {
			String[] fields = line.split(" ", -1);
			texts.add(fields[fields.length - 1]);
			vertex += fields[1].equals("VERTEX") ? 1 : 0;
		}

		// Neighbouring seeds of java.util.Random begin alike, which once made every text of a series
		// the same stage: the two stages have to both be well represented, and no text repeats itself.
		assertTrue(vertex > recorded.size() / 4 && vertex < recorded.size() * 3 / 4,
				"vertex stages " + vertex + " of " + recorded.size());
		assertTrue(texts.size() > recorded.size() * 9 / 10, "distinct texts " + texts.size());
	}
}
