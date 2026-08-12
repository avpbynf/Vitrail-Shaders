package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.program.ProgramFallbacks;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import org.joml.Matrix4fc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One program the pack draws a piece of the game's own entity geometry with, in place of the shader
 * the game would have used.
 * <p>
 * The body of it is {@link GeometryProgram}'s, as the terrain's and the sky's are; what this class
 * holds is what an entity answers differently, and there are four things.
 * <p>
 * <strong>The pipeline states are read off the game's own pipeline rather than tabulated.</strong>
 * The sky has to write its blends and its topologies out by hand, because the pass is opened by the
 * game and this engine only swaps what goes into it; here the door is handed the game's
 * {@code RenderPipeline} for the very draw being served, so the blend, the depth window, the culling
 * and the topology are asked of it. Nothing about them can drift out of step with the game, which
 * for the culling matters more than it looks: the entity pipelines split almost evenly on it, and a
 * cape drawn with the wrong answer is either a cape with no inside or a cape drawn twice.
 * <p>
 * <strong>Both halves take their first draw buffer in the pack's own targets, and they get there by
 * two different routes.</strong> The blending half is drawn after the scene seed has run, onto a
 * target that already holds the composed world, so it takes that buffer outright and owes nothing:
 * {@code GeometryProgram} ties that to the pass blending at all, and the blend it blends with is
 * the game's own for the pipeline being served. The writing half is drawn before the seed, so it
 * takes the buffer by writing the coverage mask, which carries the depth its fragment left and is
 * what the seed is cut against; {@link EntityDraw.Element#covers} is where that is decided and why.
 * <p>
 * <strong>That is what the trip through the game's target used to cost, and it is Bliss's
 * albedo.</strong> The writing half was in the position the opaque chunk passes were in before the
 * mask existed: its colour went to the game's target and reached the pack through the seed, eight
 * bits a channel. A pack packing two values into each channel of a sixteen bit target loses the
 * first of them there, and reads the second back as the albedo. What it always kept is every other
 * draw buffer, which is where a pack keeps the normal, the specular map and the material it lights
 * an entity by, and which is the whole of what {@code the entities still come from the game} meant.
 * <p>
 * <strong>Its namespace is ours and has no {@code sodium} in it</strong>, for the reason
 * {@link SkyProgram} gives: the word is what makes Sodium's mixin push twenty bytes of region offset
 * into the layout, and an entity mesh has no region.
 * <p>
 * <strong>It tells a mob it is drawing nothing in particular, and a block entity that it is drawing
 * a block entity</strong>, and the first half reads as an oversight until Iris is read. What a pack
 * gets under {@code renderStage} is the ordinal of the phase Iris has posed
 * ({@code uniforms/CommonUniforms.java:116}), and the two halves of this family are not in the same
 * position there.
 * <p>
 * The call that would pose the ENTITY phase ({@code layer/GbufferPrograms.java:27}) hangs off
 * {@code EntityRenderStateShard}, which nothing there constructs and nothing installs: it survives
 * as an unused import of {@code mixin/entity_render_context/MixinEntityRenderDispatcher.java:8} and
 * nowhere else. Its one live pose is in the shadow map ({@code shadows/ShadowRenderer.java:521}),
 * which this engine does not draw entities into yet. So a pack branching on
 * {@code MC_RENDER_STAGE_ENTITIES} never takes that branch under Iris.
 * <p>
 * The BLOCK ENTITY phase is the opposite case, and <strong>the asymmetry between the two is the
 * whole of the evidence</strong>. {@code BlockEntityRenderStateShard}
 * ({@code layer/BlockEntityRenderStateShard.java:11} to {@code layer/GbufferPrograms.java:59}) is
 * installed at four sites, {@code mixin/entity_render_context/MixinModelStorageTrigger.java:39,48,57}
 * and {@code MixinGlyphRenderType.java:19}, where the entity one is installed at none. That is what
 * settles it, and it is worth saying what does NOT: it is tempting to add that the phase is the only
 * road to Iris's block programs, and it is not. Three rows of its own table reach
 * {@code ProgramId.Block} with no phase test at all, the moving block at
 * {@code pipeline/IrisPipelines.java:30} and the end gateway and end portal at {@code :59} and
 * {@code :68}. The phase is what the entity pipelines branch on
 * ({@code pipeline/programs/ShaderOverrides.java:42-44}, read at
 * {@code pipeline/IrisPipelines.java:150,160,193,205,217}), which is a narrower claim and the true
 * one.
 */
final class EntityProgram implements DumpedProgram {

	/** What the log calls this geometry, one word in the middle of a sentence. */
	private static final String FAMILY = "entity";

	/** Ours, and deliberately without the word that turns push constants on. See the class comment. */
	private static final String NAMESPACE = Vitrail.MOD_ID;

	/**
	 * The names the mesh really carries under the spelling a pack writes, which is none of them.
	 * <p>
	 * Empty and not an oversight: {@code EntityVertex} answers every fixed function name out of the
	 * six elements of the format, and there is no room in those six for anything a pack declares for
	 * itself. {@code mc_Entity} is the one worth naming, since the chunk mesh does carry it: an
	 * entity is not a block state and has no id to travel on, so a pack branching on it here is
	 * branching on a constant, and the log says so at every load.
	 * <p>
	 * The glint's mesh answers the same, and with room to spare: it carries two elements, and
	 * {@code GlintVertex} makes every other name out of a constant.
	 */
	private static final Set<String> ANSWERED = Set.of();

	private final GeometryProgram body;

	private EntityProgram(GeometryProgram body) {
		this.body = body;
	}

	/**
	 * Prepares one already read program to be drawn as one piece of the game's entity geometry.
	 *
	 * @param loaded the pack's own program, read and translated for the threshold this piece discards
	 *               at
	 * @param writes where this piece's outputs belong, in draw buffer order and each on the side the
	 *               schedule gives it, the first one INCLUDED whichever half this is.
	 *               {@code GeometryProgram} is what decides where that first one really goes: the
	 *               pack's target for a piece drawn after the seed and for one that writes the mask,
	 *               which between them is every piece this door serves, and the game's for a piece
	 *               that asked for a mask and could not be given one by the translation.
	 *               {@link EntityDraw} settles the list and refuses a whole half where the first one
	 *               could not reach the pack
	 */
	static EntityProgram of(PackProgram.Loaded loaded, EntityDraw.Element element, PackValues values,
			int load, List<ChainPlan.Attachment> writes, TargetPlan chainTargets,
			ColorTargets targets, boolean chainRuns) {
		// Bound again against the chain's own plan, for the reason the terrain and the sky are: what
		// the load bound them against is a plan without the user's pass filter. The step is the
		// PIECE's, which is where the two halves part company: the writing half is drawn in the
		// game's opaque feature phase, which stands between the opaque chunks and the deferred stage,
		// and the blending half after that stage has run. A half bound against the other one's step
		// reads the side of every target the chain is about to write rather than the one it wrote,
		// which is a frame of lag nothing anywhere would report.
		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		// A shadow row is bound at the step of the half its geometry is drawn in, which is the early
		// one: the map is filled before the deferred stage of the frame it is read by, and it writes
		// shadowcolor rather than any colortex, so the side it reads is the only thing the step
		// decides for it.
		boolean shadow = element.shadow();
		// Asked of the row and never of the blend, which is Element.afterStage: a hand pass is drawn
		// wholly on one side whatever its rows blend, and the arm blends. Read off the blend here,
		// every hand row whose blend disagreed with its pass was bound against the wrong step, and
		// the two tables were wrong in opposite directions: a blending row of the solid pass against
		// a stage its pass runs before, a row of the water pass that does not blend against one its
		// pass runs after.
		boolean afterStage = element.afterStage();
		PackProgram.Loaded bound = loaded.rebind(chainTargets, afterStage
				? chainTargets.schedule().stepAfterDeferred(servedBy)
				: chainTargets.schedule().step(servedBy));

		RenderPipeline game = element.pipeline();

		return new EntityProgram(new GeometryProgram(new GeometryProgram.Pass(FAMILY,
				element.element(), NAMESPACE, ANSWERED, shadow,
				// Nothing blends into the shadow map, whatever the pipeline the row was made from
				// says. Every shadow program of Iris is declared with BlendModeOverride.OFF
				// (shaderpack/loading/ProgramId.java:13-19), and the reason is what a map is for:
				// what it wants of a translucent surface is the depth that surface stands at, not
				// that depth mixed with the one behind it.
				//
				// Off the map, the game's own blending for this pipeline, unless the name asked for
				// is one Iris gives a default of its own; the pack's own blend directive still
				// displaces either, GeometryProgram looking that one up under the file that really
				// served the piece. Only the eyes have one, and for them the game's translucent
				// blend is the wrong answer rather than a duller one: an eye is drawn additively or
				// it is a decal. The two questions are asked in this order and not folded together:
				// the map's refusal is about the target, the lookup is about the program name.
				shadow ? Optional.<BlendFunction>empty()
						: BlendFunctions.of(ProgramFallbacks.blendOverride(element.program()),
								game.getColorTargetState().blendFunction()),
				// covers: the mask on every piece drawn before the seed and on no other, which is
				// Element.covers and is answered there, beside the question of which side of the
				// stage a piece is drawn on. It is what takes draw buffer nought of those pieces off
				// the game's target: the mask carries the depth the fragment left, so the seed reads
				// a mob's own depth back at every pixel of it and leaves the pixel alone.
				//
				// The two are still not one decision with draw buffer nought: GeometryProgram asks
				// the side of the stage first, so a pass can keep nought on the game's target for a
				// reason that has nothing to do with a mask.
				//
				// claimed: no opaque piece of this family stands over these pixels, which is true of
				// every piece here including the arm. The sky's blending pieces have the disc and the
				// horizon cone under them and are the one family that answers otherwise; what earns
				// the hand's solid pass its own draw buffer nought is the mask it writes, not a
				// sibling.
				element.covers(), false,
				// afterDeferred: which side of the stage this piece is drawn on, which decides the
				// half of every target it reads and whether a depth sampler may be answered with the
				// opaque world's image. Asked of the row, Element.afterStage saying why that is not
				// the same question as what the pipeline blends. A shadow row is on neither side of a
				// stage that has not run when it draws, and answers the early one.
				afterStage,
				// The piece's own on both sides of the map, which is what Iris ends up drawing with
				// too, and NOT the chunk passes' answer: those really do drop the cull, and that is
				// about a wall meshed one side only, where a mob is closed geometry with no such back
				// to leak through.
				//
				// Iris looks like it says otherwise and does not. It brackets its shadow stage with
				// _disableCull() and _enableCull() (shadows/ShadowRenderer.java:501 and :615), and
				// that bracket is overridden on the first pipeline change either way. Inside the
				// branch for the passes it opens itself it re-applies the pipeline's own cull in so
				// many words (mixin/MixinGlCommandEncoder.java:137-140, under the pipeline memo at
				// :117); and it never reaches that branch for a geometry draw, its hook being an
				// @Inject at the HEAD of trySetup that only cancels there (:92, :101), so the vanilla
				// setup runs to the end and applies the same thing. The second leg is read on 26.2's
				// GL encoder rather than on the one Iris runs against, which is the weaker half of
				// this; the first is read in Iris itself.
				game.getPrimitiveTopology(), game.isCull(),
				// The piece's own, TURNED ROUND rather than replaced. See intoMap.
				shadow ? intoMap(game.getDepthStencilState()) : game.getDepthStencilState(),
				// The piece's own, and the halves answer differently: NONE for a mob, which is
				// Iris's answer rather than a reading of what the pass is, and BLOCK_ENTITIES for a
				// block entity, which Iris really does pose. The class comment has the file:line.
				element.stage(),
				// Nothing of the game's bound beside the mesh: an entity pipeline carries samplers
				// and transforms, and this program declares none of their names.
				null),
				bound, values, load, element.format(), writes, targets, chainRuns));
	}

	/**
	 * The same depth state, turned round for the window the shadow map stores.
	 * <p>
	 * <strong>Turned round and not replaced, because the state carries an intention that is not
	 * this engine's to overrule.</strong> Two rows of the table are not a plain depth write:
	 * {@code ARMOR_DECAL_CUTOUT_NO_CULL} tests {@code EQUAL} and writes no depth
	 * ({@code RenderPipelines.java:222}), and {@code BANNER_PATTERN} tests
	 * {@code GREATER_THAN_OR_EQUAL} and writes none either ({@code :319}). Given one forced state
	 * both become occluders in the map, which is a decal and a banner's pattern casting a shadow of
	 * their own over the surface they are lying on. Iris has nothing to turn round: its shadow
	 * projection is a plain forward ortho built the same way as the camera's
	 * ({@code shadows/ShadowMatrices.java:21-24}), so its map runs in the same direction as its
	 * scene and the vanilla state stands as written.
	 * <p>
	 * <strong>The conversion is three things and not one.</strong> The game rasterises under a
	 * REVERSED Z, nought at the far plane, and {@link ShadowTargets} stores the forward window, one
	 * at the far plane. So the comparison is MIRRORED, greater becoming lesser and lesser greater,
	 * the two windows running in opposite directions; the write is KEPT exactly, which is what stops
	 * a row that writes no depth from becoming an occluder; and the depth bias is NEGATED, for the
	 * same reason as the comparison, a bias nudging a surface towards the viewer and the sign of
	 * that nudge depending on which way the window runs.
	 */
	private static DepthStencilState intoMap(DepthStencilState state) {
		return new DepthStencilState(mirrored(state.depthTest()), state.writeDepth(),
				-state.depthBiasScaleFactor(), -state.depthBiasConstant());
	}

	/**
	 * A comparison read in the opposite window. {@code EQUAL} and {@code NOT_EQUAL} are their own
	 * mirrors, and the two constants have nothing to mirror.
	 */
	private static CompareOp mirrored(CompareOp op) {
		return switch (op) {
			case LESS_THAN -> CompareOp.GREATER_THAN;
			case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
			case GREATER_THAN -> CompareOp.LESS_THAN;
			case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
			default -> op;
		};
	}

	/**
	 * The pipeline this entity piece is drawn with, compiled before this engine opens its own pass.
	 *
	 * @param modelView  the matrix this pass is drawn under, which is the frame's camera for every
	 *                   piece but the hand's and null for those. It is what the derived uniforms are
	 *                   built from and no longer what places the geometry: {@code EntityDraw.Element}
	 *                   says why the depth nudge of a render type is not in it
	 * @param bob        the bob that placed this piece, or null for the frame's. Only the hand passes
	 *                   one, and it must: the projection above is built with the walk bob and the
	 *                   damage tilt alone, so the frame's four would publish a spin the arm was never
	 *                   drawn with
	 * @param projection the volume the piece is drawn in, or null for the frame's. Only the hand
	 *                   passes one, and it must: it is drawn under the head-up field of view and a
	 *                   clip depth squeezed to an eighth, which is a matrix of its own rather than a
	 *                   nudge of the frame's
	 * @see GeometryProgram#prepare
	 */
	RenderPipeline prepare(GpuDevice device, Matrix4fc modelView, Matrix4fc bob,
			Matrix4fc projection) {
		return this.body.prepare(device, null, modelView, bob, null, projection);
	}

	/**
	 * The texture the game was going to draw this piece with, and the sampler it configured for it,
	 * which for an entity is its own skin rather than an atlas shared with anything.
	 * <p>
	 * Handed on at every draw and not once at the load, which is the difference between this family
	 * and the other two: one pipeline draws every mob in the world and each of them brings its own
	 * image, so a pack reading {@code gtexture} has to be given the one belonging to the draw being
	 * recorded and not the one belonging to the draw that opened the pass.
	 */
	void texture(GpuTextureView view, GpuSampler sampler) {
		this.body.atlas(view);
		this.body.sampler(sampler);
	}

	/**
	 * The pass the run is recorded into, or null, which the caller answers with a plain pass of its
	 * own or with a refusal.
	 *
	 * @see GeometryProgram#descriptor
	 */
	RenderPassDescriptor descriptor(GpuTextureView colour, GpuTextureView depth) {
		return this.body.descriptor(colour, depth);
	}

	/**
	 * Whether the pass to open is the plain one, with none of the pack's own targets named.
	 *
	 * @see GeometryProgram#plain
	 */
	boolean plain() {
		return this.body.plain();
	}

	/**
	 * Binds this program's block and every sampler it declares, inside the pass just opened.
	 *
	 * @see GeometryProgram#bind
	 */
	void bind(RenderPass pass) {
		this.body.bind(pass);
	}

	/**
	 * Whether this program reads the game's own per draw transforms, and so whether the draw about to
	 * be recorded has to bind them.
	 *
	 * @see GeometryProgram#readsGameTransforms
	 */
	boolean readsGameTransforms() {
		return this.body.readsGameTransforms();
	}

	/** @see GeometryProgram#decoded */
	@Override
	public String decoded(WorldState world) {
		return this.body.decoded(world);
	}

	/** @see GeometryProgram#path */
	@Override
	public String path() {
		return this.body.path();
	}

	/** @see GeometryProgram#label */
	@Override
	public String label() {
		return this.body.label();
	}

	/**
	 * Rotates the ring buffer, once the frame's draw has been recorded.
	 *
	 * @see GeometryProgram#rotate
	 */
	void rotate() {
		this.body.rotate();
	}

	/**
	 * Closes this program's block and the placeholder textures it made.
	 *
	 * @see GeometryProgram#release
	 */
	void release() {
		this.body.release();
	}
}
