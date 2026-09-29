package dev.vitrail.sodium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The word that carries where a block sits in its section and what light it gives off, one field a
 * byte. Both the block renderer and the fluid renderer write it and the chunk encoder reads it back,
 * so the layout is a contract between three places that nothing else here checks.
 * <p>
 * The layout is held against a byte array laid out by hand, little endian, x then y then z then the
 * emission, so a shifted field is caught by a byte in the wrong place and not by two shifts agreeing.
 */
class TerrainVertexTest {

	private static byte[] bytesOf(int packed) {
		return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(packed).array();
	}

	@Test
	void eachFieldGetsItsOwnByteInTheOrderXYZEmission() {
		assertEquals(0x04030201, TerrainVertex.pack(1, 2, 3, 4));
		assertEquals(0x0000000F, TerrainVertex.pack(15, 0, 0, 0));
		assertEquals(0x00000F00, TerrainVertex.pack(0, 15, 0, 0));
		assertEquals(0x000F0000, TerrainVertex.pack(0, 0, 15, 0));
		assertEquals(0xFF000000, TerrainVertex.pack(0, 0, 0, 255));
		assertEquals(0, TerrainVertex.pack(0, 0, 0, 0));
	}

	@Test
	void theBytesInMemoryAreXYZAndEmissionForEveryCornerOfTheRange() {
		int[] axes = {0, 1, 7, 8, 14, 15};
		int[] lights = {0, 1, 14, 15, 16, 127, 128, 254, 255};
		for (int x : axes) {
			for (int y : axes) {
				for (int z : axes) {
					for (int light : lights) {
						byte[] bytes = bytesOf(TerrainVertex.pack(x, y, z, light));

						assertEquals(x, bytes[0] & 0xFF, "x of " + x + "," + y + "," + z + "," + light);
						assertEquals(y, bytes[1] & 0xFF, "y");
						assertEquals(z, bytes[2] & 0xFF, "z");
						assertEquals(light, bytes[3] & 0xFF, "emission");
					}
				}
			}
		}
	}

	@Test
	void aWorldPositionIsReducedToItsSectionByMaskingToSixteenAndNotToAByte() {
		// 16 is the next section's first block; masked to a byte it would keep four bits naming the
		// section and put the middle of the block up to fifteen sections away.
		assertEquals(TerrainVertex.pack(0, 0, 0, 0), TerrainVertex.pack(16, 16, 16, 0));
		assertEquals(TerrainVertex.pack(1, 2, 3, 0), TerrainVertex.pack(17, 34, 51, 0));
		assertEquals(TerrainVertex.pack(1, 2, 3, 0), TerrainVertex.pack(16 * 1000 + 1, 16 * 77 + 2, 16 * 9 + 3, 0));
		assertEquals(15, TerrainVertex.origin(TerrainVertex.pack(255, 255, 255, 0), 0));
		assertEquals(15, TerrainVertex.origin(TerrainVertex.pack(255, 255, 255, 0), 1));
		assertEquals(15, TerrainVertex.origin(TerrainVertex.pack(255, 255, 255, 0), 2));
	}

	@Test
	void aNegativeWorldCoordinateWrapsTheWayASectionCountsItsBlocks() {
		// Block -1 is the last block, 15, of the section below the origin; block -16 the first.
		assertEquals(TerrainVertex.pack(15, 15, 15, 0), TerrainVertex.pack(-1, -1, -1, 0));
		assertEquals(TerrainVertex.pack(0, 0, 0, 0), TerrainVertex.pack(-16, -32, -48, 0));
		assertEquals(TerrainVertex.pack(15, 0, 1, 0), TerrainVertex.pack(-17, -64, -63, 0));
		assertEquals(15, TerrainVertex.origin(TerrainVertex.pack(Integer.MIN_VALUE + 15, 0, 0, 0), 0));
		assertEquals(0, TerrainVertex.origin(TerrainVertex.pack(Integer.MIN_VALUE, 0, 0, 0), 0));
		assertEquals(15, TerrainVertex.origin(TerrainVertex.pack(Integer.MAX_VALUE, 0, 0, 0), 0));
	}

	@Test
	void theEmissionKeepsOnlyItsLowByteAndNeverSpillsIntoTheCoordinates() {
		assertEquals(0, TerrainVertex.emission(TerrainVertex.pack(0, 0, 0, 256)));
		assertEquals(1, TerrainVertex.emission(TerrainVertex.pack(0, 0, 0, 257)));
		assertEquals(255, TerrainVertex.emission(TerrainVertex.pack(0, 0, 0, -1)));
		assertEquals(128, TerrainVertex.emission(TerrainVertex.pack(0, 0, 0, Integer.MIN_VALUE + 128)));
		assertEquals(TerrainVertex.pack(3, 4, 5, 0), TerrainVertex.pack(3, 4, 5, 256));
		// emission of 255 makes the whole word negative and must still read back as 255, not -1
		int lit = TerrainVertex.pack(1, 2, 3, 255);
		assertTrue(lit < 0);
		assertEquals(255, TerrainVertex.emission(lit));
		assertEquals(255, TerrainVertex.origin(lit, 3));
	}

	@Test
	void everyCoordinateAndEveryLightReadsBackFromTheWordItWasPackedInto() {
		for (int x = -33; x <= 33; x += 1) {
			for (int y = -33; y <= 33; y += 2) {
				for (int z = -33; z <= 33; z += 3) {
					for (int light = 0; light < 256; light += 5) {
						int packed = TerrainVertex.pack(x, y, z, light);

						assertEquals(Math.floorMod(x, 16), TerrainVertex.origin(packed, 0));
						assertEquals(Math.floorMod(y, 16), TerrainVertex.origin(packed, 1));
						assertEquals(Math.floorMod(z, 16), TerrainVertex.origin(packed, 2));
						assertEquals(light, TerrainVertex.emission(packed));
					}
				}
			}
		}
	}

	@Test
	void everyOneOfTheFourThousandAndNinetySixBlocksOfASectionWithEveryLight() {
		int seen = 0;
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int light = 0; light < 256; light++) {
						int packed = TerrainVertex.pack(x, y, z, light);
						assertEquals(x, TerrainVertex.origin(packed, 0));
						assertEquals(y, TerrainVertex.origin(packed, 1));
						assertEquals(z, TerrainVertex.origin(packed, 2));
						assertEquals(light, TerrainVertex.emission(packed));
						seen++;
					}
				}
			}
		}

		assertEquals(16 * 16 * 16 * 256, seen);
	}

	@Test
	void distinctBlocksOfOneSectionNeverShareAWord() {
		Set<Integer> words = new HashSet<>();
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					words.add(TerrainVertex.pack(x, y, z, 9));
				}
			}
		}

		assertEquals(4096, words.size());
	}
}
