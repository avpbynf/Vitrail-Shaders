package dev.vitrail.render;

import dev.vitrail.glsl.LegacyGlsl;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.DrawBuffers;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.texture.TextureStage;
import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.TextSink;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * One program the pack draws a pass of the world's own geometry with, in place of the shader the
 * game would have used.
 * <p>
 * The work is the same whatever geometry it is drawn over, and that is why this class is not
 * {@link TerrainProgram}: the uniform block, the samplers and their fallbacks, the colour
 * attachments and the one blend the pack asked for do not know a chunk from a sky quad. What does
 * differ is gathered in {@link Pass}, which the family fills in: {@code TerrainProgram} answers it
 * out of {@link TerrainPass} for Sodium's three chunk passes.
 * <p>
 * Nothing of the mesh is changed: the attributes it carries are decoded and the names it does not
 * carry are given constants. Which of them the mesh really answers is the family's to say, in
 * {@link Pass#answered}, and it is worth saying: a name the log calls a constant when the mesh does
 * carry it sends a reader looking for a defect that is not there.
 * <p>
 * The block is called {@code OfGlobals} like every other program of this engine, and it has to stay
 * that way: Sodium binds its own {@code u_Globals} into the same pass, unconditionally, and the two
 * would be one name. The bindings Sodium emits for names this pipeline does not declare are
 * harmless, because the descriptor flush walks the layout of the pipeline that is bound and not the
 * list of what was offered; the converse is not, and everything declared here has to be bound or the
 * draw throws.
 */
final class GeometryProgram {

	/** The block name the translator writes into every program. Never {@code u_Globals}. */
	private static final String UNIFORM_BLOCK = "OfGlobals";

	/**
	 * Everything one family of geometry answers differently, so that the rest of this class can be
	 * written once. Each of these is read below exactly where the enum of a family used to be.
	 *
	 * @param family       what the log calls this geometry, {@code chunk} for a chunk pass. One word,
	 *                     and it lands in the middle of a sentence
	 * @param name         the pass inside that family, lowercased, {@code solid} or {@code shadow}.
	 *                     It tells two passes served by one file apart, both in the log and in the
	 *                     identifier the device caches a shader module under, so it may not be empty
	 * @param namespace    the namespace the pipeline is named in. <strong>Not a cosmetic where the
	 *                     geometry is Sodium's</strong>, and {@link TerrainProgram} says why in full:
	 *                     the push constant range exists only for a namespace containing
	 *                     {@code sodium}, and without it the world draws itself on top of the camera
	 * @param answered     the vertex names this mesh really carries, which are the ones NOT to
	 *                     report as constants
	 * @param shadow       whether this pass is drawn from the light rather than from the camera. It
	 *                     decides the depth window, the culling, the descriptor and what a
	 *                     {@code shadowtex} lookup may be answered with
	 * @param blend        what the pass blends with when the pack says nothing, which is the game's
	 *                     own answer for that pass and not a taste: the sun and the moon are drawn
	 *                     over the sky and the chunk pass that is translucent is drawn over the
	 *                     world. Empty for a pass that writes outright
	 * @param covers       whether this pass writes the mask the scene seed is cut with, which every
	 *                     pass drawn before the seed and into the pack's own targets has to
	 * @param claimed      whether this family draws opaque pieces of its OWN over the pixels its
	 *                     blending pieces span, which is the sky and nothing else: its disc writes
	 *                     outright and marks what it covers, and {@link HorizonCone}, drawn inside
	 *                     the disc's own pass and sharing its mask, carries that mark down over the
	 *                     lower hemisphere. So the stars, the sunrise, the sun and the moon blend
	 *                     onto a target the seed leaves alone although none of them marks a pixel of
	 *                     its own. Asked only of a blending pass drawn before the seed, and it is
	 *                     what separates the sky from the hand, which has no such sibling at all.
	 *                     <p>
	 *                     <strong>It records how a family is PUT TOGETHER and is not a per frame
	 *                     guarantee</strong>, and the difference is not a quibble: what marks those
	 *                     pixels is answered per program and per frame, and this is answered once at
	 *                     the load. Two ways the marking can be absent leave the blending pieces
	 *                     claiming all the same, and they do not cost the same. The cone is drawn
	 *                     only for {@link TerrainDraw.Mask#WRITTEN}
	 *                     ({@code render/HorizonCone.java:152}) and only while the pack serves a sky
	 *                     at all, and then what the seed repaints is what stands in the band the
	 *                     cone would have closed: the lower half of the stars, the sunrise fan, a
	 *                     rising or setting sun or moon. Or the disc's own mask is turned down,
	 *                     which is {@code covers} below, and then nothing of the sky marks anything
	 *                     and the seed repaints the whole of it. Both were already true before this
	 *                     field existed, the blend alone having granted the same pixels: a defect it
	 *                     records rather than one it introduces, and what the field buys is that it
	 *                     is written down somewhere
	 * @param afterDeferred whether the pass is drawn after the deferred stage, which is what decides
	 *                     that a depth sampler can be answered with the opaque world's image
	 * @param topology     how the mesh is assembled, which is the game's answer and not a choice:
	 *                     the pass this is bound into was opened for the pipeline the game built,
	 *                     and a difference of topology would be a difference nobody declared
	 * @param cull         whether a back face is thrown away, which is the game's answer too and
	 *                     differs inside one family: the entity pipelines split almost evenly on it,
	 *                     and a cape drawn with the wrong one is either a cape with no inside or a
	 *                     cape drawn twice. The shadow map is the one place the answer is this
	 *                     engine's, and {@link TerrainProgram} says why it is no
	 * @param depth        the depth test and write, or null for none at all. Null is not an
	 *                     oversight and the sky is why: the game's own sky pipeline declares no
	 *                     depth state, so the disc neither tests nor writes, and a pack's program
	 *                     given the ordinary state would write the sky into the depth and have the
	 *                     world tested against it
	 * @param stage        what a pack is told it is drawing, which it reads as {@code renderStage}
	 *                     and branches on with {@code MC_RENDER_STAGE_*}. Both Complementary read it,
	 *                     so a pass that answered {@code NONE} would take them down the branch meant
	 *                     for a full screen quad
	 * @param bindings     a bind group of the game's own that this stage reads besides ours, or null
	 *                     for the families that read nothing but their mesh. Only the clouds have
	 *                     one, and there it is the whole geometry: the pass fills {@code CloudInfo}
	 *                     and {@code CloudFaces} by name against whatever pipeline is bound, so a
	 *                     pipeline that did not declare them would be handed neither
	 * @param perDraw      the name of a second uniform block of ours, one the FAMILY fills between
	 *                     draws inside one pass, or null for every family whose block is written
	 *                     once. Only the far terrain has one, and it has one because its geometry
	 *                     arrives in pieces that do not share a value: each section of Distant
	 *                     Horizons carries block coordinates of its own corner, so the corner has to
	 *                     be bound per section, and a value bound per draw cannot live in
	 *                     {@code OfGlobals}, which is written once and read by every draw of the
	 *                     pass. Declared here and bound by the family: what this record decides is
	 *                     that the layout carries the name, without which the draw is refused
	 * @param distantVolume whether this pass is drawn in Distant Horizons' own volume rather than in
	 *                     the game's. True for the far terrain and false for everything else. The
	 *                     three {@code dhProjection} names answer that volume for every pass alike
	 *                     now, as Iris serves them; what this still decides is that the pass owns its
	 *                     first draw buffer outright, the constructor saying why the seed cannot be
	 *                     its road
	 */
	record Pass(String family, String name, String namespace, Set<String> answered, boolean shadow,
			Optional<BlendFunction> blend, boolean covers, boolean claimed, boolean afterDeferred,
			PrimitiveTopology topology, boolean cull, DepthStencilState depth, RenderStage stage,
			BindGroupLayout bindings, String perDraw, boolean distantVolume) {

		/** Whether the pass blends at all, which is the same question as having something to blend with. */
		boolean blended() {
			return this.blend.isPresent();
		}
	}

	/**
	 * What a pack calls the block atlas. {@code texture} arrives as {@code ofTexture} because the
	 * word is reserved in modern GLSL and the translator renames it; all eight packs of the corpus
	 * use that spelling and no other.
	 */
	private static final Set<String> ATLAS = Set.of("gtexture", "tex", "texture", "ofTexture");

	private static final String LIGHTMAP = "lightmap";

	/**
	 * The game's overlay image, which only a vertex stage drawn from the entity mesh ever asks for:
	 * the translation puts the name there itself, and no pack writes it.
	 */
	private static final String OVERLAY = LegacyGlsl.OVERLAY_SAMPLER;

	/** One pixel each, for a name this step has no answer for. */
	private static final GpuFormat CONSTANT_FORMAT = GpuFormat.RGBA8_UNORM;
	private static final Vector4f OPAQUE_BLACK = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
	private static final Vector4f OPAQUE_WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector4f MID_GREY = new Vector4f(0.5F, 0.5F, 0.5F, 1.0F);

	private static final String SHADOW_LABEL = "Vitrail shadow";

	/** Where one colour attachment of a world pass takes its image from. */
	private enum Bound {

		/** The target the chunk renderer was going to draw into, which carries its own format. */
		GAME,

		/** A colour target of the pack, named by the attachment and on the half it names. */
		PACK,

		/**
		 * No image at all, and the slot is there to be skipped.
		 * <p>
		 * The game writes each fragment output's RANK over the location it declared, so a stage
		 * declaring more outputs than the pack gave it draw buffers still spends those ranks. They
		 * are stood for here rather than closed up, or the mask below would land on the rank an
		 * output of the pack's already holds and read whatever that output happens to carry.
		 */
		UNUSED,

		/** The mask carrying the depth this pass left, which is ours and not in the pack's buffers. */
		COVERAGE
	}

	/**
	 * One colour attachment, in the order the pipeline's states and the descriptor's views are both
	 * walked. {@code target} is the pack's answer and is null for anything else.
	 */
	private record Slot(Bound bound, ChainPlan.Attachment target, GpuFormat format) {
	}

	private final Pass pass;
	private final String path;

	/** Labels for the debugger, built from the family so that two of them are never one name. */
	private final Supplier<String> blockLabel;
	private final Supplier<String> passLabel;

	/**
	 * The attachments this pass adds to or takes instead of the game's target: every draw buffer
	 * when {@link #ownsFirst}, every one but nought otherwise. Empty when there is nothing to gain.
	 */
	private final List<ChainPlan.Attachment> extra;

	/**
	 * Every colour attachment of a world pass, in order, settled once at load. Empty for a shadow
	 * pass, which draws into the map and nothing else.
	 * <p>
	 * One list rather than two readings of one rule. The pipeline carries a state per element and
	 * the descriptor names a view per element, and {@code RenderPass.setPipeline} refuses outright,
	 * in the middle of the world, the moment the two counts or the two formats stop agreeing.
	 */
	private final List<Slot> slots;

	/**
	 * Which {@code shadowcolor} each output of a shadow pass lands in, in output order. Empty for
	 * every pass drawn from the camera.
	 * <p>
	 * The rule is {@link DrawBuffers#shadowColours}, which is Iris's. What is decided here is the
	 * length: no more attachments than the fragment stage declares outputs.
	 * <p>
	 * <strong>That cut is a DIVERGENCE, and it is here because Vulkan and GL do not agree on what an
	 * attachment no fragment writes holds.</strong> Vulkan leaves it undefined for the whole draw;
	 * the GL these packs were written against leaves it standing, and what a pack reads out of an
	 * untouched shadow buffer is the white a coloured shadow multiplies by. Four packs of the corpus
	 * ship a shadow program with one output and no directive at all - BSL, Bliss, Body Camera and
	 * Sildur's - so the pair Iris opens for them would attach a second buffer nothing writes.
	 * <p>
	 * What it costs is measured and it is nothing today: of those four, only Bliss reads
	 * {@code shadowcolor1} at all, at {@code shaders/dimensions/final.fsh:114}, behind
	 * {@code DEBUG_VIEW == debug_SHADOWMAP} which its own {@code lib/settings.glsl:784} leaves off.
	 * So no pack of the corpus can tell the two apart, and the reason for cutting is the rule and
	 * not a picture anyone has seen.
	 * <p>
	 * The floor of one below is the same argument left unserved, and deliberately: a program that
	 * writes NO output still has {@code shadowcolor0} attached, because a pass with no colour at all
	 * is one the encoder takes on its depth while the pipeline substitutes a state of its own.
	 * Mellow is that program, and nothing of Mellow reads a shadow colour back.
	 */
	private final List<Integer> shadowColours;

	/** Whether draw buffer nought goes to the pack rather than to the game. The constructor says why. */
	private final boolean ownsFirst;

	/** Whether this pass writes the mask the scene seed is cut with. Opaque halves only. */
	private final boolean covers;

	/**
	 * Whether this pass blends and lost draw buffer nought all the same, which is the one demotion
	 * that says what it costs the BLEND rather than what it costs the colour. Kept as a field
	 * because it is answered where {@code owns} is in scope and said where it is not.
	 * <p>
	 * <strong>It no longer has a cause of its own, and that is worth saying rather than leaving a
	 * reader to work out.</strong> Every blending pass drawn before the seed either writes the mask
	 * or has an opaque sibling of its own family marking those pixels, so what is left here is a
	 * pass that asked for a mask and could not be given one, which the constructor has already
	 * warned about by name. The line it prints is kept because it says a different thing: not that
	 * the colour goes to the game's target, which the warning says, but that the blend is made
	 * against a target the world is not in.
	 */
	private final boolean demoted;
	private final ColorTargets targets;
	private final ShadowTargets shadow;
	private final PackProgram.Loaded loaded;
	private final PackValues values;
	private final PackUniforms uniforms;
	private final List<String> samplers;
	private final RenderPipeline pipeline;
	private final ShaderSource source;

	/**
	 * Whether this program reads {@code gl_TextureMatrix[0]} out of the game's own per draw
	 * transforms, which is what puts a second bind group on the pipeline.
	 *
	 * @see dev.vitrail.glsl.PackProgram.Loaded#readsGameTransforms
	 */
	private final boolean gameTransforms;

	private MappableRingBuffer block;
	private TextureTarget black;
	private TextureTarget white;
	private TextureTarget grey;

	/**
	 * One texel per material map, for a sprite the resource pack ships nothing for and for every
	 * family drawn with no atlas at all. Their values are not a taste: they are the ones Iris falls
	 * back on, and each of them reads as the absence of what its map describes.
	 */
	private final Map<PbrMap, TextureTarget> flatMaps = new EnumMap<>(PbrMap.class);
	private GpuTextureView atlas;

	/** The matrix the game pushed for this pass, or null for the frame's camera. */
	private Matrix4fc modelView;

	/** The bob that placed this pass's geometry, or null for the frame's. Only the hand sets one. */
	private Matrix4fc bob;

	/** The volume this pass is drawn in, or null for the frame's. Only the hand sets one. */
	private Matrix4fc projection;

	/** The colour the game modulates this pass by, or null for white. */
	private Vector4fc passColour;
	private GpuSampler atlasSampler;
	private boolean cleared;
	private boolean announced;
	private boolean drew;
	private boolean broken;

	/** Targets already reported as read on the half this pass writes. Said once each, not per frame. */
	private final Set<Integer> collisions = new HashSet<>();

	GeometryProgram(Pass pass, PackProgram.Loaded loaded, PackValues values, int load,
			VertexFormat format, List<ChainPlan.Attachment> writes, ColorTargets targets,
			boolean chainRuns) {
		this.pass = pass;
		this.path = loaded.path();
		this.gameTransforms = loaded.readsGameTransforms();
		this.blockLabel = () -> "Vitrail " + pass.family() + " OfGlobals";
		this.passLabel = () -> "Vitrail " + pass.family();
		TranslatedUnit.Notes notes = loaded.program().stages().get(ProgramStage.FRAGMENT).notes();
		int outputs = notes.fragmentOutputs();

		// Draw buffer nought goes to the pack, on all three halves of the world, and the whole cost
		// of that is that somebody else has to stop painting over it.
		//
		// The translucent half has needed it from the start. It is drawn AFTER the seed has run, so
		// the pack's own colour target already holds the opaque world, and that is exactly what a
		// gbuffers_water expects to blend onto. Sent to the game's target instead, the water is
		// drawn and then thrown away: the final overwrites that target with the image the chain
		// composed out of a colortex the water never reached.
		//
		// Every opaque half used to keep it on the game's target and reach the pack's colortex
		// through the seed, which was one conversion too many. What a gbuffers program puts in draw
		// buffer nought is not a colour but whatever the pack packed there, and the game's target is
		// eight bits a channel: Bliss packs two values into each channel of a sixteen bit colortex1,
		// and the trip through the game's target quantised its albedo away entirely, leaving the
		// encoded normal to be read back as the albedo. So the opaque halves write their target
		// outright, and the coverage mask below is what keeps the seed off the pixels they wrote.
		// The entities came last of them, and only once the mask carried a DEPTH: while it was a
		// flag, the cut compared the world's depth with one taken before a single feature was drawn,
		// and a mob standing in front of a block moved that depth by construction, so no mask it
		// wrote could have kept the seed off it.
		//
		// Everything that lands back on the game's target, and it is a list of reasons rather than a
		// count. When the chain is not running there is no final to bring a colortex to the screen,
		// so anything sent there would simply vanish; when the plan had no answer there is nowhere
		// else to send it; when a half that ASKED for a mask could not be given one, the seed would
		// repaint the whole target and take the geometry with it; when a half never asked for one,
		// which is the opaque particles and the weather, the seed carries it in by design; and the
		// last is what the statement after next is about, a blending pass drawn before the seed with
		// nothing marking the pixels it blends onto. Either way the pass draws where Sodium would
		// have, which is also what keeps the pipeline's one state the pass's.
		//
		// Whether the mask was really written is the translation's answer and not a second reading
		// of the same rule: the stage that could not be given one says so, and an engine that
		// decided for itself would be attaching an image nothing fills.
		boolean owns = chainRuns && !writes.isEmpty();
		this.covers = pass.covers() && covers(notes, writes.size(), chainRuns);
		// A blending pass may take draw buffer nought outright only where the seed will not repaint
		// the pixels it blended onto, and blending is not that question: it was read as though it
		// were, and every family answered the same either way until the hand arrived.
		//
		// Three ways the seed is kept off. The pass is drawn AFTER the seed, which is the world's
		// water, the weather, the clouds, the translucent particles, the blending half of the
		// entities and the hand's own water pass. It writes the mask itself, which is what covers
		// above answers, and which the hand's solid pass now does with the rest of the door. Or the
		// family draws opaque pieces of its own over the same pixels, which is the sky alone and
		// which claimed carries, with the two places it does not hold named where that field is
		// declared.
		//
		// The hand came last of the three roads and by the second, and what had made it impossible
		// there was the flag. The cut then asked whether the depth had moved closer since a copy
		// taken before the game's features, and the hand is drawn with its clip depth squeezed into
		// the band 0.4375 to 0.5625 (render/HandDraw.java:93,382), which is not the depth of anything
		// it stands in front of: every hand pixel answered that yes, whatever mask was written there.
		// The mask carries the depth now, so a hand row writes that same squeezed value and the cut
		// compares it with itself.
		//
		// AND IT IS WHERE IRIS HAS IT. Iris binds every gbuffers program to a framebuffer over the
		// pack's own declared draw buffers, the hand included
		// (pipeline/IrisRenderingPipeline.java:686-687; the four keys its hand passes ask for are at
		// pipeline/IrisPipelines.java:192,204,216), so its hand colour is written to the pack's
		// target and never leaves it. Three things were paid for the trip through the game's target
		// while this pass was still making it, and they are what the mask buys back. The trip through
		// eight bits a channel, which is the quantisation the Bliss paragraph above measured on the
		// terrain. The blend, which happened against the game's target: with the chain running the
		// world is in the PACK's target, so a hand pixel of alpha under one blended against the clear
		// rather than against what stands behind it. And a row that writes no depth with nothing of
		// its own pass writing depth under it - a held banner's pattern is the reachable one
		// (BANNER_PATTERN, RenderPipelines.java:318) - which the cut discarded outright, the mask and
		// the world's depth both holding what the geometry behind the hand left.
		// The far terrain owns its first draw buffer whichever half it falls on, and it is the one
		// family that owns it without a mask and without being drawn after the seed. Iris binds its
		// dh programs to a framebuffer over the pack's own declared draw buffers, exactly as it
		// binds every gbuffers program (compat/dh/DHCompatInternal.java:92 building
		// createDHFramebuffer over the terrain source), so the pack's colour never makes the trip
		// through the game's target there. And the seed cannot be this family's road at all: the
		// pack's sky claims the whole horizon with a depth of its own - the disc's, and the cone's
		// below it - which stands nearer than any far terrain, so the cut refuses the transport and
		// what the seed target keeps where the far terrain stands is the sky. What keeps the seed
		// OFF the pixels this family writes is that same claim, the world's depth holding the far
		// plane there: a game feature really drawn in front still comes in over it, its depth being
		// nearer than the sky's claim.
		this.ownsFirst = owns && (this.covers || pass.afterDeferred()
				|| (pass.blended() && pass.claimed()) || pass.distantVolume());
		// The demotion just above and none of the ones before it, which is why owns and the side are
		// both in it: without owns this would answer yes for every blending pass in a place where
		// the chain does not run or the plan had no attachments to give, and then say of the water,
		// the clouds and the weather that they are drawn before a seed they are drawn after - or
		// before a seed that is never painted at all. Nothing tests the shadow map here because
		// nothing needs to: every shadow pass is built with an empty blend.
		this.demoted = owns && !this.ownsFirst && pass.blended() && !pass.afterDeferred();
		this.extra = this.ownsFirst
				? List.copyOf(writes)
				: writes.size() < 2 ? List.of() : List.copyOf(writes.subList(1, writes.size()));
		this.slots = pass.shadow() ? List.of() : attachments(targets, outputs);
		// Never fewer than one, whatever the fragment stage declares. Mellow's shadow program writes
		// no output at all - its whole body is one discard test, so the count is nought - and a pass
		// with no colour attachment is one the encoder accepts on the strength of its depth while
		// the pipeline substitutes a state of its own, which is the mismatch this file refuses by
		// name elsewhere. It draws into the map and into nought, exactly as before this rule.
		//
		// And never more than a pipeline holds states for. The builder writes them into an array of
		// that length, so one rank past it is an index out of bounds where the program is built
		// rather than a refusal that names it. A directive can reach it: the indices run together
		// and DRAWBUFFERS:000000000 parses.
		this.shadowColours = pass.shadow()
				? DrawBuffers.shadowColours(
						loaded.program().stages().get(ProgramStage.FRAGMENT).drawBuffers(),
						ShadowTargets.COLOURS).stream()
						.limit(Math.clamp(outputs, 1, ColorTargetState.MAX_COLOR_TARGETS))
						.toList()
				: List.of();

		// Said here rather than in announce(), because it is a property of the text and not of a
		// frame, and because what it costs is invisible: the pass then draws exactly as it did
		// before the mask existed, and it is Bliss's albedo that pays for it.
		if (owns && pass.covers() && !this.covers) {
			// Split by cause, and the split is not where the predicate above suggests. The
			// translation refuses to place a mask at eight outputs, which is the same eight a
			// pipeline carries, so a stage that is full and a stage that could not be given one are
			// the same case and both belong below. What is left on this side is a pack writing more
			// draw buffers than its fragment stage declares outputs, and the numbers describe that
			// and nothing else.
			if (notes.coverage() == 1) {
				// Present tense, and it is worse than a lost mask: the translation was asked for one
				// by the family and placed it one rank above the outputs, so the fragment writes it
				// at a rank this attachment list fills with a colour target of the pack. Nothing
				// downgrades on that side, and what the pack meant to write there is written over.
				Vitrail.logger().warn("{} writes {} draw buffers where the {} pass declares {} "
						+ "fragment outputs, so the coverage mask sits at a rank one of those draw "
						+ "buffers holds and is written over it: draw buffer nought stays on the "
						+ "game's target and the scene seed keeps painting the whole of it", this.path,
						writes.size(), pass.name(), outputs);
			} else {
				Vitrail.logger().warn("{} could not be given a coverage mask by the translation, so "
						+ "draw buffer nought of the {} pass stays on the game's target and the scene "
						+ "seed keeps painting the whole of it", this.path, pass.name());
			}
		}

		this.targets = targets;
		this.shadow = targets.shadow();
		this.loaded = loaded;
		this.values = values;
		// A shadow pass is drawn from the light, so the six fixed function names answer the shadow
		// pair. Everything else in the table is the frame's and is shared with the world.
		this.uniforms = new PackUniforms(loaded.program().uniforms(),
				pass.shadow() ? values.shadowGeometryCatalog() : values.geometryCatalog());
		this.samplers = loaded.program().samplers().stream().map(TranslatedUnit.Uniform::name).toList();

		String vertex = loaded.program().stages().get(ProgramStage.VERTEX).text();
		String fragment = loaded.program().stages().get(ProgramStage.FRAGMENT).text();
		// The pass is in the name and not only the path, because two passes are usually served by
		// the same file and their text still differs: the cutout half carries a discard the solid
		// half does not. The device caches a shader module under its identifier, so one name for two
		// texts would hand the second whatever the first compiled to, and the picture would be a
		// picture with the discard silently gone.
		String stem = "pack/" + load + "/" + pass.name() + "/" + this.path;
		Identifier vertexId = Identifier.fromNamespaceAndPath(pass.namespace(), stem + "/vertex");
		Identifier fragmentId = Identifier.fromNamespaceAndPath(pass.namespace(), stem + "/fragment");

		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				return fragmentId.equals(id) ? fragment : null;
			}

			return vertexId.equals(id) ? vertex : null;
		};

		BindGroupLayout.Builder bindings = BindGroupLayout.builder()
				.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER);
		// The second block, for the one family whose geometry arrives in pieces that do not share a
		// value. In the same group as ours rather than in a group of its own: what the game builds a
		// second group for is a layout of ITS own, bound by the names it spells, and this one is this
		// engine's from end to end.
		if (pass.perDraw() != null) {
			bindings.withUniform(pass.perDraw(), UniformType.UNIFORM_BUFFER);
		}

		this.samplers.forEach(bindings::withSampler);

		// Everything but the shaders, the bind group, the attachments and the two lines below is
		// Sodium's own, taken from ShaderChunkRenderer.createShader: the pass this is bound into was
		// opened for that pipeline and a difference of topology would be a difference nobody declared.
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(pass.namespace(), "pipeline/" + stem))
				.withVertexShader(vertexId)
				.withFragmentShader(fragmentId)
				.withBindGroupLayout(bindings.build())
				.withPrimitiveTopology(pass.topology())
				.withCull(pass.cull());

		// A second group, and it is the game's own rather than one built here: the pass binds its
		// contents by name, so the names have to be the ones it binds. Only the clouds have one.
		if (pass.bindings() != null) {
			builder.withBindGroupLayout(pass.bindings());
		}

		// And the game's per draw transforms, for a program that reads a texture matrix out of them.
		// The game's own object again, and for the same reason: what is bound into it is bound by the
		// name the layout carries, so a copy built here would have to spell DynamicTransforms anyway.
		if (this.gameTransforms) {
			builder.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS);
		}

		// No binding at all where the family binds no mesh, which is the clouds and only them: their
		// geometry is three bytes a face in a texel buffer and a vertex identifier, so a binding of
		// nought elements would name a buffer the pass never sets.
		if (format != null) {
			builder.withVertexBinding(0, format);
		}

		// Left unset where the family answers null, which is not the same as setting the default one:
		// the builder hands a null state through to the pipeline, and that is a pass which neither
		// tests nor writes a depth. It is what the game's own sky is built with.
		if (pass.depth() != null) {
			builder.withDepthStencilState(pass.depth());
		}

		// One state per attachment, and dynamic rendering wants the two counts equal.
		// By slot and never by append: the builder holds the states in an array and the argumentless
		// form writes slot nought every time, so three calls would leave one state and a pipeline
		// the pass refuses to bind, by name and in the middle of the world.
		if (pass.shadow()) {
			// One state per shadowcolor the program's own draw buffers name, in their order. The
			// format is each buffer's own and not a constant: Mellow asks for R8 on nought, and a
			// state naming four channels against a one channel attachment is the pipeline refusing
			// to bind.
			for (int slot = 0; slot < this.shadowColours.size(); slot++) {
				builder.withColorTargetState(slot,
						state(targets.shadowFormat(this.shadowColours.get(slot))));
			}
		} else {
			for (int slot = 0; slot < this.slots.size(); slot++) {
				Slot one = this.slots.get(slot);
				switch (one.bound()) {
					case UNUSED -> builder.withUnusedColorTargetState(slot);
					// The mask is written outright and never blended, whatever the pack asked for its
					// own targets: what it carries is the depth the fragment left, and a depth mixed
					// with the one behind it is the depth of nothing at all.
					case COVERAGE -> builder.withColorTargetState(slot, new ColorTargetState(
							Optional.empty(), one.format(), ColorTargetState.WRITE_ALL));
					default -> builder.withColorTargetState(slot, state(one.format()));
				}
			}
		}

		this.pipeline = builder.build();

		// A storage block is the one refusal that does not announce itself. An unbindable sampler
		// stops the pipeline from being built and this class already falls back on that; a storage
		// block compiles, never enters a bind group, and leaves the descriptor on the binding the
		// pack wrote, which is a draw against nothing. The chain refuses one by name and so does
		// this, so that the world keeps the game's own shader instead.
		List<String> storage = loaded.storageBlocks();
		if (!storage.isEmpty()) {
			this.broken = true;
			Vitrail.logger().error("{} declares the storage block {}, which nothing binds, so the "
					+ "{} pass keeps the game's own shader", this.path, String.join(", ", storage),
					pass.name());
		}
	}

	/**
	 * The colour attachments of a world pass, in the order both the pipeline and the descriptor walk
	 * them.
	 * <p>
	 * Nought is the game's own target and carries its format; the rest carry the format their colour
	 * target was really allocated as, which is not always the one the pack asked for.
	 *
	 * @param outputs how many outputs the fragment stage declares, which is where the mask goes and
	 *                not where the pack's draw buffers end. See {@link Bound#UNUSED}
	 */
	private List<Slot> attachments(ColorTargets targets, int outputs) {
		List<Slot> built = new ArrayList<>();
		if (!this.ownsFirst) {
			built.add(new Slot(Bound.GAME, null, GpuFormat.RGBA8_UNORM));
		}

		for (ChainPlan.Attachment attachment : this.extra) {
			built.add(new Slot(Bound.PACK, attachment, targets.format(attachment.target())));
		}

		if (this.covers) {
			while (built.size() < outputs) {
				built.add(new Slot(Bound.UNUSED, null, null));
			}

			built.add(new Slot(Bound.COVERAGE, null, targets.coverageFormat()));
		}

		return List.copyOf(built);
	}

	/**
	 * What the pack asked to blend with, falling back to what the pass wants when it asked nothing,
	 * on every attachment alike. The per buffer form, {@code blend.<program>.<buffer>}, is still
	 * not read: one pipeline carries one blend function for every target it writes.
	 * <p>
	 * Four packs of the corpus name the translucent chunk pass here. Reverie asks for no blending
	 * at all on its water, which is the opposite of what the pass would have chosen, and Bliss and
	 * the two Complementary give a function whose alpha half differs from the one assumed.
	 */
	private ColorTargetState state(GpuFormat format) {
		return new ColorTargetState(
				BlendFunctions.of(this.targets.blend(this.loaded.path()), this.pass.blend()), format,
				ColorTargetState.WRITE_ALL);
	}

	/**
	 * Everything that has to happen outside a render pass: the pipeline compiled, the buffers made,
	 * the constants cleared, and this frame's block written.
	 * <p>
	 * Called where Sodium asks for its shader, which is before it opens its pass. Creating a texture
	 * or a buffer records a barrier into the very command buffer a pass would be recording into, and
	 * a clear refuses outright while one is open.
	 *
	 * @param atlas the block atlas of the pass being drawn, kept for the bind
	 * @return the pipeline to draw with, or null to leave the game's own shader alone
	 */
	RenderPipeline prepare(GpuDevice device, GpuTextureView atlas) {
		return prepare(device, atlas, null, null, null, null);
	}

	/**
	 * The same, for a pass the game draws with a model view, a colour or a volume of its own.
	 *
	 * @param modelView  the matrix the game pushed for this pass, or null for the frame's camera.
	 *                   Kept until the block is written rather than applied here: it is one value of
	 *                   the block among the rest, and the block is written a few lines below
	 * @param bob        the left factor that placed this pass's geometry, or null for the frame's.
	 *                   The hand is the one family that sets it, and it sets it because it is drawn
	 *                   under a projection built here rather than under the level's;
	 *                   {@code ViewMatrices.passBob} says what the frame's would cost it
	 * @param projection the volume this pass is drawn in, or null for the frame's. The hand is the
	 *                   one family that sets it, and it is not a nudge of the frame's but a matrix
	 *                   of its own; {@link dev.vitrail.uniform.ViewSource#passProjection} says why
	 */
	RenderPipeline prepare(GpuDevice device, GpuTextureView atlas, Matrix4fc modelView,
			Matrix4fc bob, Vector4fc passColour, Matrix4fc projection) {
		this.modelView = modelView;
		this.bob = bob;
		this.passColour = passColour;
		this.projection = projection;
		if (this.broken) {
			return null;
		}

		// Refused rather than drawn somewhere else. A shadow program handed back with no map to
		// draw into would be bound into the pass the renderer opens for itself, which is the game's
		// own target, and the pack's shadow output would land on the screen.
		if (this.pass.shadow() && this.shadow.depth() == null) {
			return null;
		}

		CompiledRenderPipeline compiled = device.precompilePipeline(this.pipeline, this.source);
		if (!compiled.isValid()) {
			// Handing back an invalid pipeline throws inside setPipeline, in the middle of Sodium's
			// own pass, which reads as a Sodium failure. Refused here instead, once.
			this.broken = true;
			// The PASS and not "the terrain", which it said from the day only the terrain came through
			// here, and not the family either: the family is one word for all twenty entity pieces,
			// so it would not say which failed. The same words as the storage block refusal above,
			// because the two latch the same flag and a reader meeting either has the same question.
			Vitrail.logger().error("{} did not compile, so the {} pass keeps the game's own shader",
					this.path, this.pass.name());

			return null;
		}

		this.atlas = atlas;
		ensureConstants(device);
		if (this.block == null) {
			this.block = new MappableRingBuffer(this.blockLabel,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, blockBytes());
		}

		announce();
		writeBlock();

		return this.pipeline;
	}

	/**
	 * Binds this program's block and every sampler it declares, inside the pass Sodium opened.
	 * <p>
	 * Every name the layout carries has to be bound or the draw throws on the first one missing, so
	 * a name this step has no answer for gets one pixel rather than being left out. Only two names
	 * are answered with anything real: the block atlas, and the light map. Everything else is a
	 * constant, which is why the criterion for this step is the albedo and nothing to do with light.
	 */
	void bind(RenderPass pass) {
		// Once, and it is the one thing that tells a pass that draws from a pass that only compiled:
		// announce() says a program was prepared, which happens whether or not the renderer goes on
		// to record a single command against it.
		if (!this.drew) {
			this.drew = true;

			// The image goes on this line and not on the one announce() prints, which is the only
			// place it can go: the atlas belongs to the DRAW for two of the three families, so at
			// the moment the program is announced this field holds nothing yet. Said for whoever
			// reads the atlas and for nobody else, a pass with no such name having no image to name.
			Vitrail.logger().info("The {} pass records its first draw with {}{}",
					this.pass.name(), this.path,
					this.samplers.stream().anyMatch(ATLAS::contains)
							? ", reading " + GameImages.name(this.atlas) + " where it asks for the atlas"
							: "");
		}

		pass.setUniform(UNIFORM_BLOCK, this.block.currentBuffer().slice(0, blockBytes()));

		for (String sampler : this.samplers) {
			pass.bindTexture(sampler, view(sampler), sampler(sampler));
		}
	}

	private GpuSampler sampler(String name) {
		if (ATLAS.contains(name) && this.atlasSampler != null) {
			return this.atlasSampler;
		}

		PbrMap material = material(name);
		if (material != null) {
			// The albedo's own sampler, because a map is read at the albedo's own texture coordinate
			// whichever door served it, so anything read differently would be read at a different
			// place than the albedo beside it. What that means is not the same on the two doors: an
			// atlas's maps ARE the atlas, the same size and the same chain with every sprite in the
			// same slot, where a plain texture's cover the same whole range at a resolution of their
			// own and carry no chain at all. A mipmapped sampler on the second is harmless rather
			// than overlooked: the view carries one level, so the read is bounded by the image.
			//
			// Except the specular map under labPBR, where a filter that blends two texels of it
			// produces a material that is in neither of them. Iris asks the same question here
			// (pipeline/IrisRenderingPipeline.java:860-867) and answers it with
			// GlSampler.MIPPED_NEAREST_NEAREST, which is NEAREST inside a level AND between levels.
			//
			// THIS ONLY GETS HALF OF IT, and the half it misses is an obstacle of the API rather
			// than a preference. What comes back here is nearest inside a level and LINEAR between
			// them: the backend sets the mip mode from the sampler's maximum lod alone,
			// VulkanGpuSampler:47 taking LINEAR for anything above a quarter, and the cache offers
			// no way to ask for the other one - a sampler built with no mipmaps caps the lod at that
			// quarter and gives up the chain entirely, which is worse. Making one by hand does not
			// help either, GpuDevice.createSampler reaching the same constructor.
			// What it costs: on a distant surface, where two levels are blended, the thresholds are
			// crossed after all. Close up, where a level is read whole, they are not.
			if (this.atlasSampler != null && material.interpolates(PbrAtlases.labPbr())) {
				return this.atlasSampler;
			}

			return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
		}

		if (LIGHTMAP.equals(name)) {
			return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}

		// Nothing about this one reaches the read, which is a texelFetch and takes no sampler state
		// at all. It is bound because a name declared in the module has to be, and NEAREST is what
		// says so: a filter here would be a flash halfway to the tint if anything ever sampled it.
		if (OVERLAY.equals(name)) {
			return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}

		// The noise image tiles and everything else clamps, the same rule the chain follows.
		//
		// Never past level nought, even on a target that carries a chain, and this is not a gap left
		// open. Nothing fills a chain for a geometry program, because the reduction opens render
		// passes and a terrain pass draws inside the one Sodium opened, where no other can start; a
		// sampler that let these reads climb would hand them levels nothing has written, which is
		// undefined memory rather than a coarser image.
		//
		// Nothing asks for it either, and that was measured rather than assumed: across the eight
		// packs there are fifty reads at a lod other than nought and not one of them is in a
		// geometry file. Four of them do declare colortexNMipmapEnabled on a gbuffers program,
		// Bliss on gaux1 the loudest, and none of those programs ever reads the target at a lod. So
		// the directive is theirs to declare and dead on their side, and the cost of honouring it
		// here would be a risk taken for nobody.
		SamplerPlan.Kind kind = this.loaded.samplers().binding(name).kind();
		if (kind == SamplerPlan.Kind.PACK_TEXTURE) {
			// A file of the pack's own is filtered and addressed as the pack asked, in the .mcmeta
			// beside it, and the name it took over has nothing to say about either.
			ColorTargets.PackBinding supplied = this.targets.packTexture(TextureStage.GBUFFERS, name);
			if (supplied != null) {
				return PackPass.sampler(supplied.repeat(), supplied.filter(), false);
			}
		}

		return PackPass.sampler(kind, filter(name), false);
	}

	/**
	 * The sampler the game configured for the block atlas, which is mipmapped and filtered as the
	 * user's own settings say. Worth taking rather than making one: a block atlas read without
	 * mipmaps shimmers at distance, and the sprites bleed into each other at their edges.
	 */
	void sampler(GpuSampler sampler) {
		this.atlasSampler = sampler;
	}

	/**
	 * The image a pass draws with, where the pass has one of its own rather than the block atlas
	 * { #prepare} was handed. Set before the bind and never during it.
	 */
	void atlas(GpuTextureView view) {
		this.atlas = view;
	}

	/** Whether the pipeline a pass has bound is this program's. */
	@SuppressWarnings("ReferenceEquality")
	boolean owns(RenderPipeline bound) {
		return this.pipeline == bound;
	}

	/** Whether this program can still be served, which everything built on it has to agree with. */
	boolean servable() {
		return !this.broken;
	}

	/**
	 * Whether this pass really marks the pixels it wrote, which for an opaque pass is the same
	 * question as whether it writes the pack's own colour target at all: the constructor ties the
	 * two together and says why they cannot be separated.
	 * <p>
	 * Asked from outside because a pass that claims a pixel against the scene seed takes it away
	 * from every pass whose picture reaches the pack's target through that seed, and the only one
	 * who can say whether a pass is in that position is the pass itself.
	 */
	boolean covers() {
		return this.covers;
	}

	/**
	 * Whether a pass that asks for the coverage mask would really be given one, before there is a
	 * program to ask. The constructor is one of the two callers and takes its own answer from here,
	 * so the rule has one home.
	 * <p>
	 * <strong>The other caller has to know it a step earlier than the program exists</strong>, and
	 * that is the whole reason this is not a field: {@link EntityDraw} settles where a serving file's
	 * outputs go before it builds anything, and what it settles differs on this answer. A piece given
	 * the mask writes the pack's target outright and owes the scene seed nothing; a piece that asked
	 * for one and could not be given one falls back on the game's target and reaches the pack through
	 * that seed, which is what makes the seed's own target its business again.
	 * <p>
	 * Three of the four terms are the translation's and one is the frame's. The stage says whether it
	 * was really given a mask, {@code coverage} being one where the epilogue placed it; the mask sits
	 * one rank above every output the stage declares, so a pack writing more draw buffers than it
	 * declares outputs would have it land on a rank one of those draw buffers holds, and a stage
	 * already at the eight a pipeline carries has no rank left for it. And with no attachment at all
	 * there is nothing to keep the seed off, the pass drawing into the game's target as it always
	 * did.
	 *
	 * @param attachments how many draw buffers the plan gives this pass, which is zero where it had
	 *                    no answer for it. The chain is asked separately and not read off this
	 *                    count: with the chain switched off the plan still hands its list over, and
	 *                    what is missing is the final that would have brought a colortex to the
	 *                    screen
	 */
	static boolean covers(TranslatedUnit.Notes notes, int attachments, boolean chainRuns) {
		return chainRuns && attachments > 0 && notes.coverage() == 1
				&& attachments <= notes.fragmentOutputs()
				&& notes.fragmentOutputs() < ColorTargetState.MAX_COLOR_TARGETS;
	}

	/**
	 * The render pass this program wants opened, or null to leave the chunk renderer's own alone.
	 * <p>
	 * Null is the ordinary answer and not a failure: a pass that gained nothing over the one Sodium
	 * would have opened wants exactly that one, and building an identical one of our own would only
	 * be a way of getting it wrong later. It is also the answer while the targets are still being
	 * allocated, which is the first frame or two and the frames after a resize.
	 *
	 * @param colour the colour view the renderer was going to draw into, which is attachment nought
	 *               only where the pack's own targets do not take it
	 * @param depth  the depth view it was going to use, kept as it is: the terrain has to test
	 *               against the sky the game already drew, and everything the game draws afterwards
	 *               has to test against the terrain
	 */
	RenderPassDescriptor descriptor(GpuTextureView colour, GpuTextureView depth) {
		// The same refusal prepare makes, or the two part company: with the pipeline refused,
		// Sodium keeps its own shader, which declares one target state, and steering its pass onto
		// a many target descriptor is refused at setPipeline in the middle of Sodium's own draw.
		if (this.broken) {
			return null;
		}

		if (this.pass.shadow()) {
			return shadowDescriptor();
		}

		if (plain()) {
			return null;
		}

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(this.passLabel);
		for (Slot slot : this.slots) {
			if (slot.bound() == Bound.UNUSED) {
				descriptor.withUnusedColorAttachment();
				continue;
			}

			GpuTextureView view = view(slot, colour);
			if (view == null) {
				// The targets are not there yet, or not there any more. Sodium's own pass draws this
				// frame rather than nothing at all, and the next frame tries again.
				return null;
			}

			descriptor.withColorAttachment(view);
		}

		// Never left out: the encoder refuses a descriptor without one outright, and it refuses it
		// at the first draw rather than at load time. The size is the screen's, and stays the
		// screen's now that attachment nought may be a target of the pack's: a scaled colour target
		// is dropped before it gets here, and the game's depth is attached whatever else is.
		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0,
				this.targets.screenWidth(), this.targets.screenHeight()));

		return depth == null ? descriptor : descriptor.withDepthAttachment(depth);
	}

	/**
	 * Whether this pass gains nothing over the one the game was going to open: one attachment, the
	 * game's own target, at its own format.
	 * <p>
	 * Asked from outside by whoever opens the pass itself rather than steering one the game opened,
	 * and it is the question that tells the two nulls of {@link #descriptor} apart. One of them means
	 * this, and the pass to open is the plain one; the other means the colour targets are not there
	 * yet, and then there is no pass to open at all, because the pipeline carries a state per
	 * attachment the descriptor would have named and binding it into a single attachment pass throws
	 * by name in the middle of the world.
	 */
	boolean plain() {
		return this.slots.size() == 1 && this.slots.get(0).bound() == Bound.GAME;
	}

	/**
	 * Whether the draw about to be recorded owes this program the game's own transforms, which the
	 * door that has them binds itself.
	 * <p>
	 * Answered here and bound there because the buffer belongs to the DRAW: one run of draws is one
	 * uniform block of this engine's, and two breezes in the same run hold two texture matrices.
	 */
	boolean readsGameTransforms() {
		return this.gameTransforms;
	}

	/**
	 * The image one attachment is really drawn into, or null when it is not there yet.
	 *
	 * @param colour the colour view the renderer was going to draw into, which is the only image of
	 *               this pass that is not ours to look up
	 */
	private GpuTextureView view(Slot slot, GpuTextureView colour) {
		return switch (slot.bound()) {
			case GAME -> colour;
			case PACK -> this.targets.view(slot.target().target(), slot.target().side());
			case COVERAGE -> this.targets.coverage();
			case UNUSED -> null;
		};
	}

	/**
	 * The pass the shadow map is drawn into, which shares nothing with the one the renderer opened:
	 * neither its attachments, which are ours, nor its area, which is the map's own square and not
	 * the screen's. Null while the map is not there, and then nothing is drawn at all rather than
	 * the shadow programs writing over the world.
	 */
	private RenderPassDescriptor shadowDescriptor() {
		GpuTextureView depth = this.shadow.depth();
		if (depth == null) {
			return null;
		}

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> SHADOW_LABEL);
		for (int index : this.shadowColours) {
			// One attachment per state the pipeline carries, and in the same order. The images are
			// allocated together with the depth, so this is null only where the depth above is, and
			// the test is kept all the same because the two failures are not equal: a pass short of
			// an attachment the pipeline names is setPipeline refusing in the middle of the world,
			// where a null descriptor out of this method is the shadow stage not opening at all.
			// Neither is safe here - openShadowStage settles that question outside the pass, and
			// TerrainDraw.shown says what a refusal from the light would cost - so this line is the
			// second lock on a door the stage already holds shut.
			GpuTextureView colour = this.shadow.colour(index);
			if (colour == null) {
				return null;
			}

			descriptor.withColorAttachment(colour);
		}

		return descriptor
				.withDepthAttachment(depth)
				.withRenderArea(new RenderPass.RenderArea(0, 0, this.shadow.resolution(),
						this.shadow.resolution()));
	}

	/** Rotates the ring buffer. Called once the frame's terrain draw has been recorded. */
	void rotate() {
		if (this.block != null) {
			this.block.rotate();
		}
	}

	/** This program's block as {@code name = value} text, for the decoded dump. */
	String decoded(WorldState world) {
		TextSink sink = new TextSink();
		this.uniforms.write(sink, world);

		return sink.text();
	}

	String path() {
		return this.path;
	}

	/**
	 * How the dump names this program, which has to tell two passes of one file apart. The pass is
	 * last and bare so that the line the dump is pointed at can be the pass rather than the file.
	 */
	String label() {
		return this.path + " " + this.pass.name();
	}

	void release() {
		if (this.block != null) {
			this.block.close();
			this.block = null;
		}

		this.black = release(this.black);
		this.white = release(this.white);
		this.grey = release(this.grey);
		this.flatMaps.values().forEach(TextureTarget::destroyBuffers);
		this.flatMaps.clear();
		this.cleared = false;
	}

	/**
	 * Which of the two material maps a name asks for, or null for every other name and for a name
	 * the pack has taken over.
	 * <p>
	 * The pack wins, and Iris does that on one of its two roads and not on the other. The one that
	 * serves the terrain wraps the sampler holder in
	 * {@code ProgramSamplers.customTextureSamplerInterceptor} before the level samplers are added at
	 * all ({@code gl/program/ProgramSamplers.java:50-56} from
	 * {@code pipeline/IrisRenderingPipeline.java:379} and {@code :584}), so a
	 * {@code texture.gbuffers.normals} line takes the name from underneath them. The one that serves
	 * the entities, the particles, the sky and the hand hands over the raw holder instead
	 * ({@code IrisRenderingPipeline.java:762}), and there the line does not take it. Iris disagrees
	 * with itself, so this follows the half that gives the pack what it asked for.
	 * <p>
	 * Nothing in the corpus writes such a line, measured across the eight packs' properties files, so
	 * this decides nothing today. It is here because the day it decides something, the wrong answer
	 * is a pack reading the block atlas's relief where it asked for a file of its own.
	 */
	private PbrMap material(String sampler) {
		PbrMap named = PbrMap.named(sampler);
		if (named == null) {
			return null;
		}

		return this.loaded.samplers().binding(sampler).kind() == SamplerPlan.Kind.PACK_TEXTURE
				? null
				: named;
	}

	private int blockBytes() {
		return Math.max(16, this.uniforms.size());
	}

	private void writeBlock() {
		// Before the block and never once for the run: the two conventions alternate inside one
		// frame now that the shadow map is ours and the game's targets are not, and what a vertex
		// stage does with its clip depth on the way out comes from this pair.
		this.values.convention(this.pass.shadow() ? ClipSpace.FORWARD : ClipSpace.REVERSED);
		this.values.modelView(this.modelView, this.bob);
		this.values.projection(this.projection);
		this.values.passColour(this.passColour);
		this.values.renderStage(this.pass.stage());

		try (GpuBufferSlice.MappedView view = this.block.currentBuffer().map(false, true)) {
			ByteBuffer data = view.data();
			data.position(0);
			this.uniforms.write(Std140Builder.intoBuffer(data), this.values.world());
		}
	}

	private void ensureConstants(GpuDevice device) {
		if (this.black == null) {
			this.black = new TextureTarget("Vitrail terrain black", 1, 1, false, CONSTANT_FORMAT);
			this.white = new TextureTarget("Vitrail terrain white", 1, 1, false, CONSTANT_FORMAT);
			this.grey = new TextureTarget("Vitrail terrain grey", 1, 1, false, CONSTANT_FORMAT);
			for (PbrMap map : PbrMap.values()) {
				this.flatMaps.put(map, new TextureTarget("Vitrail terrain " + map.sampler(), 1, 1,
						false, CONSTANT_FORMAT));
			}

			this.cleared = false;
		}

		if (!this.cleared) {
			this.cleared = true;
			CommandEncoder encoder = device.createCommandEncoder();
			encoder.clearColorTexture(this.black.getColorTexture(), OPAQUE_BLACK);
			encoder.clearColorTexture(this.white.getColorTexture(), OPAQUE_WHITE);
			encoder.clearColorTexture(this.grey.getColorTexture(), MID_GREY);
			this.flatMaps.forEach((map, target) ->
					encoder.clearColorTexture(target.getColorTexture(), map.missing()));
		}
	}

	private GpuTextureView view(String sampler) {
		PbrMap material = material(sampler);
		if (material != null) {
			// The map that follows THIS pass's own image, which is how the block atlas, the particle
			// atlas and every entity texture answer for themselves without this step knowing which of
			// them it is drawing. Iris resolves it from the bound albedo for the same reason
			// (pipeline/IrisRenderingPipeline.java:849-871).
			//
			// Two doors and not one, because the two shapes of image are read differently: a stitched
			// atlas has its maps stitched to match it, and a texture of its own has them beside it
			// under its own name. Iris keeps the same pair and picks between them by the class of the
			// bound texture (pbr/loader/PBRTextureLoaderRegistry.java:15-16); here the atlas door
			// answers only for an image it was built against, so asking it first picks the same one.
			GpuTextureView served = PbrAtlases.view(this.atlas, material);
			if (served == null) {
				served = PbrTextures.view(this.atlas, material);
			}

			return served == null ? this.flatMaps.get(material).getColorTextureView() : served;
		}

		if (ATLAS.contains(sampler)) {
			// One pixel where the pass has no atlas of its own, which is every cloud and the sky's
			// own disc: a name bound to nothing at all throws at the bind. The sky's textured
			// elements do have one, handed to them per draw by SkyProgram.texture.
			//
			// WHITE and not black, which is Iris's answer for the same case and is the one that
			// leaves a picture. A pack multiplies the atlas into its albedo, so white is the neutral
			// of what it is about to do and black is the value that erases the geometry: three packs
			// of the corpus sample this name from their cloud stage, and with a black pixel behind it
			// their clouds come out invisible. Nothing of the sky reads it - Body Camera declares the
			// name in its skybasic and never samples it - so the sky sees no difference either way.
			return this.atlas == null ? this.white.getColorTextureView() : this.atlas;
		}

		if (OVERLAY.equals(sampler)) {
			// The image the hit flash and the damage tint are read out of, sixteen by sixteen and
			// built once at start up. It is read by a texelFetch at the coordinate the mesh carries,
			// so unlike every other name here the fallback is not a colour that means something: a
			// single texel is out of bounds at that coordinate and what comes back is undefined. The
			// fallback is there because a name declared in the module has to be bound at all, and the
			// branch is unreachable while there is a client to draw from - the game holds this in a
			// field of its own, built with the renderer and never replaced.
			Minecraft minecraft = Minecraft.getInstance();
			GpuTextureView overlay = minecraft == null
					? null : minecraft.gameRenderer.overlayTexture().getTextureView();

			return overlay == null ? this.black.getColorTextureView() : overlay;
		}

		if (LIGHTMAP.equals(sampler)) {
			// One white texel for a piece the game draws at full light, which is the other half of what
			// the vertex head did with the light map names and is read off the same field so that the
			// two cannot part company (PackProgram.Loaded.fullbright). Iris binds its own white pixel
			// here (samplers/IrisSamplers.java:202-206). White is what leaves the piece alone: a pack
			// multiplies its albedo by what it reads out of this image, and every texel of the game's
			// own is darker than one except at the brightest level.
			if (this.loaded.fullbright()) {
				return this.white.getColorTextureView();
			}

			Minecraft minecraft = Minecraft.getInstance();
			GpuTextureView lightmap = minecraft == null ? null : minecraft.gameRenderer.lightmap();

			return lightmap == null ? this.white.getColorTextureView() : lightmap;
		}

		SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);

		// White and not black for a depth that stays a constant, and the reason is the image rather
		// than a taste: what a depth lookup reads is already in the pack's own window, where one is
		// the far plane. Black would put the whole world against the camera. PackPass answers the
		// same way, and the two have to move together.
		return switch (binding.kind()) {
			case COLORTEX -> colortex(binding);
			case DEPTH -> depth(sampler);
			case SHADOW_DEPTH -> shadowDepth(binding.sampler());
			case SHADOW_COLOUR -> shadowColour(binding.index());
			case NOISE -> this.targets.noise();
			// Every geometry program is drawn in the gbuffers stage, the shadow passes included,
			// which is the one thing a texture.STAGE.NAME override needs to know. Mellow moves
			// noisetex there and nowhere else, so this is not a case the chain also covers.
			case PACK_TEXTURE -> packTexture(sampler);
			case DISTANT_DEPTH -> distantDepth();
			default -> this.black.getColorTextureView();
		};
	}

	/** The pack's own file behind a name, or black when it took the name over and nothing was read. */
	private GpuTextureView packTexture(String sampler) {
		ColorTargets.PackBinding supplied = this.targets.packTexture(TextureStage.GBUFFERS, sampler);

		return supplied == null ? this.black.getColorTextureView() : supplied.view();
	}

	/**
	 * The shadow map, or white for the far plane.
	 * <p>
	 * White, and the same white {@link #depth(String)} falls back to, for the same reason: a
	 * {@code shadowtex} lookup is never rewritten, so what is stored is what the pack reads, and the
	 * map stores the forward window where one is the far plane. A shadow lookup that finds nothing
	 * has to say "nothing between here and the light".
	 * <p>
	 * A shadow pass reads white whatever the map holds: the image it would read is an attachment of
	 * the very pass it is drawn in, and sampling an attachment is a thing Vulkan gives no meaning to.
	 */
	private GpuTextureView shadowDepth(String sampler) {
		if (this.pass.shadow()) {
			return this.white.getColorTextureView();
		}

		// shadowtex1 is the map without the translucents and shadowtex0 the map with them. Serving
		// one image to both is what makes a pack's coloured shadow branch dead code: it asks whether
		// a point is occluded in nought and clear in one, and one image can never answer yes. Which
		// of the two the bare "shadow" names depends on the whole list this program declared, so the
		// plan answers rather than the name.
		GpuTextureView map = this.loaded.samplers().withoutTranslucents(sampler)
				? this.shadow.depthWithoutTranslucents()
				: this.shadow.depth();

		return map == null ? this.white.getColorTextureView() : map;
	}

	/** A shadow colour target, on the same two rules as the depth above. */
	private GpuTextureView shadowColour(int index) {
		if (this.pass.shadow()) {
			return this.white.getColorTextureView();
		}

		GpuTextureView view = this.shadow.colour(index);

		return view == null ? this.white.getColorTextureView() : view;
	}

	/**
	 * What a depth sampler reads, which depends on which side of the frame this pass stands.
	 * <p>
	 * The translucent pass gets the opaque world's image. At that point of the frame depthtex0 and
	 * depthtex1 are one depth, the opaque world's, and that image is exactly it; the live depth
	 * cannot be the answer for either of them, being an attachment of this very pass, and sampling an
	 * attachment is a thing Vulkan gives no meaning to. This is what BSL's water fog and refraction
	 * read.
	 * <p>
	 * The solid and cutout passes stay on the constant for those two. They draw before the image of
	 * THIS frame is taken, so the only one in existence at that moment holds the previous frame's,
	 * and handing them that would be the exact shape of picture this project refuses: plausible, and
	 * wrong by one frame of camera movement.
	 * <p>
	 * <strong>depthtex2 is asked before that test and not inside it.</strong> It is the one copy
	 * taken in the middle of the world rather than at the edge of a half:
	 * {@link PackChain#markPreHandDepth} fills it one line before the hand's solid pass is drawn, so
	 * {@code gbuffers_hand}, the program it is taken for, stands on the near side of the test and
	 * would read the far plane if the test came first. No stale image comes in by the same door: the
	 * copy is forgotten at the end of every frame, so a pass drawn BEFORE the moment it is taken
	 * finds nothing and falls through to the paragraphs above.
	 * <p>
	 * <strong>That is a gap against Iris, it is older than this class's third image, and nothing
	 * here pays for it.</strong> Iris binds {@code depthtex2} to a texture that always exists, off
	 * the one table every terrain, gbuffers and shadow program is built with
	 * ({@code samplers/IrisSamplers.java:220-226}, reached from
	 * {@code pipeline/IrisRenderingPipeline.java:380} and {@code :763}), and refills it once a frame
	 * whether a hand is drawn or not, its {@code beginHand} standing ahead of the guards that decide
	 * that ({@code mixin/MixinLevelRenderer.java:279-280} against
	 * {@code pathways/HandRenderer.java:95-98}). A program drawn before that moment reads the copy of
	 * the PREVIOUS frame there and one everywhere here, "nothing in front".
	 * <p>
	 * <strong>It is not a contournement and it is not written up as one.</strong> Nothing in 26.2
	 * forbids taking the copy on every frame the chain runs; what argues against it is cost, since
	 * this backend has to convert the reversed z and {@link PackDepth#takePreHand} is therefore a
	 * full screen draw where Iris moves depth to depth. That is a preference, so it does not make a
	 * divergence admissible, and the honest name for it is an unpaid gap.
	 * <p>
	 * What holds it at that: the same passes read the same far plane on {@code main}, this class
	 * having answered every depth copy with the constant ahead of the deferred stage long before
	 * there was a third image; the range that added the image changed nothing for them. And no pack
	 * of the eight measured reads the name from a program drawn there - the readers are BSL's two
	 * composites, Bliss's deferred, and Sildur's two deferreds, its composite and its final, with no
	 * gbuffers and no shadow program among them; two more files of Bliss declare the sampler and
	 * never fetch it. Iris's own table carries a comment asking whether those programs should be
	 * served the name at all.
	 */
	private GpuTextureView depth(String sampler) {
		if (SamplerPlan.preHandCopy(sampler)) {
			GpuTextureView preHand = this.targets.depth().preHand();
			if (preHand != null) {
				return preHand;
			}
		}

		if (this.pass.afterDeferred()) {
			GpuTextureView opaque = this.targets.depth().opaque();
			if (opaque != null) {
				return opaque;
			}
		}

		return this.white.getColorTextureView();
	}

	/**
	 * What a {@code dhDepthTex} sampler reads, on the same two rules the world's depth follows.
	 * <p>
	 * The translucent passes get the image taken before the far terrain's own water half, which at
	 * that point of the frame is the whole of what has been drawn into it; the solid and cutout
	 * passes stay on the far plane, because the only image in existence at their moment holds the
	 * previous frame's, and no distinction between the three names arrives before the copy with the
	 * water does, which is the composites' and {@link PackPass}'s to serve.
	 * <p>
	 * <strong>That second half is a divergence, written out.</strong> What Iris does: it binds the
	 * live image on every gbuffers and shadow program alike ({@code samplers/IrisSamplers.java:
	 * 109-110}), and by the time an opaque gbuffers program draws, DH's opaque LODs are already in
	 * it. What stops that here: the image a pack reads is a converted copy rather than the live
	 * attachment, and the only copy in existence when the solid passes draw is the previous
	 * frame's, which is the shape of picture this engine refuses by rule. What it costs: an opaque
	 * geometry program reading {@code dhDepthTex} sees no far terrain, where under Iris it sees the
	 * opaque LODs; no program of the eight-pack corpus makes that read, the readers being deferreds
	 * and composites throughout.
	 * <p>
	 * The far plane is also the whole answer while the pack is not drawing the far terrain: the
	 * image is only taken on the frames it really drew, so a session without Distant Horizons, or a
	 * frame its rendering switch is off on, reads white here exactly as it always did, and every
	 * Distant Horizons branch of the pack stays shut.
	 */
	private GpuTextureView distantDepth() {
		if (this.pass.afterDeferred()) {
			GpuTextureView distant = this.targets.depth().distantOpaque();
			if (distant != null) {
				return distant;
			}
		}

		return this.white.getColorTextureView();
	}

	/**
	 * A colour target of the pack, on the half the plan reads it from, or black.
	 * <p>
	 * Black covers two cases and only one of them is temporary. The targets may not be allocated
	 * yet, which is the first frame or two. And the half being read may be a half this very pass is
	 * writing, which happens when the pack asks for a target it does not double: one image cannot be
	 * an attachment and a sampled texture of the same pass, so the read is refused rather than left
	 * to mean whatever the driver decides that frame.
	 */
	private GpuTextureView colortex(SamplerPlan.Binding binding) {
		for (ChainPlan.Attachment attachment : this.extra) {
			if (attachment.target() == binding.index() && attachment.side() == binding.side()) {
				if (this.collisions.add(binding.index())) {
					Vitrail.logger().warn("{} reads {} on the half it writes, so it is answered with "
							+ "one pixel: the pack does not double that target and one image cannot be "
							+ "both an attachment and a texture of one pass", this.path,
							TargetName.canonical(binding.index()));
				}

				return this.black.getColorTextureView();
			}
		}

		GpuTextureView view = this.targets.view(binding.index(), binding.side());

		return view == null ? this.black.getColorTextureView() : view;
	}

	/**
	 * A sampler's name with what it is really bound to, the half included. The half is the thing to
	 * read: a colour target read on the wrong one holds a clear colour, which is a picture that
	 * looks like a feature nobody turned on.
	 */
	private String describe(String sampler) {
		SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);
		if (binding.kind() != SamplerPlan.Kind.COLORTEX) {
			return sampler;
		}

		return sampler + "=" + TargetName.canonical(binding.index()) + " " + binding.side();
	}

	/**
	 * Whether the plan answers this name with an image rather than with one pixel. A colour target
	 * counts even when it is empty at this point of the frame: it is the pack's own image and what
	 * it holds is a question about the order of the frame, not about the binding. A depth sampler
	 * counts only on the translucent pass, where the copy answers it, and depthtex2 on the hand's
	 * solid pass as well, the copy that name reads being taken one line before that pass is drawn.
	 * <p>
	 * The plan's answer and not a frame's, and that is the whole of what it is for: this feeds a line
	 * said once at the load, where no frame has run. A screen too big to allocate a depth image at
	 * leaves the depth names here on the constant and says so at the moment it happens, in
	 * {@link PackDepth}, which is where a reader has to go to know what a frame really did.
	 */
	private boolean readsATexture(String sampler) {
		// Asked of the pack first, on the same order the bind above follows: a name the pack supplied
		// a file for is that file, and whether it is a real image is a question about the file.
		if (material(sampler) == null && PbrMap.named(sampler) != null) {
			return this.targets.packTexture(TextureStage.GBUFFERS, sampler) != null;
		}

		PbrMap material = material(sampler);
		if (material != null) {
			// The session's answer and deliberately not this pass's, which is the one thing here that
			// cannot be asked once. This program may be drawn with several images in one frame, and
			// the atlas field holds none of them yet at the moment this line is printed: the shadow
			// stage prepares its three chunk programs with no image in hand, and the sky, the
			// particles, the weather and the entities are all handed theirs per draw, after the
			// prepare. Asked of the pass, the line printed the answer for whichever image happened to
			// be in the field, once, for the session.
			//
			// So it says what a latched line can say without lying: whether anything fills the name
			// at all. Which atlases really answer is said exactly, one line each, as they are built.
			return PbrAtlases.supplies(material);
		}

		SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);
		SamplerPlan.Kind kind = binding.kind();

		return ATLAS.contains(sampler) || LIGHTMAP.equals(sampler) || OVERLAY.equals(sampler)
				|| kind == SamplerPlan.Kind.COLORTEX
				|| kind == SamplerPlan.Kind.NOISE
				|| (kind == SamplerPlan.Kind.DEPTH && (this.pass.afterDeferred()
						|| (SamplerPlan.preHandCopy(sampler)
								&& this.pass.stage() == RenderStage.HAND_SOLID)))
				// The map exists from the first frame, but a pass that draws it reads its own
				// attachment and is answered with a constant like everything else that collides.
				|| (!this.pass.shadow() && kind == SamplerPlan.Kind.SHADOW_DEPTH
						&& this.shadow.depth() != null)
				|| (!this.pass.shadow() && kind == SamplerPlan.Kind.SHADOW_COLOUR
						&& this.shadow.colour(binding.index()) != null);
	}

	/** A name answered per frame, an image or the far plane, which is not one nothing fills. */
	private boolean readsTheDistantDepth(String sampler) {
		return this.loaded.samplers().binding(sampler).kind() == SamplerPlan.Kind.DISTANT_DEPTH;
	}

	private FilterMode filter(String sampler) {
		if (LIGHTMAP.equals(sampler)) {
			return FilterMode.LINEAR;
		}

		// A colour target is filtered as the chain filters it, LINEAR wherever the format allows it,
		// so that a name reads the same here and one pass later.
		SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);
		if (binding.kind() == SamplerPlan.Kind.COLORTEX) {
			return this.targets.filter(binding.index());
		}

		// The noise image is a continuous field, not a lookup table: a pack derives water normals
		// and cloud shapes from it and counts on the interpolation. Iris binds it LINEAR_REPEAT,
		// and reading it NEAREST shatters every one of those surfaces into facets. A shadow colour
		// is continuous in the same way, carrying the light that came through glass and water
		// across a penumbra, and both OptiFine and Iris filter it linearly.
		//
		// The shadow DEPTH is LINEAR too, and the reason that used to be given here for keeping it
		// NEAREST was wrong: where the compare mode is on, a sampler averages the RESULTS of the
		// four tests and never the depths, so nothing is ever compared against a surface that exists
		// nowhere. Iris filters this LINEAR whatever the pack asks, its SamplingSettings starting at
		// nearest=false, and adds GL_COMPARE_REF_TO_TEXTURE on top only where the pack writes
		// shadowHardwareFiltering. The manual PCF loops packs write are the whole point either way,
		// since every tap of such a loop rides on this filter. It has to match PackPass, which binds
		// the same name for the full screen passes: the two halves of one frame reading one map
		// through two filters is a difference nothing would ever explain.
		return switch (binding.kind()) {
			case NOISE, SHADOW_COLOUR, SHADOW_DEPTH -> FilterMode.LINEAR;
			default -> FilterMode.NEAREST;
		};
	}

	/**
	 * Said once, and grouped by what it costs the picture. Some names are constants, every sampler
	 * but two reads one pixel, a pass that wanted an alpha test may not have got one, and a fragment
	 * stage may declare more outputs than the one attachment Sodium's pass carries. None of them
	 * shows as an error and all of them change the image.
	 */
	private void announce() {
		if (this.announced) {
			return;
		}

		this.announced = true;
		TranslatedUnit fragment = this.loaded.program().stages().get(ProgramStage.FRAGMENT);
		int outputs = fragment.notes().fragmentOutputs();
		// The render stage is in the line rather than left silent, because nothing else can say it:
		// what a pack does with it is a branch inside its own code, so a pass told the wrong one
		// draws something that looks like a picture, and this module has no harness to catch that.
		Vitrail.logger().info("Drawing the {} {} pass with {} of {} at render stage {}, {} uniforms "
				+ "and {} samplers", this.pass.name(), this.pass.family(), this.path,
				this.loaded.packName(), this.pass.stage(), this.loaded.program().uniforms().size(),
				this.samplers.size());

		// A cutout stage without its discard draws a leaf as a cube, which reads as the pack being
		// wrong rather than as a translation that could not place a statement.
		AlphaTest alphaTest = this.loaded.alphaTest();
		if (alphaTest.tests() && fragment.notes().alphaEpilogue() == 0) {
			Vitrail.logger().warn("This pass discards at {} {} and the program could not be given the "
					+ "test, so nothing beyond the pack's own discards is thrown away",
					alphaTest.function(), alphaTest.reference());
		}

		// Split by what the mesh really answers. Only names the mesh has no element for are a gap;
		// the chunk mesh now answers four of them and the entity mesh two, while the glint's answers
		// none, which is why this comes off the PASS rather than off the family.
		List<String> constants = this.loaded.program().synthesized().keySet().stream()
				.filter(name -> !this.pass.answered().contains(name))
				.toList();
		if (!constants.isEmpty()) {
			Vitrail.logger().warn("The {} mesh carries none of these, so they are answered with a "
					+ "constant and what this program computes from them is wrong: {}",
					this.pass.family(), constants);
		}

		List<String> real = this.samplers.stream()
				.filter(this::readsATexture)
				.map(this::describe)
				.toList();
		// Kept out of the line below rather than counted in it, because that line is about a gap and
		// this is not one: these names are filled exactly on the frames the pack draws the far
		// terrain, and PackChain says once for the whole chain what they carry.
		List<String> distant = this.samplers.stream().filter(this::readsTheDistantDepth).toList();
		List<String> flat = this.samplers.stream()
				.filter(name -> !readsATexture(name) && !readsTheDistantDepth(name))
				.toList();
		Vitrail.logger().info("{} samplers of this program read a real texture: {}", real.size(), real);
		if (!distant.isEmpty()) {
			Vitrail.logger().info("{} read the far terrain's own depth on the frames this pack draws "
					+ "it, and the far plane on the rest: {}", distant.size(), distant);
		}

		if (!flat.isEmpty()) {
			// What is left is what nothing fills for this pass: the shadow map, the depth on the
			// passes that draw before the copy of this frame is taken, and the two material maps
			// wherever the RESOURCE pack ships no file beside the sprites of this pass's atlas. The
			// last of the three is the one a reader can act on, and it is not a shader pack's doing.
			Vitrail.logger().warn("{} read one pixel, because nothing fills them yet: {}",
					flat.size(), flat);
		}

		if (this.pass.shadow()) {
			Vitrail.logger().info("It draws into the shadow map, {}x{}, storing the forward depth "
					+ "window, and into {}", this.shadow.resolution(), this.shadow.resolution(),
					this.shadowColours.stream()
							.map(index -> "shadowcolor" + index)
							.collect(Collectors.joining(", ")));
			if (outputs > this.shadowColours.size()) {
				Vitrail.logger().warn("{} declares {} fragment outputs and its draw buffers name {} "
						+ "of the light's colour targets, so the outputs past the {} are written "
						+ "nowhere", this.path, outputs, this.shadowColours.size(),
						this.shadowColours.size());
			}

			// Said apart, because it is a different thing and the count above cannot show it: a
			// directive naming a target this engine does not allocate is thrown away WHOLE, so the
			// program draws into the pair instead of into what it asked for.
			List<Integer> declared = fragment.drawBuffers();
			if (declared.stream().anyMatch(index -> index >= ShadowTargets.COLOURS)) {
				Vitrail.logger().warn("{} asks for {}, and this engine draws the light into {} colour "
						+ "targets, so the whole list is set aside and the first {} are drawn into "
						+ "instead, as the reference does with it", this.path, declared,
						ShadowTargets.COLOURS, this.shadowColours.size());
			}
		} else if (this.ownsFirst) {
			// Nought included, and the log says the sides because they are the whole fix: a write on
			// the half the composites do not read is geometry that vanishes without a word from
			// anyone.
			Vitrail.logger().info("Its draw buffers all reach the pack's own targets, nought "
					+ "included: {}",
					this.extra.stream()
							.map(one -> TargetName.canonical(one.target()) + " " + one.side())
							.toList());
			if (this.covers) {
				// The pair to read this against is the seed's own line: this one says the mask is
				// written, that one says it is honoured.
				Vitrail.logger().info("It also writes the coverage mask, so nothing paints the game's "
						+ "own picture back over what this pass wrote");
			}
		} else if (this.extra.isEmpty()) {
			// Draw buffer nought is not named here on purpose: it goes to the game's own target,
			// which is where it has always gone and where the seed reads it back from.
			if (outputs > 1) {
				Vitrail.logger().warn("{} declares {} fragment outputs and writes one draw buffer, so "
						+ "all but the first are written nowhere", this.path, outputs);
			}
		} else {
			Vitrail.logger().info("Its other draw buffers reach the pack's own targets: {}",
					this.extra.stream()
							.map(one -> TargetName.canonical(one.target()) + " " + one.side())
							.toList());
		}

		// Said outside the chain above, and it is a second line about one cause rather than a line
		// about a second cause: what lands here asked for a mask and was not given one, which the
		// constructor has already named. What this adds is the half the warning does not carry, and
		// it is the half a blending pass pays twice: the blend is made against the game's target
		// instead of against the world, so a pixel of alpha under one is tinted by a clear rather
		// than by what stands behind it, and a row that writes no depth is discarded outright by the
		// seed's cut. Neither shows up as an error and neither is visible in a silent log.
		if (this.demoted) {
			Vitrail.logger().info("It blends and draw buffer nought still goes to the game's own "
					+ "target: this pass is drawn before the scene seed and nothing marks the pixels "
					+ "it blends onto, so the seed would paint the game's picture back over them");
		}

		// Said because nothing on screen would. A pack declaring sampler2DShadow asks the hardware
		// to compare and hand back a filtered fraction; blaze3d's GpuSampler carries no comparison
		// at all, so the translation makes it instead: four texels gathered, four steps, and the
		// same bilinear blend the hardware would have applied. Nothing of the shape is lost. What
		// it costs is the gather and the arithmetic, on a lookup a PCF loop makes many times.
		//
		// Asked of the notes and not of the samplers: by the time a sampler is one of those, its
		// type has been rewritten to the ordinary one and there is nothing left to recognise.
		List<String> compared = this.loaded.program().stages().values().stream()
				.flatMap(unit -> unit.notes().comparedSamplers().stream())
				.distinct()
				.toList();
		if (!compared.isEmpty()) {
			Vitrail.logger().info("{} asked the hardware to compare {}, which this backend cannot "
					+ "bind, so the comparison is made in the shader over the four texels the "
					+ "hardware would have blended", this.path, compared);
		}

		PackValues.Gaps gaps = this.values.classify(this.uniforms.unsupplied());
		if (!gaps.engine().isEmpty()) {
			Vitrail.logger().warn("{} reads {} values written as zeroes: {}", this.path,
					gaps.engine().size(), gaps.engine());
		}

		// Underneath that, and it is not the same list: these are members a source really answered,
		// with something that is not the value. They count as supplied wherever a count is taken,
		// which is the whole reason for naming them, and a full screen pass has named them since the
		// block existed while a geometry pass named none.
		//
		// One list for every pass, and that is a change this lot earned: the names that were a
		// placeholder on the entity mesh alone are made on the mesh now, each out of an element of
		// its own, so no name is a stand-in in one pass and a value in another.
		PackValues.standIns(this.loaded.program().uniforms().stream()
						.map(TranslatedUnit.Uniform::name)
						.toList())
				.forEach((reason, names) -> Vitrail.logger().warn("{} reads {} values answered with "
						+ "a stand-in rather than with the value, {}, which count as supplied "
						+ "everywhere else, because {}", this.path, names.size(), names, reason));
	}

	private static TextureTarget release(TextureTarget target) {
		if (target != null) {
			target.destroyBuffers();
		}

		return null;
	}
}
