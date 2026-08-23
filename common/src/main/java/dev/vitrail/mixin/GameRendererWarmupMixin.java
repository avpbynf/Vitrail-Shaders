package dev.vitrail.mixin;

import dev.vitrail.render.PackChain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips the world while a pack is still compiling, so the loading overlay is not covering a
 * two-frame-per-second vanilla picture of the same wait.
 * <p>
 * Compilation itself runs here rather than after the level: that after-level path never fires
 * when the level is skipped. The wrap is required for the same reason the hand's is: dropped,
 * the world would keep drawing under the overlay and the wait would look like a freeze.
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
		}

		if (PackChain.warming()) {
			return;
		}

		original.call(renderer, delta);
	}
}
