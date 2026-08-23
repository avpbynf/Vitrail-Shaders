package dev.vitrail.mixin;

import dev.vitrail.render.GeometryHold;

import com.mojang.blaze3d.systems.RenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves a geometry pass open when the next program still writes the same images. Closing would
 * end the backend pass; {@link GeometryHold} is what decides that the FBO has not moved.
 */
@Mixin(RenderPass.class)
public abstract class RenderPassMixin {

	@Inject(method = "close", at = @At("HEAD"), cancellable = true, require = 1)
	private void vitrail$keep(CallbackInfo callback) {
		if (GeometryHold.keep((RenderPass) (Object) this)) {
			callback.cancel();
		}
	}
}
