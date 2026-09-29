package dev.vitrail.pack.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.TargetFormat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds the typed view of a custom image carried in its translated resource name, and the rule for
 * which format a shader's declaration asks a view of.
 */
class CustomImageViewTest {

	@Test
	void aViewIsNamedByItsFormatWordAndTheImageItIsAViewOf() {
		assertEquals("ofCustomImageView_rgba16f_vol", new CustomImageView("vol", TargetFormat.RGBA16_FLOAT).name());
		assertEquals("ofCustomImageView_r8_snorm_a_b", new CustomImageView("a_b", TargetFormat.R8_SNORM).name());
	}

	@Test
	void everyFormatAndAnyImageNameRoundTripsThroughTheName() {
		for (TargetFormat format : TargetFormat.values()) {
			for (String original : List.of("vol", "my_image", "a_b_c", "x", "Image1", "r8", "rgba8_snorm_x")) {
				CustomImageView view = new CustomImageView(original, format);

				assertEquals(Optional.of(view), CustomImageView.parse(view.name()), view.name());
			}
		}
	}

	@Test
	void theLongestFormatWordThatFitsWinsSoASnormViewIsNotAnR8ViewOfSnormX() {
		CustomImageView parsed = CustomImageView.parse("ofCustomImageView_rgba8_snorm_x").orElseThrow();

		assertEquals(TargetFormat.RGBA8_SNORM, parsed.format());
		assertEquals("x", parsed.original());
		assertEquals(TargetFormat.RGB10A2_UINT, CustomImageView.parse("ofCustomImageView_rgb10_a2ui_x")
				.orElseThrow().format());
		assertEquals(TargetFormat.RGB10A2_UNORM, CustomImageView.parse("ofCustomImageView_rgb10_a2_ui_x")
				.orElseThrow().format());
	}

	/** The name is the format word, an underscore and the image, and an image can start like a word. */
	@Test
	void knownBug_anImageWhoseNameStartsLikeASnormWordIsReadBackAsAnotherFormat() {
		CustomImageView view = new CustomImageView("snorm_x", TargetFormat.R8_UNORM);

		CustomImageView parsed = CustomImageView.parse(view.name()).orElseThrow();

		assertEquals("ofCustomImageView_r8_snorm_x", view.name());
		assertEquals(TargetFormat.R8_SNORM, parsed.format());
		assertEquals("x", parsed.original());
	}

	@Test
	void whatIsNotAViewNameIsNotParsed() {
		for (String name : List.of("", "vol", "ofCustomImageView_", "ofCustomImageView_r8", "ofCustomImageView_r8_",
				"ofCustomImageView_bogus_x", "ofCustomImageView_R8_x", "xofCustomImageView_r8_x",
				"ofCustomImageView__x", "ofcustomimageview_r8_x")) {
			assertTrue(CustomImageView.parse(name).isEmpty(), "'" + name + "'");
		}
	}

	@Test
	void twoFormatsAreCompatibleWhenTheirTexelsAreTheSameSize() {
		assertTrue(CustomImageView.compatible(TargetFormat.R8_UNORM, TargetFormat.R8_UINT));
		assertTrue(CustomImageView.compatible(TargetFormat.R32_FLOAT, TargetFormat.R32_UINT));
		assertTrue(CustomImageView.compatible(TargetFormat.RGBA8_UNORM, TargetFormat.R32_FLOAT));
		assertTrue(CustomImageView.compatible(TargetFormat.RG16_UNORM, TargetFormat.RGBA8_UINT));
		assertFalse(CustomImageView.compatible(TargetFormat.R8_UNORM, TargetFormat.R16_UNORM));
		assertFalse(CustomImageView.compatible(TargetFormat.RGBA16_FLOAT, TargetFormat.RGBA32_FLOAT));
	}

	// ---- the format a declaration asks for ----------------------------------------------------

	@Test
	void aLayoutQualifierNamesTheFormatAndWinsOverTheType() {
		assertEquals(Optional.of(TargetFormat.RGBA8_UNORM),
				CustomImageView.requested(TargetFormat.R8_UNORM, "image2D", "rgba8"));
		assertEquals(Optional.of(TargetFormat.R32_FLOAT),
				CustomImageView.requested(TargetFormat.R32_FLOAT, "uimage2D", "r32f"));
		assertEquals(Optional.of(TargetFormat.RGB10A2_UINT),
				CustomImageView.requested(TargetFormat.RGBA8_UNORM, "uimage3D", "rgb10_a2ui"));
		assertTrue(CustomImageView.requested(TargetFormat.R8_UNORM, "image2D", "nonsense").isEmpty());
	}

	@Test
	void anIntegerTypeAsksForTheIntegerFormatOfTheSameShapeAndSize() {
		assertEquals(Optional.of(TargetFormat.RGBA8_UINT),
				CustomImageView.requested(TargetFormat.RGBA8_UNORM, "uimage2D", ""));
		assertEquals(Optional.of(TargetFormat.R32_UINT),
				CustomImageView.requested(TargetFormat.R32_FLOAT, "uimage3D", null));
		assertEquals(Optional.of(TargetFormat.R32_SINT),
				CustomImageView.requested(TargetFormat.R32_FLOAT, "iimage3D", ""));
		assertEquals(Optional.of(TargetFormat.R32_SINT),
				CustomImageView.requested(TargetFormat.R32_UINT, "iimage2D", ""));
		assertEquals(Optional.of(TargetFormat.RGBA16_UINT),
				CustomImageView.requested(TargetFormat.RGBA16_FLOAT, "usampler2D", ""));
		assertEquals(Optional.of(TargetFormat.RGBA32_SINT),
				CustomImageView.requested(TargetFormat.RGBA32_FLOAT, "isampler3D", ""));
		assertEquals(Optional.of(TargetFormat.RGBA8_SINT),
				CustomImageView.requested(TargetFormat.RGB10A2_UNORM, "iimage2D", ""));
		assertEquals(Optional.of(TargetFormat.R8_UINT),
				CustomImageView.requested(TargetFormat.R8_UNORM, "uimageBuffer", ""));
	}

	@Test
	void aBaseThatAlreadyHasTheRightTypeNeedsNoView() {
		assertTrue(CustomImageView.requested(TargetFormat.R32_UINT, "uimage3D", "").isEmpty());
		assertTrue(CustomImageView.requested(TargetFormat.RGBA8_SINT, "isampler2D", null).isEmpty());
	}

	@Test
	void aFloatTypeAndAnUnknownOneNeedNoView() {
		for (String type : List.of("image2D", "sampler2D", "image3D", "", "vec4", "uvec4")) {
			assertTrue(CustomImageView.requested(TargetFormat.R32_FLOAT, type, "").isEmpty(), "'" + type + "'");
		}
	}

	@Test
	void anIntegerViewOfAFormatWithNoIntegerTwinInThatWidthIsRefused() {
		// Three components in four bytes has no integer format.
		assertTrue(CustomImageView.requested(TargetFormat.RG11B10_FLOAT, "uimage2D", "").isEmpty());
	}
}
