package dev.vitrail.mixin;

import dev.vitrail.render.PassTimings;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Brackets every render pass with the two timestamps {@link PassTimings} reads, at the two points
 * where the game's own Tracy profiler pushes and pops a zone: just before the backend records the
 * pass and just after it has submitted it. Both sit outside the pass, on the frame's command
 * buffer, exactly as the profiler's own do.
 * <p>
 * Every overload of {@code createRenderPass} funnels into the descriptor one, so one injection
 * sees every pass whoever opens it.
 */
@Mixin(CommandEncoder.class)
public abstract class CommandEncoderMixin {

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
}
