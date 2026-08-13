package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.target.ChainPlan;
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
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
	 * @param covers       whether this pass is one of the opaque halves that write the mask the scene
	 *                     seed is cut with
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
	 */
	record Pass(String family, String name, String namespace, Set<String> answered, boolean shadow,
			Optional<BlendFunction> blend, boolean covers, boolean claimed, boolean afterDeferred,
			PrimitiveTopology topology, boolean cull, DepthStencilState depth, RenderStage stage,
			BindGroupLayout bindings) {

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

		/** The mask saying where this pass drew, which is ours and not in the pack's draw buffers. */
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

	/** Whether draw buffer nought goes to the pack rather than to the game. The constructor says why. */
	private final boolean ownsFirst;

	/** Whether this pass writes the mask the scene seed is cut with. Opaque halves only. */
	private final boolean covers;

	/**
	 * Whether this pass blends and lost draw buffer nought all the same, which is the one demotion
	 * that is a decision rather than the absence of anywhere to send it. Kept as a field because it
	 * is answered where {@code owns} is in scope and said where it is not.
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
	 * Whether this pass draws the mesh that carries the overlay, which is the entity one and no
	 * other of ours.
	 * <p>
	 * Iris asks it in two halves: the three identifiers are rewritten onto elements where the mesh
	 * carries the overlay or is text
	 * ({@code pipeline/transform/transformer/VanillaTransformer.java:20-25}), the overlay colour
	 * where it carries the overlay and is not text ({@code VanillaCoreTransformer.java:21-26}).
	 * Every pass this engine serves that reads these names draws the entity mesh, so the one
	 * question covers both halves today; a text family would have to join the split. The same names
	 * are a placeholder here and a real answer on the terrain, and only a pass that knows which
	 * mesh it binds can tell the log which it is.
	 */
	private final boolean entityMesh;

	private MappableRingBuffer block;
	private TextureTarget black;
	private TextureTarget white;
	private TextureTarget grey;
	private GpuTextureView atlas;

	/** The matrix the game pushed for this pass, or null for the frame's camera. */
	private Matrix4fc modelView;

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
		this.entityMesh = DefaultVertexFormat.ENTITY.equals(format);
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
		// The two opaque halves used to keep it on the game's target and reach the pack's colortex
		// through the seed, which was one conversion too many. What a gbuffers_terrain puts in draw
		// buffer nought is not a colour but whatever the pack packed there, and the game's target is
		// eight bits a channel: Bliss packs two values into each channel of a sixteen bit colortex1,
		// and the trip through the game's target quantised its albedo away entirely, leaving the
		// encoded normal to be read back as the albedo. So the opaque halves write their target
		// outright, and the coverage mask below is what keeps the seed off the pixels they wrote.
		//
		// Everything that lands back on the game's target, and it is a list of reasons rather than a
		// count. When the chain is not running there is no final to bring a colortex to the screen,
		// so anything sent there would simply vanish; when the plan had no answer there is nowhere
		// else to send it; when a half that ASKED for a mask could not be given one, the seed would
		// repaint the whole target and take the terrain with it; when a half never asked for one,
		// which is the entities and the opaque particles, the seed carries it in by design; and the
		// last is what the statement after next is about, a blending pass drawn before the seed with
		// nothing marking the pixels it blends onto. Either way the pass draws where Sodium would
		// have, which is also what keeps the pipeline's one state the pass's.
		//
		// Whether the mask was really written is the translation's answer and not a second reading
		// of the same rule: the stage that could not be given one says so, and an engine that
		// decided for itself would be attaching an image nothing fills.
		boolean owns = chainRuns && !writes.isEmpty();
		this.covers = owns && pass.covers() && notes.coverage() == 1 && writes.size() <= outputs
				&& outputs < ColorTargetState.MAX_COLOR_TARGETS;
		// A blending pass may take draw buffer nought outright only where the seed will not repaint
		// the pixels it blended onto, and blending is not that question: it was read as though it
		// were, and every family answered the same either way until the hand arrived.
		//
		// Three ways the seed is kept off, and the hand has none of them. The pass is drawn AFTER
		// the seed, which is the world's water, the weather, the clouds, the translucent particles,
		// the blending half of the entities and the hand's own water pass. It writes the mask
		// itself, which is what covers above answers. Or the family draws opaque pieces of its own
		// over the same pixels, which is the sky alone and which claimed carries, with the two
		// places it does not hold named where that field is declared.
		//
		// The hand's solid pass has none of the three, and no mask could give it the second: the
		// seed's cut asks whether the depth moved closer since the pack's geometry was finished with
		// it, and the hand is drawn with its clip depth squeezed into the band 0.4375 to 0.5625
		// (render/HandDraw.java:93,362), which is not the depth of anything it stands in front of.
		// A hand row that WRITES depth therefore answers that question yes at every pixel it drew,
		// whatever mask is written there, so the seed repaints those pixels and a hand that owned
		// draw buffer nought would have its colour painted over by a frame that never drew it.
		//
		// WHAT THIS COSTS AGAINST IRIS, AND IT IS NOT FORCED. Iris binds every gbuffers program to a
		// framebuffer over the pack's own declared draw buffers, the hand included
		// (pipeline/IrisRenderingPipeline.java:686-687; the four keys its hand passes ask for are at
		// pipeline/IrisPipelines.java:192,204,216), so its hand colour is written to the pack's
		// target and never leaves it. Here it goes to the game's target and reaches the pack through
		// the seed, which costs three things. The trip through eight bits a channel, which is the
		// quantisation the Bliss paragraph above measured on the terrain. The blend, which now
		// happens against the game's target: with the chain running the world is in the PACK's
		// target, so a hand pixel of alpha under one blends against the clear rather than against
		// what stands behind it. And a row that writes no depth with nothing of its own pass writing
		// depth under it - a held banner's pattern is the reachable one (BANNER_PATTERN,
		// RenderPipelines.java:318) - which the cut then answers no for and discards where the mask
		// is set.
		//
		// WHAT WOULD COST NOTHING, and it is not done here: draw the hand's solid pass BETWEEN the
		// seed and the deferred stage. Iris's constraint is only that the hand precede the deferreds
		// (mixin/MixinLevelRenderer.java:280, deferredRenderer.renderAll at
		// pipeline/IrisRenderingPipeline.java:1073), and the seed is ours and has no counterpart
		// there, so that position keeps Iris's moment AND lets the pack own draw buffer nought. It
		// is a change to the order of the frame rather than to this statement, so it is named here
		// and not taken: what this file can decide is where nought goes given when the pass is drawn.
		this.ownsFirst = owns && (this.covers || pass.afterDeferred()
				|| (pass.blended() && pass.claimed()));
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
			// One attachment, shadowcolor0, whatever the pack's draw buffers say. A shadow program
			// writing more than that has its later outputs written nowhere, which announce() says.
			// The format is the map's own and not a constant: Mellow asks for R8 there, and a state
			// naming four channels against a one channel attachment is the pipeline refusing to bind.
			builder.withColorTargetState(0, state(targets.shadowFormat()));
		} else {
			for (int slot = 0; slot < this.slots.size(); slot++) {
				Slot one = this.slots.get(slot);
				switch (one.bound()) {
					case UNUSED -> builder.withUnusedColorTargetState(slot);
					// The mask is written outright and never blended, whatever the pack asked for its
					// own targets: a fragment either covered this pixel or it did not.
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
		return prepare(device, atlas, null, null, null);
	}

	/**
	 * The same, for a pass the game draws with a model view, a colour or a volume of its own.
	 *
	 * @param modelView  the matrix the game pushed for this pass, or null for the frame's camera.
	 *                   Kept until the block is written rather than applied here: it is one value of
	 *                   the block among the rest, and the block is written a few lines below
	 * @param projection the volume this pass is drawn in, or null for the frame's. The hand is the
	 *                   one family that sets it, and it is not a nudge of the frame's but a matrix
	 *                   of its own; {@link dev.vitrail.uniform.ViewSource#passProjection} says why
	 */
	RenderPipeline prepare(GpuDevice device, GpuTextureView atlas, Matrix4fc modelView,
			Vector4fc passColour, Matrix4fc projection) {
		this.modelView = modelView;
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
			Vitrail.logger().info("The {} pass records its first draw with {}",
					this.pass.name(), this.path);
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

		if (LIGHTMAP.equals(name)) {
			return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
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
		GpuTextureView colour = this.shadow.colour(0);
		GpuTextureView depth = this.shadow.depth();
		if (colour == null || depth == null) {
			return null;
		}

		return RenderPassDescriptor.create(() -> SHADOW_LABEL)
				.withColorAttachment(colour)
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
		this.cleared = false;
	}

	private int blockBytes() {
		return Math.max(16, this.uniforms.size());
	}

	private void writeBlock() {
		// Before the block and never once for the run: the two conventions alternate inside one
		// frame now that the shadow map is ours and the game's targets are not, and what a vertex
		// stage does with its clip depth on the way out comes from this pair.
		this.values.convention(this.pass.shadow() ? ClipSpace.FORWARD : ClipSpace.REVERSED);
		this.values.modelView(this.modelView);
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
			this.cleared = false;
		}

		if (!this.cleared) {
			this.cleared = true;
			CommandEncoder encoder = device.createCommandEncoder();
			encoder.clearColorTexture(this.black.getColorTexture(), OPAQUE_BLACK);
			encoder.clearColorTexture(this.white.getColorTexture(), OPAQUE_WHITE);
			encoder.clearColorTexture(this.grey.getColorTexture(), MID_GREY);
		}
	}

	private GpuTextureView view(String sampler) {
		if (ATLAS.contains(sampler)) {
			// One pixel where the pass has no atlas of its own, which is every pass of the sky and
			// every cloud: a name bound to nothing at all throws at the bind.
			//
			// WHITE and not black, which is Iris's answer for the same case and is the one that
			// leaves a picture. A pack multiplies the atlas into its albedo, so white is the neutral
			// of what it is about to do and black is the value that erases the geometry: three packs
			// of the corpus sample this name from their cloud stage, and with a black pixel behind it
			// their clouds come out invisible. Nothing of the sky reads it - Body Camera declares the
			// name in its skybasic and never samples it - so the sky sees no difference either way.
			return this.atlas == null ? this.white.getColorTextureView() : this.atlas;
		}

		if (LIGHTMAP.equals(sampler)) {
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
	 * of the eight measured reads the name from a program drawn there - the readers are BSL's
	 * composites, Bliss's composite, deferred and final, and Sildur's deferreds, composite and
	 * final, with no gbuffers and no shadow program among them. Iris's own table carries a comment
	 * asking whether those programs should be served it at all.
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
		SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);
		SamplerPlan.Kind kind = binding.kind();

		return ATLAS.contains(sampler) || LIGHTMAP.equals(sampler)
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
		// the chunk mesh now answers four of them and the entity mesh answers none.
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
		List<String> flat = this.samplers.stream().filter(name -> !readsATexture(name)).toList();
		Vitrail.logger().info("{} samplers of this program read a real texture: {}", real.size(), real);
		if (!flat.isEmpty()) {
			// What is left is what nothing draws yet: the shadow map, and the depth on the passes
			// that draw before the copy of this frame is taken.
			Vitrail.logger().warn("{} read one pixel, because nothing fills them yet: {}",
					flat.size(), flat);
		}

		if (this.pass.shadow()) {
			Vitrail.logger().info("It draws into the shadow map, {}x{}, storing the forward depth "
					+ "window, and into shadowcolor0", this.shadow.resolution(),
					this.shadow.resolution());
			if (outputs > 1) {
				Vitrail.logger().warn("{} declares {} fragment outputs and this engine gives the "
						+ "shadow pass one, so all but the first are written nowhere", this.path,
						outputs);
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

		// Said outside the chain above, because neither branch that lands here names draw buffer
		// nought and this is the one case where its going to the game's target is a decision rather
		// than the ordinary answer. What it costs is a trip through eight bits a channel, a blend
		// against the game's target instead of the world, and the rows that write no depth being
		// discarded by the seed's cut - none of which a silent log would show.
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
		// The mesh is handed in because it decides the list. A name Iris reads off an element is a
		// placeholder only where that element would have been, so the terrain hears about the names
		// it shares with a composite and nothing else, and a pass drawn from the entity mesh hears
		// about the identifiers and the overlay colour as well.
		PackValues.standIns(this.loaded.program().uniforms().stream()
						.map(TranslatedUnit.Uniform::name)
						.toList(), this.entityMesh)
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
