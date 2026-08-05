package dev.vitrail.glsl;

import dev.vitrail.pack.program.ProgramFallbacks;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	 * One residual difference from Iris, which costs nothing measurable here: Iris also overwrites
	 * {@code gl_TextureMatrix} with the identity in a full screen pass, where ours comes from the
	 * uniform block, so {@code of_TextureMatrix[1] * of_MultiTexCoord1} gives that matrix's
	 * translation column rather than exactly nought. No program of the corpus reads it: the only
	 * live site is the body of a function no full screen pass calls, and an uncalled function is
	 * gone before the SPIR-V is emitted.
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
