package dev.vitrail.pack.texture;

/**
 * Where every texel of a volume ends up once the volume is laid out flat, decided once and read
 * by both sides.
 * <p>
 * This backend binds nothing but 2D and cube samplers, so a pack's {@code sampler3D} is served by
 * a 2D atlas of slices and a helper that reads two of them and mixes. Two readers therefore have
 * to agree texel for texel: the one that fills the atlas out of the pack's blob, and the one that
 * prints the arithmetic into the shader. They agree because they both come here. A layout written
 * twice would come out as noise on the screen, and a noise texture that is wrong looks exactly
 * like a noise texture that is right.
 * <p>
 * <strong>The gutter is the part that is easy to leave out and impossible to see afterwards.</strong>
 * Each slice is laid out with one texel of margin on all four sides, carrying the wrapped copy of
 * the opposite edge: column -1 holds column {@code width - 1}, column {@code width} holds column
 * 0, the same in y, and the four corners accordingly. Without it, the hardware's bilinear tap at
 * the edge of a tile reaches into the NEIGHBOURING slice, which is a different z entirely. The
 * picture that comes out still looks like Worley noise. With the gutter, the tap is exactly the
 * {@code REPEAT} the hardware would have done on a real volume.
 * <p>
 * The depth needs no gutter of its own: nothing interpolates between slices in hardware, the
 * helper reads two of them and mixes them itself.
 */
public final class VolumeAtlas {

	/** One texel on each side of every tile, holding the wrapped copy of the far edge. */
	public static final int GUTTER = 1;

	/**
	 * What the atlas is allowed to come out as, which is not what the volume is allowed to be.
	 * <p>
	 * A blob is bounded by the ceiling on a pack file and its declaration is checked against its
	 * length, so a volume is at most a few million texels; the atlas is what those texels are laid
	 * out AS, and one long axis lays them out in a line. A megabyte declared as
	 * {@code 4096 1 2048} spreads to a hundred and eighty eight thousand texels across, which no
	 * device will allocate and which nothing in the file said. The sides are what a device takes and
	 * the total is what the memory is: four bytes a texel, so this is half a gigabyte at the very
	 * most and a megabyte for the two volumes of the corpus.
	 */
	private static final int MAX_SIDE = 16384;

	private static final long MAX_TEXELS = 32L * 1024 * 1024;

	/** Four bytes a texel, because that is the format the engine already allocates. */
	private static final int CHANNELS = 4;

	private final int width;
	private final int height;
	private final int depth;
	private final int tilesPerRow;
	private final int rows;

	private VolumeAtlas(int width, int height, int depth, int tilesPerRow, int rows) {
		this.width = width;
		this.height = height;
		this.depth = depth;
		this.tilesPerRow = tilesPerRow;
		this.rows = rows;
	}

	/**
	 * The layout for a volume of that size. The slices are laid out as square as they go, so a
	 * 64 cubed volume becomes eight tiles by eight of 66 by 66, an atlas of 528 by 528.
	 */
	public static VolumeAtlas of(int width, int height, int depth) {
		if (width <= 0 || height <= 0 || depth <= 0) {
			throw new IllegalArgumentException("A volume of " + width + "x" + height + "x" + depth
					+ " has no texels to lay out");
		}

		int tilesPerRow = (int) Math.ceil(Math.sqrt(depth));

		return new VolumeAtlas(width, height, depth, tilesPerRow,
				(depth + tilesPerRow - 1) / tilesPerRow);
	}

	/**
	 * Whether this layout is one a device could hold and this engine is willing to spend. Asked
	 * before a volume is served, because a layout that does not fit is one nothing can draw with.
	 */
	public boolean fits() {
		return atlasWidth() <= MAX_SIDE && atlasHeight() <= MAX_SIDE
				&& (long) atlasWidth() * atlasHeight() <= MAX_TEXELS;
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
	 * answer is then the gutter texel rather than the wrapped one: what goes in it is
	 * {@link #spread}'s business.
	 */
	public int texel(int x, int y, int z) {
		int column = (z % this.tilesPerRow) * tileStride() + GUTTER + x;
		int row = (z / this.tilesPerRow) * tileHeight() + GUTTER + y;

		return row * atlasWidth() + column;
	}

	/**
	 * The atlas, RGBA8, filled from a single channel blob.
	 * <p>
	 * The value goes in red and the other three channels stay at nought. Every one of the corpus's
	 * six lookups reads {@code .r} and nothing else, so replicating it across the channels would
	 * cost three quarters of the upload to serve a read nobody makes.
	 *
	 * @throws IllegalArgumentException if the blob is shorter than the volume, which is the one
	 *                                  thing that would be filled in silently with zeroes
	 */
	public byte[] spread(byte[] blob) {
		int texels = this.width * this.height * this.depth;
		if (blob.length < texels) {
			throw new IllegalArgumentException("A volume of " + this.width + "x" + this.height + "x"
					+ this.depth + " needs " + texels + " bytes and this blob holds " + blob.length);
		}

		byte[] atlas = new byte[atlasWidth() * atlasHeight() * CHANNELS];
		for (int z = 0; z < this.depth; z++) {
			// From -1 to width inclusive: the two extra columns and rows are the gutter, and they
			// are filled by the same walk rather than patched on afterwards.
			for (int v = -GUTTER; v < this.height + GUTTER; v++) {
				for (int u = -GUTTER; u < this.width + GUTTER; u++) {
					int x = Math.floorMod(u, this.width);
					int y = Math.floorMod(v, this.height);
					atlas[texel(u, v, z) * CHANNELS] = blob[index(x, y, z)];
				}
			}
		}

		return atlas;
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
}
