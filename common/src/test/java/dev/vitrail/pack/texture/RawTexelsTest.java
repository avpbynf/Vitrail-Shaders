package dev.vitrail.pack.texture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.PixelFormat;
import dev.vitrail.pack.model.PixelType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds which descriptions of a raw texel the engine can turn into a texture, the bound on what a
 * blob may come out as, and the value one takes in each channel type.
 */
class RawTexelsTest {

	private static final Set<PixelType> TYPES = EnumSet.of(PixelType.UNSIGNED_BYTE, PixelType.UNSIGNED_SHORT,
			PixelType.HALF_FLOAT, PixelType.FLOAT);

	private static final Set<PixelFormat> FORMATS = EnumSet.of(PixelFormat.RED, PixelFormat.RG, PixelFormat.RGB,
			PixelFormat.RGBA);

	@Test
	void holdsExactlyTheSixteenCombinationsOfFourTypesAndFourChannelOrders() {
		int held = 0;
		for (PixelType type : PixelType.values()) {
			for (PixelFormat format : PixelFormat.values()) {
				boolean expected = TYPES.contains(type) && FORMATS.contains(format);

				assertEquals(expected, RawTexels.holds(type, format), type + " " + format);
				if (expected) {
					held++;
				}
			}
		}

		assertEquals(16, held);
	}

	@Test
	void anIntegerTypeAndASwappedOrderAreNotHeld() {
		assertFalse(RawTexels.holds(PixelType.UNSIGNED_INT, PixelFormat.RGBA));
		assertFalse(RawTexels.holds(PixelType.INT, PixelFormat.RED));
		assertFalse(RawTexels.holds(PixelType.UNSIGNED_BYTE, PixelFormat.BGRA));
		assertFalse(RawTexels.holds(PixelType.UNSIGNED_BYTE, PixelFormat.RGBA_INTEGER));
		assertFalse(RawTexels.holds(PixelType.UNSIGNED_SHORT_5_6_5, PixelFormat.RGB));
	}

	@Test
	void everyTextureIsFourChannelsWide() {
		assertEquals(4, RawTexels.CHANNELS);
		assertEquals(3, RawTexels.ALPHA);
	}

	@Test
	void aSideUpToSixteenThousandThreeHundredEightyFourAndAHundredTwentyEightMebibytesFit() {
		assertTrue(RawTexels.fits(1, 1, 4));
		assertTrue(RawTexels.fits(16384, 1, 16));
		// Exactly 128 MiB is allowed and one row more is not.
		assertTrue(RawTexels.fits(16384, 2048, 4));
		assertFalse(RawTexels.fits(16384, 2049, 4));
		assertTrue(RawTexels.fits(4096, 2048, 16));
		assertFalse(RawTexels.fits(4096, 2049, 16));
		assertTrue(RawTexels.fits(8192, 4096, 4));
		assertFalse(RawTexels.fits(8192, 4097, 4));
	}

	@Test
	void aSideOverTheCeilingNeverFitsHoweverLittleItHolds() {
		assertFalse(RawTexels.fits(16385, 1, 4));
		assertFalse(RawTexels.fits(1, 16385, 4));
		assertFalse(RawTexels.fits(100_000, 1, 1));
	}

	@Test
	void theTotalIsCountedInLongSoTheLargestTextureCannotWrapIt() {
		// 16384 x 16384 x 16 bytes is 4 GiB, which is past what an int holds.
		assertFalse(RawTexels.fits(16384, 16384, 16));
		assertFalse(RawTexels.fits(16384, 16384, 1));
	}

	@Test
	void oneIsTheValueOneInTheChannelsOwnTypeAndByteOrder() {
		assertArrayEquals(new byte[] {(byte) 0xFF}, RawTexels.one(PixelType.UNSIGNED_BYTE));
		assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF}, RawTexels.one(PixelType.UNSIGNED_SHORT));

		byte[] half = RawTexels.one(PixelType.HALF_FLOAT);
		assertEquals(1.0F, Float.float16ToFloat(ByteBuffer.wrap(half).order(ByteOrder.LITTLE_ENDIAN).getShort()));

		byte[] single = RawTexels.one(PixelType.FLOAT);
		assertEquals(1.0F, ByteBuffer.wrap(single).order(ByteOrder.LITTLE_ENDIAN).getFloat());
		assertEquals(4, single.length);
	}

	@Test
	void oneIsOnlyDefinedForTheTypesThatAreLaidOut() {
		for (PixelType type : PixelType.values()) {
			if (TYPES.contains(type)) {
				assertEquals(type.channelBytes(), RawTexels.one(type).length, type.name());
			} else {
				assertThrows(IllegalStateException.class, () -> RawTexels.one(type), type.name());
			}
		}
	}
}
