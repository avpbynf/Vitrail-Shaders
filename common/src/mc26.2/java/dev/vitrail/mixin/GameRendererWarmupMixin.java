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
 * vanilla picture of the same work, and asks first whether the world moved under it.
 * <p>
 * The two belong on one line because this call is the only place in the frame that stands before
 * everything the level does: {@link PackChain#beforeLevel} says what each half of a world join was
 * paying for that question being asked at the end of the frame instead.
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
		// Before the branch and not inside one of its arms: the question is owed whether the pack
		// is compiling or drawing, and this is the one line both roads pass through. A frame that
		// reads the pack again owes the level nothing, for the reason beforeLevel gives: the chunk
		// format of this frame was settled from the pack that has just been replaced.
		if (PackChain.beforeLevel() || PackChain.warming()) {
			PackChain.pumpWarmup();
			return;
		}

		original.call(renderer, delta);
	}
}
