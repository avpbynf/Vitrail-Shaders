package dev.vitrail.mixin.game;

import dev.vitrail.render.HandDraw;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps each arm in the one of the two hand passes it belongs to, on Minecraft 26.3.
 * <p>
 * The 26.2 twin is {@code ItemInHandRendererMixin} and says why the split is made here and made
 * this way: the hand is drawn twice, once for {@code gbuffers_hand} and once for
 * {@code gbuffers_hand_water}, the same submission runs both times, and the arm that belongs to
 * the other pass is cancelled at the head of the per arm method, which is Iris's rule and Iris's
 * placement. {@link HandDraw#skip} carries the test.
 * <p>
 * <strong>Same method, same head, another class and another shape.</strong> 26.3 renamed
 * {@code ItemInHandRenderer} to {@code FirstPersonHandsAndItemsRenderer} and moved what the arm is
 * drawn from into a render state extracted once per frame, so the method takes the player's render
 * state and the hands' own where it took the player, and one partial tick where it took the frame's
 * interpolation. The item held in that arm is still an argument, and it is the one this reads.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {

	/**
	 * <strong>Required, and written out rather than left to the configuration's default</strong>,
	 * for the reason the 26.2 twin gives: dropped in silence, both arms would be drawn in both
	 * passes, the second time with the water program over the first.
	 */
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true, require = 1)
	private void vitrail$oneHalfAtATime(PlayerRenderState playerState,
			FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
			InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight,
			PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
			CallbackInfo callback) {
		if (HandDraw.skip(itemStack)) {
			callback.cancel();
		}
	}
}
