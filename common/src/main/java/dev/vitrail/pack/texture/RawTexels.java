package dev.vitrail.pack.texture;

import dev.vitrail.pack.model.PixelFormat;
import dev.vitrail.pack.model.PixelType;

import java.util.Set;

/**
 * What a texel of a blob the pack ships can be made of, and what it becomes once uploaded.
 * <p>
 * A blob is a file with no header: nothing in it says what it holds, and the declaration beside
 * it is the whole of the description. This says which of those descriptions the engine can turn
 * into a texture, and it is asked before anything reads a byte, by the volume that is laid out
 * flat and by the plain image alike.
 * <p>
 * Whatever the blob holds, what goes up is four channels of the blob's own type: four is what
 * the engine allocates for a texture of its own, and a byte a channel stays a byte while a half
 * float stays a half float. A channel the blob has not got reads nought and a missing alpha
 * reads one, which is what a texture short of channels answers under GL.
 */
public final class RawTexels {

	/** Four channels a texel whatever the blob holds, the width the engine allocates. */
	public static final int CHANNELS = 4;

	/** The alpha channel, the one a blob short of channels is answered one for rather than nought. */
	static final int ALPHA = 3;

	/**
	 * The channel types these lay out: unsigned bytes and shorts, and floats of both widths, the
	 * types the corpus's blobs are made of. An integer format stays out, and that refusal is not a
	 * matter of writing more: it is read through an integer sampler nothing here is written for.
	 * <p>
	 * A single float a channel goes up as the pack declared it, which is what Iris uploads and what
	 * GL filters for it. Vulkan only PERMITS a device to filter a thirty two bit float format
	 * linearly where it requires it of a half, so on a device that does not the sampler falls back
	 * to nearest and says so, which costs the blend between two entries of a lookup table and
	 * nothing else. Laying such a blob out in halves instead would keep the filtering and round
	 * every value, and a table like that is read for its values.
	 */
	private static final Set<PixelType> TYPES = Set.of(PixelType.UNSIGNED_BYTE,
			PixelType.UNSIGNED_SHORT, PixelType.HALF_FLOAT, PixelType.FLOAT);

	/** The channel orders laid out as they come: a swapped order would have to be swapped back. */
	private static final Set<PixelFormat> FORMATS = Set.of(PixelFormat.RED, PixelFormat.RG,
			PixelFormat.RGB, PixelFormat.RGBA);

	/**
	 * What a blob is allowed to come out as, which is not what the declaration is allowed to say.
	 * <p>
	 * A blob is bounded by nothing but the file the pack ships and the declaration checked
	 * against its length, and THIS is what bounds it: nothing downstream reads a blob refused
	 * here. The sides are what a device takes and the total is what the memory is, counted in
	 * bytes because a texel is four to sixteen of them: a hundred and twenty eight mebibytes at
	 * the very most, which is a megabyte for the noise volumes of the corpus and thirty seven
	 * for iterationT's atmosphere table.
	 */
	private static final int MAX_SIDE = 16384;

	private static final long MAX_BYTES = 128L * 1024 * 1024;

	private RawTexels() {
	}

	/** Whether a texel of that description is one these lay out, whatever shape the blob is. */
	public static boolean holds(PixelType type, PixelFormat format) {
		return TYPES.contains(type) && FORMATS.contains(format);
	}

	/** Whether a texture of that shape is one a device could hold and this engine is willing to spend. */
	public static boolean fits(int width, int height, int texelBytes) {
		return width <= MAX_SIDE && height <= MAX_SIDE
				&& (long) width * height * texelBytes <= MAX_BYTES;
	}

	/** One, in the channel's own type and in the byte order the blob is in, which is the machine's. */
	static byte[] one(PixelType type) {
		return switch (type) {
			case UNSIGNED_BYTE -> new byte[] {(byte) 0xFF};
			case UNSIGNED_SHORT -> new byte[] {(byte) 0xFF, (byte) 0xFF};
			case HALF_FLOAT -> new byte[] {0x00, 0x3C};
			case FLOAT -> new byte[] {0x00, 0x00, (byte) 0x80, 0x3F};
			default -> throw new IllegalStateException(type + " is not a type these lay out");
		};
	}
}
