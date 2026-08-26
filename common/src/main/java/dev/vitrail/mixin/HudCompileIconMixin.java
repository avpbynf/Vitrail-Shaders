package dev.vitrail.mixin;

import dev.vitrail.render.PackChain;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the HUD's extraction to {@link PackChain#extractCompileIcon}, which pulses the mod's mark
 * in a corner while the pack-load workers still compile the leftover families.
 * <p>
 * At the tail of the whole extraction rather than as a layer of the HUD's own manager: the layers
 * are registered once at construction against vanilla anchor ids, and the mark is not a piece of
 * the vanilla HUD to be sorted among hearts and hotbar, it sits above everything the way the old
 * autosave indicator did. Every reason to stay quiet lives with the icon itself, where the flags
 * it reads live.
 */
@Mixin(Hud.class)
public abstract class HudCompileIconMixin {

	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 1)
	private void vitrail$compileIcon(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker,
			CallbackInfo callback) {
		PackChain.extractCompileIcon(graphics);
	}
}
