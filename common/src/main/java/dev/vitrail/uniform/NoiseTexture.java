package dev.vitrail.uniform;

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

	private NoiseTexture() {
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
