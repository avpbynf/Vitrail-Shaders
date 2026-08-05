package dev.vitrail.render;

import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Draws the game's opaque scene into the target the terrain would have written, standing in for
 * every gbuffers stage that does not run: the sky, the entities, the particles and the weather.
 * <p>
 * It is cut around the pack's own terrain rather than painted over it. The opaque and cutout chunk
 * passes write that target themselves, and they mark every pixel they covered as they go, so what
 * lands here is the game's picture everywhere the pack answered for nothing. Without the cut the
 * two would fight and the game would win, because it is drawn second. The translucent pass is
 * different again: it draws after this and blends onto what the two of them left.
 * <p>
 * This is not a fallback and should not be read as one. The first draw buffer of the terrain pass
 * is, by the definition of the OptiFine model, where the world's colour ends up, so it is the one
 * place where putting the game's own picture back is the right answer rather than a guess. Which
 * target that is comes from the plan and is not always colortex0: Sildur's serves its terrain
 * through {@code gbuffers_textured}, whose draw buffers start at colortex4. The whole class goes
 * away the day the gbuffers run, and the mask with it.
 * <p>
 * A draw and not a copy. {@code copyTextureToTexture} ends up on {@code vkCmdCopyImage}, which
 * reinterprets bits instead of converting them, and the Java side only checks that both formats
 * carry a colour aspect. The main target is RGBA8_UNORM and colortex0 is RG11B10_FLOAT on most
 * packs; both are thirty two bits wide, so a copy passes every check and hands back nonsense.
 * <p>
 * What the seed cannot repair has to be said out loud rather than assumed: the scene it carries
 * is already tone mapped, already gamma corrected, and already has vanilla fog. A pack that
 * exposes automatically works on an image that was exposed once already. The picture is readable
 * and wrong, which is the most misleading shape a result can take, so nothing about a pass is
 * ever proved through it.
 */
final class SceneSeed {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/seed_vertex");
	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/seed_fragment");

	private static final String SAMPLER = "InSampler";

	/** Where the pack's own opaque geometry has already written, and where this must not paint. */
	private static final String COVERAGE = "CoverageSampler";

	private static final Supplier<String> LABEL = () -> "Vitrail scene seed";

	/** Two triangles, the same quad the pass itself draws, which is why it is passed in. */
	private static final int VERTICES = 6;

	private static final String VERTEX = """
			#version 460 core

			in vec3 Position;
			in vec2 UV0;

			out vec2 ofTexCoord;

			void main() {
				ofTexCoord = UV0;
				gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			}
			""";

	private static final String FRAGMENT = """
			#version 460 core

			uniform sampler2D InSampler;
			uniform sampler2D CoverageSampler;

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				if (texture(CoverageSampler, ofTexCoord).r > 0.5) {
					discard;
				}

				ofFragData0 = texture(InSampler, ofTexCoord);
			}
			""";

	private final ChainPlan.Seed seed;
	private final RenderPipeline pipeline;
	private final ShaderSource source;

	private boolean reported;

	/**
	 * @param seed        which target the scene goes into and on which half, both taken from the
	 *                    geometry program it stands in for rather than assumed
	 * @param destination that target's format as the pack declared it. The caller is the one that
	 *                    checks the target exists: a place that has nowhere to put the scene draws
	 *                    no seed and says so, it does not refuse the pack
	 */
	SceneSeed(ChainPlan.Seed seed, GpuFormat destination) {
		this.seed = seed;
		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				return FRAGMENT_ID.equals(id) ? FRAGMENT : null;
			}

			return VERTEX_ID.equals(id) ? VERTEX : null;
		};

		this.pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/scene_seed"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler(SAMPLER)
						.withSampler(COVERAGE)
						.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				// The format of the target as the pack declared it, not the one of the main
				// target: setting a pipeline whose colour state disagrees with the attachment
				// throws, and the message names both formats, which is the useful failure.
				.withColorTargetState(new ColorTargetState(Optional.empty(), destination,
						ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}

	/** Where the scene goes, which is the first draw buffer of the terrain program. */
	int target() {
		return this.seed.target();
	}

	/** The half that terrain program would have written, so that the chain reads what it wrote. */
	TargetSchedule.Side side() {
		return this.seed.side();
	}

	/** The geometry program this stands in for, for the log. */
	String from() {
		return this.seed.from();
	}

	/** Called every frame: a resource reload empties the pipeline cache. */
	boolean prepare(GpuDevice device) {
		if (device.precompilePipeline(this.pipeline, this.source).isValid()) {
			return true;
		}

		if (!this.reported) {
			this.reported = true;
			Vitrail.logger().error("The scene seed did not compile, {} keeps its clear colour",
					TargetName.canonical(this.seed.target()));
		}

		return false;
	}

	/**
	 * Draws the scene over {@code into}, everywhere {@code covered} says the pack's own geometry has
	 * not already written. Only worth calling once {@link #prepare} has said the pipeline is usable.
	 *
	 * @param covered the mask, which is read and never judged: an image of nought everywhere is a
	 *                mask that hides nothing and the seed then covers the target whole, which is
	 *                what the frames with no terrain of the pack's in them have to look like
	 * @return false when a side of the draw is missing, in which case the target keeps its clear
	 *         colour rather than holding half an image
	 */
	boolean draw(CommandEncoder encoder, GpuBuffer quad, GpuTextureView scene,
			GpuTextureView covered, GpuTextureView into) {
		if (quad == null || scene == null || covered == null || into == null) {
			return false;
		}

		// Loaded rather than cleared: the clears have already run, and the draw no longer covers the
		// target whole in any case.
		try (RenderPass pass = encoder.createRenderPass(LABEL, into, Optional.empty())) {
			pass.setPipeline(this.pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setVertexBuffer(0, quad.slice());
			pass.bindTexture(SAMPLER, scene,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			// NEAREST, and the mask is the size of the screen, so a texel is a pixel and the answer
			// is the one the geometry wrote. Filtered, the edge of every block would read as half
			// covered and the threshold would move it by half a pixel.
			pass.bindTexture(COVERAGE, covered,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		return true;
	}

	/**
	 * Nothing to free. A pipeline is not a resource this class owns: the compiled form lives in
	 * the device cache, which the game empties on its own at every resource reload. The method
	 * stays so that the caller releases the seed the same way it releases the targets.
	 */
	void release() {
	}
}
