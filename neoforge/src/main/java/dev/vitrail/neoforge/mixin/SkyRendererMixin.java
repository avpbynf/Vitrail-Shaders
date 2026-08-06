package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.SkyDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.DynamicUniforms;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Draws the sky with the programs the pack ships for it, instead of the game's own shaders.
 * <p>
 * The sky is the one piece of the world that opens its own render passes: {@code SkyRenderer} makes
 * one per element, sets a pipeline of the game's and draws a buffer built once at startup. So the
 * hook is not the one the entities will use, and it is smaller: the pipeline is swapped where it is
 * set, and the pass is replaced where it is opened so that the pack's own colour targets are what
 * the piece is drawn into. The two answers are taken on one call of one wrap and cannot part
 * company: a pipeline carries one colour state per attachment the descriptor names, and setting one
 * against a pass built for the other throws by name in the middle of the sky. The two arguments
 * dropped along the way are the clear colour and the clear depth, and all six methods pass both
 * empty.
 * <p>
 * <strong>An element is recognised by the label the game gives its own pass</strong>, which is the
 * first argument of the call wrapped below. That is what lets one wrap serve six methods without a
 * table of method names to keep in step with the game: a method whose label this engine has no
 * element for prepares nothing and draws exactly as it did.
 * <p>
 * <strong>One of the pack's answers is not a shader at all.</strong> Two of these pieces, the sun
 * and the moon, can be refused outright in {@code shaders.properties}, and a refusal is cancelled at
 * the head of the method rather than served with a program of ours, because the pack has drawn that
 * piece itself.
 * <p>
 * <strong>The order of the wraps is the whole design.</strong> The pass is opened after the game has
 * pushed the model view for this element, so the matrix is final by then and the sun is where the
 * game put it; compiling a pipeline or clearing a target has to happen before the pass exists, which
 * is why the preparation hangs off the opening and not off the head of the method. The texture goes
 * past next, and the block and the samplers are bound after that, once everything the bind needs is
 * known. The draw comes last, and it is the one place a piece of geometry the game has none of can
 * be added to a pass the game built.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

	/**
	 * The pipeline the element being recorded is drawn with, or null for the game's own. A field of
	 * the mixin and not a static: the renderer is one object and its passes do not overlap.
	 */
	private RenderPipeline vitrail$pipeline;

	/**
	 * The transform the game wrote for the element being drawn, kept between the moment it writes it
	 * and the moment the pass opens.
	 * <p>
	 * <strong>Taken from the game's own call and not rebuilt.</strong> These two values are what the
	 * game hands its own shader for this draw, so they are what a pack has to be handed for the same
	 * draw: the matrix carries the rotation of the day, which is where the sun is, and the colour
	 * carries the sky's own colour, which for a mesh of bare positions is the only place it exists.
	 * Reading the model view stack instead would get the matrix and miss the colour entirely.
	 */
	private Matrix4f vitrail$modelView;
	private Vector4f vitrail$colour;

	/**
	 * Tilts the path the sun, the moon and the stars travel by what the pack asked for.
	 * <p>
	 * <strong>The bodies and not their shader.</strong> {@code sunPathRotation} already turns the
	 * shadow matrices, so a pack that asks for it lights the world from a place the game's own sun
	 * does not stand in: BSL asks for minus forty degrees and lights every surface from there while
	 * the game draws a sun straight overhead. Nothing a sky program does can put it right, because
	 * the vertices it is handed are where the game decided; what has to turn is the geometry.
	 * <p>
	 * Here and not elsewhere, because here is where the three bodies share one matrix. The rotation
	 * goes in right after the game has turned the celestial space and before it turns for the hour,
	 * so it tilts the whole path rather than the body of one moment. Iris does exactly this, at the
	 * same call of the same method, on the same axis; the shadow matrices turn by the same angle on
	 * X, in the light's own space, and the two are not interchangeable.
	 */
	@Inject(method = "renderSunMoonAndStars",
			at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.AFTER,
					target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V"))
	private void vitrail$tilt(PoseStack poseStack, float sunAngle, float moonAngle, float starAngle,
			MoonPhase moonPhase, float rainBrightness, float starBrightness, CallbackInfo callback) {
		float tilt = SkyDraw.sunPathRotation();
		if (tilt != 0.0F) {
			poseStack.mulPose(Axis.ZP.rotationDegrees(tilt));
		}
	}

	/**
	 * Takes one piece of the sky out of the frame, where the pack asked for it in
	 * {@code shaders.properties}.
	 * <p>
	 * At the head of each method and not at the pass it opens, which is what makes it a removal
	 * rather than a choice of shader: the piece is not drawn by anybody. The two methods take
	 * different arguments, so there are two of these and no way to write one; each is the same two
	 * lines, and {@link SkyDraw#draws} holds the whole of the decision, the two words of the family
	 * that take no piece away included.
	 */
	@Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
	private void vitrail$sun(float rainBrightness, PoseStack poseStack, CallbackInfo callback) {
		vitrail$refuse("Sky sun", callback);
	}

	@Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
	private void vitrail$moon(MoonPhase moonPhase, float rainBrightness, PoseStack poseStack,
			CallbackInfo callback) {
		vitrail$refuse("Sky moon", callback);
	}

	/**
	 * Safe at the head of both: each of them pushes the model view it draws under and pops it again
	 * before it returns, and the pose stack they are handed is pushed and popped by the caller. So a
	 * method that never runs leaves nothing standing.
	 */
	private static void vitrail$refuse(String label, CallbackInfo callback) {
		if (!SkyDraw.draws(label)) {
			callback.cancel();
		}
	}

	/**
	 * Lets the game write its dynamic transform and keeps what it wrote. Every sky pass writes one
	 * before it opens its pass, so this runs first and outside anything.
	 */
	@WrapOperation(
			method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset", "renderSun",
					"renderMoon"},
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform("
							+ "Lorg/joml/Matrix4f;"
							+ "Lorg/joml/Vector4f;"
							+ ")Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
	private GpuBufferSlice vitrail$transform(DynamicUniforms uniforms, Matrix4f modelView,
			Vector4f colour, Operation<GpuBufferSlice> original) {
		this.vitrail$modelView = modelView;
		this.vitrail$colour = colour;

		return original.call(uniforms, modelView, colour);
	}

	@WrapOperation(
			method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset", "renderSun",
					"renderMoon"},
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
							+ "Ljava/util/function/Supplier;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Ljava/util/Optional;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Ljava/util/OptionalDouble;"
							+ ")Lcom/mojang/blaze3d/systems/RenderPass;"))
	private RenderPass vitrail$open(CommandEncoder encoder, Supplier<String> label,
			GpuTextureView colour, Optional<?> clearColour, GpuTextureView depth,
			OptionalDouble clearDepth, Operation<RenderPass> original) {
		this.vitrail$pipeline = SkyDraw.element(label.get(), this.vitrail$modelView,
				this.vitrail$colour);
		RenderPassDescriptor descriptor = this.vitrail$pipeline == null
				? null
				: SkyDraw.descriptor(colour, depth);

		return descriptor == null
				? original.call(encoder, label, colour, clearColour, depth, clearDepth)
				: encoder.createRenderPass(descriptor);
	}

	@WrapOperation(
			method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset", "renderSun",
					"renderMoon"},
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline("
							+ "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void vitrail$pipeline(RenderPass pass, RenderPipeline pipeline,
			Operation<Void> original) {
		original.call(pass, this.vitrail$pipeline == null ? pipeline : this.vitrail$pipeline);
	}

	/**
	 * Lets the game bind its own texture and keeps what it bound. The pack's program declares its
	 * own name for the same image, and the descriptor flush walks the layout of the pipeline that is
	 * bound, so the game's binding costs nothing and the name it used is not the one that is read.
	 */
	@WrapOperation(
			method = {"renderSun", "renderMoon"},
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture("
							+ "Ljava/lang/String;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Lcom/mojang/blaze3d/textures/GpuSampler;)V"))
	private void vitrail$texture(RenderPass pass, String name, GpuTextureView view,
			GpuSampler sampler, Operation<Void> original) {
		original.call(pass, name, view, sampler);
		SkyDraw.texture(view, sampler);
	}

	/**
	 * Adds the horizon cone to the pass the disc is drawn in, once the disc itself is recorded.
	 * <p>
	 * <strong>The game has no geometry between its two sky discs</strong>, and above sea level it
	 * draws only the upper one, so everything below 1.79 degrees over the horizontal is a band with
	 * no surface in it for a pack's sky program to run on. {@code SkyDraw.horizon} says what is drawn
	 * there and why it rides in this pass rather than one of its own.
	 * <p>
	 * After the disc and not before it, which costs one thing and buys another. The two overlap
	 * between the edge of the disc and the ring of the cone, and there the cone now wins; drawing it
	 * first would mean re-binding the disc's own vertex buffer afterwards, and this handler is not
	 * given it. Iris, which draws its cone in a pass of its own before the sky, has the same overlap
	 * the other way round and calls the difference imperceptible.
	 * <p>
	 * Only where a pipeline of ours was handed back: with the game's own sky shader drawing, the
	 * band is the clear colour and looks as vanilla looks, and a cone drawn into it with the game's
	 * shader would change a picture nobody complained about.
	 */
	@WrapOperation(method = "renderSkyDisc",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;draw(IIII)V"))
	private void vitrail$horizon(RenderPass pass, int vertices, int instances, int firstVertex,
			int firstInstance, Operation<Void> original) {
		original.call(pass, vertices, instances, firstVertex, firstInstance);
		if (this.vitrail$pipeline != null) {
			SkyDraw.horizon(pass, this.vitrail$pipeline);
		}
	}

	/**
	 * The last moment before the draw, and the first at which everything the bind needs is known.
	 * Binding earlier would leave the celestial atlas out, since the game binds it after the
	 * pipeline is set.
	 */
	@WrapOperation(
			method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset", "renderSun",
					"renderMoon"},
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;setVertexBuffer("
							+ "ILcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
	private void vitrail$bind(RenderPass pass, int slot, GpuBufferSlice buffer,
			Operation<Void> original) {
		original.call(pass, slot, buffer);
		if (this.vitrail$pipeline != null) {
			SkyDraw.bind(pass, this.vitrail$pipeline);
		}
	}
}
