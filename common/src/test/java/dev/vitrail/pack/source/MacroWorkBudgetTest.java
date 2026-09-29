package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds what a pack's own defines can cost to a bound on the work, and not only on the depth.
 * <p>
 * Every name is read again from its text wherever it is met, so a define written as a wide sum of
 * the next one costs the width to the power of the levels, and four levels sit well inside the
 * depth a condition is allowed. The properties side has the same shape in its substitution, where
 * what grows is the line itself.
 */
class MacroWorkBudgetTest {

	/** Far longer than the bounded work takes, and far shorter than the unbounded work did. */
	private static final Duration QUICKLY = Duration.ofSeconds(2);

	@Test
	void givesNoAnswerToAFanOutBomb() {
		Map<String, String> defines = fanOut(100, " + ", 5, "1");

		PreprocessorExpression.Verdict verdict =
				assertTimeoutPreemptively(QUICKLY, () -> PreprocessorExpression.decide("A0 > 0", defines));

		assertEquals(Optional.empty(), verdict.taken());
	}

	@Test
	void stillResolvesSettingsDefinedThroughOthers() {
		Map<String, String> defines = Map.of(
				"QUALITY", "2",
				"SHADOW_RES", "(QUALITY * 512)",
				"SHADOW_RES_ALIAS", "SHADOW_RES",
				"HIGH_SHADOWS", "(SHADOW_RES_ALIAS >= 1024 && defined QUALITY)");

		assertEquals(Optional.of(true), PreprocessorExpression.decide("HIGH_SHADOWS", defines).taken());
		assertEquals(Optional.of(false),
				PreprocessorExpression.decide("SHADOW_RES == 512", defines).taken());
	}

	@Test
	void stillResolvesAWideSumThatStops() {
		// Thirty of the next name, two levels down: nine hundred and thirty one names, which is
		// wider than any condition of the corpus and still worked out rather than given up on.
		Map<String, String> defines = fanOut(30, " + ", 2, "1");

		assertEquals(Optional.of(true), PreprocessorExpression.decide("A0 == 900", defines).taken());
	}

	@Test
	void stopsASubstitutionThatGrowsPastTheCeiling() {
		Map<String, String> defines = fanOut(50, " ", 4, "Z");

		String expanded = Macros.expand("A0", defines);

		assertTrue(expanded.length() <= 256 * 1024, "grew to " + expanded.length() + " characters");
		assertTrue(expanded.startsWith("A2 A2 "), expanded.substring(0, 16));
	}

	@Test
	void stillSubstitutesAsItDid() {
		Map<String, String> defines = Map.of(
				"BIOME_GROVE", "5",
				"BIOME_FROZEN_OCEAN", "BIOME_OCEAN_FROZEN",
				"BIOME_OCEAN_FROZEN", "10");

		assertEquals("in(biome, 5, 10)",
				Macros.expand("in(biome, BIOME_GROVE, BIOME_FROZEN_OCEAN)", defines));
	}

	/**
	 * {@code A0} written as {@code width} of {@code A1} joined by {@code between}, {@code A1} as
	 * many {@code A2}, and so on for {@code levels}, the last of them standing for {@code last}.
	 */
	private static Map<String, String> fanOut(int width, String between, int levels, String last) {
		Map<String, String> defines = new HashMap<>();
		for (int level = 0; level < levels; level++) {
			String next = String.join(between, Collections.nCopies(width, "A" + (level + 1)));
			defines.put("A" + level, between.isBlank() ? next : "(" + next + ")");
		}

		defines.put("A" + levels, last);

		return defines;
	}
}
