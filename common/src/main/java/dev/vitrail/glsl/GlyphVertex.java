package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the meshes the game's text is drawn from carry, a name plate and a sign's board first among
 * them, and how the names a pack reads are made out of them.
 * <p>
 * <strong>Not one format, which is what {@link SkyVertex} is the other instance of.</strong> The
 * eight pipelines that draw text bind four between them, and the four differ in whether the quad
 * carries a texture coordinate and whether it carries a light map:
 * <pre>
 *     TEXT, TEXT_GRAYSCALE            POSITION_TEX_LIGHTMAP_COLOR  a glyph in the world
 *     the two POLYGON_OFFSET twins    POSITION_TEX_LIGHTMAP_COLOR  the same, nudged off its face
 *     the two SEE_THROUGH twins       POSITION_TEX_COLOR           a glyph read through a wall
 *     TEXT_BACKGROUND                 POSITION_COLOR_LIGHTMAP      a text display's own box
 *     TEXT_BACKGROUND_SEE_THROUGH     POSITION_COLOR               the same, read through a wall
 * </pre>
 * <strong>The box behind a name plate is NOT one of the last two.</strong> 26.2 draws that box as
 * an EFFECT over the font's white glyph, so it takes the glyph's own render type and with it the
 * glyph's own format: {@code BakedSheetGlyph.EffectInstance.renderType} hands back
 * {@code GlyphRenderTypes.select}, which is the very {@code TEXT} or {@code TEXT_SEE_THROUGH} the
 * letters standing on it are drawn with. What the last two rows carry is a text display ENTITY's
 * box, {@code RenderTypes.textBackground} and {@code textBackgroundSeeThrough} having one caller in
 * the whole client, {@code DisplayRenderer.TextDisplayRenderer}, which hands its quads in as custom
 * geometry.
 * <p>
 * So the head is built from the elements actually bound rather than from a table of its own, and
 * the reason is the sky's: the pairing is by name and asymmetric in both directions, a name the
 * stage declares that the format has not got being refused outright
 * ({@code IntermediaryShaderModule.rebind:205-207}) and an element the stage does not declare being
 * stepped over, which shifts the location of everything after it without a word being said.
 * <p>
 * <strong>The order is 26.2's and it is not the order Iris reads.</strong> The world's glyph was
 * {@code POSITION_COLOR_TEX_LIGHTMAP} on 26.1, which is what Iris keys its widening on
 * ({@code mixin/vertices/MixinBufferBuilder.java:112-116}); 26.2 lays the same four out as
 * {@code POSITION_TEX_LIGHTMAP_COLOR} and takes the light map off both see-through pipelines
 * altogether. Nothing here depends on the byte offsets, the elements being matched by name, but a
 * head written from Iris's list rather than from the bound one would declare a light map two of
 * these pipelines have not got.
 * <p>
 * <strong>Iris draws a glyph from a WIDER mesh than the game's, so four of the names a pack reads
 * are constants here and values there. That is a divergence and it is written out in the three
 * parts one owes.</strong>
 * <p>
 * What Iris does: it widens the glyph format on the way to the buffer,
 * {@code mixin/vertices/MixinBufferBuilder.iris$extendFormat:112-117} handing back
 * {@code IrisVertexFormats.GLYPH}, which is the four elements plus a normal, an entity identifier,
 * a mid texture coordinate and a tangent ({@code vertices/IrisVertexFormats.java:62-72}), and then
 * fills what it added: the normal and the tangent are computed per quad and the mid texture
 * coordinate averaged over its four corners ({@code vertices/sodium/GlyphExtVertexSerializer.java:27-48}),
 * and the three identifiers are copied off the captured rendering state ({@code :63-66}).
 * <p>
 * What stops that here: widening a mesh is what {@code EntityMesh} does, for one format and one
 * only, and it is a format of this engine's with a Sodium serializer behind it. Nothing widens the
 * text formats, so what the game laid out is the whole of what reaches this stage. It is the
 * position {@link CrumblingVertex} is in, and for the same reason.
 * <p>
 * What it costs the image: a pack whose {@code gbuffers_entities_translucent} does its own normal
 * mapping reads a fixed direction where Iris hands it the quad's own, a tangent that is the
 * prologue's default and a mid texture coordinate of nought, so a glyph is shaded flat. On a name
 * plate that costs nothing anyone can see, the quad facing the camera being exactly the direction
 * the constant names; on a sign's board, which faces the wall it hangs on, a pack that lights by
 * the normal lights the text as though it faced the player. And {@code mc_Entity} is nought here,
 * as it is on every mesh of the game that carries no block id.
 * <p>
 * <strong>The light map is answered at full light where the format has none</strong>, which is the
 * three see-through pipelines, and that is the answer Iris hands every mesh without the element
 * ({@code transform/transformer/VanillaCoreTransformer.java:117-118}). The sampler behind it stays
 * the real light map, which is what {@link LinesVertex} does with the same constant: full light
 * here is half of Iris's answer and the piece is not one Iris draws at full light, so taking the
 * other half would darken nothing and brighten a pack that multiplies the two.
 */
public final class GlyphVertex {

	/** The elements of a glyph in the world, in the format's own order. */
	public static final List<String> WORLD = List.of("Position", "UV0", "UV2", "Color");

	/** The same glyph read through a wall, which 26.2 draws without a light map. */
	public static final List<String> SEE_THROUGH = List.of("Position", "UV0", "Color");

	/** The box a text display draws behind its lines, which carries no texture at all. */
	public static final List<String> BACKGROUND = List.of("Position", "Color", "UV2");

	/** The same box read through a wall. */
	public static final List<String> BACKGROUND_SEE_THROUGH = List.of("Position", "Color");

	/**
	 * Every name any of the four may carry, which is what a pack may therefore not use for something
	 * of its own.
	 * <p>
	 * The union and not the bound format, for the reason {@link SkyVertex#ATTRIBUTES} gives: this
	 * list only decides which of the pack's own symbols are renamed out of the way, and renaming one
	 * the bound format happens not to carry costs nothing.
	 */
	public static final List<String> ATTRIBUTES = WORLD;

	private GlyphVertex() {
	}

	/**
	 * The head of a text vertex stage: the elements the bound format carries, the names a pack reads
	 * made out of them, and constants for everything the quad has not got.
	 *
	 * @param bound       the elements of the format this piece's pipeline binds, in the format's own
	 *                    order. Exactly these are declared, and no others
	 * @param used        every name the rewritten body mentions, so that nothing is declared for a
	 *                    pack that never asks
	 * @param synthesized the vertex inputs the pack declared for itself and that were taken out of
	 *                    the body, by name and with the type the pack gave them
	 */
	public static List<String> prologue(List<String> bound, Set<String> used,
			Map<String, String> synthesized) {
		List<String> lines = new ArrayList<>();

		for (String attribute : bound) {
			lines.add("in " + VertexPrologue.elementType(attribute) + " " + attribute + ";");
		}

		lines.add("#define of_Vertex vec4(Position, 1.0)");
		lines.add("#define of_Color Color");

		// The middle of a texel where the format has no coordinate, which is the two background
		// quads: nothing is bound behind gtexture for them, the game's own pipeline declaring no
		// Sampler0, so what a pack samples there is the one white texel GeometryProgram falls back
		// on and the corner would read the same value. LinesVertex answers its own mesh the same way.
		lines.add("#define of_MultiTexCoord0 " + (bound.contains("UV0")
				? "vec4(UV0, 0.0, 1.0)"
				: "vec4(0.5, 0.5, 0.0, 1.0)"));

		// Full light where 26.2 dropped the element, which is all three see-through pipelines. Unit
		// two is a second name for the light map and not a unit of its own, which is what Iris makes
		// of it as well (transform/transformer/VanillaTransformer.java:77 renaming one into the other).
		String light = bound.contains("UV2") ? "vec4(UV2, 0.0, 1.0)" : EntityVertex.FULL_LIGHT;
		lines.add("#define of_MultiTexCoord1 " + light);
		lines.add("#define of_MultiTexCoord2 " + light);
		lines.addAll(VertexPrologue.blankTexCoords());

		// The constant every mesh of the game without a normal gets here, and Iris's own answer for
		// such a mesh (transform/transformer/VanillaTransformer.java:152-154). A glyph really has a
		// facing and this head cannot know it: the class comment says what that costs.
		lines.add("#define of_Normal vec3(0.0, 0.0, 1.0)");

		lines.addAll(VertexPrologue.tail(used, synthesized));

		return List.copyOf(lines);
	}
}
