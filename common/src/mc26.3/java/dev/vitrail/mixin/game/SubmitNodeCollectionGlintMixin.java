package dev.vitrail.mixin.game;

import dev.vitrail.render.GlintSplit;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Submits a model that carries a glint as the plain model followed by its glint pass, which is how
 * 26.2 submitted it; {@code GlintSplit} says why and when.
 * <p>
 * At the collection's own {@code submitModel} and not at the four renderers that choose the render
 * type, because every model submission ends here whichever collector it was handed to, and the
 * phase is picked here by the call below: the plain half lands in the phase its render type asks
 * for, and the glint pass, which blends and forces nothing, in the translucent one after it, where
 * 26.2 drew it. The outline goes with the plain half and the glint pass has none, which is what the
 * game's own trim glint submits.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionGlintMixin {

	@Inject(method = "submitModel", at = @At("HEAD"), cancellable = true, require = 1)
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void vitrail$split(Model model, Object state, PoseStack poseStack, RenderType renderType,
			int lightCoords, int overlayCoords, int tintedColor, @Nullable UvMapping uvMapping,
			int outlineColor, CallbackInfo callback) {
		if (!GlintSplit.active()) {
			return;
		}

		RenderType base = GlintSplit.modelBase(renderType);
		if (base == null) {
			return;
		}

		SubmitNodeCollection self = (SubmitNodeCollection) (Object) this;
		self.submitModel(model, state, poseStack, base, lightCoords, overlayCoords, tintedColor,
				uvMapping, outlineColor);
		self.submitModel(model, state, poseStack, GlintSplit.modelGlint(renderType), lightCoords,
				overlayCoords, -1, uvMapping, 0);
		callback.cancel();
	}
}
