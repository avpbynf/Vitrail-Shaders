package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.settings.PackFile;

import org.junit.jupiter.api.Test;

/**
 * Holds the size the world is rendered at to the percentage the player asked for.
 * <p>
 * The whole feature is the size of the main render target, so this is the one number everything
 * downstream keys off: the translucency targets, the pack's colour targets, its depth images and
 * {@code viewWidth}. It is cut by an integer division, one side at a time, and never below one.
 */
class RenderScaleTest {

	@Test
	void anEvenWindowAtHalfIsHalfOnBothSides() {
		assertEquals(960, RenderScale.scaled(1920, 50));
		assertEquals(540, RenderScale.scaled(1080, 50));
	}

	@Test
	void anOddSideIsCutDownAndNeverRoundedUp() {
		assertEquals(960, RenderScale.scaled(1921, 50), "960.5");
		assertEquals(1439, RenderScale.scaled(1919, 75), "1439.25");
		assertEquals(213, RenderScale.scaled(854, 25), "213.5");
		assertEquals(2534, RenderScale.scaled(7680, 33), "2534.4");
		assertEquals(1900, RenderScale.scaled(1920, 99), "1900.8 is cut, not rounded to 1901");
	}

	@Test
	void aWindowThatIsNotSquareScalesEachSideOnItsOwn() {
		assertEquals(683, RenderScale.scaled(1367, 50));
		assertEquals(384, RenderScale.scaled(769, 50));
		assertEquals(1, RenderScale.scaled(1, 50));
		assertEquals(800, RenderScale.scaled(3200, 25));
	}

	@Test
	void aSideThatScalesToNothingStaysOneTexel() {
		assertEquals(1, RenderScale.scaled(3, 25), "0.75");
		assertEquals(1, RenderScale.scaled(2, 25), "0.5");
		assertEquals(1, RenderScale.scaled(1, 99), "0.99");
		assertEquals(1, RenderScale.scaled(0, 50), "a minimised window reports nought");
	}

	@Test
	void aSideOfOneTexelIsNeverSmallerThanTheWindowSoScalingStandsDownForIt() {
		// beginWorld stands down when either scaled side is not smaller than the window's. A one texel
		// side is the only one that can never be made smaller, so a window that thin is never scaled.
		for (int percent = PackFile.MIN_RENDER_SCALE; percent < PackFile.MAX_RENDER_SCALE; percent++) {
			assertTrue(RenderScale.scaled(1, percent) >= 1, "percent " + percent);
			assertTrue(RenderScale.scaled(2, percent) < 2, "two texels shrink at " + percent);
		}
	}

	@Test
	void aWholeWindowIsUntouchedAtAHundredPercent() {
		for (int extent : new int[] {1, 2, 3, 640, 1919, 1920, 4096, 16_384}) {
			assertEquals(extent, RenderScale.scaled(extent, PackFile.MAX_RENDER_SCALE), "extent " + extent);
		}
	}

	@Test
	void everyExtentAndEveryPercentOfTheSliderMatchesTheExactQuotient() {
		// The second reading: the floor of the exact rational, in long arithmetic, with the floor of one.
		int checked = 0;
		for (int extent = 1; extent <= 8192; extent++) {
			int previous = 0;
			for (int percent = PackFile.MIN_RENDER_SCALE; percent <= PackFile.MAX_RENDER_SCALE; percent++) {
				long exact = Math.max(1L, Math.floorDiv((long) extent * percent, 100L));
				int got = RenderScale.scaled(extent, percent);
				assertEquals(exact, got, extent + " at " + percent + "%");
				assertTrue(got <= extent, "never larger than the window");
				assertTrue(got >= previous, "never smaller for a bigger percentage");
				previous = got;
				checked++;
			}
		}

		assertEquals(8192 * 76, checked);
	}

	@Test
	void theSliderRangeIsTheFilesAndStartsAtAQuarter() {
		assertEquals(25, PackFile.MIN_RENDER_SCALE);
		assertEquals(100, PackFile.MAX_RENDER_SCALE);
	}

	@Test
	void knownBug_anExtentOverTwentyOneMillionOverflowsTheProductAndFallsToOneTexel() {
		// extent * percent is an int product, so a side of 21,691,755 or more at 99 percent wraps negative
		// and the floor of one is what is left. No window is that wide, so it is pinned and not reported
		// as a defect that matters.
		assertEquals(21_474_836, RenderScale.scaled(21_474_836, 100), "the largest side that survives 100");
		assertEquals(1, RenderScale.scaled(21_474_837, 100));
		assertEquals(1, RenderScale.scaled(Integer.MAX_VALUE, 50));
	}
}
