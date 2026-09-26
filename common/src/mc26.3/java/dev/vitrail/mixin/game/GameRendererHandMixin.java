package dev.vitrail.mixin.game;

import dev.vitrail.render.HandDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stops the game submitting the player's own hand after the level, so that {@link HandDraw} can
 * submit it inside the level instead, on Minecraft 26.3.
 * <p>
 * The 26.2 twin of the same name says why it is the submission and not the method, and why that is
 * Iris's shape. What is the same here is the place: {@code renderItemInHand} builds the pose, submits
 * the hand into a storage it shares with the screen effects, and draws that storage. Suppressed at
 * the submission alone, the storage holds no hand and the draw that follows has nothing of it to
 * draw.
 * <p>
 * <strong>What moved is the call.</strong> 26.3 renamed the class it calls and hands it the render
 * state the frame extracted for the player and for the hands, where 26.2 handed it the player and
 * its light. It is still one call, in the same method, and it is still the whole of the hand.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandMixin {

	/**
	 * Skips the game's own submission exactly when this engine has already made one, on the same
	 * question both halves of {@link HandDraw} ask before they draw anything.
	 * <p>
	 * <strong>Required, and written out rather than left to the configuration's default</strong>,
	 * for the reason the 26.2 twin gives: a handler dropped in silence would leave the hand drawn
	 * twice, once inside the level under the pack's programs and once over the finished image.
	 */
	@WrapOperation(method = "renderItemInHand", require = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;"
							+ "submitHandsWithItems("
							+ "FLcom/mojang/blaze3d/vertex/PoseStack;"
							+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
							+ "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;"
							+ "Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;"
							+ ")V"))
	private void vitrail$moveTheHand(FirstPersonHandsAndItemsRenderer renderer, float partialTicks,
			PoseStack poseStack, SubmitNodeCollector collector, PlayerRenderState playerState,
			FirstPersonHandsAndItemsRenderState state, Operation<Void> original) {
		if (!HandDraw.diverted()) {
			original.call(renderer, partialTicks, poseStack, collector, playerState, state);
		}
	}
}
