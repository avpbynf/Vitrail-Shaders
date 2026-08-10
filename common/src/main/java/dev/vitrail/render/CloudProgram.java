package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.BindGroupLayouts;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The program the pack draws the game's clouds with, in place of the game's own.
 * <p>
 * The body of it is {@link GeometryProgram}'s, as the sky's is; what this class holds is what the
 * clouds answer differently, and there are only three things.
 * <p>
 * <strong>There is no vertex buffer.</strong> {@code CloudRenderer} draws six indices a face out of
 * a texel buffer and works every corner out in the shader, so this program binds no format at all
 * and reads the game's own {@code CloudInfo} block and {@code CloudFaces} buffer instead.
 * {@code glsl/CloudVertex} carries what that means for the text; here it means one extra bind group,
 * and it has to be the game's own object rather than a rebuilt copy, because the pass fills it by
 * name against whatever pipeline is bound.
 * <p>
 * <strong>Fancy and flat differ by a culling and by nothing else.</strong> The game keeps two
 * pipelines for them, {@code CLOUDS} and {@code FLAT_CLOUDS}, and the second draws its single
 * downward face with culling off. So there are two of these over one translation, and the one that
 * is never drawn never compiles a module.
 * <p>
 * <strong>Its namespace is ours and has no {@code sodium} in it</strong>, for the reason
 * {@link SkyProgram} gives: the word turns push constants on, and the cloud pass has no region to
 * fill them from.
 */
final class CloudProgram {

	/** What the log calls this geometry, one word in the middle of a sentence. */
	private static final String FAMILY = "cloud";

	/** Ours, and deliberately without the word that turns push constants on. */
	private static final String NAMESPACE = Vitrail.MOD_ID;

	private final GeometryProgram body;

	private CloudProgram(GeometryProgram body) {
		this.body = body;
	}

	/**
	 * Prepares one already read program to be drawn as the clouds of one cloud setting.
	 *
	 * @param loaded the pack's own program, read and translated against no format at all
	 * @param fancy  whether this is the pipeline the game builds its boxed clouds with. The flat one
	 *               draws a single downward face a cell and turns culling off, which is the whole
	 *               difference between the two and the reason there are two of these
	 * @param writes where the clouds' outputs belong, in draw buffer order and each on the half the
	 *               schedule gives it. Empty leaves them on the one attachment the game opened its
	 *               own pass with, where the full screen layer is what brings them across
	 */
	static CloudProgram of(PackProgram.Loaded loaded, boolean fancy, PackValues values, int load,
			List<ChainPlan.Attachment> writes, TargetPlan chainTargets, ColorTargets targets,
			boolean chainRuns) {
		// Bound again against the chain's own plan, for the reason the terrain and the sky are: what
		// the load bound them against is a plan without the user's pass filter.
		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		PackProgram.Loaded bound =
				loaded.rebind(chainTargets, chainTargets.schedule().step(servedBy));

		return new CloudProgram(new GeometryProgram(new GeometryProgram.Pass(FAMILY,
				fancy ? "fancy" : "flat", NAMESPACE,
				// The cloud buffer carries none of the names a pack declares for itself, so every one
				// of them really is a constant here and the log is right to say so.
				Set.of(), false,
				// What the game's own two cloud pipelines blend with, both of them.
				Optional.of(BlendFunction.TRANSLUCENT),
				// No coverage mask. The clouds are drawn after the main pass and therefore after the
				// scene seed has run, so there is nothing left for a mask to keep off them.
				false, true, PrimitiveTopology.QUADS,
				// The game's answer and not a taste: RenderPipelines.CLOUDS takes the builder's
				// default, which is to cull, and FLAT_CLOUDS names withCull(false). Culling the flat
				// cloud would leave the sky empty from underneath, which is where it is looked at.
				fancy,
				// The game's own state, which tests and writes under the reversed Z the scene is
				// drawn in. Unlike the sky, which declares none: a cloud stands in the world and has
				// to be hidden by whatever is in front of it.
				DepthStencilState.DEFAULT, RenderStage.CLOUDS, BindGroupLayouts.CLOUD_INFO),
				bound, values, load,
				// No format, which this family is alone in and GeometryProgram takes as "bind no
				// vertex buffer". Handing DefaultVertexFormat.POSITION here instead would declare an
				// input the pass never sets and read whatever the last draw left bound.
				null, writes, targets, chainRuns));
	}

	/** @see GeometryProgram#prepare */
	RenderPipeline prepare(GpuDevice device) {
		return this.body.prepare(device, null);
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
