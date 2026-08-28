package dev.vitrail.mixin;

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
 * <strong>The submission and not the method.</strong> {@code renderItemInHand} builds the pose and
 * submits the hand; the storage it submits into, which the screen effects share, is executed by
 * {@code GameRenderer} itself after this method returns ({@code GameRenderer.java:581-589}).
 * Suppressed at the submission alone, exactly one thing changes: the storage no longer holds a
 * hand, and the execute that follows walks what the screen effects put there and nothing else.
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
	 * <strong>Required, and written out rather than left to the configuration's default.</strong> A
	 * handler that stopped matching and was dropped in silence would leave both submissions standing
	 * and the hand drawn twice, once inside the level under the pack's programs and once over the
	 * finished image under the game's. Two arms a frame apart is a picture nothing else in the
	 * engine would explain.
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
