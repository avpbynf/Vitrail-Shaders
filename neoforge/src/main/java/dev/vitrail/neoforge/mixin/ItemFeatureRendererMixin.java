package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.BlockEntityOrigin;
import dev.vitrail.render.BlockEntityGeometry;

import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The same for an item submission, which a block entity makes when it puts an item on itself.
 * <p>
 * On the method the three roads share rather than on the three: the main quads, the outline and the
 * foil each ask for a builder of their own, and one of them asks for it once per quad.
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {

	@Inject(method = "prepareSubmit", at = @At("HEAD"), require = 1)
	private void vitrail$begin(ItemFeatureRenderer.Submit submit, boolean foil,
			CallbackInfo callback) {
		BlockEntityGeometry.building(((BlockEntityOrigin) (Object) submit).vitrail$fromBlockEntity());
	}

	@Inject(method = "prepareSubmit", at = @At("RETURN"), require = 1)
	private void vitrail$end(ItemFeatureRenderer.Submit submit, boolean foil,
			CallbackInfo callback) {
		BlockEntityGeometry.building(false);
	}
}
