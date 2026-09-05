package dev.vitrail.mixin;

import dev.vitrail.render.EntityIdentifiers;
import dev.vitrail.render.PackNameIds;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Says that what is being drawn is fire and not the mob it wraps.
 * <p>
 * <strong>The flame is the one feature whose number does not come off the submission.</strong> The
 * mob's own is what a pack must not read here: a pack names {@code minecraft:entity_flame} to treat
 * the fire as fire, and reading the burning mob's number instead lights and fogs the flame like the
 * body under it. So the number the pack gave that name is put up for the length of the draw. Iris
 * puts it up over its whole flame group, at the head and the return of {@code renderSolid}
 * ({@code mixin/entity_render_context/MixinFlameFeatureRenderer.java:22,29}), which 26.2 has not
 * got: the group is built by {@code buildGroup}, and one flame at a time by a private
 * {@code prepare}.
 * <p>
 * Round {@code prepare}, and so round one flame rather than the whole group, although the answer is
 * the same for every mob of it: {@code buildGroup} takes the vertex builder and the two sprites
 * before its loop and writes no vertex itself, so a window round each call still covers every
 * vertex of every flame, both sprites and every {@code fireVertex} of the column, and a window no
 * wider than what it covers cannot leave the number standing over anything else.
 * <p>
 * The block entity and the item are nought outside any of the two, so the return drops all three
 * and puts nothing down that was standing, which is what the other feature renderer windows do.
 * Iris sets the entity alone here as well.
 */
@Mixin(FlameFeatureRenderer.class)
public abstract class FlameFeatureRendererMixin {

	@Inject(method = "prepare", at = @At("HEAD"), require = 1)
	private void vitrail$begin(FlameFeatureRenderer.Submit submit, VertexConsumer buffer,
			TextureAtlasSprite first, TextureAtlasSprite second, CallbackInfo callback) {
		EntityIdentifiers.entity(PackNameIds.flame());
	}

	@Inject(method = "prepare", at = @At("RETURN"), require = 1)
	private void vitrail$end(FlameFeatureRenderer.Submit submit, VertexConsumer buffer,
			TextureAtlasSprite first, TextureAtlasSprite second, CallbackInfo callback) {
		EntityIdentifiers.clear();
	}
}
