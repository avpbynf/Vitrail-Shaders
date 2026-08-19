package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Distant Horizons' own LOD mesh carries, and how the names a {@code dh_} program reads are
 * made out of it.
 * <p>
 * Sixteen bytes a vertex, in six elements: the position as three unsigned shorts of block
 * coordinates inside the section, a word of meta carrying the light and a sub-block nudge, the
 * colour as four normalised bytes, a material byte, a face byte, and a texture tile nobody here
 * reads. The layout is DH's own,
 * {@code common/render/blaze/BlazeDhTerrainRenderer.java:124-131}, and the stride is checked at
 * runtime against what DH's buffers really hold rather than trusted.
 * <p>
 * <strong>The position is a section's and not the world's, which is why this head reads a uniform
 * block.</strong> Three unsigned shorts cannot hold a world coordinate, so DH keeps the section's
 * own corner out of the mesh and hands it to the draw; every buffer of one section shares it. Iris
 * takes it the same way, off the event parameter and into a {@code modelOffset} uniform
 * ({@code compat/dh/IrisLodRenderProgram.java:126}, set per buffer at {@code :252-253}), and turns
 * it into the vertex a pack reads with {@code getVertexPosition()}
 * ({@code DHTerrainTransformer.java:84-85}). What arrives at
 * {@code gl_Vertex} is therefore the corner plus the local position minus the camera, which is the
 * camera relative world space every other family of this engine hands a pack.
 * <p>
 * <strong>The two nibbles of the light are the way round the builder wrote them and not the way
 * round DH's own shader reads them.</strong> {@code LodQuadBuilder.putVertex} packs
 * {@code skylight | (blocklight << 4)}, so the HIGH nibble is the block light; DH's own vertex stage
 * calls the high one {@code skyLight} and samples its own light map with it that way round, which is
 * its business and not a contract. Iris hands the high nibble first
 * ({@code DHTerrainTransformer.java:135}), which is the vanilla order, block light in x and sky
 * light in y, and that is what a pack reads {@code lmCoord} as everywhere else. So the builder is
 * what this head follows.
 * <p>
 * <strong>The pair is handed over RAW, as the chunk mesh hands it, and that is not a divergence from
 * Iris although the two lines look nothing alike.</strong> Iris answers
 * {@code gl_TextureMatrix[1]} with the identity on this family and normalises the pair itself, level
 * {@code i} arriving as {@code (i + 0.5) / 16}; this engine answers that matrix with the sixteen
 * numbers the fixed function pipeline held and hands the level as {@code 16i}, which comes back out
 * of the product as {@code i / 16 + 1 / 32}. The two are the same number. Handing normalised
 * coordinates here would be the divergence, the matrix being scaled either way.
 * <p>
 * <strong>There is no texture coordinate at all.</strong> Iris answers
 * {@code gl_MultiTexCoord0} with {@code vec4(0.0, 0.0, 0.0, 1.0)} on this family
 * ({@code DHTerrainTransformer.java:32-33}), so an LOD is flat coloured and the atlas is never
 * sampled for it. DH's Blaze renderer does carry a tile identifier into an atlas of its own, which is what
 * the sixth element is, and reading it here would put a texture on the far terrain that no pack
 * under Iris has ever seen.
 * <p>
 * <strong>Only the elements the pack really reads are declared, and the format is built to
 * match.</strong> That is the chunk mesh's rule and it is forced by the same mechanism:
 * {@code VulkanRenderPipeline} gives every element of the format a location counting up from nought
 * ({@code :109-117}) while {@code IntermediaryShaderModule.rebind} only counts the ones that
 * survived into the compiled module ({@code :148-162}), so an element declared and never read may be
 * dropped and take the location of every element after it down one. Here the position and the meta
 * are always read, the last element is never declared at all, and the three in between are declared
 * exactly when something reads them. What keeps the offsets right for a mesh with a hole in it is
 * the stride form of {@code VertexFormat.Builder.addAttribute}, which the family that builds the
 * format uses.
 */
public final class DistantVertex {

	/** The function the wrapper calls before the pack's own body, which fills what the mesh answers. */
	public static final String PROLOGUE = "ofDistantVertex";

	/** The block carrying the one value that belongs to the section rather than to the vertex. */
	public static final String SECTION_BLOCK = "OfDistantSection";

	/** Its one member: where this section's corner stands, in blocks, relative to the camera. */
	public static final String SECTION_OFFSET = "of_SectionOffset";

	/** Three unsigned shorts of block coordinates inside the section. */
	public static final String POSITION = "vPosition";

	/** The light in the low byte and the sub-block nudge in the high one. */
	public static final String META = "meta";

	/** Four normalised bytes, the colour DH worked out for the quad. */
	public static final String COLOUR = "vColor";

	/** One byte, the block category a pack branches on as {@code dhMaterialId}. */
	public static final String MATERIAL = "irisMaterial";

	/** One byte, which of the six faces this quad is, and the whole of what a normal can be. */
	public static final String NORMAL = "irisNormal";

	/**
	 * The elements DH's mesh carries, in its own order. The sixth, the texture tile, is deliberately
	 * absent: nothing here reads it, and being last it is the one element that can be left off
	 * without moving another.
	 */
	public static final List<String> ATTRIBUTES = List.of(POSITION, META, COLOUR, MATERIAL, NORMAL);

	/** The two nothing may leave off: without them there is no vertex at all. */
	private static final Set<String> ALWAYS = Set.of(POSITION, META);

	/** The name of {@link VertexPrologue#SYNTHESIZED} this mesh answers for real. */
	public static final Set<String> ANSWERED = Set.of("dhMaterialId");

	/** What the translation has turned {@code gl_Normal} into by the time this head is written. */
	private static final String OWN_NORMAL = "of_Normal";

	/**
	 * How far a nudged corner moves, in blocks. DH's own renderer writes this number into its shared
	 * block as a literal, {@code common/render/blaze/BlazeDhTerrainRenderer.java:208}, so there is
	 * nothing to read it out of: a constant here is that literal and not a taste. What it is for is
	 * that two LOD faces meeting at a corner are pulled apart by a hundredth of a block, so the
	 * seam between them does not flicker.
	 */
	private static final String MICRO = "0.01";

	private DistantVertex() {
	}

	/**
	 * Which elements one vertex stage really reads, out of the three that may be left off.
	 * <p>
	 * Asked of the translation between preparing a stage and writing it, exactly as the chunk mesh
	 * asks it, and the union over the pack's {@code dh_} programs is what the format is built from.
	 *
	 * @param used        every name the rewritten body mentions
	 * @param synthesized the vertex inputs the pack declared for itself, by name and type
	 */
	public static Set<String> reads(Set<String> used, Map<String, String> synthesized) {
		Set<String> reads = new LinkedHashSet<>(ALWAYS);
		if (used.contains("of_Color")) {
			reads.add(COLOUR);
		}

		// The body reads gl_Normal; the translation has already turned it into of_Normal by the time
		// this is asked, which is why the name tested is not the pack's own.
		if (used.contains(OWN_NORMAL)) {
			reads.add(NORMAL);
		}

		if (VertexPrologue.globals(used, synthesized, Map.of()).containsKey("dhMaterialId")) {
			reads.add(MATERIAL);
		}

		return reads;
	}

	/** The whole format for a pack reading those elements: DH's order, and only what was asked for. */
	public static List<String> carried(Set<String> reads) {
		return ATTRIBUTES.stream().filter(reads::contains).toList();
	}

	/**
	 * The head of a {@code dh_} vertex stage: the elements the mesh carries, the section's own
	 * corner, the names made out of the two, and constants for everything an LOD has not got.
	 *
	 * @param carried     the elements really declared, in the format's own order and the same list
	 *                    the format was built from
	 * @param used        every name the rewritten body mentions, so that nothing is declared for a
	 *                    pack that never asks
	 * @param synthesized the vertex inputs the pack declared for itself and that were taken out of
	 *                    the body, by name and with the type the pack gave them
	 */
	public static List<String> prologue(List<String> carried, Set<String> used,
			Map<String, String> synthesized) {
		List<String> lines = new ArrayList<>();

		for (String attribute : carried) {
			lines.add("in " + type(attribute) + " " + attribute + ";");
		}

		lines.add("layout(std140) uniform " + SECTION_BLOCK + " {");
		lines.add("\tvec3 " + SECTION_OFFSET + ";");
		lines.add("};");

		lines.add("vec4 of_Vertex;");
		lines.add("vec4 of_Color;");
		lines.add("vec3 of_Normal;");

		// The six faces of a block, in the order DH's own directions number themselves: DOWN, UP,
		// NORTH, SOUTH, WEST, EAST, which is the faceIndex of EDhDirection and what the builder puts
		// on the vertex (LodQuadBuilder.java:392). Iris carries the same six in the same order
		// (DHTerrainTransformer.java:117, read at :133), and the order is read off DH rather than
		// off Iris because a table copied out of the second would agree with the first only by luck.
		if (carried.contains(NORMAL)) {
			lines.add("const vec3 ofDistantFaces[6] = vec3[6](vec3(0.0, -1.0, 0.0), "
					+ "vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0), "
					+ "vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0));");
		}

		// Nothing of the atlas and nothing above the light map, which is what an LOD has not got.
		lines.add("#define of_MultiTexCoord0 vec4(0.0, 0.0, 0.0, 1.0)");
		lines.add("#define of_MultiTexCoord1 vec4(ofDistantLight(), 0.0, 1.0)");
		lines.add("#define of_MultiTexCoord2 vec4(ofDistantLight(), 0.0, 1.0)");
		lines.addAll(VertexPrologue.blankTexCoords());

		lines.add("vec2 ofDistantLight() {");
		lines.add("\treturn vec2(float((" + META + " >> 4u) & 15u), float(" + META
				+ " & 15u)) * 16.0;");
		lines.add("}");

		// The nudge, out of the six bits above the light: two a coordinate, the low one saying there
		// is an offset and the high one saying it is negative. The Y pair is decoded by neither
		// engine and is dropped here as well: DH's own stage leaves the line commented out
		// (terrain/blaze/vert.vsh:60-61 and :66) and Iris works the term out and then leaves it out of the
		// position (DHTerrainTransformer.java:132). An LOD is a heightmap, so its horizontal seams
		// are the ones a nudge is for.
		lines.add("vec3 ofDistantNudge() {");
		lines.add("\tuint at = (" + META + " >> 8u) & 63u;");
		lines.add("\tfloat x = (at & 1u) != 0u ? " + MICRO + " : 0.0;");
		lines.add("\tfloat z = (at & 16u) != 0u ? " + MICRO + " : 0.0;");
		lines.add("\treturn vec3((at & 2u) != 0u ? -x : x, 0.0, (at & 32u) != 0u ? -z : z);");
		lines.add("}");

		Map<String, String> globals = VertexPrologue.globals(used, synthesized, Map.of());
		globals.forEach((name, type) -> lines.add(ANSWERED.contains(name) && carried.contains(MATERIAL)
				? type + " " + name + ";"
				: VertexPrologue.declaration(name, type)));

		lines.add("void " + PROLOGUE + "() {");
		lines.add("\tof_Vertex = vec4(vec3(" + POSITION + ") + " + SECTION_OFFSET
				+ " + ofDistantNudge(), 1.0);");
		// The colour DH worked out and nothing multiplied into it. DH's own stage multiplies its light
		// map into it there and then; a pack lights its own fragment out of lmCoord, so the product
		// would be the light applied twice. Iris hands the element alone as well.
		lines.add("\tof_Color = " + (carried.contains(COLOUR) ? COLOUR : "vec4(1.0)") + ";");
		lines.add("\tof_Normal = " + (carried.contains(NORMAL) ? "ofDistantFaces[" + NORMAL + "]"
				: VertexPrologue.value(OWN_NORMAL, "vec3")) + ";");
		if (globals.containsKey("dhMaterialId") && carried.contains(MATERIAL)) {
			lines.add("\tdhMaterialId = " + material(globals.get("dhMaterialId")) + ";");
		}

		lines.add("}");

		return List.copyOf(lines);
	}

	/**
	 * The material byte in the shape the pack declared the name under. Iris declares it an
	 * {@code int} and assigns {@code int(irisExtra.x)} ({@code DHTerrainTransformer.java:134}); a
	 * pack that spelled it as something else gets that spelling, the way every other name a mesh
	 * answers does.
	 */
	private static String material(String type) {
		return switch (type) {
			case "int" -> "int(" + MATERIAL + ")";
			case "uint" -> MATERIAL;
			case "float" -> "float(" + MATERIAL + ")";
			default -> type + "(" + MATERIAL + ")";
		};
	}

	/** The GLSL type of one element, which is the one its format gives it and no reading of ours. */
	private static String type(String attribute) {
		return switch (attribute) {
			case POSITION -> "uvec3";
			case COLOUR -> "vec4";
			// META, MATERIAL and NORMAL: an unsigned word and two unsigned bytes.
			default -> "uint";
		};
	}
}
