package dev.vitrail.uniform;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Random;

/**
 * Noise image generation, bit for bit. Returns RGBA bytes, no graphics API named.
 * <p>
 * Adapted in August 2026 from {@code net.irisshaders.iris.targets.backed.NativeImageBackedNoiseTexture},
 * Iris commit b0ae41c. The recipe matters down to the loop order: a pack indexes this image with
 * coordinates it computed itself, so transposing it, or seeding the generator differently, gives an
 * image that still looks exactly like noise and is not the one the pack was tuned against. The
 * other noise texture in the Iris tree is dead code and does not produce the same image; it is not
 * the one to follow.
 * <p>
 * <strong>No observation in the game can prove this.</strong> Two different generators both produce
 * something that looks like noise. The only proof is a fingerprint frozen in the harness.
 * <p>
 * Modified: the pixels are written straight into a byte array in the order a texture upload wants
 * them, rather than through the game's image class. That class treats the colour as ARGB and stores
 * ABGR, which on a little endian machine puts red first, so the bytes here are laid out red, green,
 * blue, alpha and the value is decomposed accordingly.
 */
public final class NoiseTexture {

	/**
	 * The longest side an image of a pack may decode to. What a device will take: sixteen thousand
	 * three hundred and eighty four is {@code maxImageDimension2D} on all but a handful of cards.
	 */
	private static final int MAX_SIDE = 16384;

	/**
	 * And the most texels, which is the number that matters and the one nothing else says.
	 * <p>
	 * The ceiling on the file is on the bytes ON DISK and has no bearing on this: Body Camera ships
	 * a lookup table of fifty nine kilobytes that decodes to four thousand and ninety six square,
	 * sixty four megabytes, and a flat image of thirty thousand square compresses smaller still and
	 * asks for three and a half gigabytes. This is twice what the corpus needs and a hundredth of
	 * what such a file would take, and the two allocations it bounds are both made before a pack has
	 * drawn anything: past it the client would die on the load rather than lose one texture.
	 */
	private static final long MAX_TEXELS = 32L * 1024 * 1024;

	private NoiseTexture() {
	}

	/**
	 * A decoded pack image, in the same byte order {@link #rgba(int)} writes.
	 * <p>
	 * The payload is handed on rather than copied. It is megabytes of texels on its way to the GPU,
	 * read by the upload and by whoever rewraps it, written by nobody, and a record that copied it
	 * on every accessor would pay for every pack load to protect a buffer nothing modifies.
	 */
	@SuppressWarnings("ArrayRecordComponent")
	public record Image(int width, int height, byte[] rgba) {
	}

	/**
	 * Decodes a pack's own noise image, {@code texture.noise}, or any other file it ships.
	 * Four packs of the corpus ship a noise image, and theirs is nothing like the generated field:
	 * BSL's is blurred smooth, and water octaves fed the generated white noise instead crumple into
	 * facets.
	 * <p>
	 * Decoded with ImageIO rather than the game's image class, for the same reason the generator
	 * writes raw bytes: this package names no graphics API, which is what lets the harness measure
	 * it without starting the game. ImageIO is the JDK's and works headless. The samples then come
	 * off the raster as the file wrote them, expanded to four channels the way stb expands a PNG;
	 * see {@link #pixels}, where the reading is what a grey image turns on.
	 * <p>
	 * The header is read before the pixels are, and that is the whole point of going through a
	 * reader rather than through {@code ImageIO.read}: the size is in the first bytes of the file
	 * and the memory is asked for by the call that follows, so a refusal is only possible in
	 * between. A pack is downloaded content and its images are read while the client is still
	 * starting up.
	 */
	public static Image decode(byte[] png) throws IOException {
		try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				throw new IOException("not an image ImageIO recognises");
			}

			ImageReader reader = readers.next();
			try {
				reader.setInput(stream);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_TEXELS) {
					throw new IOException("the header says " + width + "x" + height + ", past the "
							+ MAX_SIDE + " a side and the " + MAX_TEXELS
							+ " texels an image of a pack is allowed");
				}

				return pixels(reader.read(0), width, height);
			} finally {
				reader.dispose();
			}
		}
	}

	/**
	 * Four channels a texel, expanded the way stb expands a PNG: one band gives grey, grey, grey and
	 * opaque, two give grey, grey, grey and the alpha, three give the colour opaque, four are taken
	 * as they are.
	 * <p>
	 * The samples are read off the raster, which is the whole point rather than a detail.
	 * {@code getRGB} goes through the colour model, and on a grey image it converts the grey colour
	 * space to sRGB and hands back every byte lifted: MakeUp UltraFast's cloud field comes out with a
	 * mean of 0.728 where the file holds 0.501, and the pack thresholds that field at 0.55, so a few
	 * filaments become a full white cover. Iris decodes with stb, which replicates the byte over red,
	 * green and blue and converts nothing.
	 * <p>
	 * A sixteen bit sample is read as its top byte, which is what stb does when it is asked for eight
	 * bit output.
	 */
	private static Image pixels(BufferedImage image, int width, int height) {
		byte[] pixels = new byte[width * height * 4];
		if (bands(image)) {
			raster(image, width, height, pixels);
		} else {
			converted(image, width, height, pixels);
		}

		return new Image(width, height, pixels);
	}

	/** Whether the bands of the image mean a channel each, which is what lets them be read raw. */
	private static boolean bands(BufferedImage image) {
		// An indexed palette is the first of the models whose bands say nothing on their own: the
		// sample is an index. Whole bytes are the other half of the question, a band of five or of
		// ten bits being a field of a packed word rather than a channel. Both go through getRGB,
		// which converts nothing there: a palette and a packed word are both already sRGB.
		//
		// A sample is a channel only in a grey or an RGB space with its alpha kept apart: a
		// premultiplied alpha or a CMYK band would be uploaded as the colour it is not. A PNG never
		// carries either, so this is about the other formats the reader accepts, which the
		// reference refuses outright; they take the colour model road rather than a raw one.
		if (image.getColorModel() instanceof IndexColorModel || image.isAlphaPremultiplied()) {
			return false;
		}

		int space = image.getColorModel().getColorSpace().getType();
		if (space != ColorSpace.TYPE_GRAY && space != ColorSpace.TYPE_RGB) {
			return false;
		}

		SampleModel samples = image.getSampleModel();
		if (samples.getNumBands() > 4) {
			return false;
		}

		for (int band = 0; band < samples.getNumBands(); band++) {
			if (samples.getSampleSize(band) != 8 && samples.getSampleSize(band) != 16) {
				return false;
			}
		}

		return true;
	}

	private static void raster(BufferedImage image, int width, int height, byte[] pixels) {
		Raster raster = image.getRaster();
		int bands = raster.getNumBands();
		int[] shift = new int[bands];
		for (int band = 0; band < bands; band++) {
			shift[band] = image.getSampleModel().getSampleSize(band) == 16 ? 8 : 0;
		}

		int minX = raster.getMinX();
		int minY = raster.getMinY();
		int[] row = new int[width * bands];
		for (int y = 0; y < height; y++) {
			raster.getPixels(minX, minY + y, width, 1, row);
			for (int x = 0; x < width; x++) {
				int at = x * bands;
				int red = sample(row, at, shift, 0);
				int green = bands < 3 ? red : sample(row, at, shift, 1);
				int blue = bands < 3 ? red : sample(row, at, shift, 2);
				int alpha = switch (bands) {
					case 2 -> sample(row, at, shift, 1);
					case 4 -> sample(row, at, shift, 3);
					default -> 255;
				};

				int offset = (y * width + x) * 4;
				pixels[offset] = (byte) red;
				pixels[offset + 1] = (byte) green;
				pixels[offset + 2] = (byte) blue;
				pixels[offset + 3] = (byte) alpha;
			}
		}
	}

	private static int sample(int[] row, int at, int[] shift, int band) {
		return (row[at + band] >> shift[band]) & 0xFF;
	}

	private static void converted(BufferedImage image, int width, int height, byte[] pixels) {
		int[] row = new int[width];
		for (int y = 0; y < height; y++) {
			image.getRGB(0, y, width, 1, row, 0, width);
			for (int x = 0; x < width; x++) {
				int argb = row[x];
				int offset = (y * width + x) * 4;
				pixels[offset] = (byte) (argb >> 16);
				pixels[offset + 1] = (byte) (argb >> 8);
				pixels[offset + 2] = (byte) argb;
				pixels[offset + 3] = (byte) (argb >> 24);
			}
		}
	}

	/**
	 * Generates the noise image the engine falls back on when the pack ships none.
	 *
	 * @param resolution the width and the height, from the pack's {@code noiseTextureResolution}
	 */
	public static byte[] rgba(int resolution) {
		byte[] pixels = new byte[resolution * resolution * 4];
		Random random = new Random(0);

		// x outside and y inside, which is Iris's order and therefore the packs' order.
		for (int x = 0; x < resolution; x++) {
			for (int y = 0; y < resolution; y++) {
				int colour = random.nextInt() | (255 << 24);
				int offset = (x + y * resolution) * 4;
				pixels[offset] = (byte) (colour >> 16);
				pixels[offset + 1] = (byte) (colour >> 8);
				pixels[offset + 2] = (byte) colour;
				pixels[offset + 3] = (byte) 255;
			}
		}

		return pixels;
	}
}
