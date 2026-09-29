package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds the vertex heads the mesh families write to the one rule {@link VertexInputs} states for
 * all of them: a vertex stage declares exactly the elements of the format its pass binds, in the
 * format's own order, and no others.
 * <p>
 * The reason is a numbering nobody can see. {@code IntermediaryShaderModule.rebind} counts only the
 * names a stage declared where the pipeline counts the whole format, so a head that left one out
 * moves the location of every name after it and one that added one is refused whole. Each family is
 * asked for a head that mentions nothing and its {@code in} lines are read back, which is the one
 * place the two lists can be seen to agree.
 */
class VertexHeadsTest {

	private static final Set<String> NOTHING = Set.of();

	private static final Map<String, String> NONE = Map.of();

	/** The {@code in} lines of a head as {@code type name}, in the order they were written. */
	private static List<String> declared(List<String> head) {
		List<String> found = new ArrayList<>();
		for (String line : head) {
			if (line.startsWith("in ") && line.endsWith(";")) {
				found.add(line.substring(3, line.length() - 1));
			}
		}

		return found;
	}

	private static List<String> asGameFormat(List<String> elements) {
		return elements.stream().map(e -> VertexPrologue.elementType(e) + " " + e).toList();
	}

	@Test
	void everyFamilyOverAFormatOfTheGameDeclaresExactlyTheElementsOfItsFormatInOrder() {
		assertEquals(asGameFormat(EntityVertex.ATTRIBUTES), declared(EntityVertex.prologue(NOTHING, NONE, false)));
		assertEquals(asGameFormat(EntityVertex.ATTRIBUTES), declared(EntityVertex.prologue(NOTHING, NONE, true)));
		assertEquals(asGameFormat(GlintVertex.ATTRIBUTES), declared(GlintVertex.prologue(NOTHING, NONE)));
		assertEquals(asGameFormat(ParticleVertex.ATTRIBUTES), declared(ParticleVertex.prologue(NOTHING, NONE)));
		assertEquals(asGameFormat(CrumblingVertex.ATTRIBUTES), declared(CrumblingVertex.prologue(NOTHING, NONE)));
		assertEquals(asGameFormat(MovingBlockVertex.ATTRIBUTES), declared(MovingBlockVertex.prologue(NOTHING, NONE)));
		assertEquals(asGameFormat(LinesVertex.ATTRIBUTES), declared(LinesVertex.prologue(NOTHING, NONE)));
	}

	@Test
	void theVariantOfAFormatDeclaresThePiecesFormatAndNotTheFamilysWhereTheyDiffer() {
		for (List<String> bound : List.of(SkyVertex.ATTRIBUTES, List.of("Position"), List.of("Position", "Color"),
				List.of("UV0", "Position"), List.<String>of())) {
			assertEquals(asGameFormat(bound), declared(SkyVertex.prologue(bound, NOTHING, NONE)), "sky over " + bound);
		}

		for (List<String> bound : List.of(GlyphVertex.WORLD, GlyphVertex.SEE_THROUGH, GlyphVertex.BACKGROUND,
				GlyphVertex.BACKGROUND_SEE_THROUGH)) {
			assertEquals(asGameFormat(bound), declared(GlyphVertex.prologue(bound, NOTHING, NONE)), "glyph over " + bound);
		}
	}

	@Test
	void theChunkMeshDeclaresSodiumsFourAndOnlyTheOfOursThePackAsksFor() {
		List<String> own = List.of("a_Position", "a_Color", "a_TexCoord", "a_LightAndData");

		assertEquals(own, SodiumVertex.carried(Set.of()));
		assertEquals(List.of("a_Position", "a_Color", "a_TexCoord", "a_LightAndData", SodiumVertex.MID_BLOCK, SodiumVertex.TINT_AND_AO),
				SodiumVertex.carried(Set.of(SodiumVertex.TINT_AND_AO, SodiumVertex.MID_BLOCK)));
		assertEquals(SodiumVertex.ATTRIBUTES, SodiumVertex.carried(new LinkedHashSet<>(SodiumVertex.ATTRIBUTES)));
		assertEquals(SodiumVertex.ATTRIBUTES.size(), Set.copyOf(SodiumVertex.ATTRIBUTES).size());
	}

	@Test
	void theChunkHeadDeclaresTheElementsItWasHandedWithTheTypesOfSodiumsPackedBytes() {
		Map<String, String> types = Map.of("a_Position", "uvec2", "a_Color", "vec4", "a_TexCoord", "uvec2",
				"a_LightAndData", "uvec4", SodiumVertex.BLOCK_ID, "uint", SodiumVertex.MID_TEX_COORD, "uvec2",
				SodiumVertex.MID_BLOCK, "ivec4", SodiumVertex.TANGENT_FRAME, "uint", SodiumVertex.TINT_AND_AO, "vec4");

		for (boolean separateAo : new boolean[] {false, true}) {
			List<String> carried = SodiumVertex.ATTRIBUTES;
			List<String> expected = carried.stream().map(a -> types.get(a) + " " + a).toList();

			assertEquals(expected, declared(SodiumVertex.prologue(carried, NOTHING, NONE, separateAo)));
		}

		List<String> some = SodiumVertex.carried(Set.of(SodiumVertex.BLOCK_ID));
		assertEquals(some.stream().map(a -> types.get(a) + " " + a).toList(),
				declared(SodiumVertex.prologue(some, NOTHING, NONE, false)));
	}

	@Test
	void theChunkMeshReadsAnElementOnlyForANameThePackMentionsOrDeclared() {
		assertEquals(Set.of(), SodiumVertex.reads(NOTHING, NONE, false));
		assertEquals(Set.of(SodiumVertex.BLOCK_ID), SodiumVertex.reads(Set.of("mc_Entity"), NONE, false));
		assertEquals(Set.of(SodiumVertex.MID_TEX_COORD), SodiumVertex.reads(Set.of("mc_midTexCoord"), NONE, false));
		assertEquals(Set.of(SodiumVertex.MID_BLOCK), SodiumVertex.reads(Set.of("at_midBlock"), NONE, false));
		assertEquals(Set.of(SodiumVertex.TANGENT_FRAME), SodiumVertex.reads(Set.of("at_tangent"), NONE, false));
		// A pack declaring the name for itself asks for it as surely as one that only reads it.
		assertEquals(Set.of(SodiumVertex.BLOCK_ID), SodiumVertex.reads(NOTHING, Map.of("mc_Entity", "vec2"), false));
		// The name gl_Normal became reads the same element the tangent does: one word, paid for once.
		assertEquals(Set.of(SodiumVertex.TANGENT_FRAME), SodiumVertex.reads(Set.of("of_Normal"), NONE, false));
		assertEquals(Set.of(SodiumVertex.TANGENT_FRAME), SodiumVertex.reads(Set.of("of_Normal", "at_tangent"), NONE, false));
		// The second colour is the directive's and not the body's.
		assertEquals(Set.of(SodiumVertex.TINT_AND_AO), SodiumVertex.reads(NOTHING, NONE, true));
		assertEquals(Set.of(), SodiumVertex.reads(Set.of("gl_Position", "texcoord"), NONE, false));
	}

	@Test
	void theNamesTheChunkMeshAnswersAreFourInTheOrderTheyAreWrittenAndCannotBeChanged() {
		assertEquals(List.of("mc_Entity", "mc_midTexCoord", "at_midBlock", "at_tangent"), List.copyOf(SodiumVertex.ANSWERED));
		assertThrows(UnsupportedOperationException.class, () -> SodiumVertex.ANSWERED.add("x"));
	}

	@Test
	void theDistantMeshAlwaysCarriesItsPositionAndMetaAndTheRestOnlyForTheNamesThatAskForThem() {
		List<String> both = List.of(DistantVertex.POSITION, DistantVertex.META);

		assertEquals(both, DistantVertex.carried(DistantVertex.reads(NOTHING, NONE)));
		assertEquals(List.of(DistantVertex.POSITION, DistantVertex.META, DistantVertex.COLOUR),
				DistantVertex.carried(DistantVertex.reads(Set.of("of_Color"), NONE)));
		assertEquals(List.of(DistantVertex.POSITION, DistantVertex.META, DistantVertex.NORMAL),
				DistantVertex.carried(DistantVertex.reads(Set.of("of_Normal"), NONE)));
		assertEquals(List.of(DistantVertex.POSITION, DistantVertex.META, DistantVertex.MATERIAL),
				DistantVertex.carried(DistantVertex.reads(Set.of("dhMaterialId"), NONE)));
		assertEquals(List.of(DistantVertex.POSITION, DistantVertex.META, DistantVertex.MATERIAL),
				DistantVertex.carried(DistantVertex.reads(NOTHING, Map.of("dhMaterialId", "int"))));
		// However the names arrive, the format is written in the mesh's own order.
		assertEquals(DistantVertex.ATTRIBUTES, DistantVertex.carried(DistantVertex.reads(
				Set.of("of_Normal", "dhMaterialId", "of_Color"), NONE)));
	}

	@Test
	void theDistantHeadDeclaresTheElementsItWasHandedWithTheTypesOfDhsSixteenBytes() {
		Map<String, String> types = Map.of(DistantVertex.POSITION, "uvec3", DistantVertex.META, "uint",
				DistantVertex.COLOUR, "vec4", DistantVertex.MATERIAL, "uint", DistantVertex.NORMAL, "uint");

		for (List<String> carried : List.of(DistantVertex.ATTRIBUTES, List.of(DistantVertex.POSITION, DistantVertex.META))) {
			assertEquals(carried.stream().map(a -> types.get(a) + " " + a).toList(),
					declared(DistantVertex.prologue(carried, NOTHING, NONE)));
		}
	}

	@Test
	void theCloudsBindNoFormatSoTheirHeadDeclaresNoInputAtAll() {
		List<String> head = CloudVertex.prologue(NOTHING, NONE);

		assertEquals(List.of(), declared(head));
		assertTrue(head.contains("uniform isamplerBuffer CloudFaces;"), head.toString());
		assertTrue(head.contains("layout(std140) uniform CloudInfo {"), head.toString());
	}

	@Test
	void everyHeadIsAnUnmodifiableList() {
		assertThrows(UnsupportedOperationException.class, () -> EntityVertex.prologue(NOTHING, NONE, false).add("x"));
		assertThrows(UnsupportedOperationException.class, () -> GlintVertex.prologue(NOTHING, NONE).add("x"));
		assertThrows(UnsupportedOperationException.class, () -> CloudVertex.prologue(NOTHING, NONE).add("x"));
	}

	// ------------------------------------------------------------------------------- entity

	@Test
	void anEntitysLightMapNamesAreTheBrightestValueOnlyWhereThePieceIsDrawnAtFullLight() {
		List<String> lit = EntityVertex.prologue(NOTHING, NONE, false);
		List<String> full = EntityVertex.prologue(NOTHING, NONE, true);

		assertTrue(lit.contains("#define of_MultiTexCoord1 vec4(UV2, 0.0, 1.0)"), lit.toString());
		assertTrue(lit.contains("#define of_MultiTexCoord2 vec4(UV2, 0.0, 1.0)"), lit.toString());
		assertTrue(full.contains("#define of_MultiTexCoord1 " + EntityVertex.FULL_LIGHT), full.toString());
		assertTrue(full.contains("#define of_MultiTexCoord2 " + EntityVertex.FULL_LIGHT), full.toString());
		assertEquals("vec4(240.0, 240.0, 0.0, 1.0)", EntityVertex.FULL_LIGHT);
		// Nothing else differs between the two: same elements, same length.
		assertEquals(lit.size(), full.size());
	}

	@Test
	void anEntityAnswersTheTwoNamesItsMeshCarriesWithMacrosAndTheOthersWithConstantsAndOnlyWhenUsed() {
		List<String> quiet = EntityVertex.prologue(NOTHING, NONE, false);
		assertFalse(String.join("\n", quiet).contains("mc_Entity"));
		assertFalse(String.join("\n", quiet).contains("at_tangent"));

		List<String> asked = EntityVertex.prologue(Set.of("mc_Entity", "mc_midTexCoord", "at_tangent"), NONE, false);
		assertTrue(asked.contains("vec4 mc_Entity = vec4(0.0);"), asked.toString());
		assertTrue(asked.contains("#define mc_midTexCoord vec4(MidTexCoord, 0.0, 1.0)"), asked.toString());
		assertTrue(asked.contains("#define at_tangent Tangent"), asked.toString());

		// The pack's own type for a name is the shape it is answered in.
		List<String> typed = EntityVertex.prologue(NOTHING, Map.of("mc_midTexCoord", "vec2", "at_tangent", "vec3"), false);
		assertTrue(typed.contains("#define mc_midTexCoord MidTexCoord"), typed.toString());
		assertTrue(typed.contains("#define at_tangent Tangent.xyz"), typed.toString());
	}

	@Test
	void theMidTexCoordOfAnEntityIsTheElementsPairWidenedToWhatThePackDeclared() {
		assertEquals("MidTexCoord.x", EntityVertex.midTexCoord("float"));
		assertEquals("MidTexCoord", EntityVertex.midTexCoord("vec2"));
		assertEquals("vec3(MidTexCoord, 0.0)", EntityVertex.midTexCoord("vec3"));
		assertEquals("vec4(MidTexCoord, 0.0, 1.0)", EntityVertex.midTexCoord("vec4"));
		assertEquals("ivec2(0)", EntityVertex.midTexCoord("ivec2"));
		assertEquals("mat3(0.0)", EntityVertex.midTexCoord("mat3"));
	}

	@Test
	void theTangentOfAnEntityIsTheElementsFourNarrowedToWhatThePackDeclared() {
		assertEquals("Tangent.x", EntityVertex.tangent("float"));
		assertEquals("Tangent.xy", EntityVertex.tangent("vec2"));
		assertEquals("Tangent.xyz", EntityVertex.tangent("vec3"));
		assertEquals("Tangent", EntityVertex.tangent("vec4"));
		assertEquals("uvec4(0u)", EntityVertex.tangent("uvec4"));
	}
}
