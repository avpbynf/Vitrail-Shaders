package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;
import dev.vitrail.glsl.TokenStream.Closing;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.ProgramNames;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.target.ConstDirectives;
import dev.vitrail.pack.target.DrawBuffers;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.SamplerTypes;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns one flattened pack unit into GLSL a Vulkan compiler will take.
 * <p>
 * The pack dialect is GLSL 120 with OptiFine's additions: fixed function state, {@code varying}
 * and {@code attribute}, texture lookups renamed twenty years ago, and plain uniforms declared
 * loose at file scope, which Vulkan does not allow at all. None of that needs the program to be
 * understood, only read, so the work here is a rewrite over a token stream rather than a compiler.
 * <p>
 * The tokens themselves, and every way of reading them or changing them, belong to
 * {@link TokenStream} rather than to this class, and the header written in front of what the
 * passes leave belongs to {@link Emitter}. What is left here is the pipeline: one pass per rule,
 * in the order {@link #rewrite} runs them.
 * <p>
 * Two things are deliberately left undone, and both were done by the measuring prototype.
 * No {@code layout(binding = )} is emitted, and no {@code layout(location = )} except on fragment
 * outputs. The game's own compiler assigns both from SPIR-V reflection and rewrites them
 * afterwards, so a number chosen here is at best ignored and at worst collides with a number
 * chosen for another unit of the same pack. When the prototype was made to number them across a
 * whole pack rather than per file, colliding locations became its largest class of failure by
 * far. Fragment outputs keep theirs, because their order is what maps a write onto a colour
 * attachment and nothing else records that.
 * <p>
 * Conditionals are left standing. The unit that arrives here still carries every {@code #if} of
 * every file that was spliced into it, and the compiler will evaluate them again, so the engine's
 * own symbols are written into the header rather than assumed: if the compiler read them
 * differently from the expander, it would take a different branch and fail somewhere unrelated.
 * <p>
 * Depth is the other rule that is not the language's. The game rasterises with a reversed Z over
 * zero to one and a pack is written for the OpenGL volume, minus one to one with the near plane at
 * minus one; Iris ends by putting the old volume back with {@code glClipControl}, which Vulkan has
 * no equivalent of. So the conversion is emitted into the shader, in both directions, out of the
 * one uniform {@code of_DepthConv}: {@code .xy} says how to write a clip depth, {@code .zw} how to
 * read a window depth back. What it costs to leave undone was measured rather than argued: Body
 * Camera's motion blur asks for a depth past 0.6 and, reading a reversed depth, would only blur
 * what stands within eight centimetres of the camera, so it blurred nothing at all.
 * <p>
 * The fragment outputs carry one rule that is not GLSL's and cannot be read off the language.
	 * 26.2 does not keep the location a stage declares: {@code IntermediaryShaderModule.createFromSpirv}
	 * asks SPIR-V reflection for the outputs and writes the rank of each one over its own location
	 * decoration. The order reflection answers in is the order the compiler first met the names in, so
	 * a stage that writes output one before output zero has the two swapped, and nothing says a word
	 * about it. Everything below about outputs exists for that: they are all declared here, from zero
	 * up with no gaps, and named once each in ascending order by a function ahead of anything the pack
	 * wrote and called first thing in {@code main}. The rank is then the location and the rewrite is
	 * the identity.
	 * <p>
	 * The same rank-is-location rewrite is why a {@code varying mat3} cannot be left as one variable.
	 * A GLSL matrix occupies one location per column, three for a {@code mat3}, but it is still one
	 * reflected name, so the next varying is numbered onto column two. OpenGL links by name and
	 * never asks the question; the workaround here is to split each matrix varying into that many
	 * vectors before compilation and rebuild the matrix as a local, which is what
	 * {@link #splitMatrixVaryings} does. A struct varying is the same case with a member per
	 * location, an array varying with an element per location, and {@link #splitStructVaryings}
	 * and {@link #splitArrayVaryings} do the same to them. Iris is not copied.
 */
public final class GlslTranslator {

	/** The one varying the engine names itself, so the one both stages have to agree about. */
	static final String FOG_COORD = "of_FogFragCoord";

	/**
	 * Prefix of the vectors a matrix varying is split into. Pack names never start with {@code of_},
	 * so a column cannot collide with a varying the pack already declared.
	 */
	private static final String MATRIX_COLUMN = "of_vmat_";

	/** The prefix of a varying standing for one member of a struct varying. */
	private static final String STRUCT_MEMBER = "of_vstruct_";

	/** The prefix of a varying standing for one element of an array varying. */
	private static final String ARRAY_ELEMENT = "of_varr_";

	/**
	 * A declarator's array suffix, one dimension sized by a number the pack wrote out: one to
	 * a few hundred elements, since no interface has that many locations and a zero is no array.
	 */
	private static final Pattern ARRAY_SUFFIX = Pattern.compile("\\[\\s*([1-9]\\d{0,2})\\s*\\]$");

	/**
	 * The member types a struct varying is split over: one location apiece, nothing nested, and
	 * nothing a varying may not be, which rules the booleans out.
	 */
	private static final Set<String> STRUCT_MEMBER_TYPES = Set.of(
			"float", "vec2", "vec3", "vec4", "int", "ivec2", "ivec3", "ivec4",
			"uint", "uvec2", "uvec3", "uvec4", "double", "dvec2", "dvec3", "dvec4");

	/**
	 * The hit flash and the damage tint, which is a varying wherever the mesh carries the overlay and
	 * a uniform everywhere else.
	 * <p>
	 * The name is the pack's and not ours, unlike {@link #FOG_COORD}, which is the whole reason it is
	 * handled here rather than left in the block: a pack declares {@code uniform vec4 entityColor}
	 * and Iris deletes that declaration outright on this mesh, makes the value in the vertex stage
	 * and hands it on ({@code pipeline/transform/transformer/EntityPatcher.java:39-56}). Answering it
	 * from the block instead is one number for every mob on screen, which is a mob that never flashes.
	 */
	static final String ENTITY_COLOR = "entityColor";

	/**
	 * The three identifiers, which are varyings wherever the mesh carries them and uniforms
	 * everywhere else, exactly as {@link #ENTITY_COLOR} is.
	 * <p>
	 * <strong>The order is the order of the lanes of the element</strong> and not a list of its own:
	 * these are {@link LegacyGlsl#ENTITY_UNIFORMS}'s keys, which that table writes in that order for
	 * this reason. A second list here would be the same three names with nothing tying it to the
	 * mesh.
	 * <p>
	 * <strong>The same door as the colour, and it is one gate narrower.</strong> Iris asks its inputs
	 * for the overlay once and calls both patchers on the answer, but the colour carries a second
	 * condition the identifiers have not got: {@code if (!parameters.inputs.isText())}
	 * ({@code pipeline/transform/transformer/VanillaCoreTransformer.java:21-25}). Its other path is
	 * plainer about the same split, calling the identifiers on the overlay OR on text alone
	 * ({@code VanillaTransformer.java:20-24}). The two agree here today for one reason: no text
	 * family is served, so {@code VertexInputs.overlay} answers for both, and the day one is served
	 * this door has to grow that second gate for the colour and open for the identifiers on text.
	 * <p>
	 * <strong>Where Iris rewrites, this renames.</strong> Iris deletes the three declarations and
	 * puts {@code iris_entityInfo.x} and its two neighbours in the place of every read
	 * ({@code EntityPatcher.java:124-152}); here each name stays the name it was and becomes a
	 * varying of its own, which is the same value under the same spelling and one location apiece
	 * instead of one for the three. What it costs is a pack that declared one of them under another
	 * type than {@code int}: Iris would keep that declaration and lose the pass to a redefinition,
	 * and this loses the type. No pack of the corpus writes one.
	 */
	static final List<String> ENTITY_IDS = List.copyOf(LegacyGlsl.ENTITY_UNIFORMS.keySet());

	/**
	 * The game's own overlay image, sixteen by sixteen, under a name no pack writes.
	 * <p>
	 * What the two coordinates mean is the game's: {@code OverlayTexture.pack} puts the white
	 * progress in u and the red flash in v, and the element the mesh carries is that pair.
	 */
	static final String OVERLAY = LegacyGlsl.OVERLAY_SAMPLER;

	/**
	 * How many outputs a fragment stage may declare. Not OptiFine's sixteen colour targets, which
	 * is the other question: a pipeline in 26.2 carries eight colour target states and no more,
	 * {@code ColorTargetState.MAX_COLOR_TARGETS}, and its builder holds them in an array of that
	 * length, so a ninth output has nowhere to land.
	 */
	private static final int MAX_FRAGMENT_OUTPUTS = 8;

	/** Names the ascending prologue, which nothing else in a pack is going to be called. */
	static final String ORDER_OUTPUTS = "ofOrderOutputs";

	/** What the pack's own {@code main} is called once the epilogue has taken the name over. */
	static final String PACK_MAIN = "ofPackMain";

	/** {@code (clipA, clipB, readA, readB)}: how to write a depth, then how to read one. */
	static final String DEPTH_CONV = "of_DepthConv";

	/** The comparison a {@code sampler2DShadow} would have had the hardware make. */
	static final String SHADOW_COMPARE = "ofShadowCompare";

	/**
	 * Whether every comparison goes back to the arithmetic of {@link #SHADOW_COMPARE} instead of
	 * staying on the sampler. The hardware road cannot be watched from inside: a comparison bound
	 * wrong does not fail, it hands back a fraction of the wrong thing, and the picture stays
	 * credible. So the trade is on a switch, the way the pass barrier is: an image that comes right
	 * with this on has named the comparison sampler rather than the pass that shows it, in one
	 * launch and without a build.
	 * <p>
	 * The property is read here so that the harness answers it too; the file in the game directory
	 * is the render side's to see, which is what {@link #askSoftCompare} is for.
	 */
	private static final boolean SOFT_COMPARE = Boolean.getBoolean("vitrail.softShadowCompare");

	private static volatile boolean softCompareArmed;

	/**
	 * What a call to {@code sin} or {@code cos} becomes: a sine of this translation's own, and
	 * never the driver's.
	 * <p>
	 * Packs feed these two whole world coordinates. A single fp32 two-pi sheds the low bits of a
	 * large argument, and the uniformity test rejects the field that reduction leaves at 427 where
	 * white noise scores 15. The form emitted here, a Cody-Waite reduction through two constants,
	 * a fold to the quarter turn, and an odd polynomial, scores 11 to 14 alongside an exact-sine
	 * reference. No driver sine is left anywhere in the call.
	 * <p>
	 * The goldberg hash {@code fract(sin(dot(p, K)) * 43758.5453)} is not sent through this helper.
	 * See {@link #HASH}.
	 * <p>
	 * The substitution can be taken off for a measurement, which leaves the driver's own two in
	 * place and emits neither of these. See {@code reduceTrig} below, which is on wherever nobody
	 * has said otherwise.
	 */
	static final String REDUCED_SIN = "ofReducedSin";

	/** See {@link #REDUCED_SIN}. */
	static final String REDUCED_COS = "ofReducedCos";

	/**
	 * Whether a pack's {@code sin} and {@code cos} are sent through {@link #REDUCED_SIN} at all.
	 * On, which is what a player gets and what every reading taken so far was taken under.
	 * <p>
	 * <strong>It is an instrument, and not a preference anybody is meant to keep.</strong> The
	 * substitution above is unconditional: it does not look at the argument, so a call on a small
	 * one pays the reduction and the polynomial for a value the driver's own sine would have got
	 * right, and one pack of the corpus writes a hundred and thirty six of them in its text,
	 * multiplied by every stage whose includes pull them in. What that costs a
	 * frame has never been measured, and it cannot be measured across two jars: a rate read on one
	 * build and set beside a rate read on another carries every difference between the two runs and
	 * not this one. Both states in one jar, a pack load apart, is what makes the number mean
	 * something.
	 * <p>
	 * What turning it off gives up is the whole reason the helper exists. Packs feed these two
	 * whole world coordinates, and a single fp32 two-pi sheds the low bits of a large argument long
	 * before anybody watching can say where the shimmer came from. {@code DriverTrig} carries how
	 * it is armed, and the line it writes to the log in both directions at every pack load.
	 */
	private static volatile boolean reduceTrig = true;

	/**
	 * The call sites matched since the switch was last set, added up over every unit translated.
	 * <p>
	 * Matched and not substituted, which is the point of it: a reading taken with the driver's own
	 * sine in place still has to be able to say the pack had something for the substitution to bite
	 * on, and a count that only rose in one of the two states would say nothing at all there.
	 * <p>
	 * One window makes it a tally and not an exact figure: a family of the chain that has just
	 * been released finishes translating on its worker (the prefetch only tests for release
	 * between families), and its sites land here after the reset. Nothing of that reaches a live
	 * program, the released chain is never active again; it moves this count and no more.
	 */
	private static final AtomicInteger TRIG_SITES = new AtomicInteger();

	/**
	 * Set at the head of a pack load, before a program of it is translated, and it empties the
	 * count with it: a tally belongs to the load whose state it was taken under.
	 */
	public static void reduceTrig(boolean on) {
		reduceTrig = on;
		TRIG_SITES.set(0);
	}

	/** How many {@code sin} and {@code cos} call sites have been matched since that call. */
	public static int trigSites() {
		return TRIG_SITES.get();
	}

	/**
	 * What the goldberg hash idiom becomes.
	 * <p>
	 * Iris leaves {@code fract(sin(dot(p, K)) * 43758.5453)} as written; the GL driver computes it.
	 * This backend compiles through shaderc to SPIR-V. Feeding that idiom to {@link #REDUCED_SIN}
	 * still jumps in game on BSL: a time-only waving pack that keeps the hash skips, and the same
	 * pack with {@code sin} in place of the hash does not. Complementary never writes the idiom and
	 * was already smooth. Nested {@code fma} on the reduction does not change that. So the call is
	 * replaced by a hash of the argument's bits, which is stable from frame to frame and
	 * uncorrelated from one lattice point to the next.
	 * <p>
	 * What it costs the image is a different noise field, not Iris's gusts. The interpolation
	 * around the lattice, and the amplitude the pack asked for, stay the pack's.
	 */
	static final String HASH = "ofHash";

	/** What a lookup on a volume the pack ships is called once the volume has been laid out flat. */
	private static final String VOLUME_LOOKUP = "ofTexture3D_";

	/** The depth at the centre of the screen, which is accumulated in a texel rather than in a value. */
	private static final String CENTER_DEPTH = "centerDepthSmooth";

	/** The type a pack declares {@link #CENTER_DEPTH} under, and the only one this moves. */
	private static final String CENTER_DEPTH_TYPE = "float";

	/** What the engine declares in place of a value it keeps in a texture. */
	private static final String SAMPLER_2D = "sampler2D";

	/**
	 * The model view a pass drawn from a game prepared draw reads, written once and spelled into
	 * three rewrites so that they cannot drift: the bob this engine publishes in the model view,
	 * times the matrix the game prepared that draw with. {@link #rewriteGameModelView} carries the
	 * whole argument.
	 */
	private static final String DRAW_MODEL_VIEW =
			"(" + LegacyGlsl.CAMERA_BOB + " * " + LegacyGlsl.GAME_MODEL_VIEW + ")";

	/** The one call a volume lookup may be written as, and the number of arguments it takes. */
	private static final String LOOKUP = "texture";
	private static final int LOOKUP_ARGUMENTS = 2;

	/** What the word sampler is followed by when the declaration asks for a comparison. */
	private static final String SHADOW_SHAPE = "Shadow";

	/** The level {@link #pinLookupLevels} writes into a lookup, as GLSL text. */
	private static final String BASE_LEVEL = "0.0";

	/** The suffix of the const directive that asks a chain for a target, {@code TargetDirectives}'s. */
	private static final String MIPMAP_SUFFIX = "MipmapEnabled";

	/** How far into a sprite a chunk mesh's texture coordinate has to be pulled. */
	private static final String TEX_SHRINK = "of_TexShrink";

	/** The colour a sky pass is modulated by, which the sky prologue folds into of_Color. */
	private static final String PASS_COLOUR = "of_PassColour";

	/** Matches the expander's own ceiling: nothing it produces should ever reach this. */
	private static final int MAX_SOURCE_CHARACTERS = 4_000_000;

	/**
	 * How far one macro may lead to another before the name is taken as non-constant, the same
	 * figure {@code PreprocessorExpression} allows a chain of settings.
	 */
	private static final int MAX_MACRO_HOPS = 8;


	private final ExpandedUnit unit;
	private final ProgramStage stage;
	private final Map<String, String> engineDefines;

	/** A pass drawn over a quad rather than over a chunk mesh takes its attributes differently. */
	private final VertexInputs inputs;

	/**
	 * The elements of the vertex format this pass actually binds, which is only ever different from
	 * {@code inputs.elements()} for {@link VertexInputs#SKY}: the sky binds four formats between its
	 * passes and a stage has to declare the one it is drawn with, exactly, or the locations shift.
	 */
	private final List<String> bound;

	/** The program the pass wants, which decides what the engine supplies it undeclared. */
	private final String program;

	/**
	 * The volumes the pack ships and this engine serves flat, by the name the pack samples them
	 * under. Empty everywhere but the two packs of the corpus that ship one.
	 */
	private final Map<String, VolumeAtlas> volumes;

	/** What the fragment stage discards at, which the fixed function pipeline used to hold. */
	private final AlphaTest alphaTest;

	/** Whether the fragment stage is asked for the coverage mask on top of what the pack writes. */
	private final boolean coverage;
	private final TokenStream tokens;

	/** Names the pack defines as macros. Their uses belong to the preprocessor, not to us. */
	private final Set<String> packMacros = new HashSet<>();

	/**
	 * What the replacement text of each macro names, its own parameters left out. Read where a
	 * name has to be judged as the compiler will see it, once the macro is gone.
	 */
	private final Map<String, Set<String>> macroBodies = new HashMap<>();

	/**
	 * The macros whose replacement text is exactly one name, by macro: a second spelling of that
	 * name. Photon declares {@code uniform sampler3D depthtex0} and reads it as
	 * {@code ATMOSPHERE_SCATTERING_LUT}, so a lookup written through the alias is a lookup of the
	 * volume. A macro defined twice to two different names stands for neither here.
	 */
	private final Map<String, String> macroAliases = new HashMap<>();

	/**
	 * What {@link #constantName} answered for each macro, so that a macro named from many places
	 * is judged once: eighty macros naming each other ten at a time would otherwise be walked a
	 * hundred million times within the hop bound.
	 */
	private final Map<String, Boolean> macroConstant = new HashMap<>();

	/** Names the unit declares under a built-in type, which is how a declaration is told apart. */
	private final Set<String> declaredNames = new HashSet<>();

	/** Built-ins added after GLSL 120 that this unit defines a function of its own for. */
	private final Set<String> shadowedBuiltins = new HashSet<>();

	private final Map<String, String> blockMembers = new LinkedHashMap<>();
	private final Map<String, String> samplers = new LinkedHashMap<>();

	/**
	 * What a pack wrote in front of an opaque uniform's type, by the name it declared:
	 * {@code writeonly}, {@code readonly}, {@code coherent} and their kind. Lifting the
	 * declaration into the header rebuilds it from the type onwards, so anything in front of the
	 * type is only still in the shader because it is kept here.
	 */
	private final Map<String, String> memoryQualifiers = new LinkedHashMap<>();

	/** Storage blocks this unit declares at file scope, each under the binding it was written at. */
	private final List<TranslatedUnit.StorageBlock> storageBlocks = new ArrayList<>();

	/** The volumes this unit reads, and so the helpers its header owes, by the pack's own name. */
	private final Map<String, VolumeAtlas> readVolumes = new LinkedHashMap<>();

	/**
	 * The comparison samplers whose comparison is made in arithmetic, their declarations rewritten
	 * ordinary. {@link #collectComparisonSamplers} says how a name lands here rather than below.
	 */
	private final List<Scoped> comparisonSamplers = new ArrayList<>();

	/**
	 * The comparison samplers that keep their spelling, so the lookup compiles to a depth-reference
	 * sample and the binding owes each name a comparison sampler.
	 */
	private final List<Scoped> hardwareComparisonSamplers = new ArrayList<>();

	/**
	 * The samplers a function takes as a parameter, over the lines of that function. Nothing is
	 * rewritten from them; they are what {@link #countDepthLookup} measures the blind spot with.
	 */
	private final List<Scoped> samplerParameters = new ArrayList<>();

	/**
	 * Those of {@link #samplerParameters} whose type has no level to pin, a rectangle, a buffer or
	 * a multisample sampler, so that {@link #pinLookupLevels} leaves a lookup through one alone.
	 */
	private final Set<Scoped> unlevelledParameters = new LinkedHashSet<>();

	/**
	 * The same parameters again, each with the function that takes it and its place in that
	 * function's list, which is what lets {@link #pinLookupLevels} read the call sites and learn
	 * what a parameter can stand for.
	 */
	private final List<SamplerParameter> typedParameters = new ArrayList<>();

	/** Outputs the pack declares itself, by the location it asked for, moved into the header. */
	private final Map<Integer, Output> packOutputs = new TreeMap<>();

	private final Set<String> injectedNames = new HashSet<>();
	private final List<String> conflicts = new ArrayList<>();
	private final List<Integer> drawBuffers = new ArrayList<>();

	/** Vertex inputs the mesh has not got, taken out of the body with the type the pack gave them. */
	private final Map<String, String> synthesized = new LinkedHashMap<>();

	/**
	 * Varyings this stage takes in, declared at file scope on a branch that is taken. Empty for a
	 * vertex stage, whose file scope {@code in} is an attribute and a different matter entirely.
	 */
	private final List<FileScope> declaredInputs = new ArrayList<>();

	/** Varyings this stage hands the next one, which is what says whether an input is provided. */
	private final Set<String> declaredOutputs = new LinkedHashSet<>();

	/** The same, as the declarations they came from, so that one can be taken back out whole. */
	private final List<FileScope> declaredOutputScopes = new ArrayList<>();

	/**
	 * Matrix varyings rewritten as one vector per column, so {@code createFromSpirv} can number
	 * them without overlap. Empty when the stage declared none.
	 */
	private final List<SplitMatrix> splitMatrices = new ArrayList<>();

	/**
	 * Struct varyings rewritten as one varying per member, for the same numbering. Empty when the
	 * stage declared none, which is every stage of the corpus but Photon's.
	 */
	private final List<SplitStruct> splitStructs = new ArrayList<>();

	/**
	 * Array varyings rewritten as one varying per element, for the same numbering again. Empty
	 * when the stage declared none.
	 */
	private final List<SplitArray> splitArrays = new ArrayList<>();

	/** Inputs {@link #dropUnprovidedInputs} took out, which this stage no longer declares. */
	private final Set<String> droppedInputs = new LinkedHashSet<>();

	/**
	 * Inputs nothing before this stage writes and that the body reads anyway, so that they could not
	 * be taken back out: by the name, the interpolation qualifier and the type it was declared
	 * under. What the stage before has to declare for the module to be built at all.
	 */
	private final Map<String, String> unprovidedInputs = new LinkedHashMap<>();

	/** Declarations this stage owes the next one although its own body writes none of them. */
	private final Map<String, String> owedOutputs = new LinkedHashMap<>();

	private Set<String> declaredAfter = Set.of();
	private Set<String> used = Set.of();

	/** Whether the body's own main has been renamed, so that the header carries the one that runs. */
	private boolean mainWrapped;

	private int maxFragmentOutput = -1;
	private boolean ordered;
	private int dynamicFragData;
	private int shadowCalls;
	private int unwrappedShadowCalls;
	private int volumeLookups;
	private int volumesLeftAlone;

	/** The lookups of those that were really rewritten onto {@link #SHADOW_COMPARE}, which is what
	 * says whether the header owes the helper at all. */
	private int softRewrites;

	/** Calls to {@code sin} or {@code cos} sent through the reduced-argument helpers. */
	private int trigCalls;

	/**
	 * Call sites to those two this unit matched, whether or not they were substituted. The same
	 * number as {@link #trigCalls} wherever {@code reduceTrig} is on, which is everywhere a player
	 * is, and the only one of the two that says anything at all when it is off.
	 */
	private int trigSites;

	/** Goldberg hash idioms rewritten onto {@link #HASH} instead of through a sine. */
	private int hashCalls;

	/**
	 * Reads of {@code gl_TextureMatrix[0]} sent to the game's own per draw block instead of to the
	 * uniform block this engine writes.
	 *
	 * @see dev.vitrail.glsl.PackProgram.Loaded#readsGameTransforms
	 */
	private int gameTextureMatrix;

	/**
	 * Reads of the model view sent to that same block, which is the glint alone and covers all three
	 * spellings of it: the matrix, the combined one and {@code ftransform()}.
	 *
	 * @see LegacyGlsl#GAME_MODEL_VIEW
	 */
	private int gameModelView;

	private int strippedExtensions;
	private boolean depthEpilogue;
	private boolean terrainPrologue;

	/** Whether the far terrain's own head is called before the pack's body, for the same reason. */
	private boolean distantPrologue;
	private boolean alphaEpilogue;

	/**
	 * Whether this vertex body was wrapped because it is drawn from the game's own entity mesh, which
	 * is decided while the text is rewritten and before anything knows what of that mesh is wanted.
	 * <p>
	 * One flag for the two things that wrapper carries, the overlay colour and the three identifiers,
	 * because the question it answers belongs to neither of them: it is whether this is that mesh.
	 * <p>
	 * Two flags and not one for the colour, and the reason is when each is knowable. Wrapping renames
	 * the pack's {@code main} and cannot be undone afterwards, so it is settled here off the MESH
	 * alone; whether the colour is really made is a property of the whole program, since a pack
	 * commonly reads {@code entityColor} in its fragment stage and never in its vertex stage, and
	 * that is only known once every stage has been asked. The wrapper standing with nothing in it
	 * costs a call the compiler inlines; the two disagreeing would cost the pass. The identifiers
	 * need no second flag of their own, owing no sampler: what the wrapper writes for them is read
	 * off the union of the varyings, which the header already holds.
	 */
	private boolean entityWrapped;

	/**
	 * Whether the pack's main is run twice and its clip position widened, which is what a lines
	 * mesh asks of its vertex stage. {@link LinesVertex} says why the mesh needs it.
	 */
	private boolean linesWrapped;

	/** Whether this stage really makes the overlay colour, which only the whole program can say. */
	private boolean makesOverlayColour;

	/** Whether the mask was really given a rank of its own. {@link #planCoverage} says when it is not. */
	private boolean covers;

	/**
	 * Whether the fragment stage names {@code gl_FragDepth} anywhere at all, live branch or not.
	 * <p>
	 * Anywhere at all, because what it decides is which value the mask is filled from, and a stage
	 * that writes its own depth in one branch writes the attachment from that branch and from
	 * {@code gl_FragCoord.z} in the others. The two have to be told apart by the text, since the
	 * branch is the preprocessor's answer and not this pass's: Bliss writes it under {@code POM}
	 * alone, and a mask filled from the interpolated depth there would say the geometry stands
	 * where the surface was before the parallax moved it.
	 */
	private boolean namesFragDepth;

	/** Where the fragment stage's own {@code main} stands, once the alpha test has claimed it. */
	private int packMainName = -1;

	/** Which function each token sits in, read while every brace is still the pack's own. */
	private int[] regions = new int[0];

	private int depthLookups;
	private int parameterLookups;

	/** Lookups {@link #pinLookupLevels} pinned to the base level of their image. */
	private int pinnedLookups;

	/**
	 * Lookups through a sampler the enclosing function was handed that the same pass left as they
	 * stood, some call of the function handing that parameter something the pass could not read as
	 * a sampler bound at the base, or the parameter having no level to pin.
	 */
	private int unpinnedParameterLookups;
	private int fragCoordZ;
	private int fragCoordXyz;
	private int fragCoordUnhandled;
	private int fragDepthWrites;
	private int fragDepthUnhandled;

	private GlslTranslator(ExpandedUnit unit, ProgramStage stage, Map<String, String> engineDefines,
			VertexInputs inputs, List<String> bound, AlphaTest alphaTest, boolean coverage,
			String program, Map<String, VolumeAtlas> volumes) {
		this.unit = unit;
		this.stage = stage;
		this.engineDefines = engineDefines;
		this.inputs = inputs;
		this.bound = List.copyOf(bound);
		this.alphaTest = alphaTest;
		this.coverage = coverage;
		this.program = program;
		this.volumes = Collections.unmodifiableMap(new LinkedHashMap<>(volumes));

		// Tokens cost far more than the text they came from, roughly seventy bytes each, so a unit
		// the expander should never have produced has to be refused before it is read rather than
		// after. Running out of memory here throws an Error, and an Error goes straight past the
		// catch that is supposed to turn a bad pack into a report.
		String text = unit.text();
		if (text.length() > MAX_SOURCE_CHARACTERS) {
			throw new IllegalStateException(unit.entry() + " expands to " + text.length()
					+ " characters, past the " + MAX_SOURCE_CHARACTERS + " a unit is allowed");
		}

		this.tokens = new TokenStream(GlslLexer.lex(text));
	}

	/**
	 * Rewrites one stage on its own, agreeing with nothing but itself. Fine for measuring, and
	 * wrong for rendering: see {@link ProgramTranslator} for why the stages of one program have to
	 * be given a header together.
	 */
	public static TranslatedUnit translate(ExpandedUnit unit, ProgramStage stage) {
		return translate(unit, stage, "");
	}

	/**
	 * Translates one expanded file into the unit a pipeline is built from.
	 *
	 * @param program the bare name of the program this file is the entry point of, or empty where
	 *                the file is an include and serves no pass of its own
	 */
	public static TranslatedUnit translate(ExpandedUnit unit, ProgramStage stage, String program) {
		Stage prepared = prepare(unit, stage, VertexInputs.WORLD, AlphaTest.OFF, program);

		return prepared.render(prepared.uniforms(), prepared.samplers(), prepared.varyings());
	}

	/**
	 * Rewrites one stage and stops short of the header, so that a caller can settle it.
	 *
	 * @param inputs    where this program's vertex stage takes its inputs from
	 * @param alphaTest what the fragment stage discards at. {@link AlphaTest#OFF} for every pass
	 *                  drawn over a quad, and for the solid half of the terrain
	 * @param program   the bare name of the program the pass wants, which is what decides the
	 *                  uniforms the engine supplies undeclared
	 */
	public static Stage prepare(ExpandedUnit unit, ProgramStage stage, VertexInputs inputs,
			AlphaTest alphaTest, String program) {
		return prepare(unit, stage, inputs, inputs.elements(), alphaTest, false, program);
	}

	/**
	 * The same, for a family that binds more than one vertex format and therefore cannot take the
	 * elements to declare from {@link VertexInputs} alone.
	 *
	 * @param bound    the elements of the format this pass binds, in the format's own order. Exactly
	 *                 these are declared: a name declared that the format has not got is refused, and
	 *                 an element left undeclared shifts every location after it in silence
	 * @param coverage whether the fragment stage writes the coverage mask on top of what the pack
	 *                 declared. A property of the pass and not of the file: one
	 *                 {@code gbuffers_terrain} serves an opaque half that writes it and a translucent
	 *                 half that does not
	 */
	public static Stage prepare(ExpandedUnit unit, ProgramStage stage, VertexInputs inputs,
			List<String> bound, AlphaTest alphaTest, boolean coverage, String program) {
		return prepare(unit, stage, inputs, bound, alphaTest, coverage, program, Map.of());
	}

	/**
	 * The same again, told what the pack ships behind the names it samples as volumes.
	 *
	 * @param volumes the volumes the pack supplies with a file, by the name it samples them under,
	 *                each with the layout the upload will use. A name missing from it is one nothing
	 *                can be moved onto, and its declaration stays what the pack wrote, which is what
	 *                keeps the program refused rather than drawn against nothing
	 */
	public static Stage prepare(ExpandedUnit unit, ProgramStage stage, VertexInputs inputs,
			List<String> bound, AlphaTest alphaTest, boolean coverage, String program,
			Map<String, VolumeAtlas> volumes) {
		GlslTranslator translator = new GlslTranslator(unit, stage,
				EngineDefines.table(EngineDefines.machine()), inputs, bound, alphaTest, coverage,
				program, volumes);
		translator.rewrite();

		return new Stage(translator);
	}

	/**
	 * Sends every unit translated from here on down the {@link #SHADOW_COMPARE} road, or lets it
	 * back off it, which matters as much: the switch is read again at every pack load, so removing
	 * the arming file and reloading has to put the comparison back on the sampler without a
	 * restart. Called by whoever can see the game directory; the system property is this class's
	 * own to read, so a harness run answers it without a game around.
	 */
	public static void askSoftCompare(boolean asked) {
		softCompareArmed = asked;
	}

	private static boolean softCompare() {
		return SOFT_COMPARE || softCompareArmed;
	}

	/**
	 * The state of every switch that changes what this translator EMITS, as one word.
	 * <p>
	 * It exists for {@link TranslationCache}, and it is the whole of what that cache cannot work
	 * out from the arguments it is handed: a translation kept on disk is the same answer only while
	 * these are the same, and a switch missing from this line would serve a unit emitted under the
	 * other state, which is a wrong picture with no error anywhere to point at it.
	 * <p>
	 * <strong>It lives here, beside the switches, because that is the only place it stays
	 * true.</strong> A switch added to this class is a line away from this method; a list of them
	 * kept over in the cache is a file away, and the day somebody adds the next one that file is
	 * the one they will not open.
	 */
	public static String emissionSwitches() {
		return (reduceTrig ? "trig-reduced" : "trig-driver")
				+ (softCompare() ? " compare-in-shader" : " compare-on-sampler");
	}

	private void rewrite() {
		collectMacroNames();
		collectDeclarations();
		// Before anything reads a lookup, because that is what it changes. The uniforms are lifted
		// much later, after the depth conversion, and a set filled there would be empty at the one
		// moment it decides something.
		collectComparisonSamplers();
		// After it, and for the same reason: the road has been settled by then, so a compared
		// parameter is collected here like any other sampler, under whichever spelling it kept.
		collectSamplerParameters();
		collectStorageBlocks();
		collectDrawBuffers();
		synthesizeAttributes();
		dropVersionAndExtensions();
		rewriteIdentifiers();
		// After the identifiers, because the goldberg idiom's sine has become ofReducedSin by then
		// and that is one of the two names the site is recognised under, the other being the plain
		// sin that reduceTrig off leaves standing. Taking it earlier would leave the sine standing
		// and the helper below would wrap a call this pass was about to erase.
		rewriteGoldbergHash();
		// After the identifiers and before the depth, and both halves of that matter: the legacy
		// spellings have to have become texture() before a lookup can be recognised, and what comes
		// out of here is a call under a name of ours that the depth pass will not look at twice.
		flattenVolumes();
		convertDepth();
		dropPrecision();
		// After precision, so a leftover highp does not sit between const and the type this pass
		// matches on, and before outputs are rewritten, which do not use const.
		demoteNonConstantInitialisers();
		rewriteFragmentOutputs();
		liftFragmentOutputs();
		// Before the lifting and after the depth, and both halves matter: the declarator it takes out
		// has to be gone before the lifting reads the statement, or the name would be carried into the
		// block after all, and the lookup it writes is text of ours that the depth conversion must not
		// have had a chance to wrap.
		moveCenterDepth();
		liftUniforms();
		// After the lifting, which is where the samplers of the file are recorded by name and type,
		// and after the depth, so that a comparison that road rewrote is under a name of ours by
		// now and the lookups left standing are the ones that really sample an image.
		pinLookupLevels();
		// Decided before the outputs are ordered and applied after: whether the fragment body is
		// wrapped is what decides where the ascending call goes, and both are settled from the one
		// answer rather than each asking again.
		planAlphaEpilogue();
		planCoverage();
		// Before the ascending call goes in, and that is the whole point: the call replaces the
		// opening brace of main with text of ours, which is no longer an operator, so a brace
		// counter run afterwards would walk past main without opening it and close one brace too
		// many at its end. Every function written after main would then read as file scope, where
		// nothing shadows anything. The token indices are stable from here on, so an array taken
		// now still describes the tokens dropUnprovidedInputs is handed later.
		this.regions = regions();
		orderFragmentOutputs();
		wrapMain();
		// After wrapMain, because the wrapper is where the columns are copied and reconstructed, and
		// before collectVaryings, because a matrix left as one out is one SPIR-V variable occupying
		// three locations and createFromSpirv then numbers the next name onto the second column.
		splitMatrixVaryings();
		splitStructVaryings();
		splitArrayVaryings();
		wrapMainForSplits();
		// Last, and it has to be: rewriteIdentifiers is where varying becomes in or out, and
		// dropUnprovidedInputs blanks the ranges recorded here, so nothing may move between the two.
		collectVaryings();

		this.used = usedNames();
		this.declaredAfter = declaredUnderAType();
		// Once per unit and only here, after the goldberg rewrite has taken its own sites back off.
		// The families translate on a worker while the chain is already read, so a counter each of
		// them walked up and down itself would be read mid-flight; one addition at the end of a
		// unit is the whole of what crosses a thread.
		TRIG_SITES.addAndGet(this.trigSites);
	}

	/**
	 * One stage rewritten, waiting for a header. What it asks for is separate from what it is
	 * given: a stage that asks for six uniforms may be handed twelve, because a sibling stage of
	 * the same program needs the other six and the block has to be the same on both sides.
	 */
	public static final class Stage {

		private final GlslTranslator translator;

		private Stage(GlslTranslator translator) {
			this.translator = translator;
		}

		public ProgramStage stage() {
			return this.translator.stage;
		}

		/** The block members this stage reads, fixed function state first. */
		public List<TranslatedUnit.Uniform> uniforms() {
			return this.translator.ownBlock();
		}

		public List<TranslatedUnit.Uniform> samplers() {
			return asUniforms(this.translator.samplers);
		}

		/**
		 * Sampler names this stage reads, not merely declares. A pack include can name twenty
		 * textures of which the body samples one; those unused names still occupy a Metal slot if
		 * they are declared first, and only sixteen exist.
		 */
		public Set<String> sampled() {
			return this.translator.sampledNames();
		}

		/**
		 * Vertex inputs this stage declared that the mesh it is drawn from has not got, with the type
		 * the pack gave them. Empty for anything not drawn from a mesh of the engine's own, and the
		 * list of what the picture will be wrong about when it is not.
		 */
		public Map<String, String> synthesized() {
			return Collections.unmodifiableMap(new LinkedHashMap<>(this.translator.synthesized));
		}

		/**
		 * Which elements of the mesh this stage really reads, out of the ones its family may leave
		 * off. Empty for every family that carries one format for all packs, which is all of them
		 * but the chunk mesh.
		 * <p>
		 * Asked between {@link #prepare} and {@link #render}, and that is the whole reason it is a
		 * method of this class: the answer is what the format is built from, and the format is what
		 * the header then declares. Nothing here reads {@code bound}, so the value handed to
		 * {@code prepare} for a stage that will only ever be asked this is not yet settled.
		 */
		public Set<String> reads() {
			if (this.translator.inputs == VertexInputs.DISTANT) {
				return DistantVertex.reads(this.translator.used, this.translator.synthesized);
			}

			return this.translator.inputs.terrain()
					? SodiumVertex.reads(this.translator.used, this.translator.synthesized,
							this.translator.inputs.separateAo())
					: Set.of();
		}

		/** Varyings the engine names, which both sides of a program have to declare or neither. */
		public Set<String> varyings() {
			Set<String> named = new LinkedHashSet<>();
			if (this.translator.used.contains(FOG_COORD)) {
				named.add(FOG_COORD);
			}

			// Asked of the mesh and of the body together. A pack that declares the name and never
			// reads it is left alone, where Iris hands the varying over regardless: the two draw the
			// same picture, and declaring nothing is the one that cannot shift a location.
			if (this.translator.inputs.overlay()) {
				if (this.translator.used.contains(ENTITY_COLOR)) {
					named.add(ENTITY_COLOR);
				}

				for (String identifier : ENTITY_IDS) {
					if (this.translator.used.contains(identifier)) {
						named.add(identifier);
					}
				}
			}

			return Set.copyOf(named);
		}

		/**
		 * Tells this stage whether the program it belongs to wants the overlay colour, which is what
		 * makes its vertex stage declare the texture it is fetched from.
		 * <p>
		 * Handed the union of {@link #varyings} over every stage, and it has to be: a pack commonly
		 * reads {@code entityColor} in its fragment stage alone, and it is the VERTEX stage that owes
		 * the value. To be called once every stage has been asked for its varyings and before any is
		 * asked for its samplers, the answer being one of them.
		 * <p>
		 * Only a stage whose body was really wrapped can answer, which is what keeps the declaration
		 * and the value together: the two sides declare the varying off the union, and a vertex stage
		 * with no {@code main} to wrap would leave the fragment reading one nothing ever wrote.
		 * <p>
		 * Between the vertex stage and the fragment stage and no further. Iris carries the colour
		 * through a tessellation or geometry stage as well
		 * ({@code pipeline/transform/transformer/EntityPatcher.java:63-106}); no program of the corpus
		 * has either, and {@link #FOG_COORD} stops at the same place for the same reason.
		 */
		public void makesOverlayColour(Set<String> varyings) {
			if (this.translator.entityWrapped && varyings.contains(ENTITY_COLOR)) {
				this.translator.makesOverlayColour = true;
				this.translator.samplers.put(OVERLAY, SAMPLER_2D + " " + OVERLAY);
			}
		}

		/** Names this stage declares itself, and that a shared block must therefore not shadow. */
		public Set<String> declared() {
			return this.translator.declaredAfter;
		}

		/** Names this stage lifted into the block, where the block member is the real meaning. */
		public Set<String> lifted() {
			return this.translator.blockMembers.keySet();
		}

		/** The varyings this stage hands on, which is what says whether the next one is provided. */
		public Set<String> provides() {
			return Set.copyOf(this.translator.declaredOutputs);
		}

		/**
		 * The varyings this stage still takes in, which is what says whether the stage before it is
		 * handing on one nobody declares.
		 * <p>
		 * What {@link #dropUnprovidedInputs} took out is left out here, and has to be: it is no
		 * longer in the text, so the game will not count it either.
		 */
		public Set<String> requires() {
			Set<String> names = new LinkedHashSet<>();
			this.translator.declaredInputs.forEach(declared -> names.addAll(declared.names()));
			names.removeAll(this.translator.droppedInputs);

			return Set.copyOf(names);
		}

		/**
		 * Stops this stage handing on the varyings the next one does not declare.
		 * See {@link GlslTranslator#withholdUndeclaredOutputs}.
		 * <p>
		 * After {@link #owe}, which adds to what this stage hands on, and before anything is
		 * rendered: it changes both the text and the answer {@link #provides} gives.
		 */
		public void withhold(Set<String> declared) {
			this.translator.withholdUndeclaredOutputs(declared);
		}

		/**
		 * Takes back out the inputs this stage declares that no stage before it writes and that its
		 * own body never reads. See {@link GlslTranslator#dropUnprovidedInputs}.
		 * <p>
		 * To be called before anything else on this stage is read. It changes what the body says, so
		 * what {@link #uniforms}, {@link #varyings}, {@link #declared} and {@link #render} answer
		 * afterwards is not what they would have answered before, and a caller that renders first
		 * and drops second emits the text the drop was meant to repair.
		 */
		public void dropUnprovidedInputs(Set<String> provided) {
			this.translator.dropUnprovidedInputs(provided);
		}

		/**
		 * What {@link #dropUnprovidedInputs} could not take out, by name, each with the text that
		 * declares it again. Empty until that has run.
		 */
		public Map<String, String> unprovided() {
			return Collections.unmodifiableMap(new LinkedHashMap<>(this.translator.unprovidedInputs));
		}

		/**
		 * Makes this stage declare these as outputs and assign each one its zero, so that the stage
		 * after it is provided for.
		 * <p>
		 * A name this stage already hands on is skipped, which is the test Iris makes at
		 * {@code CompatibilityTransformer.java:467} against the out declarations it gathered at
		 * :425-440. Compared against those and not against every name the stage declares: a vertex
		 * stage that happens to call a function parameter {@code ViewPos} would otherwise make this
		 * give up, and the pass would be lost for a name that clashes with nothing.
		 * <p>
		 * A name opening with {@code gl_} is skipped as well, as it is on both of Iris's sides at
		 * :435 and :462. Redeclaring a built-in as a varying of ours is a compile error, and the
		 * game provides them itself.
		 */
		public void owe(Map<String, String> declarations) {
			declarations.forEach((name, qualified) -> {
				if (!name.startsWith("gl_") && !this.translator.declaredOutputs.contains(name)) {
					this.translator.owedOutputs.put(name, qualified);
					this.translator.declaredOutputs.add(name);
				}
			});

			if (!this.translator.owedOutputs.isEmpty()) {
				this.translator.wrapMainForOwedOutputs();
			}
		}

		public TranslatedUnit render(List<TranslatedUnit.Uniform> block,
				List<TranslatedUnit.Uniform> samplers, Set<String> varyings) {
			return render(block, samplers, varyings, Set.of());
		}

		public TranslatedUnit render(List<TranslatedUnit.Uniform> block,
				List<TranslatedUnit.Uniform> samplers, Set<String> varyings, Set<String> shadowed) {
			return this.translator.render(block, samplers, varyings, shadowed);
		}
	}

	private TranslatedUnit render(List<TranslatedUnit.Uniform> block,
			List<TranslatedUnit.Uniform> samplers, Set<String> varyings, Set<String> shadowed) {
		return new TranslatedUnit(this.unit.entry(), this.stage,
				emitter().header(block, samplers, varyings, shadowed) + body(shadowed) + "\n", notes(),
				List.copyOf(this.drawBuffers), List.copyOf(block), List.copyOf(samplers));
	}

	/**
	 * The rewritten text, with any name the caller flagged moved out of the way.
	 * <p>
	 * A shared block carries members some of its stages never asked for, and one of those stages
	 * may already use that name for a value of its own: Bliss works out {@code sunVec} in its
	 * vertex shader and takes it as a uniform in its fragment shader. Renaming is safe precisely
	 * where it is needed, because a stage that never declared the name as a uniform has only one
	 * meaning for it, its own.
	 */
	private String body(Set<String> shadowed) {
		if (shadowed.isEmpty()) {
			return this.tokens.join();
		}

		StringBuilder text = new StringBuilder();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			boolean rename = token.kind() == Kind.IDENTIFIER
					&& shadowed.contains(token.text())
					&& !token.macroName()
					&& (token.directive() == null
							|| !LegacyGlsl.OPAQUE_DIRECTIVES.contains(token.directive()));

			text.append(rename ? "ofOwn_" + token.text() : token.text());
		}

		return text.toString();
	}

	private List<TranslatedUnit.Uniform> ownBlock() {
		List<TranslatedUnit.Uniform> block = new ArrayList<>();

		for (Map.Entry<String, String> member : LegacyGlsl.FIXED_FUNCTION_MEMBERS.entrySet()) {
			if (this.used.contains(member.getKey())) {
				block.add(TranslatedUnit.Uniform.of(member.getKey(), member.getValue()));
			}
		}

		// A name the pack did declare is already on its way into the block through liftUniforms,
		// under the type the pack chose, so only a name it never declares is supplied here. That
		// holds for the entity trio below as well.
		for (Map.Entry<String, String> member : LegacyGlsl.CORE_MATRICES.entrySet()) {
			if (this.used.contains(member.getKey()) && !this.declaredNames.contains(member.getKey())) {
				block.add(TranslatedUnit.Uniform.of(member.getKey(), member.getValue()));
			}
		}

		// Not where the mesh carries them, which is the answer liftUniforms gives for a pack that
		// declared one of the three itself, given here for a pack that never declares them. The two
		// halves have to agree: a name offered here and taken out of the block there would be a
		// declaration in the block that the varying of the same name then redeclares.
		if (LegacyGlsl.drawsEntities(this.program) && !this.inputs.overlay()) {
			for (Map.Entry<String, String> member : LegacyGlsl.ENTITY_UNIFORMS.entrySet()) {
				if (this.used.contains(member.getKey())
						&& !this.declaredNames.contains(member.getKey())) {
					block.add(TranslatedUnit.Uniform.of(member.getKey(), member.getValue()));
				}
			}
		}

		// The strength a glint's vertex colour is made of. Asked of the inputs and not of the program
		// name, unlike everything above: what it answers for belongs to the MESH, and the same
		// gbuffers_textured serves a glint under this constant and a sky pass under another. Each
		// stage adds what it names and the union hands both to both, the block being the program's
		// rather than the stage's.
		if (this.inputs == VertexInputs.GLINT && this.used.contains("of_Color")) {
			block.add(TranslatedUnit.Uniform.of(LegacyGlsl.GLINT_ALPHA,
					"float " + LegacyGlsl.GLINT_ALPHA));
		}

		// The bob every pass that builds its model view in the shader multiplies by, and asked of
		// nothing but the name: it is a name no pack writes, so it is here only where this
		// translation put it, which rewriteGameModelView does and only for the passes
		// LegacyGlsl.readsDrawModelView answers for.
		if (this.used.contains(LegacyGlsl.CAMERA_BOB)) {
			block.add(TranslatedUnit.Uniform.of(LegacyGlsl.CAMERA_BOB,
					"mat4 " + LegacyGlsl.CAMERA_BOB));
		}

		// The screen size the lines wrapper widens with, supplied under the pack's own two names
		// wherever the pack did not declare them itself; declared ones come through liftUniforms.
		// Declared anywhere: collectDeclarations does not know a scope, so a pack naming a local
		// viewWidth would keep the member out and leave the wrapper reading a name nobody
		// declared, the trap LegacyGlsl records for projectionMatrix. No pack of the corpus does.
		if (this.linesWrapped) {
			for (String name : List.of("viewWidth", "viewHeight")) {
				if (!this.declaredNames.contains(name)) {
					block.add(TranslatedUnit.Uniform.of(name, "float " + name));
				}
			}
		}

		block.addAll(asUniforms(this.blockMembers));

		return block;
	}

	static List<TranslatedUnit.Uniform> asUniforms(Map<String, String> declarations) {
		return declarations.entrySet().stream()
				.map(entry -> TranslatedUnit.Uniform.of(entry.getKey(), entry.getValue()))
				.toList();
	}


	/**
	 * Marks the name each naming directive gives, so that later passes leave it spelled as the
	 * preprocessor needs it.
	 * <p>
	 * The mark goes on the token and not into a set of positions, and that is the whole point of
	 * {@link Token#macroName()}. This runs first, before anything inserts, and two passes do insert:
	 * {@link #rewriteIdentifiers} closes the legacy shadow lookups it wrapped and {@link #convertDepth}
	 * closes the depth writes it wrapped. Each insertion moves every index after it, so a position
	 * taken here would name a different token by the time {@link #body(Set)} reads it, and rename a
	 * macro name or leave a shadowed read on the block value with nothing logged either way.
	 */
	private void collectMacroNames() {
		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.HASH || !LegacyGlsl.NAMING_DIRECTIVES.contains(token.directive())) {
				continue;
			}

			int name = this.tokens.macroNameAfter(index);
			if (name < 0) {
				continue;
			}

			this.tokens.naming(name);
			if (token.directive().equals("define")) {
				String macro = this.tokens.get(name).text();
				this.packMacros.add(macro);
				this.macroBodies.computeIfAbsent(macro, _ -> new HashSet<>()).addAll(bodyNames(name));
				// Only a live line says what the macro stands for where the lookups are: a define
				// in a branch nobody took would otherwise hand a live name to a volume. A live
				// define of anything else unsays an alias, and two aliases that disagree stand for
				// neither, which the empty text records.
				if (this.unit.isLive(lines[index])) {
					this.macroAliases.merge(macro, aliasOf(name).orElse(""),
							(first, second) -> first.equals(second) ? first : "");
				}
			}
		}
	}

	/**
	 * The one name a macro's replacement text consists of, or empty when the text is anything
	 * else: several tokens, a parameter list, or nothing at all.
	 */
	private Optional<String> aliasOf(int name) {
		String target = null;
		for (int scan = name + 1; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() == Kind.NEWLINE) {
				break;
			}

			if (token.trivia()) {
				continue;
			}

			if (token.kind() != Kind.IDENTIFIER || target != null) {
				return Optional.empty();
			}

			target = token.text();
		}

		return Optional.ofNullable(target);
	}

	/**
	 * Every identifier the replacement text of a macro names. The parameters of a function-like
	 * macro are left out: they stand for the arguments, and the arguments are judged where they are
	 * written. The parenthesis is a parameter list only when it touches the name, which is the
	 * preprocessor's own rule; after a space it is the first token of the body.
	 */
	private Set<String> bodyNames(int name) {
		Set<String> parameters = new HashSet<>();
		int scan = name + 1;
		if (scan < this.tokens.size() && this.tokens.get(scan).operator("(")) {
			for (scan++; scan < this.tokens.size(); scan++) {
				Token token = this.tokens.get(scan);
				if (token.kind() == Kind.NEWLINE || token.operator(")")) {
					break;
				}

				if (token.kind() == Kind.IDENTIFIER) {
					parameters.add(token.text());
				}
			}
		}

		Set<String> names = new HashSet<>();
		for (; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() == Kind.NEWLINE) {
				break;
			}

			if (token.kind() == Kind.IDENTIFIER && !parameters.contains(token.text())) {
				names.add(token.text());
			}
		}

		return names;
	}

	/**
	 * Records every name the unit declares under a built-in type, and among them the functions
	 * whose name GLSL has since taken for itself.
	 * <p>
	 * A declaration declares more than one name when it carries commas, and only the first of them
	 * follows the type. E-LITE writes {@code uniform int frameCounter, isEyeInWater, entityId},
	 * and reading the first alone left the other two as names the unit never declares, which is
	 * what decides whether the block supplies one: {@code entityId} was then written into the
	 * block twice, once by the lift and once as a name nobody had declared, and the compiler
	 * refuses a member declared twice.
	 */
	private void collectDeclarations() {
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER || token.directive() != null) {
				continue;
			}

			int before = this.tokens.significantBefore(index);
			if (before < 0 || !LegacyGlsl.TYPE_NAMES.contains(this.tokens.get(before).text())) {
				continue;
			}

			this.declaredNames.add(token.text());
			this.declaredNames.addAll(continuationDeclarators(index));
			if (LegacyGlsl.POST_120_BUILTINS.contains(token.text()) && this.tokens.callOpener(index) >= 0) {
				this.shadowedBuiltins.add(token.text());
			}
		}
	}

	/**
	 * The names a declaration declares after its first, which is every declarator past a comma.
	 * <p>
	 * A name is only taken where what FOLLOWS it says it is a declarator, which is a comma, a
	 * semicolon, an initialiser or an array bracket. Reading the name alone is not enough, and a
	 * parameter list is why: {@code TYPE_NAMES} carries no {@code const}, {@code in} or
	 * {@code out}, so Bliss writing {@code float rayLength, const float steps} in a signature
	 * ({@code lib/ROBOBO_sky.glsl:61}) offers {@code const} where a declarator would stand. What
	 * follows it there is a type name and never one of the four, so it is refused.
	 * <p>
	 * Liveness is not consulted, here or anywhere else in this pass: a declaration is read whether
	 * its branch is taken or not, and the tail of a list is read on the same terms as its head.
	 * Making the pass live-aware would change what it answers for every declaration in the file
	 * and belongs to a batch of its own.
	 *
	 * @param first the first declarator of the statement, the one the type stands in front of
	 */
	private List<String> continuationDeclarators(int first) {
		int end = this.tokens.statementEnd(first);
		if (end < 0) {
			return List.of();
		}

		List<String> names = new ArrayList<>();
		int depth = 0;
		boolean expectName = false;

		for (int index = this.tokens.significantAfter(first); index >= 0 && index <= end;
				index = this.tokens.significantAfter(index)) {
			Token token = this.tokens.get(index);
			if (token.operator("(") || token.operator("[") || token.operator("{")) {
				depth++;
			} else if (token.operator(")") || token.operator("]") || token.operator("}")) {
				if (depth == 0) {
					return names;
				}

				depth--;
			} else if (depth == 0 && token.operator(",")) {
				expectName = true;
			} else if (expectName) {
				if (token.kind() != Kind.IDENTIFIER || !declaratorFollows(index)) {
					return names;
				}

				names.add(token.text());
				expectName = false;
			}
		}

		return names;
	}

	/**
	 * Whether the name at this position is followed by what only ever follows a declarator: the
	 * comma before the next one, the semicolon that ends the declaration, an initialiser, or the
	 * bracket of an array.
	 */
	private boolean declaratorFollows(int name) {
		int next = this.tokens.significantAfter(name);
		if (next < 0) {
			return false;
		}

		Token token = this.tokens.get(next);

		return token.operator(",") || token.operator(";") || token.operator("=")
				|| token.operator("[");
	}

	/**
	 * Which colour attachments this program writes, bounded to what a fragment stage may declare.
	 * The rule itself is in {@link DrawBuffers}, next to the targets it decides the existence of;
	 * all that belongs here is the ceiling, since a target that has to exist and an output that
	 * can be declared are two different questions with two different answers.
	 * <p>
	 * The ceiling is on how many are kept and not on how far they count, which is the difference
	 * between the two questions. Entry {@code n} of this list is where output {@code n} lands, so
	 * an entry past the eighth belongs to an output that cannot exist; but the entries themselves
	 * name colour targets and Reverie names {@code colortex19}, which a bound of sixteen on the
	 * value would drop, moving every attachment declared after it.
	 */
	private void collectDrawBuffers() {
		DrawBuffers.parse(this.unit).stream().limit(MAX_FRAGMENT_OUTPUTS).forEach(this.drawBuffers::add);
	}

	/**
	 * Both directives go. The version is replaced by ours, and every extension the corpus uses is
	 * core in 4.60, so keeping them would at best say nothing. It would also often be an error:
	 * once includes are spliced in, an {@code #extension} line can land well past the first real
	 * token, and there it is rejected. They are counted so that an unexpected one shows up in the
	 * totals rather than vanishing.
	 */
	private void dropVersionAndExtensions() {
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.HASH) {
				continue;
			}

			if (token.directive().equals("extension")) {
				this.strippedExtensions++;
			} else if (!token.directive().equals("version")) {
				continue;
			}

			this.tokens.blankDirective(index);
		}
	}

	private void rewriteIdentifiers() {
		List<Integer> closings = new ArrayList<>();
		int[] lines = this.tokens.lineNumbers();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER) {
				continue;
			}

			String directive = token.directive();
			if (directive != null
					&& (LegacyGlsl.OPAQUE_DIRECTIVES.contains(directive) || token.macroName())) {
				continue;
			}

			String name = token.text();

			// A pack that shims a legacy name itself has already said what it means. Renaming its
			// uses would leave the macro behind, pointing at something no longer there.
			if (this.packMacros.contains(name)) {
				continue;
			}

			if (this.shadowedBuiltins.contains(name)) {
				this.tokens.replace(index, "of_" + name);
				continue;
			}

			if (name.equals("gl_TextureMatrix") && LegacyGlsl.bindsGameTransforms(this.program)
					&& rewriteGameTextureMatrix(index)) {
				continue;
			}

			if (name.equals("gl_TextureMatrix") && this.inputs == VertexInputs.DISTANT
					&& rewriteDistantTextureMatrix(index)) {
				continue;
			}

			if (LegacyGlsl.readsDrawModelView(this.program) && rewriteGameModelView(index, name)) {
				continue;
			}

			String fixed = LegacyGlsl.FIXED_FUNCTION.get(name);
			if (fixed != null) {
				this.tokens.replace(index, fixed);
				continue;
			}

			if (name.equals("gl_VertexID") || name.equals("gl_InstanceID")) {
				this.tokens.replace(index,
						name.equals("gl_VertexID") ? "gl_VertexIndex" : "gl_InstanceIndex");
				continue;
			}

			if (name.equals("ftransform") && rewriteFtransform(index)) {
				continue;
			}

			String shadow = LegacyGlsl.SHADOW_FUNCTIONS.get(name);
			if (shadow != null && this.tokens.callOpener(index) >= 0) {
				int close = this.tokens.matchingBracket(this.tokens.callOpener(index));
				if (close < 0) {
					// Unbalanced from here, usually because a macro opened the parenthesis. The
					// call is left alone and the compiler will say so, but silence here would
					// mean a lookup nobody wrapped and nobody counted.
					this.unwrappedShadowCalls++;
				}

				if (close >= 0) {
					// A legacy shadow lookup is rewritten here rather than later: the injection
					// below fuses the name into one token with the parenthesis, and the depth
					// conversion matches names.
					int first = this.tokens.significantAfter(this.tokens.callOpener(index));
					String argument = first >= 0
							&& this.tokens.get(first).kind() == Kind.IDENTIFIER
							? this.tokens.get(first).text() : null;
					boolean hardware = argument != null
							&& hardwareComparisonAt(argument, lines[index]);
					boolean compared = argument != null && !hardware
							&& comparisonAt(argument, lines[index]);

					// Only the plain lookups are the arithmetic road's to make, as in
					// rewriteShadowCompare and for the same reason: a projective comparison divides
					// before it compares, which is a different expression and not a different name.
					// It keeps the modern spelling and is counted rather than quietly turned into
					// something it is not. The hardware road has no such edge: textureProj on a
					// comparison sampler is the division and the comparison in one call, so it
					// counts as a comparison made rather than as one left on the floor.
					if (compared && shadow.equals("textureProj")) {
						this.unwrappedShadowCalls++;
						compared = false;
					}

					if (compared) {
						this.softRewrites++;
					}

					// The wrap adds an opening parenthesis, so it has to add a closing one too.
					// Substituting the head alone is what left the prototype with eighty-six
					// units ending in "unexpected SEMICOLON, expecting RIGHT_PAREN".
					this.tokens.inject(index, "vec4(" + (compared ? SHADOW_COMPARE : shadow));
					closings.add(close + 1);
					this.shadowCalls++;
					continue;
				}
			}

			String modern = LegacyGlsl.DEPRECATED_FUNCTIONS.get(name);
			if (modern != null && this.tokens.callOpener(index) >= 0) {
				this.tokens.replace(index, modern);
				continue;
			}

			// Calls only, and never a name the pack declared for itself: a unit shipping its own
			// sin has already said what it means by it. See REDUCED_SIN for why the builtin cannot
			// be left to take the argument raw.
			//
			// The site is counted before the switch is asked and not after, so that a load running
			// on the driver's own two can still say what the substitution would have had to bite
			// on. With the switch off nothing is replaced and the token falls through to the
			// readings below, exactly as any other name the pack calls does.
			if ((name.equals("sin") || name.equals("cos")) && this.tokens.callOpener(index) >= 0
					&& !this.declaredNames.contains(name)) {
				this.trigSites++;
				if (reduceTrig) {
					this.tokens.replace(index, name.equals("sin") ? REDUCED_SIN : REDUCED_COS);
					this.trigCalls++;
					continue;
				}
			}

			if (directive != null) {
				continue;
			}

			if (name.equals("varying")) {
				// A geometry shader has varyings running both ways and the keyword cannot say
				// which. Every other stage is unambiguous, and the corpus has few enough geometry
				// programs that guessing wrong here is visible rather than silent.
				this.tokens.replace(index, this.stage == ProgramStage.VERTEX ? "out" : "in");
				continue;
			}

			if (name.equals("attribute")) {
				this.tokens.replace(index, "in");
				continue;
			}

			String reserved = LegacyGlsl.RESERVED_NAMES.get(name);
			if (reserved != null && this.tokens.callOpener(index) < 0) {
				this.tokens.replace(index, reserved);
			}
		}

		// Inserting shifts every index after it, so the last insertion is made first. It also ends
		// every position taken before it, insertClosings being the one place that moves a token, so
		// anything a later pass still has to know about a token is carried on the token, as
		// Token#macroName is. A position kept across here would be read against somebody else's
		// token, and the reading pass has no way to notice.
		List<Closing> parentheses = new ArrayList<>(closings.size());
		for (int at : closings) {
			parentheses.add(new Closing(at, ")", null));
		}

		this.tokens.insertClosings(parentheses);
	}

	/**
	 * Replaces {@code fract(sin(dot(p, K)) * 43758.5453)} with a hash of {@code p}'s bits.
	 * <p>
	 * The constant is the fingerprint. Packs copy this one number into a hash they mean as white
	 * noise on a lattice; a sine that then fails is not a sine they asked to see, and feeding it
	 * to {@link #REDUCED_SIN} still skips in game. The first argument of {@code dot} is the lattice
	 * point (or the continuous coordinate, for the same idiom on stars and puddles). The second
	 * argument is thrown away: it exists only to mix those two channels into the sine.
	 * <p>
	 * Only while that second argument is a literal. Body Camera writes the frame time into it,
	 * {@code vec2(12.9898, 78.233 * frameTimeCounter)}, and means its film grain to move: a hash of
	 * the first argument alone would freeze it. Such a call is not the lattice idiom and keeps its
	 * sine.
	 */
	private void rewriteGoldbergHash() {
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("fract") || token.directive() != null) {
				continue;
			}

			int fractOpen = this.tokens.callOpener(index);
			int sine = fractOpen < 0 ? -1 : this.tokens.significantAfter(fractOpen);
			if (sine < 0 || !goldbergSine(this.tokens.get(sine))) {
				continue;
			}

			int sineOpen = this.tokens.callOpener(sine);
			int dot = sineOpen < 0 ? -1 : this.tokens.significantAfter(sineOpen);
			if (dot < 0 || !this.tokens.get(dot).identifier("dot")) {
				continue;
			}

			int dotOpen = this.tokens.callOpener(dot);
			int dotClose = this.tokens.matchingBracket(dotOpen);
			int comma = firstCallComma(dotOpen);
			int argStart = dotOpen < 0 ? -1 : this.tokens.significantAfter(dotOpen);
			int argEnd = comma < 0 ? -1 : this.tokens.significantBefore(comma);
			int sineClose = this.tokens.matchingBracket(sineOpen);
			int times = sineClose < 0 ? -1 : this.tokens.significantAfter(sineClose);
			int scale = times < 0 ? -1 : this.tokens.significantAfter(times);
			int fractClose = this.tokens.matchingBracket(fractOpen);
			if (argStart < 0 || argEnd < argStart || times < 0 || scale < 0 || fractClose < 0
					|| !this.tokens.get(times).operator("*")
					|| !goldbergScale(this.tokens.get(scale))
					|| !literalVector(comma + 1, dotClose - 1)) {
				continue;
			}

			int afterScale = this.tokens.significantAfter(scale);
			if (afterScale != fractClose) {
				continue;
			}

			boolean reduced = this.tokens.get(sine).identifier(REDUCED_SIN);
			String argument = tokenText(argStart, argEnd);
			this.tokens.blankRange(index, fractClose);
			this.tokens.inject(index, HASH + "(" + argument + ")");
			// Off both counts, and the site comes off whichever name its sine still went under: the
			// idiom is erased here, so it is not a call either helper was ever going to take.
			if (this.trigSites > 0) {
				this.trigSites--;
			}

			if (reduced && this.trigCalls > 0) {
				this.trigCalls--;
			}

			this.hashCalls++;
		}
	}

	private boolean goldbergSine(Token token) {
		// The plain arm carries BOTH exclusions the substitution itself applies, the pack macro
		// one included: a pack that defines sin for itself keeps its idiom whatever the switch
		// says, exactly as it kept it before the switch existed.
		return token.identifier(REDUCED_SIN)
				|| (token.identifier("sin") && !this.declaredNames.contains("sin")
						&& !this.packMacros.contains("sin"));
	}

	/**
	 * Whether the tokens in this range spell a vector of numbers and nothing else, such as
	 * {@code vec2(12.9898, 4.1414)}. A name in there is a value that moves.
	 */
	private boolean literalVector(int start, int end) {
		boolean number = false;
		for (int at = start; at <= end; at++) {
			Token token = this.tokens.get(at);
			if (token.trivia() || token.kind() == Kind.NEWLINE) {
				continue;
			}

			if (token.kind() == Kind.NUMBER) {
				number = true;
			} else if (token.kind() == Kind.IDENTIFIER) {
				if (!token.identifier("vec2") && !token.identifier("vec3") && !token.identifier("vec4")) {
					return false;
				}
			} else if (!token.operator("(") && !token.operator(")") && !token.operator(",")
					&& !token.operator("-") && !token.operator("+")) {
				return false;
			}
		}

		return number;
	}

	/**
	 * The goldberg scale is this one number, copied through the corpus. Anything else multiplied
	 * by a sine is a sine the pack meant to keep.
	 */
	private static boolean goldbergScale(Token token) {
		if (token.kind() != Kind.NUMBER) {
			return false;
		}

		String text = token.text();
		if (text.endsWith("f") || text.endsWith("F")) {
			text = text.substring(0, text.length() - 1);
		}

		try {
			return Math.abs(Double.parseDouble(text) - 43758.5453) < 2.0;
		} catch (NumberFormatException ignored) {
			return false;
		}
	}

	/** The comma that separates the first argument of the call that opens here, or -1. */
	private int firstCallComma(int open) {
		int close = this.tokens.matchingBracket(open);
		if (open < 0 || close < 0) {
			return -1;
		}

		int depth = 0;
		for (int scan = open; scan < close; scan++) {
			Token inner = this.tokens.get(scan);
			if (inner.kind() != Kind.OPERATOR) {
				continue;
			}

			if (inner.operator("(") || inner.operator("[") || inner.operator("{")) {
				depth++;
			} else if (inner.operator(")") || inner.operator("]") || inner.operator("}")) {
				depth--;
			} else if (inner.operator(",") && depth == 1) {
				return scan;
			}
		}

		return -1;
	}

	private String tokenText(int start, int end) {
		StringBuilder text = new StringBuilder();
		for (int at = start; at <= end; at++) {
			text.append(this.tokens.get(at).text());
		}

		return text.toString();
	}

	/**
	 * Sends one read of {@code gl_TextureMatrix[0]} to the game's own block, which is where the
	 * matrix that draw was really prepared with lives.
	 * <p>
	 * <strong>Unit nought and no other, which is the whole shape of it.</strong> The one thing the
	 * fixed function pipeline put in unit nought is what a render type animates its texture by, and
	 * six of the game's render types set one that is not the identity: the four glints, the breeze's
	 * wind and the energy swirl ({@code rendertype/RenderTypes.java:251,259,267,274,524,536}, the last
	 * two an offset built afresh per draw). Unit one is the light map's and is answered from this
	 * engine's own table, which is Iris's split as well: {@code gl_TextureMatrix[1]} goes to
	 * {@code iris_LightmapTextureMatrix} beside the line that sends nought to the block
	 * ({@code VanillaTransformer.java:163-164}).
	 * <p>
	 * Only where the pass is drawn from a draw the game prepared, which is what
	 * {@link LegacyGlsl#bindsGameTransforms} answers and what {@link LegacyGlsl#GAME_TEXTURE_MATRIX}
	 * weighs against Iris's wider reach.
	 * <p>
	 * The three bracket tokens are blanked rather than kept, because what replaces the name is a
	 * matrix and not an array: leaving {@code [0]} standing would read its first COLUMN. Any other
	 * index, and any index that is not a literal, is left alone and reaches the array as before,
	 * <strong>which for unit nought means the identity beside a neighbour that reads the real
	 * matrix</strong>. The corpus writes the name two hundred and fifty seven times, all of them
	 * {@code [0]} or {@code [1]} and none of them computed, so nothing rests on that today; it is
	 * written down because a pack that computes an index has to keep working rather than read a
	 * column, and because two answers for one unit inside one file is the kind of thing that is only
	 * ever noticed if it was said in advance.
	 *
	 * @return whether this was a read of unit nought, false leaving the name to the ordinary rename
	 */
	private boolean rewriteGameTextureMatrix(int index) {
		int open = this.tokens.significantAfter(index);
		if (open < 0 || !this.tokens.get(open).operator("[")) {
			return false;
		}

		int unit = this.tokens.significantAfter(open);
		int close = this.tokens.matchingBracket(open);
		if (unit < 0 || close < 0 || this.tokens.significantAfter(unit) != close
				|| !this.tokens.get(unit).text().equals("0")) {
			return false;
		}

		this.tokens.inject(index, LegacyGlsl.GAME_TEXTURE_MATRIX);
		this.tokens.blank(open);
		this.tokens.blank(unit);
		this.tokens.blank(close);
		this.injectedNames.add(LegacyGlsl.GAME_TEXTURE_MATRIX);
		this.gameTextureMatrix++;

		return true;
	}

	/**
	 * Answers a far terrain program's read of {@code gl_TextureMatrix[0]}, {@code [1]} or
	 * {@code [2]} with the identity, which is Iris's answer for this family and the other half of
	 * the light pair {@link DistantVertex} hands over normalised.
	 * <p>
	 * <strong>Iris replaces the first two expressions on its DH road,</strong>
	 * {@code DHTerrainTransformer.java:23-24}, because the light coordinate its vertex init builds
	 * is already {@code (i + 0.5) / 16} ({@code :135}) and unit nought's coordinate is a constant:
	 * neither has a fixed function scale left to undo. The packs split over which way they read the
	 * light, BSL and Complementary through the matrix and Bliss raw, so the matrix and the
	 * coordinate have to change convention together or one of the two camps reads a number two
	 * hundred and fifty six times off.
	 * <p>
	 * <strong>Unit two rides along because it is this engine's second name for unit one</strong>,
	 * {@code of_MultiTexCoord2} answering the same pair in the head and
	 * {@code GeometryValues.LIGHTMAP_UNITS} holding both, where Iris spells the aliasing on the
	 * coordinate instead and renames {@code gl_MultiTexCoord2} to one on this family
	 * ({@code DHTerrainTransformer.java:30}), leaving its {@code gl_TextureMatrix[2]} to fail the
	 * compile. Leaving two out here would put the light matrix under a coordinate already
	 * normalised, the exact mismatch this method exists to close. The corpus names neither the
	 * coordinate nor the matrix of unit two in a {@code dh_} program, so nothing rests on the
	 * difference from Iris's refusal.
	 * <p>
	 * Any other index, and any index that is not one of the three literals spelled plainly, falls
	 * through to the ordinary rename and reads the engine's real table. Iris leaves those reads
	 * untouched on this family, where they fail to compile; the corpus never writes one in a
	 * {@code dh_} program, so nothing rests on that today either, and it is written down here for
	 * the pack that one day does.
	 *
	 * @return whether this was a read of unit nought, one or two, false leaving the name to the
	 *         ordinary rename
	 */
	private boolean rewriteDistantTextureMatrix(int index) {
		int open = this.tokens.significantAfter(index);
		if (open < 0 || !this.tokens.get(open).operator("[")) {
			return false;
		}

		int unit = this.tokens.significantAfter(open);
		int close = this.tokens.matchingBracket(open);
		if (unit < 0 || close < 0 || this.tokens.significantAfter(unit) != close
				|| !(this.tokens.get(unit).text().equals("0")
						|| this.tokens.get(unit).text().equals("1")
						|| this.tokens.get(unit).text().equals("2"))) {
			return false;
		}

		this.tokens.inject(index, "mat4(1.0)");
		this.tokens.blank(open);
		this.tokens.blank(unit);
		this.tokens.blank(close);

		return true;
	}

	/**
	 * Sends the model view of a pass drawn from the camera to the game's own block, with the bob put
	 * back on the front.
	 * <p>
	 * <strong>It is what Iris does for every program it patches as VANILLA</strong>, its vanilla
	 * transformer rewriting {@code gl_ModelViewMatrix} as
	 * {@code (iris_transforms.ModelViewMat * _iris_internal_translate(iris_transforms.ModelOffset))}
	 * ({@code transform/transformer/VanillaTransformer.java:354-366}, the second factor built under
	 * {@code parameters.hasChunkOffset} and a {@code iris_VIEW_SCALE} put in front for lines). Not
	 * every gbuffers program: the terrain family goes down the SODIUM road instead, where the same
	 * name becomes {@code u_ModelViewMatrix}
	 * ({@code transform/transformer/SodiumTransformer.java:72}), which is the same split this engine
	 * has. {@link LegacyGlsl#readsDrawModelView} carries which passes reach it here and why the
	 * shadow map is not one of them.
	 * <p>
	 * <strong>What it buys is that a piece and whatever is drawn at its own depth read the same
	 * matrix from the same place.</strong> A pass matrix is one per RUN and is built on the
	 * processor; the game writes one per DRAW, with the render type's own nudge in it. So long as
	 * only the glint read the second, an enchanted armour piece and the glint over it were two
	 * matrices meant to be equal, and the glint's depth test is an EQUALITY:
	 * {@link LegacyGlsl#GAME_MODEL_VIEW} sets out what that costs and how it was measured.
	 * <p>
	 * <strong>The bob is the half that is not Iris's line</strong>, and leaving it out is geometry
	 * that stands still while the camera walks: this engine leaves the game's matrices alone and
	 * splits them only where a pack reads them, so the matrix the game wrote for this draw has no bob
	 * in it and the projection a pack is handed has none either. {@link LegacyGlsl#CAMERA_BOB} is the
	 * factor that puts it back, and the product is what
	 * {@link dev.vitrail.uniform.ViewSource#passModelView} would have held for this draw.
	 * {@link LegacyGlsl#GAME_MODEL_VIEW} carries where Iris does the same move instead.
	 * <p>
	 * <strong>Every spelling of the product, because the fallback tree makes any of them reachable.</strong>
	 * {@code ftransform()} is the one the corpus's glints really write and the named matrices are what
	 * its entity programs write; whichever a pack chose, a piece left on the pass's matrix is a piece
	 * whose neighbour reads another. The core profile spelling {@code modelViewMatrix} is the only one
	 * a pack may also DECLARE, the {@code gl_} prefix being reserved:
	 * {@link LegacyGlsl#CORE_MATRICES} exists because OptiFine's core mode writes it.
	 * <p>
	 * <strong>What is deliberately left on the pass is everything DERIVED from the model view</strong>,
	 * which is the inverse and the normal matrix. Iris leaves them there too, and not as an oversight
	 * it would have tidied: {@code gl_ModelViewMatrixInverse} and {@code gl_NormalMatrix} become
	 * {@code iris_ModelViewMatInverse} and {@code iris_NormalMat}
	 * ({@code transform/transformer/VanillaTransformer.java:168-178}), both filled from
	 * {@code RenderSystem.getModelViewMatrix()} at program setup
	 * ({@code pipeline/programs/ExtendedShader.java:183} and {@code :188}), which is the stack and
	 * carries no nudge. So a pack that multiplies the matrix by its own inverse is a hair off in both
	 * engines, by the same hair.
	 * <p>
	 * <strong>On the hand it is not a hair, and it is the same difference twice rather than two
	 * findings.</strong> Both derived names come off one matrix,
	 * {@code dev.vitrail.uniform.ViewSource#passModelView}, so what separates them from the
	 * reference's separates them together: the hand's carries that pass's bob and Iris's stack does
	 * not. {@code dev.vitrail.render.EntityDraw.Element.modelView} carries the whole argument and
	 * what it costs the image; it is written down as a divergence rather than closed, because the
	 * answer that would close it is Iris's number against this engine's placement of the bob.
	 *
	 * @return whether the name was one this rewrites, false leaving it to the ordinary rename
	 */
	private boolean rewriteGameModelView(int index, String name) {
		if (name.equals("gl_ModelViewMatrix")) {
			this.tokens.inject(index, DRAW_MODEL_VIEW);
		} else if (name.equals("modelViewMatrix")) {
			// The one name here a pack may declare for itself, the {@code gl_} prefix being reserved,
			// so the one that has to tell a use from a declaration. Without this the declarator is
			// rewritten too and the unit reads "uniform mat4 (of_CameraBob * of_GameModelView);",
			// which costs the whole family and says only that a program would not compile: this loop
			// runs long before the uniforms are lifted, so nothing downstream would catch it.
			// The declaration is left standing and lifted like any other; what it declares is then a
			// member nobody reads, since every USE below has gone to the game's block.
			if (declaring(index)) {
				return false;
			}

			this.tokens.inject(index, DRAW_MODEL_VIEW);
		} else if (name.equals("gl_ModelViewProjectionMatrix")) {
			this.tokens.inject(index, "(of_ProjectionMatrix * " + DRAW_MODEL_VIEW + ")");
			this.injectedNames.add("of_ProjectionMatrix");
		} else {
			return false;
		}

		this.injectedNames.add(LegacyGlsl.CAMERA_BOB);
		this.injectedNames.add(LegacyGlsl.GAME_MODEL_VIEW);
		this.gameModelView++;

		return true;
	}

	/**
	 * Whether the identifier at this position is being DECLARED rather than read: an identifier
	 * straight after a built-in type name is being named, anywhere else it is being used.
	 * <p>
	 * It answers for the head of a declaration and for nothing else, where
	 * {@link #collectDeclarations} reads the whole list. A pack writing
	 * {@code uniform mat4 gbufferModelView, modelViewMatrix} would be told the second name is a
	 * use, and the injection below would land on the declarator. No pack of the corpus writes the
	 * shape, and closing it is not this method's alone: {@code declaredUnderAType} makes the same
	 * first-name-only test for the block, so the two move together or neither does.
	 */
	private boolean declaring(int index) {
		int before = this.tokens.significantBefore(index);

		return before >= 0 && LegacyGlsl.TYPE_NAMES.contains(this.tokens.get(before).text());
	}

	private boolean rewriteFtransform(int index) {
		int open = this.tokens.callOpener(index);
		int close = this.tokens.matchingBracket(open);
		if (close < 0 || this.tokens.significantAfter(open) != close) {
			return false;
		}

		// A pass drawn from the camera takes the draw's model view here too, and it has to: this is
		// the spelling the corpus really uses for a glint, and a pack writing ftransform() would
		// otherwise get the pass's matrix back through the door rewriteGameModelView closed.
		if (LegacyGlsl.readsDrawModelView(this.program)) {
			this.tokens.inject(index, "(of_ProjectionMatrix * " + DRAW_MODEL_VIEW + " * of_Vertex)");
			this.injectedNames.add("of_ProjectionMatrix");
			this.injectedNames.add(LegacyGlsl.CAMERA_BOB);
			this.injectedNames.add(LegacyGlsl.GAME_MODEL_VIEW);
			this.gameModelView++;
		} else {
			this.tokens.inject(index, "(of_ModelViewProjectionMatrix * of_Vertex)");
			this.injectedNames.add("of_ModelViewProjectionMatrix");
		}

		this.injectedNames.add("of_Vertex");
		this.tokens.blank(open);
		this.tokens.blank(close);

		return true;
	}

	/** A sampler parameter, by the function taking it and its position in that function's list. */
	private record SamplerParameter(String function, int position, Scoped scope) {
	}

	/**
	 * One name that means something particular over the lines where it does. A uniform reaches the
	 * end of the unit and a parameter stops at its own closing brace.
	 * <p>
	 * Lines and not token positions, because the passes that ask this insert tokens and shift every
	 * index after them, and not one of them inserts a line break.
	 */
	private record Scoped(String name, int from, int to) {
	}

	/**
	 * Puts the builtins back into the window the pack was written for: what
	 * {@code gl_FragCoord} reports of the target being rasterised, and what {@code gl_FragDepth}
	 * writes back into it.
	 * <p>
	 * <strong>A depth a lookup reads is not converted here, and that is the whole point.</strong> A
	 * rewrite can only match the name of the sampler, and the name is not enough to decide by: Bliss
	 * declares {@code BilateralUpscale_REUSE_Z(sampler2D tex1, sampler2D tex2, sampler2D depth, ...)}
	 * and hands it {@code colortex12} at {@code composite1.fsh:987} and {@code depthtex0} two lines
	 * below, both live in the same token stream. One of the two has to be converted and the other
	 * must not, and the body cannot be written twice. So the images are served already converted
	 * instead, which is what {@link dev.vitrail.pack.target.SamplerPlan.Kind#DEPTH} binds, and every
	 * lookup is right whatever it is reached through. {@link #countDepthLookup} keeps the two counts
	 * that say how big the name rule's blind spot was.
	 * <p>
	 * Runs after {@link #rewriteIdentifiers} because it matches lookups by name and that is where
	 * {@code texture2D} becomes {@code texture}.
	 * <p>
	 * Hardware comparison samplers are out of scope and cannot be done here at all: what comes back
	 * from a {@code sampler2DShadow} is the result of a comparison the hardware already made, not a
	 * depth, so the shadow map has to be written in the convention the comparison expects instead.
	 * The legacy {@code shadow2D} calls are safe by construction, since they have already been
	 * turned into {@code Kind#RAW} text that no later pass matches.
	 */
	private void convertDepth() {
		List<Closing> closings = new ArrayList<>();
		int[] lines = this.tokens.lineNumbers();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER) {
				continue;
			}

			// Above every skip below, and the bias is deliberate. A stage wrongly thought to write
			// its own depth pays the early depth test and nothing else, since the wrapper then fills
			// the mask from a value it wrote there itself; a stage wrongly thought not to fills the
			// mask from a depth the attachment never received, and that is a picture.
			if (this.stage == ProgramStage.FRAGMENT && token.identifier("gl_FragDepth")) {
				this.namesFragDepth = true;
			}

			String directive = token.directive();
			if (directive != null
					&& (LegacyGlsl.OPAQUE_DIRECTIVES.contains(directive) || token.macroName())) {
				continue;
			}

			// A pack that has taken one of these names for a macro of its own is left alone, both
			// for what the macro body does and for what its uses do: the preprocessor owns them.
			if (this.packMacros.contains(token.text())) {
				continue;
			}

			if (LegacyGlsl.DEPTH_LOOKUPS.contains(token.text())) {
				// The comparison first: what a comparison sampler hands back is not a depth, so it
				// is neither rewritten as a lookup nor counted as one.
				if (!rewriteShadowCompare(index, lines[index])) {
					countDepthLookup(index, lines[index]);
				}

				continue;
			}

			if (this.stage != ProgramStage.FRAGMENT) {
				continue;
			}

			if (token.identifier("gl_FragCoord")) {
				convertFragCoord(index);
			} else if (token.identifier("gl_FragDepth")) {
				convertFragDepth(index, closings);
			}
		}

		this.tokens.insertClosings(closings);
	}

	/**
	 * Recognises a lookup on a comparison sampler, and on the arithmetic road puts
	 * {@link #SHADOW_COMPARE} in its place.
	 * <p>
	 * <strong>On the hardware road nothing is rewritten at all.</strong> The declaration kept its
	 * spelling, so the lookup compiles to a depth-reference sample and the comparison is made by
	 * the sampler the binding put under the name, {@code GL_COMPARE_REF_TO_TEXTURE} in the terms
	 * Iris binds it in. The call is still counted and still answers true, because whatever road
	 * makes the comparison, what comes back is a fraction of the light and not a depth.
	 * <p>
	 * The arithmetic road exists because {@code GpuSampler} carries no comparison at all: two
	 * address modes, two filters, an anisotropy and a maximum level of detail. Bound as an ordinary
	 * sampler the comparison means nothing, and what a pack gets back is not a wrong shadow but no
	 * shadow information whatever, which reads on screen as a world entirely in shadow. On that
	 * road only the plain lookups are rewritten; a projective or gathered comparison is left as it
	 * stands and counted, since what it needs is a different expression, not a different name.
	 *
	 * @param line where the call stands, since a comparison sampler taken as a parameter only means
	 *             one inside the function that took it
	 * @return whether this call was a comparison, in which case it is not a depth read as well
	 */
	private boolean rewriteShadowCompare(int index, int line) {
		if (this.comparisonSamplers.isEmpty() && this.hardwareComparisonSamplers.isEmpty()) {
			return false;
		}

		int open = this.tokens.callOpener(index);
		int first = open < 0 ? -1 : this.tokens.significantAfter(open);
		if (first < 0 || this.tokens.get(first).kind() != Kind.IDENTIFIER) {
			return false;
		}

		String argument = this.tokens.get(first).text();
		if (hardwareComparisonAt(argument, line)) {
			return true;
		}

		if (!comparisonAt(argument, line)) {
			return false;
		}

		String name = this.tokens.get(index).text();
		if (!name.equals("texture") && !name.equals("textureLod")) {
			this.unwrappedShadowCalls++;

			return true;
		}

		// The name alone: the arguments of texture(sampler, vec3) are the arguments the comparison
		// takes, so nothing has to be found or balanced. textureLod carries a level this ignores,
		// which is what a comparison sampler with no mipmaps would have done anyway.
		this.tokens.replace(index, SHADOW_COMPARE);
		this.softRewrites++;

		return true;
	}

	/**
	 * Files one lookup on a depth texture under what its sampler is reached through. Nothing is
	 * rewritten: both counts describe the source, and one of them is the reason the source is left
	 * alone.
	 * <p>
	 * A name the plan classifies says what it reads and could be rewritten by name. A sampler the
	 * enclosing function was handed says nothing at all, and no rule on names ever could tell those
	 * apart: it is the same call for a colour target and for a depth. That count is what makes the
	 * blind spot a number rather than a claim, and it is why the engine converts the image instead.
	 * The lookups it does not reach either way, through a macro parameter or a local of some kind,
	 * fall in neither count and are the reason the two are read together rather than differenced.
	 */
	private void countDepthLookup(int index, int line) {
		int open = this.tokens.callOpener(index);
		int first = open < 0 ? -1 : this.tokens.significantAfter(open);
		if (first < 0 || this.tokens.get(first).kind() != Kind.IDENTIFIER) {
			return;
		}

		String name = this.tokens.get(first).text();
		if (SamplerPlan.classify(name) == SamplerPlan.Kind.DEPTH) {
			this.depthLookups++;
		} else if (scoped(this.samplerParameters, name, line)) {
			this.parameterLookups++;
		}
	}

	/**
	 * Pins every lookup through a sampler bound without a mip chain to the base level of its image:
	 * {@code texture(s, uv)} becomes {@code textureLod(s, uv, 0.0)}, a level the pack wrote out
	 * becomes nought, and a bias or a pair of derivatives, which only ever chose a level, is
	 * dropped.
	 * <p>
	 * <strong>What it restores is the reference's own filtering.</strong> Iris binds the three
	 * depth textures nearest and never mipmapped ({@code IrisSamplers.addWorldDepthSamplers} and
	 * {@code addCompositeSamplers}), the noise linear ({@code addNoiseSampler}), and a colour target
	 * through the target's own sampler, linear or nearest as its format allows and mipmapped only
	 * once a program's {@code colortexNMipmapEnabled} turned the chain on
	 * ({@code CompositeRenderer.setupMipmapping}). Under OpenGL a minification filter without a
	 * mipmap in its name never selects a level: whatever level of detail a lookup computes from its
	 * derivatives, or carries as an argument, the base image answers it. Vulkan has no such filter.
	 * Every sampler selects a level, and the one this engine binds where no chain exists is told to
	 * stay within a quarter of a level of the base, which ought to come to the same thing.
	 * Measured, it does not: AstraLex marches a reflection ray across {@code depthtex1} in its
	 * translucent pass, thirty steps with a {@code break}, reading the depth with {@code texture}
	 * at a coordinate each step computes, and what came back on some of those steps was not the
	 * depth, so the ray landed on a pixel it never reached and every glass pane of a village
	 * bloomed a saturated blue over its wall, on some frames and not others. The same read at an
	 * explicit level of nought came back right on every frame, as its first composite's re-read of
	 * the depth at a refracted coordinate did. Whether it is the derivatives of a coordinate made
	 * under a divergent branch or the clamp itself that the driver answers with something other
	 * than the base is the driver's to know. Pinning the level is what the reference's filter
	 * amounts to, and it is decidable here, from the text.
	 * <p>
	 * <strong>Which lookups.</strong> In a program drawn over the screen, every sampler declared at
	 * file scope that the program asks no chain for, the request being read off the same
	 * {@code colortexNMipmapEnabled} directives {@code TargetDirectives} reads; in a geometry
	 * program, the names the engine serves out of a colour target, a depth, the noise or the shadow
	 * map, and never the atlas or a material map, which carry chains and are read through them.
	 * The shadow map's samplers are pinned with the rest, whatever mipmap directive the pack
	 * wrote for them, because nothing of this engine fills a chain on the map. A sampler a
	 * function takes as a parameter has no name to classify, as {@link #countDepthLookup} says,
	 * so its call sites are read instead: the parameter is pinned when every call of its function
	 * hands it a sampler already pinned or a parameter already proven, and outright, call sites
	 * unread, in a program drawn over the screen that asks for no chain at all, since every image
	 * such a parameter could stand for is read at the base there. A parameter one call hands
	 * something else, or one that some macro calls its function through, is left as it stood and
	 * its lookups counted. A comparison sampler is left to {@link #rewriteShadowCompare}, a name
	 * the pack made a macro of to the preprocessor, and a rectangle, buffer or multisample sampler
	 * has no levelled lookup to pin.
	 * <p>
	 * A pack image laid over the name of a colour target is classified as the target in a geometry
	 * program, since only the binding knows the difference, and is pinned with it; nothing fills a
	 * chain on such an image either, so it reads the same.
	 */
	private void pinLookupLevels() {
		if (this.stage != ProgramStage.FRAGMENT || this.program.isEmpty()) {
			return;
		}

		boolean fullScreen = fullScreenPass();
		Set<String> chained = chainedSamplers();
		Set<String> pinned = new HashSet<>();
		for (Map.Entry<String, String> sampler : this.samplers.entrySet()) {
			String name = sampler.getKey();
			if (chained.contains(name) || !levelled(sampler.getValue())) {
				continue;
			}

			if (fullScreen || servedOutOfATarget(name)) {
				pinned.add(name);
			}
		}

		Set<Scoped> proven = provenParameters(pinned, fullScreen && chained.isEmpty());
		int[] lines = this.tokens.lineNumbers();
		List<Closing> levels = new ArrayList<>();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER || token.directive() != null || token.macroName()
					|| !LegacyGlsl.LEVELLED_LOOKUPS.contains(token.text())
					|| this.packMacros.contains(token.text())) {
				continue;
			}

			int open = this.tokens.callOpener(index);
			int close = this.tokens.matchingBracket(open);
			int first = this.tokens.significantAfter(open);
			if (close < 0 || first < 0 || this.tokens.get(first).kind() != Kind.IDENTIFIER) {
				continue;
			}

			String name = this.tokens.get(first).text();
			int line = lines[index];
			if (this.packMacros.contains(name) || comparisonAt(name, line)
					|| hardwareComparisonAt(name, line)) {
				continue;
			}

			// The parameter first, because a function may name one after a sampler of the file's
			// and mean its own inside its body.
			if (scoped(this.samplerParameters, name, line)) {
				if (!scoped(proven, name, line)) {
					this.unpinnedParameterLookups++;
					continue;
				}
			} else if (!pinned.contains(name)) {
				continue;
			}

			if (pinLookup(index, open, close, levels)) {
				this.pinnedLookups++;
			}
		}

		this.tokens.insertClosings(levels);
	}

	/**
	 * The sampler parameters every call site of whose function hands a sampler read at the base:
	 * a pinned name of the file, or a parameter already proven the same way, until nothing more
	 * can be proven. On that road a function nothing calls proves nothing, and a call whose
	 * argument is anything else, an expression, a macro, a name nothing classifies, keeps its
	 * parameter out.
	 *
	 * @param all whether every parameter of a levelled type is proven outright, call sites unread,
	 *            which is the case of a program drawn over the screen asking for no chain
	 */
	private Set<Scoped> provenParameters(Set<String> pinned, boolean all) {
		Set<Scoped> proven = new LinkedHashSet<>();
		if (all) {
			for (Scoped parameter : this.samplerParameters) {
				if (!this.unlevelledParameters.contains(parameter)) {
					proven.add(parameter);
				}
			}

			return proven;
		}

		// One walk that records where each of these functions is named, rather than a walk of the
		// whole token list per parameter and per round. The rounds are bounded by the number of
		// parameters, each one proving at least one or ending the loop, so a stage taking a
		// hundred samplers as parameters walked its few hundred thousand tokens a hundred times.
		List<SamplerParameter> waiting = new ArrayList<>();
		Set<String> functions = new HashSet<>();
		for (SamplerParameter parameter : this.typedParameters) {
			if (!this.unlevelledParameters.contains(parameter.scope())) {
				waiting.add(parameter);
				functions.add(parameter.function());
			}
		}

		Map<String, List<Integer>> sites = sitesOf(functions);
		int[] lines = this.tokens.lineNumbers();
		boolean grew = true;
		while (grew) {
			grew = false;
			for (Iterator<SamplerParameter> pending = waiting.iterator(); pending.hasNext(); ) {
				SamplerParameter parameter = pending.next();
				if (proven.contains(parameter.scope())) {
					pending.remove();
					continue;
				}

				if (!callsHandOver(parameter, pinned, proven, lines,
						sites.getOrDefault(parameter.function(), List.of()))) {
					continue;
				}

				proven.add(parameter.scope());
				pending.remove();
				grew = true;
			}
		}

		return proven;
	}

	/** Where each of these names is written, in the order the tokens run. */
	private Map<String, List<Integer>> sitesOf(Set<String> names) {
		Map<String, List<Integer>> found = new HashMap<>();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() == Kind.IDENTIFIER && names.contains(token.text())) {
				found.computeIfAbsent(token.text(), _ -> new ArrayList<>()).add(index);
			}
		}

		return found;
	}

	/**
	 * Whether every call of this parameter's function hands it a sampler read at the base, and
	 * there is at least one call. A definition or a prototype of the function is told from a call
	 * by what precedes the name, a builtin type or {@code void}; anything else, an operator, a
	 * keyword, a struct's name, is read as a call, and a definition read that way refuses the
	 * parameter, its declaration being no sampler's name. A function named on a preprocessor line
	 * is called from wherever that macro is used, which no scan of the tokens can see, so such a
	 * function proves nothing.
	 *
	 * @param sites every place the function is named, in the order the tokens run, which is what
	 *              this used to walk the whole list for
	 */
	private boolean callsHandOver(SamplerParameter parameter, Set<String> pinned,
			Set<Scoped> proven, int[] lines, List<Integer> sites) {
		boolean called = false;
		for (int index : sites) {
			Token token = this.tokens.get(index);
			if (token.directive() != null) {
				return false;
			}

			int open = this.tokens.callOpener(index);
			int before = this.tokens.significantBefore(index);
			if (open < 0 || (before >= 0 && this.tokens.get(before).kind() == Kind.IDENTIFIER
					&& returnsAType(this.tokens.get(before).text()))) {
				continue;
			}

			int close = this.tokens.matchingBracket(open);
			if (close < 0) {
				return false;
			}

			List<Integer> commas = topLevelCommas(open, close);
			if (commas.size() < parameter.position()) {
				return false;
			}

			int start = parameter.position() == 0 ? open : commas.get(parameter.position() - 1);
			int end = commas.size() > parameter.position() ? commas.get(parameter.position()) : close;
			int argument = this.tokens.significantAfter(start);
			if (argument < 0 || this.tokens.significantAfter(argument) != end
					|| this.tokens.get(argument).kind() != Kind.IDENTIFIER) {
				return false;
			}

			String name = this.tokens.get(argument).text();
			int line = lines[argument];
			boolean handed = scoped(this.samplerParameters, name, line)
					? scoped(proven, name, line)
					: pinned.contains(name);
			if (!handed) {
				return false;
			}

			called = true;
		}

		return called;
	}

	/** Whether a function name preceded by this word is a definition rather than a call. */
	private static boolean returnsAType(String word) {
		return LegacyGlsl.TYPE_NAMES.contains(word);
	}

	/**
	 * The samplers this program asks a chain for, under the names it declares them by: the targets
	 * its {@code colortexNMipmapEnabled} directives name, whatever spelling the sampler took. Read
	 * the way {@code TargetDirectives} reads them, on the live lines of this unit and the last
	 * declaration winning. The shadow map's own mipmap directives are not read: Iris honours them
	 * on the map's samplers and this engine fills no chain on the map, so its shadow lookups read
	 * the base whatever the pack asked, pinned or not, and that is the older gap rather than this
	 * pass's.
	 */
	private Set<String> chainedSamplers() {
		Set<Integer> targets = new HashSet<>();
		for (ConstDirectives.Directive directive : ConstDirectives.read(this.unit)) {
			boolean on = directive.value().equals("true");
			if (!directive.type().equals("bool") || !(on || directive.value().equals("false"))) {
				continue;
			}

			Optional<TargetName.Suffixed> split = TargetName.split(directive.name());
			if (split.isPresent() && split.get().suffix().equals(MIPMAP_SUFFIX)) {
				if (on) {
					targets.add(split.get().index());
				} else {
					targets.remove(split.get().index());
				}
			}
		}

		Set<String> chained = new HashSet<>();
		for (String name : this.samplers.keySet()) {
			OptionalInt index = TargetName.index(name);
			if (index.isPresent() && targets.contains(index.getAsInt())) {
				chained.add(name);
			}
		}

		return chained;
	}

	/**
	 * Whether a name is one the engine binds an image of its own under in every family and never
	 * gives a chain to in a geometry program: a colour target, a depth of the world or of the far
	 * terrain, the noise, or the shadow map's depth and colour, on which nothing of this engine
	 * fills a chain in any family.
	 */
	private static boolean servedOutOfATarget(String name) {
		return switch (SamplerPlan.classify(name)) {
			case COLORTEX, DEPTH, NOISE, DISTANT_DEPTH, SHADOW_DEPTH, SHADOW_COLOUR -> true;
			default -> false;
		};
	}

	/**
	 * Whether a sampler declared so has a level to pin. A rectangle, a buffer and a multisample
	 * image have one level and no lookup that takes one, and a comparison sampler is another pass's.
	 *
	 * @param declaration the type alone, or the declaration {@link #liftUniforms} recorded, which
	 *                    is the type followed by the name
	 */
	private static boolean levelled(String declaration) {
		for (String word : declaration.split(" ", -1)) {
			String shape = SamplerTypes.shapeOf(word);
			if (shape != null) {
				return !shape.contains("Rect") && !shape.contains("Buffer") && !shape.contains("MS")
						&& !shape.endsWith(SHADOW_SHAPE);
			}
		}

		return false;
	}

	/**
	 * One lookup onto the base level, in whichever spelling it was written, or false for a call
	 * with fewer arguments than its spelling takes, which the compiler will name for itself.
	 *
	 * @param levels where the literal goes when it is a new argument rather than a replaced one,
	 *               applied once the scan is over so that no index moves under it
	 */
	private boolean pinLookup(int index, int open, int close, List<Closing> levels) {
		List<Integer> commas = topLevelCommas(open, close);
		String directive = this.tokens.get(index).directive();

		switch (this.tokens.get(index).text()) {
			case "texture", "textureGrad" -> {
				if (commas.isEmpty()) {
					return false;
				}

				// The bias of the one and the two derivatives of the other are what the level was
				// computed from, and the level is a literal now.
				this.tokens.replace(index, "textureLod");
				dropArgumentsFrom(commas, 2, close);
				levels.add(new Closing(close, ", " + BASE_LEVEL, directive));
			}
			case "textureOffset" -> {
				if (commas.size() < 2) {
					return false;
				}

				this.tokens.replace(index, "textureLodOffset");
				dropArgumentsFrom(commas, 3, close);
				levels.add(new Closing(commas.get(1), ", " + BASE_LEVEL, directive));
			}
			case "textureGradOffset" -> {
				if (commas.size() != 4) {
					return false;
				}

				// The two derivatives make way for the literal and the offset stays where it is.
				this.tokens.replace(index, "textureLodOffset");
				setArgument(commas, 2, close, BASE_LEVEL);
				dropArgument(commas, 3, close);
			}
			default -> {
				if (commas.size() < 2) {
					return false;
				}

				setArgument(commas, 2, close, BASE_LEVEL);
			}
		}

		return true;
	}

	/** The commas that separate a call's arguments, and none of the ones nested inside them. */
	private List<Integer> topLevelCommas(int open, int close) {
		List<Integer> commas = new ArrayList<>();
		int depth = 0;

		for (int scan = open + 1; scan < close; scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() != Kind.OPERATOR) {
				continue;
			}

			if (token.operator("(") || token.operator("[") || token.operator("{")) {
				depth++;
			} else if (token.operator(")") || token.operator("]") || token.operator("}")) {
				depth--;
			} else if (depth == 0 && token.operator(",")) {
				commas.add(scan);
			}
		}

		return commas;
	}

	/** Blanks the argument at this position, counted from nought, and every one after it. */
	private void dropArgumentsFrom(List<Integer> commas, int at, int close) {
		if (commas.size() >= at) {
			this.tokens.blankRange(commas.get(at - 1), close - 1);
		}
	}

	/** Blanks the argument at this position alone, counted from nought, with the comma before it. */
	private void dropArgument(List<Integer> commas, int at, int close) {
		int end = commas.size() > at ? commas.get(at) - 1 : close - 1;
		this.tokens.blankRange(commas.get(at - 1), end);
	}

	/** Puts a literal in place of the argument at this position, counted from nought. */
	private void setArgument(List<Integer> commas, int at, int close, String text) {
		int first = this.tokens.significantAfter(commas.get(at - 1));
		int end = commas.size() > at ? commas.get(at) - 1 : close - 1;
		this.tokens.blankRange(commas.get(at - 1) + 1, end);
		this.tokens.inject(first, text);
	}

	/**
	 * {@code gl_FragCoord.z} is the window depth the rasteriser produced, so it is in the target's
	 * convention and asks the same question a depth lookup does, with the same answer.
	 * <p>
	 * The component is demanded rather than the vector wrapped. The corpus reads {@code .xy} three
	 * hundred and forty six times, {@code .x} a hundred and forty eight and {@code .y} a hundred
	 * and forty three, none of which is a depth, and wrapping the vector would quietly corrupt
	 * every one of them.
	 */
	private void convertFragCoord(int index) {
		int dot = this.tokens.significantAfter(index);
		int swizzle = dot >= 0 && this.tokens.get(dot).operator(".")
				? this.tokens.significantAfter(dot)
				: -1;
		if (swizzle < 0 || this.tokens.get(swizzle).kind() != Kind.IDENTIFIER) {
			// Reached some other way, a subscript or a whole vector handed to a function. Nothing
			// in the corpus does it, and a pack that starts has to be visible rather than wrong.
			this.fragCoordUnhandled++;
			return;
		}

		String field = this.tokens.get(swizzle).text();
		if (field.equals("z")) {
			this.tokens.inject(index, "(" + DEPTH_CONV + ".z * gl_FragCoord.z + " + DEPTH_CONV + ".w)");
			this.fragCoordZ++;
		} else if (field.equals("xyz")) {
			// Two screen components that convert to nothing and one depth that does not, so the
			// vector has to be rebuilt. Seven sites, all in gbuffers.
			this.tokens.inject(index, "vec3(gl_FragCoord.xy, " + DEPTH_CONV + ".z * gl_FragCoord.z + "
					+ DEPTH_CONV + ".w)");
			this.fragCoordXyz++;
		} else {
			if (namesDepth(field)) {
				this.fragCoordUnhandled++;
			}

			return;
		}

		this.tokens.blank(dot);
		this.tokens.blank(swizzle);
		takeDepthConv();
	}

	/** Whether a swizzle reaches the third component, under any of the three sets of names. */
	private static boolean namesDepth(String field) {
		return field.indexOf('z') >= 0 || field.indexOf('b') >= 0 || field.indexOf('p') >= 0;
	}

	/**
	 * A write, so the inverse of what a read does. The pack produces a legacy window depth and the
	 * hardware wants one in the target's convention, {@code d = (v - readB) / readA}; that inverse
	 * is spelled with the same expression as the read because {@code readA * x + readB} is an
	 * involution for both conventions, {@code readA} being one or minus one and {@code readB} nought
	 * or one either way. It is the only reason no division appears here.
	 * <p>
	 * If a third convention ever arrives with {@code |readA| != 1}, this site is the one that has to
	 * become {@code (v - of_DepthConv.w) / of_DepthConv.z}; the reads stay as they are.
	 */
	private void convertFragDepth(int index, List<Closing> closings) {
		if (this.tokens.get(index).directive() != null) {
			// The end of the statement is where the closing goes, and a macro body has no
			// semicolon of its own: the search would run into the next statement and put the
			// bracket in someone else's code.
			this.fragDepthUnhandled++;
			return;
		}

		int eq = this.tokens.significantAfter(index);
		int after = this.tokens.significantAfter(eq);
		boolean assignment = eq >= 0 && this.tokens.get(eq).operator("=")
				&& (after < 0 || !this.tokens.get(after).operator("="));
		int end = assignment ? this.tokens.statementEnd(eq) : -1;
		if (end < 0) {
			// Anything but a plain assignment ending in a semicolon. A compound one reads the
			// value back before writing it and a fragment stage has no readable depth to start
			// from, so there is nothing here that a rewrite at this site could mean. Counted
			// rather than guessed at: the corpus has none of either.
			this.fragDepthUnhandled++;
			return;
		}

		// The pack's expression is parenthesised, which is not decoration: the right hand side may
		// be a ternary, and multiplying into one without brackets changes what it means.
		String directive = this.tokens.get(eq).directive();
		this.tokens.inject(eq, "= (" + DEPTH_CONV + ".z * (");
		closings.add(new Closing(end, ") + " + DEPTH_CONV + ".w)", directive));
		takeDepthConv();
		this.fragDepthWrites++;
	}

	/**
	 * Asks for the conversion, which is only ever asked for by a rewrite that has just emitted a
	 * use of it. A member declared and never read still occupies its place in the block of every
	 * program of the pack, and the block is the layout.
	 */
	private void takeDepthConv() {
		record(this.blockMembers, DEPTH_CONV, "vec4 " + DEPTH_CONV);
		this.injectedNames.add(DEPTH_CONV);
	}

	private void takeTexShrink() {
		record(this.blockMembers, TEX_SHRINK, "vec2 " + TEX_SHRINK);
		this.injectedNames.add(TEX_SHRINK);
	}

	/**
	 * The colour a sky pass is modulated by, asked for by the one prologue that folds a uniform into
	 * {@code of_Color}.
	 * <p>
	 * Asked for HERE and not left to the collector, for the reason every injected member is: the
	 * collector walks the body, and this name appears only in a line the header prints, so nothing
	 * in the body would ever ask for it and the stage would name an identifier the block has not
	 * got.
	 */
	private void takePassColour() {
		record(this.blockMembers, PASS_COLOUR, "vec4 " + PASS_COLOUR);
		this.injectedNames.add(PASS_COLOUR);
	}

	/**
	 * {@code const} on a variable demands a compile-time constant under Vulkan, and OpenGL drivers
	 * accepted a lot that is not one: {@code transpose} of a matrix literal, a constructor from a
	 * uniform, {@code normalize} of a vector the pack treated as immutable. The keyword is the lie,
	 * not the value, so it comes off and the declaration stays.
	 * <p>
	 * An array size still needs a real constant, so a declaration whose initialiser is only literals
	 * and type constructors is left alone. Parameters keep {@code const}: that spelling means
	 * immutable, not compile-time, and the language allows it.
	 * <p>
	 * A name the unit {@code #define}s is judged by what it stands for, and it has to be both ways:
	 * this pass reads tokens, the macro is expanded later by the compiler, and what stands here is a
	 * name where the compiler will see the body. Mellow builds its outline offsets out of
	 * {@code OUTLINE_THICKNESS}, a constructor multiplied by it four times over, and hands the
	 * array to {@code textureGatherOffsets}, which takes nothing but a constant expression: that
	 * macro stands for a number, so the keyword stays, and read as non-constant the keyword came
	 * off a declaration that was constant all along and one fullscreen pass of the pack would not
	 * compile. Photon writes its fog colours through {@code from_srgb}, a macro over {@code pow}
	 * and a matrix this very pass demotes: judged by the macro's name alone the keyword stayed, the
	 * compiler refused what the name stood for, and a pack one of whose passes does not compile
	 * draws nothing at all.
	 */
	private void demoteNonConstantInitialisers() {
		int parens = 0;
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() != null) {
				continue;
			}

			if (token.operator("(")) {
				parens++;
				continue;
			}

			if (token.operator(")")) {
				parens--;
				continue;
			}

			if (parens != 0 || !token.identifier("const")) {
				continue;
			}

			int end = this.tokens.statementEnd(index);
			if (end >= 0 && nonConstantInitialiser(index, end)) {
				this.tokens.blank(index);
			}
		}
	}

	/**
	 * Whether this {@code const} declaration assigns something Vulkan will not take as a constant
	 * expression: any identifier {@link #constantName} does not vouch for.
	 */
	private boolean nonConstantInitialiser(int keyword, int end) {
		boolean seenEquals = false;
		for (int scan = keyword; scan <= end; scan++) {
			Token token = this.tokens.get(scan);
			if (token.operator("=")) {
				seenEquals = true;
				continue;
			}

			if (!seenEquals || token.kind() != Kind.IDENTIFIER) {
				continue;
			}

			if (!constantName(token.text(), new HashSet<>())) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether this name is taken as constant inside an initialiser: a type constructor, or a macro
	 * of the pack whose replacement text names nothing but those. A macro is read through because
	 * it is gone by the time the compiler judges the line, and a call or another global inside it
	 * counts exactly as it would written out. That is the coarseness of the rule above and not the
	 * compiler's: the compiler folds a builtin over literals and keeps the keyword, and what it
	 * refuses is a global this very pass demoted, which a macro is one way of naming. Reverie
	 * writes ten colours through a macro over {@code mix}, {@code pow} and {@code step}, and they
	 * lose a keyword the compiler would have kept, at no cost to the image.
	 */
	private boolean constantName(String name, Set<String> path) {
		if (LegacyGlsl.TYPE_NAMES.contains(name)) {
			return true;
		}

		if (!this.packMacros.contains(name)) {
			return false;
		}

		Boolean judged = this.macroConstant.get(name);
		if (judged != null) {
			return judged;
		}

		// A macro naming itself is not expanded again by the preprocessor either.
		if (path.contains(name)) {
			return true;
		}

		// A chain of names is bounded as PreprocessorExpression bounds its own: a pack is
		// downloaded content, and the overflow is an error nothing above here catches. Past the
		// bound the name counts as non-constant, which costs the keyword and nothing else.
		if (path.size() >= MAX_MACRO_HOPS) {
			return false;
		}

		path.add(name);
		try {
			boolean constant = true;
			for (String inside : this.macroBodies.getOrDefault(name, Set.of())) {
				if (!constantName(inside, path)) {
					constant = false;
					break;
				}
			}

			this.macroConstant.put(name, constant);

			return constant;
		} finally {
			path.remove(name);
		}
	}

	/**
	 * Precision qualifiers mean nothing on the desktop, but two declarations of one function that
	 * disagree about them are still a mismatch, which is a failure the packs hit and cannot see.
	 */
	private void dropPrecision() {
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER || token.directive() != null) {
				continue;
			}

			if (LegacyGlsl.PRECISION_QUALIFIERS.contains(token.text())) {
				this.tokens.blank(index);
				continue;
			}

			if (!token.identifier("precision")) {
				continue;
			}

			int qualifier = this.tokens.significantAfter(index);
			if (qualifier >= 0
					&& LegacyGlsl.PRECISION_QUALIFIERS.contains(this.tokens.get(qualifier).text())) {
				int end = this.tokens.statementEnd(index);
				if (end >= 0) {
					this.tokens.blankRange(index, end);
				}
			}
		}
	}

	/**
	 * Counts writes from every branch, taken or not, which makes the number of outputs an upper
	 * bound rather than the truth. That is deliberate and it was measured: restricting the count
	 * to branches the expander took cost four units of Sildur's, where the only write to output
	 * zero sits on a line the expander read as dead and the compiler read as live. Declaring an
	 * output nothing writes costs a location; failing to declare one that is written costs the
	 * program. Which attachment each output actually reaches is in {@code drawBuffers}, not here.
	 */
	private void rewriteFragmentOutputs() {
		if (this.stage != ProgramStage.FRAGMENT) {
			return;
		}

		boolean fragColor = false;

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER) {
				continue;
			}

			if (token.identifier("gl_FragColor")) {
				this.tokens.inject(index, "ofFragData0");
				fragColor = true;
				continue;
			}

			if (!token.identifier("gl_FragData")) {
				continue;
			}

			int slot = literalIndexAfter(index);
			if (slot < 0) {
				// An index no one can read at translation time. Declaring an output for it would
				// mean declaring the lot, and writing the ones a pack never touches is not free,
				// so this is counted and left to fail loudly.
				this.dynamicFragData++;
				continue;
			}

			int open = this.tokens.significantAfter(index);
			int number = this.tokens.significantAfter(open);
			this.tokens.inject(index, "ofFragData" + slot);
			this.tokens.blank(open);
			this.tokens.blank(number);
			this.tokens.blank(this.tokens.significantAfter(number));
			this.maxFragmentOutput = Math.max(this.maxFragmentOutput, slot);
		}

		if (fragColor) {
			this.maxFragmentOutput = Math.max(this.maxFragmentOutput, 0);
		}
	}

	/** The subscript of {@code gl_FragData[n]} when it is a literal in range, otherwise -1. */
	private int literalIndexAfter(int index) {
		int open = this.tokens.significantAfter(index);
		if (open < 0 || !this.tokens.get(open).operator("[")) {
			return -1;
		}

		int number = this.tokens.significantAfter(open);
		if (number < 0 || this.tokens.get(number).kind() != Kind.NUMBER) {
			return -1;
		}

		int close = this.tokens.significantAfter(number);
		if (close < 0 || !this.tokens.get(close).operator("]")) {
			return -1;
		}

		String text = this.tokens.get(number).text();
		if (text.length() > 2 || !text.chars().allMatch(Character::isDigit)) {
			return -1;
		}

		int slot = Integer.parseInt(text);

		return slot < MAX_FRAGMENT_OUTPUTS ? slot : -1;
	}

	/** One fragment output the pack declared for itself, once its declaration has been moved. */
	record Output(String name, String type) {
	}

	/**
	 * Moves the outputs a pack declares for itself up into the header, next to the ones this
	 * translation declares, so that the whole set is written in one place and in one order.
	 * <p>
	 * Half the corpus takes this road rather than {@code gl_FragData}: two hundred and forty three
	 * fragment stages of the eight packs declare their own outputs, all of them with a location
	 * they wrote themselves, and none of them mixing the two ways. Reverie's translucent stages are
	 * where it costs something, since they name {@code Albedo}, {@code buf1}, {@code buf2} and
	 * {@code Shadow} and write the fourth before the second.
	 * <p>
	 * Only a live line is moved, for the reason {@link #liftUniforms} gives about uniforms: a
	 * declaration in a branch nobody takes would become unconditional on the way up. A declaration
	 * this refuses simply stays where it stands, which is what happens today, so the refusal costs
	 * the ordering and nothing else.
	 */
	private void liftFragmentOutputs() {
		if (this.stage != ProgramStage.FRAGMENT) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("out") || token.directive() != null) {
				continue;
			}

			if (this.unit.isLive(lines[index]) && opensDeclaration(index)) {
				liftOutput(index);
			}
		}

		for (int location : this.packOutputs.keySet()) {
			this.maxFragmentOutput = Math.max(this.maxFragmentOutput, location);
		}
	}

	/**
	 * Whether this {@code out} opens a declaration rather than qualifying a function parameter.
	 * Told apart by what stands before it: a parameter follows the opening parenthesis or a comma,
	 * a declaration follows the end of the last one or a layout qualifier.
	 */
	private boolean opensDeclaration(int index) {
		int before = this.tokens.significantBefore(index);
		if (before < 0) {
			return true;
		}

		Token token = this.tokens.get(before);

		return token.operator(";") || token.operator("}") || token.operator(")");
	}

	private void liftOutput(int keyword) {
		int start = this.tokens.statementStart(keyword);
		int end = this.tokens.statementEnd(keyword);
		if (start < 0 || end < 0) {
			return;
		}

		List<Integer> parts = this.tokens.significantRange(start, end);
		int cursor = parts.indexOf(keyword);

		// A type, one declarator and the semicolon, and nothing else. A pack that declares two
		// outputs in one statement, or an array, or a type this cannot name, keeps its declaration
		// where it wrote it rather than having it guessed at.
		if (cursor < 0 || parts.size() != cursor + 4) {
			return;
		}

		String type = this.tokens.get(parts.get(cursor + 1)).text();
		Token name = this.tokens.get(parts.get(cursor + 2));
		if (!LegacyGlsl.TYPE_NAMES.contains(type) || name.kind() != Kind.IDENTIFIER
				|| !this.tokens.get(parts.get(cursor + 3)).operator(";")) {
			return;
		}

		// A location the pack spelled out as a number. Anything else, a macro or nothing at all,
		// leaves no way to say where the output belongs, and inventing one would be worse than the
		// disorder this exists to fix.
		int location = literalLocation(parts, cursor);
		if (location < 0 || location >= MAX_FRAGMENT_OUTPUTS || location <= this.maxFragmentOutput) {
			return;
		}

		if (this.packOutputs.putIfAbsent(location, new Output(name.text(), type)) == null) {
			this.tokens.blankRange(start, end);
		}
	}

	/** The {@code location = n} of a layout qualifier standing before this {@code out}, or -1. */
	private int literalLocation(List<Integer> parts, int keyword) {
		for (int at = 0; at + 2 < keyword; at++) {
			if (!this.tokens.get(parts.get(at)).identifier("location")) {
				continue;
			}

			Token number = this.tokens.get(parts.get(at + 2));
			String text = number.text();
			if (this.tokens.get(parts.get(at + 1)).operator("=") && number.kind() == Kind.NUMBER
					&& text.length() <= 2 && text.chars().allMatch(Character::isDigit)) {
				return Integer.parseInt(text);
			}
		}

		return -1;
	}

	/**
	 * Makes {@code main} name every output in ascending order before it does anything else, by
	 * calling the function the header declares for it.
	 * <p>
	 * The call goes into {@code main} rather than the naming itself because the order that decides
	 * is the order the compiler first meets the names in, and it walks the functions in the order
	 * they were written: a helper standing before {@code main} and writing one output would take
	 * the first rank whatever {@code main} does afterwards. The function is declared in the header
	 * and so stands before everything the pack wrote.
	 * <p>
	 * The brace is replaced rather than a token inserted, since inserting moves every index after
	 * it and this runs once the rest of the rewrite has settled.
	 */
	private void orderFragmentOutputs() {
		if (this.stage != ProgramStage.FRAGMENT || this.maxFragmentOutput < 0) {
			return;
		}

		// Where the body is wrapped, the wrapper makes the call and this must not also make it: the
		// brace it would replace belongs to a function that is about to be renamed, and a second
		// call would only name the outputs a second time.
		if (wrapsFragment()) {
			this.ordered = true;
			return;
		}

		int brace = mainBrace();
		if (brace >= 0) {
			this.tokens.inject(brace, "{ " + ORDER_OUTPUTS + "();");
			this.ordered = true;
		}
	}

	/**
	 * Works out whether this fragment stage can be given the alpha test its pass asks for, before
	 * anything is moved.
	 * <p>
	 * A discard has to come at the END of {@code main}, after the pack has written its colour, which
	 * is the opposite of the ascending call and so a second mechanism rather than the same one. It
	 * is done by wrapping, for the reasons {@link #wrapMain} gives about closing braces, and the
	 * wrapper then makes both calls.
	 * <p>
	 * Three things have to hold and none of them is a formality. The stage has to declare an output
	 * nought, since that is the one the fixed function pipeline tested and the only one a pack can
	 * mean; that output has to be a {@code vec4}, or it has no alpha to read; and {@code main} has
	 * to be findable, since a stage whose {@code main} is not is a stage nothing can be appended to.
	 * Nothing in the corpus fails any of the three, and a program that does keeps its picture and
	 * loses its discard, which {@link TranslatedUnit.Notes#alphaEpilogue} reports rather than hides.
	 */
	private void planAlphaEpilogue() {
		if (this.stage != ProgramStage.FRAGMENT || !this.alphaTest.tests()
				|| this.maxFragmentOutput < 0) {
			return;
		}

		Output first = this.packOutputs.get(0);
		if (first != null && !first.type().equals("vec4")) {
			return;
		}

		// Kept rather than asked for again in wrapMain: nothing moves the tokens between here and
		// there today, and a second walk would quietly start depending on that staying true.
		this.packMainName = mainName();
		this.alphaEpilogue = this.packMainName >= 0;
	}

	/**
	 * Works out whether this fragment stage can be given the coverage mask its pass asks for.
	 * <p>
	 * The mask is one more output, and where it lands is not where it is declared: the game asks the
	 * SPIR-V reflection for the outputs and writes each one's rank over its own location, so the
	 * mask has to be named after every output the pack declared, dead branches included. It is
	 * refused where there is no room for one more, which is the eight colour targets a pipeline
	 * carries, and where the stage declares no output at all, since then rank nought would be the
	 * mask and the pack's colour would have nowhere to go.
	 * <p>
	 * A refusal costs the picture nothing by itself. The pass then draws exactly as it did before
	 * the mask existed, and it is whoever reads the mask that has to notice it was never written.
	 */
	private void planCoverage() {
		if (this.stage != ProgramStage.FRAGMENT || !this.coverage || this.maxFragmentOutput < 0
				|| this.maxFragmentOutput + 1 >= MAX_FRAGMENT_OUTPUTS) {
			return;
		}

		// The assignment goes at the end of main, after the pack has written its colour and after
		// the discard, so the body is wrapped for the same reason the alpha test wraps it. The two
		// share the wrapper and the name, and whichever asked first has already found it.
		if (this.packMainName < 0) {
			this.packMainName = mainName();
		}

		this.covers = this.packMainName >= 0;
	}

	/** Whether the fragment stage's own {@code main} is wrapped, by the alpha test or by the mask. */
	private boolean wrapsFragment() {
		return this.alphaEpilogue || this.covers;
	}

	/**
	 * Converts the clip depth the vertex stage writes, once, after everything the pack did to it.
	 * <p>
	 * The pack leaves {@code gl_Position.z} in the OpenGL volume, near at minus w and far at plus w.
	 * With {@code clipA} and {@code clipB} at minus a half and a half that becomes
	 * {@code 0.5 * (w - z)}, which is the reversed Z the game rasterises in; at a half and a half it
	 * becomes {@code 0.5 * (z + w)}, which is the forward zero to one a target of ours carries. In
	 * both, w is untouched, so x, y and the perspective divide do not move and the Vulkan clip test
	 * {@code 0 <= z <= w} is the OpenGL test {@code -w <= z <= w} exactly: the clipping is preserved
	 * and not only the value.
	 * <p>
	 * It is an epilogue and not a rewrite of each write because the corpus writes {@code gl_Position}
	 * four hundred and ninety times, a hundred and fifty nine of them through a partial swizzle, and
	 * twenty eight of those go back and touch {@code .z} or {@code .w} afterwards: BSL scales
	 * {@code gl_Position.z} in {@code shadow.glsl:265}, Bliss subtracts from it in
	 * {@code all_translucent.vsh:153}. Those touch ups are in legacy space and have to be converted
	 * after the fact, once, whereas rewriting each write would compose the conversion once per write.
	 * <p>
	 * It is a wrapper around {@code main} and not an injection before its closing brace because
	 * {@link TokenStream#matchingBracket} cannot close a brace and, more to the point, counts
	 * operators without looking at whether their line is live: {@link #liftUniforms} refuses to
	 * count brace depth for that very reason, a pack opening a brace in one branch of an
	 * {@code #if} and closing it in another. A misplaced closing brace would put the epilogue in
	 * the middle of the code. Wrapping also survives an early {@code return} from {@code main},
	 * which no vertex stage in the corpus does, and the header is already where code of ours is
	 * written.
	 * <p>
	 * The depth conversion is for vertex stages only. A geometry stage writes {@code gl_Position}
	 * once per {@code EmitVertex}, so an epilogue would land in the wrong place, and a program
	 * carrying both would have the conversion done before the geometry stage read the position back.
	 * The corpus has no geometry stage at all; the day one appears, {@link #prepare} has to be told
	 * the program has one.
	 * <p>
	 * A fragment stage is wrapped for two reasons, the alpha test and the coverage mask, decided in
	 * {@link #planAlphaEpilogue} and {@link #planCoverage}. The same wrapping argument holds and the
	 * same header carries it.
	 */
	private void wrapMain() {
		if (this.stage == ProgramStage.FRAGMENT) {
			if (wrapsFragment()) {
				this.tokens.replace(this.packMainName, PACK_MAIN);
				this.mainWrapped = true;
			}

			return;
		}

		if (this.stage != ProgramStage.VERTEX) {
			return;
		}

		// Before the early return below, because this is not about wrapping. The sky prologue spells
		// of_Color out of this uniform whatever the bound format carries, either alone or times the
		// element, and it prints that line whatever else the stage does, so the block has to carry
		// the name whatever else the stage does. Taken for the sky and for no other family: it is a
		// member of the block of the sky programs only.
		if (this.inputs == VertexInputs.SKY) {
			takePassColour();
		}

		// A terrain stage is wrapped whatever it writes, and not only when it names gl_Position: the
		// prologue is what fills the four names the mesh answers, and it is also what keeps all four
		// attributes alive. An input the shader declares and never reads may be dropped from the
		// SPIR-V, and rebind only counts the ones that survived, so a dropped attribute shifts the
		// location of every one after it.
		boolean terrain = this.inputs.terrain();
		boolean distant = this.inputs == VertexInputs.DISTANT;
		boolean depth = namesClipPosition();
		boolean overlay = this.inputs.overlay();
		boolean lines = this.inputs == VertexInputs.LINES;
		if (!terrain && !distant && !depth && !overlay && !lines) {
			return;
		}

		int name = mainName();
		if (name < 0) {
			return;
		}

		this.tokens.replace(name, PACK_MAIN);
		this.terrainPrologue = terrain;
		this.distantPrologue = distant;
		this.entityWrapped = overlay;
		this.linesWrapped = lines;
		if (lines) {
			// The widening reads the screen size off the two uniforms a pack may read and often
			// does not declare in this program; naming them here is what has the block supply them.
			this.injectedNames.add("viewWidth");
			this.injectedNames.add("viewHeight");
		}

		if (terrain) {
			takeTexShrink();
		}

		if (depth) {
			takeDepthConv();
			this.depthEpilogue = true;
		}

		this.mainWrapped = true;
	}

	/**
	 * One matrix varying rewritten as one vector per column.
	 *
	 * @param input true when this stage reads the matrix ({@code in}), false when it writes it
	 */
	record SplitMatrix(String name, String matrixType, String columnType, int columns,
			String qualifier, boolean input) {
	}

	/**
	 * Turns each file-scope matrix {@code in} / {@code out} into one vector per column.
	 * <p>
	 * {@code IntermediaryShaderModule.createFromSpirv} numbers locations by the rank of each
	 * reflected variable, {@code 0..n-1} with no stride. A GLSL {@code mat3} is one variable and
	 * occupies three consecutive locations, so the next varying is numbered onto the second
	 * column and the rotation the pack stored is no longer orthonormal. AstraLex's night planet
	 * is the image of that: a billboard {@code xy / z} through a walked-on {@code mat3}.
	 * <p>
	 * OpenGL links by name and never asks. The workaround here is to take the declaration out of
	 * the body and emit the matrix as a local in the header, beside one vector per column: the pack
	 * body still writes and reads the original name, and the wrapper copies the columns. Arrays
	 * of matrices are left alone; no pack of the corpus writes one, and AstraLex's four matrices
	 * are not arrays.
	 */
	private void splitMatrixVaryings() {
		if (this.stage == ProgramStage.COMPUTE) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() != null || !this.unit.isLive(lines[index])) {
				continue;
			}

			boolean input = token.identifier("in");
			boolean output = token.identifier("out");
			if (!input && !output) {
				continue;
			}

			if (input && this.stage == ProgramStage.VERTEX) {
				continue;
			}

			FileScope declared = fileScopeDeclaration(index);
			if (declared == null) {
				continue;
			}

			MatrixColumns layout = matrixColumns(declared.type());
			if (layout == null) {
				continue;
			}

			boolean anyArray = declared.names().stream().anyMatch(name ->
					declared.declarators().getOrDefault(name, "").contains("["));
			if (anyArray) {
				continue;
			}

			this.tokens.blankRange(declared.start(), declared.end());

			for (String name : declared.names()) {
				this.splitMatrices.add(new SplitMatrix(name, declared.type(), layout.columnType(),
						layout.columns(), declared.qualifier(), input));
			}
		}
	}

	/**
	 * Wraps {@code main} when a matrix was split and nothing else had wrapped it, so the columns
	 * have a place to be copied from and the reconstructed matrix has a place to be assigned.
	 * <p>
	 * {@link #mainName} is not enough here. {@link #orderFragmentOutputs} has already run, and on a
	 * fragment that is not wrapped for alpha or coverage it replaces the opening brace with a
	 * {@code RAW} token. {@code mainName} then no longer sees that brace and returns -1, the
	 * header still emits a wrapper because the matrices were split, and the body keeps its own
	 * {@code main}: two bodies, which is the compile error a fragment with matrix varyings hits.
	 * The injected brace is still a body of {@code main}, so it is accepted here.
	 */
	private void wrapMainForSplits() {
		if (this.mainWrapped || (this.splitMatrices.isEmpty() && this.splitStructs.isEmpty()
				&& this.splitArrays.isEmpty())) {
			return;
		}

		int name = mainName();
		if (name < 0) {
			name = mainNameAfterOutputOrder();
		}

		if (name < 0) {
			return;
		}

		this.tokens.replace(name, PACK_MAIN);
		this.mainWrapped = true;
	}

	/**
	 * The {@code main} whose opening brace {@link #orderFragmentOutputs} already replaced, or -1.
	 * Same walk as {@link #mainName}, except a {@code RAW} token that still opens with a brace
	 * counts as the body.
	 */
	private int mainNameAfterOutputOrder() {
		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("main") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int close = this.tokens.matchingBracket(this.tokens.callOpener(index));
			int brace = this.tokens.significantAfter(close);
			if (brace >= 0) {
				Token after = this.tokens.get(brace);
				if (after.kind() == Kind.RAW && after.text().startsWith("{")) {
					return index;
				}
			}
		}

		return -1;
	}

	/** Column count and vector type of a GLSL matrix, or null when the type is not a matrix. */
	private record MatrixColumns(String columnType, int columns) {
	}

	/**
	 * {@code mat3} is three {@code vec3}, {@code mat4x3} is four {@code vec3}, {@code dmat2} is two
	 * {@code dvec2}. Anything else, including a vector, answers null.
	 */
	private static MatrixColumns matrixColumns(String type) {
		String prefix = "";
		String rest = type;
		if (rest.startsWith("dmat")) {
			prefix = "d";
			rest = rest.substring(1);
		}

		if (!rest.startsWith("mat")) {
			return null;
		}

		rest = rest.substring(3);
		int columns;
		int rows;
		int by = rest.indexOf('x');
		try {
			if (by < 0) {
				columns = Integer.parseInt(rest);
				rows = columns;
			} else {
				columns = Integer.parseInt(rest.substring(0, by));
				rows = Integer.parseInt(rest.substring(by + 1));
			}
		} catch (NumberFormatException ignored) {
			return null;
		}

		if (columns < 2 || columns > 4 || rows < 2 || rows > 4) {
			return null;
		}

		return new MatrixColumns(prefix + "vec" + rows, columns);
	}

	static String matrixColumnName(String matrix, int column) {
		return MATRIX_COLUMN + matrix + "_" + column;
	}

	/** One member of a struct a varying is declared under, in the order the struct lists it. */
	record StructMember(String type, String name) {
	}

	/**
	 * One struct varying rewritten as one varying per member.
	 *
	 * @param input true when this stage reads the struct ({@code in}), false when it writes it
	 */
	record SplitStruct(String name, String structType, List<StructMember> members,
			String qualifier, boolean input) {
	}

	/**
	 * Turns each file-scope struct {@code in} / {@code out} into one varying per member, for the
	 * reason {@link #splitMatrixVaryings} turns a matrix into its columns: a struct is one
	 * reflected variable occupying as many locations as it has members, and the rank the game
	 * numbers it by is one. Photon's {@code flat in OverworldFogParameters fog_params}, three
	 * {@code vec3} of fog coefficients, reached its water fragment stage wrong under that
	 * numbering, and the fog its reflections computed with them painted every distant lake red;
	 * handed over as three varyings, the same program draws the lake as the reference does.
	 * <p>
	 * The definition is read off the unit itself, since the type is the pack's own. Only a struct
	 * whose members are all scalars or vectors is split: a member that is a matrix, an array or a
	 * struct of its own would need the same treatment one level down, and no pack of the corpus
	 * writes one. Arrays of structs are left alone as arrays of matrices are.
	 */
	private void splitStructVaryings() {
		if (this.stage == ProgramStage.COMPUTE) {
			return;
		}

		Map<String, List<StructMember>> definitions = structDefinitions();
		if (definitions.isEmpty()) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() != null || !this.unit.isLive(lines[index])) {
				continue;
			}

			boolean input = token.identifier("in");
			boolean output = token.identifier("out");
			if (!input && !output) {
				continue;
			}

			if (input && this.stage == ProgramStage.VERTEX) {
				continue;
			}

			// Read against the struct types the unit defines: the plain reader only knows the
			// language's own types, which is why a struct varying was never a declaration to it.
			FileScope declared = fileScopeDeclaration(index, definitions.keySet());
			if (declared == null) {
				continue;
			}

			List<StructMember> members = definitions.get(declared.type());

			boolean anyArray = declared.names().stream().anyMatch(name ->
					declared.declarators().getOrDefault(name, "").contains("["));
			if (anyArray) {
				continue;
			}

			this.tokens.blankRange(declared.start(), declared.end());

			for (String name : declared.names()) {
				this.splitStructs.add(new SplitStruct(name, declared.type(), members,
						declared.qualifier(), input));
			}
		}

		// The definition moves to the header with the global that carries the pack's name: the
		// wrapper that rebuilds the struct stands in the header, above the body, and the type has
		// to exist there. The body's own definition goes blank so the type is not defined twice.
		Set<String> moved = new HashSet<>();
		for (SplitStruct split : this.splitStructs) {
			if (!moved.add(split.structType())) {
				continue;
			}

			int[] definition = definitionRange(split.structType());
			if (definition != null) {
				this.tokens.blankRange(definition[0], definition[1]);
			}
		}
	}

	/**
	 * Every struct the unit defines on a live line whose members are all scalars or vectors, by
	 * type name. A struct with any other member is left out, so its varyings stay as they are.
	 */
	private Map<String, List<StructMember>> structDefinitions() {
		Map<String, List<StructMember>> definitions = new LinkedHashMap<>();
		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("struct") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int name = this.tokens.significantAfter(index);
			int open = this.tokens.significantAfter(name);
			if (name < 0 || open < 0 || this.tokens.get(name).kind() != Kind.IDENTIFIER
					|| !this.tokens.get(open).operator("{")) {
				continue;
			}

			int close = this.tokens.matchingBracket(open);
			int semicolon = this.tokens.significantAfter(close);
			// A definition that declares an instance in the same breath, "struct T { ... } t;",
			// is left alone: taking it out of the body would take the instance with it.
			if (close < 0 || semicolon < 0 || !this.tokens.get(semicolon).operator(";")) {
				continue;
			}

			List<StructMember> members = structMembers(open + 1, close - 1);
			if (members != null && !members.isEmpty()) {
				definitions.putIfAbsent(this.tokens.get(name).text(), members);
			}
		}

		return definitions;
	}

	/**
	 * The members declared between a struct's braces, or null where one of them is not a scalar
	 * or a vector: a member with brackets, a matrix, a sampler or another struct.
	 */
	private List<StructMember> structMembers(int start, int end) {
		List<StructMember> members = new ArrayList<>();
		List<Integer> statement = new ArrayList<>();
		for (int scan : this.tokens.significantRange(start, end)) {
			Token token = this.tokens.get(scan);
			if (!token.operator(";")) {
				statement.add(scan);
				continue;
			}

			if (statement.size() < 2) {
				return null;
			}

			String type = this.tokens.get(statement.get(0)).text();
			if (!STRUCT_MEMBER_TYPES.contains(type)) {
				return null;
			}

			for (int part = 1; part < statement.size(); part++) {
				Token piece = this.tokens.get(statement.get(part));
				if (piece.operator(",")) {
					continue;
				}

				if (piece.kind() != Kind.IDENTIFIER) {
					return null;
				}

				members.add(new StructMember(type, piece.text()));
			}

			statement.clear();
		}

		return statement.isEmpty() ? members : null;
	}

	/**
	 * The first live definition of that struct, from its {@code struct} keyword to the semicolon
	 * closing it, or null where the unit holds none.
	 */
	private int[] definitionRange(String structType) {
		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("struct") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int name = this.tokens.significantAfter(index);
			int open = this.tokens.significantAfter(name);
			if (name < 0 || open < 0 || !this.tokens.get(name).identifier(structType)
					|| !this.tokens.get(open).operator("{")) {
				continue;
			}

			int close = this.tokens.matchingBracket(open);
			int semicolon = this.tokens.significantAfter(close);
			if (close >= 0 && semicolon >= 0 && this.tokens.get(semicolon).operator(";")) {
				return new int[] {index, semicolon};
			}
		}

		return null;
	}

	static String structMemberName(String struct, String member) {
		return STRUCT_MEMBER + struct + "_" + member;
	}

	/**
	 * One array varying rewritten as one varying per element.
	 *
	 * @param input true when this stage reads the array ({@code in}), false when it writes it
	 */
	record SplitArray(String name, String type, int size, String qualifier, boolean input) {
	}

	/**
	 * Turns each file-scope array {@code in} / {@code out} of scalars or vectors into one varying
	 * per element, for the reason the matrices and the structs are split: an array is one
	 * reflected variable over as many locations as it has elements, and one rank. Photon's
	 * {@code flat in vec3 sky_sh[9]} carries its sky harmonics into the deferred shading, and the
	 * two varyings after it were numbered onto its elements.
	 * <p>
	 * Only a single dimension sized by a number the pack wrote out is split: a size written as a
	 * name, a macro the unit still carries or a constant expression, is left alone with the
	 * declaration. A statement declaring an array beside a plain name is left alone too, so that
	 * the two are not pulled apart.
	 * <p>
	 * Only what the vertex stage writes and the fragment stage reads: a geometry or tessellation
	 * stage declares its per-vertex inputs as arrays that are no varying arrays, and a fragment
	 * stage's output array is a set of colour outputs, which the header numbers on its own.
	 */
	private void splitArrayVaryings() {
		boolean output = this.stage == ProgramStage.VERTEX;
		if (!output && this.stage != ProgramStage.FRAGMENT) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() != null || !this.unit.isLive(lines[index])
					|| !token.identifier(output ? "out" : "in")) {
				continue;
			}

			FileScope declared = fileScopeDeclaration(index);
			if (declared == null || !STRUCT_MEMBER_TYPES.contains(declared.type())) {
				continue;
			}

			List<SplitArray> found = new ArrayList<>();
			for (String name : declared.names()) {
				String prefix = declared.type() + " " + name;
				String declarator = declared.declarators().getOrDefault(name, "");
				Matcher size = declarator.startsWith(prefix)
						? ARRAY_SUFFIX.matcher(declarator.substring(prefix.length()))
						: null;
				if (size == null || !size.matches()) {
					found.clear();
					break;
				}

				found.add(new SplitArray(name, declared.type(), Integer.parseInt(size.group(1)),
						declared.qualifier(), !output));
			}

			if (found.isEmpty()) {
				continue;
			}

			this.tokens.blankRange(declared.start(), declared.end());
			this.splitArrays.addAll(found);
		}
	}

	static String arrayElementName(String array, int element) {
		return ARRAY_ELEMENT + array + "_" + element;
	}

	/**
	 * Wraps the body's own main so that the owed varyings can be assigned before it runs, where
	 * nothing had wrapped it already.
	 * <p>
	 * After {@link #prepare} rather than inside it, and that is forced: which varyings are owed is a
	 * property of the PROGRAM, decided once both stages have been read, and a stage prepared on its
	 * own cannot know. The token indices are stable by then, which {@link #regions} relies on for
	 * the same reason.
	 * <p>
	 * Before the pack's own main and not after, as Iris prepends it at
	 * {@code CompatibilityTransformer.java:494}. It matters where a pack writes the name itself on
	 * some path: the pack's own value has to win, so ours is what stands there before it runs.
	 */
	private void wrapMainForOwedOutputs() {
		if (this.mainWrapped) {
			return;
		}

		int name = mainName();
		if (name < 0) {
			// No main to wrap and so no place to assign from. The declarations still go out, which is
			// what the pairing reads; the values are then whatever the stage leaves, as they were.
			return;
		}

		this.tokens.replace(name, PACK_MAIN);
		this.mainWrapped = true;
	}

	/**
	 * Takes the vertex inputs the chunk mesh has not got out of the body, keeping the type the pack
	 * declared them under so that the header can hand back a global of the same shape.
	 * <p>
	 * Runs before {@link #rewriteIdentifiers}, which is where {@code attribute} becomes {@code in}
	 * and the two stop being distinguishable. Leaving one standing is not a soft failure: the game
	 * refuses outright, {@code IntermediaryShaderModule.rebind} raising on an input the vertex format
	 * has no element for, which is at least loud. What it must not do is guess: an {@code in} is also
	 * how a function names a parameter, so the keyword has to open the statement, and where it is
	 * {@code in} rather than {@code attribute} the name has to be one this engine knows is an
	 * attribute.
	 */
	private void synthesizeAttributes() {
		if (this.stage != ProgramStage.VERTEX || !this.inputs.synthesizes()) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() != null || !this.unit.isLive(lines[index])) {
				continue;
			}

			if (token.identifier("attribute")) {
				synthesizeOne(index, false);
			} else if (token.identifier("in")) {
				synthesizeOne(index, true);
			}
		}
	}

	/** @param known whether the declared name has to be one this engine already calls an attribute */
	private void synthesizeOne(int keyword, boolean known) {
		FileScope declared = fileScopeDeclaration(keyword);
		if (declared == null
				|| (known && declared.names().stream().noneMatch(VertexPrologue.SYNTHESIZED::contains))) {
			return;
		}

		declared.names().forEach(name -> this.synthesized.putIfAbsent(name, declared.type()));
		this.tokens.blankRange(declared.start(), declared.end());
	}

	/**
	 * Reads the varyings this stage declares at file scope, both ways round.
	 * <p>
	 * The two are not symmetric and neither is what the game does with them.
	 * {@code IntermediaryShaderModule.rebind} walks the vertex stage's outputs and looks each one up
	 * in the fragment stage by name: an input the fragment declares that nothing before it writes is
	 * refused outright, at :205-207, and the whole module goes with it. That is a refusal, not a
	 * silence, and it costs three packs of the corpus a pass apiece.
	 * <p>
	 * A vertex stage is not asked for its inputs. Its file scope {@code in} is an attribute, which
	 * the vertex format answers and {@link #synthesizeAttributes} has already dealt with.
	 */
	private void collectVaryings() {
		if (this.stage == ProgramStage.COMPUTE) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() != null || !this.unit.isLive(lines[index])) {
				continue;
			}

			if (token.identifier("out")) {
				FileScope declared = fileScopeDeclaration(index);
				if (declared != null) {
					this.declaredOutputs.addAll(declared.names());
					this.declaredOutputScopes.add(declared);
				}
			} else if (token.identifier("in") && this.stage != ProgramStage.VERTEX) {
				FileScope declared = fileScopeDeclaration(index);
				if (declared != null) {
					this.declaredInputs.add(declared);
				}
			}
		}
	}

	/**
	 * Takes back out every input this stage declares that no stage before it writes and that its own
	 * body never reads.
	 * <p>
	 * <strong>Both halves of the condition earn their place, and the first is what makes this
	 * safe.</strong> Dropping an input the vertex stage does write would be a silent corruption
	 * rather than a fix: {@code rebind} only counts the fragment inputs it found, at :151-163, so
	 * everything declared after the missing one lands a location too low with nothing said by
	 * anyone. And dropping one the body does read would turn a refusal into an undeclared
	 * identifier, which is the same pass lost with a worse message. So what is taken out is exactly
	 * what the game was going to refuse the module for, and never anything else.
	 * <p>
	 * A pack does not write these by hand. Mellow's {@code /global/gbuffers.fsh} declares eleven
	 * varyings and every fragment stage of the pack includes it, so its {@code deferred1}, drawn
	 * over a quad, asks for the six a geometry pass would have written; Bliss loses
	 * {@code gbuffers_water} to one name, and its water is then the game's own.
	 *
	 * @param provided every name a stage before this one declares as an output
	 */
	private void dropUnprovidedInputs(Set<String> provided) {
		List<FileScope> unprovided = this.declaredInputs.stream()
				.filter(declared -> declared.names().stream().noneMatch(provided::contains))
				.toList();
		if (unprovided.isEmpty()) {
			return;
		}

		Set<String> candidates = new HashSet<>();
		unprovided.forEach(declared -> candidates.addAll(declared.names()));
		Set<String> read = readNames(candidates);

		boolean dropped = false;
		for (FileScope declared : unprovided) {
			if (declared.names().stream().noneMatch(read::contains)) {
				this.tokens.blankRange(declared.start(), declared.end());
				this.droppedInputs.addAll(declared.names());
				dropped = true;
				continue;
			}

			// Read by the body, so it stays, and the stage before it has to hand it over instead.
			// Every name of the declaration and not only the ones read: they share one statement,
			// and a name left out of it is one the module is still refused for.
			for (String name : declared.names()) {
				// A DIVERGENCE, and an array is the whole of it. Iris patches one:
				// CompatibilityTransformer.java:467-497 bails only on a type with no numeric
				// specifier (:473-480), and its declarationTemplate at :68-70 is "out __type
				// __name;", which carries no array specifier at all. What makes that impossible
				// here is that the two sides would then disagree on the type, the fragment stage
				// holding "in float x[4]" against a vertex stage holding "out float x", and this
				// backend compiles each stage to its own module rather than linking them, so the
				// disagreement lands as a compile error naming neither stage. What it costs the
				// image is nothing: the pass is lost either way, here through the refusal it
				// already had and there through a module that does not build. The one array
				// varying the corpus declares, Photon's sky harmonics, is split into its elements
				// before this runs, and those elements are not offered here either: an input array
				// the vertex stage never wrote would stay declared, which no pack of the corpus
				// does. The declarator carrying brackets is what tells one, the type name alone
				// never doing so.
				if (declared.declarators().getOrDefault(name, "").equals(declared.type() + " " + name)) {
					this.unprovidedInputs.put(name, declared.qualifier().isEmpty()
							? declared.type()
							: declared.qualifier() + " " + declared.type());
				}
			}
		}

		if (dropped) {
			this.used = usedNames();
			this.declaredAfter = declaredUnderAType();
		}
	}

	/**
	 * Stops this stage handing on every varying the stage after it does not declare, by taking the
	 * {@code out} off the declaration and leaving a plain global of the same name and type behind.
	 * <p>
	 * <strong>The other way round from {@link #dropUnprovidedInputs}, and it is the way that says
	 * nothing at all.</strong> {@code rebind:151-163} numbers the fragment stage over the vertex
	 * stage's output list and advances its counter only on the names the fragment declares, while
	 * {@code createFromSpirv:114-116} had numbered that same list {@code 0..n-1} with nothing
	 * skipped. One name the fragment does not declare therefore drops every name after it in the
	 * list by a location, with no exception raised and no line logged, and the fragment reads its
	 * neighbour's value. Measured over the corpus before this: 39 pairs handing on a varying the
	 * fragment never declares, and 16 of them landing at least one location on the neighbour.
	 * <p>
	 * <strong>Demoted rather than deleted, because the body writes them.</strong> Deleting the
	 * declaration would leave the assignments naming nothing, which is the pass lost. A global of
	 * the same name and type keeps every assignment compiling and is simply not part of the
	 * interface any more, which is the whole of what was wanted. The interpolation qualifier goes
	 * with the {@code out}: {@code flat} on a plain global is a syntax error, and it means nothing
	 * once there is nobody to interpolate for.
	 * <p>
	 * <strong>What Iris does here is nothing, and that is not an oversight on its part.</strong> It
	 * links two stages the way OpenGL does, where an output nobody reads is legal and costs nothing;
	 * there is no line of {@code CompatibilityTransformer} to follow because the language it targets
	 * never asked the question. What forces the hand here is
	 * {@code IntermediaryShaderModule.rebind:151-163}, which pairs by counting rather than by name.
	 * The picture is unchanged either way: what is taken out of the interface is, by the condition
	 * itself, a value no later stage could read.
	 * <p>
	 * <strong>A declaration is taken whole or left alone.</strong> {@code out vec3 a, b;} with the
	 * fragment declaring {@code b} alone stays as it is, since demoting it would take {@code b} out
	 * from under a fragment that reads it and that is the failure this exists to prevent. No pack
	 * of the corpus writes one, the measurement below being zero, and the shape is worth naming
	 * rather than reading as covered.
	 * <p>
	 * <strong>Only what the PACK declares is offered here, on both sides.</strong> The two varyings
	 * the engine names itself are emitted into both headers off one union and are never in either
	 * list, so neither can be withheld from under a stage that declares it. The shape that would
	 * be a hazard is a pack writing {@code out vec4 entityColor} in its vertex stage against a
	 * fragment that only gets the name from the header; it cannot arise, because a vertex stage
	 * that reaches that union already receives the header's declaration of the same name and is
	 * refused for the redefinition before this is ever asked.
	 *
	 * @param declared every name the stage after this one still declares as an input
	 */
	private void withholdUndeclaredOutputs(Set<String> declared) {
		for (FileScope scope : this.declaredOutputScopes) {
			if (scope.names().stream().anyMatch(declared::contains)) {
				continue;
			}

			// From the start of the statement up to the type, which is the first token that is
			// neither the keyword nor a qualifier. Either order has to be taken: GLSL accepts both,
			// Mellow opens with flat, and fileScopeDeclaration already gathers them both ways.
			for (int index : this.tokens.significantRange(scope.start(), scope.end())) {
				String text = this.tokens.get(index).text();
				if (!text.equals("out") && !LegacyGlsl.INTERPOLATION_QUALIFIERS.contains(text)) {
					break;
				}

				this.tokens.blank(index);
			}

			this.declaredOutputs.removeAll(scope.names());
		}
	}

	/**
	 * Which of these names the body really reads, counting a function that declares one of them for
	 * itself as meaning its own.
	 * <p>
	 * Counting mentions is not enough and Mellow is why. Its {@code deferred1} includes a header
	 * declaring eleven varyings and its own full screen vertex stage writes five of them; of the six
	 * left over, three go out here. {@code Tangent} is mentioned once in the whole stage, as a
	 * parameter of the pack's own {@code tbn_decode(vec3 Normal, vec4 Tangent)}, and {@code Normal}
	 * is mentioned in five functions, each of which declares a {@code Normal} of its own, four as a
	 * parameter and one as a local. Counting mentions would keep both, and the game would then
	 * refuse the whole module over two varyings nothing reads.
	 * <p>
	 * <strong>Scope is taken at the function and not at the block, which is coarser than the
	 * language.</strong> A name declared inside an {@code if} and read again after the closing brace
	 * is called shadowed here and is not. Nothing in the corpus does it, and the cost if something
	 * did is bounded by the caller: the only declarations offered here are ones no earlier stage
	 * writes, so the worst this can do is turn a module the game refuses into a stage that does not
	 * compile.
	 */
	private Set<String> readNames(Set<String> candidates) {
		boolean[] declaration = new boolean[this.tokens.size()];
		for (FileScope declared : this.declaredInputs) {
			for (int index = declared.start(); index <= declared.end(); index++) {
				declaration[index] = true;
			}
		}

		int[] region = this.regions;
		int[] lines = this.tokens.lineNumbers();
		Map<String, Set<Integer>> shadowed = new HashMap<>();
		Set<String> read = new HashSet<>();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			// A branch nobody takes neither reads the name nor shadows it, since the compiler never
			// sees either line. Asked of both, so that the two halves keep agreeing.
			if (token.kind() != Kind.IDENTIFIER || declaration[index]
					|| !this.unit.isLive(lines[index]) || !candidates.contains(token.text())) {
				continue;
			}

			int before = this.tokens.significantBefore(index);
			boolean declares = before >= 0
					&& LegacyGlsl.TYPE_NAMES.contains(this.tokens.get(before).text());
			if (declares && region[index] >= 0) {
				// From here to the end of the function, and not before it: a mention above the
				// declaration is still the varying's, which is what the language says.
				shadowed.computeIfAbsent(token.text(), ignored -> new HashSet<>()).add(region[index]);
			} else if (!declares && !shadowed.getOrDefault(token.text(), Set.of()).contains(region[index])) {
				read.add(token.text());
			}
		}

		return read;
	}

	/**
	 * Which top level block each token belongs to, counting the signature that opens it, or -1 for a
	 * token at file scope. A local declaration only means its own name inside one of these.
	 * <p>
	 * Counts the pack's braces and only those, so it has to be asked while they are all that is
	 * there. {@link #rewrite} asks it once, at the last moment where that holds.
	 */
	private int[] regions() {
		int[] region = new int[this.tokens.size()];
		Arrays.fill(region, -1);

		int depth = 0;
		int current = -1;
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() == null && token.operator("{")) {
				if (depth == 0) {
					current++;
					// Back over the signature, so that a parameter counts as declared inside the
					// body it names things for rather than at file scope beside it.
					int from = this.tokens.statementStart(index);
					for (int back = from < 0 ? index : from; back <= index; back++) {
						region[back] = current;
					}
				}

				depth++;
			} else if (token.directive() == null && token.operator("}")) {
				depth--;
			}

			if (depth > 0) {
				region[index] = current;
			}
		}

		return region;
	}

	/**
	 * One declaration at file scope, and the tokens that would go with it if it were taken out.
	 *
	 * @param qualifier   the interpolation qualifiers of the declaration, from either side of the
	 *                    storage keyword, empty where it has none. Kept because a varying handed to
	 *                    the other stage has to be declared there under the same ones, and
	 *                    {@code flat} is the one that matters: an integer varying is flat or it is
	 *                    nothing
	 * @param declarators each name with the text that declares it, array brackets included, which is
	 *                    what lets a declaration be written again rather than described
	 */
	private record FileScope(List<String> names, String type, String qualifier,
			Map<String, String> declarators, int start, int end) {

		private FileScope {
			names = List.copyOf(names);
			declarators = Map.copyOf(declarators);
		}
	}

	/**
	 * The declaration a storage keyword opens at file scope, or null where the keyword opens
	 * something else.
	 * <p>
	 * It must not guess: {@code in} and {@code out} are also how a function names a parameter, so
	 * the keyword has to open the statement, and everything before it has to be an interpolation
	 * qualifier. A parameter list puts the return type and the function's name ahead of it, and a
	 * caller blanking from there would erase the function.
	 * <p>
	 * That rules out one form a pack could legally write, {@code layout(location = N) out}: what
	 * stands before the keyword is no interpolation qualifier, so this answers null and the varying
	 * is neither offered to {@link #dropUnprovidedInputs} nor counted as provided. Harmless where
	 * both stages spell it that way, since neither side is seen. The shape to watch for is a vertex
	 * stage writing it against a fragment stage that declares the same name plainly and never reads
	 * it, which would be taken out from under a location the vertex stage still fills. No pack of
	 * the corpus writes the form: over the stages the harness emits there is not one
	 * {@code layout} qualified {@code in} among the 905 fragment stages, and not one
	 * {@code layout} qualified {@code out} among the 903 vertex stages. What this rules out is a
	 * pack, and never a stage of ours: the fragment outputs the header writes carry the qualifier
	 * and are never read back through here.
	 */
	private FileScope fileScopeDeclaration(int keyword) {
		return fileScopeDeclaration(keyword, LegacyGlsl.TYPE_NAMES);
	}

	/**
	 * The same, for a declaration whose type is among those named rather than among the
	 * language's own: the struct types a unit defines, which the split of struct varyings reads
	 * and nothing else asks for.
	 */
	private FileScope fileScopeDeclaration(int keyword, Set<String> types) {
		int end = this.tokens.statementEnd(keyword);
		int start = end < 0 ? -1 : this.tokens.statementStart(keyword);
		if (start < 0) {
			return null;
		}

		List<Integer> parts = this.tokens.significantRange(start, end);
		int cursor = parts.indexOf(keyword);
		if (cursor < 0) {
			return null;
		}

		// Gathered on both sides of the keyword, because GLSL takes the qualifier either way round
		// and packs write both: Mellow opens with flat, and a pack writing "in flat" is as legal.
		List<String> qualifiers = new ArrayList<>();
		for (int before = 0; before < cursor; before++) {
			String text = this.tokens.get(parts.get(before)).text();
			if (!LegacyGlsl.INTERPOLATION_QUALIFIERS.contains(text)) {
				return null;
			}

			qualifiers.add(text);
		}

		cursor++;
		while (cursor < parts.size() && (isQualifier(this.tokens.get(parts.get(cursor)))
				|| LegacyGlsl.INTERPOLATION_QUALIFIERS
						.contains(this.tokens.get(parts.get(cursor)).text()))) {
			String text = this.tokens.get(parts.get(cursor)).text();
			if (LegacyGlsl.INTERPOLATION_QUALIFIERS.contains(text)) {
				qualifiers.add(text);
			}

			cursor++;
		}

		if (cursor >= parts.size() || this.tokens.get(parts.get(cursor)).kind() != Kind.IDENTIFIER) {
			return null;
		}

		String type = this.tokens.get(parts.get(cursor)).text();
		if (!types.contains(type)) {
			return null;
		}

		Map<String, String> found = new LinkedHashMap<>();
		if (!readDeclarators(parts, cursor + 1, type, "", found)) {
			return null;
		}

		return new FileScope(List.copyOf(found.keySet()), type, String.join(" ", qualifiers), found,
				start, end);
	}

	private boolean namesClipPosition() {
		for (Token token : this.tokens) {
			if (token.identifier("gl_Position")) {
				return true;
			}
		}

		return false;
	}

	/**
	 * The brace opening the body of {@code main}, or -1 when the unit serves no such function.
	 */
	private int mainBrace() {
		int name = mainName();

		return name < 0
				? -1
				: this.tokens.significantAfter(
						this.tokens.matchingBracket(this.tokens.callOpener(name)));
	}

	/**
	 * The name of the {@code main} this unit really serves, or -1 when it serves none.
	 * <p>
	 * A dead one is stepped over, for the reason {@link #liftFragmentOutputs} gives about
	 * declarations: a pack that writes one {@code main} per branch of an {@code #if} would
	 * otherwise have the prologue put in whichever came first in the file, and the branch the
	 * compiler actually sees would name its outputs in whatever order the pack wrote them in.
	 */
	private int mainName() {
		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("main") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int close = this.tokens.matchingBracket(this.tokens.callOpener(index));
			int brace = this.tokens.significantAfter(close);
			if (brace >= 0 && this.tokens.get(brace).operator("{")) {
				return index;
			}
		}

		return -1;
	}

	/**
	 * Moves every plain uniform into one block, because Vulkan takes no other kind. Samplers stay
	 * opaque and loose, but their declarations leave the body the same way: the header writes them
	 * in the order the program handed over, sampled names first, so MoltenVK numbers those first.
	 * <p>
	 * Brace depth is not consulted. A uniform is only legal at file scope, {@code uniform} is a
	 * reserved word so it can be nothing else, and a pack that opens a brace in one branch of an
	 * {@code #if} and closes it in another would put any depth count out by one for the rest of
	 * the file.
	 */
	private void liftUniforms() {
		int[] lines = this.tokens.lineNumbers();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("uniform") || token.directive() != null) {
				continue;
			}

			// A declaration in a branch nobody takes stays where it is. Moving it to the header
			// would make it unconditional, and packs do declare a name as a uniform in one branch
			// and as an ordinary global in the other. Complementary's dhProjection six are the
			// other side of that rule: they are ordinary pack uniforms under DISTANT_HORIZONS,
			// Iris registers them as such (CommonUniforms.java:184-186, MatrixUniforms.java:41-45)
			// and OpenGL accepts the loose form. They enter OfGlobals only when that branch is
			// live. If the expander never saw the symbol and the header still defines it, they
			// stay in the body and Vulkan refuses the unit; PackChain.load installs the table
			// before SettingSet.resolve so the two readers agree.
			if (this.unit.isLive(lines[index])) {
				liftOne(index);
			}
		}

		// Where the mesh carries the overlay this name is not a uniform at all, so the declaration
		// the loop above has just taken out of the body leaves nothing behind. Iris deletes the same
		// declaration outright (EntityPatcher.java:39) and answers every read from a varying its
		// vertex stage writes; leaving the name in the block would answer them all with one number
		// instead, which is the mob that never flashes.
		//
		// By the name and not by the shape, where Iris matches the whole declaration. The corpus
		// writes one shape and only one, eighteen declarations of uniform vec4 entityColor over the
		// eight packs, so the two answer the same today; a pack that wrote another type would keep
		// its uniform under Iris and lose the pass to a redefinition, and lose the type here.
		//
		// The three identifiers leave by the same door and for the same reason, one draw batching
		// several submissions: a chest and the mob beside it share it, so a number in the block is
		// one number for both. Iris deletes those three declarations in the same breath
		// (EntityPatcher.java:130-132).
		if (this.inputs.overlay()) {
			this.blockMembers.remove(ENTITY_COLOR);
			ENTITY_IDS.forEach(this.blockMembers::remove);
		}
	}

	private void liftOne(int keyword) {
		int end = this.tokens.statementEnd(keyword);
		if (end < 0) {
			// Either a uniform block, which is already legal, or a declaration with no semicolon,
			// which is a pack problem and not ours to paper over.
			return;
		}

		int start = this.tokens.statementStart(keyword);
		if (start < 0) {
			return;
		}

		List<Integer> parts = this.tokens.significantRange(start, end);
		int keywordAt = parts.indexOf(keyword);
		if (keywordAt < 0) {
			return;
		}

		// Both sides of the keyword, because the corpus uses both. BSL and Complementary's voxel
		// volume write writeonly uniform image3D, the qualifier in front, and Bliss and
		// Complementary's player atlas write uniform readonly and uniform writeonly, the
		// qualifier behind. Reading only what follows the keyword lost the first form.
		List<String> memory = new ArrayList<>();
		for (int part = 0; part < keywordAt; part++) {
			rememberMemoryQualifier(memory, this.tokens.get(parts.get(part)));
		}

		int cursor = keywordAt + 1;
		while (cursor < parts.size() && isQualifier(this.tokens.get(parts.get(cursor)))) {
			rememberMemoryQualifier(memory, this.tokens.get(parts.get(cursor)));
			cursor++;
		}

		if (cursor >= parts.size() || this.tokens.get(parts.get(cursor)).kind() != Kind.IDENTIFIER) {
			return;
		}

		String type = this.tokens.get(parts.get(cursor)).text();

		// An opaque uniform is recorded the same way, then taken out of the body: the header
		// writes every sampler in the order the program settled, which is what MoltenVK numbers.
		boolean opaque = LegacyGlsl.isOpaqueType(type);

		// Recorded under the spelling the token carries, which is the road decision made real:
		// collectComparisonSamplers has already taken the comparison out of every declaration it
		// sent to the arithmetic, and one still spelling it is one the binding owes a comparison
		// sampler, so the header has to keep saying so. Taking it out here again is what once
		// declared a kept comparison ordinary, and every lookup on it stopped compiling.
		//
		// Recorded for an opaque uniform alone, which is the only declaration these can be
		// written on and the only one that reads them back out of the header.
		if (!readDeclarators(parts, cursor + 1, type, opaque ? String.join(" ", memory) : "",
				opaque ? this.samplers : this.blockMembers)) {
			return;
		}

		this.tokens.blankRange(start, end);
	}

	/**
	 * Finds every sampler the pack declared as a comparison sampler, remembers the name so that
	 * {@link #rewriteShadowCompare} knows its lookups, and settles which of the two roads each
	 * takes: the declaration keeps its spelling and the binding carries a comparison sampler, or it
	 * is declared ordinary and {@link #SHADOW_COMPARE} makes the comparison in arithmetic.
	 * <p>
	 * A pass of its own, and early, because of when the others run: the uniforms are lifted after
	 * the depth conversion, and the conversion is the one place that has to know. It walks the
	 * declaration itself rather than reusing the lifting, since all it needs is the type token and
	 * the names it introduces, and it must not disturb what the lifting then reads.
	 * <p>
	 * The two forms a comparison sampler is written in are told apart by the parenthesis, and they
	 * have to be: a declaration lists names until the semicolon, a parameter names one thing and
	 * ends at the comma. Read as a declaration, {@code shadowsmoothfilter(in sampler2DShadow tex,
	 * in vec3 uv, ...)} claims every identifier down to the first statement of the body.
	 * <p>
	 * A declaration in a branch nobody takes says nothing about the name, the way it says nothing to
	 * {@link #liftUniforms}. Both Complementary packs declare {@code shadowtex0} a plain
	 * {@code sampler2D} in their {@code composite1} and a {@code sampler2DShadow} in the branch that
	 * is not composite1; taking the dead one would make every lookup on that name a comparison, in a
	 * program whose texture is not one. The bracket count still walks every token, live or not,
	 * because it is the shape of the text and not a statement about the program.
	 * <p>
	 * The road is decided per declaration, by the name and by nothing a stage chooses, so the same
	 * name answers the same way in every stage that spells it the same. A file-scope declaration
	 * keeps its spelling when every name it introduces is one of the shadow map's own and the pack
	 * has not taken the name over as a custom image, because those are the names the binding can
	 * really put a comparison sampler under; anything else compared is rewritten ordinary and
	 * handed the arithmetic, as every name once was. What this cannot close is a pack spelling one
	 * name two ways across the stages of one program: the sampler is the pipeline's, so the plain
	 * spelling would read through the comparison, undefined exactly as it is under Iris, and the
	 * binding says so by name when it meets one.
	 * <p>
	 * A parameter follows the unit: it keeps its spelling where the unit keeps a comparison at file
	 * scope, since that is what its callers pass, and goes back to ordinary with everything else,
	 * which is what the callers hold then. A unit mixing both roads at file scope leaves a helper
	 * with one type and a caller with the other, which fails to compile, loudly; no pack of the
	 * corpus does. A compute unit is all-arithmetic whatever it declares, its descriptors being
	 * pushed on a road the render-pass substitution never sees, and so is every unit of a launch
	 * whose soft-compare switch is armed.
	 */
	private void collectComparisonSamplers() {
		int[] lines = this.tokens.lineNumbers();
		int depth = 0;
		int parameters = -1;
		boolean arithmetic = softCompare() || this.stage == ProgramStage.COMPUTE;
		List<Integer> parameterTypes = new ArrayList<>();
		List<Scoped> parameterNames = new ArrayList<>();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() == null && token.operator("(")) {
				if (depth == 0) {
					parameters = index;
				}

				depth++;
			} else if (token.directive() == null && token.operator(")")) {
				depth--;
			}

			if (token.kind() != Kind.IDENTIFIER || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			String plain = withoutComparison(token.text());
			if (plain.equals(token.text()) || !LegacyGlsl.isOpaqueType(token.text())) {
				continue;
			}

			// Every identifier this declaration introduces, and how far what it introduces them
			// into reaches. Array bounds and commas are not identifiers, so they are stepped over.
			int last = depth > 0 ? this.tokens.functionEnd(parameters) : this.tokens.size() - 1;
			List<Scoped> introduced = new ArrayList<>();
			boolean bindable = !arithmetic;
			for (int scan = this.tokens.significantAfter(index); scan >= 0;
					scan = this.tokens.significantAfter(scan)) {
				Token next = this.tokens.get(scan);
				if (next.operator(";") || (depth > 0 && (next.operator(",") || next.operator(")")))) {
					break;
				}

				if (next.kind() == Kind.IDENTIFIER) {
					introduced.add(new Scoped(next.text(), lines[index], lines[last]));
					if (depth == 0 && (SamplerPlan.classify(next.text())
							!= SamplerPlan.Kind.SHADOW_DEPTH
							|| CustomImages.named(next.text()))) {
						bindable = false;
					}
				}
			}

			// A parameter waits for the file-scope verdict, since what it must match is whatever
			// its callers will be holding by then.
			if (depth > 0) {
				parameterTypes.add(index);
				parameterNames.addAll(introduced);
			} else if (bindable) {
				this.hardwareComparisonSamplers.addAll(introduced);
			} else {
				this.tokens.replace(index, plain);
				this.comparisonSamplers.addAll(introduced);
			}
		}

		if (!this.hardwareComparisonSamplers.isEmpty()) {
			this.hardwareComparisonSamplers.addAll(parameterNames);

			return;
		}

		for (int index : parameterTypes) {
			this.tokens.replace(index, withoutComparison(this.tokens.get(index).text()));
		}

		this.comparisonSamplers.addAll(parameterNames);
	}

	/**
	 * Finds every sampler a function takes as a parameter, and over which lines that name means it.
	 * <p>
	 * Nothing is rewritten and nothing depends on it being complete: it exists so that a lookup made
	 * through such a name is counted as one no rule on names could have classified, rather than
	 * passed over in silence. The old rewrite by name returned on exactly these and moved no
	 * counter, so the one thing it could not do was also the one thing nothing measured.
	 */
	private void collectSamplerParameters() {
		int[] lines = this.tokens.lineNumbers();
		int depth = 0;
		int parameters = -1;
		int position = 0;

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() == null && token.operator("(")) {
				if (depth == 0) {
					parameters = index;
					position = 0;
				}

				depth++;
			} else if (token.directive() == null && token.operator(")")) {
				depth--;
			} else if (token.directive() == null && depth == 1 && token.operator(",")) {
				position++;
			}

			// Inside a parenthesis and nowhere else, which is what tells a parameter from a
			// declaration: a declaration lists names until the semicolon and would claim the whole
			// body of whatever follows it.
			if (depth <= 0 || parameters < 0 || token.directive() != null
					|| token.kind() != Kind.IDENTIFIER || !LegacyGlsl.isOpaqueType(token.text())) {
				continue;
			}

			int name = this.tokens.significantAfter(index);
			if (name >= 0 && this.tokens.get(name).kind() == Kind.IDENTIFIER) {
				Scoped parameter = new Scoped(this.tokens.get(name).text(), lines[index],
						lines[this.tokens.functionEnd(parameters)]);
				this.samplerParameters.add(parameter);
				if (!levelled(token.text())) {
					this.unlevelledParameters.add(parameter);
				}

				int function = this.tokens.significantBefore(parameters);
				if (function >= 0 && this.tokens.get(function).kind() == Kind.IDENTIFIER) {
					this.typedParameters.add(new SamplerParameter(this.tokens.get(function).text(),
							position, parameter));
				}
			}
		}
	}

	/**
	 * Moves {@code centerDepthSmooth} off its declaration and onto a lookup in one texel.
	 * <p>
	 * The value is the depth at the middle of the screen, smoothed by the pack's own half life, and
	 * it is what a depth of field focuses on. It is accumulated on the card, in the texel the
	 * engine's {@code CenterDepth} pass draws, and never comes back to this side at all, so it
	 * cannot be a member of the uniform block: the declaration has to become a sampler and every use
	 * of the name a lookup. That is exactly what Iris does, in
	 * {@code CompositeDepthTransformer}, and the packs are written against the result rather than
	 * against a float.
	 * <p>
	 * <strong>Only a full screen family, which is where Iris makes it available and nowhere
	 * else.</strong> Its transformer runs under {@code Patch.COMPOSITE} alone, so a
	 * {@code gbuffers} program that declares the name keeps a float nothing writes and reads a
	 * nought. Mellow is the one pack of the corpus that reaches that case, declaring it in an include
	 * its whole tree takes, and serving it there would be a value where the packs were written for a
	 * zero.
	 * <p>
	 * The family and not the vertex format, for the same reason Iris chooses on the patch: what
	 * decides this is which stage of the frame a program is drawn in, and a file translated with no
	 * pass named is the entry point of nothing and is left as it stands. A compute stage is out for
	 * the same reason: {@code TransformPatcher} sends {@code Patch.COMPUTE} to
	 * {@code CommonTransformer} alone and past this transformer entirely.
	 * <p>
	 * Neither of those two families reaches this on the pack road, and both lines are written for
	 * the one reader that does meet them: {@code PackProgram} reads a vertex, a geometry and a
	 * fragment stage and no other, so a compute unit is never translated at all, and
	 * {@code TargetPlan} puts a shadow composite aside before the list of running programs is
	 * built. What is left is the harness, which translates a unit on its own with no plan around it.
	 * <p>
	 * The third refusal is a pack that {@code #define}s the name itself: the lookup would be
	 * expanded by its own macro and the declaration is not the pack's meaning of the word. It is
	 * taken from the macros the unit collects rather than from the live lines, so a definition in a
	 * branch that is not taken stops the move as well - wider than it needs to be, and no pack of
	 * the corpus writes it either way.
	 * <p>
	 * <strong>A shadow composite is out too, and there the reason is ours.</strong> Iris injects the
	 * sampler there like anywhere else and never binds it: {@code ShadowCompositeRenderer} does not
	 * name {@code CenterDepthSampler} once, so what the declaration reads is texture unit nought and
	 * no pack can build on it. This engine runs no shadow composite at all, which
	 * {@code TargetPlan} says by name where it drops them, so there is nothing here to bind either.
	 * Leaving the family alone gives it the nought a gbuffers program gets, which costs the image
	 * nothing: not one program of the family is drawn.
	 * <p>
	 * The member comes out of its declaration rather than the declaration out of the file, which is
	 * what lets a name declared beside others be moved: {@code uniform float a, centerDepthSmooth;}
	 * keeps its {@code a}. Where the name sits in the list makes no difference, the type being
	 * looked for at the head of the list rather than in front of the name alone; Iris detaches the
	 * {@code DeclarationMember} wherever it sits too, and injects its sampler before the
	 * declarations. Here the sampler is registered like any other opaque uniform and the header
	 * writes it, since the lifting has already been given a statement that no longer names it.
	 * <p>
	 * A declaration of the name under anything but {@code uniform float} is left exactly where it
	 * is, and it neither moves this unit nor stops it. That is Iris's shape: its matcher takes
	 * {@code uniform float name;} and nothing else, so a unit where the name is only a local, a
	 * parameter or a global of the pack's own is not rewritten on either side, while a unit that
	 * declares the uniform as well is rewritten around the other declaration. Every read goes to
	 * the lookup there, the local's own reads included, which is what
	 * {@code CompositeDepthTransformer.java:50} does over the whole tree. Both Complementary packs
	 * write the shape, the uniform in one branch of an {@code #if} and an ordinary {@code float} of
	 * the same name in the other; at their own defaults neither branch is live, so what they meet
	 * is the empty unit above.
	 */
	private void moveCenterDepth() {
		if (this.stage == ProgramStage.COMPUTE || !movesCenterDepth()
				|| this.packMacros.contains(CENTER_DEPTH)) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		List<Integer> members = new ArrayList<>();
		List<Integer> reads = new ArrayList<>();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER || !token.text().equals(CENTER_DEPTH)
					|| token.directive() != null || !this.unit.isLive(lines[index])) {
				continue;
			}

			int type = declarationType(index);
			if (type < 0) {
				reads.add(index);
			} else if (uniformFloat(type)) {
				members.add(index);
			}
		}

		// A unit that reads the name without declaring it as a uniform is left alone, which is the
		// same answer Iris gives: what is not declared is not made available.
		if (members.isEmpty()) {
			return;
		}

		// The header declares the sampler, because the statement the name was taken out of may
		// still declare other names and cannot carry a second declaration.
		String texel = SamplerPlan.centerDepth();
		members.forEach(this::detachMember);

		this.samplers.put(texel, SAMPLER_2D + " " + texel);
		reads.forEach(read -> this.tokens.inject(read, LOOKUP + "(" + texel + ", vec2(0.5)).r"));
	}

	/**
	 * Whether the pass this file is the entry point of is one this engine draws over a quad, which is
	 * where Iris makes the value available, less the shadow composites it never binds and this engine
	 * never runs.
	 */
	private boolean movesCenterDepth() {
		return fullScreenPass();
	}

	/**
	 * Whether the pass this file is the entry point of is drawn over a quad by this engine: named,
	 * not a geometry family, and not a shadow composite, which this engine never runs.
	 */
	private boolean fullScreenPass() {
		String family = ProgramNames.familyOf(this.program);

		return !this.program.isEmpty() && !ProgramNames.geometry(family)
				&& !ProgramNames.shadowComposite(family);
	}

	/**
	 * The type token of the declaration this name is a declarator of, or -1 when the name is read
	 * rather than declared.
	 * <p>
	 * A built-in type in front of the name is what makes it a declaration, and it has to be the
	 * type set rather than any identifier: this lexer has no keyword, so
	 * {@code return centerDepthSmooth;} puts an identifier there too. The type is not always in
	 * front of the name, though, and the walk back over the declarators before it is what finds it
	 * where it is not: without that walk {@code uniform float a, centerDepthSmooth;} reads as a use
	 * of the name, no member is found, and the whole unit is left standing with a member nothing
	 * answers.
	 * <p>
	 * The walk gives up on anything that is not a bare declarator, and that is what keeps a read
	 * out of the members: the comma of {@code f(a, centerDepthSmooth)} is reached the same way and
	 * stops on the bracket in front of {@code a}.
	 */
	private int declarationType(int name) {
		int cursor = name;
		while (cursor > 0) {
			int before = this.tokens.significantBefore(cursor);
			if (before < 0) {
				return -1;
			}

			Token token = this.tokens.get(before);
			if (LegacyGlsl.TYPE_NAMES.contains(token.text())) {
				return before;
			}

			if (!token.operator(",")) {
				return -1;
			}

			// The declarator this comma binds to the list. Anything else there, an initialiser or a
			// closing bracket, is an expression and not a list this pass knows how to read.
			int previous = this.tokens.significantBefore(before);
			if (previous < 0 || this.tokens.get(previous).kind() != Kind.IDENTIFIER) {
				return -1;
			}

			cursor = previous;
		}

		return -1;
	}

	/**
	 * Whether the declaration this type token opens is a {@code uniform float}, which is the one
	 * shape whose member can be moved onto a sampler.
	 */
	private boolean uniformFloat(int type) {
		if (!this.tokens.get(type).text().equals(CENTER_DEPTH_TYPE)) {
			return false;
		}

		int cursor = this.tokens.significantBefore(type);
		while (cursor >= 0 && isQualifier(this.tokens.get(cursor))) {
			cursor = this.tokens.significantBefore(cursor);
		}

		return cursor >= 0 && this.tokens.get(cursor).identifier("uniform");
	}

	/**
	 * Takes one declarator out of the statement that introduces it, leaving whatever else the
	 * statement declares standing.
	 * <p>
	 * The comma that binds the name is what goes with it, and it is the one before unless the name
	 * opens the list. Where there is no comma either side the name is the only declarator, and then
	 * the statement itself goes: a {@code uniform float ;} left behind is not a declaration of
	 * nothing, it is a syntax error.
	 */
	private void detachMember(int name) {
		int before = this.tokens.significantBefore(name);
		if (before >= 0 && this.tokens.get(before).operator(",")) {
			this.tokens.blank(before);
			this.tokens.blank(name);

			return;
		}

		int after = this.tokens.significantAfter(name);
		if (after >= 0 && this.tokens.get(after).operator(",")) {
			this.tokens.blank(name);
			this.tokens.blank(after);

			return;
		}

		int start = this.tokens.statementStart(name);
		int end = this.tokens.statementEnd(name);
		if (start < 0 || end < 0) {
			// A declaration with no semicolon, which is a pack problem and not ours to paper over.
			// Left whole rather than half emptied, the way the lifting leaves it.
			return;
		}

		this.tokens.blankRange(start, end);
	}

	/**
	 * Moves every volume the pack ships onto a flat atlas: the declaration to a {@code sampler2D}
	 * under a forged name, and each lookup to a helper that reads two slices and mixes them.
	 * <p>
	 * <strong>The declaration is what has to go, not the lookup.</strong>
	 * {@code GlslCompiler.addToBindGroup} refuses anything the reflection reports as neither
	 * {@code SpvDim2D} nor {@code SpvDimCube}, and the reflection lists a module's whole resource
	 * list at optimisation level zero, so a {@code sampler3D} declared in a shared include and never
	 * read costs the program its pipeline exactly as one sampled on every pixel does. Supplying a
	 * real volume would not help either: the type is the refusal.
	 * <p>
	 * <strong>Every program carrying the declaration is rewritten, and Iris rewrites only the stage
	 * the directive names.</strong> Its {@code TextureTransformer} runs per stage, so under it
	 * Mellow's composites and its final keep a live {@code sampler3D colortex6} bound to nothing,
	 * which GL tolerates and Vulkan does not. Renaming everywhere invents nothing: the pack has
	 * named exactly one file for that identifier, with its shape, its size and its format written
	 * out, and that file is what every one of those declarations was going to read.
	 * <p>
	 * Nothing is moved unless everything can be. A name this unit reaches any other way than as
	 * {@code texture(name, vec3)}, the name being the declared one or a macro the unit defines as
	 * exactly that name, is counted and left exactly as it stands, declaration included, so the
	 * program stays refused with the message it had. There is no site in the corpus like that, and
	 * the count is what would say one had appeared.
	 */
	private void flattenVolumes() {
		if (this.volumes.isEmpty()) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		this.volumes.forEach((name, atlas) -> flattenOne(name, atlas, lines));
	}

	private void flattenOne(String name, VolumeAtlas atlas, int[] lines) {
		// The name token of a declaration, and the pair of tokens each lookup rewrites: the callee
		// and the argument. Held rather than found again below, so that what is rewritten is what
		// was judged.
		List<Integer> declarations = new ArrayList<>();
		Map<Integer, Integer> lookups = new LinkedHashMap<>();
		boolean elsewhere = this.packMacros.contains(name);
		Set<String> spellings = spellingsOf(name);

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER || !spellings.contains(token.text())
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			if (token.directive() != null) {
				// The preprocessor's own lines: an alias being defined or tested is its business,
				// and the name standing as an alias's replacement text IS the alias. The name on
				// any other directive is a use this pass cannot follow.
				if (token.text().equals(name) && !aliasBody(index)) {
					elsewhere = true;
				}

				continue;
			}

			int callee = plainLookup(index);
			if (token.text().equals(name) && volumeDeclaration(index)) {
				declarations.add(index);
			} else if (callee >= 0) {
				lookups.put(index, callee);
			} else {
				elsewhere = true;
			}
		}

		// A unit that reads the name without declaring it has been handed the sampler by something
		// this pass has not seen, so there is nothing here to rename it against.
		if (declarations.isEmpty()) {
			return;
		}

		if (elsewhere) {
			this.volumesLeftAlone++;
			return;
		}

		String forged = SamplerPlan.forged(name);
		for (int declaration : declarations) {
			this.tokens.replace(this.tokens.significantBefore(declaration), "sampler2D");
			this.tokens.replace(declaration, forged);
		}

		lookups.forEach((argument, callee) -> {
			this.tokens.replace(callee, VOLUME_LOOKUP + name);
			this.tokens.replace(argument, forged);
		});

		this.volumeLookups += lookups.size();
		if (!lookups.isEmpty()) {
			this.readVolumes.put(name, atlas);
		}
	}

	/** The name and every macro the unit defines as exactly that name, aliases of aliases included. */
	private Set<String> spellingsOf(String name) {
		Set<String> spellings = new HashSet<>();
		spellings.add(name);
		boolean grew = true;
		while (grew) {
			grew = false;
			for (Map.Entry<String, String> alias : this.macroAliases.entrySet()) {
				if (spellings.contains(alias.getValue()) && spellings.add(alias.getKey())) {
					grew = true;
				}
			}
		}

		return spellings;
	}

	/**
	 * Whether this token, on a directive, is the whole replacement text of a macro standing for it:
	 * the {@code depthtex0} of {@code #define ATMOSPHERE_SCATTERING_LUT depthtex0}.
	 */
	private boolean aliasBody(int index) {
		for (int scan = index - 1; scan >= 0; scan--) {
			Token token = this.tokens.get(scan);
			if (token.trivia()) {
				continue;
			}

			return token.macroName()
					&& this.tokens.get(index).text().equals(this.macroAliases.get(token.text()));
		}

		return false;
	}

	/**
	 * Whether this name is being declared as a uniform of a three dimensional shape here.
	 * <p>
	 * The {@code uniform} is demanded and the type is not enough on its own: a function taking a
	 * {@code sampler3D} parameter of the same name declares a name inside its own body, and renaming
	 * that would leave the body reading a parameter nobody passes.
	 */
	private boolean volumeDeclaration(int index) {
		int type = this.tokens.significantBefore(index);
		if (type < 0 || this.tokens.get(type).kind() != Kind.IDENTIFIER
				|| !"3D".equals(SamplerTypes.shapeOf(this.tokens.get(type).text()))) {
			return false;
		}

		int cursor = this.tokens.significantBefore(type);
		while (cursor >= 0 && isQualifier(this.tokens.get(cursor))) {
			cursor = this.tokens.significantBefore(cursor);
		}

		return cursor >= 0 && this.tokens.get(cursor).identifier("uniform");
	}

	/**
	 * The {@code texture} this name is the first argument of, or -1 when it is reached any other
	 * way. The argument count is checked as well as the name: {@code texture(s, p, bias)} compiles
	 * and means something else, and the helper takes two.
	 */
	private int plainLookup(int index) {
		int open = this.tokens.significantBefore(index);
		if (open < 0 || !this.tokens.get(open).operator("(")) {
			return -1;
		}

		int callee = this.tokens.significantBefore(open);
		if (callee < 0 || !this.tokens.get(callee).identifier(LOOKUP)) {
			return -1;
		}

		int close = this.tokens.matchingBracket(open);

		return close >= 0 && arguments(open, close) == LOOKUP_ARGUMENTS ? callee : -1;
	}

	/** How many arguments a call holds, counting the commas that belong to it and not to a nested one. */
	private int arguments(int open, int close) {
		int depth = 0;
		int count = 1;

		for (int index = open; index < close; index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.OPERATOR || token.directive() != null) {
				continue;
			}

			String text = token.text();
			if (text.equals("(") || text.equals("[")) {
				depth++;
			} else if (text.equals(")") || text.equals("]")) {
				depth--;
			} else if (depth == 1 && text.equals(",")) {
				count++;
			}
		}

		return count;
	}

	/**
	 * The trilinear read of a volume, over the atlas its slices were laid out in.
	 * <p>
	 * The hardware does the two dimensional half: each slice carries one texel of gutter holding
	 * what lies past its edge, the far edge for a volume that repeats and the edge itself for one
	 * that clamps, so a bilinear tap at the edge of a tile reads what {@code REPEAT} or
	 * {@code CLAMP} would have read on a real volume rather than the slice next door. Only the depth
	 * is done here, two taps and a mix, because nothing interpolates between tiles of an atlas, and
	 * the slice index repeats or clamps as the pack asked.
	 * <p>
	 * The half texel is the whole of the arithmetic: a lookup at {@code u} samples the volume at
	 * {@code u * size - 0.5} in texels, and the atlas coordinate has to land on the same pair of
	 * texels the hardware would have blended. Every constant here comes from {@link VolumeAtlas} so
	 * that this and the upload cannot drift apart; a layout written twice reads as noise, and noise
	 * that is wrong looks exactly like noise that is right.
	 */
	static List<String> volumeHelper(String name, VolumeAtlas atlas) {
		String depth = whole(atlas.depth());
		String last = Integer.toString(atlas.depth() - 1);
		String tiles = Integer.toString(atlas.tilesPerRow());

		List<String> lines = new ArrayList<>();
		lines.add("vec4 " + VOLUME_LOOKUP + name + "(sampler2D ofMap, vec3 ofAt) {");
		lines.add(atlas.clamp() ? "\tvec3 ofQ = clamp(ofAt, 0.0, 1.0);" : "\tvec3 ofQ = fract(ofAt);");
		lines.add("\tfloat ofZ = ofQ.z * " + depth + " - 0.5;");
		lines.add("\tfloat ofBase = floor(ofZ);");
		lines.add("\tvec2 ofIn = ofQ.xy * vec2(" + whole(atlas.width()) + ", " + whole(atlas.height())
				+ ") + " + whole(VolumeAtlas.GUTTER) + ";");
		if (atlas.clamp()) {
			lines.add("\tint ofNear = clamp(int(ofBase), 0, " + last + ");");
			lines.add("\tint ofFar = clamp(int(ofBase) + 1, 0, " + last + ");");
		} else {
			lines.add("\tint ofNear = int(mod(ofBase, " + depth + "));");
			lines.add("\tint ofFar = int(mod(ofBase + 1.0, " + depth + "));");
		}

		lines.add("\tvec2 ofTile = vec2(" + whole(atlas.tileStride()) + ", " + whole(atlas.tileHeight())
				+ ");");
		lines.add("\tvec2 ofSize = vec2(" + whole(atlas.atlasWidth()) + ", " + whole(atlas.atlasHeight())
				+ ");");
		lines.add("\tvec2 ofA = (vec2(ofNear % " + tiles + ", ofNear / " + tiles
				+ ") * ofTile + ofIn) / ofSize;");
		lines.add("\tvec2 ofB = (vec2(ofFar % " + tiles + ", ofFar / " + tiles
				+ ") * ofTile + ofIn) / ofSize;");
		lines.add("\treturn mix(texture(ofMap, ofA), texture(ofMap, ofB), clamp(ofZ - ofBase, 0.0, 1.0));");
		lines.add("}");

		return lines;
	}

	/** An integer as a GLSL float literal, spelled by hand so that no locale can put a comma in it. */
	private static String whole(int value) {
		return value + ".0";
	}

	/**
	 * Records every storage block the unit declares, so a {@code bufferObject} can be bound to it.
	 * <p>
	 * Named rather than rewritten because the game never looks for one.
	 * {@code IntermediaryShaderModule.createFromSpirv} lists the module's uniform buffers and its
	 * sampled images and nothing else, so a storage block is appended afterwards and remapped with
	 * the rest. Complementary Ultra writes {@code blockDataBuffer} at binding 0 for world-space
	 * reflections; without that name the shadow program is refused and voxel lighting never writes.
	 * <p>
	 * Read on the shape and not on the word alone: {@code buffer} followed by a name and an opening
	 * brace is an interface block and can be nothing else, so no brace depth has to be counted, which
	 * this class cannot count anyway. A block in a branch the expander did not take is left out for
	 * the reason a uniform in one is: the compiler will not see it either.
	 */
	private void collectStorageBlocks() {
		int[] lines = this.tokens.lineNumbers();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("buffer") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int name = this.tokens.significantAfter(index);
			if (name < 0 || this.tokens.get(name).kind() != Kind.IDENTIFIER) {
				continue;
			}

			int brace = this.tokens.significantAfter(name);
			if (brace < 0 || !this.tokens.get(brace).operator("{")) {
				continue;
			}

			int binding = layoutBinding(index);
			note(this.tokens.get(name).text(), binding);
			int close = this.tokens.matchingBracket(brace);
			int instance = close < 0 ? -1 : this.tokens.significantAfter(close);
			if (instance >= 0 && this.tokens.get(instance).kind() == Kind.IDENTIFIER) {
				note(this.tokens.get(instance).text(), binding);
			}
		}
	}

	/**
	 * Both spellings of one block, the block name and the instance name, share its binding, and the
	 * first spelling filed keeps it.
	 * <p>
	 * Which of two would win is a question no pack that compiles can ask: two live declarations of
	 * one block name at file scope are a redeclaration, so the only name that can reach here twice
	 * is one block's instance name standing as another block's name, and neither of the two bindings
	 * is then the right one. Iris never has the choice to make at all, binding a buffer by the index
	 * in its layout qualifier and never by the name.
	 */
	private void note(String name, int binding) {
		if (this.storageBlocks.stream().noneMatch(block -> block.name().equals(name))) {
			this.storageBlocks.add(new TranslatedUnit.StorageBlock(name, binding));
		}
	}

	/**
	 * The {@code binding = N} in the layout qualifier that precedes this {@code buffer} token, or
	 * {@code -1} when the pack wrote none. Complementary always writes one; a nameless
	 * {@code bufferObject.N} is matched against it.
	 */
	private int layoutBinding(int bufferIndex) {
		int start = 0;
		for (int scan = bufferIndex - 1; scan >= 0; scan--) {
			if (this.tokens.get(scan).operator(";")) {
				start = scan + 1;
				break;
			}
		}

		for (int scan = start; scan < bufferIndex; scan++) {
			if (!this.tokens.get(scan).identifier("binding")) {
				continue;
			}

			int equals = this.tokens.significantAfter(scan);
			if (equals < 0 || !this.tokens.get(equals).operator("=")) {
				continue;
			}

			int number = this.tokens.significantAfter(equals);
			if (number < 0 || this.tokens.get(number).kind() != Kind.NUMBER) {
				continue;
			}

			try {
				return Integer.parseInt(this.tokens.get(number).text());
			} catch (NumberFormatException ignored) {
				return -1;
			}
		}

		return -1;
	}

	/**
	 * Whether this name is a comparison sampler where it stands. A parameter only is inside its own
	 * function, and Bliss's {@code lib/texFiltering.glsl} is why the question is asked that way: it
	 * names both its plain sampler and its comparison sampler {@code tex}, in two functions one
	 * after the other, and a rewrite of the first would not compile.
	 */
	private boolean comparisonAt(String name, int line) {
		return scoped(this.comparisonSamplers, name, line);
	}

	/** The same question for the comparison samplers that kept their spelling. */
	private boolean hardwareComparisonAt(String name, int line) {
		return scoped(this.hardwareComparisonSamplers, name, line);
	}

	/** Whether one of these names means what the list says it does on this line. */
	private static boolean scoped(Collection<Scoped> names, String name, int line) {
		for (Scoped scoped : names) {
			if (scoped.name().equals(name) && line >= scoped.from() && line <= scoped.to()) {
				return true;
			}
		}

		return false;
	}

	/**
	 * The same sampler type with the comparison taken out of its spelling, or the type as it stands.
	 * {@code sampler2DShadow} becomes {@code sampler2D} and {@code sampler2DArrayShadow} becomes
	 * {@code sampler2DArray}; a name that is not a sampler comes back untouched.
	 */
	private static String withoutComparison(String type) {
		String shape = SamplerTypes.shapeOf(type);
		if (shape == null || !shape.endsWith(SHADOW_SHAPE)) {
			return type;
		}

		return type.substring(0, type.length() - SHADOW_SHAPE.length());
	}

	/**
	 * Records each declarator of one declaration. False if none could be read.
	 *
	 * @param memory the memory qualifiers written in front of the type, kept beside the name
	 *               rather than in the declaration so that the type stays the first word of it.
	 *               Empty for everything but an opaque uniform, which is the only declaration
	 *               these can be written on.
	 */
	private boolean readDeclarators(List<Integer> parts, int from, String type, String memory,
			Map<String, String> target) {
		int cursor = from;
		boolean any = false;

		while (cursor < parts.size()) {
			Token token = this.tokens.get(parts.get(cursor));
			if (token.operator(";")) {
				break;
			}

			if (token.kind() != Kind.IDENTIFIER) {
				return false;
			}

			if (!memory.isEmpty()) {
				this.memoryQualifiers.put(token.text(), memory);
			}

			StringBuilder declaration = new StringBuilder(type).append(' ').append(token.text());
			cursor++;

			while (cursor < parts.size() && this.tokens.get(parts.get(cursor)).operator("[")) {
				int close = this.tokens.matchingBracket(parts.get(cursor));
				if (close < 0) {
					return false;
				}

				while (cursor < parts.size() && parts.get(cursor) <= close) {
					declaration.append(this.tokens.get(parts.get(cursor)).text());
					cursor++;
				}
			}

			// OptiFine tolerates an initialiser on a uniform and GLSL does not, so it is dropped.
			if (cursor < parts.size() && this.tokens.get(parts.get(cursor)).operator("=")) {
				while (cursor < parts.size()) {
					Token skipped = this.tokens.get(parts.get(cursor));
					if (skipped.operator(",") || skipped.operator(";")) {
						break;
					}

					cursor++;
				}
			}

			record(target, token.text(), declaration.toString());
			any = true;

			if (cursor < parts.size() && this.tokens.get(parts.get(cursor)).operator(",")) {
				cursor++;
			}
		}

		return any;
	}

	private void record(Map<String, String> target, String name, String declaration) {
		String existing = target.putIfAbsent(name, declaration);
		if (existing != null && !existing.equals(declaration)) {
			this.conflicts.add(name);
		}
	}


	/**
	 * The answers the header is written from, taken once every pass has run. Nothing here is
	 * written back, which is why {@link Emitter} is handed these rather than this object.
	 */
	private Emitter emitter() {
		return new Emitter(this.stage, this.inputs, this.bound, this.alphaTest, this.engineDefines,
				this.memoryQualifiers, this.used, this.declaredNames, this.synthesized,
				this.readVolumes, this.packOutputs, this.maxFragmentOutput, this.owedOutputs,
				this.splitMatrices, this.splitStructs, this.splitArrays, this.gameTextureMatrix,
				this.gameModelView, this.softRewrites, this.trigCalls, this.hashCalls,
				this.mainWrapped, this.depthEpilogue, this.terrainPrologue, this.distantPrologue,
				this.entityWrapped, this.linesWrapped, this.alphaEpilogue, this.covers,
				wrapsFragment(), this.ordered, this.namesFragDepth, this.makesOverlayColour);
	}

	/**
	 * Names declared under a built-in type once the uniforms have been lifted out of the text. The
	 * outputs moved into the header count too: the body no longer shows them, and a shared block
	 * that carried the same name would end up declaring it twice.
	 */
	private Set<String> declaredUnderAType() {
		Set<String> names = new HashSet<>();
		this.packOutputs.values().forEach(output -> names.add(output.name()));

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER || token.directive() != null) {
				continue;
			}

			int before = this.tokens.significantBefore(index);
			if (before >= 0 && LegacyGlsl.TYPE_NAMES.contains(this.tokens.get(before).text())) {
				names.add(token.text());
			}
		}

		return names;
	}

	/**
	 * Sampler names this stage reads outside their uniform declaration. An include can declare
	 * twenty textures of which the body samples one: those unused names still occupy identifier
	 * tokens at the declaration, and counting every identifier would treat them as used.
	 */
	private Set<String> sampledNames() {
		boolean[] declared = new boolean[this.tokens.size()];
		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("uniform") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			FileScope scope = fileScopeDeclaration(index);
			if (scope == null) {
				continue;
			}

			for (int at = scope.start(); at <= scope.end() && at < declared.length; at++) {
				declared[at] = true;
			}
		}

		Set<String> sampled = new LinkedHashSet<>();
		for (int index = 0; index < this.tokens.size(); index++) {
			if (declared[index]) {
				continue;
			}

			Token token = this.tokens.get(index);
			if (token.kind() == Kind.IDENTIFIER && this.samplers.containsKey(token.text())) {
				sampled.add(token.text());
			}
		}

		return sampled;
	}

	/**
	 * Every name the translated body mentions. Taken from the tokens rather than the text, so a
	 * name that only appears inside a comment does not have a uniform declared for it.
	 */
	private Set<String> usedNames() {
		Set<String> names = new HashSet<>(this.injectedNames);
		for (Token token : this.tokens) {
			if (token.kind() == Kind.IDENTIFIER) {
				names.add(token.text());
			}
		}

		return names;
	}

	private TranslatedUnit.Notes notes() {
		return new TranslatedUnit.Notes(this.maxFragmentOutput + 1, this.dynamicFragData,
				this.conflicts.size(), this.shadowCalls, this.unwrappedShadowCalls,
				this.strippedExtensions, this.depthEpilogue ? 1 : 0, this.alphaEpilogue ? 1 : 0,
				this.covers ? 1 : 0, this.depthLookups,
				this.parameterLookups, this.pinnedLookups, this.unpinnedParameterLookups,
				this.fragCoordZ, this.fragCoordXyz,
				this.fragCoordUnhandled, this.fragDepthWrites, this.fragDepthUnhandled,
				List.copyOf(this.conflicts), comparedSamplers(), hardwareComparedSamplers(),
				List.copyOf(this.storageBlocks),
				this.volumeLookups, this.volumesLeftAlone, this.trigCalls, this.gameTextureMatrix,
				this.gameModelView);
	}

	/**
	 * The comparison samplers this stage is handed from outside, both roads together, which are the
	 * only ones anything binds: one taken as a parameter is a name inside a function and never a
	 * descriptor.
	 */
	private List<String> comparedSamplers() {
		return descriptors(Stream.concat(this.comparisonSamplers.stream(),
				this.hardwareComparisonSamplers.stream()));
	}

	/** The bound ones of the road that kept its spelling, which the binding owes a comparison. */
	private List<String> hardwareComparedSamplers() {
		return descriptors(this.hardwareComparisonSamplers.stream());
	}

	private List<String> descriptors(Stream<Scoped> scoped) {
		return scoped
				.map(Scoped::name)
				.distinct()
				.filter(this.samplers::containsKey)
				.toList();
	}


	/** Adds a memory qualifier to what a declaration carries, in the order the pack wrote it. */
	private static void rememberMemoryQualifier(List<String> memory, Token token) {
		String text = token.text();
		if (token.kind() == Kind.IDENTIFIER && LegacyGlsl.MEMORY_QUALIFIERS.contains(text)
				&& !memory.contains(text)) {
			memory.add(text);
		}
	}

	private static boolean isQualifier(Token token) {
		return token.kind() == Kind.IDENTIFIER
				&& (LegacyGlsl.MEMORY_QUALIFIERS.contains(token.text())
						|| LegacyGlsl.PRECISION_QUALIFIERS.contains(token.text()));
	}
}
