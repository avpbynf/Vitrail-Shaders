package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Holds the small answers of {@link LegacyGlsl} to what their javadoc says: how a full screen pass
 * reads the names a quad answers, which type names are opaque, and which passes are drawn from what.
 * <p>
 * The tables are checked at their edges and never in whole. What matters about them is that a name
 * belongs to the right side of each question, and every one of these is asked about a name a pack
 * writes.
 */
class LegacyGlslTest {

	@Test
	void aQuadsPositionReadsAsTheThreeFloatsItCarriesWidenedTheWayTheLanguageWidensAnAttribute() {
		assertEquals("Position", LegacyGlsl.fullscreenElement("vaPosition", "vec3"));
		assertEquals("Position.xy", LegacyGlsl.fullscreenElement("vaPosition", "vec2"));
		assertEquals("Position.x", LegacyGlsl.fullscreenElement("vaPosition", "float"));
		// A missing component reads nought and the fourth reads one.
		assertEquals("vec4(Position, 1.0)", LegacyGlsl.fullscreenElement("vaPosition", "vec4"));
	}

	@Test
	void aQuadsTextureCoordinateIsTwoFloatsWidenedTheSameWay() {
		assertEquals("UV0", LegacyGlsl.fullscreenElement("vaUV0", "vec2"));
		assertEquals("UV0.x", LegacyGlsl.fullscreenElement("vaUV0", "float"));
		assertEquals("vec3(UV0, 0.0)", LegacyGlsl.fullscreenElement("vaUV0", "vec3"));
		assertEquals("vec4(UV0, 0.0, 1.0)", LegacyGlsl.fullscreenElement("vaUV0", "vec4"));
	}

	@Test
	void aNameTheQuadDoesNotCarryOrATypeThatIsNotAFloatVectorIsNotAnswered() {
		assertNull(LegacyGlsl.fullscreenElement("vaColor", "vec4"));
		assertNull(LegacyGlsl.fullscreenElement("vaNormal", "vec3"));
		assertNull(LegacyGlsl.fullscreenElement("Position", "vec3"));
		assertNull(LegacyGlsl.fullscreenElement("vaPosition", "ivec3"));
		assertNull(LegacyGlsl.fullscreenElement("vaPosition", "mat3"));
		assertNull(LegacyGlsl.fullscreenElement("vaPosition", ""));
		assertNull(LegacyGlsl.fullscreenElement("", "vec3"));
	}

	@Test
	void aTypeIsOpaqueByItsPrefixAndNothingPlainIsOpaque() {
		for (String opaque : List.of("sampler2D", "sampler2DShadow", "sampler3D", "isampler2D", "usampler2DArray", "image2D",
				"iimage3D", "uimageBuffer", "texture2D", "itexture2D", "utexture3D", "subpassInput", "atomic_uint")) {
			assertTrue(LegacyGlsl.isOpaqueType(opaque), opaque);
		}

		for (String plain : List.of("float", "int", "uint", "bool", "vec2", "vec4", "ivec3", "uvec4", "mat4", "mat2x3", "",
				"Sampler2D", "struct")) {
			assertFalse(LegacyGlsl.isOpaqueType(plain), plain);
		}
	}

	@Test
	void onlyTheStorageImagesAreImages() {
		for (String image : List.of("image2D", "iimage2D", "uimage3D", "imageBuffer")) {
			assertTrue(LegacyGlsl.isImageType(image), image);
		}

		for (String other : List.of("sampler2D", "isampler2D", "texture2D", "float", "")) {
			assertFalse(LegacyGlsl.isImageType(other), other);
		}
	}

	@Test
	void everyEightOrSixteenBitTypeIsDeclaredUnderItsThirtyTwoBitForm() {
		Map<String, String> expected = Map.ofEntries(Map.entry("float16_t", "float"), Map.entry("int16_t", "int"),
				Map.entry("uint16_t", "uint"), Map.entry("int8_t", "int"), Map.entry("uint8_t", "uint"),
				Map.entry("f16vec2", "vec2"), Map.entry("f16vec4", "vec4"), Map.entry("i16vec3", "ivec3"),
				Map.entry("u16vec2", "uvec2"), Map.entry("i8vec4", "ivec4"), Map.entry("u8vec3", "uvec3"),
				Map.entry("f16mat3", "mat3"), Map.entry("f16mat2x4", "mat2x4"), Map.entry("f16mat4x3", "mat4x3"));

		expected.forEach((narrow, wide) -> assertEquals(wide, LegacyGlsl.widened(narrow), narrow));
	}

	@Test
	void aTypeThatIsAlreadyWideIsHandedBackAsTheSameString() {
		for (String type : List.of("float", "vec3", "uvec4", "half", "double", "", "f32vec3", "mat3")) {
			assertSame(type, LegacyGlsl.widened(type));
		}
	}

	@Test
	void everyNarrowTypeIsATypeNameSoADeclarationUnderItIsNotReadAsAUse() {
		for (String narrow : List.of("float16_t", "int8_t", "f16vec4", "u8vec2", "f16mat3x3", "float32_t", "i64vec3", "f64mat4x2",
				"dmat3x4", "bvec2", "uvec4", "void", "double")) {
			assertTrue(LegacyGlsl.TYPE_NAMES.contains(narrow), narrow);
		}

		for (String other : List.of("texture", "main", "gl_Position", "sampler2D", "struct", "half", "vec5", "vec1", "f16vec5")) {
			assertFalse(LegacyGlsl.TYPE_NAMES.contains(other), other);
		}

		assertThrows(UnsupportedOperationException.class, () -> LegacyGlsl.TYPE_NAMES.add("x"));
	}

	@Test
	void everyFunctionGlslGainedAfter120IsInTheRenameSet() {
		for (String name : List.of("fma", "tanh", "round", "inverse", "transpose", "floatBitsToInt", "packHalf2x16", "findMSB")) {
			assertTrue(LegacyGlsl.POST_120_BUILTINS.contains(name), name);
		}

		for (String name : List.of("sin", "texture2D", "mix", "main")) {
			assertFalse(LegacyGlsl.POST_120_BUILTINS.contains(name), name);
		}
	}

	// ------------------------------------------------------------------------ which pass draws what

	@Test
	void aPassUnderAnEntityRootDrawsEntitiesWhereOneThatMerelySharesAFileDoesNot() {
		for (String program : List.of("gbuffers_entities", "gbuffers_entities_translucent", "gbuffers_block", "gbuffers_hand",
				"shadow_entities", "shadow_block")) {
			assertTrue(LegacyGlsl.drawsEntities(program), program);
		}

		for (String program : List.of("gbuffers_textured_lit", "gbuffers_textured", "gbuffers_terrain", "gbuffers_skybasic",
				"shadow", "composite", "")) {
			assertFalse(LegacyGlsl.drawsEntities(program), program);
		}
	}

	@Test
	void theGlintIsDrawnFromAPreparedDrawWithoutBeingAnEntity() {
		assertTrue(LegacyGlsl.bindsGameTransforms("gbuffers_armor_glint"));
		assertFalse(LegacyGlsl.drawsEntities("gbuffers_armor_glint"));
		// Its parent, which the sky reaches as well, is not one.
		assertFalse(LegacyGlsl.bindsGameTransforms("gbuffers_textured"));
	}

	@Test
	void everyEntityPassBindsTheGamesTransformsAndTheTerrainAndTheQuadsDoNot() {
		for (String program : List.of("gbuffers_entities", "gbuffers_block", "gbuffers_hand", "shadow_entities", "shadow_block",
				"gbuffers_armor_glint")) {
			assertTrue(LegacyGlsl.bindsGameTransforms(program), program);
		}

		for (String program : List.of("gbuffers_terrain", "gbuffers_water", "gbuffers_skybasic", "composite", "final", "")) {
			assertFalse(LegacyGlsl.bindsGameTransforms(program), program);
		}
	}

	@Test
	void theModelViewIsAskedPerDrawOfTheSamePassesLessTheTwoDrawnFromTheLight() {
		for (String program : List.of("gbuffers_entities", "gbuffers_entities_translucent", "gbuffers_block", "gbuffers_hand",
				"gbuffers_armor_glint")) {
			assertTrue(LegacyGlsl.readsDrawModelView(program), program);
		}

		for (String program : List.of("shadow_entities", "shadow_block", "gbuffers_terrain", "composite", "")) {
			assertFalse(LegacyGlsl.readsDrawModelView(program), program);
		}

		for (String program : List.of("gbuffers_entities", "shadow_entities", "shadow_block", "gbuffers_terrain",
				"gbuffers_armor_glint", "")) {
			assertEquals(LegacyGlsl.bindsGameTransforms(program) && !program.startsWith("shadow"),
					LegacyGlsl.readsDrawModelView(program), program);
		}
	}
}
