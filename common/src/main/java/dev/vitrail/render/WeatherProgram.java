package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
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
 * One program the pack draws the game's rain and snow with, in place of the game's own.
 * <p>
 * The body of it is {@link GeometryProgram}'s, as the terrain's, the sky's and the entities' are;
 * what this class holds is what the weather answers differently, and there are three things.
 * <p>
 * <strong>The pipeline states are read off the game's own pipeline</strong>, as the entities' are
 * and unlike the sky's, because there is one to read: the weather renderer picks between two
 * pipelines earlier in the method that opens its pass, and the door is handed the one it picked. That
 * is what makes {@code rain.depth} cost nothing here, the directive being served where Iris serves
 * it, by moving the game's own choice rather than by describing a depth state of our own.
 * <p>
 * <strong>Draw buffer nought goes to the pack outright.</strong> The weather blends, and it is drawn
 * after the whole main pass: the deferred stage has run and the world's translucents are down, so the
 * pack's colour target already holds the picture this curtain is meant to blend onto. That is the
 * position {@code gbuffers_water} is in and the opposite of the entities', which are cut against a
 * depth taken before any of it and reach the picture through the scene seed.
 * <p>
 * <strong>Its namespace is ours and has no {@code sodium} in it</strong>, for the reason
 * {@link SkyProgram} gives: the word is what makes Sodium's mixin push twenty bytes of region offset
 * into the layout, and a weather mesh has no region.
 */
final class WeatherProgram implements DumpedProgram {

	/** What the log calls this geometry, one word in the middle of a sentence. */
	private static final String FAMILY = "weather";

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

	private final GeometryProgram body;

	private WeatherProgram(GeometryProgram body) {
		this.body = body;
	}

	/**
	 * Prepares one already read program to draw the rain and the snow.
	 *
	 * @param loaded the pack's own program, read and translated for the threshold this piece discards
	 *               at
	 * @param writes where this piece's outputs belong, in draw buffer order and each on the half the
	 *               schedule gives it. Empty leaves the curtain on the one attachment the game opened
	 *               its own pass with
	 */
	static WeatherProgram of(PackProgram.Loaded loaded, WeatherDraw.Element element, PackValues values,
			int load, List<ChainPlan.Attachment> writes, TargetPlan chainTargets,
			ColorTargets targets, boolean chainRuns) {
		// Bound again against the chain's own plan, for the reason the terrain and the sky are: what
		// the load bound them against is a plan without the user's pass filter.
		//
		// And taken through stepAfterDeferred, which is what this family answers differently from the
		// sky and the entities: the game draws its weather in a frame graph pass of its own, after the
		// whole main pass, so the halves it READS have to be the ones the deferred stage left, exactly
		// as the translucent chunk pass takes them. Asked through step, this program would write the
		// half ChainPlan gave it, which is the after-deferred one, and read the half before it, and
		// TargetSchedule.stepAfterDeferred says in its own words that nothing on either side would
		// say a word about that.
		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		PackProgram.Loaded bound =
				loaded.rebind(chainTargets, chainTargets.schedule().stepAfterDeferred(servedBy));

		RenderPipeline game = element.pipeline();

		return new WeatherProgram(new GeometryProgram(new GeometryProgram.Pass(FAMILY,
				element.element(), NAMESPACE, ANSWERED, false,
				game.getColorTargetState().blendFunction(),
				// No coverage mask, and the sky's rule is the one that decides it: the mask is written
				// whatever the blend, so a curtain of rain that is a hundred parts transparent to one
				// part water would claim every pixel it spans. It is also drawn long after the seed,
				// which is what the mask exists to cut.
				false, true, game.getPrimitiveTopology(), game.isCull(),
				game.getDepthStencilState(), element.stage(),
				// Nothing of the game's bound beside the mesh, unlike the clouds: the curtain is a
				// vertex buffer the renderer fills, and this program declares no name of its own.
				null),
				bound, values, load, DefaultVertexFormat.PARTICLE, writes, targets, chainRuns));
	}

	/**
	 * @see GeometryProgram#prepare
	 */
	RenderPipeline prepare(GpuDevice device) {
		// No model view of its own and no colour: the renderer writes its transform from
		// RenderSystem.getModelViewMatrixCopy(), which is the frame's camera, and through the one
		// argument overload, whose modulator is white.
		return this.body.prepare(device, null, null, null);
	}

	/**
	 * The texture the game was going to draw this half of the curtain with, and the sampler it
	 * configured for it.
	 * <p>
	 * Handed on at every draw and not once at the pass, which this family shares with the entities
	 * and for a smaller version of the same reason: one pass draws the rain and then the snow, out of
	 * one vertex buffer and with one pipeline, and the only thing that changes between the two draws
	 * is this image.
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

	/** @see GeometryProgram#owns */
	boolean owns(RenderPipeline bound) {
		return this.body.owns(bound);
	}

	/** @see GeometryProgram#bind */
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

	/** @see GeometryProgram#rotate */
	void rotate() {
		this.body.rotate();
	}

	/** @see GeometryProgram#release */
	void release() {
		this.body.release();
	}
}
