package dev.vitrail.mixin.game;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.vitrail.render.PackChain;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the game's order independent transparency off while a pack draws.
 * <p>
 * 26.3 replaced improved transparency with a wavelet OIT: when it is on, the level draws its
 * translucents in three phases of passes of their own over accumulation targets, rather than in the
 * main pass after the opaque world. A pack composes its own translucency, over the targets of its
 * own, and the engine's stages are cut around the classic order, the translucent features, then the
 * translucent terrain, in the main pass. So the one answer every reader of the option asks is no
 * while a pack is drawing, which is what Iris does with improved transparency on the other backend.
 * The option itself is lowered at pack load by {@code PackChoice.turnOffImprovedTransparency}, as
 * Iris lowers it, and this answer covers any frame that reads the option before that write.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererTransparencyMixin {

	@ModifyReturnValue(method = "useImprovedTransparency", at = @At("RETURN"), require = 1)
	private boolean vitrail$classicUnderPack(boolean improved) {
		return improved && !PackChain.drawingPack();
	}
}
