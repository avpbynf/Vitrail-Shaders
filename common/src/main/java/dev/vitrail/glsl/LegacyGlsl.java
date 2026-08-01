package dev.vitrail.glsl;

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
	 */
	public static final List<String> FULLSCREEN_ATTRIBUTES = List.of(
			"in vec3 Position;",
			"in vec2 UV0;",
			"#define of_Vertex vec4(Position, 1.0)",
			"#define of_MultiTexCoord0 vec4(UV0, 0.0, 1.0)",
			"#define of_Color vec4(1.0)",
			"#define of_Normal vec3(0.0, 0.0, 1.0)");

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
