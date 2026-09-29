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

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds where every texel of a volume ends up once the volume is laid out flat as an atlas of
 * slices with a gutter of one texel around each, and that filling the atlas and printing the
 * arithmetic into the shader agree.
 */
class VolumeAtlasTest {

	private static PackTexture.Raw volume(int x, int y, int z, PixelFormat format, PixelType type) {
		return new PackTexture.Raw(PackTexture.Shape.TEXTURE_3D, TargetFormat.resolve("RGBA8"), x, y, z, format,
				type);
	}

	private static VolumeAtlas atlas(int x, int y, int z, boolean clamp) {
		return VolumeAtlas.of(volume(x, y, z, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE), clamp);
	}

	/** The smallest number of tiles across whose square holds the depth, found by counting. */
	private static int smallestSquareSide(int depth) {
		int side = 1;
		while (side * side < depth) {
			side++;
		}

		return side;
	}

	// ---- the layout --------------------------------------------------------------------------

	@Test
	void aSixtyFourCubedVolumeIsEightTilesByEightOfSixtySixSquare() {
		VolumeAtlas atlas = atlas(64, 64, 64, false);

		assertEquals(8, atlas.tilesPerRow());
		assertEquals(66, atlas.tileStride());
		assertEquals(66, atlas.tileHeight());
		assertEquals(528, atlas.atlasWidth());
		assertEquals(528, atlas.atlasHeight());
		assertEquals(1, VolumeAtlas.GUTTER);
	}

	@Test
	void slicesAreLaidOutAsSquareAsTheyGoAndTheLastRowMayBeShort() {
		int[][] cases = {{1, 1, 1}, {2, 2, 1}, {3, 2, 2}, {4, 2, 2}, {5, 3, 2}, {7, 3, 3}, {9, 3, 3}, {10, 4, 3},
			{16, 4, 4}, {17, 5, 4}, {100, 10, 10}, {101, 11, 10}};

		for (int[] row : cases) {
			VolumeAtlas atlas = atlas(4, 2, row[0], false);

			assertEquals(row[1], atlas.tilesPerRow(), "tiles across for depth " + row[0]);
			assertEquals(row[2], atlas.atlasHeight() / atlas.tileHeight(), "rows for depth " + row[0]);
		}
	}

	@Test
	void tilesAcrossIsTheSmallestSquareSideForEveryDepthUpToFiveThousand() {
		for (int depth = 1; depth <= 5000; depth++) {
			VolumeAtlas atlas = atlas(1, 1, depth, false);
			int side = smallestSquareSide(depth);

			assertEquals(side, atlas.tilesPerRow(), "depth " + depth);
			int rows = atlas.atlasHeight() / atlas.tileHeight();
			assertEquals((depth + side - 1) / side, rows, "rows for depth " + depth);
			assertTrue(side * rows >= depth, "tiles for depth " + depth);
		}
	}

	@Test
	void aVolumeThatIsNotSquareHasATileStrideAndAHeightOfItsOwn() {
		VolumeAtlas atlas = atlas(4, 2, 5, false);

		assertEquals(6, atlas.tileStride());
		assertEquals(4, atlas.tileHeight());
		assertEquals(3, atlas.tilesPerRow());
		assertEquals(18, atlas.atlasWidth());
		assertEquals(8, atlas.atlasHeight());
	}

	@Test
	void theBlobIsAddressedXFastestThenYThenZ() {
		VolumeAtlas atlas = atlas(4, 2, 5, false);

		assertEquals(0, atlas.index(0, 0, 0));
		assertEquals(3, atlas.index(3, 0, 0));
		assertEquals(4, atlas.index(0, 1, 0));
		assertEquals(8, atlas.index(0, 0, 1));
		assertEquals(3 + 1 * 4 + 2 * 8, atlas.index(3, 1, 2));
		assertEquals(4 * 2 * 5 - 1, atlas.index(3, 1, 4));
	}

	@Test
	void aTexelSitsAtItsTileWithTheGutterAddedAndTheGutterOwnsTheOutside() {
		VolumeAtlas atlas = atlas(4, 2, 5, false);

		assertEquals(1 * 18 + 1, atlas.texel(0, 0, 0));
		// Slice 4 is the second tile of the second row: 6 texels across and 4 down from the corner.
		assertEquals((1 * 4 + 1 + 1) * 18 + (1 * 6 + 1 + 3), atlas.texel(3, 1, 4));
		assertEquals(0, atlas.texel(-1, -1, 0), "the corner of the gutter is the corner of the atlas");
		assertEquals((1 + 2) * 18 + (1 + 4), atlas.texel(4, 2, 0), "one past the far edge, in the gutter");
	}

	@Test
	void everyTexelOfEveryTileAndItsGutterRoundTripsThroughTheAtlas() {
		int[][] sizes = {{1, 1, 1}, {5, 3, 7}, {1, 9, 2}, {3, 1, 5}, {17, 13, 11}, {4, 4, 4}, {6, 2, 1}};

		for (int[] size : sizes) {
			VolumeAtlas atlas = atlas(size[0], size[1], size[2], false);
			Set<Integer> seen = new HashSet<>();

			for (int z = 0; z < size[2]; z++) {
				for (int y = -1; y <= size[1]; y++) {
					for (int x = -1; x <= size[0]; x++) {
						int texel = atlas.texel(x, y, z);
						int column = texel % atlas.atlasWidth();
						int row = texel / atlas.atlasWidth();

						// Read back with the tile arithmetic taken the other way round.
						int tileX = column / atlas.tileStride();
						int tileY = row / atlas.tileHeight();
						assertEquals(x, column % atlas.tileStride() - 1, "x of " + x + "," + y + "," + z);
						assertEquals(y, row % atlas.tileHeight() - 1, "y of " + x + "," + y + "," + z);
						assertEquals(z, tileY * atlas.tilesPerRow() + tileX, "z of " + x + "," + y + "," + z);
						assertTrue(seen.add(texel), "two texels share " + texel);
					}
				}
			}

			assertEquals((size[0] + 2) * (size[1] + 2) * size[2], seen.size());
		}
	}

	// ---- what fits ---------------------------------------------------------------------------

	@Test
	void servesAThreeDimensionalBlobOfAChannelTypeItCarries() {
		assertTrue(VolumeAtlas.serves(volume(4, 4, 4, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE)));
		assertTrue(VolumeAtlas.serves(volume(1, 1, 1, PixelFormat.RED, PixelType.FLOAT)));
		assertFalse(VolumeAtlas.serves(volume(0, 4, 4, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE)));
		assertFalse(VolumeAtlas.serves(volume(4, 0, 4, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE)));
		assertFalse(VolumeAtlas.serves(volume(4, 4, 0, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE)));
		assertFalse(VolumeAtlas.serves(volume(4, 4, 4, PixelFormat.BGR, PixelType.UNSIGNED_BYTE)));
		assertFalse(VolumeAtlas.serves(volume(4, 4, 4, PixelFormat.RGBA, PixelType.INT)));
		assertFalse(VolumeAtlas.serves(new PackTexture.Raw(PackTexture.Shape.TEXTURE_2D,
				TargetFormat.resolve("RGBA8"), 4, 4, 4, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE)));
	}

	@Test
	void aVolumeItDoesNotServeCannotBeLaidOutAndSaysWhy() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> VolumeAtlas.of(volume(4, 4, 0, PixelFormat.RGBA, PixelType.INT), false));

		assertEquals("A volume of 4x4x0 in RGBA INT is not one this lays out flat", e.getMessage());
	}

	@Test
	void theAtlasIsWhatIsMeasuredNotTheVolume() {
		// 4096 x 1 x 2048 is eight million texels and spreads to 188508 across.
		VolumeAtlas wide = VolumeAtlas.of(volume(4096, 1, 2048, PixelFormat.RED, PixelType.UNSIGNED_BYTE), false);

		assertEquals(46, wide.tilesPerRow());
		assertEquals(188_508, wide.atlasWidth());
		assertEquals(135, wide.atlasHeight());
		assertFalse(wide.fits());
	}

	@Test
	void theLargestVolumeOfBytesThatFitsSitsExactlyOnTheBudget() {
		// 254 + 2 is 256 across and 4096 slices are 64 tiles across: 16384 wide, the side limit.
		// Thirty texels and two of gutter make 32 rows of 64 tiles, 2048 high: 128 MiB at four bytes.
		VolumeAtlas exact = atlas(254, 30, 4096, false);
		assertEquals(16384, exact.atlasWidth());
		assertEquals(2048, exact.atlasHeight());
		assertTrue(exact.fits());

		assertFalse(atlas(254, 31, 4096, false).fits(), "one texel taller is over the budget");
		assertFalse(atlas(255, 30, 4096, false).fits(), "one texel wider is over the side limit");
	}

	@Test
	void theBudgetIsInBytesSoAWiderTexelFitsASmallerVolume() {
		VolumeAtlas bytes = VolumeAtlas.of(volume(150, 150, 150, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE), false);
		VolumeAtlas floats = VolumeAtlas.of(volume(150, 150, 150, PixelFormat.RGBA, PixelType.FLOAT), false);

		// 13 tiles across and 12 down of 152: 1976 by 1824 texels.
		assertEquals(1976, bytes.atlasWidth());
		assertEquals(1824, bytes.atlasHeight());
		assertTrue(bytes.fits());
		assertTrue(floats.fits());
		assertFalse(VolumeAtlas.of(volume(200, 200, 200, PixelFormat.RGBA, PixelType.FLOAT), false).fits());
		assertTrue(VolumeAtlas.of(volume(200, 200, 200, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE), false).fits());
	}

	@Test
	void theIndexAndTheTexelStayInsideAnIntForTheLargestVolumeThatFits() {
		VolumeAtlas atlas = atlas(254, 30, 4096, false);

		// The last texel of the blob, and the last gutter texel of the last tile, which is the last
		// texel of the atlas: the tile at column 63 of row 63, 255 across and 31 down inside it.
		assertEquals(254 * 30 * 4096 - 1, atlas.index(253, 29, 4095));
		assertEquals((63 * 32 + 1 + 30) * 16384 + (63 * 256 + 1 + 254), atlas.texel(254, 30, 4095));
		assertEquals(16384 * 2048 - 1, atlas.texel(254, 30, 4095));
	}

	// ---- the accessors -----------------------------------------------------------------------

	@Test
	void keepsWhatItWasBuiltFrom() {
		VolumeAtlas atlas = VolumeAtlas.of(volume(6, 5, 4, PixelFormat.RG, PixelType.UNSIGNED_SHORT), true);

		assertEquals(6, atlas.width());
		assertEquals(5, atlas.height());
		assertEquals(4, atlas.depth());
		assertEquals(PixelType.UNSIGNED_SHORT, atlas.type());
		assertEquals(2, atlas.components());
		assertTrue(atlas.clamp());
		assertEquals(2, atlas.channelBytes());
		assertEquals(8, atlas.texelBytes());
		assertFalse(atlas(1, 1, 1, false).clamp());
	}

	// ---- filling the atlas -------------------------------------------------------------------

	@Test
	void aRepeatingVolumeCarriesTheOppositeEdgeInTheGutter() {
		VolumeAtlas atlas = VolumeAtlas.of(volume(2, 2, 1, PixelFormat.RED, PixelType.UNSIGNED_BYTE), false);

		byte[] out = atlas.spread(new byte[] {1, 2, 3, 4});

		// One tile of 4 by 4 texels of four bytes. Row -1 holds row 1 (3 4), row 2 holds row 0 (1 2),
		// and column -1 holds column 1, column 2 holds column 0. Each texel is r, 0, 0, 255.
		int[][] expected = {{4, 3, 4, 3}, {2, 1, 2, 1}, {4, 3, 4, 3}, {2, 1, 2, 1}};
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				int at = (row * 4 + column) * 4;

				assertArrayEquals(new byte[] {(byte) expected[row][column], 0, 0, -1},
						new byte[] {out[at], out[at + 1], out[at + 2], out[at + 3]}, "row " + row + " column " + column);
			}
		}
	}

	@Test
	void aClampingVolumeCarriesTheEdgeItselfAgain() {
		VolumeAtlas atlas = VolumeAtlas.of(volume(2, 2, 1, PixelFormat.RED, PixelType.UNSIGNED_BYTE), true);

		byte[] out = atlas.spread(new byte[] {1, 2, 3, 4});

		int[][] expected = {{1, 1, 2, 2}, {1, 1, 2, 2}, {3, 3, 4, 4}, {3, 3, 4, 4}};
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				assertEquals(expected[row][column], out[(row * 4 + column) * 4], "row " + row + " column " + column);
			}
		}
	}

	@Test
	void aTileTheDepthDoesNotFillStaysZeroEvenInItsAlpha() {
		// Five slices take 3 by 2 tiles, so the last tile is nobody's.
		VolumeAtlas atlas = VolumeAtlas.of(volume(2, 1, 5, PixelFormat.RGB, PixelType.UNSIGNED_BYTE), false);
		byte[] blob = new byte[2 * 1 * 5 * 3];
		for (int i = 0; i < blob.length; i++) {
			blob[i] = (byte) (i + 1);
		}

		byte[] out = atlas.spread(blob);

		int lastTileFirstColumn = 2 * atlas.tileStride();
		int lastTileFirstRow = atlas.tileHeight();
		for (int row = lastTileFirstRow; row < lastTileFirstRow + atlas.tileHeight(); row++) {
			for (int column = lastTileFirstColumn; column < lastTileFirstColumn + atlas.tileStride(); column++) {
				for (int b = 0; b < 4; b++) {
					assertEquals(0, out[(row * atlas.atlasWidth() + column) * 4 + b], "row " + row + " column " + column);
				}
			}
		}
		assertEquals(atlas.atlasWidth() * atlas.atlasHeight() * 4, out.length);
	}

	@Test
	void aBlobShorterThanTheVolumeIsRefusedNotPaddedWithZeroes() {
		VolumeAtlas atlas = VolumeAtlas.of(volume(2, 2, 2, PixelFormat.RG, PixelType.UNSIGNED_SHORT), false);

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> atlas.spread(new byte[31]));

		assertEquals("A volume of 2x2x2 needs 32 bytes and this blob holds 31", e.getMessage());
		assertEquals(atlas.atlasWidth() * atlas.atlasHeight() * 8, atlas.spread(new byte[32]).length);
	}

	/**
	 * The whole atlas against a second reading of the volume that goes tile by tile and byte by byte
	 * and never calls {@code texel} or {@code index}: what each texel of each tile has to hold.
	 */
	@Test
	void everyTexelOfTheAtlasIsWhatTheVolumeHoldsThereOnGeneratedBlobs() {
		Random random = new Random(12);
		List<PixelFormat> formats = List.of(PixelFormat.RED, PixelFormat.RG, PixelFormat.RGB, PixelFormat.RGBA);
		List<PixelType> types = List.of(PixelType.UNSIGNED_BYTE, PixelType.UNSIGNED_SHORT, PixelType.HALF_FLOAT,
				PixelType.FLOAT);
		byte[][] one = {{-1}, {-1, -1}, {0, 0x3C}, {0, 0, (byte) 0x80, 0x3F}};
		int[][] sizes = {{1, 1, 1}, {2, 3, 5}, {5, 2, 3}, {4, 4, 7}, {3, 1, 10}};

		for (boolean clamp : new boolean[] {false, true}) {
			for (PixelFormat format : formats) {
				for (int typeIndex = 0; typeIndex < types.size(); typeIndex++) {
					int[] size = sizes[random.nextInt(sizes.length)];
					checkAtlas(random, clamp, format, types.get(typeIndex), one[typeIndex], size[0], size[1], size[2]);
				}
			}
		}
	}

	private static void checkAtlas(Random random, boolean clamp, PixelFormat format, PixelType type, byte[] one,
			int width, int height, int depth) {
		int components = format.components();
		int channel = type.channelBytes();
		byte[] blob = new byte[width * height * depth * components * channel];
		random.nextBytes(blob);

		VolumeAtlas atlas = VolumeAtlas.of(volume(width, height, depth, format, type), clamp);
		byte[] out = atlas.spread(blob);

		int across = smallestSquareSide(depth);
		int down = (depth + across - 1) / across;
		int atlasWidth = across * (width + 2);
		int atlasHeight = down * (height + 2);
		assertEquals(atlasWidth * atlasHeight * 4 * channel, out.length);

		for (int tile = 0; tile < across * down; tile++) {
			int originColumn = (tile % across) * (width + 2);
			int originRow = (tile / across) * (height + 2);
			for (int v = 0; v < height + 2; v++) {
				for (int u = 0; u < width + 2; u++) {
					int base = ((originRow + v) * atlasWidth + originColumn + u) * 4 * channel;
					for (int c = 0; c < 4; c++) {
						for (int b = 0; b < channel; b++) {
							byte expected = 0;
							if (tile < depth) {
								int x = u - 1;
								int y = v - 1;
								x = clamp ? Math.min(Math.max(x, 0), width - 1) : ((x % width) + width) % width;
								y = clamp ? Math.min(Math.max(y, 0), height - 1) : ((y % height) + height) % height;
								if (c < components) {
									expected = blob[(((tile * height + y) * width + x) * components + c) * channel + b];
								} else if (c == 3) {
									expected = one[b];
								}
							}

							assertEquals(expected, out[base + c * channel + b], (clamp ? "clamp " : "repeat ") + format
									+ " " + type + " " + width + "x" + height + "x" + depth + " tile " + tile + " at "
									+ u + "," + v + " channel " + c);
						}
					}
				}
			}
		}
	}
}
