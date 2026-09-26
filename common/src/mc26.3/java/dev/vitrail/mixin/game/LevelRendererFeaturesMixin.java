package dev.vitrail.mixin.game;

import dev.vitrail.render.GameRender;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hands the game's translucent features the pass {@link GameRender#featuresPass()} opens on the
 * layer, while a redirect stands, in place of the level's own pass.
 * <p>
 * The one call that draws that phase in the classic order, which is the only order this engine
 * draws a pack in, improved transparency being kept off meanwhile. The level's pass steps aside for
 * the layer's as it does for the engine's own stages, and opens again at the translucent terrain
 * after it; the layer is composed onto the pack's image where the phase's closing stage runs, after
 * this returns, whichever loader posts it.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFeaturesMixin {

	@WrapOperation(method = "executeClassicTransparency", require = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
							+ "executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void vitrail$layer(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass,
			Operation<Void> original) {
		RenderPass layer = GameRender.featuresPass();
		if (layer == null) {
			original.call(frame, pass);

			return;
		}

		try {
			original.call(frame, layer);
		} finally {
			layer.close();
		}
	}
}
