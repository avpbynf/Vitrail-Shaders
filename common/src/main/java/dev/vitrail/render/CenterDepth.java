package dev.vitrail.render;

import dev.vitrail.uniform.Smoothed;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
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

import java.util.Optional;

/**
 * The depth at the centre of the screen, smoothed by the pack's own half life, kept in one texel.
 * <p>
 * This is what a pack reads as {@code centerDepthSmooth} and uses as the point it focuses on. A
 * depth of field built on it and handed a nought focuses on the camera, so everything past a few
 * blocks is at the maximum blur radius and the whole screen is soft; that is what Body Camera did,
 * and nothing about it fails.
 * <p>
 * <strong>The value never comes back to the processor, and that is Iris's shape rather than an
 * optimisation.</strong> {@code CenterDepthSampler} draws one texel from the depth, mixes it with
 * what it drew last frame, and hands the pack a sampler:
 * {@code CompositeDepthTransformer} takes the pack's {@code uniform float centerDepthSmooth} out and
 * puts {@code texture(iris_centerDepthSmooth, vec2(0.5)).r} behind every use of the name.
 * {@code GlslTranslator.moveCenterDepth} does that half, this class draws the texel.
 * <p>
 * Two textures and not one: a render pass may not sample what it is attached to, so the frame reads
 * the texel the previous frame wrote and writes the other. The pair costs eight bytes.
 * <p>
 * The mixing factor is worked out here rather than in the shader, from
 * {@link Smoothed#blend}, so that the one place that knows what a half life in deciseconds means is
 * the one place both this and a pack's own {@code smooth()} read it from.
 * <p>
 * <strong>Two ways in for a NaN, and Iris closes the first of them.</strong> A fresh texture holds
 * whatever the driver left, and no choice of factor writes that out of the accumulator:
 * {@code mix(x, y, 1.0)} is {@code x * 0.0 + y}, and a NaN times nought is still a NaN, so one bad
 * decode would poison every frame after it. The fragment stage therefore carries Iris's own test,
 * taken every frame there as here. What the first draw does with the rest is where the two part:
 * Iris has no case for it and folds with its ordinary factor, so how much of a fresh texel holding
 * a finite value survives is whatever that factor happens to be, while the factor of one used here
 * writes it out outright. Only a finite one, though: an infinity multiplied by nought is a NaN as
 * well, so a texel decoding to an infinity costs that first frame and is pulled back by the test
 * on the second.
 * <p>
 * The other way in is the factor itself, and there the arithmetic runs out on both sides. A half
 * life of nought gives an infinite decay constant, the frame clock quantises a duration to the
 * whole millisecond, and an infinity times a nought is a NaN: a pack that asks for no smoothing at
 * all turns the texel to NaN on every frame shorter than a millisecond, and the test above cannot
 * pull it back out, since the factor is NaN again the frame after. Iris works the same factor out
 * in its own fragment stage and has nothing there for that case. Folded with a factor of nought
 * here instead, which holds the accumulator where it stands and is the answer
 * {@link Smoothed#updateAndGet} already gives the values accumulated on this side.
 */
final class CenterDepth {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/center_depth_vertex");
	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/center_depth_fragment");

	/** The whole screen's depth, in the window the pack reads depth in. */
	private static final String SCENE = "InSampler";

	/** The one texel this pass wrote last frame, which is the accumulator. */
	private static final String BEFORE = "PrevSampler";

	private static final String UNIFORM_BLOCK = "OfCenterDepth";

	/** One float, rounded up to what a uniform buffer binding is allowed to start on. */
	private static final int BLOCK_BYTES = 16;

	/** Two triangles, the quad every full screen pass of this engine draws. */
	private static final int VERTICES = 6;

	/** One float a texel, like the depth images this reads from. Iris stores R32F too. */
	private static final GpuFormat FORMAT = GpuFormat.R32_FLOAT;

	/** Two and not one, for the reason the class javadoc gives: a pass cannot sample its target. */
	private static final int SURFACES = 2;

	private static final String LABEL = "Vitrail centre depth";

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
	 * The coordinate is the centre of the screen because the target is one texel: the quad covers it
	 * whole, so the one fragment this pass ever has sits at the middle of it and interpolates to a
	 * half in both directions. Iris writes the half out rather than interpolating to it, and its
	 * target is one texel too: both of its colour textures are made {@code 1x1}
	 * ({@code CenterDepthSampler.setupColorTexture}) and the framebuffer's one attachment is one of
	 * them, the viewport of a single texel over it agreeing rather than cutting anything down.
	 * <p>
	 * The test on the accumulator is Iris's own, and the class javadoc says why the factor cannot
	 * stand in for it.
	 */
	private static final String FRAGMENT = """
			#version 460 core

			uniform sampler2D InSampler;
			uniform sampler2D PrevSampler;

			layout(std140) uniform OfCenterDepth {
				float of_CenterBlend;
			};

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				float here = texture(InSampler, ofTexCoord).r;
				float before = texture(PrevSampler, ofTexCoord).r;

				if (isnan(before)) {
					before = here;
				}

				ofFragData0 = vec4(mix(before, here, of_CenterBlend));
			}
			""";

	private static final ShaderSource SOURCE = (id, type) -> {
		if (type == ShaderType.FRAGMENT) {
			return FRAGMENT_ID.equals(id) ? FRAGMENT : null;
		}

		return VERTEX_ID.equals(id) ? VERTEX : null;
	};

	private RenderPipeline pipeline;

	/**
	 * That this pass is not going to draw at all, whether the pair or the pipeline is what failed.
	 * <p>
	 * Read by both, so that a driver that will not have this is said once and not per frame. There
	 * is nothing here for the size of the screen to lift, which is where this differs from
	 * {@link PackDepth}: its images are the size of the window and a resize is a real second chance
	 * at an allocation, while this pair is one texel and would ask for the same eight bytes again
	 * every frame for the rest of the session.
	 */
	private boolean refused;

	private TargetSurface[] texels;
	private MappableRingBuffer factor;

	/** Which of the two holds the value, which is the one the pack reads and the one this reads. */
	private int current;

	/** Whether anything has been drawn since the pair was allocated; see the class javadoc. */
	private boolean primed;

	/**
	 * The smoothed depth of the centre of the screen, or null while nothing has drawn it. Looked up
	 * at every use like every other view here, since a pack reload frees the pair.
	 */
	GpuTextureView view() {
		return this.primed ? this.texels[this.current].view() : null;
	}

	/**
	 * Draws this frame's value. Must run on the render thread and outside any render pass.
	 * <p>
	 * Where it is called from is the whole of what it means, and {@code PackChain} says which moment
	 * of Iris's frame that is. A frame that draws nothing here leaves the accumulator where it
	 * stands, so the pack reads what it read before rather than a nought.
	 *
	 * @param opaque   the depth of the opaque world as it stood before the hand was drawn, already
	 *                 converted into the pack's own window. Null on a frame that kept none
	 * @param halfLife the pack's {@code centerDepthHalflife}, in deciseconds
	 * @param seconds  the previous frame's duration
	 */
	void sample(CommandEncoder encoder, GpuDevice device, GpuBuffer quad, GpuTextureView opaque,
			float halfLife, float seconds) {
		if (quad == null || opaque == null || !ensure()) {
			return;
		}

		RenderPipeline compiled = pipeline(device);
		if (compiled == null) {
			return;
		}

		int into = 1 - this.current;
		float blend = blend(halfLife, seconds);

		// Written through the game's own builder rather than into the buffer by hand, which is what
		// every other block of this engine does: one float is a layout too, and the two would drift.
		this.factor.rotate();
		try (GpuBufferSlice.MappedView view = this.factor.currentBuffer().map(false, true)) {
			Std140Builder.intoBuffer(view.data()).putFloat(blend);
		}

		// Loaded rather than cleared: the draw covers the one texel, so a clear would be one more
		// write of the same texel.
		try (RenderPass pass = encoder.createRenderPass(() -> LABEL, this.texels[into].view(),
				Optional.empty())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(UNIFORM_BLOCK, this.factor.currentBuffer());
			pass.setVertexBuffer(0, quad.slice());
			// NEAREST on both, as Iris binds both: one is read at its own centre and the other is one
			// texel wide, so there is nothing for a filter to average either way.
			pass.bindTexture(SCENE, opaque,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.bindTexture(BEFORE, this.texels[this.current].view(),
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		this.current = into;
		this.primed = true;
	}

	/**
	 * How far this draw moves the texel towards the depth it has just been handed, between nought
	 * and one. See the class javadoc for the two cases that are not the pack's half life.
	 *
	 * @param halfLife the pack's {@code centerDepthHalflife}, in deciseconds
	 * @param seconds  the previous frame's duration
	 */
	private float blend(float halfLife, float seconds) {
		if (!this.primed) {
			return 1.0F;
		}

		return seconds > 0.0F ? Smoothed.blend(halfLife, seconds) : 0.0F;
	}

	/** Frees the pair and the buffer behind the factor. The accumulator goes with them. */
	void release() {
		if (this.texels != null) {
			for (TargetSurface texel : this.texels) {
				if (texel != null) {
					texel.close();
				}
			}

			this.texels = null;
		}

		if (this.factor != null) {
			this.factor.close();
			this.factor = null;
		}

		this.current = 0;
		this.primed = false;
	}

	/**
	 * Makes the pair and the factor's buffer exist. Nothing here depends on the size of the screen,
	 * so unlike every other surface of this engine they are allocated once and kept across a resize.
	 */
	private boolean ensure() {
		if (this.refused) {
			return false;
		}

		if (this.texels != null) {
			return true;
		}

		try {
			// Into the field one at a time, and not through an array expression: that evaluates both
			// constructors before anything holds either, so a second one that throws leaks the first
			// past a release() that has nothing to look at.
			this.texels = new TargetSurface[SURFACES];
			this.texels[0] = new TargetSurface("Vitrail centre depth", FORMAT, false, 1, 1);
			this.texels[1] = new TargetSurface("Vitrail centre depth, the frame before", FORMAT,
					false, 1, 1);
			// Three buffers and a fence per turn, so a frame never writes over what the previous one
			// is still being read for.
			this.factor = new MappableRingBuffer(() -> LABEL,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, BLOCK_BYTES);
		} catch (RuntimeException e) {
			release();
			this.refused = true;
			Vitrail.logger().error("Vitrail could not allocate the two texels the smoothed centre "
					+ "depth is kept in, so every read of centerDepthSmooth in this pack stands at "
					+ "the far plane", e);

			return false;
		}

		return true;
	}

	/**
	 * The pipeline, compiled the first time it is asked for and kept.
	 * <p>
	 * The compiled form lives in the device cache, which the game empties at every resource reload,
	 * so this asks the device every time rather than trusting a flag of its own: that call is a
	 * {@code computeIfAbsent} on the device side and costs nothing once it has been made.
	 */
	private RenderPipeline pipeline(GpuDevice device) {
		if (this.refused) {
			return null;
		}

		if (this.pipeline == null) {
			this.pipeline = build();
		}

		if (device.precompilePipeline(this.pipeline, SOURCE).isValid()) {
			return this.pipeline;
		}

		// Released and not merely refused, which is what makes the line below true. A compile is
		// re-asked every frame, so this road is reached after frames have drawn: the pair would
		// otherwise stand primed and every read would go on answering the last value folded, frozen
		// for the session, while this says the reads stand at the far plane.
		release();
		this.refused = true;
		this.pipeline = null;
		Vitrail.logger().error("The centre depth pass did not compile, so every read of "
				+ "centerDepthSmooth in this pack stands at the far plane");

		return null;
	}

	private static RenderPipeline build() {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/center_depth"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER)
						.withSampler(SCENE)
						.withSampler(BEFORE)
						.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withColorTargetState(new ColorTargetState(Optional.empty(), FORMAT,
						ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}
}
