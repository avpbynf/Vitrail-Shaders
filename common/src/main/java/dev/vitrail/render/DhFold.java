package dev.vitrail.render;

import dev.vitrail.dh.DhDepth;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Vector2f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Folds Distant Horizons' far terrain into the game's depth, so that everything downstream of the
 * game's depth knows the far terrain is there.
 * <p>
 * It is one pass and not three because of where it runs. Fold the game's own image once, before the
 * depth the pack reads past the hand with is taken, and the three copies {@link PackDepth} makes,
 * the cut the scene seed is drawn against, and the depth every family the game still draws itself
 * tests against all carry the far terrain without any of them knowing this class exists. Folding
 * into the copies instead would be three conversions, would leave the seed and the game's own
 * translucents reading a world with a hole in it, and would have to be kept in step by hand.
 * <p>
 * <strong>The conversion, and why it is exactly two numbers.</strong> DH rasterises with the game's
 * matrix and its own z row, {@code RenderUtil.setDhProjectionMatrix} overwriting that row and
 * nothing else. Two projections that differ only in their z row share a w row, so the eye distance
 * behind a texel falls out of one and back into the other with no matrix and no unprojection at
 * all: with a row written {@code (scale, offset)}, a texel holds {@code offset / d - scale}, so
 * {@code d = offset / (z + scale)} on the way out and the composition of the two is
 * {@code z' = a * z + b} with {@code a = offsetGame / offsetFar} and
 * {@code b = a * scaleFar - scaleGame}. One multiply and one add, per texel, and it is exact rather
 * than fitted.
 * <p>
 * <strong>One test does the work of three.</strong> A folded value at or below nought is thrown
 * away, and that covers every case at once: DH clears its image to nought, which is its own far
 * plane and also every texel it drew nothing into, and both land below the game's far plane once
 * converted. So does anything genuinely past the game's far plane, which is the honest limit of
 * this pass and is written out below.
 * <p>
 * <strong>What cannot be folded.</strong> The game clips at {@code max(renderDistance * 4,
 * cloudRange * 16)} blocks, two thousand and forty-eight of them with both sliders at their default,
 * and a reversed Z has no value left for anything beyond that: the far plane IS nought. DH's own
 * terrain reaches the same two thousand at its default, so the band that matters is inside, and it
 * grows with the render distance rather than against it. What sits past the game's far plane keeps
 * reading as sky, exactly as the whole of the far terrain did before this pass existed.
 * <p>
 * The colour attachment is written by nothing and is there because the encoder demands one: a pass
 * with a depth attachment alone is legal, but the first colour attachment may not be absent, and a
 * pipeline built without a colour target state is given one. So the game's own colour rides along
 * with its write mask shut.
 */
final class DhFold {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/distant_depth_vertex");
	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/distant_depth_fragment");

	/** DH's own depth image, as it stands after DH has drawn and composited its colour. */
	private static final String SAMPLER = "InSampler";

	private static final String UNIFORM_BLOCK = "OfDistantFold";

	/** One vec2, rounded up to what a uniform buffer binding is allowed to start on. */
	private static final int BLOCK_BYTES = 16;

	/** Two triangles, the quad every full screen pass of this engine draws. */
	private static final int VERTICES = 6;

	/**
	 * The game's own near plane, which {@code Camera.update} passes and never varies. The far plane
	 * is the frame's and is handed in, {@code Camera.depthFar} moving with two sliders.
	 */
	private static final float NEAR = 0.05F;

	private static final String LABEL = "Vitrail distant depth";

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
	 * The test is written as a negation so that a NaN goes the same way an empty texel does. A DH
	 * image is cleared rather than left, so nothing should decode to one, but a fragment that wrote
	 * a NaN into the game's depth would take every later test of the frame with it.
	 */
	private static final String FRAGMENT = """
			#version 460 core

			uniform sampler2D InSampler;

			layout(std140) uniform OfDistantFold {
				vec2 of_DistantFold;
			};

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				float folded = of_DistantFold.x * texture(InSampler, ofTexCoord).r + of_DistantFold.y;
				if (!(folded > 0.0)) {
					discard;
				}

				ofFragData0 = vec4(0.0);
				gl_FragDepth = folded;
			}
			""";

	private static final ShaderSource SOURCE = (id, type) -> {
		if (type == ShaderType.FRAGMENT) {
			return FRAGMENT_ID.equals(id) ? FRAGMENT : null;
		}

		return VERTEX_ID.equals(id) ? VERTEX : null;
	};

	/** Read every frame and never held: DH moves both its planes with the world around the player. */
	private final Vector2f far = new Vector2f();

	private RenderPipeline pipeline;

	/**
	 * Which colour format the kept pipeline was built for. A pipeline carries a state per colour
	 * target and the pass refuses one whose format is not the attachment's, so the format the game's
	 * own target happens to be is read rather than assumed.
	 */
	private GpuFormat built;

	private MappableRingBuffer terms;

	/** Said once and not per frame, so that a driver that will not have this shader is readable. */
	private boolean refused;

	/** Said once, because what a player wants to know is that it happened at all. */
	private boolean said;

	/**
	 * Folds DH's far terrain into the game's depth. Must run on the render thread and outside any
	 * render pass, which opens one of its own.
	 * <p>
	 * Answers false whenever there is nothing to fold, and the ordinary reasons are the quiet ones:
	 * DH is not installed, has not started, or has not drawn a frame of this world yet. The screen
	 * then looks exactly as it did before this class existed.
	 *
	 * @param colour   the game's own colour target, written by nothing here
	 * @param depth    the game's depth as it stands, with its opaque world in it
	 * @param depthFar the plane the game clips at this frame, which is {@code Camera.depthFar}
	 */
	boolean fold(CommandEncoder encoder, GpuDevice device, GpuBuffer quad, GpuTextureView colour,
			GpuTextureView depth, float depthFar) {
		if (this.refused || quad == null || colour == null || depth == null || depthFar <= NEAR) {
			return false;
		}

		GpuTextureView distant = DhDepth.view();
		if (distant == null || !DhDepth.zRow(this.far)) {
			return false;
		}

		// The two images are made against the same window and resized with it, so a disagreement is
		// one frame of a resize where one of them has moved and the other has not. Stretching DH's
		// image over the screen for that frame would put its terrain somewhere it is not.
		if (distant.getWidth(0) != depth.getWidth(0)
				|| distant.getHeight(0) != depth.getHeight(0)) {
			return false;
		}

		RenderPipeline compiled = pipeline(device, colour.texture().getFormat());
		if (compiled == null || !ensure()) {
			return false;
		}

		float scaleGame = NEAR / (depthFar - NEAR);
		float offsetGame = NEAR * depthFar / (depthFar - NEAR);
		float scale = offsetGame / this.far.y;
		float offset = scale * this.far.x - scaleGame;

		// Written through the game's own builder rather than into the buffer by hand, which is what
		// every other block of this engine does: two floats are a layout too, and the two would
		// drift.
		this.terms.rotate();
		try (GpuBufferSlice.MappedView view = this.terms.currentBuffer().map(false, true)) {
			Std140Builder.intoBuffer(view.data()).putVec2(scale, offset);
		}

		// Loaded on both attachments: the world's own depth is exactly what this pass tests against,
		// and a clear of either would be the end of the frame rather than a fold into it.
		try (RenderPass pass = encoder.createRenderPass(() -> LABEL, colour, Optional.empty(), depth,
				OptionalDouble.empty())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(UNIFORM_BLOCK, this.terms.currentBuffer());
			pass.setVertexBuffer(0, quad.slice());
			// NEAREST, and it is the same reason the depth window binds NEAREST: one destination
			// texel covers one source texel, and a filtered depth is an average of two surfaces that
			// stands in front of neither.
			pass.bindTexture(SAMPLER, distant,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		if (!this.said) {
			this.said = true;
			// The planes rather than the terms, because the planes are what a player can compare
			// against two sliders, and it is the first thing to look at when the band is in the
			// wrong place. They come straight back out of the row: the far one is the ratio of the
			// two terms and the near one is the offset over one plus the scale.
			Vitrail.logger().info("Distant Horizons' far terrain is folded into the game's depth, "
					+ "from its own {} to {} blocks into the game's {} to {}, so what a pack reads "
					+ "as a depth carries it out to the nearer of the two far planes",
					this.far.y / (1.0F + this.far.x), this.far.y / this.far.x, NEAR, depthFar);
		}

		return true;
	}

	/** Frees the buffer behind the terms. The pipeline is the device's and cannot be handed back. */
	void release() {
		if (this.terms != null) {
			this.terms.close();
			this.terms = null;
		}
	}

	/**
	 * Makes the buffer the two terms are written into exist. Nothing here depends on the size of the
	 * screen, so it is allocated once and kept across a resize.
	 */
	private boolean ensure() {
		if (this.terms != null) {
			return true;
		}

		try {
			// Three buffers and a fence per turn, so a frame never writes over what the previous one
			// is still being read for.
			this.terms = new MappableRingBuffer(() -> LABEL,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, BLOCK_BYTES);
		} catch (RuntimeException e) {
			this.refused = true;
			Vitrail.logger().error("Vitrail could not allocate the buffer the distant depth "
					+ "conversion is driven from, so the far terrain of Distant Horizons stays flat "
					+ "for the rest of this session", e);

			return false;
		}

		return true;
	}

	/**
	 * The pipeline, compiled the first time it is asked for and kept until the colour format under
	 * it moves.
	 * <p>
	 * The compiled form lives in the device cache, which the game empties at every resource reload,
	 * so this asks the device every time rather than trusting a flag of its own: that call is a
	 * {@code computeIfAbsent} on the device side and costs nothing once it has been made.
	 */
	private RenderPipeline pipeline(GpuDevice device, GpuFormat format) {
		if (this.pipeline == null || this.built != format) {
			this.pipeline = build(format);
			this.built = format;
		}

		if (device.precompilePipeline(this.pipeline, SOURCE).isValid()) {
			return this.pipeline;
		}

		this.refused = true;
		this.pipeline = null;
		Vitrail.logger().error("The distant depth conversion did not compile, so the far terrain of "
				+ "Distant Horizons stays at the far plane for every depth this pack reads");

		return null;
	}

	private static RenderPipeline build(GpuFormat format) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/distant_depth"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER)
						.withSampler(SAMPLER)
						.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withColorTargetState(new ColorTargetState(Optional.empty(), format,
						ColorTargetState.WRITE_NONE))
				// Greater and not greater or equal, in a reversed Z where greater is nearer: what
				// the game has already drawn stands, and a far terrain that ties with it changes
				// nothing worth a write.
				.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, true))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}
}
