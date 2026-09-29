package dev.vitrail.glsl;

import static dev.vitrail.glsl.TranslateSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.GlslLexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link GeometryFold} to its javadoc: a geometry stage that only hands each corner of its
 * triangle on is folded into the fragment stage, and a stage that does anything else is refused
 * with the clause that says what.
 * <p>
 * {@code GeometryFoldTest}, on the branch that reads the shape Bliss writes, holds the loop and the
 * counter; what is pinned here is everything around them: what the fragment stage is left with,
 * every reason to refuse, the rate a fragment stage can answer, and that no text at all makes the
 * fold throw.
 */
class GeometryFoldPinTest {

	private static final String FRAGMENT = """
			#version 460
			in vec4 color;
			layout(location = 0) out vec4 outColor;
			void main() {
				outColor = color;
			}
			""";

	/** A geometry stage of the one shape the fold serves, with the parts a test varies filled in. */
	private static String geometry(String declarations, String before, String loopBody, String after) {
		return """
				layout(triangles) in;
				layout(triangle_strip, max_vertices = 3) out;
				%s
				void main() {
					%s
					for (int i = 0; i < 3; i++) {
						gl_Position = gl_in[i].gl_Position;
						%s
						EmitVertex();
					}
					%s
				}
				""".formatted(declarations, before, loopBody, after);
	}

	private static String canonical() {
		return geometry("in vec4 vs_color[];\nout vec4 color;", "", "color = vs_color[i];", "EndPrimitive();");
	}

	private static GeometryFold.Result fold(String geometry) {
		return GeometryFold.fold(geometry, FRAGMENT);
	}

	private static void refused(GeometryFold.Result result, String clause) {
		assertFalse(result.folded(), "folded: " + result.fragment());
		assertNull(result.fragment());
		assertNotNull(result.refusal());
		assertTrue(result.refusal().contains(clause), "refused as: " + result.refusal());
	}

	@Test
	void resultCarriesTheFragmentOrTheRefusalAndNeverBoth() {
		GeometryFold.Result folded = fold(canonical());
		assertTrue(folded.folded());
		assertNull(folded.refusal());
		assertNotNull(folded.fragment());

		GeometryFold.Result refused = GeometryFold.Result.refused("does not");
		assertFalse(refused.folded());
		assertNull(refused.fragment());
		assertEquals("does not", refused.refusal());
	}

	@Test
	void theFragmentStageDeclaresTheVertexStagesNameAndFillsItsOwnAtTheHeadOfMain() {
		String text = fold(canonical()).fragment();

		assertTrue(text.contains("in vec4 vs_color; vec4 color;"), text);
		assertTrue(text.contains("{ { color = vs_color;"), text);
		// The copy runs before the first statement the pack wrote, which reads the name.
		assertTrue(text.indexOf("color = vs_color;") < text.indexOf("outColor = color;"), text);
		assertTrue(text.contains("#version 460"), text);
		assertTrue(text.contains("layout(location = 0) out vec4 outColor;"), text);
	}

	@Test
	void theFragmentStagesQualifiersStayOnTheDeclarationItKeeps() {
		String fragment = """
				#version 460
				noperspective in vec4 color;
				void main() { }
				""";

		String text = GeometryFold.fold(canonical(), fragment).fragment();

		assertTrue(text.contains("noperspective in vec4 vs_color; vec4 color;"), text);
	}

	@Test
	void anInputNothingCopiesIsStillDeclaredUnderItsOwnNameBecauseTheNumberingCountsNames() {
		String geometry = geometry("in vec4 vs_color[];\nin vec2 vs_uv[];\nout vec4 color;", "",
				"color = vs_color[i];", "EndPrimitive();");

		String text = fold(geometry).fragment();

		assertTrue(text.contains("in vec2 vs_uv;"), text);
		assertEquals(1, count(text, "in vec4 vs_color;"), text);
	}

	@Test
	void anIntegerInputNothingCopiesIsDeclaredFlatWhereverItReachesTheFragmentStage() {
		String geometry = geometry("in vec4 vs_color[];\nin int vs_id[];\nin uvec2 vs_pair[];\nflat in ivec3 vs_cell[];\n"
				+ "out vec4 color;", "", "color = vs_color[i];", "EndPrimitive();");

		String text = fold(geometry).fragment();

		assertTrue(text.contains("flat in int vs_id;"), text);
		assertTrue(text.contains("flat in uvec2 vs_pair;"), text);
		assertEquals(1, count(text, "flat in ivec3 vs_cell;"), text);
		assertFalse(text.contains("flat flat"), text);
	}

	@Test
	void aSizedThreeOrUnsizedInputArrayIsRead() {
		assertTrue(fold(geometry("in vec4 vs_color[3];\nout vec4 color;", "", "color = vs_color[i];",
				"EndPrimitive();")).folded());
		assertTrue(fold(geometry("in vec4 vs_color[];\nout vec4 color;", "", "color = vs_color[i];",
				"EndPrimitive();")).folded());
	}

	@Test
	void anOutputWrittenOnceForTheWholeTriangleBecomesAGlobalSetAtTheHeadOfMain() {
		String geometry = geometry("in vec4 vs_color[];\nout vec4 color;\nflat out int face;", "face = 7;",
				"color = vs_color[i];", "EndPrimitive();");
		String fragment = """
				#version 460
				in vec4 color;
				flat in int face;
				void main() { }
				""";

		String text = GeometryFold.fold(geometry, fragment).fragment();

		assertNotNull(text);
		assertFalse(text.contains("flat in int face;"), text);
		assertTrue(text.contains("int face;"), text);
		assertTrue(text.contains("face = 7;"), text);
	}

	@Test
	void aLoopWithThePreIncrementIsTheSameLoop() {
		String geometry = canonical().replace("i++", "++i");

		assertTrue(fold(geometry).folded());
	}

	// ------------------------------------------------------------------------------ refusals

	@Test
	void refusesAnythingButTrianglesIn() {
		refused(fold(canonical().replace("layout(triangles) in;", "layout(lines) in;")), "triangles");
		refused(fold(canonical().replace("layout(triangles) in;", "")), "triangles");
	}

	@Test
	void refusesAStripOfAnyOtherLengthOrKind() {
		refused(fold(canonical().replace("max_vertices = 3", "max_vertices = 4")), "three corners");
		refused(fold(canonical().replace("triangle_strip", "line_strip")), "three corners");
		refused(fold(canonical().replace("layout(triangle_strip, max_vertices = 3) out;", "")), "three corners");
	}

	@Test
	void refusesAVaryingPlacedByLayout() {
		refused(fold(canonical().replace("in vec4 vs_color[];", "layout(location = 0) in vec4 vs_color[];")),
				"places a varying by layout");
	}

	@Test
	void refusesAStageWithNoMainOrWithAMainOfAnotherShape() {
		refused(fold("layout(triangles) in;\nlayout(triangle_strip, max_vertices = 3) out;\n"), "no main");
		refused(fold(canonical().replace("void main()", "void main(int unused)")), "main in a shape");
		refused(fold(canonical().replace("void main()", "int main()")), "main in a shape");
		refused(fold(canonical() + "void main() { }\n"), "main in a shape");
	}

	@Test
	void refusesALoopThatIsNotOverThreeCornersFromZero() {
		refused(fold(canonical().replace("i < 3", "i < 4")), "loops over something other");
		refused(fold(canonical().replace("int i = 0", "int i = 1")), "loops over something other");
		refused(fold(canonical().replace("i++", "i += 1")), "loops over something other");
		// A block that is not the loop is more than a value for the whole triangle, and a main that
		// works out such a value and then ends never loops at all.
		refused(fold(canonical().replace("for (int i = 0; i < 3; i++) {", "while (true) {")), "does more before its loop");
		refused(fold("layout(triangles) in;\nlayout(triangle_strip, max_vertices = 3) out;\nflat out int face;\n"
				+ "void main() { face = 7; }\n"), "never loops");
	}

	@Test
	void refusesAnythingInTheLoopButThePositionTheCopiesAndEmitVertex() {
		refused(fold(geometry("in vec4 vs_color[];\nout vec4 color;", "", "color = vs_color[i] * 2.0;",
				"EndPrimitive();")), "something other than copy");
		refused(fold(geometry("in vec4 vs_color[];\nout vec4 color;", "", "color = vs_color[i];\nEmitVertex();",
				"EndPrimitive();")), "something other than copy");
		refused(fold(canonical().replace("EmitVertex();", "")), "something other than copy");
	}

	@Test
	void refusesACopyFromAnotherCornerThanTheOneTheLoopIsAt() {
		refused(fold(canonical().replace("vs_color[i]", "vs_color[0]")), "something other than copy");
	}

	@Test
	void refusesAnythingAfterTheLoopButTheEndOfThePrimitive() {
		refused(fold(geometry("in vec4 vs_color[];\nout vec4 color;", "", "color = vs_color[i];",
				"EndPrimitive();\ncolor = vec4(1.0);")), "more after its loop");
		refused(fold(geometry("in vec4 vs_color[];\nout vec4 color;", "", "color = vs_color[i];", "")),
				"more after its loop");
	}

	@Test
	void refusesAnOutputThatIsNeverWritten() {
		refused(fold(geometry("in vec4 vs_color[];\nout vec4 color;\nout vec4 unwritten;", "", "color = vs_color[i];",
				"EndPrimitive();")), "never writes unwritten");
	}

	@Test
	void refusesAnOutputWrittenTwiceOrAnInputReadTwice() {
		refused(fold(geometry("in vec4 vs_color[];\nout vec4 color;", "", "color = vs_color[i];\ncolor = vs_color[i];",
				"EndPrimitive();")), "more than once");
		refused(fold(geometry("in vec4 vs_color[];\nout vec4 color;\nout vec4 other;", "",
				"color = vs_color[i];\nother = vs_color[i];", "EndPrimitive();")), "more than once");
	}

	@Test
	void refusesACopyBetweenVaryingsOfDifferentTypes() {
		refused(fold(geometry("in vec3 vs_color[];\nout vec4 color;", "", "color = vs_color[i];", "EndPrimitive();")),
				"another type");
	}

	@Test
	void refusesAVaryingDeclaredInAShapeItDoesNotRead() {
		refused(fold(geometry("in vec4 vs_color[4];\nout vec4 color;", "", "color = vs_color[i];", "EndPrimitive();")),
				"varying in a shape");
		refused(fold(geometry("in vec4 vs_color;\nout vec4 color;", "", "color = vs_color;", "EndPrimitive();")),
				"varying in a shape");
		refused(fold(geometry("in vec4 vs_color[];\nout vec4 color[2];", "", "color = vs_color[i];", "EndPrimitive();")),
				"varying in a shape");
		refused(fold(geometry("in vec4 vs_color[];\nfoo out vec4 color;", "", "color = vs_color[i];", "EndPrimitive();")),
				"varying in a shape");
	}

	@Test
	void refusesANameDeclaredTwice() {
		refused(fold(geometry("in vec4 vs_color[];\nin vec4 vs_color[];\nout vec4 color;", "", "color = vs_color[i];",
				"EndPrimitive();")), "twice");
	}

	@Test
	void refusesABracketLeftOpen() {
		refused(fold(canonical() + "void broken( {"), "bracket open");
	}

	@Test
	void refusesAnInputNameTheFragmentStageAlreadySpells() {
		String fragment = FRAGMENT.replace("outColor = color;", "outColor = color + vs_color;");

		refused(GeometryFold.fold(canonical(), fragment), "already spells");
	}

	@Test
	void refusesAFragmentStageThatDoesNotDeclareWhatIsHandedOn() {
		refused(GeometryFold.fold(canonical(), "#version 460\nvoid main() { }\n"), "does not declare once");
		refused(GeometryFold.fold(canonical(), FRAGMENT.replace("in vec4 color;", "in vec4 color;\nin vec4 color;")),
				"does not declare once");
	}

	@Test
	void refusesAFragmentStageThatReadsWhatIsHandedOnAsAnotherType() {
		refused(GeometryFold.fold(canonical(), FRAGMENT.replace("in vec4 color;", "in vec3 color;")),
				"a type the fragment stage does not read");
	}

	@Test
	void refusesAFragmentStageWithNoMainOrTwo() {
		refused(GeometryFold.fold(canonical(), "#version 460\nin vec4 color;\n"), "main the fold cannot find");
		refused(GeometryFold.fold(canonical(), FRAGMENT + "void main() { }\n"), "two main");
	}

	// ------------------------------------------------------------------------------------- rate

	private static final String RATE_GEOMETRY = """
			layout(triangles) in;
			layout(triangle_strip, max_vertices = 3) out;
			in vec2 uv[];
			in vec3 pos[];
			flat out vec2 tile;
			void main() {
				vec2 rate = max(max(abs(uv[0] - uv[1]) / distance(pos[0], pos[1]), abs(uv[0] - uv[2]) / distance(pos[0], pos[2])), abs(uv[1] - uv[2]) / distance(pos[1], pos[2]));
				tile = rate;
				for (int i = 0; i < 3; i++) {
					gl_Position = gl_in[i].gl_Position;
					EmitVertex();
				}
				EndPrimitive();
			}
			""";

	private static final String RATE_FRAGMENT = """
			#version 460
			flat in vec2 tile;
			layout(location = 0) out vec4 outColor;
			void main() { outColor = vec4(tile, 0.0, 1.0); }
			""";

	@Test
	void theLargestChangePerLengthOverTheTriangleIsWorkedOutFromThePixelsDerivatives() {
		String text = GeometryFold.fold(RATE_GEOMETRY, RATE_FRAGMENT).fragment();

		assertNotNull(text);
		assertTrue(text.contains("vec2 rate = ofEdgeRate(dFdx(uv), dFdy(uv), dFdx(pos), dFdy(pos));"), text);
		assertTrue(text.contains("tile = rate;"), text);
		assertTrue(text.indexOf("vec2 rate = ofEdgeRate") < text.indexOf("tile = rate;"), text);
		assertTrue(text.contains("vec2 ofEdgeRate(vec2 ofAx, vec2 ofAy, vec3 ofBx, vec3 ofBy) {"), text);
		// A division by nothing is guarded, since a triangle seen edge on has no gradient to speak of.
		assertTrue(text.contains("max(ofXx * ofYy - ofXy * ofXy, 1e-30)"), text);
		// The inputs the rate is measured over are declared, and the one written for the triangle is a global.
		assertTrue(text.contains("in vec2 uv;"), text);
		assertTrue(text.contains("in vec3 pos;"), text);
		assertFalse(text.contains("flat in vec2 tile;"), text);
		assertTrue(text.contains("vec2 tile;"), text);
	}

	@Test
	void theRateHelperIsWrittenOncePerPairOfTypes() {
		String geometry = RATE_GEOMETRY.replace("flat out vec2 tile;", "flat out vec2 tile;\nflat out vec2 second;")
				.replace("tile = rate;", "tile = rate;\nvec2 again = "
						+ "max(max(abs(uv[0] - uv[1]) / distance(pos[0], pos[1]), abs(uv[0] - uv[2]) / distance(pos[0], pos[2])), "
						+ "abs(uv[1] - uv[2]) / distance(pos[1], pos[2]));\nsecond = again;");
		String fragment = RATE_FRAGMENT.replace("flat in vec2 tile;", "flat in vec2 tile;\nflat in vec2 second;");

		String text = GeometryFold.fold(geometry, fragment).fragment();

		assertNotNull(text);
		assertEquals(1, count(text, "vec2 ofEdgeRate(vec2 ofAx"), text);
	}

	@Test
	void refusesARateOverAVaryingThatIsNotInterpolatedPlainly() {
		for (String qualifier : List.of("flat", "noperspective", "centroid")) {
			refused(GeometryFold.fold(RATE_GEOMETRY.replace("in vec2 uv[];", qualifier + " in vec2 uv[];"), RATE_FRAGMENT),
					"pixel cannot");
		}
	}

	@Test
	void refusesARateThatIsNotThreeEdgesEachOnce() {
		String twice = RATE_GEOMETRY.replace("abs(uv[1] - uv[2]) / distance(pos[1], pos[2])",
				"abs(uv[0] - uv[1]) / distance(pos[0], pos[1])");

		refused(GeometryFold.fold(twice, RATE_FRAGMENT), "pixel cannot");
	}

	@Test
	void refusesARateWhoseTypeIsNotTheChangingInputs() {
		refused(GeometryFold.fold(RATE_GEOMETRY.replace("vec2 rate =", "float rate ="), RATE_FRAGMENT), "pixel cannot");
	}

	@Test
	void refusesARateMeasuredOverAVaryingTheFragmentStageReadsFlat() {
		String geometry = RATE_GEOMETRY.replace("in vec2 uv[];", "in vec2 uv[];\nout vec2 uv_out;")
				.replace("EmitVertex();", "uv_out = uv[i];\nEmitVertex();");
		String fragment = RATE_FRAGMENT.replace("flat in vec2 tile;", "flat in vec2 tile;\nflat in vec2 uv_out;");

		refused(GeometryFold.fold(geometry, fragment), "reads flat");
	}

	@Test
	void aUniformReadBeforeTheLoopHasToBeTheSameUniformInTheFragmentStage() {
		String geometry = RATE_GEOMETRY.replace("tile = rate;", "tile = rate * scale;")
				.replace("in vec2 uv[];", "in vec2 uv[];\nuniform float scale;");

		refused(GeometryFold.fold(geometry, RATE_FRAGMENT), "does not declare as the same uniform");
		refused(GeometryFold.fold(geometry, RATE_FRAGMENT.replace("flat in vec2 tile;",
				"flat in vec2 tile;\nuniform int scale;")), "does not declare as the same uniform");

		String same = GeometryFold.fold(geometry, RATE_FRAGMENT.replace("flat in vec2 tile;",
				"flat in vec2 tile;\nuniform float scale;")).fragment();
		assertNotNull(same);
		assertTrue(same.contains("tile = rate * scale;"), same);
	}

	@Test
	void aSamplerOrAFunctionOfItsOwnCannotBeReadBeforeTheLoop() {
		refused(GeometryFold.fold(RATE_GEOMETRY.replace("tile = rate;", "tile = rate * texture(sampler, vec2(0.0)).xy;")
				.replace("in vec2 uv[];", "in vec2 uv[];\nuniform sampler2D sampler;"), RATE_FRAGMENT), "pixel cannot");
		refused(GeometryFold.fold(RATE_GEOMETRY.replace("tile = rate;", "tile = helper(rate);")
				.replace("void main() {", "vec2 helper(vec2 v) { return v; }\nvoid main() {"), RATE_FRAGMENT), "function of its own");
	}

	// ----------------------------------------------------------------------------- ugly input

	@Test
	void everyKindOfEmptyOrBrokenTextIsARefusalAndNeverAnException() {
		String[] texts = {"", " ", "\n", "}", "{", "(", ")", "[", "]", ";", "#", "#version 460", "//", "/*", "\0",
			"layout(", "layout(triangles) in", "void main(", "in", "out", "\u00e9\uD83D", "\r\n\r\n", "for", "}}}{{{"};

		for (String geometry : texts) {
			for (String fragment : texts) {
				GeometryFold.Result result = GeometryFold.fold(geometry, fragment);
				assertTrue(result.folded() ^ (result.refusal() != null), "one of the two: " + geometry + " / " + fragment);
			}
		}
	}

	@Test
	void anyMutationOfAFoldableStageIsAFoldOrARefusalNeverAnException() {
		String[] geometries = {canonical(), RATE_GEOMETRY};
		String[] fragments = {FRAGMENT, RATE_FRAGMENT};
		Random random = new Random(0xF01D);
		int folded = 0;
		int refusals = 0;

		for (int round = 0; round < 3_000; round++) {
			int pick = random.nextInt(2);
			boolean mutateGeometry = random.nextBoolean();
			List<String> tokens = new ArrayList<>();
			for (Token token : GlslLexer.lex(mutateGeometry ? geometries[pick] : fragments[pick])) {
				tokens.add(token.text());
			}

			for (int change = 1 + random.nextInt(3); change > 0; change--) {
				int at = random.nextInt(tokens.size());
				switch (random.nextInt(4)) {
					case 0 -> tokens.remove(at);
					case 1 -> tokens.add(at, tokens.get(random.nextInt(tokens.size())));
					case 2 -> tokens.set(at, tokens.get(random.nextInt(tokens.size())));
					default -> tokens.set(at, new String[] {"(", ")", "{", "}", "[", "]", ";", "in", "out", "3", "0"}[random.nextInt(11)]);
				}

				if (tokens.isEmpty()) {
					tokens.add("x");
				}
			}

			String mutated = String.join("", tokens);
			GeometryFold.Result result = mutateGeometry ? GeometryFold.fold(mutated, fragments[pick])
					: GeometryFold.fold(geometries[pick], mutated);
			assertTrue(result.folded() ^ (result.refusal() != null), "round " + round);
			if (result.folded()) {
				folded++;
			} else {
				refusals++;
			}
		}

		// A mutation that leaves the stage foldable exists, and most do not: both roads were walked.
		assertTrue(folded > 0 && refusals > 0, folded + " folded, " + refusals + " refused");
	}
}
