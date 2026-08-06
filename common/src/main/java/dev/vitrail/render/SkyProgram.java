package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.SkyVertex;
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

import org.joml.Matrix4fc;
import org.joml.Vector4fc;

import java.util.List;
import java.util.Set;

/**
 * One program the pack draws a piece of the game's sky with, in place of the game's own.
 * <p>
 * The body of it is {@link GeometryProgram}'s, as the terrain's is; what this class holds is what
 * the sky answers differently, and there are only three things.
 * <p>
 * <strong>The sky is not one format.</strong> {@code SkyRenderer} opens a render pass per element
 * and binds four formats between them, so the elements a stage declares are the answer of the PASS
 * and not of the family, and a program is loaded once per format it may be drawn against. The
 * pairing is by name and asymmetric in both directions, which {@link SkyVertex} spells out: a name
 * declared that the format has not got refuses the program outright, and an element the format
 * carries that the stage does not declare shifts the location of every one after it without a word.
 * <p>
 * <strong>It neither tests nor writes a depth.</strong> {@code RenderPipelines.SKY} declares no
 * depth state at all, and the disc is drawn before the world: given the ordinary one, the pack's
 * program would write the sky into the depth buffer and the world would be tested against it.
 * <p>
 * <strong>Its namespace is ours and has no {@code sodium} in it.</strong> The word is what makes
 * Sodium's mixin push twenty bytes of region offset into the layout, and the sky is the game's
 * geometry: it has no region and no push constants, and borrowing the word would push them anyway.
 */
final class SkyProgram {

	/** What the log calls this geometry, one word in the middle of a sentence. */
	private static final String FAMILY = "sky";

	/** Ours, and deliberately without the word that turns push constants on. See the class comment. */
	private static final String NAMESPACE = Vitrail.MOD_ID;

	private final GeometryProgram body;

	private SkyProgram(GeometryProgram body) {
		this.body = body;
	}

	/**
	 * Prepares one already read program to be drawn as one piece of the sky.
	 *
	 * @param loaded the pack's own program, read and translated against the format the pass that
	 *               draws this piece binds
	 * @param writes where this piece's outputs belong, in draw buffer order and each on the half the
	 *               schedule gives it. {@link ChainPlan#sky}'s answer for the program that draws it,
	 *               unless {@link SkyDraw} emptied it, which it does for the whole sky at once.
	 *               Empty leaves the piece on the one attachment the game opened its own pass with
	 */
	static SkyProgram of(PackProgram.Loaded loaded, SkyDraw.Element element, PackValues values,
			int load, List<ChainPlan.Attachment> writes, TargetPlan chainTargets,
			ColorTargets targets, boolean chainRuns) {
		// Bound again against the chain's own plan, for the reason the terrain is: what the load
		// bound them against is a plan without the user's pass filter. The step is the one before
		// the deferreds, the sky standing at the third rank of the frame.
		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		PackProgram.Loaded bound =
				loaded.rebind(chainTargets, chainTargets.schedule().step(servedBy));

		// A piece that writes outright is one the scene seed must not paint over, and a piece that
		// blends is not: the mask is written whatever the blend, so a star quad, which is a hundred
		// parts transparent to one part star, would claim every pixel it spans and cut the game's
		// picture out of all of them. The opaque and cutout chunk passes answer the same question the
		// same way, and the translucent one answers it no.
		return new SkyProgram(new GeometryProgram(new GeometryProgram.Pass(FAMILY, element.element(),
				NAMESPACE, Set.copyOf(SkyVertex.ATTRIBUTES), false, element.blend(),
				element.blend().isEmpty(), false, element.topology(),
				// None of the six sky pipelines names a culling of its own, so all six take the
				// builder's default, which is to cull.
				true, null, element.stage()),
				bound, values, load, element.format(), writes, targets, chainRuns));
	}

	/**
	 * @param modelView the matrix the game pushed for this element, which is where the sun is
	 * @see GeometryProgram#prepare
	 */
	RenderPipeline prepare(GpuDevice device, Matrix4fc modelView, Vector4fc colour) {
		return this.body.prepare(device, null, modelView, colour);
	}

	/**
	 * The texture the game was going to draw this element with, and the sampler it configured for
	 * it: the celestial atlas for the sun and the moon. Handed on so that a pack reading
	 * {@code gtexture} reads the same image the game would have.
	 */
	void texture(GpuTextureView view, GpuSampler sampler) {
		this.body.atlas(view);
		this.body.sampler(sampler);
	}

	/** @see GeometryProgram#descriptor */
	RenderPassDescriptor descriptor(GpuTextureView colour, GpuTextureView depth) {
		return this.body.descriptor(colour, depth);
	}

	/** @see GeometryProgram#bind */
	void bind(RenderPass pass) {
		this.body.bind(pass);
	}

	/** @see GeometryProgram#owns */
	boolean owns(RenderPipeline bound) {
		return this.body.owns(bound);
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
