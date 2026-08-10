package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the game's own particle mesh carries, and how the names a pack reads are made out of it.
 * <p>
 * {@code DefaultVertexFormat.PARTICLE} holds four elements in twenty-eight bytes: {@code Position}
 * as three floats, {@code UV0} as two floats, {@code Color} as four normalised bytes, {@code UV2} as
 * two signed shorts. Two families of the game bind it and nothing else between them, the quad
 * particles and the weather, so like the entity mesh and unlike the sky there is one format to
 * declare rather than one per pass.
 * <p>
 * <strong>All four are declared, in the format's own order, and no fewer.</strong> The pairing is by
 * name and asymmetric in both directions, which {@link EntityVertex} spells out with the
 * {@code file:line} of both halves: a name the stage declares that the format has not got is refused
 * outright, and an element the stage does not declare is stepped over and drops everything after it
 * one location without a word.
 * <p>
 * <strong>Declaring one is not the same as keeping it, and that risk is measured rather than
 * argued.</strong> An input a stage declares and never reads may be dropped from the compiled
 * module, and {@code rebind} only counts the ones that survived. {@code Color} is third of the four
 * here, so a pack whose particle stage never reads {@code gl_Color} would have {@code UV2}, which is
 * the light map, read out of the colour's bytes. The off-game harness compiles every such program of
 * the corpus and reads the disassembly back to check all four are still there, which is the same
 * check the entity mesh has and the sky still owes.
 * <p>
 * <strong>There is no normal and no overlay here.</strong> A particle is a quad turned to face the
 * camera, so {@code of_Normal} is answered facing the viewer rather than left at nought, which is
 * the value Iris hands every format that carries no normal
 * ({@code transform/transformer/VanillaTransformer.java:152-154}) and the same answer
 * {@link SkyVertex} gives for the same reason: a normalise of a zero vector is a NaN that spreads
 * into the colour through the tangent frame.
 * <p>
 * <strong>The colour is the element alone, with no modulator folded into it</strong>, and that is
 * read rather than assumed. Iris multiplies the two everywhere the format has a colour
 * ({@code VanillaTransformer.java:124-125}), and here the second factor is white by construction:
 * both renderers write their transform through the ONE argument {@code writeTransform}
 * ({@code renderer/DynamicUniforms.java:40-42}), which fills the modulator with white, so the
 * product is the element. The sky is the family where that is not true and where the modulator
 * carries the whole of the colour.
 */
public final class ParticleVertex {

	/** The elements of the particle mesh, in the format's own order. */
	public static final List<String> ATTRIBUTES = List.of("Position", "UV0", "Color", "UV2");

	private ParticleVertex() {
	}

	/**
	 * The head of a particle vertex stage: the four elements, the names a pack reads made out of
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

		lines.add("#define of_Vertex vec4(Position, 1.0)");
		lines.add("#define of_Color Color");
		lines.add("#define of_MultiTexCoord0 vec4(UV0, 0.0, 1.0)");
		// The light map, on both units that name it. It is UV2 here as it is on the entity mesh, and
		// there is no UV1 at all to be confused with it: the overlay is what an entity has and a
		// particle has not.
		lines.add("#define of_MultiTexCoord1 vec4(UV2, 0.0, 1.0)");
		lines.add("#define of_MultiTexCoord2 vec4(UV2, 0.0, 1.0)");
		lines.addAll(VertexPrologue.blankTexCoords());

		lines.add("#define of_Normal vec3(0.0, 0.0, 1.0)");

		// mc_Entity among them, and a pack branching on it here is branching on a constant. That is
		// what the mesh says rather than a gap in this head: a particle has no block state and no
		// entity to travel on, and Iris answers it the same way by declaring the entity uniforms off
		// the fallback root, which neither of this family's two names is.
		lines.addAll(VertexPrologue.tail(used, synthesized));

		return List.copyOf(lines);
	}
}
