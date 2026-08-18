package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What every vertex head has in common: the names no mesh of this engine carries, the types the
 * game's own formats give their elements, and the texture units nothing fills.
 * <p>
 * The chunk mesh, the entity mesh and the sky each wrote these three pieces out for themselves, and
 * the entity work is about to add a fourth head that would write them again. Neither copy is
 * harmless. The tail is an answer to
 * "what is {@code at_tangent} worth when the mesh has not got one", which is a question about the
 * name and not about the mesh, so a family answering it differently from its neighbour is a
 * difference nothing would ever explain. The element types are worse, because a wrong one is
 * silent: {@code DefaultVertexFormat} spells {@code UV1} and {@code UV2} as pairs of signed shorts
 * wherever they appear, so a head declaring one of them a {@code vec2} where its neighbour
 * declares an {@code ivec2} reads a different number from the same bytes, and nothing anywhere
 * complains. The list of names is not in here and must not be: it is the format the pass binds,
 * and a head declaring one fewer than that moves the location of every element after it,
 * {@code IntermediaryShaderModule.rebind:151-163} counting only what the stage declared where
 * {@code VulkanRenderPipeline:109-117} counts the whole format.
 * <p>
 * Sodium's chunk mesh keeps a table of its own all the same, in {@link SodiumVertex}, and that is
 * not an oversight: its elements are twenty packed bytes of its own invention, {@code uvec2} and
 * {@code uvec4} where the game's formats are floats, so the two tables answer different questions
 * and merging them would be merging a coincidence.
 */
public final class VertexPrologue {

	/**
	 * Vertex inputs a pack declares for itself, or reads without declaring, that no mesh of this
	 * engine carries. A declaration of one of these is taken out of the body and reappears in the
	 * header as an ordinary global with a value, so that the pack compiles and reads that value
	 * instead of an attribute nothing fills.
	 * <p>
	 * Sorted, and not a set literal, for the reason {@link LegacyGlsl} gives about its maps: the
	 * heads walk this to write their globals, and a literal hands its names back in an order the
	 * runtime picks afresh on every start. Reverie's terrain stage had {@code dhMaterialId} and
	 * {@code mc_chunkFade} swap places between two runs, which is the same text to a reader and a
	 * different shader to the game, so it recompiles a pipeline it already has.
	 */
	public static final Set<String> SYNTHESIZED = Collections.unmodifiableSet(new TreeSet<>(
			List.of("mc_Entity", "mc_midTexCoord", "mc_chunkFade", "at_tangent", "at_midBlock",
					"vaPosition", "vaNormal", "vaColor", "vaUV0", "vaUV1", "vaUV2", "dhMaterialId")));

	/**
	 * A tangent of nought is not harmless. Every pack that reads one normalises it, and
	 * {@code normalize(vec3(0))} is a division by nought whose NaN travels into the colour through
	 * the tangent frame. So this one gets an axis rather than a zero.
	 */
	private static final Map<String, String> BETTER_DEFAULTS = Map.of(
			"at_tangent", "vec4(1.0, 0.0, 0.0, 1.0)",
			"of_Normal", "vec3(0.0, 1.0, 0.0)");

	private VertexPrologue() {
	}

	/**
	 * Every name this stage reads that its mesh has not got, in the order the head declares them.
	 *
	 * @param used        every name the rewritten body mentions, so that nothing is declared for a
	 *                    pack that never asks
	 * @param synthesized the vertex inputs the pack declared for itself and that were taken out of
	 *                    the body, by name and with the type the pack gave them. First, so that a
	 *                    pack asking for a vec2 {@code mc_Entity} gets a vec2
	 */
	private static Map<String, String> globals(Set<String> used, Map<String, String> synthesized) {
		return globals(used, synthesized, Map.of());
	}

	/**
	 * The globals the vertex prologue has to declare, and the text each is given.
	 *
	 * @param ahead names to answer between the pack's own and {@link #SYNTHESIZED}. Only the chunk
	 *              mesh has any: its head hands back the texture units above the light map as
	 *              globals rather than as macros, and where they sit decides the text
	 */
	public static Map<String, String> globals(Set<String> used, Map<String, String> synthesized,
			Map<String, String> ahead) {
		Map<String, String> globals = new LinkedHashMap<>(synthesized);

		ahead.forEach((name, type) -> {
			if (used.contains(name) && !globals.containsKey(name)) {
				globals.put(name, type);
			}
		});

		for (String name : SYNTHESIZED) {
			if (used.contains(name) && !globals.containsKey(name)) {
				globals.put(name, defaultType(name));
			}
		}

		return globals;
	}

	/** One of them as the head writes it: a global of the pack's own type, holding a constant. */
	public static String declaration(String name, String type) {
		return type + " " + name + " = " + value(name, type) + ";";
	}

	/** The whole tail, for a head with nothing of its own to say about any of these names. */
	public static List<String> tail(Set<String> used, Map<String, String> synthesized) {
		List<String> lines = new ArrayList<>();
		globals(used, synthesized).forEach((name, type) -> lines.add(declaration(name, type)));

		return lines;
	}

	/**
	 * The texture units above the light map, which no mesh of the game carries.
	 * <p>
	 * Iris makes unit three an alias of {@code mc_midTexCoord} and hands back this constant for
	 * four to seven; no program of the corpus reads any of them, so what these lines buy is that
	 * such a program names something rather than nothing.
	 */
	public static List<String> blankTexCoords() {
		List<String> lines = new ArrayList<>();
		for (int unit = 3; unit <= 7; unit++) {
			lines.add("#define of_MultiTexCoord" + unit + " vec4(0.0, 0.0, 0.0, 1.0)");
		}

		return lines;
	}

	/**
	 * The GLSL type of one element of a format of the game's own.
	 * <p>
	 * One table for every such format rather than one per family, because the type belongs to the
	 * element and not to the pass that binds it: {@code DefaultVertexFormat} spells {@code UV1} and
	 * {@code UV2} as pairs of signed shorts wherever they appear, and a family reading one of them
	 * as a float would read a different number from the same bytes.
	 * <p>
	 * {@link EntityVertex#IDENTIFIERS} is in here rather than beside its own head for that same
	 * reason and no other: it is an element of a format like the six around it, and the one format
	 * that carries it is the one this engine appends it to.
	 */
	public static String elementType(String element) {
		return switch (element) {
			case "Position" -> "vec3";
			case "UV0" -> "vec2";
			case "UV1", "UV2" -> "ivec2";
			case EntityVertex.IDENTIFIERS -> "uvec4";
			default -> "vec4";
		};
	}

	/** What a name the mesh does not carry is worth, given the type the pack declared it under. */
	private static String value(String name, String type) {
		String better = BETTER_DEFAULTS.get(name);

		return better != null && better.startsWith(type + "(") ? better : zero(type);
	}

	/** Nought of a type, spelled the way the type takes it. */
	public static String zero(String type) {
		return switch (type) {
			case "float" -> "0.0";
			case "int" -> "0";
			case "uint" -> "0u";
			case "bool" -> "false";
			case "vec2", "vec3", "vec4" -> type + "(0.0)";
			case "ivec2", "ivec3", "ivec4" -> type + "(0)";
			case "uvec2", "uvec3", "uvec4" -> type + "(0u)";
			case "bvec2", "bvec3", "bvec4" -> type + "(false)";
			// A matrix attribute is not something the corpus has, and a constructor from one scalar
			// is the identity rather than a zero. Left as the language's own default so that a pack
			// which somehow declares one still compiles.
			default -> type + "(0.0)";
		};
	}

	/**
	 * The type a name takes when the pack reads it without ever declaring it. Asked by every mesh
	 * and not by one of them: the question is what the name means, which no mesh gets a say in.
	 */
	private static String defaultType(String name) {
		return switch (name) {
			case "dhMaterialId" -> "int";
			case "mc_chunkFade" -> "float";
			case "at_midBlock", "vaPosition", "vaNormal" -> "vec3";
			case "vaUV1", "vaUV2" -> "ivec2";
			case "vaUV0" -> "vec2";
			default -> "vec4";
		};
	}
}
