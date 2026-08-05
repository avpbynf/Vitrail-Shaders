package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the game's sky meshes carry, and how the names a pack reads are made out of them.
 * <p>
 * Unlike the chunk mesh and the entity mesh, the sky is not ONE format. {@code SkyRenderer} opens
 * eight render passes of its own and binds four different formats between them, and
 * {@code CloudRenderer} adds its own:
 * <pre>
 *     SKY, STARS          POSITION             disc, dark disc, stars
 *     SUNRISE_SUNSET      POSITION_COLOR       the sunrise and sunset band
 *     CELESTIAL           POSITION_TEX         sun, moon, the End flash
 *     END_SKY             POSITION_TEX_COLOR   the End's sky
 * </pre>
 * So the prologue is built from the elements actually bound rather than from a table of its own,
 * and that is not a refinement. The pairing is by name and asymmetric in both directions: a name
 * the stage declares that the format has not got is refused outright,
 * {@code IntermediaryShaderModule.rebind:205-207}, and an element the format carries that the
 * stage does not declare is stepped over, which shifts the location of everything after it without
 * a word being said. Declaring the union of the four would break three of them.
 * <p>
 * Only three names ever appear: {@code Position}, {@code Color} and {@code UV0}. There is no
 * normal, no light map and no overlay anywhere in the sky, which is why so much of what a pack
 * reads is answered with a constant here.
 * <p>
 * <strong>What is approximated, said out loud.</strong> {@code of_MultiTexCoord1} is the light map,
 * and no sky format carries one. It is answered at full sky light rather than at nought, because
 * nought is the value that reads as "underground" to a pack that folds it into its own sky colour.
 * No program of the corpus was seen reading it in a sky stage, so this constant is a guard rather
 * than a value anything is known to depend on. {@code of_Normal} is answered facing the camera for
 * the same reason: the sky has no surface, and a normalise of a zero vector is a NaN that spreads.
 * <p>
 * <strong>One risk this class cannot close on its own, and that whoever draws the sky has to
 * measure.</strong> An input a stage declares and never reads may be dropped from the SPIR-V, and
 * {@code rebind} only counts the ones that survived, so a dropped element shifts the location of
 * every one after it. {@code Position} is safe, since a vertex stage cannot avoid computing a
 * position out of it, but a sky program that ignores {@code Color} or {@code UV0} would lose them.
 * The entity family answers the same risk by reading the SPIR-V of every corpus stage back at the
 * harness and checking the variables are still there; the sky needs that check before it is
 * believed, not after.
 */
public final class SkyVertex {

	/**
	 * Every name any sky format may carry, which is what a pack may therefore not use for something
	 * of its own.
	 * <p>
	 * The union and not the bound format, on purpose, and it is the one place the union is right:
	 * this list decides which of the pack's own symbols are renamed out of the way, and renaming one
	 * the bound format happens not to carry costs nothing, while missing one that it does carry is a
	 * redefinition at file scope that refuses the stage.
	 */
	public static final List<String> ATTRIBUTES = List.of("Position", "Color", "UV0");

	private SkyVertex() {
	}

	/**
	 * The head of a sky vertex stage: the elements the bound format carries, the names a pack reads
	 * made out of them, and constants for everything the sky has not got.
	 *
	 * @param bound       the elements of the format this pass actually binds, in the format's own
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
		// The modulator, times the element where the format has one, and this is the one line of the
		// prologue that decides a picture rather than guarding one.
		//
		// The game does not put the sky's colour in the mesh; it puts it in the colour modulator of
		// its dynamic transforms, one value for the whole draw, and under OptiFine that value is
		// what a pack reads as gl_Color. White is not a neutral stand in for it: packs recognise
		// vanilla's stars by exactly that shape, a colour whose three channels are equal and above
		// nought, and Body Camera and Sildur's both then take their star branch and paint the whole
		// sky disc flat.
		//
		// The two MULTIPLY where both exist, because that is what the game's own shaders do with
		// them: core/position_color.fsh ends on fragColor = color * ColorModulator, and so does
		// core/position_tex. The one element of the sky whose format carries a colour is the sunrise
		// band, and there the mesh is white at the centre fading to a transparent white at the rim,
		// ARGB.white(1.0F) and ARGB.white(0.0F) in SkyRenderer.buildSunriseFan: every bit of the
		// band's actual colour is in the modulator, so keeping the element alone draws a white band
		// at sunset. Dropping the element instead would take the fade with it and paint a disc.
		lines.add(bound.contains("Color")
				? "#define of_Color (Color * of_PassColour)"
				: "#define of_Color of_PassColour");

		String texture = bound.contains("UV0") ? "vec4(UV0, 0.0, 1.0)" : "vec4(0.0, 0.0, 0.0, 1.0)";
		lines.add("#define of_MultiTexCoord0 " + texture);

		// The light map, which no sky format carries. Full sky light and no block light, in the raw
		// coordinates a pack expects to divide down itself.
		for (int unit = 1; unit <= 2; unit++) {
			lines.add("#define of_MultiTexCoord" + unit + " vec4(0.0, 240.0, 0.0, 1.0)");
		}

		lines.addAll(VertexPrologue.blankTexCoords());

		lines.add("#define of_Normal vec3(0.0, 0.0, 1.0)");
		lines.addAll(VertexPrologue.tail(used, synthesized));

		return List.copyOf(lines);
	}
}
