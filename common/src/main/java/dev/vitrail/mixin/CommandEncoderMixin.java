package dev.vitrail.mixin;

import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.timing.PassTimings;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts every render pass, texture clear and texture copy the encoder is asked for, and brackets
 * each pass with the two timestamps {@link PassTimings} reads when the timed report is on.
 * <p>
 * Every overload of {@code createRenderPass} funnels into the descriptor one, so one injection
 * sees every pass whoever opens it. Clears and copies each end with the same full memory barrier
 * the backend puts after a pass, which is why they are counted beside it.
 * <p>
 * A geometry hold must end before any of those: a copy or a clear cannot be recorded inside a
 * pass, and a composite that samples what geometry just wrote has to see the store. Leftover
 * Immediate draws and Distant Horizons' GenericObjectRenderer that write the same images are
 * the exception: they keep the hold, the way Iris leaves the default framebuffer bound.
 */
@Mixin(CommandEncoder.class)
public abstract class CommandEncoderMixin {

	@Inject(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)"
			+ "Lcom/mojang/blaze3d/systems/RenderPass;",
			at = @At("HEAD"),
			cancellable = true,
			require = 1)
	private void vitrail$flushHold(RenderPassDescriptor descriptor,
			CallbackInfoReturnable<RenderPass> cir) {
		RenderPass leftover = GeometryHold.leftover(descriptor);
		if (leftover != null) {
			cir.setReturnValue(leftover);

			return;
		}

		if (!GeometryHold.opening()) {
			// The pass's own label is the cause, and it is already a supplier: naming it costs
			// nothing until a census asks, which is why the prefix is added there and not here.
			GeometryHold.flush(descriptor.label());
		}
	}

	@Inject(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)"
			+ "Lcom/mojang/blaze3d/systems/RenderPass;",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/CommandEncoderBackend;createRenderPass("
							+ "Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)"
							+ "Lcom/mojang/blaze3d/systems/RenderPassBackend;"),
			require = 1)
	private void vitrail$openPass(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
		PassTimings.open((CommandEncoder) (Object) this, descriptor.label());
	}

	@Inject(method = "submitRenderPass", at = @At("TAIL"), require = 1)
	private void vitrail$closePass(CallbackInfo ci) {
		PassTimings.close((CommandEncoder) (Object) this);
	}

	@Inject(method = "clearColorTexture", at = @At("HEAD"), require = 1)
	private void vitrail$clearColour(CallbackInfo ci) {
		GeometryHold.flush(() -> "a texture clear");
		PassTimings.censusClear();
	}

	@Inject(method = "clearDepthTexture", at = @At("HEAD"), require = 1)
	private void vitrail$clearDepth(CallbackInfo ci) {
		GeometryHold.flush(() -> "a texture clear");
		PassTimings.censusClear();
	}

	@Inject(method = "clearColorAndDepthTextures("
			+ "Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;"
			+ "Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
			at = @At("HEAD"), require = 1)
	private void vitrail$clearColourAndDepth(CallbackInfo ci) {
		GeometryHold.flush(() -> "a texture clear");
		PassTimings.censusClear();
	}

	@Inject(method = "clearColorAndDepthTextures("
			+ "Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;"
			+ "Lcom/mojang/blaze3d/textures/GpuTexture;DIIII)V",
			at = @At("HEAD"), require = 1)
	private void vitrail$clearColourAndDepthRegion(CallbackInfo ci) {
		GeometryHold.flush(() -> "a texture clear");
		PassTimings.censusClear();
	}

	@Inject(method = "copyTextureToTexture", at = @At("HEAD"), require = 1)
	private void vitrail$copyTexture(CallbackInfo ci) {
		GeometryHold.flush(() -> "a texture copy");
		PassTimings.censusCopy();
	}
}
