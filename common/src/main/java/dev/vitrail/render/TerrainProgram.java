package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.SodiumVertex;
import dev.vitrail.glsl.VertexInputs;
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
public final class TerrainProgram implements DumpedProgram {

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
				// claimed: no sibling of this family marks a chunk pass's pixels for it. The opaque
				// halves ask for the mask themselves, which covers answers and GeometryProgram can
				// still turn down, and the translucent one is drawn after the seed.
				pass.covers(), false, pass.afterDeferred(),
				// Sodium's own, taken from ShaderChunkRenderer.createShader: the pass this is bound
				// into was opened for that pipeline and a difference of topology would be a
				// difference nobody declared.
				PrimitiveTopology.QUADS,
				// Nothing is culled in the shadow map. What matters there is which surface is nearest
				// the light and not which way it faces, and a wall drawn on one side only leaks light
				// through its back. Iris cuts it for the same reason.
				// Nothing of the game's bound beside the mesh: the chunk pass reads Sodium's own
				// buffers and none of them is a name this program declares.
				!pass.shadow(), depthState(pass), pass.stage(), null),
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
	 * Reads and translates the six programs the chunk renderer draws with, and says what mesh they
	 * were written against. A pass the pack ships nothing for is absent, and keeps the game's own
	 * shader.
	 * <p>
	 * A second reading of the pack, which costs one plan build for all six. The chain's own reading
	 * translates what the chain runs, and a gbuffers program is not in it: folding this into that
	 * walk would make every place pay for programs only this step uses.
	 * <p>
	 * <strong>Read where the pack is loaded and not where a chunk pass first asks.</strong> What the
	 * mesh carries is the union of what these six read, and the mesh has to be settled before Sodium
	 * builds the chunk renderer that meshes with it. The device is not touched here: the pipelines
	 * are built by {@link #build}, at the first draw and against the format the renderer hands over.
	 */
	static PackProgram.Terrain read(Path packPath, String place, Map<String, OptionValue> chosen,
			String profile, PackValues values) throws IOException {
		// Which of the two colours the six vertex stages read, settled here because this is where
		// the pack's own reading of separateAo is held. Two shapes for one word Sodium already
		// writes, so it decides both which element a stage reads and whether the mesh carries the
		// second one at all.
		VertexInputs inputs = values.separateAo()
				? VertexInputs.TERRAIN_SEPARATE_AO
				: VertexInputs.TERRAIN;
		PackProgram.Terrain read = PackProgram.loadTerrain(packPath, place, chosen, profile, inputs);
		if (read.programs().isEmpty()) {
			Vitrail.logger().warn("{} serves no terrain program with both stages in {}, so the "
					+ "world keeps the game's own shader", packPath.getFileName(),
					place.isEmpty() ? "its root" : place);
		}

		return read;
	}

	/**
	 * Equips the programs already read with everything that needs a format and a plan, keyed by the
	 * pass each one serves.
	 *
	 * @param format the chunk mesh format, handed in rather than looked up, because nothing in this
	 *               module is allowed to name Sodium
	 */
	static Map<TerrainPass, TerrainProgram> build(Map<TerrainPass, PackProgram.Loaded> loaded,
			PackValues values, int load, VertexFormat format, ChainPlan plan,
			TargetPlan chainTargets, boolean chainRuns, ColorTargets targets) {
		try {
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
		} catch (RuntimeException e) {
			Vitrail.logger().error("Could not equip the terrain programs, so the world keeps the "
					+ "game's own shader", e);

			return Map.of();
		}
	}

	/**
	 * Checks that the mesh the renderer bound is the one these programs were written against, and
	 * says so once when it is not.
	 *
	 * @param carried the elements the programs declare, which is what the pack was read for and what
	 *                the format was built from. Compared rather than assumed because the two are
	 *                settled at two moments, the reading of the pack and the rebuild of the chunk
	 *                renderer, and nothing in between makes them agree by construction
	 */
	static boolean carries(VertexFormat format, List<String> carried) {
		List<String> elements = format.getElements().stream()
				.map(VertexFormatElement::name)
				.toList();
		if (elements.equals(carried)) {
			return true;
		}

		// A silent failure otherwise, and the worst kind. An element the shader does not declare
		// moves the location of every element after it without a word, so the picture stays a
		// picture and the texture coordinates come out of the light map.
		//
		// The format follows the pack now, so reaching here is a defect of this engine and not
		// something the player did. Refusing the terrain alone was worse than refusing everything:
		// the world then came from the game while the sky came from the pack, which puts the sky in
		// front of the trees and reads as a broken sky rather than as a terrain program that never
		// ran. So the caller stops the whole pack.
		Vitrail.logger().error("The chunk mesh carries {} and these programs declare {}, so no terrain "
				+ "program can be drawn and this pack is put away rather than drawn by halves",
				elements, carried);

		return false;
	}

	/**
	 * The pipeline this program is drawn with, compiled where the renderer asks for its shader.
	 *
	 * @see GeometryProgram#prepare
	 */
	RenderPipeline prepare(GpuDevice device, GpuTextureView atlas) {
		return this.body.prepare(device, atlas);
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
	 * Takes the sampler the game configured for the block atlas, mipmaps and filtering included.
	 *
	 * @see GeometryProgram#sampler(GpuSampler)
	 */
	void sampler(GpuSampler sampler) {
		this.body.sampler(sampler);
	}

	/**
	 * Whether the pipeline a pass has bound is this program's.
	 *
	 * @see GeometryProgram#owns
	 */
	boolean owns(RenderPipeline bound) {
		return this.body.owns(bound);
	}

	/**
	 * Whether this program can still be served, which everything built on it has to agree with.
	 *
	 * @see GeometryProgram#servable
	 */
	boolean servable() {
		return this.body.servable();
	}

	/**
	 * Whether this pass really marks the pixels it wrote.
	 *
	 * @see GeometryProgram#covers
	 */
	boolean covers() {
		return this.body.covers();
	}

	/**
	 * The pass this program is drawn into, or null to leave the chunk renderer its own.
	 *
	 * @see GeometryProgram#descriptor
	 */
	RenderPassDescriptor descriptor(GpuTextureView colour, GpuTextureView depth) {
		return this.body.descriptor(colour, depth);
	}

	/**
	 * Rotates the ring buffer, once the frame's draw has been recorded.
	 *
	 * @see GeometryProgram#rotate
	 */
	void rotate() {
		this.body.rotate();
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
	 * Closes this program's block and the placeholder textures it made.
	 *
	 * @see GeometryProgram#release
	 */
	void release() {
		this.body.release();
	}
}
