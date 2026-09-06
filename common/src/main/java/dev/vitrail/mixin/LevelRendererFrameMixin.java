package dev.vitrail.mixin;

import dev.vitrail.render.timing.PassTimings;
import dev.vitrail.render.timing.RingTimings;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the frame boundary {@link PassTimings} counts by, at the renderer's end of frame: the
 * game calls it once per frame from its tick loop, just after the present, and on the frames that
 * have none. The present itself is skipped while the window is minimised or its surface is
 * invalid, and the world goes on being drawn then, so a boundary hung off the present would fold
 * those frames into one; this one is reached by every frame whatever happened to the surface.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFrameMixin {

	@Inject(method = "endFrame", at = @At("HEAD"), require = 1)
	private void vitrail$endFrame(CallbackInfo ci) {
		PassTimings.endFrame();
		RingTimings.endFrame();
	}
}
