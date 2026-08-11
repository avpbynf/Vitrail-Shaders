package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.HandDraw;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps each arm in the one of the two hand passes it belongs to.
 * <p>
 * The hand is drawn twice, once for {@code gbuffers_hand} and once for {@code gbuffers_hand_water},
 * and the same submission runs both times: nothing in the game splits it, the whole notion of two
 * hand passes being the shader format's rather than the game's. So the split is made here, one arm
 * at a time, by cancelling whichever arm belongs to the other pass. A player holding a glass block
 * and a sword has one arm in each.
 * <p>
 * Iris's rule and Iris's placement, {@code mixin/MixinItemInHandRenderer.java:32-39}: the same head
 * of the same per arm method, cancelled on the same comparison. What decides is the item, and
 * {@link HandDraw#skip} carries the test.
 * <p>
 * <strong>It answers no whenever the hand is not being drawn by this engine at all</strong>, which
 * covers the game's own late call on every frame where the switch is off or no pack is loaded. There
 * is no need to test that separately: nothing raises a half except the two passes themselves.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

	/**
	 * <strong>Required, where most of this package is not.</strong> Dropped in silence, both arms
	 * would be drawn in both passes: the whole hand would be drawn twice per frame, the second time
	 * with the water program over the first, and a pack that tints its translucent hand would tint
	 * the sword as well. That is a plausible picture and a wrong one, which is the failure this
	 * engine refuses.
	 */
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true, require = 1)
	private void vitrail$oneHalfAtATime(AbstractClientPlayer player, float frameInterp, float xRot,
			InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight,
			PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
			CallbackInfo callback) {
		if (HandDraw.skip(itemStack)) {
			callback.cancel();
		}
	}
}
