package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds the grammar of {@code image.NAME}: a sampler or {@code none}, a pixel format, an internal
 * format, a pixel type, whether the image is cleared, whether the size is a fraction of the
 * screen, and then one to three dimensions, which may be the names of the pack's own settings.
 */
class ImageInformationTest {

	private final List<ImageInformation> images = new ArrayList<>();

	private String parse(String name, String value) {
		return ImageInformation.parse(name, value, Map.of(), this.images);
	}

	private ImageInformation only() {
		assertEquals(1, this.images.size());

		return this.images.get(0);
	}

	@Test
	void nineWordsAreAVolume() {
		assertNull(parse("floodfill", "floodfill_img RGBA RGBA16F HALF_FLOAT true false 256 128 64"));

		ImageInformation image = only();
		assertEquals("floodfill", image.name());
		assertEquals(Optional.of("floodfill_img"), image.sampler());
		assertEquals(PackTexture.Shape.TEXTURE_3D, image.shape());
		assertEquals(PixelFormat.RGBA, image.pixelFormat());
		assertEquals(TargetFormat.RGBA16_FLOAT, image.internalFormat().used());
		assertEquals("RGBA16F", image.internalFormat().declared());
		assertEquals(PixelType.HALF_FLOAT, image.pixelType());
		assertEquals(256, image.width());
		assertEquals(128, image.height());
		assertEquals(64, image.depth());
		assertTrue(image.clear());
		assertFalse(image.relative());
		assertEquals(0.0F, image.relativeWidth());
		assertEquals(0.0F, image.relativeHeight());
	}

	@Test
	void eightWordsAreAPictureAndSevenAreALine() {
		assertNull(parse("flat", "none RED_INTEGER R32UI UNSIGNED_INT false false 512 256"));
		assertNull(parse("line", "none RED R32I INT false false 1024"));

		ImageInformation flat = this.images.get(0);
		assertEquals(PackTexture.Shape.TEXTURE_2D, flat.shape());
		assertEquals(512, flat.width());
		assertEquals(256, flat.height());
		assertEquals(0, flat.depth());
		assertEquals(Optional.empty(), flat.sampler());
		assertFalse(flat.clear());

		ImageInformation line = this.images.get(1);
		assertEquals(PackTexture.Shape.TEXTURE_1D, line.shape());
		assertEquals(1024, line.width());
		assertEquals(0, line.height());
		assertEquals(0, line.depth());
	}

	@Test
	void aRelativeImageTakesExactlyTwoFractionsAndIsTwoDimensional() {
		assertNull(parse("scr", "scene RGBA RGBA8 UNSIGNED_BYTE true true 0.5 0.25"));

		ImageInformation image = only();
		assertTrue(image.relative());
		assertEquals(PackTexture.Shape.TEXTURE_2D, image.shape());
		assertEquals(0.5F, image.relativeWidth());
		assertEquals(0.25F, image.relativeHeight());
		assertEquals(0, image.width());
		assertEquals(0, image.height());
		assertEquals(0, image.depth());
	}

	@Test
	void theSamplerIsNoneInAnyCaseOrEmpty() {
		assertNull(parse("a", "none RGBA RGBA8 UNSIGNED_BYTE false false 4"));
		assertNull(parse("b", "NONE RGBA RGBA8 UNSIGNED_BYTE false false 4"));
		assertNull(parse("c", " RGBA RGBA8 UNSIGNED_BYTE false false 4"));
		assertNull(parse("d", "None_ RGBA RGBA8 UNSIGNED_BYTE false false 4"));

		assertEquals(Optional.empty(), this.images.get(0).sampler());
		assertEquals(Optional.empty(), this.images.get(1).sampler());
		assertEquals(Optional.empty(), this.images.get(2).sampler());
		assertEquals(Optional.of("None_"), this.images.get(3).sampler());
	}

	@Test
	void clearAndRelativeAreBooleansReadTheWayJavaReadsThem() {
		assertNull(parse("a", "none RGBA RGBA8 UNSIGNED_BYTE TRUE false 4"));
		assertNull(parse("b", "none RGBA RGBA8 UNSIGNED_BYTE 1 false 4"));
		assertNull(parse("c", "none RGBA RGBA8 UNSIGNED_BYTE yes false 4"));

		assertTrue(this.images.get(0).clear());
		assertFalse(this.images.get(1).clear());
		assertFalse(this.images.get(2).clear());
	}

	@Test
	void aSizeMayBeTheNameOfASettingAndIsReadLikeAnInt() {
		Map<String, String> defines = Map.of("SIZE", " 32 ", "HEIGHT", "8", "WORD", "big");

		assertNull(ImageInformation.parse("v", "none RGBA RGBA8 UNSIGNED_BYTE false false SIZE HEIGHT 016", defines,
				this.images));

		ImageInformation image = only();
		assertEquals(32, image.width());
		assertEquals(8, image.height());
		assertEquals(16, image.depth());

		assertEquals("size is not a number", ImageInformation.parse("w", "none RGBA RGBA8 UNSIGNED_BYTE false false WORD",
				defines, this.images));
		assertEquals("size is not a number", ImageInformation.parse("x", "none RGBA RGBA8 UNSIGNED_BYTE false false NOPE 4",
				defines, this.images));
		assertEquals("size is not a number", ImageInformation.parse("y", "none RGBA RGBA8 UNSIGNED_BYTE false false 4 4 1.5",
				defines, this.images));
		assertEquals(1, this.images.size());
	}

	@Test
	void aRelativeSizeIsAFloatAndIsNeverLookedUpInTheSettings() {
		Map<String, String> defines = Map.of("HALF", "0.5");

		assertEquals("relative size is not a number", ImageInformation.parse("x",
				"none RGBA RGBA8 UNSIGNED_BYTE false true HALF HALF", defines, this.images));
		assertTrue(this.images.isEmpty());
	}

	@Test
	void theParserDoesNotJudgeTheSizeItReads() {
		assertNull(parse("z", "none RGBA RGBA8 UNSIGNED_BYTE false false 0 -5"));

		assertEquals(0, only().width());
		assertEquals(-5, only().height());
	}

	@Test
	void fewerThanSixWordsIsRefused() {
		assertEquals("expected at least six words", parse("a", ""));
		assertEquals("expected at least six words", parse("a", "none RGBA RGBA8 UNSIGNED_BYTE false"));
		assertTrue(this.images.isEmpty());
	}

	@Test
	void aCountOfWordsThatIsNoShapeIsRefused() {
		// Six words name no dimension at all; ten name four.
		assertEquals("unknown image type", parse("a", "none RGBA RGBA8 UNSIGNED_BYTE false false"));
		assertEquals("unknown image type", parse("a", "none RGBA RGBA8 UNSIGNED_BYTE false false 1 2 3 4"));
		// A relative image takes two words and no other count.
		assertEquals("a relative image takes two size words", parse("a", "none RGBA RGBA8 UNSIGNED_BYTE false true"));
		assertEquals("a relative image takes two size words", parse("a", "none RGBA RGBA8 UNSIGNED_BYTE false true 0.5"));
		assertEquals("a relative image takes two size words",
				parse("a", "none RGBA RGBA8 UNSIGNED_BYTE false true 0.5 0.5 0.5"));
		assertTrue(this.images.isEmpty());
	}

	@Test
	void aFormatOrATypeThatIsNotOneNamesAllThreeInTheReason() {
		assertEquals("format XYZ internal RGBA8 pixel type UNSIGNED_BYTE",
				parse("a", "none XYZ RGBA8 UNSIGNED_BYTE false false 4"));
		assertEquals("format RGBA internal NOPE pixel type UNSIGNED_BYTE",
				parse("a", "none RGBA NOPE UNSIGNED_BYTE false false 4"));
		assertEquals("format RGBA internal RGBA8 pixel type BYTES",
				parse("a", "none RGBA RGBA8 BYTES false false 4"));
		assertTrue(this.images.isEmpty());
	}

	@Test
	void aWidenedInternalFormatIsAcceptedAndOnlyAnUnknownOneIsNot() {
		assertNull(parse("a", "none RGB RGB16F HALF_FLOAT false false 4"));

		assertEquals(TargetFormat.Reason.PROMOTED, only().internalFormat().reason());
		assertTrue(only().internalFormat().alphaAdded());
	}

	@Test
	void everyImageIsAddedInTheOrderItIsRead() {
		assertNull(parse("first", "none RGBA RGBA8 UNSIGNED_BYTE false false 4"));
		assertEquals("expected at least six words", parse("broken", "none"));
		assertNull(parse("second", "none RGBA RGBA8 UNSIGNED_BYTE false false 4"));

		assertEquals(List.of("first", "second"), this.images.stream().map(ImageInformation::name).toList());
		assertEquals(16, ImageInformation.LIMIT);
	}

	@Test
	void describesTheNameTheSamplerTheShapeAndTheSize() {
		assertNull(parse("floodfill", "floodfill_img RGBA RGBA16F HALF_FLOAT true false 256 128 64"));
		assertNull(parse("flat", "none RED_INTEGER R32UI UNSIGNED_INT false false 512 256"));
		assertNull(parse("line", "none RED R32I INT false false 1024"));
		assertNull(parse("scr", "scene RGBA RGBA8 UNSIGNED_BYTE true true 0.5 0.25"));

		assertEquals("floodfill as floodfill_img TEXTURE_3D RGBA16F 256x128x64, cleared each frame",
				this.images.get(0).describe());
		assertEquals("flat TEXTURE_2D R32UI 512x256", this.images.get(1).describe());
		assertEquals("line TEXTURE_1D R32I 1024", this.images.get(2).describe());
		assertEquals("scr as scene TEXTURE_2D RGBA8 0.5x0.25 of the screen, cleared each frame",
				this.images.get(3).describe());
	}

	@Test
	void aRectangleIsDescribedLikeAPicture() {
		ImageInformation rectangle = new ImageInformation("r", Optional.empty(), PackTexture.Shape.TEXTURE_RECTANGLE,
				PixelFormat.RGBA, TargetFormat.resolve("RGBA8"), PixelType.UNSIGNED_BYTE, 16, 9, 0, false, false, 0.0F,
				0.0F);

		assertEquals("r TEXTURE_RECTANGLE RGBA8 16x9", rectangle.describe());
	}

	@Test
	void aReadingHoldsCopiesAndAnEmptyOneHoldsNothing() {
		List<String> dropped = new ArrayList<>(List.of("image.x = y: expected at least six words"));
		assertNull(parse("a", "none RGBA RGBA8 UNSIGNED_BYTE false false 4"));

		ImageInformation.Reading reading = new ImageInformation.Reading(this.images, dropped);
		this.images.clear();
		dropped.clear();

		assertEquals(1, reading.images().size());
		assertEquals(1, reading.dropped().size());
		assertThrows(UnsupportedOperationException.class, () -> reading.images().clear());
		assertThrows(UnsupportedOperationException.class, () -> reading.dropped().clear());
		assertTrue(ImageInformation.Reading.empty().images().isEmpty());
		assertTrue(ImageInformation.Reading.empty().dropped().isEmpty());
	}
}
