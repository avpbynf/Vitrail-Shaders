package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the game's own entity mesh carries, and how the names a pack reads are made out of it.
 * <p>
 * {@code DefaultVertexFormat.ENTITY} holds six elements in thirty-six bytes: {@code Position} as
 * three floats, {@code Color} as four normalised bytes, {@code UV0} as two floats, {@code UV1} and
 * {@code UV2} as two signed shorts each, {@code Normal} as four signed bytes. Every pipeline built
 * on the entity snippets binds that format and nothing else, so unlike the chunk mesh there is
 * nothing to unpack and nothing to push: the five names a pack reads are the elements themselves
 * under another spelling.
 * <p>
 * <strong>All six are declared, and no fewer.</strong> The pairing is by name and asymmetric in
 * both directions. A name the stage declares that the format has not got is refused outright,
 * {@code IntermediaryShaderModule.rebind:205-207}. An element the stage does not declare is simply
 * stepped over, and since {@code VulkanRenderPipeline} counts every element while {@code rebind}
 * only counts the ones it found, everything after the gap lands one location too low without a
 * word being said.
 * <p>
 * <strong>The light map is {@code UV2} and not {@code UV1}.</strong> {@code UV1} is the overlay,
 * the hit flash and the damage tint, and it is the one element nothing here answers for: Iris
 * makes {@code entityColor} out of it by fetching an overlay texture this engine does not bind
 * yet, {@code EntityPatcher.patchOverlayColor}. So it is declared and read by nobody, which is
 * safe for the one reason that matters and is measured rather than assumed: the off-game harness
 * reads the SPIR-V of every entity stage of the corpus back and checks that all six variables are
 * still in it, because a variable the compiler dropped is one {@code rebind} cannot find.
 * <p>
 * Written as macros rather than as globals for the reason {@link LegacyGlsl#FULLSCREEN_ATTRIBUTES}
 * gives: a global initialised from a vertex input is not a constant expression and the language
 * refuses it, and the chunk mesh only escapes that by having a prologue it needs anyway. Here
 * there is nothing to compute, so there is no prologue to hang it on.
 */
public final class EntityVertex {

	/** The elements of the entity mesh, in the format's own order. */
	public static final List<String> ATTRIBUTES =
			List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal");

	private EntityVertex() {
	}

	/**
	 * The head of an entity vertex stage: the six elements, the names a pack reads made out of
	 * five of them, and whatever the pack asks for that the mesh has not got.
	 *
	 * @param used        every name the rewritten body mentions, so that nothing is declared for a
	 *                    pack that never asks
	 * @param synthesized the vertex inputs the pack declared for itself and that were taken out of
	 *                    the body, by name and with the type the pack gave them
	 */
	public static List<String> prologue(Set<String> used, Map<String, String> synthesized) {
		List<String> lines = new ArrayList<>();

		for (String attribute : ATTRIBUTES) {
			lines.add("in " + type(attribute) + " " + attribute + ";");
		}

		lines.add("#define of_Vertex vec4(Position, 1.0)");
		lines.add("#define of_Color Color");
		lines.add("#define of_MultiTexCoord0 vec4(UV0, 0.0, 1.0)");
		lines.add("#define of_MultiTexCoord1 vec4(UV2, 0.0, 1.0)");
		// Unit two is a second name for the light map and not a unit of its own, which is what Iris
		// makes of it as well, VanillaTransformer.java:77 renaming one into the other.
		lines.add("#define of_MultiTexCoord2 vec4(UV2, 0.0, 1.0)");
		// Above that the mesh has nothing. Iris makes unit three an alias of mc_midTexCoord and
		// hands back this constant for four to seven; no program of the corpus reads any of them,
		// so what these lines buy is that such a program names something rather than nothing.
		for (int unit = 3; unit <= 7; unit++) {
			lines.add("#define of_MultiTexCoord" + unit + " vec4(0.0, 0.0, 0.0, 1.0)");
		}

		lines.add("#define of_Normal Normal.xyz");

		// The pack's own declaration first, so that a pack asking for a vec2 mc_Entity gets a vec2.
		Map<String, String> globals = new LinkedHashMap<>(synthesized);
		for (String name : SodiumVertex.SYNTHESIZED) {
			if (used.contains(name) && !globals.containsKey(name)) {
				globals.put(name, SodiumVertex.defaultType(name));
			}
		}

		// The chunk mesh answers mc_Entity out of the block id it carries and this one cannot: an
		// entity mesh has no room for one. So every name here is a constant, including that one,
		// and what the picture is then wrong about is what the caller has to name in the log.
		globals.forEach((name, type) ->
				lines.add(type + " " + name + " = " + SodiumVertex.value(name, type) + ";"));

		return List.copyOf(lines);
	}

	/** The GLSL type of one element of the format. */
	private static String type(String attribute) {
		return switch (attribute) {
			case "Position" -> "vec3";
			case "UV0" -> "vec2";
			case "UV1", "UV2" -> "ivec2";
			default -> "vec4";
		};
	}
}
