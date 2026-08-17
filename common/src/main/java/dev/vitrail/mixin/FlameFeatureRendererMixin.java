package dev.vitrail.mixin;

import dev.vitrail.render.EntityIdentifiers;
import dev.vitrail.render.SubmittedIdentifiers;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The window for one flame, and it is one submission wide for the reason the items' is: the group
 * takes a single vertex builder before the loop and every turn of that loop is a different mob.
 */
@Mixin(FlameFeatureRenderer.class)
public abstract class FlameFeatureRendererMixin {

	@Inject(method = "prepare", at = @At("HEAD"), require = 1)
	private void vitrail$begin(FlameFeatureRenderer.Submit submit, VertexConsumer buffer,
			TextureAtlasSprite first, TextureAtlasSprite second, CallbackInfo callback) {
		EntityIdentifiers.restore(((SubmittedIdentifiers) (Object) submit).vitrail$identifiers());
	}

	@Inject(method = "prepare", at = @At("RETURN"), require = 1)
	private void vitrail$end(FlameFeatureRenderer.Submit submit, VertexConsumer buffer,
			TextureAtlasSprite first, TextureAtlasSprite second, CallbackInfo callback) {
		EntityIdentifiers.clear();
	}
}
