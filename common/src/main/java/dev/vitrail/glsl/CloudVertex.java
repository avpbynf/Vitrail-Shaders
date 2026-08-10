package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the game's cloud mesh carries, and how the names a pack reads are made out of it.
 * <p>
 * <strong>There is no cloud mesh.</strong> {@code CloudRenderer} binds no vertex buffer at all: it
 * fills a texel buffer with three bytes a face, a cell in x, a cell in z and a word carrying the
 * facing and four flags, then draws six indices a face and lets the vertex stage work the corner
 * out of {@code gl_VertexID}. So this head declares no vertex input, and everything a pack reads is
 * made out of that buffer and the block beside it.
 * <p>
 * The two names it declares are the game's own and not ours to choose: the pass that draws the
 * clouds binds them by name, {@code CloudInfo} and {@code CloudFaces}, against whatever pipeline is
 * bound. A pipeline of ours that spelled either differently would be handed neither, and the stage
 * would read a buffer nothing filled.
 * <p>
 * <strong>The tables below are the game's geometry and not a shape of ours.</strong> Twenty four
 * corners in facing order, six normals, six shades: they are what {@code core/rendertype_clouds}
 * reads out of the same buffer, so a pack drawing through this engine gets the cloud the game would
 * have drawn, lit by the pack instead of by the game's own flat shading. Getting a corner out of
 * order does not fail, it turns one face of every cloud inside out.
 * <p>
 * Two things the game's own shader has no use for are answered here all the same. The normal,
 * because a pack does read it - Body Camera writes {@code gl_NormalMatrix * gl_Normal} into a colour
 * target from its cloud stage - and the facing is exactly what the buffer already carries. And the
 * light map, at the top of both channels, for the reason {@link SkyVertex} gives: nought reads as
 * "underground" to a pack that folds it into its own sky colour.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris, LGPL-3.0</a>
 */
public final class CloudVertex {

	/**
	 * The names this head declares, which the pack may therefore not use for anything of its own.
	 * <p>
	 * Not vertex inputs, unlike every other family's list, and the mechanism does not care: what the
	 * translator does with these is move the pack's own symbol out of the way wherever it declares
	 * one of them, which is as necessary for a uniform block as for an attribute. A pack declaring
	 * its own {@code CloudFaces} would otherwise be a redefinition at file scope, and the stage would
	 * be refused outright.
	 */
	public static final List<String> ATTRIBUTES = List.of("CloudInfo", "CloudFaces");

	/**
	 * The corner of the quad each of the six facings is built from, in the order the facing value
	 * indexes them: down, up, north, south, west, east, which is {@code Direction.get3DDataValue}.
	 */
	private static final List<String> CORNERS = List.of(
			// Down
			"vec3(1.0, 0.0, 0.0)", "vec3(1.0, 0.0, 1.0)", "vec3(0.0, 0.0, 1.0)", "vec3(0.0, 0.0, 0.0)",
			// Up
			"vec3(0.0, 1.0, 0.0)", "vec3(0.0, 1.0, 1.0)", "vec3(1.0, 1.0, 1.0)", "vec3(1.0, 1.0, 0.0)",
			// North
			"vec3(0.0, 0.0, 0.0)", "vec3(0.0, 1.0, 0.0)", "vec3(1.0, 1.0, 0.0)", "vec3(1.0, 0.0, 0.0)",
			// South
			"vec3(1.0, 0.0, 1.0)", "vec3(1.0, 1.0, 1.0)", "vec3(0.0, 1.0, 1.0)", "vec3(0.0, 0.0, 1.0)",
			// West
			"vec3(0.0, 0.0, 1.0)", "vec3(0.0, 1.0, 1.0)", "vec3(0.0, 1.0, 0.0)", "vec3(0.0, 0.0, 0.0)",
			// East
			"vec3(1.0, 0.0, 0.0)", "vec3(1.0, 1.0, 0.0)", "vec3(1.0, 1.0, 1.0)", "vec3(1.0, 0.0, 1.0)");

	/** Which way each facing points, in the same order. */
	private static final List<String> NORMALS = List.of(
			"vec3(0.0, -1.0, 0.0)", "vec3(0.0, 1.0, 0.0)", "vec3(0.0, 0.0, -1.0)",
			"vec3(0.0, 0.0, 1.0)", "vec3(-1.0, 0.0, 0.0)", "vec3(1.0, 0.0, 0.0)");

	/**
	 * How much of the cloud's colour each facing keeps, in the same order.
	 * <p>
	 * The game's own numbers, alpha included: they are its whole lighting model for a cloud, one
	 * value a face, and a pack that keeps {@code gl_Color} keeps them. Alpha is one here and the
	 * transparency of a cloud is entirely in the colour the game passes down beside them.
	 */
	private static final List<String> SHADES = List.of(
			"vec4(0.7, 0.7, 0.7, 1.0)", "vec4(1.0, 1.0, 1.0, 1.0)", "vec4(0.8, 0.8, 0.8, 1.0)",
			"vec4(0.8, 0.8, 0.8, 1.0)", "vec4(0.9, 0.9, 0.9, 1.0)", "vec4(0.9, 0.9, 0.9, 1.0)");

	private CloudVertex() {
	}

	/**
	 * The head of a cloud vertex stage: the buffer the faces come out of, the tables they are built
	 * with, and the names a pack reads made out of both.
	 *
	 * @param used        every name the rewritten body mentions, so that nothing is declared for a
	 *                    pack that never asks
	 * @param synthesized the vertex inputs the pack declared for itself and that were taken out of
	 *                    the body, by name and with the type the pack gave them. All of them, since
	 *                    this pass binds no format at all
	 */
	public static List<String> prologue(Set<String> used, Map<String, String> synthesized) {
		List<String> lines = new ArrayList<>();

		// The members are ours to name and the block is not. std140 matches by offset, so what has
		// to hold is the order and the padding: a vec4 then two vec3, which is what
		// CloudRenderer.render fills through Std140Builder in exactly that order.
		lines.add("layout(std140) uniform CloudInfo {");
		lines.add("\tvec4 of_CloudColour;");
		lines.add("\tvec3 of_CloudOffset;");
		lines.add("\tvec3 of_CellSize;");
		lines.add("};");
		lines.add("uniform isamplerBuffer CloudFaces;");

		lines.add("const vec3 of_cloudCorners[24] = vec3[24](" + String.join(", ", CORNERS) + ");");
		lines.add("const vec3 of_cloudNormals[6] = vec3[6](" + String.join(", ", NORMALS) + ");");
		lines.add("const vec4 of_cloudShades[6] = vec4[6](" + String.join(", ", SHADES) + ");");

		// One word per face rather than one read per name: the three below all want it, and a
		// compiler that fails to fold three identical fetches into one still reads the same value.
		//
		// The buffer is signed bytes and the arithmetic below depends on it. The engine packs the low
		// bit of each cell into bits seven and six of this word, so bit seven is the sign, and the
		// word arrives negative wherever the cell is odd in x. Masking first is what makes that
		// harmless: `word & 128` is 128 whatever the sign extension put above it.
		lines.add("int of_cloudWord() { return texelFetch(CloudFaces, (gl_VertexID / 4) * 3 + 2).r; }");
		lines.add("int of_cloudFacing() { return of_cloudWord() & 7; }");

		lines.add("vec3 of_cloudPosition() {");
		lines.add("\tint face = (gl_VertexID / 4) * 3;");
		lines.add("\tint word = of_cloudWord();");
		lines.add("\tint cellX = (texelFetch(CloudFaces, face).r << 1) | ((word & 128) >> 7);");
		lines.add("\tint cellZ = (texelFetch(CloudFaces, face + 1).r << 1) | ((word & 64) >> 6);");
		// A face the camera stands inside is wound the other way round, which is the whole of what
		// bit four says. Read as anything else it is a face culled exactly where it has to be drawn.
		lines.add("\tint corner = gl_VertexID % 4;");
		lines.add("\tvec3 at = of_cloudCorners[(word & 7) * 4 + ((word & 16) != 0 ? 3 - corner : corner)];");
		lines.add("\treturn (at * of_CellSize) + (vec3(cellX, 0.0, cellZ) * of_CellSize) + of_CloudOffset;");
		lines.add("}");

		lines.add("#define of_Vertex vec4(of_cloudPosition(), 1.0)");
		// Bit five says this face takes the top shade whatever way it points, which is how a flat
		// cloud is drawn: one downward face a cell, lit as though it were the top.
		lines.add("#define of_Color (((of_cloudWord() & 32) != 0 ? of_cloudShades[1] "
				+ ": of_cloudShades[of_cloudFacing()]) * of_CloudColour)");
		lines.add("#define of_Normal of_cloudNormals[of_cloudFacing()]");

		// The middle of the sprite, since nothing textures a cloud. The corner instead would send a
		// pack that samples gtexture to one texel of whatever is bound rather than to the one the
		// name stands for, and three packs of the corpus do sample it here.
		lines.add("#define of_MultiTexCoord0 vec4(0.5, 0.5, 0.0, 1.0)");

		// The light map, which the cloud buffer has not got. Both channels at the top of the range,
		// in the raw coordinates a pack divides down itself, exactly as the sky answers it.
		for (int unit = 1; unit <= 2; unit++) {
			lines.add("#define of_MultiTexCoord" + unit + " vec4(240.0, 240.0, 0.0, 1.0)");
		}

		lines.addAll(VertexPrologue.blankTexCoords());
		lines.addAll(VertexPrologue.tail(used, synthesized));

		return List.copyOf(lines);
	}
}
