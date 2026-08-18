package dev.vitrail.mixin;

import dev.vitrail.render.EntityIdentifiers;
import dev.vitrail.render.SubmittedIdentifiers;

import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts an item submission's identifiers back up while that submission is turned into vertices.
 * <p>
 * Round {@code prepareSubmit} and not round {@code buildGroup}, which is where the model side puts
 * it: a group of items is walked twice over, once for the ordinary quads and once for the foil, and
 * every turn of both loops is a different item. The window has to be one submission wide.
 * <p>
 * The three quads roads a submission may take, the main one, the outline and the foil, are all
 * inside that call, and each of them asks for its vertex builder and writes into it there.
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {

	@Inject(method = "prepareSubmit", at = @At("HEAD"), require = 1)
	private void vitrail$begin(ItemFeatureRenderer.Submit submit, boolean foil,
			CallbackInfo callback) {
		EntityIdentifiers.restore(((SubmittedIdentifiers) (Object) submit).vitrail$identifiers());
	}

	@Inject(method = "prepareSubmit", at = @At("RETURN"), require = 1)
	private void vitrail$end(ItemFeatureRenderer.Submit submit, boolean foil,
			CallbackInfo callback) {
		EntityIdentifiers.clear();
	}
}
