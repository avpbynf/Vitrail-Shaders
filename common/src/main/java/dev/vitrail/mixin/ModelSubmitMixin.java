package dev.vitrail.mixin;

import dev.vitrail.render.BlockEntityGeometry;
import dev.vitrail.render.BlockEntityOrigin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers, on a model submission, whether it was handed in by a block entity renderer.
 * <p>
 * <strong>At construction and not at storage</strong>, which is what makes one hook enough. A model
 * submission is built inside the submit call in three places at once, the ordinary one, the outline
 * and the crumbling overlay, and all three are made while the dispatcher's mark is up; hooking the
 * lists they are filed into would be three hooks that have to stay in step with a method this engine
 * does not own.
 * <p>
 * This is the submission every block entity in the game produces: {@code submitModelPart} is a
 * default method of the collector that delegates to {@code submitModel}, so a chest, a sign's board,
 * a banner and a skull all arrive here, and there is no second kind to mark.
 */
@Mixin(ModelFeatureRenderer.Submit.class)
public abstract class ModelSubmitMixin implements BlockEntityOrigin {

	@Unique
	private boolean vitrail$blockEntity;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$capture(RenderType renderType, PoseStack.Pose pose, Model<?> model,
			Object state, int lightCoords, int overlayCoords, int tintedColor,
			TextureAtlasSprite sprite, PoseStack.Pose sheetedDecalPose, CallbackInfo callback) {
		this.vitrail$blockEntity = BlockEntityGeometry.submitting();
	}

	@Override
	public boolean vitrail$fromBlockEntity() {
		return this.vitrail$blockEntity;
	}
}
