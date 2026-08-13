package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the mesh of an enchantment's glint carries, and what a pack reads that it has not got.
 * <p>
 * {@code DefaultVertexFormat.POSITION_TEX} holds two elements: {@code Position} as three floats and
 * {@code UV0} as two. That is the whole of it - no colour, no overlay, no light map, no normal.
 * {@code RenderPipelines.GLINT} binds it ({@code RenderPipelines.java:432}), and so do the passes
 * that draw the sun and the moon, which {@link SkyVertex} answers for: a head is not one per format,
 * it is one per family that reads the format, and what these two families make of two bare elements
 * is not the same.
 * <p>
 * The four glint render types of the game are all built on that one pipeline
 * ({@code rendertype/RenderTypes.java:247,255,263,270}), so one head answers for the armour's glint,
 * the item's, the entity's and the translucent one alike.
 * <p>
 * <strong>Every name beyond those two is a constant, and the constants are Iris's rather than
 * chosen.</strong> Iris arrives at them through the format: {@code ShaderAttributeInputs} sets its
 * flags from the element names really bound ({@code gl/state/ShaderAttributeInputs.java:25-48}), and
 * {@code POSITION_TEX} turns off colour, overlay, light and normal at once. So its vanilla
 * transformer answers the light map with the saturated corner and the normal with the axis facing
 * the viewer ({@code transform/transformer/VanillaTransformer.java:104-105,154}), which is what the
 * two lines below say.
 * <p>
 * <strong>The colour is the one that is not a plain constant, and it is where the glint's strength
 * lives.</strong> Iris replaces {@code gl_Color} with
 * {@code vec4(ColorModulator.rgb, ColorModulator.a * GlintAlpha)}
 * ({@code VanillaTransformer.java:134}). The modulator is white for every draw the game prepares
 * from a render type, which {@link LegacyGlsl#GAME_TRANSFORMS_BLOCK} sets out, so what is left of
 * that expression here is the alpha: {@link LegacyGlsl#GLINT_ALPHA}, which is the same number Iris
 * reads out of the game's globals block and this engine answers from the frame's own snapshot of the
 * field that fills it.
 * <p>
 * <strong>What a glint is made of is that colour and that matrix</strong>, and the corpus reads both:
 * every pack of it reads {@code gl_TextureMatrix[0]}, which is what scrolls the sheet and without
 * which the effect is drawn frozen on one frame of its animation, and all but one read the colour.
 * <p>
 * Written as macros rather than as globals for the reason {@link EntityVertex} gives: a global
 * initialised from a vertex input is not a constant expression and the language refuses it.
 */
public final class GlintVertex {

	/** The elements of the glint mesh, in the format's own order. */
	public static final List<String> ATTRIBUTES = List.of("Position", "UV0");

	/**
	 * The light map coordinate of a mesh that has not got one, which is the saturated corner.
	 * <p>
	 * Iris's own number, {@code VanillaTransformer.java:104-105}, and it is the pair before the light
	 * map's texture matrix rather than after it: the level stores a light map coordinate as two
	 * levels from nought to two hundred and forty, and the matrix that turns those into texels is
	 * answered by {@code GeometryValues} for this family as for every other.
	 */
	private static final String FULL_BRIGHT = "vec4(240.0, 240.0, 0.0, 1.0)";

	private GlintVertex() {
	}

	/**
	 * The head of a glint vertex stage: the two elements, the names a pack reads made out of them or
	 * out of a constant, and whatever the pack asks for that the mesh has not got.
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

		lines.add("#define of_Vertex vec4(Position, 1.0)");
		lines.add("#define of_Color vec4(1.0, 1.0, 1.0, " + LegacyGlsl.GLINT_ALPHA + ")");
		lines.add("#define of_MultiTexCoord0 vec4(UV0, 0.0, 1.0)");
		lines.add("#define of_MultiTexCoord1 " + FULL_BRIGHT);
		// Unit two is a second name for the light map and not a unit of its own, exactly as it is on
		// the entity mesh: Iris renames one into the other before it substitutes anything,
		// VanillaTransformer.java:77.
		lines.add("#define of_MultiTexCoord2 " + FULL_BRIGHT);
		lines.addAll(VertexPrologue.blankTexCoords());

		// Towards the viewer, which is Iris's answer for a mesh with no normal in it
		// (VanillaTransformer.java:154) and not VertexPrologue's own axis: that one is for a name the
		// pack declared for itself, where nobody knows what it meant.
		lines.add("#define of_Normal vec3(0.0, 0.0, 1.0)");

		lines.addAll(VertexPrologue.tail(used, synthesized));

		return List.copyOf(lines);
	}
}
