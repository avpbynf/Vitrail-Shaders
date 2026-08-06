package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.BlockEntityOrigin;
import dev.vitrail.render.BlockEntityGeometry;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The same for an item a block entity puts on itself, which is the shelf and nothing else today.
 * <p>
 * Worth marking even at one caller, and the reason is what it would look like otherwise: an item
 * lying on a shelf takes {@code ITEM_CUTOUT}, which is a row of the entity table, so it would be the
 * one thing in the room lit as a mob while the shelf holding it is lit as a block. Iris sends it to
 * {@code gbuffers_block} like the rest of the block entity.
 */
@Mixin(ItemFeatureRenderer.Submit.class)
public abstract class ItemSubmitMixin implements BlockEntityOrigin {

	@Unique
	private boolean vitrail$blockEntity;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$capture(PoseStack.Pose pose, ItemDisplayContext displayContext,
			int lightCoords, int overlayCoords, int outlineColor, int[] tintLayers,
			List<BakedQuad> quads, ItemStackRenderState.FoilType foilType, CallbackInfo callback) {
		this.vitrail$blockEntity = BlockEntityGeometry.submitting();
	}

	@Override
	public boolean vitrail$fromBlockEntity() {
		return this.vitrail$blockEntity;
	}

	@Override
	public void vitrail$fromBlockEntity(boolean fromBlockEntity) {
		this.vitrail$blockEntity = fromBlockEntity;
	}
}
