package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds how {@code size.buffer.NAME} is read: a dot makes a value a fraction of the screen and its
 * absence makes it a count of pixels, and the value may be the name of one of the pack's settings.
 */
class TargetSizeTest {

	private static TargetSize parsed(String value, Map<String, String> defines) {
		return TargetSize.parse(value, defines).orElseThrow(() -> new AssertionError("'" + value + "' was refused"));
	}

	private static TargetSize parsed(String value) {
		return parsed(value, Map.of());
	}

	@Test
	void aDotMakesAFractionOfTheScreen() {
		TargetSize half = parsed("0.5 0.5");

		assertTrue(half.relative());
		assertEquals(0.5F, half.width());
		assertEquals(0.5F, half.height());
		assertEquals(960, half.width(1920));
		assertEquals(540, half.height(1080));
		assertFalse(half.full());
	}

	@Test
	void theAbsenceOfADotMakesACountOfPixelsWhateverTheWindowDoes() {
		TargetSize fixed = parsed("960 540");

		assertFalse(fixed.relative());
		assertEquals(960, fixed.width(1920));
		assertEquals(960, fixed.width(10));
		assertEquals(540, fixed.height(4000));
		assertFalse(fixed.full());
	}

	@Test
	void oneAndOneWithADotIsTheWholeScreenAndWithoutOnePixel() {
		assertTrue(parsed("1.0 1.0").full());
		assertEquals(TargetSize.ofScreen(), parsed("1.0 1.0"));
		assertTrue(TargetSize.ofScreen().full());
		assertSame(TargetSize.ofScreen(), TargetSize.ofScreen());

		TargetSize pixel = parsed("1 1");
		assertFalse(pixel.full());
		assertEquals(1, pixel.width(1920));
	}

	@Test
	void aFractionAboveOneIsAMultipleOfTheScreen() {
		TargetSize twice = parsed("2.0 2.0");

		assertEquals(3840, twice.width(1920));
		assertEquals(2160, twice.height(1080));
		assertFalse(twice.full());
	}

	@Test
	void theTwoAxesHaveToAgreeAboutBeingRelative() {
		assertTrue(TargetSize.parse("0.5 540", Map.of()).isEmpty());
		assertTrue(TargetSize.parse("960 0.5", Map.of()).isEmpty());
	}

	@Test
	void theValueIsTwoTokensSeparatedByASingleSpace() {
		assertEquals(Optional.of(new TargetSize(true, 0.5F, 0.25F)), TargetSize.parse(" 0.5 0.25 ", Map.of()));
		for (String value : new String[] {"", "0.5", "0.5 0.5 0.5", "0.5  0.5", "0.5\t0.5", " ", "a b", "0.5 x",
			"x 0.5", "0x10 0x10", "0.5, 0.5"}) {
			assertTrue(TargetSize.parse(value, Map.of()).isEmpty(), "'" + value + "'");
		}
	}

	@Test
	void aTokenMayBeTheNameOfASetting() {
		Map<String, String> defines = Map.of("REFLECTION_RES", "0.5", "W", "1920", "H", " 1080 ");

		assertEquals(new TargetSize(true, 0.5F, 0.5F), parsed("REFLECTION_RES REFLECTION_RES", defines));
		assertEquals(new TargetSize(false, 1920F, 1080F), parsed("W H", defines));
		assertTrue(TargetSize.parse("REFLECTION_RES 1080", defines).isEmpty(), "a fraction and a count");
		assertTrue(TargetSize.parse("UNDECLARED UNDECLARED", defines).isEmpty());
	}

	@Test
	void aSettingMayPointAtAnotherSettingButNotForever() {
		assertEquals(new TargetSize(true, 0.25F, 0.25F), parsed("A A", Map.of("A", "B", "B", "0.25")));

		// A loop has no end to read a number from; after eight hops what is left is a name.
		assertTrue(TargetSize.parse("A A", Map.of("A", "B", "B", "A")).isEmpty());

		// Eight hops are allowed and a ninth is not.
		Map<String, String> chain = Map.of("A0", "A1", "A1", "A2", "A2", "A3", "A3", "A4", "A4", "A5", "A5", "A6",
				"A6", "A7", "A7", "0.5");
		assertEquals(new TargetSize(true, 0.5F, 0.5F), parsed("A0 A0", chain));
		Map<String, String> longer = Map.of("A0", "A1", "A1", "A2", "A2", "A3", "A3", "A4", "A4", "A5", "A5", "A6",
				"A6", "A7", "A7", "A8", "A8", "0.5");
		assertTrue(TargetSize.parse("A0 A0", longer).isEmpty());
	}

	@Test
	void aNumberIsWhatFloatParseAcceptsSoASuffixAndAnExponentPass() {
		assertEquals(new TargetSize(true, 0.5F, 0.5F), parsed("0.5f 0.5f"));
		assertEquals(new TargetSize(false, 1000F, 1000F), parsed("1e3 1e3"));
		assertEquals(new TargetSize(true, 0.5F, 0.5F), parsed(".5 .5"));
		assertEquals(new TargetSize(true, 500F, 500F), parsed("5.0E2 5.0E2"));
	}

	@Test
	void aSideIsNeverBelowOneNorAboveTheCeiling() {
		TargetSize tiny = parsed("0.0001 0.0001");
		assertEquals(1, tiny.width(1920));
		assertEquals(1, tiny.height(1080));

		TargetSize negative = parsed("-0.5 -0.5");
		assertEquals(1, negative.width(1920));

		assertEquals(1, parsed("-1 -1").width(1920));
		assertEquals(1, parsed("0 0").width(1920));

		assertEquals(TargetSize.MAX_DIMENSION, parsed("100000 100000").width(1920));
		assertEquals(16384, TargetSize.MAX_DIMENSION);
		assertEquals(16384, parsed("20.0 20.0").width(1920));
		assertEquals(16384, parsed("16384 16384").width(1));
	}

	@Test
	void aFractionIsMultipliedInFloatAndCutNotRounded() {
		assertEquals(959, parsed("0.5 0.5").width(1919));
		assertEquals(1, parsed("0.5 0.5").width(3));
		assertEquals(1, parsed("0.5 0.5").width(2));
		assertEquals(100, parsed("0.1 0.1").width(1000));
		assertEquals(270, parsed("0.25 0.25").height(1080));
	}

	@Test
	void notANumberAndInfinityAreAcceptedAsCountsAndClamped() {
		TargetSize nan = parsed("NaN NaN");
		assertFalse(nan.relative());
		assertEquals(1, nan.width(1920));

		TargetSize infinite = parsed("Infinity Infinity");
		assertEquals(TargetSize.MAX_DIMENSION, infinite.width(1920));
		assertTrue(infinite.overCap());
	}

	@Test
	void onlyAnAbsoluteSizePastTheCeilingIsOverTheCap() {
		assertTrue(parsed("16385 1").overCap());
		assertTrue(parsed("1 16385").overCap());
		assertTrue(parsed("100000 100000").overCap());
		assertFalse(parsed("16384 16384").overCap());
		assertFalse(parsed("960 540").overCap());
		// A relative size is measured against a window that is already allocated.
		assertFalse(parsed("20.0 20.0").overCap());
	}
}
