package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Sodium's chunk mesh carries, and how the names a pack reads are made out of it.
 * <p>
 * Sodium's own twenty bytes are laid out as follows. {@code a_Position} holds three coordinates of
 * twenty bits each, split so that the top ten bits of x, y and z sit at bits 0, 10 and 20 of its
 * first component and the bottom ten at the same places of its second; a coordinate counts
 * thirty-two blocks over its full range and starts eight blocks before the section, which is the
 * reach a mesh needs for the faces of its neighbours. {@code a_Color} is the block tint with the
 * ambient occlusion already multiplied into it, on every mesh and whatever the pack asked: it is
 * Sodium's own word and nothing of this engine writes it. {@code a_TexCoord} keeps fifteen bits of
 * texture coordinate per axis and spends its top bit on which side of the sprite the corner lies.
 * {@code a_LightAndData}
 * holds the block light, the sky light, a byte of material bits and the index of the draw command,
 * one per byte. Everything past the twentieth is this engine's own, six elements of four bytes.
 * <p>
 * <strong>Sodium is under the PolyForm Shield licence, which this project cannot take code
 * from.</strong> So what is written below is this engine's own reading of that layout and not a
 * transcription of Sodium's shader: the layout is a fact about the bytes, the way of undoing it is
 * ours. Nothing here is copied, and nothing here may be replaced by something copied.
 * <p>
 * <strong>Every name a pack reads of this geometry is now in the mesh.</strong> The block id, the
 * middle of the sprite, the offset to the middle of the block, the normal and the tangent are five
 * elements of this engine's own; {@link #TINT_AND_AO} is a sixth, and the one that answers no name
 * of the pack's but the shape of a name it already reads. The facing that used to stand in for a
 * normal, in the spare bits of the material byte, is gone with the last thing that read it.
 * <p>
 * <strong>Which of the six a mesh really carries follows the pack</strong>, and the format is
 * therefore anything from twenty bytes to forty-four. {@link #reads} says what one vertex stage
 * needs, {@link #carried} turns the union over the pack's six chunk programs into the format, and
 * {@link #prologue} declares that list and nothing besides. The union and not each program's own
 * answer: an element the format carries that a stage does not declare shifts the location of every
 * element after it without a word, so what one pass reads is paid for on every vertex by all six.
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

	/**
	 * The block id, packed as Iris packs it: {@code ((id + 1) << 1) | isFluid}, so that nought means
	 * no declaration matched and comes back out as the -1 every pack tests against.
	 * <p>
	 * One of the elements this engine appends. They are last on purpose: an element a shader does not
	 * declare shifts the location of every element AFTER it, silently, and Sodium's own chunk shader
	 * declares the first four and none of ours.
	 */
	public static final String BLOCK_ID = "a_BlockId";

	/**
	 * The middle of the sprite a quad is mapped to, both axes in one element and quantised exactly as
	 * {@code a_TexCoord} is, so that the two divide down by the same number below.
	 * <p>
	 * It is a property of the QUAD and not of a corner: the four vertices carry the same pair. That is
	 * what a pack tests a corner against to know which side of its sprite the corner lies on, which is
	 * how a leaf knows which of its vertices are the top ones and may be moved by the wind.
	 */
	public static final String MID_TEX_COORD = "a_MidTexCoord";

	/**
	 * How far a vertex is from the middle of its own block, per axis and in sixty-fourths, with the
	 * light that block gives off in the fourth component.
	 * <p>
	 * A pack divides it by 64 itself, which is why nothing here does: four packs of the corpus write
	 * {@code at_midBlock.xyz / 64.0} word for word, and the two that declare the name as a
	 * {@code vec4}, Bliss and Reverie, both read its fourth component as the block's own light -
	 * Bliss as an index into its voxel ids, Reverie as a level it divides by fifteen. It is what
	 * places a block in a voxel grid from inside a vertex stage, which is how
	 * the five packs that voxelise their lighting find out where a light actually stands.
	 */
	public static final String MID_BLOCK = "a_MidBlock";

	/**
	 * The quad's own normal, taken from its corners rather than from the axis its facing names.
	 * <p>
	 * The facing this engine read before is one of six axes plus a value meaning none, so anything
	 * that is not a box face got one of six wrong answers: a plant drawn as a cross, a sloped fluid
	 * surface, every custom model. And the facing never reached a translucent quad or a fluid at all,
	 * so glass and water were lit as if they faced up.
	 */
	public static final String NORMAL = "a_Normal";

	/**
	 * The direction the texture's own U axis runs in over this quad, with the handedness of the frame
	 * in the fourth component.
	 * <p>
	 * Every normal map on the terrain is read through it, and this engine handed back a constant,
	 * which tilts all of them the same wrong way. Eight packs of the corpus read {@code at_tangent}.
	 */
	public static final String TANGENT = "a_Tangent";

	/**
	 * The block's tint undivided, with the ambient occlusion in the fourth component rather than
	 * multiplied into the other three. What a pack that wrote {@code separateAo} reads as its vertex
	 * colour, and the one element here that carries no name of the pack's: it is a second shape for
	 * {@code gl_Color}, and {@link #prologue} is where one of the two is chosen.
	 * <p>
	 * <strong>An element of ours and not Sodium's own word rewritten.</strong> {@code a_Color} keeps
	 * the product on every mesh, so the game's own chunk shader draws a correct colour through this
	 * format whatever this engine is doing - and it draws through it often, a chain warming one
	 * program a frame after every load and every resource reload. That shader alpha tests the
	 * product of the vertex colour and the texture, so an occlusion sitting in the alpha it reads
	 * would punch holes through every cutout block. Iris writes the pair over that word instead,
	 * {@code XHFPTerrainVertex.java:152}, and can: nothing of its own warms up over several frames.
	 * <p>
	 * <strong>The cost is that one colour element goes unread whichever way the choice falls</strong>,
	 * and an input a stage declares and never reads may be dropped from the compiled module, where
	 * {@code rebind} counts only the survivors and a drop shifts every location after it. Under
	 * {@code separateAo} the unread one is {@code a_Color}, second of ten. It is not a new kind of
	 * risk - four of the appended elements already go unread for a pack that names none of them -
	 * but it is the first to reach one of Sodium's own four, where a drop takes the texture
	 * coordinate and the light map down with it. So it is measured rather than assumed, the way the
	 * entity family measures it: the off-game harness compiles every chunk program of the corpus
	 * under BOTH colours and reads the module back, checking that each of the ten is still there and
	 * still at the location its place in the format gives it.
	 */
	public static final String TINT_AND_AO = "a_TintAndAo";

	/**
	 * The elements of the chunk mesh, in order. The format must carry these and no more, and the
	 * order after {@code a_LightAndData} is the one {@code TerrainMesh.Extra} lays out.
	 */
	public static final List<String> ATTRIBUTES =
			List.of("a_Position", "a_Color", "a_TexCoord", "a_LightAndData", BLOCK_ID, MID_TEX_COORD,
					MID_BLOCK, NORMAL, TANGENT, TINT_AND_AO);

	/**
	 * The ones of {@link #ATTRIBUTES} this engine appends, which are also the only ones a pack may
	 * be spared. Sodium's own four are never dropped: its encoder writes all four whatever this
	 * engine is doing, and its own chunk shader reads all four.
	 */
	private static final Set<String> OURS =
			Set.of(BLOCK_ID, MID_TEX_COORD, MID_BLOCK, NORMAL, TANGENT, TINT_AND_AO);

	/**
	 * What the translation has turned {@code gl_Normal} into by the time a head is written. The one
	 * name the mesh answers that is a spelling of neither the pack's nor an element's.
	 */
	private static final String OWN_NORMAL = "of_Normal";

	/**
	 * The ones of {@link VertexPrologue#SYNTHESIZED} this mesh answers for real, and the element
	 * each of them is made out of. What is left of that set is what the log has to call a constant,
	 * and so is any of these whose element the pack was not asked to carry.
	 * <p>
	 * A {@link LinkedHashMap} and not a literal, for the reason {@link VertexPrologue#SYNTHESIZED}
	 * gives about its own set: this is walked to write a head, and a literal hands its names back in
	 * an order the runtime picks afresh on every start, which is the same text to a reader and a
	 * different shader to the game.
	 */
	private static final Map<String, String> OUT_OF = outOf();

	/** The names of {@link #OUT_OF}, for a caller asking whether the mesh answers one of them. */
	public static final Set<String> ANSWERED = OUT_OF.keySet();

	/**
	 * Every texture unit above the light map. Declared whether the pack mentions them or not costs
	 * nothing; not declaring one the pack does mention costs the program.
	 * <p>
	 * {@code of_Normal} used to be here and is not any more: the mesh carries a normal of its own.
	 */
	private static final Map<String, String> FIXED = fixed();

	private SodiumVertex() {
	}

	/**
	 * Which of the elements this engine appends one vertex stage really reads.
	 * <p>
	 * The question {@link #prologue} answers for one stage, asked before any text exists so that the
	 * mesh can be built out of the union over the pack's chunk programs. The two walk the same
	 * globals and have to go on doing so: an element left out that a head then names is a stage
	 * declaring an input the format has not got, and {@code IntermediaryShaderModule.rebind:205-207}
	 * refuses the whole module for it.
	 * <p>
	 * <strong>A divergence, and it is one of ORDER rather than of taste.</strong> Iris asks the same
	 * question of the LINKED program, {@code getAttribLocation} on its handle at
	 * {@code pipeline/programs/SodiumPrograms.java:174-177}, so an attribute the compiler eliminated
	 * for being named only in a dead branch costs it nothing. Nothing is compiled where this is
	 * asked: {@code TerrainMesh.settle} is called from the head of Sodium's {@code initRenderer} and
	 * the format has to be settled there, before the chunk renderer that meshes at that stride
	 * exists, and each stage becomes a module of its own here rather than a linked program there
	 * would be anything to query. So the names the rewritten text mentions are what is read. The
	 * format can therefore only be LARGER than Iris's, never smaller, which costs four bytes a vertex
	 * for such a name and nothing at all to the picture.
	 *
	 * @param used        every name the rewritten body mentions
	 * @param synthesized the vertex inputs the pack declared for itself and that were taken out of
	 *                    the body, by name and with the type the pack gave them
	 * @param separateAo  whether the pack asked for the terrain's ambient occlusion to be kept out
	 *                    of its vertex colour. The one answer here that is the PACK's rather than
	 *                    this stage's: it decides which of the two colour elements every chunk
	 *                    program reads, so it decides whether the mesh carries the second one at all
	 */
	public static Set<String> reads(Set<String> used, Map<String, String> synthesized,
			boolean separateAo) {
		Set<String> reads = new LinkedHashSet<>();
		for (String name : VertexPrologue.globals(used, synthesized, FIXED).keySet()) {
			String element = OUT_OF.get(name);
			if (element != null) {
				reads.add(element);
			}
		}

		// Not one of the four, because it answers no name the pack writes: a body reads gl_Normal,
		// and the translation has already turned that into of_Normal by the time this is asked.
		if (used.contains(OWN_NORMAL)) {
			reads.add(NORMAL);
		}

		// Nor is this, for the other reason: it is a second shape for a word the mesh already
		// carries, and what decides whether a stage reads it is the directive and not the body.
		if (separateAo) {
			reads.add(TINT_AND_AO);
		}

		return reads;
	}

	/**
	 * The whole format for a pack whose chunk programs read those elements: Sodium's own four, then
	 * ours in {@link #ATTRIBUTES}'s order and only the ones asked for.
	 * <p>
	 * The union over the pack's six chunk programs and never one program's own answer. Every one of
	 * them declares every element the mesh carries, so an element one pass reads is paid for by all
	 * six; declaring fewer is what shifts the location of everything after the gap.
	 */
	public static List<String> carried(Set<String> reads) {
		return ATTRIBUTES.stream()
				.filter(attribute -> !OURS.contains(attribute) || reads.contains(attribute))
				.toList();
	}

	/**
	 * The head of a terrain vertex stage: the elements the mesh carries, the region push constants,
	 * the names the mesh answers out of them, and whatever the pack reads that no element carries.
	 *
	 * @param carried     the elements the mesh really carries, in the format's own order. Exactly
	 *                    these are declared and no others, which is why it is the union of
	 *                    {@link #reads} over the pack's chunk programs and not this stage's own
	 * @param used        every name the rewritten body mentions, so that nothing is declared for a
	 *                    pack that never asks
	 * @param synthesized the vertex inputs the pack declared for itself and that were taken out of
	 *                    the body, by name and with the type the pack gave them
	 * @param separateAo  whether the pack asked for the terrain's ambient occlusion to be kept out
	 *                    of its vertex colour, which decides which of the two colour elements this
	 *                    stage reads. A property of the PACK and not of this stage, and the mesh
	 *                    carries the second colour exactly when it is set
	 */
	public static List<String> prologue(List<String> carried, Set<String> used,
			Map<String, String> synthesized, boolean separateAo) {
		List<String> lines = new ArrayList<>();

		for (String attribute : carried) {
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

		Map<String, String> globals = VertexPrologue.globals(used, synthesized, FIXED);

		// A name the mesh answers is left uninitialised here and filled in the prologue below, like
		// of_Vertex above it. A global initialiser that reads an attribute is not a constant
		// expression, and the language does not allow one. A name whose element this pack was not
		// asked to carry takes the constant instead, which is the road every name outside ANSWERED
		// has always taken.
		globals.forEach((name, type) -> lines.add(answered(carried, name)
				? type + " " + name + ";"
				: VertexPrologue.declaration(name, type)));

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
		// Sodium's own word holds the tint with the occlusion multiplied into it; ours holds the two
		// apart, and separateAo is the pack saying it means to see them apart. Both are on every
		// vertex, so this line is the whole of what the directive decides and no mesh is rebuilt
		// for it. The word left unread here is the one the game's own shader goes on reading.
		lines.add("\tof_Color = " + (separateAo ? TINT_AND_AO : "a_Color") + ";");
		// The top bit of each texture coordinate says which side of its sprite this corner is on,
		// and the coordinate is pulled that way by a fraction of a texel. Leaving it out is not
		// invisible: a corner that lands exactly on a sprite's edge picks up the neighbouring sprite
		// of the atlas, which shows as a fringe along the top of every block of grass.
		lines.add("\tvec2 ofInward = vec2(a_TexCoord >> 15u) * 2.0 - 1.0;");
		lines.add("\tof_MultiTexCoord0 = vec4(vec2(a_TexCoord & 32767u) / 32768.0"
				+ " + ofInward * of_TexShrink, 0.0, 1.0);");
		// The light map RAW, as the mesh carries it, which is a pair of levels from nought to two
		// hundred and forty. This used to divide by 256 here, and dividing here is the same mistake
		// as answering gl_TextureMatrix[1] with the identity, seen from the other end: the scale
		// belongs in that matrix, where the pack applies it itself and where the half texel that
		// centres the sample on its level comes with it. Iris carries the raw pair too,
		// SodiumTransformer.java:228, and every other family of this engine already did.
		lines.add("\tof_MultiTexCoord1 = vec4(vec2(a_LightAndData.xy), 0.0, 1.0);");
		// Asked of the carried list and not of ANSWERED, this being the one name of the head that is
		// no spelling of the pack's. A pack no chunk program of which reads gl_Normal leaves the
		// element off the mesh, and what stands here is then the constant every other family hands
		// back for a mesh with no normal in it.
		lines.add("\tof_Normal = " + (carried.contains(NORMAL)
				? NORMAL + ".xyz"
				: VertexPrologue.value(OWN_NORMAL, "vec3")) + ";");
		globals.forEach((name, type) -> {
			if (answered(carried, name)) {
				lines.add("\t" + name + " = " + answer(name, type) + ";");
			}
		});

		lines.add("}");

		return List.copyOf(lines);
	}

	/** Whether the mesh answers this name AND was asked to carry the element it comes out of. */
	private static boolean answered(List<String> carried, String name) {
		String element = OUT_OF.get(name);

		return element != null && carried.contains(element);
	}

	/** One of the names the mesh answers, in the shape the pack declared it under. */
	private static String answer(String name, String type) {
		return switch (name) {
			case "mc_midTexCoord" -> midTexCoord(type);
			case "at_midBlock" -> midBlock(type);
			case "at_tangent" -> tangent(type);
			default -> entity(type);
		};
	}

	/**
	 * {@code mc_midTexCoord} out of the element, in the shape the pack declared it under.
	 * <p>
	 * The stored pair is the mean of the quad's corners under the same {@code 1 << 15} Sodium
	 * quantises a corner with, so the division here is the one the corner coordinate already gets.
	 * The shapes are Iris's, form for form, from {@code SodiumTransformer.replaceMidTexCoord}: a pack
	 * declaring a float reads the u alone, and one declaring three or four components gets nought and
	 * one in the places the pair does not fill.
	 * <p>
	 * A type this does not know keeps its zero, which is a pack declaring this name as something the
	 * corpus has never used.
	 */
	private static String midTexCoord(String type) {
		String pair = "vec2(" + MID_TEX_COORD + ") * (1.0 / 32768.0)";

		return switch (type) {
			case "float" -> "float(" + MID_TEX_COORD + ".x) * (1.0 / 32768.0)";
			case "vec2" -> pair;
			case "vec3" -> "vec3(" + pair + ", 0.0)";
			case "vec4" -> "vec4(" + pair + ", 0.0, 1.0)";
			default -> VertexPrologue.zero(type);
		};
	}

	/**
	 * {@code at_tangent} out of the element, in the shape the pack declared it under.
	 * <p>
	 * Four components is the shape every pack of the corpus declares, the fourth being the handedness
	 * that says which way the third axis of the tangent frame turns. A pack declaring three loses it
	 * and gets the direction alone, which is what asking for three means.
	 */
	private static String tangent(String type) {
		return switch (type) {
			case "float" -> "float(" + TANGENT + ".x)";
			case "vec2" -> "vec2(" + TANGENT + ".xy)";
			case "vec3" -> "vec3(" + TANGENT + ".xyz)";
			case "vec4" -> TANGENT;
			// A tangent is an axis and not a zero: a pack normalises what it reads here, and
			// normalize(vec3(0)) puts a NaN in the colour. That rule is carried by the vec3 and vec4
			// arms above, which hand back a whole direction, and by the at_tangent entry of
			// VertexPrologue.BETTER_DEFAULTS, which answers for a vec4 declaration alone, each entry
			// there being matched against the spelling the pack used.
			//
			// It is NOT carried by this arm, which answers zero for every type but a matrix, any
			// more than by the float and vec2 arms, which take a component or two and so answer zero
			// for a tangent along Z - nor, on the road that has no mesh element at all, by anything
			// but that vec4 entry, a pack declaring three components getting the same zero there.
			// Every at_tangent declaration of the corpus is a vec4, so no pack reaches the gap; it
			// is a gap all the same.
			default -> type.startsWith("mat") ? type + "(1.0)" : VertexPrologue.zero(type);
		};
	}

	/**
	 * {@code at_midBlock} out of the element, in the shape the pack declared it under.
	 * <p>
	 * Nothing is scaled here. The element holds sixty-fourths of a block and every pack that reads
	 * this divides by 64 itself, so a division on this side would be applied twice and put every
	 * block a pack voxelises sixty-four times too close to its own corner.
	 * <p>
	 * A pack declaring three components gets the offset alone and one declaring four gets the block's
	 * light with it, which is the shape Bliss and Reverie read. Both shapes are Iris's own: its
	 * chunk format declares four signed bytes unnormalised, {@code IrisChunkMeshAttributes.MID_BLOCK},
	 * so a three component declaration there drops the light in exactly the same way.
	 */
	private static String midBlock(String type) {
		return switch (type) {
			case "float" -> "float(" + MID_BLOCK + ".x)";
			case "vec2" -> "vec2(" + MID_BLOCK + ".xy)";
			case "vec3" -> "vec3(" + MID_BLOCK + ".xyz)";
			case "vec4" -> "vec4(" + MID_BLOCK + ")";
			case "ivec2" -> MID_BLOCK + ".xy";
			case "ivec3" -> MID_BLOCK + ".xyz";
			case "ivec4" -> MID_BLOCK;
			default -> VertexPrologue.zero(type);
		};
	}

	/**
	 * {@code mc_Entity} out of the packed element, in the shape the pack declared it under.
	 * <p>
	 * The unpacking is Iris's, term for term, because the packs are written against Iris and not
	 * against a reading of OptiFine: {@code x} is the number from {@code block.properties} or -1
	 * where nothing matched, and {@code y} is one for a fluid. The engine that writes the element has
	 * to pack it the same way round, which is the only thing the two sides share.
	 * <p>
	 * A type this does not know keeps its zero, which is a pack reading {@code mc_Entity} as
	 * something the corpus has never declared it as.
	 */
	private static String entity(String type) {
		String id = "int(" + BLOCK_ID + " >> 1u) - 1";
		String fluid = BLOCK_ID + " & 1u";

		return switch (type) {
			case "float", "int" -> id;
			// Iris writes the signed form straight into a uint here, which no strict compiler takes.
			case "uint" -> "uint(" + id + ")";
			case "vec2" -> "vec2(" + id + ", " + fluid + ")";
			case "vec3" -> "vec3(" + id + ", " + fluid + ", 0.0)";
			case "vec4" -> "vec4(" + id + ", " + fluid + ", 0.0, 1.0)";
			case "ivec2" -> "ivec2(" + id + ", " + fluid + ")";
			case "ivec3" -> "ivec3(" + id + ", " + fluid + ", 0)";
			case "ivec4" -> "ivec4(" + id + ", " + fluid + ", 0, 1)";
			default -> VertexPrologue.zero(type);
		};
	}

	/** The GLSL type of one element of the format. */
	private static String type(String attribute) {
		return switch (attribute) {
			case "a_Position", "a_TexCoord", MID_TEX_COORD -> "uvec2";
			case MID_BLOCK -> "ivec4";
			case NORMAL, TANGENT, TINT_AND_AO -> "vec4";
			case "a_LightAndData" -> "uvec4";
			case BLOCK_ID -> "uint";
			default -> "vec4";
		};
	}

	private static Map<String, String> outOf() {
		Map<String, String> names = new LinkedHashMap<>();
		names.put("mc_Entity", BLOCK_ID);
		names.put("mc_midTexCoord", MID_TEX_COORD);
		names.put("at_midBlock", MID_BLOCK);
		names.put("at_tangent", TANGENT);

		return Collections.unmodifiableMap(names);
	}

	private static Map<String, String> fixed() {
		Map<String, String> names = new LinkedHashMap<>();
		for (int unit = 2; unit <= 7; unit++) {
			names.put("of_MultiTexCoord" + unit, "vec4");
		}

		return Collections.unmodifiableMap(names);
	}
}
