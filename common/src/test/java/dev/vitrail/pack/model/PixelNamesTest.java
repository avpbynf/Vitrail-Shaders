package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds how a raw texture's pixel format and pixel type are read, and what they say about how many
 * bytes a texel costs: it is the one check that tells a truncated blob from a whole one.
 */
class PixelNamesTest {

	// ---- PixelFormat -------------------------------------------------------------------------

	@Test
	void everyFormatRoundTripsThroughItsNameInAnyCase() {
		for (PixelFormat format : PixelFormat.values()) {
			assertEquals(Optional.of(format), PixelFormat.parse(format.name()));
			assertEquals(Optional.of(format), PixelFormat.parse(format.name().toLowerCase(Locale.ROOT)));
			assertEquals(Optional.of(format), PixelFormat.parse("  " + format.name() + "\t"));
		}
	}

	@Test
	void aFormatNameIsExactlyOneOfTheTwelve() {
		assertEquals(12, PixelFormat.values().length);
		for (String name : new String[] {"", "R", "RGBAA", "RGB A", "RED_INT", "LUMINANCE", "RGBA_", "rgba8"}) {
			assertTrue(PixelFormat.parse(name).isEmpty(), "'" + name + "'");
		}
	}

	@Test
	void countsTheChannelsOfEachFormat() {
		assertEquals(1, PixelFormat.RED.components());
		assertEquals(2, PixelFormat.RG.components());
		assertEquals(3, PixelFormat.RGB.components());
		assertEquals(3, PixelFormat.BGR.components());
		assertEquals(4, PixelFormat.RGBA.components());
		assertEquals(4, PixelFormat.BGRA.components());
		assertEquals(1, PixelFormat.RED_INTEGER.components());
		assertEquals(2, PixelFormat.RG_INTEGER.components());
		assertEquals(3, PixelFormat.RGB_INTEGER.components());
		assertEquals(3, PixelFormat.BGR_INTEGER.components());
		assertEquals(4, PixelFormat.RGBA_INTEGER.components());
		assertEquals(4, PixelFormat.BGRA_INTEGER.components());
	}

	// ---- PixelType ---------------------------------------------------------------------------

	@Test
	void everyTypeRoundTripsThroughItsNameInAnyCase() {
		for (PixelType type : PixelType.values()) {
			assertEquals(Optional.of(type), PixelType.parse(type.name()));
			assertEquals(Optional.of(type), PixelType.parse(type.name().toLowerCase(Locale.ROOT)));
			assertEquals(Optional.of(type), PixelType.parse(" " + type.name() + " "));
		}
		assertEquals(22, PixelType.values().length);
	}

	@Test
	void aTypeNameIsExactlyOneOfThem() {
		for (String name : new String[] {"", "UBYTE", "UNSIGNED", "FLOAT16", "HALF", "UNSIGNED_BYTE_", "float32"}) {
			assertTrue(PixelType.parse(name).isEmpty(), "'" + name + "'");
		}
	}

	@Test
	void aPlainTypeGivesTheWidthOfOneChannel() {
		assertEquals(1, PixelType.BYTE.channelBytes());
		assertEquals(2, PixelType.SHORT.channelBytes());
		assertEquals(4, PixelType.INT.channelBytes());
		assertEquals(2, PixelType.HALF_FLOAT.channelBytes());
		assertEquals(4, PixelType.FLOAT.channelBytes());
		assertEquals(1, PixelType.UNSIGNED_BYTE.channelBytes());
		assertEquals(2, PixelType.UNSIGNED_SHORT.channelBytes());
		assertEquals(4, PixelType.UNSIGNED_INT.channelBytes());
	}

	@Test
	void aPlainTypeCostsItsWidthTimesTheChannels() {
		assertEquals(1, PixelType.UNSIGNED_BYTE.bytesPerTexel(PixelFormat.RED));
		assertEquals(3, PixelType.UNSIGNED_BYTE.bytesPerTexel(PixelFormat.RGB));
		assertEquals(4, PixelType.HALF_FLOAT.bytesPerTexel(PixelFormat.RG));
		assertEquals(8, PixelType.UNSIGNED_SHORT.bytesPerTexel(PixelFormat.RGBA));
		assertEquals(16, PixelType.FLOAT.bytesPerTexel(PixelFormat.RGBA));
		assertEquals(12, PixelType.INT.bytesPerTexel(PixelFormat.BGR_INTEGER));
	}

	@Test
	void aPackedTypeCostsTheWholeWordWhateverTheChannels() {
		assertEquals(1, PixelType.UNSIGNED_BYTE_3_3_2.bytesPerTexel(PixelFormat.RGB));
		assertEquals(1, PixelType.UNSIGNED_BYTE_2_3_3_REV.bytesPerTexel(PixelFormat.RGB));
		assertEquals(2, PixelType.UNSIGNED_SHORT_5_6_5.bytesPerTexel(PixelFormat.RGB));
		assertEquals(2, PixelType.UNSIGNED_SHORT_5_6_5_REV.bytesPerTexel(PixelFormat.RGB));
		assertEquals(2, PixelType.UNSIGNED_SHORT_4_4_4_4.bytesPerTexel(PixelFormat.RGBA));
		assertEquals(2, PixelType.UNSIGNED_SHORT_5_5_5_1.bytesPerTexel(PixelFormat.RGBA));
		assertEquals(4, PixelType.UNSIGNED_INT_8_8_8_8.bytesPerTexel(PixelFormat.RGBA));
		assertEquals(4, PixelType.UNSIGNED_INT_2_10_10_10_REV.bytesPerTexel(PixelFormat.BGRA));
		assertEquals(4, PixelType.UNSIGNED_INT_10F_11F_11F_REV.bytesPerTexel(PixelFormat.RGB));
		assertEquals(4, PixelType.UNSIGNED_INT_5_9_9_9_REV.bytesPerTexel(PixelFormat.RGB));
		// Multiplying by the channels would ask a file for three times what it holds.
		assertEquals(2, PixelType.UNSIGNED_SHORT_5_6_5.bytesPerTexel(PixelFormat.RGBA_INTEGER));
	}

	@Test
	void theWidthOfEveryTypeFollowsItsNameAndEveryPackedTypeIsOneWord() {
		for (PixelType type : PixelType.values()) {
			String name = type.name();
			boolean packed = name.startsWith("UNSIGNED_BYTE_") || name.startsWith("UNSIGNED_SHORT_")
					|| name.startsWith("UNSIGNED_INT_");
			int expected;
			if (name.equals("BYTE") || name.startsWith("UNSIGNED_BYTE")) {
				expected = 1;
			} else if (name.equals("SHORT") || name.equals("HALF_FLOAT") || name.startsWith("UNSIGNED_SHORT")) {
				expected = 2;
			} else {
				expected = 4;
			}

			assertEquals(expected, type.channelBytes(), name);
			// A plain type costs four channels of its width in RGBA, a packed one costs its width.
			assertEquals(packed ? expected : 4L * expected, type.bytesPerTexel(PixelFormat.RGBA), name);
		}
	}
}
