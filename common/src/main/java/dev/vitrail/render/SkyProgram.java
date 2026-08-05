package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.SkyVertex;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import org.joml.Matrix4fc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	 * Reads one sky program and prepares it to be drawn in a pass of that format.
	 * <p>
	 * Nothing is written into the pack's own colour targets yet: the writes handed to the body are
	 * empty, so the program keeps the one attachment the game opened its pass with and asks for no
	 * descriptor of its own. What the pack's {@code DRAWBUFFERS} say is {@link ChainPlan#sky}'s
	 * answer and is the next slice, not this one.
	 *
	 * @param program the bare name the game would have drawn with, {@code gbuffers_skybasic}
	 * @param element the pass this is drawn in, one word, which tells two passes of one file apart
	 * @param format  the vertex format that pass binds, whose elements are declared exactly
	 * @return empty when the pack serves nothing for it, and the game then keeps its own sky
	 */
	static SkyProgram read(Path packPath, String place, String program, String element,
			Map<String, OptionValue> chosen, String profile, PackValues values, int load,
			VertexFormat format, PrimitiveTopology topology, Optional<BlendFunction> blend,
			TargetPlan chainTargets, ColorTargets targets) {
		try {
			List<String> elements = format.getElements().stream()
					.map(VertexFormatElement::name)
					.toList();
			Optional<PackProgram.Loaded> loaded =
					PackProgram.loadSky(packPath, place, program, elements, chosen, profile);
			if (loaded.isEmpty()) {
				Vitrail.logger().info("{} serves no {} in {}, so the game keeps its own sky",
						packPath.getFileName(), program, place.isEmpty() ? "its root" : place);

				return null;
			}

			// Bound again against the chain's own plan, for the reason the terrain is: what the load
			// bound them against is a plan without the user's pass filter. The step is the one before
			// the deferreds, the sky standing at the third rank of the frame.
			String servedBy = loaded.get().path().substring(loaded.get().path().lastIndexOf('/') + 1);
			PackProgram.Loaded bound =
					loaded.get().rebind(chainTargets, chainTargets.schedule().step(servedBy));

			return new SkyProgram(new GeometryProgram(new GeometryProgram.Pass(FAMILY, element,
					NAMESPACE, Set.copyOf(SkyVertex.ATTRIBUTES), false, blend, false, false,
					topology, null),
					bound, values, load, format, List.of(), targets, false));
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the sky programs of " + packPath.getFileName()
					+ ", so the game keeps its own sky", e);

			return null;
		}
	}

	/**
	 * @param modelView the matrix the game pushed for this element, which is where the sun is
	 * @see GeometryProgram#prepare
	 */
	RenderPipeline prepare(GpuDevice device, Matrix4fc modelView) {
		return this.body.prepare(device, null, modelView);
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
