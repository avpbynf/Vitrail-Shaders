package dev.vitrail.mixin;

import dev.vitrail.render.PackChain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Compiles the pack before the world is drawn, so the wait is not a two-frame-per-second
 * vanilla picture of the same work.
 * <p>
 * Compilation itself runs here rather than after the level: that after-level path never fires
 * when the level is skipped. The HUD stays up so the action-bar line can say the pack is
 * still compiling.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererWarmupMixin {

	@WrapOperation(method = "render", require = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel("
							+ "Lnet/minecraft/client/DeltaTracker;)V"))
	private void vitrail$skipLevelWhilePackWarms(GameRenderer renderer, DeltaTracker delta,
			Operation<Void> original) {
		if (PackChain.warming()) {
			PackChain.pumpWarmup();
			return;
		}

		original.call(renderer, delta);
	}
}
