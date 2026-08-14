package dev.vitrail.mixin;

import dev.vitrail.render.CloudDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Draws the clouds with the program the pack ships for them, instead of the game's own shader.
 * <p>
 * The shape is {@link SkyRendererMixin}'s and shorter, because the cloud renderer is simpler than
 * the sky renderer: one method, one pass, one draw. The pipeline is swapped where it is set and the
 * pass is replaced where it is opened, on one call of one wrap each, so that the two cannot part
 * company - a pipeline carries one colour state per attachment the descriptor names, and setting one
 * against a pass built for the other throws by name in the middle of the frame.
 * <p>
 * <strong>What is not swapped is the geometry, because there is none to swap.</strong> This renderer
 * binds no vertex buffer at all: it fills a texel buffer with three bytes a face and draws six
 * indices a face out of it, working the corners out in the vertex stage. Everything the game sets
 * afterwards - the index buffer, the cloud block, the face buffer - is left exactly as it was and
 * lands on the pack's own pipeline instead, which is why that pipeline has to declare the game's own
 * two names. {@code CloudDraw} says what that costs and {@code glsl/CloudVertex} says what the pack
 * then reads.
 * <p>
 * <strong>Which of the two cloud pipelines is coming has to be known before the pass exists</strong>,
 * because it decides the culling and therefore which of the two programs is prepared. It is taken off
 * the argument the renderer was called with, at the head of the method, rather than read back from
 * the user's settings: a pack is allowed to overrule those, and it does so through the same accessor
 * this renderer was handed its answer from.
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {

	/**
	 * The pipeline this draw is recorded with, or null for the game's own. A field of the mixin and
	 * not a static: the renderer is one object and it opens one pass at a time.
	 */
	private RenderPipeline vitrail$pipeline;

	/** Whether the draw being prepared is the boxed cloud rather than the flat one. */
	private boolean vitrail$fancy;

	/**
	 * Keeps which of its two pipelines the renderer is about to choose.
	 * <p>
	 * At the head and not at the pass, because the pass is three quarters of a method later and the
	 * decision is made from this argument at the top of it. Nothing is prepared here: this method is
	 * reached on every frame that has a cloud texture, including the frames that go on to draw
	 * nothing at all.
	 */
	@Inject(method = "render", at = @At("HEAD"))
	private void vitrail$status(int colour, CloudStatus cloudStatus, float bottomY, int range,
			Vec3 cameraPosition, long gameTime, float partialTicks, CallbackInfo callback) {
		this.vitrail$fancy = cloudStatus == CloudStatus.FANCY;
	}

	@WrapOperation(method = "render",
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
		this.vitrail$pipeline = CloudDraw.pipeline(this.vitrail$fancy);
		RenderPassDescriptor descriptor = this.vitrail$pipeline == null
				? null
				: CloudDraw.descriptor(colour, depth);

		return descriptor == null
				? original.call(encoder, label, colour, clearColour, depth, clearDepth)
				: encoder.createRenderPass(descriptor);
	}

	@WrapOperation(method = "render",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline("
							+ "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void vitrail$pipeline(RenderPass pass, RenderPipeline pipeline,
			Operation<Void> original) {
		original.call(pass, this.vitrail$pipeline == null ? pipeline : this.vitrail$pipeline);
	}

	/**
	 * The last moment before the draw, and the first at which everything the bind needs is set.
	 * <p>
	 * After the game's own uniforms and not before them, which is what makes the two halves fit: the
	 * game fills the cloud block and the face buffer by name, and the block and samplers bound here
	 * are the pack's own. Neither knows about the other, and the draw wants both.
	 */
	@WrapOperation(method = "render",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIIII)V"))
	private void vitrail$draw(RenderPass pass, int indexCount, int instanceCount, int firstIndex,
			int vertexOffset, int firstInstance, Operation<Void> original) {
		if (this.vitrail$pipeline != null) {
			CloudDraw.bind(pass, this.vitrail$pipeline);
		}

		original.call(pass, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}
}
