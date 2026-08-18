package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the entity mesh carries, and how the names a pack reads are made out of it.
 * <p>
 * {@code DefaultVertexFormat.ENTITY} holds six elements in thirty-six bytes: {@code Position} as
 * three floats, {@code Color} as four normalised bytes, {@code UV0} as two floats, {@code UV1} and
 * {@code UV2} as two signed shorts each, {@code Normal} as four signed bytes. This engine appends a
 * seventh, {@link #IDENTIFIERS}, on a format of its OWN built out of those six: Sodium writes every
 * cuboid of a mob at the game's own stride and copies the run over raw whenever the two formats are
 * the same object, so lengthening the game's would copy forty-four bytes a vertex out of thirty-six
 * and leave no mob on screen. {@code EntityMesh} is where that is built, and it carries what a draw
 * a loaded pack does not serve then binds.
 * <p>
 * <strong>All seven are declared, and no fewer.</strong> The pairing is by name and asymmetric in
 * both directions. A name the stage declares that the format has not got is refused outright,
 * {@code IntermediaryShaderModule.rebind:205-207}. An element the stage does not declare is simply
 * stepped over, and since {@code VulkanRenderPipeline} counts every element while {@code rebind}
 * only counts the ones it found, everything after the gap lands one location too low without a
 * word being said.
 * <p>
 * <strong>The light map is {@code UV2} and not {@code UV1}.</strong> {@code UV1} is the overlay,
 * the hit flash and the damage tint, and what reads it is not a name of the prologue: the wrapper
 * around {@code main} fetches the texel it points at and hands the colour on as
 * {@code entityColor}, which is where Iris takes it from as well
 * ({@code EntityPatcher.patchOverlayColor}). See {@code GlslTranslator.overlayPrologue}. All seven
 * elements are declared whether or not a pack asks for any of them, which is safe for the one reason
 * that matters and is measured rather than assumed: the off-game harness reads the SPIR-V of every
 * entity stage of the corpus back and checks that all seven variables are still in it, because a
 * variable the compiler dropped is one {@code rebind} cannot find.
 * <p>
 * Written as macros rather than as globals for the reason {@link LegacyGlsl#FULLSCREEN_ATTRIBUTES}
 * gives: a global initialised from a vertex input is not a constant expression and the language
 * refuses it, and the chunk mesh only escapes that by having a prologue it needs anyway. Here
 * there is nothing to compute, so there is no prologue to hang it on.
 */
public final class EntityVertex {

	/**
	 * The element this engine appends to the game's own entity format, holding the three identifiers
	 * a pack tells one entity, block entity or held item apart by.
	 * <p>
	 * <strong>Four lanes of which three are read</strong>, which is Iris's own shape
	 * ({@code vertices/IrisVertexFormats.java:30}, four unsigned shorts) and is not a round number
	 * picked for looks: a vertex has to be a multiple of four bytes wide, so three shorts would be
	 * followed by two bytes of padding anyway.
	 * <p>
	 * <strong>Unsigned, and that decides what a pack reads for a name it never mapped.</strong> The
	 * tables answer -1 there, the lane holds it as {@code 0xFFFF}, and a stage reading the element as
	 * unsigned gets 65535 rather than -1. That is what Iris hands over as well, its element being
	 * unsigned too and its input an {@code ivec3} the driver zero extends into, so a pack testing that
	 * name meets the same number under both engines.
	 */
	public static final String IDENTIFIERS = "EntityIds";

	/**
	 * The elements of the entity mesh, in the format's own order, the six the game lays out and the
	 * one this engine appends after them. {@code EntityMesh} is what appends it.
	 */
	public static final List<String> ATTRIBUTES =
			List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal", IDENTIFIERS);

	/**
	 * What the light map names read on a piece the game draws at full light, which is the value a
	 * block at the brightest light level would have carried on the element.
	 * <p>
	 * Iris's own constant, {@code transform/transformer/VanillaCoreTransformer.java:117-118}, and the
	 * number is the game's rather than a round one. {@code LightCoordsUtil.pack} shifts the block level
	 * up by four and the sky level up by twenty ({@code util/LightCoordsUtil.java:13-14}), so each of
	 * the two lands on its own short with the brightest of the sixteen levels at fifteen times sixteen.
	 * The game names that value twice itself, {@code FULL_BRIGHT = 0xF000F0} at {@code :9} and
	 * {@code MAX_SMOOTH_LIGHT_LEVEL = 240} at {@code :11}. Handing a pack anything larger would send it
	 * off its own light map.
	 */
	public static final String FULL_LIGHT = "vec4(240.0, 240.0, 0.0, 1.0)";

	private EntityVertex() {
	}

	/**
	 * The head of an entity vertex stage: the seven elements, the names a pack reads made out of
	 * five of them, and whatever the pack asks for that the mesh has not got.
	 *
	 * @param used        every name the rewritten body mentions, so that nothing is declared for a
	 *                    pack that never asks
	 * @param synthesized the vertex inputs the pack declared for itself and that were taken out of
	 *                    the body, by name and with the type the pack gave them
	 * @param fullbright  whether the light map names are answered with {@link #FULL_LIGHT} rather
	 *                    than out of the element, which is a fact about the PIECE being drawn and not
	 *                    about the mesh: {@code UV2} is bound and carries a real light map either
	 *                    way. {@link VertexInputs#ENTITY_FULLBRIGHT} says which pieces and why the
	 *                    sampler has to follow
	 */
	public static List<String> prologue(Set<String> used, Map<String, String> synthesized,
			boolean fullbright) {
		List<String> lines = new ArrayList<>();

		for (String attribute : ATTRIBUTES) {
			lines.add("in " + VertexPrologue.elementType(attribute) + " " + attribute + ";");
		}

		// Still declared under full light, and it has to be: the element is in the format whatever
		// the piece does with it, and rebind matches by name over the whole format, so a head that
		// dropped it would move every name after it one location down without a word. Iris keeps it
		// for the same reason, renaming vaUV2 in both branches (VanillaCoreTransformer.java:115,119).
		String light = fullbright ? FULL_LIGHT : "vec4(UV2, 0.0, 1.0)";

		lines.add("#define of_Vertex vec4(Position, 1.0)");
		lines.add("#define of_Color Color");
		lines.add("#define of_MultiTexCoord0 vec4(UV0, 0.0, 1.0)");
		lines.add("#define of_MultiTexCoord1 " + light);
		// Unit two is a second name for the light map and not a unit of its own, which is what Iris
		// makes of it as well, VanillaTransformer.java:77 renaming one into the other.
		lines.add("#define of_MultiTexCoord2 " + light);
		lines.addAll(VertexPrologue.blankTexCoords());

		lines.add("#define of_Normal Normal.xyz");

		// The chunk mesh answers mc_Entity out of the block id it carries and this one cannot: an
		// entity mesh has no room for one. So every name here is a constant, including that one,
		// and what the picture is then wrong about is what the caller has to name in the log.
		lines.addAll(VertexPrologue.tail(used, synthesized));

		return List.copyOf(lines);
	}
}
