package dev.vitrail.fabric.mixin;

import dev.vitrail.render.PbrAtlases;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the resource pack ships beside the sprites of an atlas the game has just stitched, read once
 * per atlas and once per resource reload.
 * <p>
 * The sprites come from the preparations the method was handed rather than from the atlas, and that
 * is the only difference from the NeoForge side, which walks {@code getTextures} because NeoForge
 * added it. It is the same map: {@code upload} copies it out of these very preparations three lines
 * above the return.
 */
@Mixin(TextureAtlas.class)
public abstract class AtlasStitchedMixin {

	@Inject(method = "upload", at = @At("RETURN"), require = 1)
	private void vitrail$stitched(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		TextureAtlas atlas = (TextureAtlas) (Object) this;
		PbrAtlases.stitched(atlas.location(), atlas.getTexture(), preparations.regions().values());
	}
}
