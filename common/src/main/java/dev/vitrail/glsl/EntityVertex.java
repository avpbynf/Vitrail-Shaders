package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What the entity mesh carries, and how the names a pack reads are made out of it.
 * <p>
 * {@code DefaultVertexFormat.ENTITY} holds six elements in thirty-six bytes: {@code Position} as
 * three floats, {@code Color} as four normalised bytes, {@code UV0} as two floats, {@code UV1} and
 * {@code UV2} as two signed shorts each, {@code Normal} as four signed bytes. This engine appends
 * three more, {@link #IDENTIFIERS}, {@link #MID_TEX_COORD} and {@link #TANGENT}, on a format of its
 * OWN built out of those six: Sodium writes every cuboid of a mob at the game's own stride and
 * copies the run over raw whenever the two formats are the same object, so lengthening the game's
 * would copy fifty-six bytes a vertex out of thirty-six and leave no mob on screen.
 * {@code EntityMesh} is where that is built, and it carries what a draw a loaded pack does not serve
 * then binds.
 * <p>
 * <strong>The three are Iris's own three and in its own order</strong>
 * ({@code vertices/IrisVertexFormats.java:49-60}: {@code iris_Entity}, then {@code mc_midTexCoord},
 * then {@code at_tangent}). The last two are what a pack asks of a POLYGON rather than of a corner,
 * the four corners of a quad carrying one pair and one tangent between them;
 * {@code render/EntityFrame} works both out, and the two roads that write them in are
 * {@code sodium/EntityMeshSerializer} for a mob and {@code mixin/BufferBuilderMixin} for everything
 * else this format draws.
 * <p>
 * <strong>All nine are declared, and no fewer.</strong> The pairing is by name and asymmetric in
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
 * ({@code EntityPatcher.patchOverlayColor}). See {@code GlslTranslator.overlayPrologue}. All nine
 * elements are declared whether or not a pack asks for any of them, which is safe for the one reason
 * that matters and is measured rather than assumed: the off-game harness reads the SPIR-V of every
 * entity stage of the corpus back and checks that all nine variables are still in it, because a
 * variable the compiler dropped is one {@code rebind} cannot find.
 * <p>
 * Written as macros rather than as globals for the reason {@link LegacyGlsl#FULLSCREEN_ATTRIBUTES}
 * gives: a global initialised from a vertex input is not a constant expression and the language
 * refuses it, and the chunk mesh only escapes that by having a prologue it needs anyway. Here
 * there is nothing to compute, so there is no prologue to hang it on.
 * <p>
 * <strong>A macro is not shadowable where a global is</strong>, and that is the one thing it costs.
 * A pack that spells one of these names for something of its own meets a substitution rather than a
 * name, and what comes out does not parse. The attribute a pack declares is answered by the
 * translation, which takes that declaration out of the body before the head is written; a LOCAL of
 * the same name inside a function is answered by nothing, and would have compiled under a global.
 * Both are measured rather than argued: the off-game harness translates and compiles every entity
 * stage of the corpus, so either would have failed there, and none does.
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
	 * The middle of the sprite a polygon is mapped to, as the pair of floats {@code UV0} already
	 * spells a corner in, so that the two are compared without a scale between them.
	 * <p>
	 * It is a property of the POLYGON and not of a corner: the four corners of a quad carry the same
	 * pair. That is what a pack tests a corner against to know which side of its sprite the corner
	 * lies on, and on an entity it is what tells a cape's lower edge from its upper one, or picks the
	 * middle texel of a sprite to read a colour from without an edge in it.
	 */
	public static final String MID_TEX_COORD = "MidTexCoord";

	/**
	 * The direction the texture's U axis runs in over a polygon, and the handedness of the frame it
	 * builds with the normal, in four normalised bytes as the game spells {@code Normal} beside it.
	 * <p>
	 * A property of the polygon like {@link #MID_TEX_COORD}, and the one every normal map on an
	 * entity is read through: a pack builds its tangent frame out of this and {@code gl_Normal}, so
	 * an engine answering it with a constant tilts every bump on every mob the same wrong way.
	 * <p>
	 * <strong>The fourth component is not padding</strong>: it is plus or minus one and says which
	 * way the third axis of the frame turns, which is what a pack multiplies its bitangent by. A skin
	 * whose sprite is mirrored lights its bumps as dents without it. {@code EntityFrame.tangent} is
	 * where it is worked out.
	 */
	public static final String TANGENT = "Tangent";

	/**
	 * The three this engine appends, in the order {@code EntityMesh} appends them and the order they
	 * have to keep: an element sits at the location its place in the format gives it.
	 */
	public static final List<String> APPENDED = List.of(IDENTIFIERS, MID_TEX_COORD, TANGENT);

	/**
	 * The elements of the entity mesh, in the format's own order, the six the game lays out and the
	 * three this engine appends after them. {@code EntityMesh} is what appends them.
	 */
	public static final List<String> ATTRIBUTES = Stream.concat(
			Stream.of("Position", "Color", "UV0", "UV1", "UV2", "Normal"), APPENDED.stream())
			.toList();

	/**
	 * The ones of {@link VertexPrologue#SYNTHESIZED} this mesh answers for real rather than with a
	 * constant. What is left of that set is what the log has to call a constant, and
	 * {@code EntityProgram} is what hands this on to it.
	 * <p>
	 * {@code mc_Entity} is not in here and is the one worth naming: the chunk mesh serves it out of
	 * the block id it carries, and an entity is not a block state and has no id to travel on. Iris
	 * does not serve it on this mesh either.
	 */
	public static final Set<String> ANSWERED = Set.of("mc_midTexCoord", "at_tangent");

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
	 * The head of an entity vertex stage: the nine elements, the names a pack reads made out of
	 * seven of them, and whatever the pack asks for that the mesh has not got.
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

		// The two the mesh really carries are macros over their element, like every name above them
		// and for the same reason: a global initialised from a vertex input is not a constant
		// expression. The rest stay constants, mc_Entity among them, and what the picture is then
		// wrong about is what the caller has to name in the log.
		VertexPrologue.globals(used, synthesized, Map.of()).forEach((name, type) -> lines.add(
				ANSWERED.contains(name)
						? "#define " + name + " " + answer(name, type)
						: VertexPrologue.declaration(name, type)));

		return List.copyOf(lines);
	}

	/** One of the two names the mesh answers, in the shape the pack declared it under. */
	private static String answer(String name, String type) {
		return name.equals("at_tangent") ? tangent(type) : midTexCoord(type);
	}

	/**
	 * {@code mc_midTexCoord} out of the element, in the shape the pack declared it under.
	 * <p>
	 * The shapes are what a driver would have handed a stage declaring that many components against
	 * an element carrying two, which is exactly what a pack meets under Iris: its element is the
	 * pair and the pack's own declaration is the input. So three components get a nought and four
	 * get a nought and a one, and the two engines answer alike whatever the pack wrote. A type this
	 * does not know keeps its zero, which is a pack declaring this name as something the corpus has
	 * never used.
	 */
	private static String midTexCoord(String type) {
		return switch (type) {
			case "float" -> MID_TEX_COORD + ".x";
			case "vec2" -> MID_TEX_COORD;
			case "vec3" -> "vec3(" + MID_TEX_COORD + ", 0.0)";
			case "vec4" -> "vec4(" + MID_TEX_COORD + ", 0.0, 1.0)";
			default -> VertexPrologue.zero(type);
		};
	}

	/**
	 * {@code at_tangent} out of the element, in the shape the pack declared it under.
	 * <p>
	 * Four components is what every pack of the corpus declares, and the fourth is the handedness
	 * rather than a spare lane. A pack declaring three loses it and gets the direction alone, which
	 * is what asking for three means.
	 */
	private static String tangent(String type) {
		return switch (type) {
			case "float" -> TANGENT + ".x";
			case "vec2" -> TANGENT + ".xy";
			case "vec3" -> TANGENT + ".xyz";
			case "vec4" -> TANGENT;
			default -> VertexPrologue.zero(type);
		};
	}
}
