package dev.vitrail.mixin.game;

import dev.vitrail.render.GlintSplit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Writes an enchanted item as its plain quads followed by the same quads in the glint pass, which is
 * how 26.2 wrote it; {@code GlintSplit} says why and when.
 * <p>
 * Every plain quad first and every glint quad after, and not quad by quad: a group draws its render
 * types in the order it first met them, so this puts the glint after the item it lands on, and an
 * ordered group, which starts a draw at every change of render type, gets two draws rather than two
 * per quad.
 * <p>
 * The special foil keeps its own projection. Its glint is not laid along the item's texture but
 * projected onto it from the decal pose, which 26.3 writes as a third coordinate and 26.2 wrote as
 * the glint pass's only one through {@code SheetedDecalTextureGenerator}. That wrapper still does
 * the second: it drops the item's coordinate and writes the projected one in its place, with the
 * scale and the pose the game computes for the combined draw.
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererGlintMixin
		extends RenderTypeFeatureRenderer<ItemFeatureRenderer.Submit> {

	@Shadow
	@Final
	private QuadInstance quadInstance;

	@Shadow
	private static int getLayerColorSafe(int[] tintLayers, BakedQuad.MaterialInfo material) {
		throw new AssertionError("a shadow's body is never run");
	}

	@Shadow
	private static PoseStack.Pose computeFoilDecalPose(ItemDisplayContext type, PoseStack.Pose pose) {
		throw new AssertionError("a shadow's body is never run");
	}

	@Inject(method = "prepareMainSubmit", at = @At("HEAD"), cancellable = true, require = 1)
	private void vitrail$split(ItemFeatureRenderer.Submit submit, CallbackInfo callback) {
		ItemStackRenderState.FoilType foil = submit.foilType();
		if (foil == ItemStackRenderState.FoilType.NONE || !GlintSplit.active()) {
			return;
		}

		this.quadInstance.setLightCoords(submit.lightCoords());
		this.quadInstance.setOverlayCoords(submit.overlayCoords());
		for (BakedQuad quad : submit.quads()) {
			BakedQuad.MaterialInfo material = quad.materialInfo();
			this.quadInstance.setColor(getLayerColorSafe(submit.tintLayers(), material));
			this.getVertexBuilder(material.itemRenderType()).putBakedQuad(submit.pose(), quad,
					this.quadInstance);
		}

		VertexConsumer glint = this.getVertexBuilder(GlintSplit.itemGlint());
		if (foil == ItemStackRenderState.FoilType.SPECIAL) {
			glint = new SheetedDecalTextureGenerator(glint,
					computeFoilDecalPose(submit.displayContext(), submit.pose()),
					ItemFeatureRenderer.SPECIAL_FOIL_TEXTURE_SCALE);
		}

		for (BakedQuad quad : submit.quads()) {
			glint.putBakedQuad(submit.pose(), quad, this.quadInstance);
		}

		callback.cancel();
	}
}
