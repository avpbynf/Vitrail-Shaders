package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslTranslatorCases.Pair;
import dev.vitrail.glsl.GlslTranslatorCases.Single;

import java.io.IOException;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds the goldens whose output depends on two readings that are expected to change: how a
 * {@code #version} line is taken (which of several lines decides, and whether a line no branch takes
 * may), and how the parameters of a function-like macro are told from the names they shadow.
 * <p>
 * They are a test of their own and their inputs are not in the recombinations, so that when either
 * reading is changed on purpose the goldens named here can be written again without touching any
 * other, and a failure anywhere else still means the translator moved where nobody meant it to.
 * What each case records is what the code does today.
 */
class GlslTranslatorFencedGoldenTest {

	static Stream<Single> singles() {
		return GlslTranslatorCases.fenced().stream();
	}

	static Stream<Pair> pairs() {
		return GlslTranslatorCases.fencedPairs().stream();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("singles")
	void translatesOneStageToItsGolden(Single one) throws IOException {
		GlslTranslatorGoldenTest.assertGolden(one.name(), GlslTranslatorCases.run(one));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("pairs")
	void translatesAProgramToItsGolden(Pair pair) throws IOException {
		GlslTranslatorGoldenTest.assertGolden(pair.name(), GlslTranslatorCases.run(pair));
	}
}
