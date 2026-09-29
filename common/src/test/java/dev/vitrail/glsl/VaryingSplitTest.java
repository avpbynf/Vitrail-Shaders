package dev.vitrail.glsl;

import static dev.vitrail.glsl.TranslateSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.ProgramStage;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link VaryingSplit} to what its javadoc promises, through the translator, which is its
 * only way in: a matrix, a struct or an array a stage hands on is taken apart into one varying per
 * column, member or element, and rebuilt as a local around {@code main}.
 * <p>
 * The reason is a numbering. The game numbers a varying by the rank of its reflected name with no
 * stride, so a name that fills several locations has the next one numbered onto its second. What
 * the tests check is therefore the names the interface ends up with and what is left alone, and
 * they never compare a whole translated text, which carries the define table of the machine.
 */
class VaryingSplitTest {

	private static String fragment(String source, int... dead) {
		return TranslateSupport.translate(ProgramStage.FRAGMENT, source, dead).text();
	}

	private static String vertex(String source) {
		return TranslateSupport.translate(ProgramStage.VERTEX, source).text();
	}

	private static final String READS_NOTHING = "void main() { gl_FragData[0] = vec4(1.0); }\n";

	@Test
	void namesAreThePrefixTheNameAndTheIndexOfWhatTheyStandFor() {
		assertEquals("of_vmat_tbn_2", VaryingSplit.matrixColumnName("tbn", 2));
		assertEquals("of_vstruct_fog_a", VaryingSplit.structMemberName("fog", "a"));
		assertEquals("of_varr_sky_sh_8", VaryingSplit.arrayElementName("sky_sh", 8));
	}

	@Test
	void aMatrixInBecomesOneVectorPerColumnRebuiltAtTheHeadOfMain() {
		String text = fragment("""
				#version 120
				varying mat3 tbn;
				void main() { gl_FragData[0] = vec4(tbn * vec3(0.0, 0.0, 1.0), 1.0); }
				""");

		assertTrue(text.contains("in vec3 of_vmat_tbn_0;"), text);
		assertTrue(text.contains("in vec3 of_vmat_tbn_1;"), text);
		assertTrue(text.contains("in vec3 of_vmat_tbn_2;"), text);
		assertFalse(text.contains("of_vmat_tbn_3"), text);
		assertFalse(text.contains("varying"), text);
		// The pack's own name is a local the body still reads and writes.
		assertTrue(text.contains("mat3 tbn;"), text);
		assertTrue(text.contains("tbn = mat3(of_vmat_tbn_0, of_vmat_tbn_1, of_vmat_tbn_2);"), text);
		assertTrue(text.indexOf("tbn = mat3(of_vmat") > text.indexOf("void ofPackMain()"), text);
	}

	@Test
	void aMatrixOutIsCopiedOntoItsColumnsAfterTheBodyRan() {
		String text = vertex("""
				#version 120
				varying mat3 tbn;
				void main() { gl_Position = vec4(1.0); tbn = mat3(1.0); }
				""");

		assertTrue(text.contains("out vec3 of_vmat_tbn_0;"), text);
		assertTrue(text.contains("out vec3 of_vmat_tbn_2;"), text);

		int call = text.indexOf("ofPackMain();");
		assertTrue(call > 0, text);
		for (int column = 0; column < 3; column++) {
			int copy = text.indexOf("of_vmat_tbn_" + column + " = tbn[" + column + "];");
			assertTrue(copy > call, "column " + column + " in " + text);
		}
	}

	@Test
	void aNonSquareMatrixHasAsManyColumnsAsItsFirstNumberAndTheirLengthIsTheSecond() {
		// GLSL names a matrix by columns, then rows: mat2x3 is two columns of three.
		String text = fragment("""
				#version 330
				in mat2x3 two_by_three;
				in mat3x2 three_by_two;
				in mat4 four;
				""" + READS_NOTHING);

		assertEquals(2, count(text, "in vec3 of_vmat_two_by_three_"), text);
		assertEquals(3, count(text, "in vec2 of_vmat_three_by_two_"), text);
		assertEquals(4, count(text, "in vec4 of_vmat_four_"), text);
		assertTrue(text.contains("two_by_three = mat2x3(of_vmat_two_by_three_0, of_vmat_two_by_three_1);"), text);
	}

	@Test
	void aDoubleMatrixIsSplitIntoDoubleVectors() {
		String text = fragment("""
				#version 400
				in dmat2 wide;
				""" + READS_NOTHING);

		assertEquals(2, count(text, "in dvec2 of_vmat_wide_"), text);
	}

	@Test
	void anArrayOfMatricesIsLeftAsTheDeclarationItIs() {
		String text = fragment("""
				#version 330
				in mat3 many[2];
				""" + READS_NOTHING);

		assertTrue(text.contains("in mat3 many[2];"), text);
		assertFalse(text.contains("of_vmat_many"), text);
	}

	@Test
	void aMatrixAttributeOfAVertexStageIsNotAVaryingAndIsNotSplit() {
		String text = vertex("""
				#version 330
				in mat3 attribute_matrix;
				void main() { gl_Position = vec4(attribute_matrix[0], 1.0); }
				""");

		assertFalse(text.contains("of_vmat_attribute_matrix"), text);
	}

	@Test
	void aLineTheExpanderLeftDeadIsNotReadAsADeclaration() {
		// Line one, counted from zero, is the first declaration: the version is line zero.
		String text = fragment("""
				#version 330
				in mat3 dead_matrix;
				in mat2 live_matrix;
				""" + READS_NOTHING, 1);

		assertFalse(text.contains("of_vmat_dead_matrix"), text);
		assertTrue(text.contains("of_vmat_live_matrix_1"), text);
	}

	@Test
	void aStructVaryingIsSplitPerMemberKeepingItsQualifierAndTheDefinitionMovesToTheHeader() {
		String text = fragment("""
				#version 330
				struct Fog { vec3 near; vec3 far; float density; };
				flat in Fog fog_params;
				void main() { gl_FragData[0] = vec4(fog_params.near + fog_params.far, fog_params.density); }
				""");

		assertTrue(text.contains("flat in vec3 of_vstruct_fog_params_near;"), text);
		assertTrue(text.contains("flat in vec3 of_vstruct_fog_params_far;"), text);
		assertTrue(text.contains("flat in float of_vstruct_fog_params_density;"), text);
		assertTrue(text.contains("fog_params = Fog(of_vstruct_fog_params_near, of_vstruct_fog_params_far, "
				+ "of_vstruct_fog_params_density);"), text);
		// The wrapper is in the header, above the body, and so is the type it builds.
		assertEquals(1, count(text, "struct Fog"), text);
		assertTrue(text.indexOf("struct Fog") < text.indexOf("void ofPackMain()"), text);
		assertTrue(text.contains("Fog fog_params;"), text);
	}

	@Test
	void aStructWithAnythingButScalarsAndVectorsIsLeftAlone() {
		String text = fragment("""
				#version 330
				struct WithArray { vec3 a; vec3 b[2]; };
				struct WithMatrix { vec3 a; mat3 m; };
				in WithArray with_array;
				in WithMatrix with_matrix;
				""" + READS_NOTHING);

		assertFalse(text.contains("of_vstruct_"), text);
		assertTrue(text.contains("in WithArray with_array;"), text);
		assertTrue(text.contains("in WithMatrix with_matrix;"), text);
	}

	@Test
	void aStructDefinitionThatDeclaresAnInstanceInTheSameBreathIsNotTakenOutOfTheBody() {
		String text = fragment("""
				#version 330
				struct Named { vec3 a; } instance;
				in Named varying_named;
				""" + READS_NOTHING);

		assertFalse(text.contains("of_vstruct_"), text);
		assertTrue(text.contains("struct Named { vec3 a; } instance;"), text);
	}

	@Test
	void anArrayVaryingIsSplitPerElementAndFilledBeforeMainRuns() {
		String text = fragment("""
				#version 330
				flat in vec3 sky_sh[3];
				void main() { gl_FragData[0] = vec4(sky_sh[1], 1.0); }
				""");

		for (int element = 0; element < 3; element++) {
			assertTrue(text.contains("flat in vec3 of_varr_sky_sh_" + element + ";"), text);
			assertTrue(text.contains("sky_sh[" + element + "] = of_varr_sky_sh_" + element + ";"), text);
		}

		assertFalse(text.contains("of_varr_sky_sh_3"), text);
		assertTrue(text.contains("vec3 sky_sh[3];"), text);
	}

	@Test
	void anArrayOutOfAVertexStageIsCopiedElementByElementAfterTheBody() {
		String text = vertex("""
				#version 330
				out vec3 sky_sh[2];
				void main() { gl_Position = vec4(1.0); sky_sh[0] = vec3(1.0); sky_sh[1] = vec3(2.0); }
				""");

		int call = text.indexOf("ofPackMain();");
		assertTrue(call > 0, text);
		assertTrue(text.indexOf("of_varr_sky_sh_0 = sky_sh[0];") > call, text);
		assertTrue(text.indexOf("of_varr_sky_sh_1 = sky_sh[1];") > call, text);
	}

	@Test
	void anArrayIsSplitOnlyWhereItsSizeIsANumberOfOneToNineHundredNinetyNine() {
		String text = fragment("""
				#version 330
				in vec3 by_macro[N];
				in float none[0];
				in float huge[1000];
				in float edge[999];
				in float one[1];
				""" + READS_NOTHING);

		assertTrue(text.contains("in vec3 by_macro[N];"), text);
		assertTrue(text.contains("in float none[0];"), text);
		assertTrue(text.contains("in float huge[1000];"), text);
		assertFalse(text.contains("of_varr_by_macro"), text);
		assertFalse(text.contains("of_varr_none"), text);
		assertFalse(text.contains("of_varr_huge"), text);
		assertEquals(999, count(text, "in float of_varr_edge_"), "the last of nine hundred and ninety nine");
		assertTrue(text.contains("in float of_varr_edge_998;"));
		assertEquals(1, count(text, "in float of_varr_one_"), text);
	}

	@Test
	void aStatementDeclaringAnArrayBesideAPlainNameIsLeftWhole() {
		String text = fragment("""
				#version 330
				in vec3 pair[2], single;
				""" + READS_NOTHING);

		assertTrue(text.contains("in vec3 pair[2], single;"), text);
		assertFalse(text.contains("of_varr_pair"), text);
	}

	@Test
	void anArrayOfAnythingButScalarsAndVectorsIsNotSplitAsAnArray() {
		String text = fragment("""
				#version 330
				in mat2 columns[2];
				""" + READS_NOTHING);

		assertFalse(text.contains("of_varr_"), text);
	}

	@Test
	void aVertexStageThatWritesWhatTheFragmentStageNeverReadsCostsNoColumnsThere() {
		Map<ProgramStage, String> stages = new LinkedHashMap<>();
		stages.put(ProgramStage.VERTEX, """
				#version 120
				varying vec2 texcoord;
				void main() { gl_Position = gl_Vertex; texcoord = gl_MultiTexCoord0.st; }
				""");
		// Sildur's tbnMatrix: declared without a condition in the fragment stage, written by the vertex
		// stage only behind a switch that is off. Nothing writes it, and nothing here reads it.
		stages.put(ProgramStage.FRAGMENT, """
				#version 120
				varying vec2 texcoord;
				varying mat3 tbnMatrix;
				void main() { gl_FragData[0] = vec4(texcoord, 0.0, 1.0); }
				""");

		ProgramTranslator.TranslatedProgram program = TranslateSupport.program(stages, Map.of());

		assertFalse(program.stages().get(ProgramStage.FRAGMENT).text().contains("of_vmat_tbnMatrix"),
				program.stages().get(ProgramStage.FRAGMENT).text());
	}

	@Test
	void bothStagesOfAProgramAgreeOnTheColumnsOfAMatrixTheyHandOver() {
		Map<ProgramStage, String> stages = new LinkedHashMap<>();
		stages.put(ProgramStage.VERTEX, """
				#version 120
				varying mat3 tbnMatrix;
				void main() { gl_Position = gl_Vertex; tbnMatrix = mat3(1.0); }
				""");
		stages.put(ProgramStage.FRAGMENT, """
				#version 120
				varying mat3 tbnMatrix;
				void main() { gl_FragData[0] = vec4(tbnMatrix[2], 1.0); }
				""");

		ProgramTranslator.TranslatedProgram program = TranslateSupport.program(stages, Map.of());
		String vertex = program.stages().get(ProgramStage.VERTEX).text();
		String fragment = program.stages().get(ProgramStage.FRAGMENT).text();

		for (int column = 0; column < 3; column++) {
			assertTrue(vertex.contains("out vec3 of_vmat_tbnMatrix_" + column + ";"), vertex);
			assertTrue(fragment.contains("in vec3 of_vmat_tbnMatrix_" + column + ";"), fragment);
		}
	}
}
