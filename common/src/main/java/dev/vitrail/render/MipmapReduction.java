package dev.vitrail.render;

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

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Fills the mip chain of a colour target, one level at a time, by averaging each level into the
 * next. Nothing of the pack takes part: this is the engine's own pipeline and its own two shaders.
 * <p>
 * It exists because 26.2 has no way to generate a mip chain. There is no {@code generateMipmaps} on
 * the command encoder and no other path to one, so a directive like {@code colortex0MipmapEnabled}
 * was read, counted and dropped. What the packs do with those levels is not decoration: BSL drives
 * its automatic exposure from {@code texture2DLod(colortex0, vec2(0.5), log2(viewHeight * R))},
 * which without a chain reads level nought at the centre of the screen, so the whole image is
 * exposed for one pixel and darkens wholesale the moment a jump moves that pixel from the ground to
 * the sky. The same pack reads lods for its depth of field and for the tiles of its bloom.
 * <p>
 * One render pass per level, which is what makes the chain correct rather than merely written. The
 * Vulkan backend ends a pass with a full memory barrier, so level N-1 is finished before the pass
 * that reads it to write level N begins; the two views are disjoint subresources of one image, and
 * a single pass writing what it samples would be a hazard rather than a saving.
 * <p>
 * The filter is a plain box of four texels, which is what {@code glGenerateMipmap} gave the packs
 * this is imitating. It is taken as four explicit fetches rather than as one bilinear fetch of the
 * level below: the two agree only while both dimensions are even, and a target sized on an odd
 * screen would otherwise drift towards one corner as the chain descends.
 * <p>
 * One pipeline per format, and no more: the colour state of a pipeline has to agree with the format
 * of the attachment exactly or {@code setPipeline} throws, and a pack's targets are not all of one
 * format. They are keyed by format for that reason alone, not to save compilations.
 */
final class MipmapReduction {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/mipmap_vertex");
	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/mipmap_fragment");

	private static final String SAMPLER = "InSampler";

	/** Two triangles, the same quad every full screen pass of the chain draws. */
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

	/**
	 * The bound view carries the source level and nothing else, so {@code textureSize} at nought is
	 * that level's size and the four fetches land on its texel centres: half a source texel either
	 * side of the point the destination texel covers.
	 */
	private static final String FRAGMENT = """
			#version 460 core

			uniform sampler2D InSampler;

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				vec2 half_texel = 0.5 / vec2(textureSize(InSampler, 0));
				ofFragData0 = 0.25 * (
					textureLod(InSampler, ofTexCoord + vec2(-half_texel.x, -half_texel.y), 0.0)
					+ textureLod(InSampler, ofTexCoord + vec2(half_texel.x, -half_texel.y), 0.0)
					+ textureLod(InSampler, ofTexCoord + vec2(-half_texel.x, half_texel.y), 0.0)
					+ textureLod(InSampler, ofTexCoord + vec2(half_texel.x, half_texel.y), 0.0));
			}
			""";

	private static final Supplier<String> LABEL = () -> "Vitrail mipmap reduction";

	private final ShaderSource source;
	private final Map<GpuFormat, RenderPipeline> pipelines = new EnumMap<>(GpuFormat.class);

	/** Formats whose pipeline would not compile, so that the failure is said once and not per frame. */
	private final Set<GpuFormat> refused = new LinkedHashSet<>();

	MipmapReduction() {
		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				return FRAGMENT_ID.equals(id) ? FRAGMENT : null;
			}

			return VERTEX_ID.equals(id) ? VERTEX : null;
		};
	}

	/**
	 * Fills every level past the base of one surface, reading the level above each time.
	 * <p>
	 * Must run outside any render pass. Silent and harmless on a surface with no chain, which is
	 * every target no program reads at a lod: the caller is not expected to sort them out first.
	 *
	 * @return false when the chain could not be filled, in which case the levels hold whatever they
	 *         held and a lod read falls back to what it read before there were chains
	 */
	boolean generate(CommandEncoder encoder, GpuDevice device, GpuBuffer quad, TargetSurface surface) {
		if (quad == null || surface == null || surface.levels() <= 1) {
			return false;
		}

		RenderPipeline pipeline = pipelineFor(device, surface.texture().getFormat());
		if (pipeline == null) {
			return false;
		}

		for (int level = 1; level < surface.levels(); level++) {
			GpuTextureView from = surface.levelView(level - 1);
			GpuTextureView into = surface.levelView(level);
			if (from == null || into == null) {
				return false;
			}

			// Loaded rather than cleared: the draw covers the level whole, so a clear would be one
			// more write of the same texels.
			try (RenderPass pass = encoder.createRenderPass(LABEL, into, Optional.empty())) {
				pass.setPipeline(pipeline);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setVertexBuffer(0, quad.slice());
				// The view holds one level, so a sampler that stops at lod nought reads exactly it.
				pass.bindTexture(SAMPLER, from,
						RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.draw(VERTICES, 1, 0, 0);
			}
		}

		return true;
	}

	/**
	 * The pipeline for one attachment format, compiled the first time it is asked for and kept.
	 * <p>
	 * The compiled form lives in the device cache, which the game empties at every resource reload,
	 * so this asks the device every time rather than trusting a flag of its own: that call is a
	 * {@code computeIfAbsent} on the device side and costs nothing once it has been made.
	 */
	private RenderPipeline pipelineFor(GpuDevice device, GpuFormat format) {
		if (this.refused.contains(format)) {
			return null;
		}

		RenderPipeline pipeline = this.pipelines.computeIfAbsent(format, MipmapReduction::build);
		if (device.precompilePipeline(pipeline, this.source).isValid()) {
			return pipeline;
		}

		this.refused.add(format);
		this.pipelines.remove(format);
		Vitrail.logger().error("The mipmap reduction did not compile for {}, the targets of that "
				+ "format keep level 0 alone and a lod read falls back to it", format);

		return null;
	}

	private static RenderPipeline build(GpuFormat format) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID,
						"pipeline/mipmap_reduction/" + format.name().toLowerCase(Locale.ROOT)))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder().withSampler(SAMPLER).build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withColorTargetState(new ColorTargetState(Optional.empty(), format,
						ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}
}
