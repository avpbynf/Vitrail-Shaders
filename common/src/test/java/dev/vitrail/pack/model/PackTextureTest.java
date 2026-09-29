package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds the description of a texture a pack supplies: how many bytes a blob has to hold for its
 * declaration to be true, whether it is a picture or a blob or a resource of the game, and the
 * line it is named by in the log.
 */
class PackTextureTest {

	private static PackTexture.Raw raw(PackTexture.Shape shape, String internal, int x, int y, int z,
			PixelFormat format, PixelType type) {
		return new PackTexture.Raw(shape, TargetFormat.resolve(internal), x, y, z, format, type);
	}

	@Test
	void aBlobNeedsTheProductOfItsSizesAndTheCostOfATexel() {
		// The SMAA area table: 160 by 560 texels of two bytes.
		assertEquals(179_200L, raw(PackTexture.Shape.TEXTURE_2D, "RG8", 160, 560, 0, PixelFormat.RG,
				PixelType.UNSIGNED_BYTE).bytes());
		assertEquals(4_194_304L, raw(PackTexture.Shape.TEXTURE_3D, "RGBA32F", 64, 64, 64, PixelFormat.RGBA,
				PixelType.FLOAT).bytes());
		assertEquals(1024L, raw(PackTexture.Shape.TEXTURE_1D, "RGBA8", 256, 0, 0, PixelFormat.RGBA,
				PixelType.UNSIGNED_BYTE).bytes());
		assertEquals(3L * 5 * 7 * 6, raw(PackTexture.Shape.TEXTURE_3D, "RGB16", 3, 5, 7, PixelFormat.RGB,
				PixelType.UNSIGNED_SHORT).bytes());
	}

	@Test
	void anAxisOfNoughtCountsAsOneSoALowerDimensionIsNotEmpty() {
		assertEquals(10L, raw(PackTexture.Shape.TEXTURE_1D, "R8", 10, 0, 0, PixelFormat.RED,
				PixelType.UNSIGNED_BYTE).bytes());
		assertEquals(60L, raw(PackTexture.Shape.TEXTURE_2D, "R8", 10, 6, 0, PixelFormat.RED,
				PixelType.UNSIGNED_BYTE).bytes());
	}

	@Test
	void aPackedTypeCostsItsWordAndNotItsChannels() {
		assertEquals(2L * 8 * 8, raw(PackTexture.Shape.TEXTURE_2D, "RGB565", 8, 8, 0, PixelFormat.RGB,
				PixelType.UNSIGNED_SHORT_5_6_5).bytes());
	}

	@Test
	void theCountIsAlongSoAHugeDeclarationCannotWrapIt() {
		long bytes = raw(PackTexture.Shape.TEXTURE_3D, "RGBA32F", 100_000, 100_000, 100_000, PixelFormat.RGBA,
				PixelType.FLOAT).bytes();

		assertEquals(16_000_000_000_000_000L, bytes);
	}

	@Test
	void aTextureIsAPictureWhenItHasNoBlobBehindIt() {
		PackTexture picture = new PackTexture("noise", Optional.empty(), "tex/noise.png", Optional.empty(), false,
				false);
		PackTexture blob = new PackTexture("lut", Optional.empty(), "tex/lut.bin", Optional.of(
				raw(PackTexture.Shape.TEXTURE_2D, "RG8", 4, 4, 0, PixelFormat.RG, PixelType.UNSIGNED_BYTE)), true, true);

		assertTrue(picture.png());
		assertFalse(blob.png());
	}

	@Test
	void aColonInThePathNamesAResourceOfTheGame() {
		assertTrue(PackTexture.gameResource("minecraft:textures/atlas/blocks.png"));
		assertTrue(PackTexture.gameResource("mod:x"));
		assertTrue(PackTexture.gameResource("a:b:c"));
		assertTrue(PackTexture.gameResource("C:/windows/path.png"));
		assertTrue(PackTexture.gameResource(":"));
		assertFalse(PackTexture.gameResource("tex/noise.png"));
		assertFalse(PackTexture.gameResource("/tex/noise.png"));
		assertFalse(PackTexture.gameResource(""));

		PackTexture atlas = new PackTexture("atlas", Optional.empty(), "minecraft:textures/atlas/blocks.png",
				Optional.empty(), false, false);
		assertTrue(atlas.gameResource());
	}

	@Test
	void describesWhichNameIsMovedAndWhereItNowReadsFrom() {
		PackTexture custom = new PackTexture("noise", Optional.empty(), "tex/noise.png", Optional.empty(), false,
				false);
		PackTexture override = new PackTexture("colortex3", Optional.of(TextureStage.COMPOSITE), "tex/lut.png",
				Optional.empty(), false, false);
		PackTexture blob = new PackTexture("areatex", Optional.of(TextureStage.GBUFFERS), "tex/area.bin", Optional.of(
				raw(PackTexture.Shape.TEXTURE_2D, "RG8", 160, 560, 0, PixelFormat.RG, PixelType.UNSIGNED_BYTE)), true,
				true);

		assertEquals("noise supplied by tex/noise.png", custom.describe());
		assertEquals("composite colortex3 overridden by tex/lut.png", override.describe());
		assertEquals("gbuffers areatex overridden by tex/area.bin (TEXTURE_2D RG8 160x560x0)", blob.describe());
	}

	@Test
	void theFourShapesAreTheOnesAPackSpellsOut() {
		assertEquals(4, PackTexture.Shape.values().length);
		assertEquals(PackTexture.Shape.TEXTURE_RECTANGLE, PackTexture.Shape.valueOf("TEXTURE_RECTANGLE"));
	}
}
