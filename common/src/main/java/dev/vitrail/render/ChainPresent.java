package dev.vitrail.render;

import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.target.ChainPlan;
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

/**
 * Brings the chain's own colour to the game's target on a pack that ships no {@code final}.
 * <p>
 * <strong>What it stands in for.</strong> A pack's {@code final} is the one program that writes the
 * game's target rather than a colour target of the pack's, and most packs use it for their tone
 * mapping. A pack without one has simply finished in {@code colortex0} and expects what is there to
 * be the picture. Iris answers that by copying the target into the main framebuffer and says so in
 * as many words, {@code pipeline/FinalPassRenderer.java:113} making the pass optional and
 * {@code :268-277} doing the copy. Refusing such a pack, which is what this engine did until this
 * class existed, cost I Like Vanilla and Pegasus their whole picture.
 * <p>
 * <strong>A draw and not a copy, for {@link SceneSeed}'s reason and it is the same one.</strong>
 * {@code copyTextureToTexture} reaches {@code vkCmdCopyImage}, which reinterprets bits rather than
 * converting them, and the two formats here are not the same: the game's target is RGBA8_UNORM and
 * a pack's {@code colortex0} is commonly RG11B10_FLOAT. Both are thirty two bits wide, so a copy
 * passes every check the Java side makes and hands back nonsense. Iris can copy because both sides
 * of its transfer are GL textures under a conversion its own call performs; here the conversion has
 * to be a sample and a write.
 * <p>
 * <strong>Loaded and never cleared</strong>, as the final it stands in for is: a chain that does not
 * cover every pixel leaves the world showing underneath, which is Iris's behaviour and the one a
 * pack with a partial chain is written against.
 */
final class ChainPresent {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/present_vertex");
	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/present_fragment");

	private static final String SAMPLER = "InSampler";

	private static final String LABEL = "Vitrail chain present";

	/** The game's own colour target, the one format this ever writes into. */
	private static final GpuFormat SCREEN_FORMAT = GpuFormat.RGBA8_UNORM;

	/** Two triangles over the whole screen, the quad every full screen pass of the chain draws. */
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
	 * The alpha is written as one and not as the target's, which is the same choice the final's own
	 * pipeline makes one line further down in {@link PackPass}: the interface is drawn over this
	 * afterwards and reads that channel.
	 */
	private static final String FRAGMENT = """
			#version 460 core

			uniform sampler2D InSampler;

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				ofFragData0 = vec4(texture(InSampler, ofTexCoord).rgb, 1.0);
			}
			""";

	private final ChainPlan.Attachment from;
	private final RenderPipeline pipeline;
	private final ShaderSource source;

	private boolean reported;

	ChainPresent(ChainPlan.Attachment from) {
		this.from = from;
		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				return FRAGMENT_ID.equals(id) ? FRAGMENT : null;
			}

			return VERTEX_ID.equals(id) ? VERTEX : null;
		};

		this.pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/chain_present"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler(SAMPLER)
						.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withColorTargetState(new ColorTargetState(Optional.empty(), SCREEN_FORMAT,
						ColorTargetState.WRITE_COLOR))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}

	/** Called every frame: a resource reload empties the pipeline cache. */
	boolean prepare(GpuDevice device) {
		if (device.precompilePipeline(this.pipeline, this.source).isValid()) {
			return true;
		}

		if (!this.reported) {
			this.reported = true;
			Vitrail.logger().error("The pass that brings {} to the screen did not compile, and this "
					+ "pack serves no final, so nothing the chain wrote is shown",
					TargetName.canonical(this.from.target()));
		}

		return false;
	}

	/**
	 * Draws the target the plan named over the game's own, everywhere.
	 *
	 * @param into    the game's colour target for this frame
	 * @param targets where the colour target is looked up, every frame and never held: a resize
	 *                replaces the images and one kept across it draws from a texture that is closed
	 * @return false when the target or the quad is missing, which is a frame in the middle of a
	 *         resize. The screen then keeps the world the game drew under the chain
	 */
	boolean draw(CommandEncoder encoder, GpuBuffer quad, GpuTextureView into, ColorTargets targets) {
		GpuTextureView view = targets.view(this.from.target(), this.from.side());
		if (quad == null || into == null || view == null) {
			return false;
		}

		try (RenderPass pass = encoder.createRenderPass(() -> LABEL, into, Optional.empty())) {
			pass.setPipeline(this.pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setVertexBuffer(0, quad.slice());
			// NEAREST, the target being the size of the screen: one texel is one pixel and the
			// value wanted is the one the chain wrote, not a blend of it with its neighbour.
			pass.bindTexture(SAMPLER, view,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		return true;
	}
}
