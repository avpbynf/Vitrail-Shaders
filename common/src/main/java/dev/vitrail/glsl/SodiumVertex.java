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
 * The mesh is twenty bytes, laid out as follows. {@code a_Position} holds three coordinates of
 * twenty bits each, split so that the top ten bits of x, y and z sit at bits 0, 10 and 20 of its
 * first component and the bottom ten at the same places of its second; a coordinate counts
 * thirty-two blocks over its full range and starts eight blocks before the section, which is the
 * reach a mesh needs for the faces of its neighbours. {@code a_Color} is the block tint already
 * multiplied by the ambient occlusion. {@code a_TexCoord} keeps fifteen bits of texture coordinate
 * per axis and spends its top bit on which side of the sprite the corner lies. {@code a_LightAndData}
 * holds the block light, the sky light, a byte of material bits and the index of the draw command,
 * one per byte.
 * <p>
 * <strong>Sodium is under the PolyForm Shield licence, which this project cannot take code
 * from.</strong> So what is written below is this engine's own reading of that layout and not a
 * transcription of Sodium's shader: the layout is a fact about the bytes, the way of undoing it is
 * ours. Nothing here is copied, and nothing here may be replaced by something copied.
 * <p>
 * <strong>Three of the names a pack reads are not in the mesh at all.</strong> There is no block id,
 * no mid texture coordinate and no tangent, so those are given a constant and named in the log. The
 * normal is not among them any more: the facing rides in the spare bits of the material byte, which
 * costs the mesh nothing. Providing the block id for real is what still needs a fifth element on the
 * format.
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
	 * Where the quad's facing sits in the material byte, and why there is room for it.
	 * <p>
	 * {@code packLightAndData} gives the material a whole byte and Sodium's own
	 * {@code chunk_material.glsl} uses three bits of it, one for the mipmap and two for the alpha
	 * cutoff. The facing needs three more, {@code ModelQuadFacing} having seven values, so it goes
	 * in the five that were spare and the mesh does not grow by one byte. What is stored is the
	 * ordinal PLUS ONE, so that nought keeps its meaning: nobody wrote a facing here. Fluids take
	 * the other push site and are not hooked, so nought really happens.
	 */
	public static final int FACING_SHIFT = 3;
	public static final int FACING_MASK = 7;

	/**
	 * Every texture unit above the light map. Declared whether the pack mentions them or not costs
	 * nothing; not declaring one the pack does mention costs the program.
	 * <p>
	 * {@code of_Normal} used to be here and is not any more: the facing arrives in the mesh, so the
	 * prologue works it out instead of standing one in.
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
		lines.add("vec3 of_Normal;");

		// In ModelQuadFacing's own order, shifted up by one so that index nought is the quad nobody
		// wrote a facing for. Index seven is UNASSIGNED, a quad aligned on no axis at all, which the
		// mesh cannot describe with one normal; both fall back to up rather than to nought, because
		// every pack normalises what it reads and normalize(vec3(0)) is a NaN in the colour.
		lines.add("const vec3 ofFacingNormals[8] = vec3[8]("
				+ "vec3(0.0, 1.0, 0.0), "
				+ "vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0), "
				+ "vec3(-1.0, 0.0, 0.0), vec3(0.0, -1.0, 0.0), vec3(0.0, 0.0, -1.0), "
				+ "vec3(0.0, 1.0, 0.0));");

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

		// One coordinate out of the pair, given where its ten bit halves sit in each component.
		lines.add("float ofAxis(uint at) {");
		lines.add("\tuint top = (a_Position.x >> at) & 1023u;");
		lines.add("\tuint bottom = (a_Position.y >> at) & 1023u;");
		// Thirty-two blocks across the full twenty bit range, starting eight blocks early.
		lines.add("\treturn float(top * 1024u + bottom) * (32.0 / 1048576.0) - 8.0;");
		lines.add("}");

		// Where the section this draw command belongs to sits inside its region, in blocks. The
		// index packs eight sections across, four up and eight deep. Leaving this out is not
		// subtle: every section of a region lands on the region's own corner.
		lines.add("vec3 ofSectionOrigin(uint index) {");
		lines.add("\treturn vec3(float((index >> 5u) & 7u), float(index & 3u),"
				+ " float((index >> 2u) & 7u)) * 16.0;");
		lines.add("}");

		lines.add("void " + PROLOGUE + "() {");
		lines.add("\tvec3 ofLocal = vec3(ofAxis(0u), ofAxis(10u), ofAxis(20u));");
		lines.add("\tof_Vertex = vec4(ofLocal + of_RegionOffset"
				+ " + ofSectionOrigin(a_LightAndData.w), 1.0);");
		lines.add("\tof_Color = a_Color;");
		// The top bit of each texture coordinate says which side of its sprite this corner is on,
		// and the coordinate is pulled that way by a fraction of a texel. Leaving it out is not
		// invisible: a corner that lands exactly on a sprite's edge picks up the neighbouring sprite
		// of the atlas, which shows as a fringe along the top of every block of grass.
		lines.add("\tvec2 ofInward = vec2(a_TexCoord >> 15u) * 2.0 - 1.0;");
		lines.add("\tof_MultiTexCoord0 = vec4(vec2(a_TexCoord & 32767u) / 32768.0"
				+ " + ofInward * of_TexShrink, 0.0, 1.0);");
		lines.add("\tof_MultiTexCoord1 = vec4(vec2(a_LightAndData.xy) / 256.0, 0.0, 1.0);");
		lines.add("\tof_Normal = ofFacingNormals[int((a_LightAndData.z >> " + FACING_SHIFT
				+ "u) & " + FACING_MASK + "u)];");
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
		for (int unit = 2; unit <= 7; unit++) {
			names.put("of_MultiTexCoord" + unit, "vec4");
		}

		return Collections.unmodifiableMap(names);
	}
}
