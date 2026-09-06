package dev.vitrail.pack.texture;

import dev.vitrail.pack.model.PackTexture;
import dev.vitrail.pack.model.PixelFormat;
import dev.vitrail.pack.model.PixelType;

import java.util.Set;

/**
 * Where every texel of a volume ends up once the volume is laid out flat, decided once and read
 * by both sides.
 * <p>
 * A pack's {@code sampler3D} over a blob it ships is served by a 2D atlas of slices and a helper
 * that reads two of them and mixes: the blob is uploaded flat and nothing builds a 3D view over
 * it, the one volume the backend binds as such being the kind an {@code image} directive fills. Two readers therefore have
 * to agree texel for texel: the one that fills the atlas out of the pack's blob, and the one that
 * prints the arithmetic into the shader. They agree because they both come here. A layout written
 * twice would come out as noise on the screen, and a noise texture that is wrong looks exactly
 * like a noise texture that is right.
 * <p>
 * <strong>The gutter is the part that is easy to leave out and impossible to see afterwards.</strong>
 * Each slice is laid out with one texel of margin on all four sides, carrying what the hardware
 * would have read one texel past the edge of a real volume: for a volume that repeats, the
 * opposite edge, column -1 holding column {@code width - 1} and column {@code width} holding
 * column 0, the same in y and the four corners accordingly; for one that clamps, the edge itself
 * again. Without it, the hardware's bilinear tap at the edge of a tile reaches into the
 * NEIGHBOURING slice, which is a different z entirely. The picture that comes out still looks like
 * Worley noise. With the gutter, the tap is exactly the {@code REPEAT} or the {@code CLAMP} the
 * hardware would have done on a real volume.
 * <p>
 * The depth needs no gutter of its own: nothing interpolates between slices in hardware, the
 * helper reads two of them and mixes them itself, and it is the helper that repeats or clamps the
 * slice index.
 * <p>
 * The atlas keeps the blob's channel type and widens the texel to four channels, because four is
 * what the engine allocates for a texture of its own: a byte a channel stays a byte, a half float
 * stays a half float. A channel the blob has not got reads nought and a missing alpha reads one,
 * which is what a texture short of channels answers under GL.
 */
public final class VolumeAtlas {

	/** One texel on each side of every tile, holding the copy of what lies past the edge. */
	public static final int GUTTER = 1;

	/**
	 * What the atlas is allowed to come out as, which is not what the volume is allowed to be.
	 * <p>
	 * A blob is bounded by the ceiling on a pack file and its declaration is checked against its
	 * length, so a volume is at most a few million texels; the atlas is what those texels are laid
	 * out AS, and one long axis lays them out in a line. A megabyte declared as
	 * {@code 4096 1 2048} spreads to a hundred and eighty eight thousand texels across, which no
	 * device will allocate and which nothing in the file said. The sides are what a device takes and
	 * the total is what the memory is, counted in bytes because a texel is four to eight of them: a
	 * hundred and twenty eight mebibytes at the very most, and a megabyte for the noise volumes of
	 * the corpus.
	 */
	private static final int MAX_SIDE = 16384;

	private static final long MAX_BYTES = 128L * 1024 * 1024;

	/** Four channels a texel in the atlas whatever the blob holds, the width the engine allocates. */
	private static final int CHANNELS = 4;

	/** The alpha channel, the one a blob short of channels is answered one for rather than nought. */
	private static final int ALPHA = 3;

	/**
	 * The channel types this lays out: unsigned bytes and shorts, and half floats, the types the
	 * corpus's noise volumes and Photon's atmosphere table are made of. Everything else is refused
	 * until a pack ships it, and two of the refusals are not a matter of writing more: a single
	 * float a channel would be allocated in a format Vulkan does not promise to filter linearly,
	 * which is the whole of what the atlas asks the hardware to do, and an integer format is read
	 * through an integer sampler the helper is not written for.
	 */
	private static final Set<PixelType> TYPES = Set.of(PixelType.UNSIGNED_BYTE,
			PixelType.UNSIGNED_SHORT, PixelType.HALF_FLOAT);

	/** The channel orders laid out as they come: a swapped order would have to be swapped back. */
	private static final Set<PixelFormat> FORMATS = Set.of(PixelFormat.RED, PixelFormat.RG,
			PixelFormat.RGB, PixelFormat.RGBA);

	private final int width;
	private final int height;
	private final int depth;
	private final int tilesPerRow;
	private final int rows;
	private final PixelType type;
	private final int components;
	private final boolean clamp;

	private VolumeAtlas(int width, int height, int depth, PixelType type, int components,
			boolean clamp) {
		this.width = width;
		this.height = height;
		this.depth = depth;
		this.tilesPerRow = (int) Math.ceil(Math.sqrt(depth));
		this.rows = (depth + this.tilesPerRow - 1) / this.tilesPerRow;
		this.type = type;
		this.components = components;
		this.clamp = clamp;
	}

	/**
	 * The layout for that blob, addressed as the pack asked. The slices are laid out as square as
	 * they go, so a 64 cubed volume becomes eight tiles by eight of 66 by 66, an atlas of 528 by 528.
	 *
	 * @throws IllegalArgumentException if the blob is not one {@link #serves} says yes to, which the
	 *                                  caller has to have asked first
	 */
	public static VolumeAtlas of(PackTexture.Raw raw, boolean clamp) {
		if (!serves(raw)) {
			throw new IllegalArgumentException("A volume of " + raw.sizeX() + "x" + raw.sizeY() + "x"
					+ raw.sizeZ() + " in " + raw.pixelFormat() + " " + raw.pixelType()
					+ " is not one this lays out flat");
		}

		return new VolumeAtlas(raw.sizeX(), raw.sizeY(), raw.sizeZ(), raw.pixelType(),
				raw.pixelFormat().components(), clamp);
	}

	/** Whether a blob of that description is one this lays out flat: three dimensional, of a channel type it carries. */
	public static boolean serves(PackTexture.Raw raw) {
		return raw.shape() == PackTexture.Shape.TEXTURE_3D
				&& raw.sizeX() > 0 && raw.sizeY() > 0 && raw.sizeZ() > 0
				&& TYPES.contains(raw.pixelType()) && FORMATS.contains(raw.pixelFormat());
	}

	/**
	 * Whether this layout is one a device could hold and this engine is willing to spend. Asked
	 * before a volume is served, because a layout that does not fit is one nothing can draw with.
	 */
	public boolean fits() {
		return atlasWidth() <= MAX_SIDE && atlasHeight() <= MAX_SIDE
				&& (long) atlasWidth() * atlasHeight() * texelBytes() <= MAX_BYTES;
	}

	/**
	 * Where a texel sits in the blob the pack ships.
	 * <p>
	 * {@code x + y * width + z * width * height}, which is the order {@code glTexImage3D} consumes
	 * and therefore the order the file was written in. Nothing else about the file says so.
	 */
	public int index(int x, int y, int z) {
		return x + y * this.width + z * this.width * this.height;
	}

	/**
	 * Which texel of the atlas a texel of the volume lands on, counting rows of the atlas.
	 * <p>
	 * The coordinates may fall one outside the slice on either side, which is the gutter, and the
	 * answer is then the gutter texel rather than the one it copies: what goes in it is
	 * {@link #spread}'s business.
	 */
	public int texel(int x, int y, int z) {
		int column = (z % this.tilesPerRow) * tileStride() + GUTTER + x;
		int row = (z / this.tilesPerRow) * tileHeight() + GUTTER + y;

		return row * atlasWidth() + column;
	}

	/**
	 * The atlas, four channels of the blob's own type a texel, filled from the blob.
	 * <p>
	 * The blob's channels go in as they are, byte for byte, so a half float stays the half float
	 * the file holds. A channel the blob has not got stays at nought and a missing alpha is
	 * written as one, the way GL answers a read past a texture's channels; every lookup of the
	 * corpus reads {@code .r} or {@code .rgb} and nothing reads an alpha the pack did not write.
	 *
	 * @throws IllegalArgumentException if the blob is shorter than the volume, which is the one
	 *                                  thing that would be filled in silently with zeroes
	 */
	public byte[] spread(byte[] blob) {
		int in = this.components * channelBytes();
		long texels = (long) this.width * this.height * this.depth;
		if (blob.length < texels * in) {
			throw new IllegalArgumentException("A volume of " + this.width + "x" + this.height + "x"
					+ this.depth + " needs " + texels * in + " bytes and this blob holds "
					+ blob.length);
		}

		int out = texelBytes();
		byte[] one = one();
		byte[] atlas = new byte[atlasWidth() * atlasHeight() * out];
		for (int z = 0; z < this.depth; z++) {
			// From -1 to width inclusive: the two extra columns and rows are the gutter, and they
			// are filled by the same walk rather than patched on afterwards.
			for (int v = -GUTTER; v < this.height + GUTTER; v++) {
				for (int u = -GUTTER; u < this.width + GUTTER; u++) {
					int x = past(u, this.width);
					int y = past(v, this.height);
					int to = texel(u, v, z) * out;
					System.arraycopy(blob, index(x, y, z) * in, atlas, to, in);
					if (this.components <= ALPHA) {
						System.arraycopy(one, 0, atlas, to + ALPHA * channelBytes(), one.length);
					}
				}
			}
		}

		return atlas;
	}

	/** The texel a coordinate one past the edge reads: the far edge when repeating, the edge itself when clamping. */
	private int past(int at, int size) {
		return this.clamp ? Math.clamp(at, 0, size - 1) : Math.floorMod(at, size);
	}

	/** One, in the channel's own type and in the byte order the blob is in, which is the machine's. */
	private byte[] one() {
		return switch (this.type) {
			case UNSIGNED_BYTE -> new byte[] {(byte) 0xFF};
			case UNSIGNED_SHORT -> new byte[] {(byte) 0xFF, (byte) 0xFF};
			case HALF_FLOAT -> new byte[] {0x00, 0x3C};
			default -> throw new IllegalStateException(this.type + " is not a type this lays out");
		};
	}

	/** How far apart two tiles start across, gutter included: 66 for a 64 wide volume. */
	public int tileStride() {
		return this.width + 2 * GUTTER;
	}

	/** The same down the atlas, which is not the same number for a volume that is not square. */
	public int tileHeight() {
		return this.height + 2 * GUTTER;
	}

	public int tilesPerRow() {
		return this.tilesPerRow;
	}

	public int atlasWidth() {
		return this.tilesPerRow * tileStride();
	}

	public int atlasHeight() {
		return this.rows * tileHeight();
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	public int depth() {
		return this.depth;
	}

	/** The blob's channel type, which the atlas keeps. */
	public PixelType type() {
		return this.type;
	}

	/** How many channels the blob holds a texel, before the atlas widens it to four. */
	public int components() {
		return this.components;
	}

	/** Whether the pack asked the volume to clamp, which the helper and the gutter both honour. */
	public boolean clamp() {
		return this.clamp;
	}

	/** Bytes in one channel of the atlas, the blob's own. */
	public int channelBytes() {
		return this.type.channelBytes();
	}

	/** Bytes in one texel of the atlas: four channels of the blob's type. */
	public int texelBytes() {
		return CHANNELS * channelBytes();
	}
}
