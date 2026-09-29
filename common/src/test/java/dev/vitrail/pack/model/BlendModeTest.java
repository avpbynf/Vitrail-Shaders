package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds how {@code blend.PROGRAM} is read: four factors, two factors that mean what
 * {@code glBlendFunc} means, or {@code off}; and that not understanding is empty, never off.
 */
class BlendModeTest {

	@Test
	void offInAnyCaseIsTheOneNoBlendMode() {
		for (String word : new String[] {"off", "OFF", "Off", " off ", "\toff\n"}) {
			Optional<BlendMode> mode = BlendMode.parse(word);

			assertTrue(mode.isPresent(), word);
			assertSame(BlendMode.OFF, mode.get(), word);
			assertTrue(mode.get().off());
		}
		assertEquals("off", BlendMode.OFF.toString());
	}

	@Test
	void fourFactorsAreSrcRgbDstRgbSrcAlphaDstAlphaUpperCased() {
		BlendMode mode = BlendMode.parse("src_alpha One_Minus_Src_Alpha ONE zero").orElseThrow();

		assertFalse(mode.off());
		assertEquals("SRC_ALPHA", mode.srcRgb());
		assertEquals("ONE_MINUS_SRC_ALPHA", mode.dstRgb());
		assertEquals("ONE", mode.srcAlpha());
		assertEquals("ZERO", mode.dstAlpha());
		assertEquals("SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO", mode.toString());
	}

	@Test
	void twoFactorsAreTheSamePairForColourAndAlpha() {
		BlendMode mode = BlendMode.parse("SRC_ALPHA ONE").orElseThrow();

		assertEquals(new BlendMode(false, "SRC_ALPHA", "ONE", "SRC_ALPHA", "ONE"), mode);
		assertEquals("SRC_ALPHA ONE SRC_ALPHA ONE", mode.toString());
	}

	@Test
	void whatIsNotOffTwoOrFourWordsIsUnderstoodAsNothing() {
		for (String value : new String[] {"", " ", "ONE", "ONE ONE ONE", "ONE ONE ONE ONE ONE", "SRC_ALPHA  ONE",
			"SRC_ALPHA\tONE", "a  b c d", "of", "offf"}) {
			assertTrue(BlendMode.parse(value).isEmpty(), "'" + value + "'");
		}
		assertTrue(BlendMode.parse(null).isEmpty());
	}

	@Test
	void anyFourWordsAreAcceptedWhateverTheyNameSinceTheBackendChecksThem() {
		BlendMode mode = BlendMode.parse("A B C D").orElseThrow();

		assertEquals(new BlendMode(false, "A", "B", "C", "D"), mode);
		// Two words are a pair whatever they say, off among them.
		assertEquals(new BlendMode(false, "OFF", "OFF", "OFF", "OFF"), BlendMode.parse("off off").orElseThrow());
	}

	@Test
	void theNoBlendModeCarriesNoFactors() {
		assertEquals(new BlendMode(true, "", "", "", ""), BlendMode.OFF);
	}
}
