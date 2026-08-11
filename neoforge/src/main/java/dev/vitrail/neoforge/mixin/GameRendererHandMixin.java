package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.HandDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stops the game submitting the player's own hand after the level, so that {@link HandDraw} can
 * submit it inside the level instead.
 * <p>
 * <strong>The submission and not the method.</strong> {@code renderItemInHand} does three things
 * around this call: it builds the pose, it submits the hand, and it executes every feature of the
 * storage the screen effects also go into. Suppressing the whole method would take the third with
 * it, and the screen effects submitted a few lines later in {@code renderLevel} would then be drawn
 * by nothing at all, so a player under water or in lava would lose the overlay. Suppressed at the
 * submission alone, the execute that follows it walks a storage nothing put anything in and costs
 * nothing.
 * <p>
 * That is Iris's shape as well: it redirects the same call and nothing around it
 * ({@code mixin/MixinGameRenderer.java:75}), so the hand is neutralised where it is submitted rather
 * than where it is drawn.
 * <p>
 * A class of its own rather than a fourth handler on {@link GameRendererMixin}, which copies the
 * projection this method never touches: the two have nothing in common but their target.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandMixin {

	/**
	 * Skips the game's own submission exactly when this engine has already made one.
	 * <p>
	 * <strong>Required, where most of this package is not.</strong> The configuration sets
	 * {@code defaultRequire} to nought, so a handler that stopped matching would be dropped in
	 * silence; here that leaves both submissions standing and the hand is drawn twice, once inside
	 * the level under the pack's programs and once over the finished image under the game's. Two arms
	 * a frame apart is a picture nothing else in the engine would explain.
	 * <p>
	 * {@link HandDraw#diverted()} is the same question both halves ask before they draw anything, so
	 * the two cannot disagree and leave the hand submitted twice or not at all.
	 */
	@WrapOperation(method = "renderItemInHand", require = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;submitHandsWithItems("
							+ "FLcom/mojang/blaze3d/vertex/PoseStack;"
							+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
							+ "Lnet/minecraft/client/player/LocalPlayer;I)V"))
	private void vitrail$moveTheHand(ItemInHandRenderer renderer, float partialTick,
			PoseStack poseStack, SubmitNodeCollector collector, LocalPlayer player, int light,
			Operation<Void> original) {
		if (!HandDraw.diverted()) {
			original.call(renderer, partialTick, poseStack, collector, player, light);
		}
	}
}
