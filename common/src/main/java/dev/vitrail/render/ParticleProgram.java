package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.List;
import java.util.Set;

/**
 * One program the pack draws a half of the game's quad particles with, in place of the game's own.
 * <p>
 * The body of it is {@link GeometryProgram}'s, as every other family's is; what this class holds is
 * what a particle answers differently, and there are three things.
 * <p>
 * <strong>The two halves stand on opposite sides of the frame</strong>, which is the whole reason
 * they are two programs rather than one with two blends. The opaque half is drawn among the game's
 * solid features, before the deferred stage, so it is in the entities' position: it writes the
 * coverage mask and owns every draw buffer the pack asked for, nought included. The translucent
 * half is drawn last of all, after the world's own water, so it is in the weather's position: it
 * blends onto a picture the chain has already put there and owns draw buffer nought on the strength
 * of its side. What the mask buys the opaque half is written where this class builds the pass, and
 * it is worth naming because the two halves look interchangeable and are not.
 * <p>
 * <strong>The image belongs to the draw.</strong> One pass draws every particle of its half, and the
 * layers inside it come off three different atlases, so a pack reading {@code gtexture} has to be
 * given the one belonging to the layer being recorded.
 * <p>
 * <strong>Its namespace is ours and has no {@code sodium} in it</strong>, for the reason
 * {@link SkyProgram} gives: the word is what makes Sodium's mixin push twenty bytes of region offset
 * into the layout, and a particle mesh has no region.
 */
final class ParticleProgram extends FamilyProgram {

	/** What the log calls this geometry, one word in the middle of a sentence. */
	private static final String FAMILY = "particles";

	/** Ours, and deliberately without the word that turns push constants on. See the class comment. */
	private static final String NAMESPACE = Vitrail.MOD_ID;

	/**
	 * The names the particle mesh really carries under the spelling a pack writes, which is none of
	 * them.
	 * <p>
	 * Empty for the reason {@code EntityProgram} gives about its own: {@code ParticleVertex} answers
	 * every fixed function name out of the four elements of the format, and there is no room in those
	 * four for anything a pack declares for itself.
	 */
	private static final Set<String> ANSWERED = Set.of();

	private ParticleProgram(GeometryProgram body) {
		super(body);
	}

	/**
	 * Prepares one already read program to draw one half of the game's quad particles.
	 *
	 * @param writes where this half's outputs belong, in draw buffer order and each on the half of
	 *               the schedule its side of the deferred stage gives it
	 */
	static ParticleProgram of(PackProgram.Loaded loaded, ParticleDraw.Element element,
			PackValues values, int load, List<ChainPlan.Attachment> writes, TargetPlan chainTargets,
			ColorTargets targets, boolean chainRuns) {
		// Bound again against the chain's own plan, for the reason every other family is: what the
		// load bound them against is a plan without the user's pass filter.
		//
		// And the step is the ELEMENT's, which is the one place this family needs both answers: it
		// straddles the deferred stage, so the opaque half reads the halves the prepares left and the
		// translucent one reads the halves the deferreds left. Handed one answer for both, the
		// translucent half would write the half the plan gave it and read the half before it, which
		// TargetSchedule.stepAfterDeferred says nothing on either side would report. The terrain
		// branches on the same question and for the same reason.
		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		PackProgram.Loaded bound = loaded.rebind(chainTargets, element.afterDeferred()
				? chainTargets.schedule().stepAfterDeferred(servedBy)
				: chainTargets.schedule().step(servedBy));

		RenderPipeline game = element.pipeline();

		return new ParticleProgram(new GeometryProgram(new GeometryProgram.Pass(FAMILY,
				element.element(), NAMESPACE, ANSWERED, false,
				game.getColorTargetState().blendFunction(),
				// The coverage mask on the opaque half and not on the translucent one, which is the
				// entities' rule and is what decides whether the pack owns draw buffer nought.
				//
				// WITHOUT IT THE OPAQUE HALF NEVER WRITES THE PACK'S TARGET AT ALL. Draw buffer
				// nought falls back to the game's own, so a pack whose particle program declares one
				// draw buffer writes nothing of the pack's and its colour reaches the picture only
				// through the seed, already tone mapped and flat. Photon is that pack: its
				// gbuffers_particles writes colortex1, which is its gbuffer, and the deferred stage
				// shades what it finds there. Reached through the seed instead, the smoke arrives as
				// a finished LDR colour the deferred stage then reads as gbuffer data, which is the
				// warm haze a capture pair put beside Iris's dark smoke.
				//
				// Iris binds every gbuffers program to a framebuffer over the pack's own declared
				// draw buffers, the particles among them (pipeline/IrisRenderingPipeline.java:677-678
				// with the two particle keys at pipeline/programs/ShaderKey.java:94,96), so the
				// pack's colour never makes that trip there.
				//
				// The translucent half owes no mask for the reason every after-deferred pass owes
				// none: the seed has run long before it draws, and it owns draw buffer nought on the
				// strength of its side alone.
				//
				// claimed: no sibling marks these pixels for them. The opaque half is drawn with
				// RenderPipelines.OPAQUE_PARTICLE, which blends nothing, so the question does not
				// arise there either.
				!element.afterDeferred(), false, element.afterDeferred(),
				game.getPrimitiveTopology(), game.isCull(),
				game.getDepthStencilState(), element.stage(),
				// Nothing of the game's bound beside the mesh, unlike the clouds: a particle carries
				// its whole geometry in the vertex buffer the renderer fills.
				null,
				// One block, written once: every draw of one pass reads the same values.
				null,
				// Drawn in the game's own volume, so the dh matrices answer the game's.
				false),
				bound, values, load, DefaultVertexFormat.PARTICLE, writes, targets, chainRuns));
	}

	/**
	 * The pipeline the particles are drawn with, compiled before the renderer opens its pass.
	 *
	 * @see GeometryProgram#prepare
	 */
	RenderPipeline prepare(GpuDevice device) {
		// No model view of its own and no colour: the renderer writes its transform from
		// RenderSystem.getModelViewMatrixCopy(), which is the frame's camera, and through the one
		// argument overload, whose modulator is white.
		return this.body.prepare(device, null, null, null, null, null);
	}

	/**
	 * The atlas the game was going to draw this layer with, and the sampler it configured for it.
	 * <p>
	 * Handed on at every draw and not once at the pass, which this family shares with the entities:
	 * one pass draws every particle of its half, and the layers inside it come off the block atlas,
	 * the item atlas and the particle atlas.
	 */
	void texture(GpuTextureView view, GpuSampler sampler) {
		this.body.atlas(view);
		this.body.sampler(sampler);
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
	 * The same program over a mesh laid out by another mod.
	 *
	 * @see GeometryProgram#reshape
	 */
	RenderPipeline reshape(GpuDevice device, VertexFormat layout) {
		return this.body.reshape(device, layout);
	}

}
