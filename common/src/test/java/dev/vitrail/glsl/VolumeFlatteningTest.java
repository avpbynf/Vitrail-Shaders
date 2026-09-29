package dev.vitrail.glsl;

import static dev.vitrail.glsl.TranslateSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.PackTexture;
import dev.vitrail.pack.model.PixelFormat;
import dev.vitrail.pack.model.PixelType;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link VolumeFlattening} to its javadoc: a volume the pack ships is declared flat and every
 * read of it goes through a helper, or, where any read cannot be moved, nothing of it is moved and
 * the count says so.
 * <p>
 * The helper is checked against numbers worked out by hand from {@link VolumeAtlas}'s layout, whose
 * class comment gives the one that matters: one texel of gutter on each side of every tile, tiles
 * laid out as square as they go. The rewrite is driven through the program translator, which is the
 * only way to hand the pass the volumes.
 */
class VolumeFlatteningTest {

	private static VolumeAtlas atlas(int size, boolean clamp) {
		return VolumeAtlas.of(new PackTexture.Raw(PackTexture.Shape.TEXTURE_3D, null, size, size, size,
				PixelFormat.RGBA, PixelType.UNSIGNED_BYTE), clamp);
	}

	private static TranslatedUnit fragment(String source, Map<String, VolumeAtlas> volumes) {
		ProgramTranslator.TranslatedProgram program =
				TranslateSupport.program(Map.of(ProgramStage.FRAGMENT, source), volumes);

		return program.stages().get(ProgramStage.FRAGMENT);
	}

	private static final Map<String, VolumeAtlas> NOISE = Map.of("noisetex", atlas(8, false));

	private static final String HEAD = """
			#version 120
			uniform sampler3D noisetex;
			uniform sampler2D colortex0;
			varying vec2 texcoord;
			""";

	@Test
	void theHelperOfAVolumeThatRepeatsIsWorkedOutFromItsLayout() {
		// Eight slices of eight by eight: three tiles to a row, three rows, a tile of ten with its
		// gutter and an atlas of thirty. Three, because the smallest square holding eight is nine.
		List<String> lines = VolumeFlattening.helper("noisetex", atlas(8, false));

		assertEquals("vec4 ofTexture3D_noisetex(sampler2D ofMap, vec3 ofAt) {", lines.get(0));
		assertEquals("\tvec3 ofQ = fract(ofAt);", lines.get(1));
		assertEquals("\tfloat ofZ = ofQ.z * 8.0 - 0.5;", lines.get(2));
		assertEquals("\tvec2 ofIn = ofQ.xy * vec2(8.0, 8.0) + 1.0;", lines.get(4));
		assertEquals("\tint ofNear = int(mod(ofBase, 8.0));", lines.get(5));
		assertEquals("\tint ofFar = int(mod(ofBase + 1.0, 8.0));", lines.get(6));
		assertEquals("\tvec2 ofTile = vec2(10.0, 10.0);", lines.get(7));
		assertEquals("\tvec2 ofSize = vec2(30.0, 30.0);", lines.get(8));
		assertEquals("\tvec2 ofA = (vec2(ofNear % 3, ofNear / 3) * ofTile + ofIn) / ofSize;", lines.get(9));
		assertEquals("\tvec2 ofB = (vec2(ofFar % 3, ofFar / 3) * ofTile + ofIn) / ofSize;", lines.get(10));
	}

	@Test
	void theHelperOfAVolumeThatClampsClampsTheCoordinateAndTheSliceIndex() {
		List<String> lines = VolumeFlattening.helper("noisetex", atlas(8, true));

		assertEquals("\tvec3 ofQ = clamp(ofAt, 0.0, 1.0);", lines.get(1));
		assertEquals("\tint ofNear = clamp(int(ofBase), 0, 7);", lines.get(5));
		assertEquals("\tint ofFar = clamp(int(ofBase) + 1, 0, 7);", lines.get(6));
	}

	@Test
	void theHelperHasALevelledTwinThatReadsTheSameWhateverTheLevelSays() {
		List<String> lines = VolumeFlattening.helper("noisetex", atlas(8, false));

		assertEquals(lines.size() - 3, lines.indexOf("}") + 1);
		assertEquals("vec4 ofTexture3D_noisetex(sampler2D ofMap, vec3 ofAt, float ofLevel) {",
				lines.get(lines.size() - 3));
		assertEquals("\treturn ofTexture3D_noisetex(ofMap, ofAt);", lines.get(lines.size() - 2));
		assertEquals("}", lines.getLast());
	}

	@Test
	void everyReadOfAVolumeGoesThroughTheHelperAndTheDeclarationIsFlat() {
		TranslatedUnit unit = fragment(HEAD + """
				#define NOISE noisetex
				void main() {
					vec4 a = texture3D(noisetex, vec3(texcoord, 0.5));
					vec4 b = texture(NOISE, vec3(texcoord, 0.25));
					vec4 c = textureLod(noisetex, vec3(texcoord, 0.75), 0.0);
					gl_FragData[0] = a + b + c + texture2D(colortex0, texcoord);
				}
				""", NOISE);
		String text = unit.text();

		assertTrue(text.contains("uniform sampler2D ofPackTexture_noisetex;"), text);
		assertFalse(text.contains("sampler3D"), text);
		assertTrue(text.contains("ofTexture3D_noisetex(ofPackTexture_noisetex, vec3(texcoord, 0.5))"), text);
		assertTrue(text.contains("ofTexture3D_noisetex(ofPackTexture_noisetex, vec3(texcoord, 0.25))"), text);
		// The levelled read keeps its third argument for the overload that drops it.
		assertTrue(text.contains("ofTexture3D_noisetex(ofPackTexture_noisetex, vec3(texcoord, 0.75), 0.0)"), text);
		assertEquals(3, unit.notes().volumeLookups());
		assertEquals(0, unit.notes().volumesLeftAlone());
		assertTrue(unit.samplers().stream().anyMatch(s -> s.name().equals("ofPackTexture_noisetex")), unit.samplers().toString());
		assertFalse(unit.samplers().stream().anyMatch(s -> s.name().equals("noisetex")), unit.samplers().toString());
	}

	@Test
	void theHelperIsWrittenOnceIntoTheHeaderHoweverManyTimesTheVolumeIsRead() {
		String text = fragment(HEAD + """
				void main() {
					vec4 a = texture3D(noisetex, vec3(texcoord, 0.5));
					vec4 b = texture3D(noisetex, vec3(texcoord, 0.6));
					gl_FragData[0] = a + b;
				}
				""", NOISE).text();

		assertEquals(1, count(text, "vec4 ofTexture3D_noisetex(sampler2D ofMap, vec3 ofAt) {"), text);
		assertEquals(1, count(text, "vec4 ofTexture3D_noisetex(sampler2D ofMap, vec3 ofAt, float ofLevel) {"), text);
		assertTrue(text.indexOf("vec4 ofTexture3D_noisetex(sampler2D ofMap, vec3 ofAt) {") < text.indexOf("void main()"), text);
	}

	@Test
	void aVolumeReadAnyOtherWayIsLeftWhollyAsItWasAndCounted() {
		TranslatedUnit unit = fragment(HEAD + """
				void main() {
					vec4 a = texture3D(noisetex, vec3(texcoord, 0.5));
					vec3 size = vec3(textureSize(noisetex, 0));
					gl_FragData[0] = a + vec4(size, 1.0);
				}
				""", NOISE);

		assertEquals(1, unit.notes().volumesLeftAlone());
		assertEquals(0, unit.notes().volumeLookups());
		assertTrue(unit.text().contains("uniform sampler3D noisetex;"), unit.text());
		assertFalse(unit.text().contains("ofTexture3D_"), unit.text());
		assertFalse(unit.text().contains("ofPackTexture_noisetex"), unit.text());
	}

	@Test
	void aReadWithAnOffsetIsNotAFlatReadAndLeavesTheVolumeAlone() {
		TranslatedUnit unit = fragment(HEAD + """
				void main() {
					gl_FragData[0] = textureLod(noisetex, vec3(texcoord, 0.5), 0.0, 1.0);
				}
				""", NOISE);

		assertEquals(1, unit.notes().volumesLeftAlone());
		assertTrue(unit.text().contains("uniform sampler3D noisetex;"), unit.text());
	}

	@Test
	void aVolumeDeclaredAndNeverReadIsStillDeclaredFlatSoTheProgramIsNotRefusedForIt() {
		TranslatedUnit unit = fragment(HEAD + """
				void main() { gl_FragData[0] = texture2D(colortex0, texcoord); }
				""", NOISE);

		assertTrue(unit.text().contains("uniform sampler2D ofPackTexture_noisetex;"), unit.text());
		assertFalse(unit.text().contains("ofTexture3D_"), "a helper nothing calls is not written");
		assertEquals(0, unit.notes().volumeLookups());
		assertEquals(0, unit.notes().volumesLeftAlone());
	}

	@Test
	void aVolumeThePackDoesNotShipIsNotTouched() {
		TranslatedUnit unit = fragment(HEAD + """
				void main() { gl_FragData[0] = texture3D(noisetex, vec3(texcoord, 0.5)); }
				""", Map.of());

		assertTrue(unit.text().contains("uniform sampler3D noisetex;"), unit.text());
		assertEquals(0, unit.notes().volumeLookups());
		assertEquals(0, unit.notes().volumesLeftAlone());
	}

	@Test
	void aNameReadWithoutBeingDeclaredHereIsNotRenamedAgainstNothing() {
		TranslatedUnit unit = fragment("""
				#version 120
				varying vec2 texcoord;
				void main() { gl_FragData[0] = texture3D(noisetex, vec3(texcoord, 0.5)); }
				""", NOISE);

		assertFalse(unit.text().contains("ofTexture3D_"), unit.text());
		assertEquals(0, unit.notes().volumeLookups());
		assertEquals(0, unit.notes().volumesLeftAlone());
	}

	@Test
	void aReadOnADeadLineIsNotAReadTheCompilerWouldSee() {
		ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(
				Map.of(ProgramStage.FRAGMENT, TranslateSupport.unit("test.fsh", HEAD + """
						void main() {
							vec4 a = texture3D(noisetex, vec3(texcoord, 0.5));
							vec3 size = vec3(textureSize(noisetex, 0));
							gl_FragData[0] = a;
						}
						""", 6)), VertexInputs.FULLSCREEN, List.of("Position", "UV0"),
				AlphaTest.OFF, false, "composite", NOISE);
		TranslatedUnit unit = program.stages().get(ProgramStage.FRAGMENT);

		// Line six, counted from zero, is the textureSize one: the head fills lines zero to three and
		// main opens on four.
		assertEquals(0, unit.notes().volumesLeftAlone());
		assertEquals(1, unit.notes().volumeLookups());
	}

	@Test
	void everyStageThatCarriesTheDeclarationIsRewrittenNotOnlyTheOneThatReads() {
		Map<ProgramStage, String> stages = new LinkedHashMap<>();
		stages.put(ProgramStage.VERTEX, """
				#version 120
				uniform sampler3D noisetex;
				varying vec2 texcoord;
				void main() { gl_Position = gl_Vertex; texcoord = gl_MultiTexCoord0.st; }
				""");
		stages.put(ProgramStage.FRAGMENT, HEAD + """
				void main() { gl_FragData[0] = texture3D(noisetex, vec3(texcoord, 0.5)); }
				""");

		ProgramTranslator.TranslatedProgram program = TranslateSupport.program(stages, NOISE);

		assertTrue(program.stages().get(ProgramStage.VERTEX).text().contains("ofPackTexture_noisetex"),
				program.stages().get(ProgramStage.VERTEX).text());
		assertFalse(program.stages().get(ProgramStage.VERTEX).text().contains("sampler3D"));
		assertFalse(program.stages().get(ProgramStage.FRAGMENT).text().contains("sampler3D"));
	}
}
