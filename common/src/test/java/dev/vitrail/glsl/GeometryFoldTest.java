package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Folds a pass-through geometry stage into the fragment stage, which is how a device without
 * geometry shaders draws the program at all.
 * <p>
 * The Bliss shape is the one Eclipse's terrain writes once its interface blocks are flattened: the
 * counter declared ahead of the loop, the corner's position held in a local, and every varying
 * handed on under the name it came in by. The canonical shape is kept beside it so a change for
 * the one cannot quietly cost the other.
 */
class GeometryFoldTest {

	private static final String FRAGMENT = """
			#version 460
			in vec4 of_DATA_color;
			flat in int of_DATA_blockID;
			layout(location = 0) out vec4 outColor;
			void main() {
				outColor = of_DATA_color * float(of_DATA_blockID);
			}
			""";

	private static String blissLoop(String counterDeclared, String between) {
		return """
				layout(triangles) in;
				layout(triangle_strip, max_vertices = 3) out;
				in vec4 of_DATA_color[];
				flat in int of_DATA_blockID[];
				out vec4 of_DATA_color;
				flat out int of_DATA_blockID;
				void main() {
					int %s;
					for (i = 0; i < 3; i++) {
						vec4 vertex = gl_in[i].gl_Position;
						%s
						gl_Position = vertex;
						of_DATA_color = of_DATA_color[i];
						of_DATA_blockID = of_DATA_blockID[i];
						EmitVertex();
					}
					EndPrimitive();
				}
				""".formatted(counterDeclared, between);
	}

	@Test
	void foldsTheBlissLoopWithoutRenamingWhatIsHandedOnUnderItsOwnName() {
		GeometryFold.Result result = GeometryFold.fold(blissLoop("i", ""), FRAGMENT);

		assertTrue(result.folded(), () -> "refused: " + result.refusal());
		String text = result.fragment();
		assertEquals(1, occurrences(text, "vec4 of_DATA_color;"), text);
		assertEquals(1, occurrences(text, "int of_DATA_blockID;"), text);
		assertFalse(text.contains("of_DATA_color = of_DATA_color"), text);
	}

	@Test
	void refusesAPositionChangedOnTheWayThrough() {
		GeometryFold.Result result = GeometryFold.fold(blissLoop("i", "vertex.xy += vec2(1.0);"), FRAGMENT);

		assertFalse(result.folded());
	}

	@Test
	void refusesALoopCountingSomethingElseThanTheCounterDeclared() {
		GeometryFold.Result result = GeometryFold.fold(blissLoop("j", ""), FRAGMENT);

		assertFalse(result.folded());
	}

	@Test
	void stillFoldsTheCanonicalLoopWithACopy() {
		String geometry = """
				layout(triangles) in;
				layout(triangle_strip, max_vertices = 3) out;
				in vec4 vs_color[];
				out vec4 color;
				void main() {
					for (int i = 0; i < 3; i++) {
						gl_Position = gl_in[i].gl_Position;
						color = vs_color[i];
						EmitVertex();
					}
					EndPrimitive();
				}
				""";
		String fragment = """
				#version 460
				in vec4 color;
				layout(location = 0) out vec4 outColor;
				void main() {
					outColor = color;
				}
				""";

		GeometryFold.Result result = GeometryFold.fold(geometry, fragment);

		assertTrue(result.folded(), () -> "refused: " + result.refusal());
		assertTrue(result.fragment().contains("color = vs_color;"), result.fragment());
	}

	private static int occurrences(String text, String part) {
		int count = 0;
		for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
			count++;
		}

		return count;
	}
}
