package dev.vitrail.render;

import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.WorldState;
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
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.MappableRingBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3dc;

import java.util.Optional;

/**
 * Where each pixel of this frame stood in the previous one, in the form a temporal upscaler takes.
 * <p>
 * <strong>Iris has nothing like this, and that is a carve-out rather than a drift.</strong> The
 * directive that this engine does what Iris does was reopened on this one point, temporal
 * upscaling, so the pass exists knowing the reference has no counterpart to be measured against.
 * Nothing else follows from it.
 * <p>
 * <strong>The convention is NVIDIA's and it is written here so nobody re-derives it.</strong> The
 * DLSS Super Resolution guide, revision 310.6.0, section 3.6: "The motion vectors map a pixel from
 * the current frame to its position in the previous frame. That is, when the motion vector for the
 * pixel is added to the pixel's current location, the result is the location the pixel occupied in
 * the previous frame." Section 3.6.1 fixes the units, "the number of pixels calculated in screen
 * space (ie the amount a pixel has moved at the render resolution)", and the axes, "[0,0] as the
 * upper left of the screen". So the vector points AGAINST the movement, and a buffer holding the
 * direction of travel is the whole thing negated. FSR takes the same buffer through a scale factor,
 * which is why the pixel form is the one stored rather than a normalised one.
 * <p>
 * <strong>The camera moves here and nothing else does.</strong> The pass reprojects a depth sample,
 * so it knows only what the camera did between two frames: a mob walking across a still screen gets
 * a vector of nought, which is a lie an upscaler will smear. Per-object motion needs the previous
 * transform of each drawn thing, which means an extra output on translated pack programs, and that
 * is not this pass.
 * <p>
 * <strong>The pair of matrices is the rendered one and never the published one.</strong>
 * {@link ViewMatrices#previousRendered} carries why at length. In one line: the volume being
 * reconstructed from is reversed Z over 0..1, and the matrix a pack reads has been converted to the
 * OpenGL volume, so pairing the two reconstructs a position that is wrong by more the further away
 * it is, and the picture that comes out of it looks entirely reasonable.
 * <p>
 * <strong>The depth sampled is not in that volume and is put back into it here.</strong>
 * {@link PackDepth} does not copy the device's image, it converts it: its fragment applies
 * {@code ClipSpace.REVERSED}'s read pair, so what {@code depthtex1} holds is {@code 1 - d}, forward
 * over 0..1, which is the window a pack reads depth in. Sampling that and dropping it into a
 * reversed Z slot swaps near and far on every pixel. The conversion is undone from the same two
 * constants rather than by a written out {@code 1 - d}, so that a change to the pair cannot leave
 * one of the two sides behind. Reading the device image instead is not the alternative it looks
 * like: that image is the one being drawn into, which is the reason {@code PackDepth} keeps a copy
 * at all.
 * <p>
 * <strong>Neither matrix carries the camera's translation, so it is applied on its own.</strong>
 * The view handed to {@link ViewMatrices#advance} is {@code viewRotationMatrix}, a pure rotation, so
 * every position here is in player space, measured from wherever the camera of that frame stood. A
 * point at {@code p} this frame stood at {@code p + (camera now - camera before)} in the previous
 * frame's own space, and without that term the pass reprojects rotation alone: a player walking in a
 * straight line writes vectors of nought and a turning one writes correct ones, which is the failure
 * that looks most like success. The engine pays the same trap on the shadow pair and corrects it the
 * same way, {@code ViewMatrices:465}. The two positions come from
 * {@link dev.vitrail.uniform.WorldState#cameraPosition} and its previous, which are shifted by the
 * same amount as each other precisely so that a difference between them survives the shift.
 * <p>
 * The screen coordinate comes from {@code gl_FragCoord} rather than from the quad's own
 * interpolated UV. Vulkan puts that origin at the upper left with y increasing downwards, which is
 * both the axis the guide asks for and the axis the depth image is stored in, so the same value
 * serves as the sampling coordinate and as the output position, and neither depends on how the quad
 * happens to be wound.
 * <p>
 * <strong>{@code ndc.xy = uv * 2 - 1} carries no flip here, and the reason is the viewport rather
 * than the matrix.</strong> A pass is given its viewport by the game, in
 * {@code com.mojang.blaze3d.vulkan.VulkanRenderPass}, which sets the origin at nought and takes the
 * height from the attachment, so it is positive and the transform between clip and window
 * coordinates is exactly {@code uv = ndc * 0.5 + 0.5} with nothing negated. That is a fact about the
 * game and not about this engine, which is worth saying because this class has no viewport call of
 * its own to point at: {@code VulkanCommandEncoderMixin.vitrail$viewport} exists for the mipmap
 * chain and nothing that draws a world pass goes through it.
 * <p>
 * The line above is that relation read backwards, and the matrix it then inverts is the same one
 * that projected, so the round trip closes whichever way up the projection itself is built. This is
 * why the pass needs no separate answer to which handedness the game's Vulkan renderer uses: the
 * question does not reach it.
 * <p>
 * Half floats and not full ones: the guide asks for "16-bit or 32-bit floating point values" and
 * refuses integers, because sub-pixel movement is the point. Half precision holds a fraction of a
 * pixel exactly where the movement is small, which is every frame that is not a whip, and the
 * buffer is half the bandwidth of the full form.
 */
final class MotionVectors {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/motion_vectors_vertex");

	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/motion_vectors_fragment");

	/** The depth of the opaque world, in the volume the device wrote it in. */
	private static final String SCENE = "DepthSampler";

	private static final String UNIFORM_BLOCK = "OfMotionVectors";

	/** Two matrices and two pairs, which std140 lays out at 64, 64, 8 and 8. */
	private static final int BLOCK_BYTES = 144;

	/** Two triangles, the quad every full screen pass of this engine draws. */
	private static final int VERTICES = 6;

	/** Two half floats a texel, which is the form both DLSS and FSR document. */
	private static final GpuFormat FORMAT = GpuFormat.RG16_FLOAT;

	private static final String LABEL = "Vitrail motion vectors";

	private static final String VERTEX = """
			#version 460 core

			in vec3 Position;

			void main() {
				gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			}
			""";

	/**
	 * Formatted with {@code ClipSpace.REVERSED}'s read pair, undone rather than applied: the image
	 * being sampled has already been through {@code readA * d + readB} on its way out of the device
	 * volume, and this pass wants the device value back.
	 */
	private static final String FRAGMENT = String.format("""
			#version 460 core

			uniform sampler2D DepthSampler;

			layout(std140) uniform OfMotionVectors {
				mat4 of_FromScreen;
				mat4 of_ToPreviousClip;
				vec2 of_Size;
				vec2 of_InverseSize;
			};

			layout(location = 0) out vec2 ofFragData0;

			void main() {
				vec2 uv = gl_FragCoord.xy * of_InverseSize;
				float depth = (texture(DepthSampler, uv).r - %s) / %s;

				vec4 here = of_FromScreen * vec4(uv * 2.0 - 1.0, depth, 1.0);
				vec4 before = of_ToPreviousClip * (here / here.w);

				// Behind the previous camera, so there is no previous pixel to point at. Nought says
				// the pixel did not move, which is wrong and bounded; anything else off screen would
				// be a coordinate an upscaler would go and sample.
				if (before.w <= 0.0) {
					ofFragData0 = vec2(0.0);
					return;
				}

				vec2 was = (before.xy / before.w) * 0.5 + 0.5;
				ofFragData0 = (was - uv) * of_Size;
			}
			""", ClipSpace.REVERSED.w, ClipSpace.REVERSED.z);

	private static final ShaderSource SOURCE = (id, type) -> {
		if (type == ShaderType.FRAGMENT) {
			return FRAGMENT_ID.equals(id) ? FRAGMENT : null;
		}

		return VERTEX_ID.equals(id) ? VERTEX : null;
	};

	private final Matrix4f fromScreen = new Matrix4f();
	private final Matrix4f toPreviousClip = new Matrix4f();

	private RenderPipeline pipeline;
	private TargetSurface vectors;
	private MappableRingBuffer block;

	/**
	 * That the allocation failed at a size, and which one. A resize is a real second chance, the
	 * same way {@link PackDepth} treats one, since the image is the size of the screen.
	 */
	private boolean broken;
	private int brokenWidth;
	private int brokenHeight;

	/** That the pipeline will not compile, which no resize lifts. */
	private boolean refused;

	/** Whether this frame drew, since a frame that did not leaves the previous frame's vectors. */
	private boolean drawn;

	/** That the image was just allocated, so the frame reaching it has no history at this size. */
	private boolean fresh;

	/**
	 * Gives the image back on a frame nothing will read it. Separate from {@link #release} because
	 * the caller says WHY, and because a caller that simply stops calling {@link #draw} would leave
	 * a full screen image alive for the rest of the session.
	 */
	void standDown() {
		this.drawn = false;
		release();
	}

	/** This frame's vectors, or null while nothing has drawn them. */
	GpuTextureView view() {
		return this.drawn ? this.vectors.view() : null;
	}

	/**
	 * Draws this frame's vectors. Must run on the render thread and outside any render pass.
	 * <p>
	 * <strong>Only called on a frame something will read them.</strong> Vectors are read by
	 * something rebuilding one picture out of several, so on any other frame they would be a full
	 * screen image and a pass spent on nobody. Deciding that is the caller's, and the other side of
	 * the decision is {@link #standDown}, which gives the image back.
	 *
	 * @param depth the opaque world's depth as a pack reads it, forward over 0..1, which the
	 *              fragment stage puts back into the device volume
	 * @param view  the frame's matrices, already advanced, so its previous pair is the frame before
	 * @param world the frame's values, for the camera of this frame and of the one before
	 */
	void draw(CommandEncoder encoder, GpuDevice device, GpuBuffer quad, GpuTextureView depth,
			int width, int height, ViewMatrices view, WorldState world) {
		this.drawn = false;
		if (quad == null || depth == null || !ensure(width, height)) {
			return;
		}

		// The first frame at a new size has no history at that size: the matrices still describe a
		// camera looking through a differently shaped window, and the vectors drawn from them are
		// that reshaping rather than any movement. Skipped outright rather than drawn and ignored,
		// so what reads this is handed nothing and falls back for the one frame it takes.
		if (this.fresh) {
			this.fresh = false;

			return;
		}

		RenderPipeline compiled = pipeline(device);
		if (compiled == null) {
			return;
		}

		// Composed and inverted here rather than in the shader: it is two multiplies and one inverse
		// a frame against one of each per pixel, and the shader stays a line of arithmetic anybody
		// can check against the quoted convention.
		this.fromScreen.set(view.rendered()).mul(view.gbufferModelView()).invert();

		// Post-multiplied, so the offset reaches a point BEFORE the previous frame's rotation turns
		// it: both are distances in player space, and one applied on the far side of the rotation
		// would be a different place in the world. The same order the shadow pair is corrected in.
		Vector3dc now = world.cameraPosition();
		Vector3dc before = world.previousCameraPosition();
		this.toPreviousClip.set(view.previousRendered()).mul(view.gbufferPreviousModelView())
				.translate((float) (now.x() - before.x()), (float) (now.y() - before.y()),
						(float) (now.z() - before.z()));

		this.block.rotate();
		try (GpuBufferSlice.MappedView mapped = this.block.currentBuffer().map(false, true)) {
			Std140Builder.intoBuffer(mapped.data())
					.putMat4f(this.fromScreen)
					.putMat4f(this.toPreviousClip)
					.putVec2(width, height)
					.putVec2(1.0F / width, 1.0F / height);
		}

		try (RenderPass pass = encoder.createRenderPass(() -> LABEL, this.vectors.view(),
				Optional.empty())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(UNIFORM_BLOCK, this.block.currentBuffer());
			pass.setVertexBuffer(0, quad.slice());
			// NEAREST, because a filtered depth between two surfaces is a position on neither of
			// them and the vector drawn from it points at nothing.
			pass.bindTexture(SCENE, depth,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		this.drawn = true;
	}

	/** Frees the image and the buffer behind the block. */
	void release() {
		if (this.vectors != null) {
			this.vectors.close();
			this.vectors = null;
		}

		if (this.block != null) {
			this.block.close();
			this.block = null;
		}

		this.drawn = false;
	}

	private boolean ensure(int width, int height) {
		// Before the latch and not after, the order PackDepth.ensure keeps and for the reason
		// written there: a minimised window is another size, and lifting a refusal on a size nothing
		// is ever allocated at only makes the real size pay the failure twice.
		if (width <= 0 || height <= 0) {
			return false;
		}

		if (this.broken && (width != this.brokenWidth || height != this.brokenHeight)) {
			this.broken = false;
		}

		if (this.broken) {
			return false;
		}

		if (this.block == null) {
			try {
				this.block = new MappableRingBuffer(() -> LABEL,
						GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, BLOCK_BYTES);
			} catch (RuntimeException e) {
				this.broken = true;
				this.brokenWidth = width;
				this.brokenHeight = height;
				Vitrail.logger().error("Vitrail could not allocate the uniform buffer the motion "
						+ "vector pass writes its two matrices into, so nothing reprojects", e);

				return false;
			}
		}

		if (this.vectors != null && this.vectors.width() == width
				&& this.vectors.height() == height) {
			return true;
		}

		try {
			// The old one first: a resize that allocates before freeing holds two full screen images
			// at once, and the size that fails is the size that was already tight.
			if (this.vectors != null) {
				this.vectors.close();
				this.vectors = null;
			}

			this.vectors = new TargetSurface(LABEL, FORMAT, false, width, height);
		} catch (RuntimeException e) {
			this.broken = true;
			this.brokenWidth = width;
			this.brokenHeight = height;
			this.drawn = false;
			Vitrail.logger().error("Vitrail could not allocate the motion vector image at {}x{}, so "
					+ "nothing reprojects until the screen is another size", width, height, e);

			return false;
		}

		this.fresh = true;

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

		release();
		this.refused = true;
		this.pipeline = null;
		Vitrail.logger().error("The motion vector pass did not compile, so nothing reprojects for "
				+ "the rest of this session");

		return null;
	}

	private static RenderPipeline build() {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID,
						"pipeline/motion_vectors"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER)
						.withSampler(SCENE)
						.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withColorTargetState(new ColorTargetState(Optional.empty(), FORMAT,
						ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}
}
