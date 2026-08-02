package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Sodium's chunk mesh carries, and how the names a pack reads are made out of it.
 * <p>
 * The mesh is twenty bytes: {@code a_Position} as three twenty bit coordinates interleaved across
 * two unsigned integers, {@code a_Color}, {@code a_TexCoord}, and {@code a_LightAndData} whose last
 * two bytes are the material bits and the index of the draw command. The decode below is Sodium's
 * own {@code assets/sodium/shaders/include/chunk_vertex.glsl} and
 * {@code blocks/block_layer_opaque.vsh}, written out rather than included: the include is a file of
 * the game's resource packs and our text never goes through that path.
 * <p>
 * <strong>Four of the six names a pack reads are not in the mesh at all.</strong> There is no
 * normal, no block id, no mid texture coordinate and no tangent, so those are given a constant and
 * named in the log. That is the whole reason this stage is called a narrow door: providing them for
 * real means adding fields to {@code ChunkVertexEncoder$Vertex} and filling them in the mesher.
 * <p>
 * The region offset arrives through push constants, which is the one thing here that has to be got
 * right or nothing else matters. blaze3d never declares a push constant range; Sodium adds one, and
 * only to a pipeline whose location has a namespace <em>containing</em> {@code sodium}. A pipeline
 * named otherwise is pushed twenty bytes into a layout that has no room for them, the offset never
 * arrives, and every region draws itself on top of the one before at the camera.
 */
public final class SodiumVertex {

	/** What the prologue is called. The wrapper around the pack's own {@code main} calls it. */
	public static final String PROLOGUE = "ofSodiumVertex";

	/** The elements of {@code CompactChunkVertex}, in order. The format must carry these and no more. */
	public static final List<String> ATTRIBUTES =
			List.of("a_Position", "a_Color", "a_TexCoord", "a_LightAndData");

	/**
	 * Vertex inputs a pack declares for itself that the chunk mesh does not carry. A declaration of
	 * one of these is taken out of the body and reappears in the header as an ordinary global with a
	 * value, so that the pack compiles and reads a constant instead of an attribute nothing fills.
	 */
	public static final Set<String> SYNTHESIZED =
			Set.of("mc_Entity", "mc_midTexCoord", "mc_chunkFade", "at_tangent", "at_midBlock",
					"vaPosition", "vaNormal", "vaColor", "vaUV0", "vaUV1", "vaUV2", "dhMaterialId");

	/**
	 * The two fixed function attributes the mesh cannot answer, and every texture unit above the
	 * light map. Declared whether the pack mentions them or not costs nothing; not declaring one the
	 * pack does mention costs the program.
	 */
	private static final Map<String, String> FIXED = fixed();

	/**
	 * A tangent of nought is not harmless. Every pack that reads one normalises it, and
	 * {@code normalize(vec3(0))} is a division by nought whose NaN travels into the colour through
	 * the tangent frame. So this one gets an axis rather than a zero.
	 */
	private static final Map<String, String> BETTER_DEFAULTS = Map.of(
			"at_tangent", "vec4(1.0, 0.0, 0.0, 1.0)",
			"of_Normal", "vec3(0.0, 1.0, 0.0)");

	private SodiumVertex() {
	}

	/**
	 * The head of a terrain vertex stage: the four attributes, the region push constants, the four
	 * names the mesh answers, and whatever the pack reads that it does not.
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

		// Named apart from the pack's own uniforms on purpose. This block is not in the bind group
		// and never reaches the reflection the engine binds by name: SPIRV-Cross lists push
		// constants under a resource type the game does not ask for, which is why Sodium's own
		// shader gets away with the same declaration.
		lines.add("layout(push_constant) uniform OfSodiumRegion {");
		lines.add("\tvec3 of_RegionOffset;");
		lines.add("\tint of_RegionAge;");
		lines.add("\tuint of_RegionId;");
		lines.add("};");

		lines.add("vec4 of_Vertex;");
		lines.add("vec4 of_Color;");
		lines.add("vec4 of_MultiTexCoord0;");
		lines.add("vec4 of_MultiTexCoord1;");

		// The pack's own declaration first, so that a pack asking for a vec2 mc_Entity gets a vec2.
		synthesized.forEach((name, type) -> lines.add(declare(type, name)));
		FIXED.forEach((name, type) -> {
			if (used.contains(name) && !synthesized.containsKey(name)) {
				lines.add(declare(type, name));
			}
		});

		for (String name : SYNTHESIZED) {
			if (used.contains(name) && !synthesized.containsKey(name)) {
				lines.add(declare(defaultType(name), name));
			}
		}

		lines.add("void " + PROLOGUE + "() {");
		lines.add("\tuvec3 ofHi = (uvec3(a_Position.x) >> uvec3(0u, 10u, 20u)) & 0x3FFu;");
		lines.add("\tuvec3 ofLo = (uvec3(a_Position.y) >> uvec3(0u, 10u, 20u)) & 0x3FFu;");
		// 32 over two to the twentieth, and minus eight: a section is sixteen blocks and the mesh
		// reaches eight blocks either side of it for the faces of its neighbours.
		lines.add("\tvec3 ofLocal = vec3((ofHi << 10u) | ofLo) * (32.0 / 1048576.0) - 8.0;");
		// Which section of the region this draw command belongs to, packed by LocalSectionIndex.
		// Leaving it out is not subtle: every section of a region lands on the region's own corner.
		lines.add("\tvec3 ofSection = vec3(uvec3(a_LightAndData.w) >> uvec3(5u, 0u, 2u)"
				+ " & uvec3(7u, 3u, 7u)) * 16.0;");
		lines.add("\tof_Vertex = vec4(ofLocal + of_RegionOffset + ofSection, 1.0);");
		lines.add("\tof_Color = a_Color;");
		// The top bit of each texture coordinate says which side of its sprite this corner is on,
		// and the coordinate is pulled that way by a fraction of a texel. Leaving it out is not
		// invisible: a corner that lands exactly on a sprite's edge picks up the neighbouring sprite
		// of the atlas, which shows as a fringe along the top of every block of grass.
		lines.add("\tvec2 ofBias = mix(vec2(-1.0), vec2(1.0), bvec2(a_TexCoord >> 15u));");
		lines.add("\tof_MultiTexCoord0 = vec4(ofBias * of_TexShrink"
				+ " + vec2(a_TexCoord & 0x7FFFu) / 32768.0, 0.0, 1.0);");
		lines.add("\tof_MultiTexCoord1 = vec4(vec2(a_LightAndData.xy) / 256.0, 0.0, 1.0);");
		lines.add("}");

		return List.copyOf(lines);
	}

	/** What a name the mesh does not carry is worth, given the type the pack declared it under. */
	public static String value(String name, String type) {
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

	private static String declare(String type, String name) {
		return type + " " + name + " = " + value(name, type) + ";";
	}

	/** The type a name takes when the pack reads it without ever declaring it. */
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

	/** The GLSL type of one of Sodium's four elements. */
	private static String type(String attribute) {
		return switch (attribute) {
			case "a_Position", "a_TexCoord" -> "uvec2";
			case "a_LightAndData" -> "uvec4";
			default -> "vec4";
		};
	}

	private static Map<String, String> fixed() {
		Map<String, String> names = new LinkedHashMap<>();
		names.put("of_Normal", "vec3");
		for (int unit = 2; unit <= 7; unit++) {
			names.put("of_MultiTexCoord" + unit, "vec4");
		}

		return Collections.unmodifiableMap(names);
	}
}
