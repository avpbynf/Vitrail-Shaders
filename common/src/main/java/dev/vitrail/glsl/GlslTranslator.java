package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.ProgramNames;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.target.DrawBuffers;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.SamplerTypes;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.pack.texture.CustomStorage;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Turns one flattened pack unit into GLSL a Vulkan compiler will take.
 * <p>
 * The pack dialect is GLSL 120 with OptiFine's additions: fixed function state, {@code varying}
 * and {@code attribute}, texture lookups renamed twenty years ago, and plain uniforms declared
 * loose at file scope, which Vulkan does not allow at all. None of that needs the program to be
 * understood, only read, so the work here is a rewrite over a token stream rather than a compiler.
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
	 * {@link #splitMatrixVaryings} does. Iris is not copied.
 */
public final class GlslTranslator {

	private static final String VERSION = "#version 460 core";

	/** The block name has to be declared to the pipeline by hand later, so it is fixed here. */
	private static final String UNIFORM_BLOCK = "OfGlobals";

	/** The one varying the engine names itself, so the one both stages have to agree about. */
	private static final String FOG_COORD = "of_FogFragCoord";

	/**
	 * Prefix of the vectors a matrix varying is split into. Pack names never start with {@code of_},
	 * so a column cannot collide with a varying the pack already declared.
	 */
	private static final String MATRIX_COLUMN = "of_vmat_";

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
	private static final String ENTITY_COLOR = "entityColor";

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
	private static final List<String> ENTITY_IDS = List.copyOf(LegacyGlsl.ENTITY_UNIFORMS.keySet());

	/**
	 * The game's own overlay image, sixteen by sixteen, under a name no pack writes.
	 * <p>
	 * What the two coordinates mean is the game's: {@code OverlayTexture.pack} puts the white
	 * progress in u and the red flash in v, and the element the mesh carries is that pair.
	 */
	private static final String OVERLAY = LegacyGlsl.OVERLAY_SAMPLER;

	/** The fetch the overlay colour is made from, named so that the pack's own names cannot meet it. */
	private static final String OVERLAY_TEXEL = "ofOverlayTexel";

	private static final String FOG_STRUCT =
			"struct OfFog { vec4 color; float density; float start; float end; float scale; };";

	/**
	 * How many outputs a fragment stage may declare. Not OptiFine's sixteen colour targets, which
	 * is the other question: a pipeline in 26.2 carries eight colour target states and no more,
	 * {@code ColorTargetState.MAX_COLOR_TARGETS}, and its builder holds them in an array of that
	 * length, so a ninth output has nowhere to land.
	 */
	private static final int MAX_FRAGMENT_OUTPUTS = 8;

	/** Names the ascending prologue, which nothing else in a pack is going to be called. */
	private static final String ORDER_OUTPUTS = "ofOrderOutputs";

	/** What the pack's own {@code main} is called once the epilogue has taken the name over. */
	private static final String PACK_MAIN = "ofPackMain";

	/**
	 * The one output this engine adds itself: the depth the pack's geometry left at this pixel, so
	 * that whoever puts the game's own picture into the same target can tell whether anything has
	 * been drawn in front of it since.
	 * <p>
	 * A depth and not a flag, and what it buys is the whole of what a flag could not answer: the
	 * reader compares it with the world's depth as it stands, so a pixel nothing was drawn over
	 * compares equal and belongs to the pack, and a pixel the game drew a feature onto does not.
	 * The pixels the pack never wrote carry a value outside zero to one instead, which every real
	 * depth is in front of, and the reader owes them no test of their own.
	 */
	private static final String COVERAGE = "ofCoverage";

	/** {@code (clipA, clipB, readA, readB)}: how to write a depth, then how to read one. */
	private static final String DEPTH_CONV = "of_DepthConv";

	/** The comparison a {@code sampler2DShadow} would have had the hardware make. */
	private static final String SHADOW_COMPARE = "ofShadowCompare";

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
	private static final String REDUCED_SIN = "ofReducedSin";

	/** See {@link #REDUCED_SIN}. */
	private static final String REDUCED_COS = "ofReducedCos";

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
	private static final String HASH = "ofHash";

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

	/** How far into a sprite a chunk mesh's texture coordinate has to be pulled. */
	private static final String TEX_SHRINK = "of_TexShrink";

	/** The colour a sky pass is modulated by, which the sky prologue folds into of_Color. */
	private static final String PASS_COLOUR = "of_PassColour";

	/** Matches the expander's own ceiling: nothing it produces should ever reach this. */
	private static final int MAX_SOURCE_CHARACTERS = 4_000_000;

	/**
	 * How far a declaration may reach for its semicolon. A pack that leaves one out would
	 * otherwise have the rest of the file scanned once per {@code uniform} it declares, which is
	 * quadratic: forty thousand such lines took twenty-seven seconds.
	 */
	private static final int MAX_STATEMENT_TOKENS = 4096;

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
	private final List<Token> tokens;

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

	/**
	 * The sampler this unit reads {@code centerDepthSmooth} out of, or null where nothing was moved.
	 * The header declares it, because the statement it was taken out of may still declare other
	 * names and cannot carry a second declaration.
	 */
	private String centerDepthTexel;

	/** Storage blocks this unit declares at file scope, by the name the block is written under. */
	private final List<String> storageBlocks = new ArrayList<>();

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

	/** Lookups on a comparison sampler, whichever of the two roads makes the comparison. */
	private int shadowCompares;

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

		this.tokens = new ArrayList<>(GlslLexer.lex(text));
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
		wrapMainForMatrixSplits();
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
				header(block, samplers, varyings, shadowed) + body(shadowed) + "\n", notes(),
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
			return GlslLexer.join(this.tokens);
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
		int[] lines = lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.HASH || !LegacyGlsl.NAMING_DIRECTIVES.contains(token.directive())) {
				continue;
			}

			int name = macroNameAfter(index);
			if (name < 0) {
				continue;
			}

			this.tokens.set(name, this.tokens.get(name).naming());
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

			int before = significantBefore(index);
			if (before < 0 || !LegacyGlsl.TYPE_NAMES.contains(this.tokens.get(before).text())) {
				continue;
			}

			this.declaredNames.add(token.text());
			this.declaredNames.addAll(continuationDeclarators(index));
			if (LegacyGlsl.POST_120_BUILTINS.contains(token.text()) && callOpener(index) >= 0) {
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
		int end = statementEnd(first);
		if (end < 0) {
			return List.of();
		}

		List<String> names = new ArrayList<>();
		int depth = 0;
		boolean expectName = false;

		for (int index = significantAfter(first); index >= 0 && index <= end;
				index = significantAfter(index)) {
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
		int next = significantAfter(name);
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

			blankDirective(index);
		}
	}

	private void rewriteIdentifiers() {
		List<Integer> closings = new ArrayList<>();
		int[] lines = lineNumbers();

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
				replace(index, "of_" + name);
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
				replace(index, fixed);
				continue;
			}

			if (name.equals("gl_VertexID") || name.equals("gl_InstanceID")) {
				replace(index, name.equals("gl_VertexID") ? "gl_VertexIndex" : "gl_InstanceIndex");
				continue;
			}

			if (name.equals("ftransform") && rewriteFtransform(index)) {
				continue;
			}

			String shadow = LegacyGlsl.SHADOW_FUNCTIONS.get(name);
			if (shadow != null && callOpener(index) >= 0) {
				int close = matchingBracket(callOpener(index));
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
					int first = significantAfter(callOpener(index));
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
					} else if (compared || hardware) {
						this.shadowCompares++;
					}

					if (compared) {
						this.softRewrites++;
					}

					// The wrap adds an opening parenthesis, so it has to add a closing one too.
					// Substituting the head alone is what left the prototype with eighty-six
					// units ending in "unexpected SEMICOLON, expecting RIGHT_PAREN".
					inject(index, "vec4(" + (compared ? SHADOW_COMPARE : shadow));
					closings.add(close + 1);
					this.shadowCalls++;
					continue;
				}
			}

			String modern = LegacyGlsl.DEPRECATED_FUNCTIONS.get(name);
			if (modern != null && callOpener(index) >= 0) {
				replace(index, modern);
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
			if ((name.equals("sin") || name.equals("cos")) && callOpener(index) >= 0
					&& !this.declaredNames.contains(name)) {
				this.trigSites++;
				if (reduceTrig) {
					replace(index, name.equals("sin") ? REDUCED_SIN : REDUCED_COS);
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
				replace(index, this.stage == ProgramStage.VERTEX ? "out" : "in");
				continue;
			}

			if (name.equals("attribute")) {
				replace(index, "in");
				continue;
			}

			String reserved = LegacyGlsl.RESERVED_NAMES.get(name);
			if (reserved != null && callOpener(index) < 0) {
				replace(index, reserved);
			}
		}

		// Inserting shifts every index after it, so the last insertion is made first. It also ends
		// every position taken before it: this loop and insertClosings are the only two places that
		// move a token, so anything a later pass still has to know about a token is carried on the
		// token, as Token#macroName is. A position kept across here would be read against somebody
		// else's token, and the reading pass has no way to notice.
		closings.sort(Comparator.reverseOrder());
		for (int at : closings) {
			this.tokens.add(at, new Token(Kind.RAW, ")", null));
		}
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

			int fractOpen = callOpener(index);
			int sine = fractOpen < 0 ? -1 : significantAfter(fractOpen);
			if (sine < 0 || !goldbergSine(this.tokens.get(sine))) {
				continue;
			}

			int sineOpen = callOpener(sine);
			int dot = sineOpen < 0 ? -1 : significantAfter(sineOpen);
			if (dot < 0 || !this.tokens.get(dot).identifier("dot")) {
				continue;
			}

			int dotOpen = callOpener(dot);
			int dotClose = matchingBracket(dotOpen);
			int comma = firstCallComma(dotOpen);
			int argStart = dotOpen < 0 ? -1 : significantAfter(dotOpen);
			int argEnd = comma < 0 ? -1 : significantBefore(comma);
			int sineClose = matchingBracket(sineOpen);
			int times = sineClose < 0 ? -1 : significantAfter(sineClose);
			int scale = times < 0 ? -1 : significantAfter(times);
			int fractClose = matchingBracket(fractOpen);
			if (argStart < 0 || argEnd < argStart || times < 0 || scale < 0 || fractClose < 0
					|| !this.tokens.get(times).operator("*")
					|| !goldbergScale(this.tokens.get(scale))
					|| !literalVector(comma + 1, dotClose - 1)) {
				continue;
			}

			int afterScale = significantAfter(scale);
			if (afterScale != fractClose) {
				continue;
			}

			boolean reduced = this.tokens.get(sine).identifier(REDUCED_SIN);
			String argument = tokenText(argStart, argEnd);
			blankRange(index, fractClose);
			inject(index, HASH + "(" + argument + ")");
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
		int close = matchingBracket(open);
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
		int open = significantAfter(index);
		if (open < 0 || !this.tokens.get(open).operator("[")) {
			return false;
		}

		int unit = significantAfter(open);
		int close = matchingBracket(open);
		if (unit < 0 || close < 0 || significantAfter(unit) != close
				|| !this.tokens.get(unit).text().equals("0")) {
			return false;
		}

		inject(index, LegacyGlsl.GAME_TEXTURE_MATRIX);
		blank(open);
		blank(unit);
		blank(close);
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
		int open = significantAfter(index);
		if (open < 0 || !this.tokens.get(open).operator("[")) {
			return false;
		}

		int unit = significantAfter(open);
		int close = matchingBracket(open);
		if (unit < 0 || close < 0 || significantAfter(unit) != close
				|| !(this.tokens.get(unit).text().equals("0")
						|| this.tokens.get(unit).text().equals("1")
						|| this.tokens.get(unit).text().equals("2"))) {
			return false;
		}

		inject(index, "mat4(1.0)");
		blank(open);
		blank(unit);
		blank(close);

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
			inject(index, DRAW_MODEL_VIEW);
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

			inject(index, DRAW_MODEL_VIEW);
		} else if (name.equals("gl_ModelViewProjectionMatrix")) {
			inject(index, "(of_ProjectionMatrix * " + DRAW_MODEL_VIEW + ")");
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
		int before = significantBefore(index);

		return before >= 0 && LegacyGlsl.TYPE_NAMES.contains(this.tokens.get(before).text());
	}

	private boolean rewriteFtransform(int index) {
		int open = callOpener(index);
		int close = matchingBracket(open);
		if (close < 0 || significantAfter(open) != close) {
			return false;
		}

		// A pass drawn from the camera takes the draw's model view here too, and it has to: this is
		// the spelling the corpus really uses for a glint, and a pack writing ftransform() would
		// otherwise get the pass's matrix back through the door rewriteGameModelView closed.
		if (LegacyGlsl.readsDrawModelView(this.program)) {
			inject(index, "(of_ProjectionMatrix * " + DRAW_MODEL_VIEW + " * of_Vertex)");
			this.injectedNames.add("of_ProjectionMatrix");
			this.injectedNames.add(LegacyGlsl.CAMERA_BOB);
			this.injectedNames.add(LegacyGlsl.GAME_MODEL_VIEW);
			this.gameModelView++;
		} else {
			inject(index, "(of_ModelViewProjectionMatrix * of_Vertex)");
			this.injectedNames.add("of_ModelViewProjectionMatrix");
		}

		this.injectedNames.add("of_Vertex");
		blank(open);
		blank(close);

		return true;
	}

	/** One closing the wrap owes, put in once the scan that found it has finished reading. */
	private record Closing(int at, String text, String directive) {
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
		int[] lines = lineNumbers();

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

		insertClosings(closings);
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

		int open = callOpener(index);
		int first = open < 0 ? -1 : significantAfter(open);
		if (first < 0 || this.tokens.get(first).kind() != Kind.IDENTIFIER) {
			return false;
		}

		String argument = this.tokens.get(first).text();
		if (hardwareComparisonAt(argument, line)) {
			this.shadowCompares++;

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
		replace(index, SHADOW_COMPARE);
		this.shadowCompares++;
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
		int open = callOpener(index);
		int first = open < 0 ? -1 : significantAfter(open);
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
	 * {@code gl_FragCoord.z} is the window depth the rasteriser produced, so it is in the target's
	 * convention and asks the same question a depth lookup does, with the same answer.
	 * <p>
	 * The component is demanded rather than the vector wrapped. The corpus reads {@code .xy} three
	 * hundred and forty six times, {@code .x} a hundred and forty eight and {@code .y} a hundred
	 * and forty three, none of which is a depth, and wrapping the vector would quietly corrupt
	 * every one of them.
	 */
	private void convertFragCoord(int index) {
		int dot = significantAfter(index);
		int swizzle = dot >= 0 && this.tokens.get(dot).operator(".") ? significantAfter(dot) : -1;
		if (swizzle < 0 || this.tokens.get(swizzle).kind() != Kind.IDENTIFIER) {
			// Reached some other way, a subscript or a whole vector handed to a function. Nothing
			// in the corpus does it, and a pack that starts has to be visible rather than wrong.
			this.fragCoordUnhandled++;
			return;
		}

		String field = this.tokens.get(swizzle).text();
		if (field.equals("z")) {
			inject(index, "(" + DEPTH_CONV + ".z * gl_FragCoord.z + " + DEPTH_CONV + ".w)");
			this.fragCoordZ++;
		} else if (field.equals("xyz")) {
			// Two screen components that convert to nothing and one depth that does not, so the
			// vector has to be rebuilt. Seven sites, all in gbuffers.
			inject(index, "vec3(gl_FragCoord.xy, " + DEPTH_CONV + ".z * gl_FragCoord.z + "
					+ DEPTH_CONV + ".w)");
			this.fragCoordXyz++;
		} else {
			if (namesDepth(field)) {
				this.fragCoordUnhandled++;
			}

			return;
		}

		blank(dot);
		blank(swizzle);
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

		int eq = significantAfter(index);
		int after = significantAfter(eq);
		boolean assignment = eq >= 0 && this.tokens.get(eq).operator("=")
				&& (after < 0 || !this.tokens.get(after).operator("="));
		int end = assignment ? statementEnd(eq) : -1;
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
		inject(eq, "= (" + DEPTH_CONV + ".z * (");
		closings.add(new Closing(end, ") + " + DEPTH_CONV + ".w)", directive));
		takeDepthConv();
		this.fragDepthWrites++;
	}

	/**
	 * Inserting shifts every index after it, so the last insertion is made first.
	 * <p>
	 * This and the closing loop at the end of {@link #rewriteIdentifiers} are the only two places
	 * that move a token, and so the only two that can make a position stale. What a later pass has
	 * to know about a token is carried on the token for that reason, as {@link Token#macroName()}
	 * is: a position kept across either of them would be read against somebody else's token, and
	 * nothing downstream can tell.
	 */
	private void insertClosings(List<Closing> closings) {
		closings.sort(Comparator.comparingInt(Closing::at).reversed());
		for (Closing closing : closings) {
			this.tokens.add(closing.at(), new Token(Kind.RAW, closing.text(), closing.directive()));
		}
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

			int end = statementEnd(index);
			if (end >= 0 && nonConstantInitialiser(index, end)) {
				blank(index);
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
				blank(index);
				continue;
			}

			if (!token.identifier("precision")) {
				continue;
			}

			int qualifier = significantAfter(index);
			if (qualifier >= 0 && LegacyGlsl.PRECISION_QUALIFIERS.contains(this.tokens.get(qualifier).text())) {
				int end = statementEnd(index);
				if (end >= 0) {
					blankRange(index, end);
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
				inject(index, "ofFragData0");
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

			int open = significantAfter(index);
			int number = significantAfter(open);
			inject(index, "ofFragData" + slot);
			blank(open);
			blank(number);
			blank(significantAfter(number));
			this.maxFragmentOutput = Math.max(this.maxFragmentOutput, slot);
		}

		if (fragColor) {
			this.maxFragmentOutput = Math.max(this.maxFragmentOutput, 0);
		}
	}

	/** The subscript of {@code gl_FragData[n]} when it is a literal in range, otherwise -1. */
	private int literalIndexAfter(int index) {
		int open = significantAfter(index);
		if (open < 0 || !this.tokens.get(open).operator("[")) {
			return -1;
		}

		int number = significantAfter(open);
		if (number < 0 || this.tokens.get(number).kind() != Kind.NUMBER) {
			return -1;
		}

		int close = significantAfter(number);
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
	private record Output(String name, String type) {
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

		int[] lines = lineNumbers();
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
		int before = significantBefore(index);
		if (before < 0) {
			return true;
		}

		Token token = this.tokens.get(before);

		return token.operator(";") || token.operator("}") || token.operator(")");
	}

	private void liftOutput(int keyword) {
		int start = statementStart(keyword);
		int end = statementEnd(keyword);
		if (start < 0 || end < 0) {
			return;
		}

		List<Integer> parts = significantRange(start, end);
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
			blankRange(start, end);
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
			inject(brace, "{ " + ORDER_OUTPUTS + "();");
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
	 * {@link #matchingBracket} cannot close a brace and, more to the point, counts operators without
	 * looking at whether their line is live: {@link #liftUniforms} refuses to count brace depth for
	 * that very reason, a pack opening a brace in one branch of an {@code #if} and closing it in
	 * another. A misplaced closing brace would put the epilogue in the middle of the code. Wrapping
	 * also survives an early {@code return} from {@code main}, which no vertex stage in the corpus
	 * does, and the header is already where code of ours is written.
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
				replace(this.packMainName, PACK_MAIN);
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
		if (!terrain && !distant && !depth && !overlay) {
			return;
		}

		int name = mainName();
		if (name < 0) {
			return;
		}

		replace(name, PACK_MAIN);
		this.terrainPrologue = terrain;
		this.distantPrologue = distant;
		this.entityWrapped = overlay;
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
	private record SplitMatrix(String name, String matrixType, String columnType, int columns,
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
	 * are left alone; no pack of the corpus writes one, and AstraLex's four matrices are not arrays.
	 */
	private void splitMatrixVaryings() {
		if (this.stage == ProgramStage.COMPUTE) {
			return;
		}

		int[] lines = lineNumbers();
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

			blankRange(declared.start(), declared.end());

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
	private void wrapMainForMatrixSplits() {
		if (this.mainWrapped || this.splitMatrices.isEmpty()) {
			return;
		}

		int name = mainName();
		if (name < 0) {
			name = mainNameAfterOutputOrder();
		}

		if (name < 0) {
			return;
		}

		replace(name, PACK_MAIN);
		this.mainWrapped = true;
	}

	/**
	 * The {@code main} whose opening brace {@link #orderFragmentOutputs} already replaced, or -1.
	 * Same walk as {@link #mainName}, except a {@code RAW} token that still opens with a brace
	 * counts as the body.
	 */
	private int mainNameAfterOutputOrder() {
		int[] lines = lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("main") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int close = matchingBracket(callOpener(index));
			int brace = significantAfter(close);
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

	private static String matrixColumnName(String matrix, int column) {
		return MATRIX_COLUMN + matrix + "_" + column;
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

		replace(name, PACK_MAIN);
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

		int[] lines = lineNumbers();
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
		blankRange(declared.start(), declared.end());
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

		int[] lines = lineNumbers();
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
				blankRange(declared.start(), declared.end());
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
				// already had and there through a module that does not build, and no pack of the
				// corpus declares a varying array. The declarator carrying brackets is what tells
				// one, the type name alone never doing so.
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
			for (int index : significantRange(scope.start(), scope.end())) {
				String text = this.tokens.get(index).text();
				if (!text.equals("out") && !LegacyGlsl.INTERPOLATION_QUALIFIERS.contains(text)) {
					break;
				}

				blank(index);
			}

			this.declaredOutputs.removeAll(scope.names());
		}
	}

	/**
	 * An owed varying written as an output declaration, with {@code out} where GLSL wants it: after
	 * the interpolation qualifier and before the type. Writing {@code out flat float} rather than
	 * {@code flat out float} is a syntax error, so the two cannot simply be concatenated.
	 *
	 * @param qualified the qualifier and the type, {@code flat float} or {@code vec3}
	 */
	private static String outDeclaration(String name, String qualified) {
		int type = qualified.lastIndexOf(' ');

		return type < 0
				? "out " + qualified + " " + name + ";"
				: qualified.substring(0, type) + " out " + qualified.substring(type + 1) + " " + name + ";";
	}

	/**
	 * What the stage assigns an owed varying, which is the type's zero, exactly as Iris writes it at
	 * {@code CompatibilityTransformer.java:494} out of {@code getInitializer:351-359}.
	 * <p>
	 * {@code type(0)} spells the zero of every type that can reach here. {@link LegacyGlsl#TYPE_NAMES}
	 * holds the scalars, the vectors and the matrices, and the constructor of each takes a single
	 * zero; {@code bool} and {@code bvec} are in it too and take one just as well. {@code void} is
	 * the one member no constructor answers for, and a varying cannot be declared under it.
	 */
	private static String initialiser(String name, String qualified) {
		int type = qualified.lastIndexOf(' ');

		return name + " = " + qualified.substring(type + 1) + "(0);";
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
		int[] lines = lineNumbers();
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

			int before = significantBefore(index);
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
					int from = statementStart(index);
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
		int end = statementEnd(keyword);
		int start = end < 0 ? -1 : statementStart(keyword);
		if (start < 0) {
			return null;
		}

		List<Integer> parts = significantRange(start, end);
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
				|| LegacyGlsl.INTERPOLATION_QUALIFIERS.contains(this.tokens.get(parts.get(cursor)).text()))) {
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
		if (!LegacyGlsl.TYPE_NAMES.contains(type)) {
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

		return name < 0 ? -1 : significantAfter(matchingBracket(callOpener(name)));
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
		int[] lines = lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("main") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int close = matchingBracket(callOpener(index));
			int brace = significantAfter(close);
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
		int[] lines = lineNumbers();

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

	/**
	 * Vulkan GLSL requires a format on a storage image, unless the image is write-only. Iris's GL
	 * bind supplies the format at bind time, so Complementary writes
	 * {@code writeonly uniform uimage3D voxel_img} with none, and BSL writes
	 * {@code writeonly uniform image3D lightimg0} the same way. The format comes off the
	 * {@code image.} directive and is written here, in the header: the body declaration has
	 * already been lifted, so a layout qualifier inserted into the token stream would qualify a
	 * statement that is no longer in the shader.
	 * <p>
	 * The pack's own memory qualifiers are written back for the same reason, and they are what
	 * carries a declaration whose format we never learn: a pack switches its images off with the
	 * setting that switches off the program reading them, and then the {@code image.} lines go
	 * with it while the {@code writeonly} on the declaration stays. Dropping it turned a legal
	 * declaration into one shaderc refuses.
	 */
	private String declareOpaque(TranslatedUnit.Uniform sampler) {
		String memory = this.memoryQualifiers.getOrDefault(sampler.name(), "");
		String tail = (memory.isEmpty() ? "" : memory + " ") + "uniform " + sampler.declaration()
				+ ";";
		if (!isImageType(sampler.type())) {
			return tail;
		}

		return CustomImages.layoutFormat(sampler.name())
				.map(format -> "layout(" + format + ") " + tail)
				.orElse(tail);
	}

	private static boolean isImageType(String type) {
		return type.startsWith("image") || type.startsWith("iimage") || type.startsWith("uimage");
	}

	private void liftOne(int keyword) {
		int end = statementEnd(keyword);
		if (end < 0) {
			// Either a uniform block, which is already legal, or a declaration with no semicolon,
			// which is a pack problem and not ours to paper over.
			return;
		}

		int start = statementStart(keyword);
		if (start < 0) {
			return;
		}

		List<Integer> parts = significantRange(start, end);
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

		blankRange(start, end);
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
		int[] lines = lineNumbers();
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
			int last = depth > 0 ? functionEnd(parameters) : this.tokens.size() - 1;
			List<Scoped> introduced = new ArrayList<>();
			boolean bindable = !arithmetic;
			for (int scan = significantAfter(index); scan >= 0; scan = significantAfter(scan)) {
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
				replace(index, plain);
				this.comparisonSamplers.addAll(introduced);
			}
		}

		if (!this.hardwareComparisonSamplers.isEmpty()) {
			this.hardwareComparisonSamplers.addAll(parameterNames);

			return;
		}

		for (int index : parameterTypes) {
			replace(index, withoutComparison(this.tokens.get(index).text()));
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
		int[] lines = lineNumbers();
		int depth = 0;
		int parameters = -1;

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

			// Inside a parenthesis and nowhere else, which is what tells a parameter from a
			// declaration: a declaration lists names until the semicolon and would claim the whole
			// body of whatever follows it.
			if (depth <= 0 || parameters < 0 || token.directive() != null
					|| token.kind() != Kind.IDENTIFIER || !LegacyGlsl.isOpaqueType(token.text())) {
				continue;
			}

			int name = significantAfter(index);
			if (name >= 0 && this.tokens.get(name).kind() == Kind.IDENTIFIER) {
				this.samplerParameters.add(new Scoped(this.tokens.get(name).text(), lines[index],
						lines[functionEnd(parameters)]));
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

		int[] lines = lineNumbers();
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

		String texel = SamplerPlan.centerDepth();
		members.forEach(this::detachMember);

		this.centerDepthTexel = texel;
		this.samplers.put(texel, SAMPLER_2D + " " + texel);
		reads.forEach(read -> inject(read, LOOKUP + "(" + texel + ", vec2(0.5)).r"));
	}

	/**
	 * Whether the pass this file is the entry point of is one this engine draws over a quad, which is
	 * where Iris makes the value available, less the shadow composites it never binds and this engine
	 * never runs.
	 */
	private boolean movesCenterDepth() {
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
			int before = significantBefore(cursor);
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
			int previous = significantBefore(before);
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

		int cursor = significantBefore(type);
		while (cursor >= 0 && isQualifier(this.tokens.get(cursor))) {
			cursor = significantBefore(cursor);
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
		int before = significantBefore(name);
		if (before >= 0 && this.tokens.get(before).operator(",")) {
			blank(before);
			blank(name);

			return;
		}

		int after = significantAfter(name);
		if (after >= 0 && this.tokens.get(after).operator(",")) {
			blank(name);
			blank(after);

			return;
		}

		int start = statementStart(name);
		int end = statementEnd(name);
		if (start < 0 || end < 0) {
			// A declaration with no semicolon, which is a pack problem and not ours to paper over.
			// Left whole rather than half emptied, the way the lifting leaves it.
			return;
		}

		blankRange(start, end);
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

		int[] lines = lineNumbers();
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
			replace(significantBefore(declaration), "sampler2D");
			replace(declaration, forged);
		}

		lookups.forEach((argument, callee) -> {
			replace(callee, VOLUME_LOOKUP + name);
			replace(argument, forged);
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
		int type = significantBefore(index);
		if (type < 0 || this.tokens.get(type).kind() != Kind.IDENTIFIER
				|| !"3D".equals(SamplerTypes.shapeOf(this.tokens.get(type).text()))) {
			return false;
		}

		int cursor = significantBefore(type);
		while (cursor >= 0 && isQualifier(this.tokens.get(cursor))) {
			cursor = significantBefore(cursor);
		}

		return cursor >= 0 && this.tokens.get(cursor).identifier("uniform");
	}

	/**
	 * The {@code texture} this name is the first argument of, or -1 when it is reached any other
	 * way. The argument count is checked as well as the name: {@code texture(s, p, bias)} compiles
	 * and means something else, and the helper takes two.
	 */
	private int plainLookup(int index) {
		int open = significantBefore(index);
		if (open < 0 || !this.tokens.get(open).operator("(")) {
			return -1;
		}

		int callee = significantBefore(open);
		if (callee < 0 || !this.tokens.get(callee).identifier(LOOKUP)) {
			return -1;
		}

		int close = matchingBracket(open);

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
	private static List<String> volumeHelper(String name, VolumeAtlas atlas) {
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
		int[] lines = lineNumbers();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("buffer") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int name = significantAfter(index);
			if (name < 0 || this.tokens.get(name).kind() != Kind.IDENTIFIER) {
				continue;
			}

			int brace = significantAfter(name);
			if (brace < 0 || !this.tokens.get(brace).operator("{")) {
				continue;
			}

			String block = this.tokens.get(name).text();
			int binding = layoutBinding(index);
			if (!this.storageBlocks.contains(block)) {
				this.storageBlocks.add(block);
			}

			CustomStorage.declare(block, binding);
			int close = matchingBracket(brace);
			int instance = close < 0 ? -1 : significantAfter(close);
			if (instance >= 0 && this.tokens.get(instance).kind() == Kind.IDENTIFIER) {
				String instanceName = this.tokens.get(instance).text();
				if (!this.storageBlocks.contains(instanceName)) {
					this.storageBlocks.add(instanceName);
				}

				CustomStorage.declare(instanceName, binding);
			}
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

			int equals = significantAfter(scan);
			if (equals < 0 || !this.tokens.get(equals).operator("=")) {
				continue;
			}

			int number = significantAfter(equals);
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
	 * The last token of the function a parameter list opens, which is the brace that closes the
	 * body, or the parenthesis itself when the function is only declared.
	 */
	private int functionEnd(int parameters) {
		int close = matchingBracket(parameters);
		int brace = close < 0 ? -1 : significantAfter(close);
		if (brace < 0 || !this.tokens.get(brace).operator("{")) {
			return close < 0 ? this.tokens.size() - 1 : close;
		}

		int end = matchingBracket(brace);

		return end < 0 ? this.tokens.size() - 1 : end;
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
	private static boolean scoped(List<Scoped> names, String name, int line) {
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
				int close = matchingBracket(parts.get(cursor));
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


	private String header(List<TranslatedUnit.Uniform> block, List<TranslatedUnit.Uniform> samplers,
			Set<String> varyings, Set<String> shadowed) {
		List<String> lines = new ArrayList<>();
		lines.add(VERSION);

		for (Map.Entry<String, String> define : this.engineDefines.entrySet()) {
			lines.add(define.getValue().isEmpty()
					? "#define " + define.getKey()
					: "#define " + define.getKey() + " " + define.getValue());
		}

		// The block is written in the order it was handed over, and nothing sorts it: a std140
		// buffer is filled by walking the members, so a different order is a different buffer.
		if (block.stream().anyMatch(member -> member.name().equals("of_Fog"))) {
			lines.add(FOG_STRUCT);
		}

		if (!block.isEmpty()) {
			lines.add("layout(std140) uniform " + UNIFORM_BLOCK + " {");
			for (TranslatedUnit.Uniform member : block) {
				lines.add("\t" + member.declaration() + ";");
			}

			lines.add("};");
		}

		// The game's own block, beside ours and never merged into it: this one is filled by the game
		// once per draw and ours is written once per run, which is the whole reason a matrix that
		// changes with the draw is read from over there.
		if (this.gameTextureMatrix > 0 || this.gameModelView > 0) {
			lines.addAll(LegacyGlsl.GAME_TRANSFORMS_BLOCK);
		}

		// Written here, in the order the program handed over, rather than left in the body. The
		// compiler numbers a sampler by the order it first meets the name, and MoltenVK turns that
		// number into a Metal slot that only accepts 0 through 15. Sampled names come first.
		for (TranslatedUnit.Uniform sampler : samplers) {
			lines.add(declareOpaque(sampler));
		}

		// Attributes stay a matter for the stage that has them. Only a vertex shader has inputs
		// from a buffer, so there is no other side to agree with.
		if (this.stage == ProgramStage.VERTEX) {
			switch (this.inputs) {
				case FULLSCREEN -> {
					lines.addAll(LegacyGlsl.FULLSCREEN_ATTRIBUTES);
					lines.addAll(VertexPrologue.tail(this.used, this.synthesized));
				}
				case TERRAIN, TERRAIN_SEPARATE_AO -> lines.addAll(SodiumVertex.prologue(this.bound,
						this.used, this.synthesized, this.inputs.separateAo()));
				case ENTITY, ENTITY_FULLBRIGHT -> lines.addAll(
						EntityVertex.prologue(this.used, this.synthesized, this.inputs.fullbright()));
				case GLINT -> lines.addAll(GlintVertex.prologue(this.used, this.synthesized));
				case CRUMBLING -> lines.addAll(CrumblingVertex.prologue(this.used, this.synthesized));
				case PARTICLE -> lines.addAll(ParticleVertex.prologue(this.used, this.synthesized));
				case SKY -> lines.addAll(SkyVertex.prologue(this.bound, this.used, this.synthesized));
				case CLOUDS -> lines.addAll(CloudVertex.prologue(this.used, this.synthesized));
				case DISTANT -> lines.addAll(
						DistantVertex.prologue(this.bound, this.used, this.synthesized));
				case WORLD -> {
					for (Map.Entry<String, String> attribute : LegacyGlsl.FIXED_ATTRIBUTES.entrySet()) {
						if (this.used.contains(attribute.getKey())) {
							lines.add("in " + attribute.getValue() + ";");
						}
					}

					for (Map.Entry<String, String> attribute : LegacyGlsl.ENGINE_ATTRIBUTES.entrySet()) {
						if (this.used.contains(attribute.getKey())
								&& !this.declaredNames.contains(attribute.getKey())) {
							lines.add("in " + attribute.getValue() + ";");
						}
					}
				}
			}
		}

		// The four taps a hardware comparison blends, for the lookups the road decision sent here:
		// GpuSampler carries no compare mode, so when the comparison cannot ride the binding, what
		// the hardware does is done in arithmetic instead. The hardware compares each of the four
		// texels a bilinear filter would have taken and blends the four RESULTS, so that is what
		// this does: textureGather brings the four back whatever filter is bound, the comparison is
		// made on each, and the blend uses the weights of the filter. Comparing an already filtered
		// depth is the one thing it must not do, and that is the difference: the average of four
		// depths is a surface standing nowhere, while the average of four comparisons is a fraction
		// of the light, which is the whole point of the thing. Iris binds GL_LINEAR plus
		// GL_COMPARE_REF_TO_TEXTURE for it, in ShadowRenderTargets.getSamplerFor, and the hardware
		// road binds the same pair through the descriptor instead of coming here.
		//
		// Not conditioned on shadowHardwareFiltering, and nothing here could condition it: the
		// header is written per stage, before any of the pack's directives are folded. It costs
		// nothing on this corpus. Without that directive Iris leaves the comparison mode off and
		// what a sampler2DShadow reads is undefined, so the declaration this translation found is
		// the only live meaning the directive has; and the harder shape the pair can ask for,
		// NEAREST_HW, needs shadowtexNearest, which no pack of the corpus writes.
		//
		// The sense is LEQUAL, which is what OptiFine sets on a shadow texture and therefore what
		// every pack is written against: one where the fragment is no further from the light than
		// what the map holds, and the map holds the forward window where nearer is smaller.
		//
		// The level of detail is dropped, which is what a comparison sampler with no mipmaps would
		// have done with it anyway: nothing ever fills a chain on the shadow map.
		if (this.softRewrites > 0) {
			lines.add("float " + SHADOW_COMPARE + "(sampler2D ofMap, vec3 ofAt) {"
					+ " vec4 ofTests = step(vec4(ofAt.z), textureGather(ofMap, ofAt.xy, 0));"
					+ " vec2 ofPart = fract(ofAt.xy * vec2(textureSize(ofMap, 0)) - 0.5);"
					+ " return mix(mix(ofTests.w, ofTests.z, ofPart.x),"
					+ " mix(ofTests.x, ofTests.y, ofPart.x), ofPart.y); }");
			lines.add("float " + SHADOW_COMPARE + "(sampler2D ofMap, vec3 ofAt, float ofLod) {"
					+ " return " + SHADOW_COMPARE + "(ofMap, ofAt); }");
		}

		// One overload per shape the builtins take, and no driver sine anywhere in it. The turn
		// count is taken out through two constants whose sum carries two pi to thirty-three bits,
		// so the residue keeps its low bits where a single fp32 two-pi would shed them; the residue
		// is folded to a quarter turn and fed to the odd polynomial. Measured on the hash's own
		// yardstick: a single-constant reduction leaves a field the uniformity test rejects at 427
		// where white noise scores 15, and this form scores 11 to 14, alongside the reference.
		if (this.trigCalls > 0) {
			for (String shape : new String[] {"float", "vec2", "vec3", "vec4"}) {
				lines.add(shape + " " + REDUCED_SIN + "(" + shape + " ofX) {"
						+ " " + shape + " ofK = floor(ofX * 0.15915494);"
						+ " " + shape + " ofR = ofX - ofK * 6.28125 - ofK * 1.9353072e-3;"
						+ " ofR -= 6.2831855 * step(3.1415927, ofR);"
						+ " " + shape + " ofS = sign(ofR);"
						+ " " + shape + " ofA = 1.5707964 - abs(abs(ofR) - 1.5707964);"
						+ " " + shape + " ofZ = ofA * ofA;"
						+ " return ofS * ofA * (1.0 + ofZ * (-1.6666654611e-1"
						+ " + ofZ * (8.3321608736e-3 + ofZ * (-1.9515295891e-4)))); }");
				lines.add(shape + " " + REDUCED_COS + "(" + shape + " ofX) {"
						+ " return " + REDUCED_SIN + "(ofX + 1.5707964); }");
			}
		}

		// One overload per vector the idiom hashes. The bits are hashed rather than the sine of a
		// huge argument, which is the whole of rewriteGoldbergHash.
		if (this.hashCalls > 0) {
			lines.add("float " + HASH + "(vec2 ofP) {"
					+ " uvec2 ofV = floatBitsToUint(ofP);"
					+ " uint ofN = (ofV.x * 1597334677u) ^ (ofV.y * 3812015801u);"
					+ " ofN ^= ofN >> 16u;"
					+ " ofN *= 2246822519u;"
					+ " ofN ^= ofN >> 13u;"
					+ " ofN *= 3266489917u;"
					+ " ofN ^= ofN >> 16u;"
					+ " return float(ofN) * 2.3283064365386963e-10; }");
			lines.add("float " + HASH + "(vec3 ofP) {"
					+ " uvec3 ofV = floatBitsToUint(ofP);"
					+ " uint ofN = ofV.x ^ (ofV.y * 1597334677u) ^ (ofV.z * 3812015801u);"
					+ " ofN ^= ofN >> 16u;"
					+ " ofN *= 2246822519u;"
					+ " ofN ^= ofN >> 13u;"
					+ " ofN *= 3266489917u;"
					+ " ofN ^= ofN >> 16u;"
					+ " return float(ofN) * 2.3283064365386963e-10; }");
		}

		// Only where a lookup was moved. A stage carrying the declaration and never reading it, which
		// is most of them, has its declaration flattened and owes no helper.
		this.readVolumes.forEach((name, atlas) -> lines.addAll(volumeHelper(name, atlas)));

		// Declared on both sides or on neither, whether this stage reads it or not. A varying the
		// vertex writes and the fragment never mentions is accepted in silence and shifts the
		// location of everything declared after it.
		if (varyings.contains(FOG_COORD)) {
			lines.add((this.stage == ProgramStage.VERTEX ? "out" : "in") + " float " + FOG_COORD + ";");
		}

		// The same rule for the overlay colour, and it arrives here by the same road: the union says
		// yes, so both sides declare it whichever of them reads it. No interpolation qualifier, which
		// is Iris's answer too: every vertex of one model part carries the same overlay coordinate,
		// so what is interpolated between them is one value.
		if (varyings.contains(ENTITY_COLOR)) {
			lines.add((this.stage == ProgramStage.VERTEX ? "out" : "in") + " vec4 " + ENTITY_COLOR + ";");
		}

		// And the same rule again for the three identifiers, with the qualifier the language demands
		// rather than one chosen: an integer varying may not be interpolated, so flat is not a
		// decision here. Iris writes flat on its own ivec3 for the same reason
		// (EntityPatcher.java:159).
		for (String identifier : ENTITY_IDS) {
			if (varyings.contains(identifier)) {
				lines.add("flat " + (this.stage == ProgramStage.VERTEX ? "out" : "in") + " int "
						+ identifier + ";");
			}
		}

		for (SplitMatrix split : this.splitMatrices) {
			lines.add(split.matrixType() + " " + split.name() + ";");
			String storage = split.input() ? "in" : "out";
			String qualified = split.qualifier().isEmpty()
					? storage + " " + split.columnType()
					: split.qualifier() + " " + storage + " " + split.columnType();
			for (int column = 0; column < split.columns(); column++) {
				lines.add(qualified + " " + matrixColumnName(split.name(), column) + ";");
			}
		}

		// From zero up with no gaps, because a location the game finds nothing declared at is not
		// left empty: it renumbers what is there and everything above the gap moves down one.
		for (int slot = 0; slot <= this.maxFragmentOutput; slot++) {
			Output output = this.packOutputs.get(slot);
			lines.add("layout(location = " + slot + ") out "
					+ (output == null ? "vec4" : output.type()) + " " + outputName(slot, shadowed) + ";");
		}

		// Above everything the pack declared, dead branches included, because the rank is what
		// becomes the location and the ranks below this one are already spoken for.
		if (this.covers) {
			lines.add("layout(location = " + (this.maxFragmentOutput + 1) + ") out float "
					+ COVERAGE + ";");
		}

		if (this.ordered) {
			StringBuilder order = new StringBuilder("void " + ORDER_OUTPUTS + "() {");
			for (int slot = 0; slot <= this.maxFragmentOutput; slot++) {
				order.append(' ').append(outputName(slot, shadowed)).append(';');
			}

			if (this.covers) {
				order.append(' ').append(COVERAGE).append(';');
			}

			lines.add(order.append(" }").toString());
		}

		// The same rule as the varying above, read from the other end: a varying the NEXT stage
		// declares and this one never wrote is not a silence but a refusal, so it is declared here
		// rather than taken out there. The qualifier travels with it, the two sides having to agree
		// on that as well.
		//
		// BELOW the colour outputs and below the ascending function, for the reason the next block
		// gives about itself: on a stage that has colour outputs, a plain out declaration met first
		// would take location nought from the one the game writes back. Only the last stage of a
		// pipeline has those and only a stage that is not last is ever owed anything, so the two do
		// not meet today. They are ordered anyway rather than left to that argument holding.
		this.owedOutputs.forEach((name, qualified) -> lines.add(outDeclaration(name, qualified)));

		// Below the block, since it reads it, and below the outputs and the ascending function for a
		// reason that decides the picture: a wrapper standing above them would be the first place the
		// compiler met an output name, and the rank it hands out there is the location the game
		// writes back. It has to be the ascending function that gets there first, so this goes last.
		// The pack's body is concatenated after the header, so its own main is only a name here and
		// has to be declared before it can be called.
		if (this.depthEpilogue || this.terrainPrologue || this.distantPrologue || this.entityWrapped
				|| wrapsFragment() || owesInitialisers() || !this.splitMatrices.isEmpty()) {
			lines.add("void " + PACK_MAIN + "();");
			// The mask goes last of all, after the discard: a fragment the alpha test threw away
			// covered nothing, and marking it covered would leave a hole where a leaf was.
			lines.add("void main() { "
					+ (this.terrainPrologue ? SodiumVertex.PROLOGUE + "(); " : "")
					+ (this.distantPrologue ? DistantVertex.PROLOGUE + "(); " : "")
					+ overlayPrologue()
					+ identifierPrologue(varyings)
					+ (wrapsFragment() ? ORDER_OUTPUTS + "(); " : "")
					+ coveragePrologue()
					+ owedPrologue()
					+ matrixPrologue()
					+ PACK_MAIN + "();"
					+ matrixEpilogue()
					+ (this.depthEpilogue ? " gl_Position.z = " + DEPTH_CONV
							+ ".x * gl_Position.z + " + DEPTH_CONV + ".y * gl_Position.w;" : "")
					+ (this.alphaEpilogue
							? " " + this.alphaTest.discard(outputName(0, shadowed) + ".a")
							: "")
					+ (this.covers ? " " + COVERAGE + " = " + writtenDepth() + ";" : "")
					+ " }");
		}

		return String.join("\n", lines) + "\n";
	}

	/** Whether there is anything owed AND a wrapped main to assign it from. */
	private boolean owesInitialisers() {
		return this.mainWrapped && !this.owedOutputs.isEmpty();
	}

	/** The owed varyings set to their zero, ahead of the pack's own main. */
	private String owedPrologue() {
		if (!owesInitialisers()) {
			return "";
		}

		StringBuilder assignments = new StringBuilder();
		this.owedOutputs.forEach((name, qualified) ->
				assignments.append(initialiser(name, qualified)).append(' '));

		return assignments.toString();
	}

	/**
	 * Rebuilds each input matrix from its columns before the pack body runs, so a read of the
	 * original name still sees a {@code mat3}.
	 */
	private String matrixPrologue() {
		StringBuilder assignments = new StringBuilder();
		for (SplitMatrix split : this.splitMatrices) {
			if (!split.input()) {
				continue;
			}

			assignments.append(split.name()).append(" = ").append(split.matrixType()).append('(');
			for (int column = 0; column < split.columns(); column++) {
				if (column > 0) {
					assignments.append(", ");
				}

				assignments.append(matrixColumnName(split.name(), column));
			}

			assignments.append("); ");
		}

		return assignments.toString();
	}

	/**
	 * Copies each output matrix onto its columns after the pack body ran, so the interface the
	 * next stage reads is the value the pack wrote.
	 */
	private String matrixEpilogue() {
		StringBuilder assignments = new StringBuilder();
		for (SplitMatrix split : this.splitMatrices) {
			if (split.input()) {
				continue;
			}

			for (int column = 0; column < split.columns(); column++) {
				assignments.append(matrixColumnName(split.name(), column)).append(" = ")
						.append(split.name()).append('[').append(column).append("]; ");
			}
		}

		return assignments.toString();
	}

	/**
	 * What the depth attachment of this draw receives, which is what the mask is filled from.
	 * <p>
	 * The interpolated depth for an ordinary stage, and the stage's own where it writes one: those
	 * are the two values the hardware may write, and the mask exists to be compared with what was
	 * written. Neither is converted. The pack's own reads of {@code gl_FragCoord.z} are put back
	 * into the window it was written for, and its writes to {@code gl_FragDepth} are brought out of
	 * it again, both by {@link #convertDepth}; this line is text of ours that pass never sees, so
	 * both names here carry the value the target really holds.
	 */
	private String writtenDepth() {
		return this.namesFragDepth ? "gl_FragDepth" : "gl_FragCoord.z";
	}

	/**
	 * Gives {@code gl_FragDepth} the value the hardware would have written, before the pack's own
	 * body runs and can write another.
	 * <p>
	 * <strong>Only where the mask is written and the stage names the builtin</strong>, and both
	 * halves are paid for. A stage that names it may still leave it alone on the branch that runs -
	 * Bliss writes it under {@code POM} and nowhere else ({@code dimensions/all_solid.fsh:359,394})
	 * - and reading a builtin the stage never wrote is undefined, so the mask would carry whatever
	 * the driver left there. Writing it costs the early depth test, which is why a stage that never
	 * names it is left alone: it would pay that for a value the line below can read off
	 * {@code gl_FragCoord} instead.
	 */
	private String coveragePrologue() {
		return this.covers && this.namesFragDepth ? "gl_FragDepth = gl_FragCoord.z; " : "";
	}

	/**
	 * Makes the hit flash and the damage tint out of the overlay the mesh carries, before the pack's
	 * own body runs and can read it.
	 * <p>
	 * Iris's three lines, term for term
	 * ({@code pipeline/transform/transformer/EntityPatcher.java:55-56} and {@code :62}, the fourth
	 * statement of that run being a vertex colour it hands on for its own reasons), and each is the
	 * game's rather than a choice. The image is {@code OverlayTexture}'s sixteen by sixteen, red over
	 * the top half and white with a falling alpha over the bottom, and the element is the pair
	 * {@code OverlayTexture.pack} wrote; the alpha is turned around because what the texture stores
	 * is how much of the mob shows through. The third line is a workaround Iris carries for the
	 * packs, and it stays because the packs are what this engine is written against: some read the
	 * colour without looking at the alpha and expect a black where there is no flash.
	 * <p>
	 * A {@code texelFetch} and not a sample, so nothing the bound sampler does can reach it: the
	 * coordinate is a texel of a sixteen wide image and a filter between two of them would be a
	 * flash halfway to the tint.
	 */
	private String overlayPrologue() {
		if (!this.makesOverlayColour) {
			return "";
		}

		return "vec4 " + OVERLAY_TEXEL + " = texelFetch(" + OVERLAY + ", UV1, 0); "
				+ ENTITY_COLOR + " = vec4(" + OVERLAY_TEXEL + ".rgb, 1.0 - " + OVERLAY_TEXEL + ".a); "
				+ ENTITY_COLOR + ".rgb *= float(" + ENTITY_COLOR + ".a != 0.0); ";
	}

	/**
	 * Hands the three identifiers on out of the element the mesh carries them on, before the pack's
	 * own body runs and can read them.
	 * <p>
	 * Iris's one line spread over three, and the difference is only that its names are components of
	 * one {@code ivec3} where these keep the spelling the pack wrote
	 * ({@code EntityPatcher.java:165-166}). Only what some stage really reads is written: what is in
	 * the union is what was declared, and writing a varying the header did not declare would not
	 * compile.
	 * <p>
	 * The lane is the position in {@link #ENTITY_IDS} and the element is unsigned, so the cast is
	 * where a name the pack never mapped becomes 65535 rather than minus one. That is Iris's number
	 * as well, its own element being unsigned and its input an {@code ivec3} the driver zero extends
	 * into, and it is the number the packs are written against.
	 */
	private String identifierPrologue(Set<String> varyings) {
		if (!this.entityWrapped) {
			return "";
		}

		StringBuilder written = new StringBuilder();
		for (int lane = 0; lane < ENTITY_IDS.size(); lane++) {
			String identifier = ENTITY_IDS.get(lane);
			if (varyings.contains(identifier)) {
				written.append(identifier).append(" = int(")
						.append(EntityVertex.IDENTIFIERS).append('[').append(lane).append("]); ");
			}
		}

		return written.toString();
	}

	/** What output {@code slot} is called, which is the pack's own name when it declared one. */
	private String outputName(int slot, Set<String> shadowed) {
		Output output = this.packOutputs.get(slot);
		if (output == null) {
			return "ofFragData" + slot;
		}

		// Renamed here as well as in the body, since the declaration has moved up out of it and
		// the two halves of one name have to keep agreeing.
		return shadowed.contains(output.name()) ? "ofOwn_" + output.name() : output.name();
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

			int before = significantBefore(index);
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
		int[] lines = lineNumbers();
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
				this.parameterLookups, this.fragCoordZ, this.fragCoordXyz,
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


	private void replace(int index, String text) {
		this.tokens.set(index, this.tokens.get(index).as(text));
	}

	/** Replaces a token with text of our own, which no later pass will match again. */
	private void inject(int index, String text) {
		this.tokens.set(index, new Token(Kind.RAW, text, this.tokens.get(index).directive()));
	}

	private void blank(int index) {
		if (index >= 0) {
			this.tokens.set(index, Token.BLANK);
		}
	}

	/** Empties a range but keeps its line breaks, so error messages still point at the right line. */
	private void blankRange(int start, int end) {
		for (int index = start; index <= end; index++) {
			if (this.tokens.get(index).kind() != Kind.NEWLINE) {
				this.tokens.set(index, Token.BLANK);
			}
		}
	}

	private void blankDirective(int hash) {
		for (int index = hash; index < this.tokens.size(); index++) {
			if (this.tokens.get(index).kind() == Kind.NEWLINE) {
				return;
			}

			this.tokens.set(index, Token.BLANK);
		}
	}

	/**
	 * The next token that is neither space nor comment. A line break ends the search inside a
	 * directive, since a directive is one line, and is stepped over everywhere else.
	 */
	private int significantAfter(int index) {
		if (index < 0) {
			return -1;
		}

		boolean directive = this.tokens.get(index).directive() != null;
		for (int scan = index + 1; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.trivia()) {
				continue;
			}

			if (token.kind() == Kind.NEWLINE) {
				if (directive) {
					return -1;
				}

				continue;
			}

			return scan;
		}

		return -1;
	}

	/**
	 * The identifier a directive declares or tests, which is the second one on its line. The first
	 * is the directive keyword itself.
	 */
	private int macroNameAfter(int hash) {
		boolean keywordSeen = false;

		for (int scan = hash + 1; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() == Kind.NEWLINE) {
				return -1;
			}

			if (token.trivia()) {
				continue;
			}

			if (!keywordSeen) {
				keywordSeen = true;
				continue;
			}

			return token.kind() == Kind.IDENTIFIER ? scan : -1;
		}

		return -1;
	}

	/** The previous token that is neither space, comment nor line break, staying out of directives. */
	private int significantBefore(int index) {
		for (int scan = index - 1; scan >= 0; scan--) {
			Token token = this.tokens.get(scan);
			if (token.trivia() || token.kind() == Kind.NEWLINE) {
				continue;
			}

			return token.directive() == null ? scan : -1;
		}

		return -1;
	}

	/**
	 * Which line of the expanded unit each token sits on. Recomputed rather than kept, because
	 * wrapping a shadow lookup inserts tokens and moves every index after it.
	 */
	private int[] lineNumbers() {
		int[] lines = new int[this.tokens.size()];
		int line = 0;

		for (int index = 0; index < this.tokens.size(); index++) {
			lines[index] = line;
			String text = this.tokens.get(index).text();
			for (int at = 0; at < text.length(); at++) {
				if (text.charAt(at) == '\n') {
					line++;
				}
			}
		}

		return lines;
	}

	/** The opening parenthesis of a call on the identifier at this index, or -1 if it is not one. */
	private int callOpener(int index) {
		int next = significantAfter(index);

		return next >= 0 && this.tokens.get(next).operator("(") ? next : -1;
	}

	private int matchingBracket(int open) {
		if (open < 0) {
			return -1;
		}

		String opening = this.tokens.get(open).text();
		String closing = switch (opening) {
			case "(" -> ")";
			case "{" -> "}";
			default -> "]";
		};
		int depth = 0;

		for (int scan = open; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() != Kind.OPERATOR) {
				continue;
			}

			if (token.operator(opening)) {
				depth++;
			} else if (token.operator(closing)) {
				depth--;
				if (depth == 0) {
					return scan;
				}
			}
		}

		return -1;
	}

	/**
	 * Where the statement containing this token starts: just past whatever ended the last one.
	 * Returns -1 when no end to the previous statement is within reach, since blanking a range
	 * whose beginning was guessed would erase code that is none of our business.
	 */
	private int statementStart(int index) {
		int limit = Math.max(0, index - MAX_STATEMENT_TOKENS);
		int start = -1;

		for (int scan = index - 1; scan >= limit; scan--) {
			Token token = this.tokens.get(scan);
			boolean boundary = token.kind() == Kind.HASH || token.directive() != null
					|| token.operator(";") || token.operator("{") || token.operator("}");
			if (boundary) {
				start = scan + 1;
				break;
			}
		}

		if (start < 0) {
			// Reaching the first token is a real answer; running out of budget is not.
			if (limit > 0) {
				return -1;
			}

			start = 0;
		}

		while (start < index) {
			Token token = this.tokens.get(start);
			if (!token.trivia() && token.kind() != Kind.NEWLINE) {
				break;
			}

			start++;
		}

		return start;
	}

	/** The semicolon closing this statement, or -1 if a brace opens first or none is found. */
	private int statementEnd(int index) {
		int depth = 0;
		int last = Math.min(this.tokens.size(), index + MAX_STATEMENT_TOKENS);

		for (int scan = index; scan < last; scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() != Kind.OPERATOR || token.directive() != null) {
				continue;
			}

			String text = token.text();
			if (text.equals("(") || text.equals("[")) {
				depth++;
			} else if (text.equals(")") || text.equals("]")) {
				depth--;
			} else if (depth == 0 && text.equals("{")) {
				return -1;
			} else if (depth == 0 && text.equals(";")) {
				return scan;
			}
		}

		return -1;
	}

	private List<Integer> significantRange(int start, int end) {
		List<Integer> found = new ArrayList<>();
		for (int scan = start; scan <= end; scan++) {
			Token token = this.tokens.get(scan);
			if (!token.trivia() && token.kind() != Kind.NEWLINE) {
				found.add(scan);
			}
		}

		return found;
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
