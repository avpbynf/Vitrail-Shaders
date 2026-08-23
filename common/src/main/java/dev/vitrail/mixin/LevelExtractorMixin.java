package dev.vitrail.mixin;

import dev.vitrail.render.PackChain;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns improved transparency off before the world renderer rebuilds around it.
 * <p>
 * Iris injects the same refusal on {@code LevelRenderer.allChanged} and on a resource reload
 * ({@code mixin/fabulous/MixinDisableFabulousGraphics.java:16-26}). 26.2 moved {@code allChanged}
 * onto {@code LevelExtractor}; the resource-reload listener there only flags the sky renderer, so
 * the one injection that still rebuilds the fabulous target is this one. Pack load itself also
 * calls the same method, because enabling a pack here is not a renderer reload.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris, LGPL-3.0</a>
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {

	@Inject(method = "allChanged", at = @At("HEAD"), require = 1)
	private void vitrail$fabulous(CallbackInfo ci) {
		PackChain.turnOffImprovedTransparency();
	}
}
