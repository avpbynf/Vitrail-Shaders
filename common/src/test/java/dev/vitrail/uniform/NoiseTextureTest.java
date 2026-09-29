package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link NoiseTexture} to the image a pack was tuned against.
 * <p>
 * The generated field cannot be told from another noise field by looking, and neither can its
 * transpose, so what is compared here is bytes: against a generator written out again in this file
 * from the definition of {@code java.util.Random} (a 48 bit linear congruential generator), in the
 * loop order the definition of the image needs, and against fingerprints of the result. The decoder
 * is held to the expansion rule stb applies, since the packs that ship their own noise are tuned
 * against that.
 */
class NoiseTextureTest {

	private static final long MASK = (1L << 48) - 1;

	/**
	 * The image as its definition reads: the draws come from a generator seeded with nought, the
	 * outer loop is x and the inner is y, and the draw for (x, y) lands at pixel {@code x + y * n}.
	 * Written with the generator spelled out, so it shares no code with the class under test.
	 * With {@code transposed} the loops swap: the image a wrong port would produce.
	 */
	private static byte[] reference(int resolution, boolean transposed) {
		byte[] pixels = new byte[resolution * resolution * 4];
		long seed = 0x5DEECE66DL & MASK;

		for (int outer = 0; outer < resolution; outer++) {
			for (int inner = 0; inner < resolution; inner++) {
				seed = (seed * 0x5DEECE66DL + 0xBL) & MASK;
				int draw = (int) (seed >>> 16);

				int x = transposed ? inner : outer;
				int y = transposed ? outer : inner;
				int at = (x + y * resolution) * 4;
				pixels[at] = (byte) (draw >> 16);
				pixels[at + 1] = (byte) (draw >> 8);
				pixels[at + 2] = (byte) draw;
				pixels[at + 3] = (byte) 255;
			}
		}

		return pixels;
	}

	private static long crc(byte[] bytes) {
		CRC32 crc = new CRC32();
		crc.update(bytes);

		return crc.getValue();
	}

	private static int unsigned(byte b) {
		return b & 255;
	}

	@Test
	void theFirstDrawsOfTheGeneratorAreTheKnownOnes() {
		// java.util.Random(0) draws -1155484576, -723955400, 1033096058, -1690734402, -1557280266.
		// The colour is the draw with alpha forced to opaque, and the bytes are red, green, blue, alpha.
		byte[] pixels = NoiseTexture.rgba(4);

		assertEquals(0x20, unsigned(pixels[0]), "red of 0xFF20B460");
		assertEquals(0xB4, unsigned(pixels[1]));
		assertEquals(0x60, unsigned(pixels[2]));
		assertEquals(0xFF, unsigned(pixels[3]));
	}

	@Test
	void theSecondDrawIsBelowTheFirstAndNotBesideItBecauseXIsTheOuterLoop() {
		byte[] pixels = NoiseTexture.rgba(4);

		// Draw 1 is (x 0, y 1): a row down, four pixels on, sixteen bytes. 0xFFD95138.
		assertEquals(0xD9, unsigned(pixels[16]));
		assertEquals(0x51, unsigned(pixels[17]));
		assertEquals(0x38, unsigned(pixels[18]));
		// And draw 2, 0xFF93CB7A, two rows down.
		assertEquals(0x93, unsigned(pixels[32]));
		assertEquals(0xCB, unsigned(pixels[33]));
		assertEquals(0x7A, unsigned(pixels[34]));
		// Draw 4 starts the next column: (x 1, y 0), 0xFF2DC9F6, the pixel beside the first.
		assertEquals(0x2D, unsigned(pixels[4]));
		assertEquals(0xC9, unsigned(pixels[5]));
		assertEquals(0xF6, unsigned(pixels[6]));
	}

	@Test
	void matchesTheGeneratorWrittenOutAgainAtSeveralSizes() {
		for (int resolution : new int[] { 0, 1, 2, 3, 4, 7, 16, 64, 100, 256 }) {
			assertArrayEquals(reference(resolution, false), NoiseTexture.rgba(resolution), "resolution " + resolution);
		}
	}

	@Test
	void isNotTheTransposedImageWhichLooksExactlyAsMuchLikeNoise() {
		for (int resolution : new int[] { 2, 16, 256 }) {
			assertFalse(Arrays.equals(reference(resolution, true), NoiseTexture.rgba(resolution)),
					"resolution " + resolution);
		}
	}

	@Test
	void hasTheFingerprintsFrozenFromTheIndependentGenerator() {
		assertEquals(0xBDEA2C4L, crc(NoiseTexture.rgba(1)));
		assertEquals(0xCDCD56CAL, crc(NoiseTexture.rgba(4)));
		assertEquals(0x34CDEE85L, crc(NoiseTexture.rgba(64)));
		assertEquals(0xB686AC12L, crc(NoiseTexture.rgba(256)));

		// And the transposes, which differ: a swap of the two loops cannot pass any of these.
		assertEquals(0x8C490D9DL, crc(reference(4, true)));
		assertEquals(0x74369693L, crc(reference(256, true)));
	}

	@Test
	void isFourBytesAPixelAndAlwaysOpaque() {
		byte[] pixels = NoiseTexture.rgba(37);

		assertEquals(37 * 37 * 4, pixels.length);
		for (int at = 3; at < pixels.length; at += 4) {
			assertEquals((byte) 255, pixels[at]);
		}
	}

	@Test
	void givesTheSameImageEveryTimeAndNeverSharesItsArray() {
		byte[] first = NoiseTexture.rgba(32);
		byte[] second = NoiseTexture.rgba(32);

		assertArrayEquals(first, second);
		first[0] = (byte) ~first[0];
		assertFalse(Arrays.equals(first, second), "a caller that edits its copy does not edit anybody's");
		assertArrayEquals(reference(32, false), NoiseTexture.rgba(32));
	}

	@Test
	void aSmallerImageIsNotAPrefixOfALargerOneBecauseTheRowsAreTheWidth() {
		byte[] small = NoiseTexture.rgba(4);
		byte[] large = NoiseTexture.rgba(8);

		// The first column is the first draws either way; from the second row down they part.
		assertEquals(unsigned(small[0]), unsigned(large[0]));
		assertFalse(Arrays.equals(Arrays.copyOf(small, 16), Arrays.copyOf(large, 16)));
	}

	// decode

	private static byte[] png(BufferedImage image) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, "png", out));

		return out.toByteArray();
	}

	@Test
	void expandsAGreyImageToGreyGreyGreyOpaqueWithoutTheColourConversionGetRGBApplies() throws IOException {
		// getRGB on a grey image converts the grey colour space to sRGB and lifts every byte: a file
		// holding 128 comes out at 187. The samples must come off the raster as the file wrote them.
		BufferedImage grey = new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_GRAY);
		int[] values = { 0, 64, 128, 255 };
		for (int i = 0; i < 4; i++) {
			grey.getRaster().setSample(i % 2, i / 2, 0, values[i]);
		}

		NoiseTexture.Image image = NoiseTexture.decode(png(grey));

		assertEquals(2, image.width());
		assertEquals(2, image.height());
		for (int i = 0; i < 4; i++) {
			int v = values[i];
			assertEquals(v, unsigned(image.rgba()[i * 4]), "red " + i);
			assertEquals(v, unsigned(image.rgba()[i * 4 + 1]), "green " + i);
			assertEquals(v, unsigned(image.rgba()[i * 4 + 2]), "blue " + i);
			assertEquals(255, unsigned(image.rgba()[i * 4 + 3]), "alpha " + i);
		}
	}

	@Test
	void expandsAGreyImageWithAlphaToGreyGreyGreyAndTheAlpha() throws IOException {
		ComponentColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), true,
				false, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
		WritableRaster raster = model.createCompatibleWritableRaster(2, 1);
		raster.setSample(0, 0, 0, 200);
		raster.setSample(0, 0, 1, 30);
		raster.setSample(1, 0, 0, 10);
		raster.setSample(1, 0, 1, 250);

		NoiseTexture.Image image = NoiseTexture.decode(png(new BufferedImage(model, raster, false, null)));

		assertArrayEquals(new byte[] { (byte) 200, (byte) 200, (byte) 200, 30, 10, 10, 10, (byte) 250 },
				image.rgba());
	}

	@Test
	void readsAnRgbImageAsColourAndOpaque() throws IOException {
		BufferedImage rgb = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
		rgb.setRGB(0, 0, 0x102030);
		rgb.setRGB(1, 0, 0xFFC080);

		NoiseTexture.Image image = NoiseTexture.decode(png(rgb));

		assertArrayEquals(
				new byte[] { 0x10, 0x20, 0x30, (byte) 255, (byte) 0xFF, (byte) 0xC0, (byte) 0x80, (byte) 255 },
				image.rgba());
	}

	@Test
	void readsAnRgbaImageAsItIsWithTheAlphaLast() throws IOException {
		BufferedImage rgba = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
		rgba.setRGB(0, 0, 0x40102030);
		rgba.setRGB(1, 0, 0x00FFC080);

		NoiseTexture.Image image = NoiseTexture.decode(png(rgba));

		assertArrayEquals(new byte[] { 0x10, 0x20, 0x30, 0x40, (byte) 0xFF, (byte) 0xC0, (byte) 0x80, 0x00 },
				image.rgba());
	}

	@Test
	void readsASixteenBitSampleAsItsTopByte() throws IOException {
		BufferedImage wide = new BufferedImage(2, 1, BufferedImage.TYPE_USHORT_GRAY);
		wide.getRaster().setSample(0, 0, 0, 0xABCD);
		wide.getRaster().setSample(1, 0, 0, 0x00FF);

		NoiseTexture.Image image = NoiseTexture.decode(png(wide));

		assertArrayEquals(
				new byte[] { (byte) 0xAB, (byte) 0xAB, (byte) 0xAB, (byte) 255, 0x00, 0x00, 0x00, (byte) 255 },
				image.rgba());
	}

	@Test
	void readsAPaletteThroughItsColoursBecauseAnIndexMeansNothingOnItsOwn() throws IOException {
		byte[] red = { 0, (byte) 200 };
		byte[] green = { 0, 100 };
		byte[] blue = { 0, 50 };
		BufferedImage indexed = new BufferedImage(2, 1, BufferedImage.TYPE_BYTE_INDEXED,
				new IndexColorModel(8, 2, red, green, blue));
		indexed.getRaster().setSample(0, 0, 0, 1);
		indexed.getRaster().setSample(1, 0, 0, 0);

		NoiseTexture.Image image = NoiseTexture.decode(png(indexed));

		assertArrayEquals(new byte[] { (byte) 200, 100, 50, (byte) 255, 0, 0, 0, (byte) 255 }, image.rgba());
	}

	@Test
	void keepsRowsInOrderTopToBottom() throws IOException {
		BufferedImage rgb = new BufferedImage(1, 3, BufferedImage.TYPE_INT_RGB);
		rgb.setRGB(0, 0, 0x010000);
		rgb.setRGB(0, 1, 0x020000);
		rgb.setRGB(0, 2, 0x030000);

		byte[] pixels = NoiseTexture.decode(png(rgb)).rgba();

		assertEquals(1, unsigned(pixels[0]));
		assertEquals(2, unsigned(pixels[4]));
		assertEquals(3, unsigned(pixels[8]));
	}

	@Test
	void refusesBytesNoReaderRecognises() {
		IOException refused = assertThrows(IOException.class, () -> NoiseTexture.decode(new byte[] { 1, 2, 3, 4 }));

		assertEquals("not an image ImageIO recognises", refused.getMessage());
		assertFalse(refused instanceof NoiseTexture.TooLarge);
	}

	@Test
	void refusesAnImageWhoseHeaderAsksForMoreThanItWillAllocateBeforeReadingAnyPixel() throws IOException {
		byte[] wide = withSize(png(new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_GRAY)), 20_000, 4);
		byte[] tall = withSize(png(new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_GRAY)), 4, 16_385);
		// Each side is allowed and the product is not: 8192 * 8192 is 64 Mi texels against 32 Mi.
		byte[] many = withSize(png(new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_GRAY)), 8192, 8192);

		for (byte[] bytes : new byte[][] { wide, tall, many }) {
			NoiseTexture.TooLarge refused = assertThrows(NoiseTexture.TooLarge.class, () -> NoiseTexture.decode(bytes));

			assertTrue(refused.getMessage().contains("texels an image of a pack is allowed"), refused.getMessage());
		}
	}

	/** The PNG with the width and height its header states replaced; the pixels are never read. */
	private static byte[] withSize(byte[] png, int width, int height) {
		byte[] patched = png.clone();
		// Signature (8), length (4), 'IHDR' (4), then width and height, big endian.
		for (int i = 0; i < 4; i++) {
			patched[16 + i] = (byte) (width >>> (24 - 8 * i));
			patched[20 + i] = (byte) (height >>> (24 - 8 * i));
		}

		return patched;
	}
}
