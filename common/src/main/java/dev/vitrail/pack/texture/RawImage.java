package dev.vitrail.pack.texture;

import dev.vitrail.pack.model.PackTexture;
import dev.vitrail.pack.model.PixelType;

/**
 * A blob the pack ships as a plain two dimensional texture, laid out as it will be uploaded.
 * <p>
 * This is the ordinary case and the atlas beside it is the exotic one: a pack writes
 * {@code customTexture.areatex=tex/smaa_area.bin TEXTURE_2D RG8 160 560 RG UNSIGNED_BYTE} and
 * means a texture of exactly that size, read at exactly those coordinates. Nothing is tiled and
 * there is no gutter, because there is no neighbouring slice to bleed into: what a read past the
 * edge answers is the sampler's own wrap, which is what a real two dimensional texture does.
 * <p>
 * All that is left is the widening {@link RawTexels} describes, four channels of the blob's own
 * type. RenderPearl's two SMAA tables are the measured case: an {@code RG8} area table and an
 * {@code R8} search table, both read as {@code .rg} and {@code .r}, and until they were served the
 * compute that samples them found no image under the name and was dropped whole.
 * <p>
 * A shape that is not two dimensional stays out. A {@code TEXTURE_1D} would be a
 * {@code sampler1D} and a {@code TEXTURE_RECTANGLE} a {@code sampler2DRect} indexed in texels
 * rather than in zero to one, and neither is a thing this backend binds or this engine rewrites;
 * no pack of the corpus declares either.
 */
public final class RawImage {

	private final int width;
	private final int height;
	private final PixelType type;
	private final int components;

	private RawImage(int width, int height, PixelType type, int components) {
		this.width = width;
		this.height = height;
		this.type = type;
		this.components = components;
	}

	/**
	 * The layout for that blob.
	 *
	 * @throws IllegalArgumentException if the blob is not one {@link #serves} says yes to, which the
	 *                                  caller has to have asked first
	 */
	public static RawImage of(PackTexture.Raw raw) {
		if (!serves(raw)) {
			throw new IllegalArgumentException("A texture of " + raw.sizeX() + "x" + raw.sizeY()
					+ " in " + raw.pixelFormat() + " " + raw.pixelType()
					+ " is not one this uploads flat");
		}

		return new RawImage(raw.sizeX(), raw.sizeY(), raw.pixelType(),
				raw.pixelFormat().components());
	}

	/** Whether a blob of that description is one this uploads: two dimensional, of a channel type it carries. */
	public static boolean serves(PackTexture.Raw raw) {
		return raw.shape() == PackTexture.Shape.TEXTURE_2D
				&& raw.sizeX() > 0 && raw.sizeY() > 0
				&& RawTexels.holds(raw.pixelType(), raw.pixelFormat());
	}

	/** Whether this is one a device could hold and this engine is willing to spend. */
	public boolean fits() {
		return RawTexels.fits(this.width, this.height, texelBytes());
	}

	/**
	 * The texels, four channels of the blob's own type each, filled from the blob in the order the
	 * file was written in, which is the order {@code glTexImage2D} consumes.
	 *
	 * @throws IllegalArgumentException if the blob is shorter than the texture, which is the one
	 *                                  thing that would be filled in silently with zeroes
	 */
	public byte[] widen(byte[] blob) {
		int in = this.components * channelBytes();
		int texels = this.width * this.height;
		if (blob.length < (long) texels * in) {
			throw new IllegalArgumentException("A texture of " + this.width + "x" + this.height
					+ " needs " + (long) texels * in + " bytes and this blob holds " + blob.length);
		}

		int out = texelBytes();
		byte[] one = RawTexels.one(this.type);
		byte[] image = new byte[texels * out];
		for (int texel = 0; texel < texels; texel++) {
			int to = texel * out;
			System.arraycopy(blob, texel * in, image, to, in);
			if (this.components <= RawTexels.ALPHA) {
				System.arraycopy(one, 0, image, to + RawTexels.ALPHA * channelBytes(), one.length);
			}
		}

		return image;
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	/** The blob's channel type, which the upload keeps. */
	public PixelType type() {
		return this.type;
	}

	/** How many channels the blob holds a texel, before it is widened to four. */
	public int components() {
		return this.components;
	}

	/** Bytes in one channel, the blob's own. */
	public int channelBytes() {
		return this.type.channelBytes();
	}

	/** Bytes in one texel once uploaded: four channels of the blob's type. */
	public int texelBytes() {
		return RawTexels.CHANNELS * channelBytes();
	}
}
