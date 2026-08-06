package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import java.util.List;
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
 * <strong>Its first draw buffer stays on the game's target.</strong> This is the position the opaque
 * chunk passes were in before the coverage mask existed, and it is deliberate here: the scene seed
 * is cut against a depth taken before the game draws a single feature, so an entity is by definition
 * a pixel the seed repaints. Writing the pack's colour target outright would put the picture there
 * and have the seed paint over it a frame later in the same frame. What that costs is the albedo
 * making one trip through eight bits a channel; what it buys is every other draw buffer, which is
 * where a pack keeps the normal, the specular map and the material it lights an entity by, and which
 * is the whole of what {@code the entities still come from the game} meant.
 * <p>
 * <strong>Its namespace is ours and has no {@code sodium} in it</strong>, for the reason
 * {@link SkyProgram} gives: the word is what makes Sodium's mixin push twenty bytes of region offset
 * into the layout, and an entity mesh has no region.
 * <p>
 * <strong>It tells the pack it is drawing nothing in particular</strong>, and that reads as an
 * oversight until Iris is read. What a pack gets under {@code renderStage} is the ordinal of the
 * phase Iris has posed ({@code uniforms/CommonUniforms.java:116}), and the call that would pose the
 * entity one ({@code layer/GbufferPrograms.java:27}) hangs off {@code EntityRenderStateShard}, which
 * nothing there constructs and nothing installs: it survives as an unused import of
 * {@code mixin/entity_render_context/MixinEntityRenderDispatcher.java:8} and nowhere else. Its one
 * live pose is in the shadow map ({@code shadows/ShadowRenderer.java:521}), which this engine does
 * not draw entities into yet, and no phase at all is posed over the feature groups of the main pass.
 * So a pack branching on {@code MC_RENDER_STAGE_ENTITIES} never takes that branch under Iris, and
 * the packs are written against Iris.
 */
final class EntityProgram {

	/** What the log calls this geometry, one word in the middle of a sentence. */
	private static final String FAMILY = "entity";

	/** Ours, and deliberately without the word that turns push constants on. See the class comment. */
	private static final String NAMESPACE = Vitrail.MOD_ID;

	/**
	 * The names the entity mesh really carries under the spelling a pack writes, which is none of
	 * them.
	 * <p>
	 * Empty and not an oversight: {@code EntityVertex} answers every fixed function name out of the
	 * six elements of the format, and there is no room in those six for anything a pack declares for
	 * itself. {@code mc_Entity} is the one worth naming, since the chunk mesh does carry it: an
	 * entity is not a block state and has no id to travel on, so a pack branching on it here is
	 * branching on a constant, and the log says so at every load.
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
	 * @param writes where this piece's outputs belong, in draw buffer order and each on the half the
	 *               schedule gives it, the first one INCLUDED and still going to the game's target.
	 *               {@link EntityDraw} settles it and refuses the whole family where the first one
	 *               could not reach the pack through the seed
	 */
	static EntityProgram of(PackProgram.Loaded loaded, EntityDraw.Element element, PackValues values,
			int load, List<ChainPlan.Attachment> writes, TargetPlan chainTargets,
			ColorTargets targets, boolean chainRuns) {
		// Bound again against the chain's own plan, for the reason the terrain and the sky are: what
		// the load bound them against is a plan without the user's pass filter. The step is the one
		// before the deferreds, every piece served here being drawn in the game's opaque feature
		// phase, which stands between the opaque chunks and the deferred stage.
		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		PackProgram.Loaded bound =
				loaded.rebind(chainTargets, chainTargets.schedule().step(servedBy));

		RenderPipeline game = element.pipeline();

		return new EntityProgram(new GeometryProgram(new GeometryProgram.Pass(FAMILY,
				element.element(), NAMESPACE, ANSWERED, false,
				game.getColorTargetState().blendFunction(),
				// No coverage mask, which is the same decision as leaving draw buffer nought on the
				// game's target and not a second one: the two are tied together in GeometryProgram,
				// and marking a pixel the seed is going to repaint anyway would only take the game's
				// own picture away from whatever is drawn there next.
				false, false, game.getPrimitiveTopology(), game.isCull(),
				// NONE and not ENTITIES, which is Iris's answer rather than a reading of what this
				// pass is: the class comment has the four file:line it was read from.
				game.getDepthStencilState(), RenderStage.NONE),
				bound, values, load, DefaultVertexFormat.ENTITY, writes, targets, chainRuns));
	}

	/** @see GeometryProgram#prepare */
	RenderPipeline prepare(GpuDevice device) {
		return this.body.prepare(device, null);
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

	/** @see GeometryProgram#descriptor */
	RenderPassDescriptor descriptor(GpuTextureView colour, GpuTextureView depth) {
		return this.body.descriptor(colour, depth);
	}

	/** @see GeometryProgram#plain */
	boolean plain() {
		return this.body.plain();
	}

	/** @see GeometryProgram#bind */
	void bind(RenderPass pass) {
		this.body.bind(pass);
	}

	/** @see GeometryProgram#decoded */
	String decoded(WorldState world) {
		return this.body.decoded(world);
	}

	/** @see GeometryProgram#path */
	String path() {
		return this.body.path();
	}

	/** @see GeometryProgram#label */
	String label() {
		return this.body.label();
	}

	/** @see GeometryProgram#rotate */
	void rotate() {
		this.body.rotate();
	}

	/** @see GeometryProgram#release */
	void release() {
		this.body.release();
	}
}
