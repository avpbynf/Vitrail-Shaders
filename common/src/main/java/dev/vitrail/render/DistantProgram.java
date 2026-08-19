package dev.vitrail.render;

import dev.vitrail.glsl.DistantVertex;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;

import org.joml.Matrix4fc;

import java.util.List;
import java.util.Optional;

/**
 * One program the pack draws a half of Distant Horizons' far terrain with, in place of DH's own.
 * <p>
 * The body of it is {@link GeometryProgram}'s like every other family's; what this class holds is
 * what an LOD answers differently, and there are four things.
 * <p>
 * <strong>The volume is DH's and not the frame's.</strong> A pack writes its far terrain's clip
 * position through {@code dhProjection} - BSL's {@code program/dh_terrain.glsl} ends on
 * {@code gl_Position = dhProjection * gbufferModelView * position} - and Iris hands its own DH
 * programs the same matrix under {@code iris_ProjectionMatrix} as well
 * ({@code compat/dh/LodRendererEvents.java:317-320}), so both spellings have to answer the volume
 * the far terrain is really rasterised in. That is why the pass is prepared with a projection of its
 * own, the way the hand is: {@code render/ViewMatrices} builds it out of the frame's own matrix and
 * the z row DH drew with, and serves the same volume to every pass, as Iris serves it.
 * <p>
 * <strong>The depth it writes is not the game's</strong>, and that is the arrangement Iris has
 * rather than a caution of ours: DH's own near plane is pulled in to seven and a half blocks, so a
 * value from that volume read as one of the game's puts a hill a thousand blocks off at arm's
 * length. Iris draws its LODs into a framebuffer whose depth attachment is DH's own depth image
 * ({@code compat/dh/DHCompatInternal.java:166-171} attaching the texture it will serve back), and
 * the far terrain never enters the game's depth there at all. Here it is the same shape one class
 * over: {@link DistantDraw} keeps a depth image of its own, and {@code render/PackDepth} converts
 * it into the window the pack reads, which is what a {@code dhDepthTex} lookup is answered with.
 * The pack's own Distant Horizons branches - BSL's {@code deferred1.glsl} taking its
 * {@code else if (dhZ < 1.0)} road - are what light, fog and occlude the far terrain from there.
 * <p>
 * <strong>Every draw buffer goes to the pack's own targets, the first included</strong>, which is
 * where Iris puts them: its dh programs are bound over the pack's declared draw buffers exactly as
 * its gbuffers programs are ({@code compat/dh/DHCompatInternal.java:92}). The seed cannot carry
 * this family and does not need to keep off it, and {@link GeometryProgram} says why in the one
 * place that rule is decided.
 * <p>
 * <strong>Its namespace is ours and has no {@code sodium} in it</strong>, for the reason
 * {@link SkyProgram} gives: the word is what makes Sodium's mixin push twenty bytes of region offset
 * into the layout, and DH's mesh has no region. It has a section instead, and that is what the
 * second uniform block is for.
 */
final class DistantProgram implements DumpedProgram {

	/** What the log calls this geometry, one word in the middle of a sentence. */
	private static final String FAMILY = "far terrain";

	/** Ours, and deliberately without the word that turns push constants on. */
	private static final String NAMESPACE = Vitrail.MOD_ID;

	/**
	 * The depth window an LOD is drawn under: greater passes, and the pass writes what it passed
	 * with.
	 * <p>
	 * Greater and not less, because the image this writes is reversed Z over zero to one like every
	 * other depth in this engine, DH clearing its own to nought and taking that value from
	 * {@code core/render/EDhRenderDepth.java:18}. It is the state DH's own pipeline is built with,
	 * {@code common/render/blaze/BlazeDhTerrainRenderer.java:104-111}, which branches on the
	 * backend's convention and lands here on this one.
	 */
	private static final DepthStencilState DEPTH =
			new DepthStencilState(CompareOp.GREATER_THAN, true);

	private final GeometryProgram body;

	private DistantProgram(GeometryProgram body) {
		this.body = body;
	}

	/**
	 * Prepares one already read program to draw one half of the far terrain.
	 *
	 * @param carried the elements of DH's mesh the pack's far terrain programs read, which is what
	 *                the format is built from and what every one of those programs declares
	 * @param writes  where this half's outputs belong, in draw buffer order and each on the half of
	 *                the schedule its side of the deferred stage gives it
	 */
	static DistantProgram of(PackProgram.Loaded loaded, DistantDraw.Element element,
			List<String> carried, PackValues values, int load, List<ChainPlan.Attachment> writes,
			TargetPlan chainTargets, ColorTargets targets, boolean chainRuns) {
		// Bound again against the chain's own plan, for the reason every other family is: what the
		// load bound them against is a plan without the user's pass filter. The step is the half's,
		// the two standing on opposite sides of the deferred stage.
		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		PackProgram.Loaded bound = loaded.rebind(chainTargets, element.afterDeferred()
				? chainTargets.schedule().stepAfterDeferred(servedBy)
				: chainTargets.schedule().step(servedBy));

		VertexFormat format = DistantMesh.format(carried);

		return new DistantProgram(new GeometryProgram(new GeometryProgram.Pass(FAMILY,
				element.element(), NAMESPACE, DistantVertex.ANSWERED, false,
				// The half DH defers blends over what stands behind it, exactly as the world's own
				// water does; the opaque half writes outright. DH's own two pipelines are built the
				// same way, one withoutBlend and one with TRANSLUCENT
				// (common/render/blaze/BlazeDhTerrainRenderer.java:133-146).
				element.afterDeferred() ? Optional.of(BlendFunction.TRANSLUCENT)
						: Optional.<BlendFunction>empty(),
				// No coverage mask, and the class comment says what writes those pixels instead.
				// claimed: nothing of this family marks them either, for the same reason.
				false, false, element.afterDeferred(), PrimitiveTopology.TRIANGLES,
				// Culled, which is DH's own answer: withFaceCulling(true) on both of its pipelines.
				true, DEPTH, element.stage(), null,
				// The one family with a value that belongs to the section rather than to the pass.
				DistantVertex.SECTION_BLOCK,
				// And the one family drawn in DH's own volume, so the three dh matrices answer that
				// volume here and the game's everywhere else.
				true),
				bound, values, load, format, writes, targets, chainRuns));
	}

	/**
	 * The pipeline the far terrain is drawn with, compiled before the pass is opened.
	 *
	 * @param projection the volume DH rasterises in, which both spellings of the projection a
	 *                   {@code dh_} program may read have to answer
	 * @see GeometryProgram#prepare
	 */
	RenderPipeline prepare(GpuDevice device, Matrix4fc projection) {
		// No model view and no colour of its own: an LOD stands in the world like a chunk, so the
		// frame's camera places it, and nothing modulates what DH built.
		return this.body.prepare(device, null, null, null, null, projection);
	}

	/**
	 * The pass this program is drawn into, or null to open the plain one.
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
	 * Rotates the ring buffer, once the frame's draws have been recorded.
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
