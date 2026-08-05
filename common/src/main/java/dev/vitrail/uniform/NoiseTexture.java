package dev.vitrail.uniform;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import java.awt.image.BufferedImage;
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

	/** A decoded pack image, in the same byte order {@link #rgba(int)} writes. */
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
	 * it without starting the game. ImageIO is the JDK's and works headless.
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

	private static Image pixels(BufferedImage image, int width, int height) {
		byte[] pixels = new byte[width * height * 4];
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

		return new Image(width, height, pixels);
	}

	/** @param resolution the width and the height, from the pack's {@code noiseTextureResolution} */
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
