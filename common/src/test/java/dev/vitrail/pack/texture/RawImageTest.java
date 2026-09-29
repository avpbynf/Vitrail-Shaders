package dev.vitrail.pack.texture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.PackTexture;
import dev.vitrail.pack.model.PixelFormat;
import dev.vitrail.pack.model.PixelType;
import dev.vitrail.pack.model.TargetFormat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Holds how a blob the pack ships as a plain two dimensional texture is laid out for upload: four
 * channels of the blob's own type, a channel the blob has not got at nought and a missing alpha at
 * one, in the order the file was written in.
 */
class RawImageTest {

	private static PackTexture.Raw raw(PackTexture.Shape shape, int x, int y, PixelFormat format, PixelType type) {
		return new PackTexture.Raw(shape, TargetFormat.resolve("RGBA8"), x, y, 0, format, type);
	}

	private static PackTexture.Raw flat(int x, int y, PixelFormat format, PixelType type) {
		return raw(PackTexture.Shape.TEXTURE_2D, x, y, format, type);
	}

	@Test
	void servesATwoDimensionalBlobOfAChannelTypeItCarries() {
		assertTrue(RawImage.serves(flat(160, 560, PixelFormat.RG, PixelType.UNSIGNED_BYTE)));
		assertTrue(RawImage.serves(flat(1, 1, PixelFormat.RGBA, PixelType.FLOAT)));
		assertTrue(RawImage.serves(flat(4, 4, PixelFormat.RED, PixelType.HALF_FLOAT)));
	}

	@Test
	void refusesTheOtherShapesEmptyExtentsAndTheTypesItDoesNotLayOut() {
		assertFalse(RawImage.serves(raw(PackTexture.Shape.TEXTURE_1D, 4, 0, PixelFormat.RG, PixelType.UNSIGNED_BYTE)));
		assertFalse(RawImage.serves(raw(PackTexture.Shape.TEXTURE_3D, 4, 4, PixelFormat.RG, PixelType.UNSIGNED_BYTE)));
		assertFalse(RawImage.serves(raw(PackTexture.Shape.TEXTURE_RECTANGLE, 4, 4, PixelFormat.RG,
				PixelType.UNSIGNED_BYTE)));
		assertFalse(RawImage.serves(flat(0, 4, PixelFormat.RG, PixelType.UNSIGNED_BYTE)));
		assertFalse(RawImage.serves(flat(4, 0, PixelFormat.RG, PixelType.UNSIGNED_BYTE)));
		assertFalse(RawImage.serves(flat(-4, 4, PixelFormat.RG, PixelType.UNSIGNED_BYTE)));
		assertFalse(RawImage.serves(flat(4, 4, PixelFormat.RG, PixelType.UNSIGNED_INT)));
		assertFalse(RawImage.serves(flat(4, 4, PixelFormat.BGRA, PixelType.UNSIGNED_BYTE)));
	}

	@Test
	void aBlobItDoesNotServeCannotBeLaidOutAndSaysWhy() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> RawImage.of(flat(0, 4, PixelFormat.RG, PixelType.UNSIGNED_BYTE)));

		assertEquals("A texture of 0x4 in RG UNSIGNED_BYTE is not one this uploads flat", e.getMessage());
	}

	@Test
	void aLayoutKnowsItsSizeAndItsChannelsAndWhatAnUploadedTexelCosts() {
		RawImage image = RawImage.of(flat(160, 560, PixelFormat.RG, PixelType.UNSIGNED_BYTE));

		assertEquals(160, image.width());
		assertEquals(560, image.height());
		assertEquals(PixelType.UNSIGNED_BYTE, image.type());
		assertEquals(2, image.components());
		assertEquals(1, image.channelBytes());
		assertEquals(4, image.texelBytes());
		assertEquals(16, RawImage.of(flat(1, 1, PixelFormat.RGBA, PixelType.FLOAT)).texelBytes());
		assertEquals(8, RawImage.of(flat(1, 1, PixelFormat.RED, PixelType.HALF_FLOAT)).texelBytes());
	}

	@Test
	void widensATwoChannelByteBlobWithNoBlueAndAnOpaqueAlpha() {
		RawImage image = RawImage.of(flat(2, 1, PixelFormat.RG, PixelType.UNSIGNED_BYTE));

		byte[] widened = image.widen(new byte[] {1, 2, 3, 4});

		assertArrayEquals(new byte[] {1, 2, 0, (byte) 0xFF, 3, 4, 0, (byte) 0xFF}, widened);
	}

	@Test
	void widensEveryChannelCountOfBytesTheSameWay() {
		byte[] blob = {10, 20, 30, 40, 50, 60, 70, 80};

		assertArrayEquals(new byte[] {10, 0, 0, -1, 20, 0, 0, -1},
				RawImage.of(flat(2, 1, PixelFormat.RED, PixelType.UNSIGNED_BYTE)).widen(blob));
		assertArrayEquals(new byte[] {10, 20, 30, -1, 40, 50, 60, -1},
				RawImage.of(flat(2, 1, PixelFormat.RGB, PixelType.UNSIGNED_BYTE)).widen(blob));
		// Four channels are what is uploaded, so the alpha the blob has is the alpha it keeps.
		assertArrayEquals(new byte[] {10, 20, 30, 40, 50, 60, 70, 80},
				RawImage.of(flat(2, 1, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE)).widen(blob));
	}

	@Test
	void widensShortsHalvesAndFloatsInTheirOwnWidth() {
		byte[] shorts = {1, 2, 3, 4, 5, 6, 7, 8};
		assertArrayEquals(new byte[] {1, 2, 3, 4, 0, 0, -1, -1, 5, 6, 7, 8, 0, 0, -1, -1},
				RawImage.of(flat(2, 1, PixelFormat.RG, PixelType.UNSIGNED_SHORT)).widen(shorts));

		byte[] halves = {1, 2, 3, 4, 5, 6};
		// One half float is 0x3C00, little endian.
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 0, 0x3C},
				RawImage.of(flat(1, 1, PixelFormat.RGB, PixelType.HALF_FLOAT)).widen(halves));

		byte[] single = {9, 8, 7, 6};
		assertArrayEquals(new byte[] {9, 8, 7, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0x80, 0x3F},
				RawImage.of(flat(1, 1, PixelFormat.RED, PixelType.FLOAT)).widen(single));
	}

	@Test
	void whatIsPastTheDeclaredSizeIsNotRead() {
		RawImage image = RawImage.of(flat(1, 1, PixelFormat.RG, PixelType.UNSIGNED_BYTE));

		assertArrayEquals(new byte[] {1, 2, 0, -1}, image.widen(new byte[] {1, 2, 3, 4, 5, 6, 7}));
	}

	@Test
	void aBlobShorterThanTheTextureIsRefusedNotPaddedWithZeroes() {
		RawImage image = RawImage.of(flat(2, 2, PixelFormat.RG, PixelType.UNSIGNED_SHORT));

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> image.widen(new byte[15]));

		assertEquals("A texture of 2x2 needs 16 bytes and this blob holds 15", e.getMessage());
		assertEquals(32, image.widen(new byte[16]).length);
	}

	@Test
	void fitsWhereTheDeviceAndTheBudgetAllow() {
		assertTrue(RawImage.of(flat(8192, 4096, PixelFormat.RG, PixelType.UNSIGNED_BYTE)).fits());
		assertFalse(RawImage.of(flat(8192, 4097, PixelFormat.RG, PixelType.UNSIGNED_BYTE)).fits());
		assertTrue(RawImage.of(flat(4096, 2048, PixelFormat.RGBA, PixelType.FLOAT)).fits());
		assertFalse(RawImage.of(flat(4096, 2049, PixelFormat.RGBA, PixelType.FLOAT)).fits());
		assertFalse(RawImage.of(flat(16385, 1, PixelFormat.RED, PixelType.UNSIGNED_BYTE)).fits());
	}

	/** Each texel of the widened image against a reading of the blob channel by channel and byte by byte. */
	@Test
	void widenedTexelsAreTheBlobChannelsThenNoughtsThenAnAlphaOfOneOnGeneratedBlobs() {
		Random random = new Random(5);
		List<PixelFormat> formats = List.of(PixelFormat.RED, PixelFormat.RG, PixelFormat.RGB, PixelFormat.RGBA);
		List<PixelType> types = List.of(PixelType.UNSIGNED_BYTE, PixelType.UNSIGNED_SHORT, PixelType.HALF_FLOAT,
				PixelType.FLOAT);
		byte[][] one = {{-1}, {-1, -1}, {0, 0x3C}, {0, 0, (byte) 0x80, 0x3F}};

		for (PixelFormat format : formats) {
			for (int typeIndex = 0; typeIndex < types.size(); typeIndex++) {
				PixelType type = types.get(typeIndex);
				for (int round = 0; round < 4; round++) {
					int width = 1 + random.nextInt(7);
					int height = 1 + random.nextInt(7);
					int components = format.components();
					int width1 = type.channelBytes();
					byte[] blob = new byte[width * height * components * width1 + random.nextInt(5)];
					random.nextBytes(blob);

					byte[] widened = RawImage.of(flat(width, height, format, type)).widen(blob);

					assertEquals(width * height * 4 * width1, widened.length);
					for (int texel = 0; texel < width * height; texel++) {
						for (int channel = 0; channel < 4; channel++) {
							for (int b = 0; b < width1; b++) {
								byte expected;
								if (channel < components) {
									expected = blob[(texel * components + channel) * width1 + b];
								} else if (channel == 3) {
									expected = one[typeIndex][b];
								} else {
									expected = 0;
								}

								assertEquals(expected, widened[(texel * 4 + channel) * width1 + b],
										format + " " + type + " texel " + texel + " channel " + channel);
							}
						}
					}
				}
			}
		}
	}
}
