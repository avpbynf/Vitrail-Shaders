package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link VertexPrologue}, and the vertex format table it shares with {@link VertexInputs}, to
 * the answers a vertex head writes into every program of the engine.
 * <p>
 * The list is walked by heads that write it out, so its order is part of the text: a set literal
 * hands its names back in an order the runtime picks afresh on every start, which is a different
 * shader to the game from one run to the next. Every answer here is one a head would print, and the
 * order is pinned as the sort the class says it keeps.
 */
class VertexPrologueTest {

	@Test
	void theNamesNoMeshCarriesAreSortedAndCannotBeChanged() {
		assertEquals(List.of("at_midBlock", "at_tangent", "dhMaterialId", "mc_Entity", "mc_chunkFade", "mc_midTexCoord",
				"vaColor", "vaNormal", "vaPosition", "vaUV0", "vaUV1", "vaUV2"), List.copyOf(VertexPrologue.SYNTHESIZED));
		assertThrows(UnsupportedOperationException.class, () -> VertexPrologue.SYNTHESIZED.add("x"));
		assertThrows(UnsupportedOperationException.class, () -> VertexPrologue.SYNTHESIZED.remove("at_tangent"));
	}

	@Test
	void everyTypeIsZeroSpelledTheWayItsOwnLanguageTakesIt() {
		assertEquals("0.0", VertexPrologue.zero("float"));
		assertEquals("0", VertexPrologue.zero("int"));
		assertEquals("0u", VertexPrologue.zero("uint"));
		assertEquals("false", VertexPrologue.zero("bool"));
		for (String type : List.of("vec2", "vec3", "vec4")) {
			assertEquals(type + "(0.0)", VertexPrologue.zero(type));
		}

		for (String type : List.of("ivec2", "ivec3", "ivec4")) {
			assertEquals(type + "(0)", VertexPrologue.zero(type));
		}

		for (String type : List.of("uvec2", "uvec3", "uvec4")) {
			assertEquals(type + "(0u)", VertexPrologue.zero(type));
		}

		for (String type : List.of("bvec2", "bvec3", "bvec4")) {
			assertEquals(type + "(false)", VertexPrologue.zero(type));
		}

		assertEquals("mat3(0.0)", VertexPrologue.zero("mat3"));
	}

	@Test
	void aNameThatMustNotBeNoughtGetsAnAxisAndOnlyUnderTheTypeThatAxisIsWrittenFor() {
		assertEquals("vec4(1.0, 0.0, 0.0, 1.0)", VertexPrologue.value("at_tangent", "vec4"));
		assertEquals("vec3(0.0, 1.0, 0.0)", VertexPrologue.value("of_Normal", "vec3"));
		// A pack that declared them under another type gets that type's nought, not a constructor of the wrong size.
		assertEquals("vec3(0.0)", VertexPrologue.value("at_tangent", "vec3"));
		assertEquals("vec4(0.0)", VertexPrologue.value("of_Normal", "vec4"));
		assertEquals("vec4(0.0)", VertexPrologue.value("mc_Entity", "vec4"));
	}

	@Test
	void aDeclarationIsAGlobalOfThePacksTypeHoldingItsConstant() {
		assertEquals("vec4 at_tangent = vec4(1.0, 0.0, 0.0, 1.0);", VertexPrologue.declaration("at_tangent", "vec4"));
		assertEquals("int dhMaterialId = 0;", VertexPrologue.declaration("dhMaterialId", "int"));
		assertEquals("vec2 mc_Entity = vec2(0.0);", VertexPrologue.declaration("mc_Entity", "vec2"));
	}

	@Test
	void theTypeOfAnElementIsTheOneTheGamesFormatsSpellItWith() {
		assertEquals("vec3", VertexPrologue.elementType("Position"));
		assertEquals("vec2", VertexPrologue.elementType("UV0"));
		assertEquals("ivec2", VertexPrologue.elementType("UV1"));
		assertEquals("ivec2", VertexPrologue.elementType("UV2"));
		assertEquals("float", VertexPrologue.elementType("LineWidth"));
		assertEquals("vec4", VertexPrologue.elementType("Color"));
		assertEquals("vec4", VertexPrologue.elementType("Normal"));
		assertEquals("vec2", VertexPrologue.elementType(EntityVertex.MID_TEX_COORD));
		assertEquals("uvec4", VertexPrologue.elementType(EntityVertex.IDENTIFIERS));
		// Anything a format adds later reads as four normalised bytes until somebody says otherwise.
		assertEquals("vec4", VertexPrologue.elementType("NotAnElement"));
		assertEquals("vec4", VertexPrologue.elementType(""));
	}

	@Test
	void theTextureUnitsAboveTheLightMapAreBlankMacros() {
		assertEquals(List.of("#define of_MultiTexCoord3 vec4(0.0, 0.0, 0.0, 1.0)",
				"#define of_MultiTexCoord4 vec4(0.0, 0.0, 0.0, 1.0)", "#define of_MultiTexCoord5 vec4(0.0, 0.0, 0.0, 1.0)",
				"#define of_MultiTexCoord6 vec4(0.0, 0.0, 0.0, 1.0)", "#define of_MultiTexCoord7 vec4(0.0, 0.0, 0.0, 1.0)"),
				VertexPrologue.blankTexCoords());
	}

	@Test
	void onlyTheNamesTheBodyMentionsAreDeclaredAndInTheOrderThePackThenTheHeadThenTheSortedList() {
		Map<String, String> declared = new LinkedHashMap<>();
		declared.put("mc_Entity", "vec2");
		Map<String, String> ahead = new LinkedHashMap<>();
		ahead.put("of_MultiTexCoord9", "vec4");
		ahead.put("unused_ahead", "vec4");
		Set<String> used = Set.of("mc_Entity", "vaUV1", "at_tangent", "of_MultiTexCoord9", "vaColor", "notOne", "at_midBlock");

		Map<String, String> globals = VertexPrologue.globals(used, declared, ahead);

		assertEquals(List.of("mc_Entity", "of_MultiTexCoord9", "at_midBlock", "at_tangent", "vaColor", "vaUV1"),
				new ArrayList<>(globals.keySet()));
		// The type the pack gave a name wins over what the name would have been given.
		assertEquals("vec2", globals.get("mc_Entity"));
		assertEquals("vec4", globals.get("of_MultiTexCoord9"));
		assertEquals("vec3", globals.get("at_midBlock"));
		assertEquals("vec4", globals.get("at_tangent"));
		assertEquals("ivec2", globals.get("vaUV1"));
	}

	@Test
	void everyNameIsGivenTheTypeItMeansWhenThePackNeverDeclaredIt() {
		Map<String, String> globals = VertexPrologue.globals(VertexPrologue.SYNTHESIZED, Map.of(), Map.of());

		assertEquals("int", globals.get("dhMaterialId"));
		assertEquals("float", globals.get("mc_chunkFade"));
		assertEquals("vec3", globals.get("at_midBlock"));
		assertEquals("vec3", globals.get("vaPosition"));
		assertEquals("vec3", globals.get("vaNormal"));
		assertEquals("ivec2", globals.get("vaUV1"));
		assertEquals("ivec2", globals.get("vaUV2"));
		assertEquals("vec2", globals.get("vaUV0"));
		assertEquals("vec4", globals.get("mc_Entity"));
		assertEquals("vec4", globals.get("mc_midTexCoord"));
		assertEquals("vec4", globals.get("vaColor"));
		assertEquals("vec4", globals.get("at_tangent"));
		assertEquals(VertexPrologue.SYNTHESIZED.size(), globals.size());
	}

	@Test
	void aPackThatReadsNothingIsDeclaredNothingAndTheTailIsOneLinePerGlobal() {
		assertTrue(VertexPrologue.tail(Set.of(), Map.of()).isEmpty());
		assertTrue(VertexPrologue.tail(Set.of("gl_Position", "texcoord"), Map.of()).isEmpty());

		List<String> tail = VertexPrologue.tail(Set.of("at_tangent", "dhMaterialId"), Map.of());

		// In the order of the sorted list, which is the one order a head may print them in.
		assertEquals(List.of("vec4 at_tangent = vec4(1.0, 0.0, 0.0, 1.0);", "int dhMaterialId = 0;"), tail);
	}

	@Test
	void aNameThePackDeclaredIsDeclaredEvenWhenTheBodyNoLongerMentionsIt() {
		Map<String, String> declared = Map.of("mc_Entity", "vec2");

		assertEquals(List.of("vec2 mc_Entity = vec2(0.0);"), VertexPrologue.tail(Set.of(), declared));
	}

	// ------------------------------------------------------------------------ VertexInputs

	@Test
	void everyVertexInputsNamesItsElementsAndOnlyWorldNamesNone() {
		for (VertexInputs inputs : VertexInputs.values()) {
			if (inputs == VertexInputs.WORLD) {
				assertTrue(inputs.elements().isEmpty());
			} else {
				assertFalse(inputs.elements().isEmpty(), inputs.name());
				assertEquals(inputs.elements().size(), Set.copyOf(inputs.elements()).size(), inputs.name() + " repeats a name");
			}
		}
	}

	@Test
	void theQuestionsAVertexInputsAnswersAreEachTrueForExactlyTheConstantsTheyNameInTheirDocs() {
		for (VertexInputs inputs : VertexInputs.values()) {
			assertEquals(inputs != VertexInputs.WORLD, inputs.synthesizes(), inputs.name());
			assertEquals(inputs == VertexInputs.TERRAIN || inputs == VertexInputs.TERRAIN_SEPARATE_AO, inputs.terrain(),
					inputs.name());
			assertEquals(inputs == VertexInputs.TERRAIN_SEPARATE_AO, inputs.separateAo(), inputs.name());
			assertEquals(inputs == VertexInputs.ENTITY_FULLBRIGHT || inputs == VertexInputs.CRUMBLING, inputs.fullbright(),
					inputs.name());
			assertEquals(inputs == VertexInputs.ENTITY || inputs == VertexInputs.ENTITY_FULLBRIGHT, inputs.overlay(),
					inputs.name());
		}
	}

	@Test
	void twoContractsOverOneFormatDeclareTheSameElementsInTheSameOrder() {
		assertEquals(VertexInputs.TERRAIN.elements(), VertexInputs.TERRAIN_SEPARATE_AO.elements());
		assertEquals(VertexInputs.ENTITY.elements(), VertexInputs.ENTITY_FULLBRIGHT.elements());
	}
}
