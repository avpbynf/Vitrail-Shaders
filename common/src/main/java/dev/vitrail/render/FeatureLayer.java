package dev.vitrail.render;

import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
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

import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Catches the game's translucent features, the player's own body first among them, and hands them
 * to the pack's image.
 * <p>
 * They are drawn between the seed and the world's translucents, into the game's target alone, and
 * the pack's final overwrites that target from colortex0: everything they painted vanished, which
 * read as a player with a cape and no body in third person. The layer redirects those draws with
 * the game's own override, {@code RenderSystem.outputColorTextureOverride}, the exact mechanism the
 * game uses for its always-on-top features, and composes the result onto the half of the pack's
 * target the world's translucents are about to blend onto, in the order vanilla draws: features
 * first, then water.
 * <p>
 * <strong>This is a stopgap and it is measured as one.</strong> It is the seed's compromise applied
 * to a handful more pixels: the features arrive tone mapped, after the deferreds, so they are
 * visible and flat, with none of the pack's lighting or shadow on them. It builds no piece of the
 * real milestone, entities through the pack's gbuffers, and it goes away with that milestone the
 * same day the seed does.
 * <p>
 * The composition is premultiplied, {@code ONE, ONE_MINUS_SRC_ALPHA}, and that is not a taste. The
 * game's pipelines blend {@code SRC_ALPHA} onto a layer cleared to transparent black, which leaves
 * the colour already multiplied by its alpha; blending by alpha a second time would darken every
 * edge.
 */
final class FeatureLayer {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/features_vertex");
	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/features_fragment");

	private static final String SAMPLER = "InSampler";

	private static final Supplier<String> LABEL = () -> "Vitrail feature layer";

	private static final Vector4fc TRANSPARENT = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

	/**
	 * The game's own colour format, and it has to be: the features are drawn by the game's
	 * pipelines, which declare this format for their target, and a Vulkan pipeline bound to an
	 * attachment of another format is undefined behaviour rather than a clean refusal.
	 */
	private static final GpuFormat FORMAT = GpuFormat.RGBA8_UNORM;

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

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				ofFragData0 = texture(InSampler, ofTexCoord);
			}
			""";

	/** Where the composition lands: the seed's target, on its after-deferred half. */
	private final ChainPlan.Attachment into;

	private final RenderPipeline pipeline;
	private final ShaderSource source;

	private TextureTarget layer;
	private boolean broken;
	private boolean reported;

	/**
	 * @param into        the first draw buffer of the translucent geometry pass, which is where the
	 *                    world's own translucents are about to blend, taken from the plan rather
	 *                    than assumed
	 * @param destination that target's format as the pack declared it, for the pipeline's own
	 *                    colour state, the same rule the seed follows
	 */
	FeatureLayer(ChainPlan.Attachment into, GpuFormat destination) {
		this.into = into;
		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				return FRAGMENT_ID.equals(id) ? FRAGMENT : null;
			}

			return VERTEX_ID.equals(id) ? VERTEX : null;
		};

		this.pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID,
						"pipeline/feature_layer"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder().withSampler(SAMPLER).build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withColorTargetState(new ColorTargetState(
						Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), destination,
						ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}

	/**
	 * Makes the layer exist at the screen's size and empties it to transparent, and answers the
	 * view the game's overrides should point at, or null when there is nothing safe to redirect
	 * into. Must run outside any render pass.
	 */
	GpuTextureView open(GpuDevice device, int width, int height) {
		if (this.broken || width <= 0 || height <= 0) {
			return null;
		}

		if (this.layer != null
				&& (this.layer.width != width || this.layer.height != height)) {
			this.layer.destroyBuffers();
			this.layer = null;
		}

		if (this.layer == null) {
			try {
				// No depth of its own: the redirected draws test against the game's depth, which
				// the override for depth keeps pointing at, so entities still hide behind walls.
				this.layer = new TextureTarget("Vitrail features", width, height, false, FORMAT);
			} catch (RuntimeException e) {
				this.broken = true;
				Vitrail.logger().error("Vitrail could not allocate the feature layer, so the "
						+ "game's translucent features stay on the game's target", e);

				return null;
			}
		}

		device.createCommandEncoder().clearColorTexture(this.layer.getColorTexture(), TRANSPARENT);

		return this.layer.getColorTextureView();
	}

	/** Called every frame: a resource reload empties the pipeline cache. */
	boolean prepare(GpuDevice device) {
		if (device.precompilePipeline(this.pipeline, this.source).isValid()) {
			return true;
		}

		if (!this.reported) {
			this.reported = true;
			Vitrail.logger().error("The feature layer did not compile, so the game's translucent "
					+ "features are not composed onto {}", TargetName.canonical(this.into.target()));
		}

		return false;
	}

	/** Where the composition lands, for the caller to look the view up on the right half. */
	ChainPlan.Attachment into() {
		return this.into;
	}

	/**
	 * Composes the layer over {@code into}, premultiplied. Must run outside any render pass, after
	 * the redirected draws and before the world's translucents, which is the order vanilla drew
	 * them in.
	 */
	void compose(CommandEncoder encoder, GpuBuffer quad, GpuTextureView into) {
		if (this.layer == null || quad == null || into == null) {
			return;
		}

		try (RenderPass pass = encoder.createRenderPass(LABEL, into, Optional.empty())) {
			pass.setPipeline(this.pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setVertexBuffer(0, quad.slice());
			pass.bindTexture(SAMPLER, this.layer.getColorTextureView(),
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}
	}

	void release() {
		if (this.layer != null) {
			this.layer.destroyBuffers();
			this.layer = null;
		}
	}
}
