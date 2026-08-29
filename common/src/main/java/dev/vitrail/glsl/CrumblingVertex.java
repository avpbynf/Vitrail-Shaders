package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the mesh a block's breaking overlay is drawn from carries, and how the names a pack reads are
 * made out of it.
 * <p>
 * {@code DefaultVertexFormat.BLOCK} holds four elements in twenty-eight bytes: {@code Position} as
 * three floats, {@code Color} as four normalised bytes, {@code UV0} as two floats, {@code UV2} as
 * two signed shorts. The order is the format's own and is not the particle mesh's, which carries the
 * same four names in another one.
 * <p>
 * <strong>Named after the family and not after the format</strong>, which {@link GlintVertex} is the
 * other instance of. The beacon beam binds that same format and is not served, and the name says
 * which of the two this head was written against rather than claiming the format outright. Nothing
 * here would refuse the beam: Iris draws it at full light as well
 * ({@code pipeline/programs/ShaderKey.java:70}), so this head would answer it. What it wants is a
 * row of its own, its program, its alpha test and its fog being none of the crumbling's.
 * <p>
 * <strong>The light map is answered at full light, and that is read off Iris rather than
 * chosen.</strong> {@code pipeline/programs/ShaderKey.java:61} gives the crumbling key
 * {@code LightingModel.FULLBRIGHT}, and {@link VertexInputs#fullbright} carries the other half of
 * that answer, the white pixel behind the light map sampler. The element itself is bound and
 * declared all the same, the format carrying it. This is the camera's contract and the only one:
 * the shadow map files no twin for this overlay, because the reference's map never receives the
 * draw, which the crumbling row of {@code EntityDraw} carries with its file and line.
 * <p>
 * <strong>Iris draws this overlay from a WIDER mesh than the game's, so four of the names a pack
 * reads are constants here and values there. That is a divergence and it is written out in the
 * three parts one owes.</strong>
 * <p>
 * What Iris does: it widens this very format on the way to the buffer,
 * {@code mixin/vertices/MixinBufferBuilder.iris$extendFormat:102-106} handing back
 * {@code IrisVertexFormats.TERRAIN} for {@code DefaultVertexFormat.BLOCK}, and then fills what it
 * added. The normal and the tangent are computed per polygon and the mid texture coordinate written
 * beside them ({@code MixinBufferBuilder:240-260}); {@code at_midBlock} is filled at every vertex
 * ({@code :127-134}). The block id is the one it does NOT fill on this draw: nothing opens a block
 * around the overlay's buffer, so {@code currentBlock} stays at the {@code -1} it is declared with
 * ({@code :74}) and a pack reads that pair rather than a block.
 * <p>
 * What stops that here: widening a mesh is what {@code EntityMesh} does, for one format and one
 * only, and it is a format of this engine's with a Sodium serializer behind it. Nothing widens the
 * block format, so the four elements above are the whole of what reaches this stage.
 * <p>
 * What it costs the image: a pack whose {@code gbuffers_damagedblock} does its own normal mapping
 * or relief reads a fixed direction where Iris hands it the face's, a tangent that is the
 * prologue's default and a mid texture coordinate of nought, so a crack is shaded flat instead of
 * following the face it lies on. The normal is the value Iris itself hands every format carrying
 * none ({@code transform/transformer/VanillaTransformer.java:152-154}), and the one
 * {@link ParticleVertex} and {@link SkyVertex} give for the same reason, a normalise of a zero
 * vector being a NaN that spreads into the colour through the tangent frame. What is left is flat
 * rather than absent: the crack darkens the albedo it is multiplied onto either way, that being
 * what the game's own blend does with it. And {@code mc_Entity} is nought here where it is
 * {@code -1} there, so a pack branching on it takes the same branch as an unnamed block on both,
 * unless it tests for one of those two values by name.
 * <p>
 * <strong>Declaring an element is not the same as keeping it</strong>, and the risk is sharper on
 * this family than on any other. An input a stage declares and never reads may be dropped from the
 * compiled module, and {@code rebind} only counts the ones that survived: {@code Color} is second
 * of the four, so a stage that never reads {@code gl_Color} and loses it would have {@code UV0}
 * read out of the colour's bytes and {@code UV2} out of the coordinate's. {@code UV2} is the one
 * the camera's side never reads by construction, its light map being answered with a constant. The
 * off-game harness compiles every such program of the corpus and reads the disassembly back to
 * check the four are still there, which is the check the particle mesh already had.
 * <p>
 * <strong>There is no overlay here.</strong> {@code UV1} is what an entity carries and a block has
 * not got, so {@code entityColor} is not made in this head and {@link VertexInputs#overlay} leaves
 * this contract out.
 */
public final class CrumblingVertex {

	/** The elements of the block mesh, in the format's own order. */
	public static final List<String> ATTRIBUTES = List.of("Position", "Color", "UV0", "UV2");

	private CrumblingVertex() {
	}

	/**
	 * The head of a crumbling vertex stage: the four elements, the names a pack reads made out of
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
		// Both units that name the light map, answered at full light like every other piece the
		// game draws at the camera: Iris keys this draw FULLBRIGHT (ShaderKey.java:61), and no
		// other side of the stage reaches this head, the map filing no twin for the overlay.
		lines.add("#define of_MultiTexCoord1 " + EntityVertex.FULL_LIGHT);
		lines.add("#define of_MultiTexCoord2 " + EntityVertex.FULL_LIGHT);
		lines.addAll(VertexPrologue.blankTexCoords());

		lines.add("#define of_Normal vec3(0.0, 0.0, 1.0)");

		// mc_Entity among them, and it is a constant under Iris too on this draw, at -1 rather than at
		// the nought this hands back. A pack branching on it here is branching on a constant either
		// way.
		lines.addAll(VertexPrologue.tail(used, synthesized));

		return List.copyOf(lines);
	}
}
