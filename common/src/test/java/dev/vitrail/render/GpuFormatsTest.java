package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.TargetFormat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Holds the table that turns a pack's colour format into the device's to the game's own description
 * of each format, for every format there is.
 * <p>
 * {@code GpuFormats.of} is a thirty eight arm switch written out by hand, and the memory a pack is
 * said to cost is summed from {@link TargetFormat#bytesPerPixel()}, a second table written by hand.
 * The game describes every format itself: its size, its channel count and the type of a channel. A
 * wrong arm, a wrong size or a wrong channel count is a copy and paste away and shows as a plausible
 * picture in the wrong precision, or as a memory figure that is wrong in the log and right nowhere.
 * <p>
 * The game's type is never named here: it lives in a different package in 26.3, and tests are not
 * rewritten onto that name the way the shared sources are.
 */
class GpuFormatsTest {

	private static final Pattern PLAIN = Pattern.compile("(R|RG|RGBA)(8|16|32)_(UNORM|SNORM|UINT|SINT|FLOAT)");

	@Test
	void everyFormatMapsToTheGamesFormatOfTheSameName() {
		for (TargetFormat format : TargetFormat.values()) {
			assertEquals(format.name(), GpuFormats.of(format).name(), "the device format of " + format);
		}
	}

	@Test
	void everyFormatIsTheSizeTheGameSaysItIs() {
		for (TargetFormat format : TargetFormat.values()) {
			assertEquals(GpuFormats.of(format).blockSize(), format.bytesPerPixel(), "bytes per pixel of " + format);
		}
	}

	@Test
	void everyPlainFormatHasTheChannelCountTheGameSaysItHas() {
		for (TargetFormat format : TargetFormat.values()) {
			if (PLAIN.matcher(format.name()).matches()) {
				assertEquals(GpuFormats.of(format).componentCount(), format.components(), "channels of " + format);
			}
		}
	}

	@Test
	void theSizeIsChannelsTimesTheWidthOfOneForEveryFormatSpelledOutInItsName() {
		// A second reading of the size, from the name alone: R, RG or RGBA, a width in bits and a kind.
		int checked = 0;
		for (TargetFormat format : TargetFormat.values()) {
			Matcher matcher = PLAIN.matcher(format.name());
			if (!matcher.matches()) {
				continue;
			}

			int channels = matcher.group(1).length();
			int bytes = Integer.parseInt(matcher.group(2)) / 8;
			assertEquals(channels * bytes, format.bytesPerPixel(), "bytes per pixel of " + format);
			assertEquals(channels, format.components(), "channels of " + format);
			checked++;
		}

		assertEquals(TargetFormat.values().length - 3, checked, "all but the two packed ten bit formats and RG11B10");
	}

	@Test
	void thePackedFormatsAreFourBytesAndOneOpaqueWordToTheGameWhateverTheirChannelCount() {
		// The game does not describe a packed format by its channels: it is one opaque thirty two bit
		// word, so the channel count of the pack side (four, four and three) has no counterpart to be
		// compared with and is pinned on its own.
		for (TargetFormat format : new TargetFormat[] {TargetFormat.RGB10A2_UNORM, TargetFormat.RGB10A2_UINT,
				TargetFormat.RG11B10_FLOAT}) {
			assertEquals(4, format.bytesPerPixel(), format.name());
			assertEquals("OPAQUE_32", GpuFormats.of(format).componentType().name(), format.name());
			assertEquals(1, GpuFormats.of(format).componentCount(), format.name());
		}

		assertEquals(4, TargetFormat.RGB10A2_UNORM.components());
		assertEquals(4, TargetFormat.RGB10A2_UINT.components());
		assertEquals(3, TargetFormat.RG11B10_FLOAT.components(), "three logical channels in four bytes");
		assertTrue(TargetFormat.RGB10A2_UINT.integer(), "and the integer one is integer on the pack side");
	}

	@Test
	void aChannelOfAnIntegerFormatIsAnIntegerTypeInTheGameAndSoIsOnlyThat() {
		for (TargetFormat format : TargetFormat.values()) {
			if (!PLAIN.matcher(format.name()).matches()) {
				continue;
			}

			String type = GpuFormats.of(format).componentType().name();
			boolean integer = type.startsWith("UINT") || type.startsWith("SINT");

			assertEquals(integer, format.integer(), format + " is " + type + " in the game");
		}
	}

	@Test
	void theKindAndWidthOfAChannelAreTheOnesTheNameSays() {
		for (TargetFormat format : TargetFormat.values()) {
			Matcher matcher = PLAIN.matcher(format.name());
			if (matcher.matches()) {
				assertEquals(matcher.group(3) + "_" + matcher.group(2), GpuFormats.of(format).componentType().name(),
						"the channel type of " + format);
			}
		}
	}

	@Test
	void anIntegerFormatIsReadNearestAndEveryOtherFormatLinear() {
		int integers = 0;
		for (TargetFormat format : TargetFormat.values()) {
			assertEquals(format.integer() ? "NEAREST" : "LINEAR", GpuFormats.filterFor(format).name(),
					"the filter of " + format);
			if (format.integer()) {
				integers++;
			}
		}

		assertTrue(integers > 10, "there are integer formats, so the NEAREST arm is exercised: " + integers);
	}

	@Test
	void withNoDeviceToAskADeviceFeatureIsAnsweredTheCautiousWay() {
		// Filtering is assumed, because taking it away on a reading that proves nothing would cost every
		// texture of every pack its blur; a storage image and a blit are not, because scheduling a compute
		// or a chain fill the device may refuse costs a frame it cannot report.
		var format = GpuFormats.of(TargetFormat.RGBA8_UNORM);

		assertTrue(GpuFormats.filtersLinearly(format));
		assertEquals(false, GpuFormats.storageCapable(format));
		assertEquals(false, GpuFormats.blitsBothWays(format));
	}
}
