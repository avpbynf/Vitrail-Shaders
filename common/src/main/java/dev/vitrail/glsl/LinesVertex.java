package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the mesh the game's lines are drawn from carries, the block outline first among them, and
 * how the names a pack reads are made out of it.
 * <p>
 * {@code DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH} holds four elements: {@code
 * Position} as three floats, {@code Color} as four normalised bytes, {@code Normal} as four
 * normalised bytes of which three are read, {@code LineWidth} as one float. The game emits every
 * edge as two vertices carrying the same normal, which is the edge's own direction, writes each of
 * them twice into the buffer ({@code BufferBuilder.endLastVertex} under the lines topology) and
 * indexes every four as two triangles ({@code PrimitiveTopology.indexCount}), so an edge reaches
 * the vertex stage as a degenerate quad. Its {@code rendertype_lines} stage opens it: each vertex
 * is pushed off the line by half the width, one way for an even vertex and the other for an odd
 * one, along the screen normal of the edge's projection ({@code core/rendertype_lines.vsh}). The
 * mesh itself is thin; the width is made in the vertex stage or it is not made at all.
 * <p>
 * <strong>Iris keeps that widening and puts it AROUND the pack's own main</strong>
 * ({@code transform/transformer/VanillaTransformer.java:188-222}): the pack's main runs twice,
 * once with the edge's direction added to the position, which projects the far end, and once
 * without, which projects the vertex itself, and the two clip positions are widened the way the
 * game's stage widens them, short of the 255/256 shrink towards the eye the game's stage applies
 * to both before projecting them, which Iris leaves to the pack and so does this. {@code
 * gl_Normal} is answered with the constant a mesh without a normal gets, plus Z, since the
 * element it comes from is a direction and not a surface normal ({@code :143-148}); {@code
 * vaNormal} keeps the element, which is what a pack written for Iris reads when it widens by
 * itself. {@link GlslTranslator} writes the same wrapper, and this head declares what it reads:
 * {@link #OFFSET}, the direction added to the position on the first run, and {@link #WIDEN}, the
 * function that pushes the vertex off the line.
 * <p>
 * <strong>One guard Iris has not got, and it is a divergence written out in the three parts one
 * owes.</strong> What Iris does: it normalises the screen direction between the two clip positions
 * whatever they are ({@code VanillaTransformer.java:203}). What makes that wrong here: a pack that
 * widens by itself reads {@code vaPosition}, which carries no offset under Iris either
 * ({@code VanillaCoreTransformer.java:100}), so its two runs hand back one position, and
 * normalising nought is a NaN that lands in the clip position; Complementary and Photon both
 * widen by themselves. What it costs the image: nothing that Iris draws, since a NaN draws
 * nothing; {@link #WIDEN} leaves such an edge where the pack put it instead.
 * <p>
 * The light map is answered at full light, which is what Iris hands a mesh without a light
 * element ({@code VanillaTransformer.java:103-105}), the sampler behind it staying the real light
 * map, and {@code vaUV2} with nought, which is what an attribute Iris declares and nothing fills
 * reads there ({@code VanillaCoreTransformer.java:119}); the texture coordinate is the centre of
 * a texel, the first file's answer for a mesh without one ({@code :86-88}).
 */
public final class LinesVertex {

	/** The elements of the lines mesh, in the format's own order. */
	public static final List<String> ATTRIBUTES = List.of("Position", "Color", "Normal", "LineWidth");

	/** The names a pack written for Iris reads straight off the mesh, answered here as macros. */
	public static final Set<String> ANSWERED = Set.of("vaPosition", "vaNormal", "vaColor");

	/** The direction the wrapper adds to the position on its first run, nought on the second. */
	public static final String OFFSET = "of_LineOffset";

	/** The function the wrapper calls with the two clip positions, which widens the edge. */
	public static final String WIDEN = "of_WidenLine";

	private LinesVertex() {
	}

	/**
	 * The head of a lines vertex stage: the four elements, the names a pack reads made out of
	 * them, and constants for everything the mesh has not got.
	 *
	 * @param used        every name the rewritten body mentions, so that nothing is declared for a
	 *                    pack that never asks
	 * @param synthesized the vertex inputs the pack declared for itself and that were taken out of
	 *                    the body, by name and with the type the pack gave them
	 */
	public static List<String> prologue(Set<String> used, Map<String, String> synthesized) {
		List<String> lines = new ArrayList<>();

		for (String attribute : ATTRIBUTES) {
			lines.add("in " + VertexPrologue.elementType(attribute) + " " + attribute + ";");
		}

		lines.add("vec3 " + OFFSET + " = vec3(0.0);");
		lines.add("#define of_Vertex vec4(Position + " + OFFSET + ", 1.0)");
		lines.add("#define of_Color Color");
		lines.add("#define of_MultiTexCoord0 vec4(0.5, 0.5, 0.0, 1.0)");
		lines.add("#define of_MultiTexCoord1 " + EntityVertex.FULL_LIGHT);
		lines.add("#define of_MultiTexCoord2 " + EntityVertex.FULL_LIGHT);
		lines.addAll(VertexPrologue.blankTexCoords());

		// The element is the edge's direction and not a surface normal, so what a pack takes for
		// the normal is the constant every mesh without one gets, which is Iris's own answer for
		// this mesh (VanillaTransformer.java:146-147).
		lines.add("#define of_Normal vec3(0.0, 0.0, 1.0)");

		VertexPrologue.globals(used, synthesized, Map.of()).forEach((name, type) -> lines.add(
				ANSWERED.contains(name)
						? "#define " + name + " " + answer(name)
						: VertexPrologue.declaration(name, type)));

		return List.copyOf(lines);
	}

	/**
	 * The widening, declared after the head and before the wrapper that calls it. The screen size
	 * comes from the two uniforms every pack may read, which the translation supplies where the
	 * pack did not declare them. An edge whose two ends project to one point is left where the
	 * pack put it: a pack that widened the edge by itself hands both runs the same position, and a
	 * direction normalised out of nought would carry a NaN into the position.
	 */
	public static List<String> widen() {
		return List.of(
				"void " + WIDEN + "(inout vec4 lineStart, vec4 lineEnd) {",
				"    vec2 screenSize = vec2(viewWidth, viewHeight);",
				"    vec3 ndc1 = lineStart.xyz / lineStart.w;",
				"    vec3 ndc2 = lineEnd.xyz / lineEnd.w;",
				"    vec2 along = (ndc2.xy - ndc1.xy) * screenSize;",
				"    if (dot(along, along) < 1.0e-12) {",
				"        return;",
				"    }",
				"    vec2 lineScreenDirection = normalize(along);",
				"    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * LineWidth"
						+ " / screenSize;",
				"    if (lineOffset.x < 0.0) {",
				"        lineOffset *= -1.0;",
				"    }",
				"    if (gl_VertexIndex % 2 == 0) {",
				"        lineStart = vec4((ndc1 + vec3(lineOffset, 0.0)) * lineStart.w, lineStart.w);",
				"    } else {",
				"        lineStart = vec4((ndc1 - vec3(lineOffset, 0.0)) * lineStart.w, lineStart.w);",
				"    }",
				"}");
	}

	private static String answer(String name) {
		return switch (name) {
			case "vaPosition" -> "Position";
			case "vaNormal" -> "Normal.xyz";
			default -> "Color";
		};
	}
}
