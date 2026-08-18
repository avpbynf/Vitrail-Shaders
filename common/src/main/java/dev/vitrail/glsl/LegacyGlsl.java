package dev.vitrail.glsl;

import dev.vitrail.pack.program.ProgramFallbacks;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What the OptiFine dialect says and what Vulkan GLSL says instead.
 * <p>
 * Every entry here is a name-for-name substitution with no judgement in it, which is what makes
 * the translation safe to do on text no compiler has read yet. Anything that has to know what the
 * surrounding code means belongs in {@link GlslTranslator}, not here.
 * <p>
 * The tables that get iterated rather than only looked up are wrapped with
 * {@link Collections#unmodifiableMap}, never with {@code Map.copyOf}. The latter does not promise
 * an iteration order and the runtime shuffles it differently on every start, which was enough to
 * make two runs of the translator emit the same uniform block with its members in a different
 * order. Identical input has to give identical text, or the game recompiles pipelines it already
 * has.
 */
public final class LegacyGlsl {

	private LegacyGlsl() {
	}

	/**
	 * Fixed function state, gone since GLSL 140 and still used by every pack in the corpus, with
	 * the declaration each one needs inside the uniform block. The {@code gl_} prefix is reserved,
	 * so these cannot be declared again under their own names: they are renamed and the engine
	 * supplies them.
	 */
	public static final Map<String, String> FIXED_FUNCTION_MEMBERS = fixedFunctionMembers();

	/** Vertex inputs the fixed function pipeline used to provide, with their declarations. */
	public static final Map<String, String> FIXED_ATTRIBUTES = fixedAttributes();

	/** Every {@code gl_} name above, mapped to what it is called after translation. */
	public static final Map<String, String> FIXED_FUNCTION = fixedFunction();

	/** Texture lookups renamed rather than rewritten: same arguments, same meaning. */
	public static final Map<String, String> DEPRECATED_FUNCTIONS = deprecatedFunctions();

	/**
	 * The shadow lookups, which are not a rename. In GLSL 120 {@code shadow2D} returns a
	 * {@code vec4}; the modern {@code texture} on a {@code sampler2DShadow} returns a
	 * {@code float}. The call has to be wrapped, not renamed, and wrapping means finding the
	 * closing parenthesis rather than assuming where it is.
	 */
	public static final Map<String, String> SHADOW_FUNCTIONS = Map.of(
			"shadow2D", "texture",
			"shadow2DLod", "textureLod",
			"shadow2DProj", "textureProj");

	/**
	 * The lookups whose result is what the texture holds, and so a depth when the texture holds
	 * one. Spelled in the names that are left once {@link #DEPRECATED_FUNCTIONS} has been applied,
	 * since that is the point at which the translator asks.
	 * <p>
	 * {@code textureSize}, {@code textureQueryLevels} and {@code textureQueryLod} are missing on
	 * purpose rather than by oversight: they take a depth sampler like the rest and return a size
	 * or a level, which no conversion applies to. The corpus calls none of the three on one.
	 */
	public static final Set<String> DEPTH_LOOKUPS = Set.of(
			"texture", "textureLod", "texelFetch", "textureOffset", "textureLodOffset",
			"textureGrad", "textureProj", "textureProjLod",
			"textureGather", "textureGatherOffset", "textureGatherOffsets");

	/**
	 * Words GLSL 4.60 reserves that packs still use as ordinary names. Renaming them is only safe
	 * where they are used as a name, which the translator decides; this table only says what they
	 * become.
	 */
	public static final Map<String, String> RESERVED_NAMES = Map.of(
			"texture", "ofTexture",
			"sampler", "ofSampler",
			"image", "ofImage");

	/**
	 * Vertex inputs the engine supplies under a name of its own, which packs read without ever
	 * declaring because every other engine declares them. Same standing as the Distant Horizons
	 * defines in {@code EngineDefines}: leaving them out turns a working pack into a wall of
	 * undeclared identifiers, and putting them in is a promise the renderer has to keep.
	 */
	public static final Map<String, String> ENGINE_ATTRIBUTES = engineAttributes();

	/**
	 * Uniforms the engine supplies to a pass that draws an entity, which packs read without ever
	 * declaring for the same reason as {@link #ENGINE_ATTRIBUTES}: no other engine makes them
	 * declare one. Iris does not hand them over as uniforms at all. It feeds all three out of one
	 * {@code ivec3} attribute and rewrites every mention of them into a component of it, so a pack
	 * has no declaration to write and writes none; that is
	 * {@code EntityPatcher.patchEntityId}, read on 3 August 2026.
	 * <p>
	 * Bliss reads {@code entityId} in {@code dimensions/all_translucent.vsh} and declares it in no
	 * file that reaches those programs, which costs six programs of the corpus. The other two are
	 * here because Iris moves the three together and because listing a name nothing reads costs
	 * nothing: only a name a program mentions and never declares is ever put in the block.
	 * <p>
	 * <strong>The order of these three is the order of the lanes of the element the entity mesh
	 * carries them on</strong>, {@code EntityVertex.IDENTIFIERS}, and {@code GlslTranslator} reads
	 * this table's keys rather than keeping a list of its own. Where the mesh does carry them the
	 * block never sees them at all; this table is what a pass with no such mesh gets, which is the
	 * composites and the terrain and the sky.
	 */
	public static final Map<String, String> ENTITY_UNIFORMS = entityUniforms();

	/**
	 * The fixed function pair under the names the core profile spells them with, read undeclared for
	 * the same reason as {@link #ENGINE_ATTRIBUTES} and answered with the same values as their
	 * {@code gl_} twins.
	 * <p>
	 * Not a pair of their own, and that is the whole content of this table. Iris answers the bare
	 * spelling on one path per family and the {@code gl_} spelling on the other, picking between
	 * the two by the profile the unit declares ({@code TransformPatcher.java:146}), and both land on
	 * the same value: a quad gets the identity and the quad projection
	 * ({@code CompositeCoreTransformer.java:20} and {@code :22}, against
	 * {@code CompositeTransformer.java:78} and {@code :81}), the world gets
	 * {@code iris_transforms.ModelViewMat} and {@code iris_ProjMat}
	 * ({@code VanillaCoreTransformer.java:76} and {@code :80}), and a chunk gets Sodium's own
	 * uniforms ({@code SodiumCoreTransformer.java:32} and {@code :36}, against
	 * {@code SodiumTransformer.java:72} and {@code :34}). That lines up with the three catalogues
	 * this engine layers, so the value follows the family without either side saying so twice.
	 * <p>
	 * The vanilla path is the one that names both spellings side by side, and it has to: Iris sends
	 * a line program down the core transformer whatever profile it declares
	 * ({@code TransformPatcher.java:144}), which is exactly the {@code gbuffers_line} below.
	 * <p>
	 * Both Complementary packs multiply {@code projectionMatrix * VIEW_SCALE * modelViewMatrix} in
	 * the line branch of {@code program/gbuffers_basic.glsl} and declare neither. That costs six
	 * entry points of the corpus, and {@code gbuffers_line} is behind no {@code program.enabled}, so
	 * nothing a user can set makes the pack stop asking. A stage that declares one of them itself is
	 * left alone: Body Camera names a function parameter {@code projectionMatrix} and Bliss a local,
	 * and neither means this.
	 */
	public static final Map<String, String> CORE_MATRICES = coreMatrices();

	/**
	 * The name the game binds its own per draw transforms under, which is therefore the name the
	 * block has to be declared with. The members below are ours to name; this one is not.
	 */
	public static final String GAME_TRANSFORMS = "DynamicTransforms";

	/**
	 * What {@code gl_TextureMatrix[0]} becomes wherever the game's block is bound, and the first of
	 * the two members of it anything here reads, {@link #GAME_MODEL_VIEW} being the other.
	 * <p>
	 * Iris makes the same substitution, {@code iris_transforms.TextureMat} at
	 * {@code transform/transformer/VanillaTransformer.java:163} and
	 * {@code VanillaCoreTransformer.java:86}. What that matrix holds is the render type's own
	 * {@code TextureTransform.createMatrix()}, written into the draw's transforms by
	 * {@code rendertype/RenderType.java:76}.
	 * <p>
	 * <strong>Iris substitutes it on every program it patches as vanilla and this engine only where
	 * {@link #bindsGameTransforms} is true</strong>, which is every pass the entity door records: the
	 * entity family, the block entities, the hand, the casters of the shadow map and the glint.
	 * <p>
	 * <strong>What is left out is the sky, the clouds, the weather and the particles, and they differ
	 * in route and not in value.</strong> They are vanilla programs under Iris and read the game's
	 * matrix there; here they keep the identity of
	 * {@link dev.vitrail.uniform.values.GeometryValues}. What makes the two the same number is
	 * measured rather than assumed: a render setup starts at
	 * {@code TextureTransform.DEFAULT_TEXTURING}, which is {@code Matrix4f::new}
	 * ({@code rendertype/RenderSetup.java:131} and {@code rendertype/TextureTransform.java:15}), and
	 * the whole game calls {@code setTextureTransform} six times, at
	 * {@code rendertype/RenderTypes.java:251,259,267,274,524,536}. None of the six is drawn by any of
	 * those four families.
	 * <p>
	 * <strong>Four of those six sites are the glint's, and that is why it is in the set although its
	 * mesh carries no entity.</strong> Without the matrix a glint is drawn frozen on one frame of its
	 * animation, which looks like an image rather than like an absence; the remaining two are the
	 * breeze's wind and the energy swirl, which no door asks for yet.
	 */
	public static final String GAME_TEXTURE_MATRIX = "of_GameTextureMatrix";

	/**
	 * How strong an enchantment's glint is drawn, which is a setting of the player's and the one
	 * thing a glint's vertex colour is made of.
	 * <p>
	 * <strong>A member of this engine's own block and not of the game's, and there Iris does the
	 * opposite.</strong> The number sits in the game's globals block
	 * ({@code renderer/GlobalSettingsUniform.java:29}), and Iris reads it there: its vanilla
	 * transformer declares that very block as {@code iris_Globals}
	 * ({@code transform/transformer/VanillaTransformer.java:65-72}) and writes
	 * {@code iris_globalInfo.GlintAlpha} into the colour ({@code :134}).
	 * <p>
	 * <strong>The difference is a route and not a value</strong>, which is the same shape the texture
	 * matrix's own note carries: what fills the game's member is
	 * {@code gameRenderState.optionsRenderState.glintStrength} ({@code renderer/GameRenderer.java:419}
	 * out of {@code :623}), and that is the field this engine's value store reads, on the same frame.
	 * What it buys is a bind group: reading the block would put a third one on every pipeline of this
	 * family for one float, where the block this engine already writes has room for it.
	 * <p>
	 * Declared for a glint stage that reads a colour and for nothing else, which
	 * {@code GlslTranslator.ownBlock} settles on the vertex inputs. Other meshes have no colour
	 * either - the sky's four formats do not all carry one - but they are answered from what the pass
	 * was set up with rather than from a setting of the player's, so the name would mean nothing to
	 * them.
	 */
	public static final String GLINT_ALPHA = "of_GlintAlpha";

	/**
	 * The game's overlay image, under a name no pack writes, bound wherever a vertex stage was given
	 * the fetch that makes {@code entityColor} out of it.
	 * <p>
	 * Named here rather than inside the translation for the reason {@link #GAME_TRANSFORMS} is: the
	 * name has to be the same character for character in the GLSL, in the bind group and in the call
	 * that binds it, and those are two different classes. Iris calls its own {@code iris_overlay} and
	 * binds the same texture ({@code samplers/IrisSamplers.java} through
	 * {@code pipeline/transform/transformer/EntityPatcher.java:46}).
	 */
	public static final String OVERLAY_SAMPLER = "ofOverlay";

	/**
	 * The model view the game prepared that draw with, which is the second member of its block that
	 * anything here reads and the one place a per draw answer is not optional.
	 * <p>
	 * <strong>Read by every program {@link #readsDrawModelView} answers for, and never on its own:
	 * what they read is {@link #CAMERA_BOB} times this.</strong> That is the entity family, the block
	 * entities, the hand and the glint, which is exactly the set the entity door records from the
	 * camera. The sky, the clouds, the weather and the particles are still answered from the pass,
	 * {@code dev.vitrail.uniform.ViewSource#passModelView}, and the shadow map from the light.
	 * <p>
	 * <strong>The glint is what made this necessary, because it is the first family whose depth test
	 * is an EQUALITY.</strong> Its depth test is {@code EQUAL} and it writes no depth
	 * ({@code RenderPipelines.java:434}), so a glint whose vertex lands a last bit away from the
	 * armour under it does not z-fight, it VANISHES. Two things used to put it there and both are
	 * closed by reading this matrix on both sides. The nudge: its one pipeline carries two, since
	 * {@code ARMOR_ENTITY_GLINT} sets {@code LayeringTransform.VIEW_OFFSET_Z_LAYERING} and the other
	 * three set none ({@code rendertype/RenderTypes.java:252} against {@code :255,263,270}), so no
	 * column of a table of ours could hold it. And the rounding: a product formed in the shader on
	 * one side and on the processor on the other is the same value and not the same bits, which is
	 * what an item lying on the ground showed, its glint carrying no nudge at all.
	 * <p>
	 * Iris meets none of this because it reads the model view here for every vanilla program it
	 * patches, glint included: {@code gl_ModelViewMatrix} becomes
	 * {@code (iris_transforms.ModelViewMat * _iris_internal_translate(iris_transforms.ModelOffset))}
	 * ({@code transform/transformer/VanillaTransformer.java:355-366}, the second factor always built
	 * because {@code hasChunkOffset} is unconditionally true and said to be at {@code :344}, and
	 * always the identity here because the offset of a prepared draw is nought), and
	 * {@code gl_ModelViewProjectionMatrix} is rewritten as the product of that and the projection
	 * ({@code :340-341}) rather than being a matrix of its own.
	 * <p>
	 * <strong>IT IS NOT READ ON ITS OWN HERE, AND THAT IS THE WHOLE OF THE TRANSLATION.</strong> Both
	 * engines agree the walk bob belongs in the model view and disagree about WHERE to put it there.
	 * Iris moves it on the game's own matrices, its {@code mixin/MixinModelViewBobbing.java:68-76}
	 * swallowing the multiplication into the projection and {@code :101} doing
	 * {@code modelViewMatrix.mulLocal(bobStack)} instead, so the per draw matrix it later reads out of
	 * this block already carries the bob and one factor is enough.
	 * {@link dev.vitrail.render.CameraBob} leaves the game's matrices exactly as they are and splits
	 * them only where a pack reads them, so the matrix the game wrote for this draw has no bob in it
	 * and neither has the projection a pack is handed. The product these families need is therefore
	 * {@link #CAMERA_BOB} times this, and a copy of Iris's line would be geometry that stands still
	 * while the camera walks.
	 */
	public static final String GAME_MODEL_VIEW = "of_GameModelView";

	/**
	 * The walk bob and the three effects beside it, as their own matrix, for the passes that have to
	 * multiply them by something only the shader knows.
	 * <p>
	 * The families {@link #readsDrawModelView} answers for take their right hand factor from the
	 * DRAW, {@link #GAME_MODEL_VIEW} saying why, so the two have to meet in the shader and the left
	 * one has to be a name of its own. Everything else is handed {@code bob * view} already
	 * multiplied, under {@code of_ModelViewMatrix}, the right hand factor belonging to the run there.
	 * <p>
	 * It is the very matrix {@code dev.vitrail.render.ViewMatrices#passModelView} multiplies by,
	 * published rather than rebuilt, so that the two roads cannot end up with two different bobs.
	 * <p>
	 * <strong>Four effects for three of the four families and TWO for the hand, and that is a
	 * property of where each is drawn.</strong> What this name means is the left factor that really
	 * placed the geometry of the pass reading it. The entities, the block entities and the glint are
	 * placed by the level's matrix, which carries all four; the hand is drawn under a projection this
	 * engine builds out of the walk bob and the damage tilt alone, the nausea and the portal being a
	 * distortion of the world rather than of the arm.
	 * {@code dev.vitrail.render.ViewMatrices.passBob} carries what handing it the frame's four would
	 * cost, and it is not small: a hand program writes its clip position through this factor.
	 */
	public static final String CAMERA_BOB = "of_CameraBob";

	/**
	 * The game's transforms block, declared exactly as the game fills it.
	 * <p>
	 * All four members and not the two that are read, because std140 matches by OFFSET: the texture
	 * matrix sits at ninety six bytes, behind a {@code mat4}, a {@code vec4} and a {@code vec3}, and a
	 * block declaring only the last of the four would read the model view instead. The order is
	 * {@code DynamicUniforms.Transform.write} at {@code renderer/DynamicUniforms.java:84}, and the
	 * same four in the same order are what Iris declares at
	 * {@code transform/transformer/VanillaTransformer.java:52-57}.
	 * <p>
	 * The other two are named rather than padded so that a reader meets the reason they are here.
	 * Nothing reads them and nothing should: for a draw the game prepares from a render type the
	 * modulator is always white and the offset always nought
	 * ({@code rendertype/RenderType.java:76} reaching the two argument
	 * {@code DynamicUniforms.writeTransform}, {@code renderer/DynamicUniforms.java:48-50}).
	 */
	public static final List<String> GAME_TRANSFORMS_BLOCK = List.of(
			"layout(std140) uniform " + GAME_TRANSFORMS + " {",
			"\tmat4 " + GAME_MODEL_VIEW + ";",
			"\tvec4 of_GameColorModulator;",
			"\tvec3 of_GameModelOffset;",
			"\t" + "mat4 " + GAME_TEXTURE_MATRIX + ";",
			"};");

	/**
	 * The roots of the fallback tree whose programs are drawn from a mesh that carries an entity's
	 * identity, and so are served {@link #ENTITY_UNIFORMS}.
	 * <p>
	 * Iris decides this from the vertex format really bound rather than from a list of names: an
	 * overlay element is what an entity mesh has and a chunk mesh has not,
	 * {@code ShaderAttributeInputs.java:38-40}. A name is what this engine has while it translates,
	 * and the tree makes the two agree, since everything under these five is drawn from that mesh
	 * and nothing else is.
	 */
	private static final Set<String> ENTITY_ROOTS = Set.of(
			"gbuffers_entities", "gbuffers_block", "gbuffers_hand",
			"shadow_entities", "shadow_block");

	/**
	 * The roots of the fallback tree whose programs are drawn from a draw the game itself prepared,
	 * and so are handed the game's own transforms.
	 * <p>
	 * <strong>Not the same question as {@link #ENTITY_ROOTS}, and the glint is where the two part
	 * company.</strong> That set answers what the MESH carries, and an entity mesh carries an
	 * identity; this one answers who PREPARED the draw, and the door that records the glint is the
	 * same one that records a mob, so it binds the same slice for both
	 * ({@code render/EntityDraw.record}). A glint mesh has no entity in it and takes none of
	 * {@link #ENTITY_UNIFORMS}; it is drawn from a render type all the same, and the matrix that
	 * render type was prepared with is the whole of its animation.
	 * <p>
	 * The glint is the one name here that is not already under an entity root, and it enters as a
	 * root rather than through the tree because its parent is {@code gbuffers_textured}
	 * ({@code pack/program/ProgramFallbacks.java:76}), which a sky pass reaches as well. A pack that
	 * serves both out of one file is read twice, once per answer, which is what the translation key
	 * of {@code PackProgram.loadGeometry} carries.
	 */
	private static final Set<String> GAME_DRAW_ROOTS =
			Stream.concat(ENTITY_ROOTS.stream(), Stream.of("gbuffers_armor_glint"))
					.collect(Collectors.toUnmodifiableSet());

	/** The two roots above whose pass is drawn from the light rather than from the camera. */
	private static final Set<String> LIGHT_DRAW_ROOTS = Set.of("shadow_entities", "shadow_block");

	/**
	 * The roots of {@link #GAME_DRAW_ROOTS} whose pass is drawn from the CAMERA, which are the ones a
	 * read of the model view is answered per draw for.
	 * <p>
	 * Derived rather than listed, so that a family added to the entity roots enters both sets at once
	 * and only the exception has to be named.
	 */
	private static final Set<String> CAMERA_DRAW_ROOTS = GAME_DRAW_ROOTS.stream()
			.filter(root -> !LIGHT_DRAW_ROOTS.contains(root))
			.collect(Collectors.toUnmodifiableSet());

	/**
	 * What a full screen pass gets instead of vertex inputs of its own.
	 * <p>
	 * Attributes are matched by name against the elements of the vertex format, in
	 * {@code GlslCompiler.compile}, so a shader declaring {@code of_Vertex} would be looking for
	 * an element nothing provides. Worse, that failure is silent: the location counter only moves
	 * for inputs the format does have, so an unmatched one simply reads whatever is there. The
	 * names here are the ones a full screen quad really carries.
	 * <p>
	 * Written as macros rather than as variables so that the body is left alone, and because a
	 * global initialised from an attribute is not a constant expression and GLSL would refuse it.
	 * The values are Iris's, since a pack's full screen pass is written expecting them: a quad
	 * from (0,0) to (1,1), no colour, a normal pointing at the viewer.
	 * <p>
	 * <strong>The texture units above nought are here and they are not padding.</strong> Iris
	 * substitutes {@code vec4(0.0, 0.0, 0.0, 1.0)} for {@code gl_MultiTexCoord1} through
	 * {@code gl_MultiTexCoord7} in a composite, a deferred or a final, and only unit nought gets a
	 * real attribute: {@code CompositeTransformer.java:51} calling
	 * {@code CommonTransformer.replaceGlMultiTexCoordBounded(t, root, 1, 7)}. Without them the two
	 * Complementary packs do not load at all, at any setting: both write
	 * {@code vec2 lmCoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy} at the top of
	 * {@code lib/util/commonFunctions.glsl}, under {@code #ifdef VERTEX_SHADER} and under nothing
	 * else, so every full screen vertex stage of those packs names an identifier nothing declares.
	 * Fifty four programs of the corpus, and no other pack writes the name at all.
	 * <p>
	 * One residual difference from Iris, in form and not in value: Iris substitutes the identity for
	 * all eight of {@code gl_TextureMatrix} in a full screen pass,
	 * {@code CompositeTransformer.java:43}, where ours arrives through the uniform block. It is eight
	 * identities there too, {@link dev.vitrail.uniform.values.DrawValues}, so the product above is
	 * exactly nought either way, and it is the geometry table that answers the light map's unit with
	 * a matrix rather than an identity. Which is where that difference really lives: a full screen
	 * pass has no light map to sample, and its unit one is nought on both sides.
	 */
	public static final List<String> FULLSCREEN_ATTRIBUTES = List.of(
			"in vec3 Position;",
			"in vec2 UV0;",
			"#define of_Vertex vec4(Position, 1.0)",
			"#define of_MultiTexCoord0 vec4(UV0, 0.0, 1.0)",
			"#define of_MultiTexCoord1 vec4(0.0, 0.0, 0.0, 1.0)",
			"#define of_MultiTexCoord2 vec4(0.0, 0.0, 0.0, 1.0)",
			"#define of_MultiTexCoord3 vec4(0.0, 0.0, 0.0, 1.0)",
			"#define of_MultiTexCoord4 vec4(0.0, 0.0, 0.0, 1.0)",
			"#define of_MultiTexCoord5 vec4(0.0, 0.0, 0.0, 1.0)",
			"#define of_MultiTexCoord6 vec4(0.0, 0.0, 0.0, 1.0)",
			"#define of_MultiTexCoord7 vec4(0.0, 0.0, 0.0, 1.0)",
			"#define of_Color vec4(1.0)",
			"#define of_Normal vec3(0.0, 0.0, 1.0)");

	/** The two lines of {@link #FULLSCREEN_ATTRIBUTES} that are inputs rather than macros. */
	public static final List<String> FULLSCREEN_ELEMENTS = List.of("Position", "UV0");

	/**
	 * Functions GLSL gained after 120 that a pack written against 120 may define for itself. Its
	 * own definition then collides with the built-in one, which is reported as a mismatch of
	 * parameter precision and reads like anything but the name clash it is. Renaming the pack's
	 * version is safe precisely because the built-in did not exist in the dialect it was written
	 * for, so none of its calls can have meant the built-in.
	 * <p>
	 * The corpus only exercises {@code fma} and {@code tanh}. The rest are here because the rename
	 * only fires on a name the pack actually defines, so listing one costs nothing and not listing
	 * one costs a pack.
	 */
	public static final Set<String> POST_120_BUILTINS = Set.of(
			"sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
			"fma", "frexp", "ldexp", "round", "roundEven", "trunc", "modf", "isnan", "isinf",
			"inverse", "determinant", "outerProduct", "transpose",
			"floatBitsToInt", "floatBitsToUint", "intBitsToFloat", "uintBitsToFloat",
			"packUnorm2x16", "unpackUnorm2x16", "packSnorm2x16", "unpackSnorm2x16",
			"packHalf2x16", "unpackHalf2x16",
			"bitfieldExtract", "bitfieldInsert", "bitfieldReverse", "bitCount", "findLSB", "findMSB");

	/**
	 * Built-in type names. Used to tell a declaration from a use: an identifier straight after one
	 * of these is being named, anywhere else it is being read.
	 */
	public static final Set<String> TYPE_NAMES = typeNames();

	/** Precision qualifiers, meaningless on the desktop and a source of overload mismatches. */
	public static final Set<String> PRECISION_QUALIFIERS = Set.of("lowp", "mediump", "highp");

	/**
	 * Qualifiers allowed to stand beside the storage keyword of a declaration. They matter for one
	 * question only: whether an {@code in} that is not the first word of its statement is still a
	 * declaration at file scope or the first parameter of a function.
	 */
	public static final Set<String> INTERPOLATION_QUALIFIERS =
			Set.of("flat", "smooth", "noperspective", "centroid", "sample", "invariant", "precise");

	/** Qualifiers that may sit between {@code uniform} and the type name. */
	public static final Set<String> MEMORY_QUALIFIERS =
			Set.of("restrict", "coherent", "readonly", "writeonly", "volatile");

	/**
	 * Directives whose contents are not code and must not be renamed. {@code #define} is absent on
	 * purpose: its body is code, and only the name it declares is protected.
	 */
	public static final Set<String> OPAQUE_DIRECTIVES =
			Set.of("include", "version", "extension", "line", "pragma", "error");

	/** Directives whose first identifier names a macro rather than uses one. */
	public static final Set<String> NAMING_DIRECTIVES = Set.of("define", "undef", "ifdef", "ifndef");

	/** Type name prefixes that make a uniform opaque, so that it keeps a declaration of its own. */
	private static final Set<String> OPAQUE_PREFIXES =
			Set.of("sampler", "isampler", "usampler", "image", "iimage", "uimage",
					"texture", "itexture", "utexture", "subpassInput", "atomic_uint");

	/**
	 * Whether the pass this program is wanted for draws entities, and so reads
	 * {@link #ENTITY_UNIFORMS} whether it declares them or not.
	 * <p>
	 * Asked of the program the pass wants and never of the file that answers for it. One
	 * {@code gbuffers_textured_lit} serves the entity pass of a pack that ships nothing else and
	 * the sky pass of the same pack, and only the first of the two has an entity to name.
	 *
	 * @param program the bare name, {@code gbuffers_entities_translucent}, or empty where the
	 *                caller is measuring a file and no pass is named
	 */
	public static boolean drawsEntities(String program) {
		return ProgramFallbacks.chain(program).stream().anyMatch(ENTITY_ROOTS::contains);
	}

	/**
	 * Whether the pass this program is wanted for is drawn from a draw the game prepared, and so is
	 * handed {@link #GAME_TRANSFORMS} to read {@link #GAME_TEXTURE_MATRIX} out of.
	 * <p>
	 * Asked of the program the pass wants and never of the file that answers for it, for the reason
	 * {@link #drawsEntities} gives: one file commonly serves a pass of this kind and a pass of
	 * another, and only the first of the two has a prepared draw behind it.
	 * <p>
	 * The runtime asks a narrower question under a name of its own: {@code readsGameTransforms} is
	 * whether the translated program really named the block, which is what decides that a draw binds
	 * it. This one is whether it was allowed to.
	 *
	 * @param program the bare name, {@code gbuffers_armor_glint}, or empty where the caller is
	 *                measuring a file and no pass is named
	 */
	public static boolean bindsGameTransforms(String program) {
		return ProgramFallbacks.chain(program).stream().anyMatch(GAME_DRAW_ROOTS::contains);
	}

	/**
	 * Whether a read of the model view in this program is answered from the DRAW rather than from the
	 * run, which is {@link #CAMERA_BOB} times {@link #GAME_MODEL_VIEW}.
	 * <p>
	 * <strong>Narrower than {@link #bindsGameTransforms} by the two shadow roots, and that is the
	 * whole of the difference.</strong> Every program the entity door records binds the game's block
	 * and reads its texture matrix out of it; only the ones drawn from the CAMERA read its model view
	 * as well.
	 * <p>
	 * <strong>Iris patches its shadow programs as vanilla like the rest ({@code ShaderCreator.java:301},
	 * the shadow twin of {@code :73}), and what its casters read there is the IDENTITY rather than the
	 * light.</strong> Its light is baked into the vertices: the walk poses its submissions on a
	 * {@code PoseStack} the shadow model view was built into
	 * ({@code shadows/ShadowRenderer.java:416}, handed to {@code renderEntities} at {@code :572} and
	 * {@code renderBlockEntities} at {@code :580}, used at {@code :684} and {@code :657-658}), and for
	 * exactly that stretch it sets {@code RenderSystem.getModelViewStack()} to the identity
	 * ({@code :570}, restored at {@code :590}). Its TERRAIN is the other road and is where the light
	 * really does go on the stack, {@code :420-421}.
	 * <p>
	 * <strong>This engine puts the light in the other place, so the same line would mean the opposite
	 * thing.</strong> {@code render/ShadowGeometry.submit} poses its submissions on a fresh
	 * {@code PoseStack} carrying a camera relative translation and nothing else, so a caster's
	 * vertices arrive in PLAYER space and the light has to be the matrix a shadow program reads.
	 * {@code dev.vitrail.uniform.values.ShadowGeometryValues} is what answers it, layered over the
	 * geometry table, from the drawn shadow pair. What the game wrote into a caster's dynamic
	 * transforms is meanwhile whatever the camera left there, the walk standing at the end of a
	 * frame. So a shadow program sent down this road would draw the map from the player's eye, which
	 * is a shadow map of exactly the wrong thing and looks like one all the same.
	 * <p>
	 * Both engines agree on the conclusion and disagree on the route: a shadow program is never handed
	 * the camera's per draw matrix, there because its vertices already carry the light, here because
	 * the matrix it is handed instead is the light's.
	 *
	 * @param program the bare name, {@code gbuffers_entities_translucent}, or empty where the caller
	 *                is measuring a file and no pass is named
	 */
	public static boolean readsDrawModelView(String program) {
		return ProgramFallbacks.chain(program).stream().anyMatch(CAMERA_DRAW_ROOTS::contains);
	}

	/**
	 * Whether a uniform of this type has to keep its own declaration. Everything else is plain
	 * data, and Vulkan will only take that inside a block.
	 */
	public static boolean isOpaqueType(String type) {
		for (String prefix : OPAQUE_PREFIXES) {
			if (type.startsWith(prefix)) {
				return true;
			}
		}

		return false;
	}

	private static Set<String> typeNames() {
		Set<String> names = new HashSet<>(Set.of("void", "bool", "int", "uint", "float", "double"));

		for (String prefix : List.of("", "b", "i", "u", "d")) {
			for (int size = 2; size <= 4; size++) {
				names.add(prefix + "vec" + size);
			}
		}

		for (String prefix : List.of("", "d")) {
			for (int rows = 2; rows <= 4; rows++) {
				names.add(prefix + "mat" + rows);
				for (int columns = 2; columns <= 4; columns++) {
					names.add(prefix + "mat" + rows + "x" + columns);
				}
			}
		}

		return Set.copyOf(names);
	}

	private static Map<String, String> engineAttributes() {
		Map<String, String> attributes = new LinkedHashMap<>();

		attributes.put("dhMaterialId", "int dhMaterialId");

		return Collections.unmodifiableMap(attributes);
	}

	private static Map<String, String> coreMatrices() {
		Map<String, String> matrices = new LinkedHashMap<>();

		matrices.put("modelViewMatrix", "mat4 modelViewMatrix");
		matrices.put("projectionMatrix", "mat4 projectionMatrix");

		return Collections.unmodifiableMap(matrices);
	}

	private static Map<String, String> entityUniforms() {
		Map<String, String> uniforms = new LinkedHashMap<>();

		uniforms.put("entityId", "int entityId");
		uniforms.put("blockEntityId", "int blockEntityId");
		uniforms.put("currentRenderedItemId", "int currentRenderedItemId");

		return Collections.unmodifiableMap(uniforms);
	}

	private static Map<String, String> fixedFunctionMembers() {
		Map<String, String> members = new LinkedHashMap<>();

		members.put("of_ModelViewMatrix", "mat4 of_ModelViewMatrix");
		members.put("of_ModelViewProjectionMatrix", "mat4 of_ModelViewProjectionMatrix");
		members.put("of_ProjectionMatrix", "mat4 of_ProjectionMatrix");
		members.put("of_ModelViewMatrixInverse", "mat4 of_ModelViewMatrixInverse");
		members.put("of_ProjectionMatrixInverse", "mat4 of_ProjectionMatrixInverse");
		members.put("of_NormalMatrix", "mat3 of_NormalMatrix");
		members.put("of_TextureMatrix", "mat4 of_TextureMatrix[8]");
		members.put("of_Fog", "OfFog of_Fog");

		return Collections.unmodifiableMap(members);
	}

	private static Map<String, String> fixedAttributes() {
		Map<String, String> attributes = new LinkedHashMap<>();

		attributes.put("of_Vertex", "vec4 of_Vertex");
		attributes.put("of_Color", "vec4 of_Color");
		attributes.put("of_Normal", "vec3 of_Normal");
		for (int unit = 0; unit <= 7; unit++) {
			attributes.put("of_MultiTexCoord" + unit, "vec4 of_MultiTexCoord" + unit);
		}

		return Collections.unmodifiableMap(attributes);
	}

	private static Map<String, String> fixedFunction() {
		Map<String, String> renames = new LinkedHashMap<>();

		for (String name : fixedFunctionMembers().keySet()) {
			renames.put("gl_" + name.substring(3), name);
		}

		for (String name : fixedAttributes().keySet()) {
			renames.put("gl_" + name.substring(3), name);
		}

		// Not part of the uniform block: it is a varying, and which direction it runs depends on
		// the stage, so the translator declares it rather than this table.
		renames.put("gl_FogFragCoord", "of_FogFragCoord");

		return Collections.unmodifiableMap(renames);
	}

	private static Map<String, String> deprecatedFunctions() {
		Map<String, String> functions = new LinkedHashMap<>();

		functions.put("texture1D", "texture");
		functions.put("texture2D", "texture");
		functions.put("texture3D", "texture");
		functions.put("textureCube", "texture");
		functions.put("texture2DLod", "textureLod");
		functions.put("texture3DLod", "textureLod");
		functions.put("textureCubeLod", "textureLod");
		functions.put("texture2DProj", "textureProj");
		functions.put("texture2DProjLod", "textureProjLod");
		functions.put("texture2DGrad", "textureGrad");
		functions.put("texture2DGradARB", "textureGrad");
		functions.put("texture2DOffset", "textureOffset");
		functions.put("texture2DLodOffset", "textureLodOffset");
		functions.put("texelFetch2D", "texelFetch");
		functions.put("texelFetch3D", "texelFetch");

		return Collections.unmodifiableMap(functions);
	}
}
