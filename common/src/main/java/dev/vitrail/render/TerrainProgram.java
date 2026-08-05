package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.SodiumVertex;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSize;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One of the three programs the pack draws a chunk pass with, in place of Sodium's own shader.
 * <p>
 * The work of drawing one is {@link GeometryProgram}'s and is not done here twice: this class is
 * what makes that work Sodium's. It reads the three programs out of the pack, answers
 * {@link GeometryProgram.Pass} out of {@link TerrainPass}, and checks that the mesh is still the one
 * the prologue decodes. Everything else it is asked for it hands straight on, and the surface it
 * keeps is the one {@link TerrainDraw} and {@link PackDump} were already written against.
 * <p>
 * Nothing of the mesh is changed: the attributes it carries are decoded and the names it does not
 * carry are given constants, which {@link SodiumVertex} spells out. What separates the three is not
 * the geometry but the pass, and {@link TerrainPass} holds all of it: which program the pack serves
 * it with, what alpha the fragment stage discards at, and whether the result is blended. The first
 * two are settled at translation and reach here already written into the text; only the blend is a
 * property of the pipeline, and it is one of the answers passed on below.
 * <p>
 * <strong>The pipeline is named in a namespace containing {@code sodium}, and that is not a
 * cosmetic.</strong> blaze3d never declares a push constant range; Sodium adds one by a mixin on
 * {@code VulkanRenderPipeline}, and only when
 * {@code pipeline.getLocation().getNamespace().contains("sodium")}. Named anything else, this
 * pipeline is pushed twenty bytes into a layout with no room for them: the region offset never
 * arrives and the whole world draws itself on top of the camera. It is a {@code contains} and not an
 * {@code equals}, so a namespace of our own with the word in it is enough and no mixin is needed.
 * <strong>A family whose geometry is not Sodium's must not borrow it</strong>, or it takes the push
 * constants with it.
 */
public final class TerrainProgram {

	/** The one name that decides everything. See the class comment before shortening it. */
	private static final String NAMESPACE = Vitrail.MOD_ID + "_sodium";

	/** What the log calls this geometry, one word in the middle of a sentence. */
	private static final String FAMILY = "chunk";

	private final GeometryProgram body;

	private TerrainProgram(TerrainPass pass, PackProgram.Loaded loaded, PackValues values, int load,
			VertexFormat format, List<ChainPlan.Attachment> writes, ColorTargets targets,
			boolean chainRuns) {
		this.body = new GeometryProgram(new GeometryProgram.Pass(FAMILY,
				pass.name().toLowerCase(Locale.ROOT), NAMESPACE, SodiumVertex.ANSWERED,
				pass.shadow(),
				// The translucent half blends over the world, the two opaque ones write outright.
				pass.blended() ? Optional.of(BlendFunction.TRANSLUCENT) : Optional.<BlendFunction>empty(),
				pass.covers(), pass.afterDeferred(),
				// Sodium's own, taken from ShaderChunkRenderer.createShader: the pass this is bound
				// into was opened for that pipeline and a difference of topology would be a
				// difference nobody declared.
				PrimitiveTopology.QUADS, depthState(pass), pass.stage()),
				loaded, values, load, format, writes, targets, chainRuns);
	}

	/**
	 * Which way the depth test runs, which follows the window the target stores and nothing else.
	 * <p>
	 * The game rasterises the scene under a reversed Z and clears its depth to nought, so its own
	 * targets keep the default and its greater-or-equal. The shadow map is ours and stores the
	 * forward window, cleared to one, so its test is the other way round. Getting this pair out of
	 * step does not fail: it fills the map with the geometry furthest from the light, which is a
	 * shadow map of the far side of the world and reads as shadows in all the wrong places.
	 */
	private static DepthStencilState depthState(TerrainPass pass) {
		return pass.shadow()
				? new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true)
				: DepthStencilState.DEFAULT;
	}

	/**
	 * Reads and translates the three programs the chunk renderer draws with, keyed by the pass each
	 * one serves. A pass the pack ships nothing for is absent, and keeps the game's own shader.
	 * <p>
	 * A second reading of the pack, which costs one plan build for all three. The chain's own reading
	 * translates what the chain runs, and a gbuffers program is not in it: folding this into that
	 * walk would make every place pay for programs only this step uses.
	 *
	 * @param format the chunk mesh format, handed in rather than looked up, because nothing in this
	 *               module is allowed to name Sodium
	 */
	static Map<TerrainPass, TerrainProgram> read(Path packPath, String place,
			Map<String, OptionValue> chosen, String profile, PackValues values, int load,
			VertexFormat format, ChainPlan plan, TargetPlan chainTargets, boolean chainRuns,
			ColorTargets targets) {
		try {
			Map<TerrainPass, PackProgram.Loaded> loaded =
					PackProgram.loadTerrain(packPath, place, chosen, profile);
			if (loaded.isEmpty()) {
				Vitrail.logger().warn("{} serves no terrain program with both stages in {}, so the "
						+ "world keeps the game's own shader", packPath.getFileName(),
						place.isEmpty() ? "its root" : place);

				return Map.of();
			}

			Map<TerrainPass, TerrainProgram> programs = new EnumMap<>(TerrainPass.class);
			loaded.forEach((pass, one) -> {
				// The samplers are bound again, against the chain's own plan and on the step of the
				// PASS. What loadTerrain bound them against is a plan without the user's pass
				// filter and a step looked up by file, and both are the wrong parity in their own
				// way: the first the moment passes= trims the chain, the second for the translucent
				// pass, whose reads land on the halves the deferred stage leaves behind. BSL's
				// gbuffers_water reading gaux1, which its own deferred writes, is the second case.
				String servedBy = one.path().substring(one.path().lastIndexOf('/') + 1);
				PackProgram.Loaded bound = one.rebind(chainTargets, pass.afterDeferred()
						? chainTargets.schedule().stepAfterDeferred(servedBy)
						: chainTargets.schedule().step(servedBy));

				// Attachment nought is the game's own target and it is the size of the screen, so
				// every other attachment of that pass has to be too: one render pass has one render
				// area. A pack scaling its targets with size.buffer therefore keeps the single
				// attachment pass, and the log says so rather than the encoder throwing mid frame.
				List<ChainPlan.Attachment> writes = plan.geometry(pass)
						.filter(geometry -> {
							if (geometry.size().equals(TargetSize.ofScreen())) {
								return true;
							}

							Vitrail.logger().warn("{} writes targets the pack asked to be scaled, so "
									+ "they cannot share a pass with the game's own target and its "
									+ "other draw buffers are written nowhere", servedBy);

							return false;
						})
						.map(ChainPlan.Pass::attachments)
						.orElse(List.of());
				programs.put(pass, new TerrainProgram(pass, bound, values, load, format, writes,
						targets, chainRuns));
			});

			return programs;
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the terrain programs of "
					+ packPath.getFileName() + ", so the world keeps the game's own shader", e);

			return Map.of();
		}
	}

	/** Checks that the mesh is still the one the prologue decodes, and says so once when it is not. */
	static boolean carries(VertexFormat format) {
		List<String> elements = format.getElements().stream()
				.map(VertexFormatElement::name)
				.toList();
		if (elements.equals(SodiumVertex.ATTRIBUTES)) {
			return true;
		}

		// A silent failure otherwise, and the worst kind. An element the shader does not declare
		// moves the location of every element after it without a word, so the picture stays a
		// picture and the texture coordinates come out of the light map.
		Vitrail.logger().error("The chunk mesh carries {} and this engine decodes {}, so no terrain "
				+ "program will be drawn. The mesh is decided once when the game starts, so this is "
				+ "what turning the terrain on after that looks like: restart the game", elements,
				SodiumVertex.ATTRIBUTES);

		return false;
	}

	/** @see GeometryProgram#prepare */
	RenderPipeline prepare(GpuDevice device, GpuTextureView atlas) {
		return this.body.prepare(device, atlas);
	}

	/** @see GeometryProgram#bind */
	void bind(RenderPass pass) {
		this.body.bind(pass);
	}

	/** @see GeometryProgram#sampler(GpuSampler) */
	void sampler(GpuSampler sampler) {
		this.body.sampler(sampler);
	}

	/** @see GeometryProgram#owns */
	boolean owns(RenderPipeline bound) {
		return this.body.owns(bound);
	}

	/** @see GeometryProgram#servable */
	boolean servable() {
		return this.body.servable();
	}

	/** @see GeometryProgram#descriptor */
	RenderPassDescriptor descriptor(GpuTextureView colour, GpuTextureView depth) {
		return this.body.descriptor(colour, depth);
	}

	/** @see GeometryProgram#rotate */
	void rotate() {
		this.body.rotate();
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

	/** @see GeometryProgram#release */
	void release() {
		this.body.release();
	}
}
