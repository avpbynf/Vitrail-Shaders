package dev.vitrail.pack.texture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vitrail.pack.model.PackTexture;
import dev.vitrail.pack.model.PixelFormat;
import dev.vitrail.pack.model.PixelType;
import dev.vitrail.pack.model.TargetFormat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Holds the two conversions that lay a blob out for upload, {@link RawImage#widen} and
 * {@link VolumeAtlas#spread}, to a second reading of the same rule: one texel at a time, the way
 * they were first written and kept here as they were.
 * <p>
 * Compared byte for byte over every channel type and channel count the layouts serve, both
 * addressings of a volume, sizes down to a single texel, blobs longer than they need to be, and the
 * refusal of one that is shorter, on a fixed seed.
 */
class TextureConversionEquivalenceTest {

	private static final List<PixelFormat> FORMATS = List.of(PixelFormat.RED, PixelFormat.RG, PixelFormat.RGB,
			PixelFormat.RGBA);

	private static final List<PixelType> TYPES = List.of(PixelType.UNSIGNED_BYTE, PixelType.UNSIGNED_SHORT,
			PixelType.HALF_FLOAT, PixelType.FLOAT);

	// ---- the reference conversions -----------------------------------------------------------

	private static byte[] referenceWiden(RawImage image, byte[] blob) {
		int in = image.components() * image.channelBytes();
		int texels = image.width() * image.height();
		if (blob.length < (long) texels * in) {
			throw new IllegalArgumentException("A texture of " + image.width() + "x" + image.height()
					+ " needs " + (long) texels * in + " bytes and this blob holds " + blob.length);
		}

		int out = image.texelBytes();
		byte[] one = RawTexels.one(image.type());
		byte[] result = new byte[texels * out];
		for (int texel = 0; texel < texels; texel++) {
			int to = texel * out;
			System.arraycopy(blob, texel * in, result, to, in);
			if (image.components() <= RawTexels.ALPHA) {
				System.arraycopy(one, 0, result, to + RawTexels.ALPHA * image.channelBytes(), one.length);
			}
		}

		return result;
	}

	private static int referencePast(VolumeAtlas atlas, int at, int size) {
		return atlas.clamp() ? Math.clamp(at, 0, size - 1) : Math.floorMod(at, size);
	}

	private static byte[] referenceSpread(VolumeAtlas atlas, byte[] blob) {
		int in = atlas.components() * atlas.channelBytes();
		long texels = (long) atlas.width() * atlas.height() * atlas.depth();
		if (blob.length < texels * in) {
			throw new IllegalArgumentException("A volume of " + atlas.width() + "x" + atlas.height() + "x"
					+ atlas.depth() + " needs " + texels * in + " bytes and this blob holds " + blob.length);
		}

		int out = atlas.texelBytes();
		byte[] one = RawTexels.one(atlas.type());
		byte[] result = new byte[atlas.atlasWidth() * atlas.atlasHeight() * out];
		for (int z = 0; z < atlas.depth(); z++) {
			for (int v = -VolumeAtlas.GUTTER; v < atlas.height() + VolumeAtlas.GUTTER; v++) {
				for (int u = -VolumeAtlas.GUTTER; u < atlas.width() + VolumeAtlas.GUTTER; u++) {
					int x = referencePast(atlas, u, atlas.width());
					int y = referencePast(atlas, v, atlas.height());
					int to = atlas.texel(u, v, z) * out;
					System.arraycopy(blob, atlas.index(x, y, z) * in, result, to, in);
					if (atlas.components() <= RawTexels.ALPHA) {
						System.arraycopy(one, 0, result, to + RawTexels.ALPHA * atlas.channelBytes(), one.length);
					}
				}
			}
		}

		return result;
	}

	// ---- the inputs --------------------------------------------------------------------------

	private static PackTexture.Raw flat(int x, int y, PixelFormat format, PixelType type) {
		return new PackTexture.Raw(PackTexture.Shape.TEXTURE_2D, TargetFormat.resolve("RGBA8"), x, y, 0, format, type);
	}

	private static PackTexture.Raw volume(int x, int y, int z, PixelFormat format, PixelType type) {
		return new PackTexture.Raw(PackTexture.Shape.TEXTURE_3D, TargetFormat.resolve("RGBA8"), x, y, z, format, type);
	}

	private static byte[] blob(Random random, int length) {
		byte[] blob = new byte[length];
		random.nextBytes(blob);

		return blob;
	}

	@Test
	void widenIsTheReferenceOnEveryTypeAndChannelCountAndRandomSizes() {
		Random random = new Random(1);
		for (PixelFormat format : FORMATS) {
			for (PixelType type : TYPES) {
				for (int round = 0; round < 25; round++) {
					int width = 1 + random.nextInt(round < 5 ? 3 : 41);
					int height = 1 + random.nextInt(round < 5 ? 3 : 41);
					RawImage image = RawImage.of(flat(width, height, format, type));
					int needed = width * height * format.components() * type.channelBytes();
					byte[] blob = blob(random, needed + (round % 3 == 0 ? random.nextInt(9) : 0));

					assertArrayEquals(referenceWiden(image, blob), image.widen(blob),
							format + " " + type + " " + width + "x" + height);
				}
			}
		}
	}

	@Test
	void widenIsTheReferenceOnALargeImage() {
		Random random = new Random(2);
		for (PixelFormat format : FORMATS) {
			RawImage image = RawImage.of(flat(1024, 640, format, PixelType.UNSIGNED_BYTE));
			byte[] blob = blob(random, 1024 * 640 * format.components());

			assertArrayEquals(referenceWiden(image, blob), image.widen(blob), format.toString());
		}
	}

	@Test
	void widenRefusesAShortBlobWithTheReferencesMessage() {
		for (PixelFormat format : FORMATS) {
			for (PixelType type : TYPES) {
				RawImage image = RawImage.of(flat(5, 3, format, type));
				byte[] blob = new byte[5 * 3 * format.components() * type.channelBytes() - 1];

				IllegalArgumentException expected = assertThrows(IllegalArgumentException.class,
						() -> referenceWiden(image, blob));
				IllegalArgumentException actual = assertThrows(IllegalArgumentException.class, () -> image.widen(blob));

				assertEquals(expected.getMessage(), actual.getMessage());
			}
		}
	}

	@Test
	void spreadIsTheReferenceOnEveryTypeAndChannelCountAndBothAddressings() {
		Random random = new Random(3);
		for (boolean clamp : new boolean[] {false, true}) {
			for (PixelFormat format : FORMATS) {
				for (PixelType type : TYPES) {
					for (int round = 0; round < 12; round++) {
						int width = 1 + random.nextInt(round < 4 ? 2 : 9);
						int height = 1 + random.nextInt(round < 4 ? 2 : 9);
						int depth = 1 + random.nextInt(round < 4 ? 2 : 13);
						VolumeAtlas atlas = VolumeAtlas.of(volume(width, height, depth, format, type), clamp);
						int needed = width * height * depth * format.components() * type.channelBytes();
						byte[] blob = blob(random, needed + (round % 3 == 0 ? random.nextInt(9) : 0));

						assertArrayEquals(referenceSpread(atlas, blob), atlas.spread(blob),
								(clamp ? "clamp " : "repeat ") + format + " " + type + " " + width + "x" + height + "x"
										+ depth);
					}
				}
			}
		}
	}

	@Test
	void spreadIsTheReferenceOnALargerVolume() {
		Random random = new Random(4);
		for (boolean clamp : new boolean[] {false, true}) {
			for (PixelFormat format : FORMATS) {
				VolumeAtlas atlas = VolumeAtlas.of(volume(37, 29, 31, format, PixelType.UNSIGNED_BYTE), clamp);
				byte[] blob = blob(random, 37 * 29 * 31 * format.components());

				assertArrayEquals(referenceSpread(atlas, blob), atlas.spread(blob), format.toString());
			}
		}
	}

	@Test
	void spreadRefusesAShortBlobWithTheReferencesMessage() {
		for (PixelFormat format : FORMATS) {
			for (PixelType type : TYPES) {
				VolumeAtlas atlas = VolumeAtlas.of(volume(3, 4, 5, format, type), false);
				byte[] blob = new byte[3 * 4 * 5 * format.components() * type.channelBytes() - 1];

				IllegalArgumentException expected = assertThrows(IllegalArgumentException.class,
						() -> referenceSpread(atlas, blob));
				IllegalArgumentException actual = assertThrows(IllegalArgumentException.class, () -> atlas.spread(blob));

				assertEquals(expected.getMessage(), actual.getMessage());
			}
		}
	}
}
