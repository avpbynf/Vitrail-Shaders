package dev.vitrail.mixin;

import dev.vitrail.render.BlockStateIds;
import dev.vitrail.render.DisplayedItem;
import dev.vitrail.render.EntityIdentifiers;
import dev.vitrail.render.PackNameIds;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SolidBucketItem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the item a render state was built for, and says which number it is worth while it is
 * being submitted.
 * <p>
 * <strong>Round the whole state and not round each of its layers</strong>, which is where Iris puts
 * it ({@code mixin/entity_render_context/ItemStackStateLayerMixin.java:37}): every layer of one
 * state is the same item, the item is kept on the state rather than on the layer, and the layers of
 * one state are submitted in a single loop with nothing between them. One window instead of one per
 * layer, and the same window.
 * <p>
 * <strong>A block held as an item answers on the BLOCK table and puts a one in the block entity
 * lane</strong>, which reads like a magic number and is the packs' own contract: it is how a shader
 * tells a block item apart from an ordinary one, and Iris writes the same one
 * ({@code ItemStackStateLayerMixin.java:66-69}). It is also why the lane is put back afterwards
 * rather than dropped: an item is submitted inside a block entity's own window often enough, a
 * flower in a pot or a sword on an armour stand, and the block entity has to get its number back.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements DisplayedItem {

	/** What a block item puts in the block entity lane, which is the packs' way of recognising one. */
	@Unique
	private static final int BLOCK_ITEM = 1;

	@Unique
	private @Nullable Item vitrail$item;

	@Unique
	private @Nullable Identifier vitrail$model;

	/** The block entity's own number, held for the length of one item and put back after it. */
	@Unique
	private int vitrail$heldBlockEntity;

	@Override
	public void vitrail$displayedItem(@Nullable Item item, @Nullable Identifier modelId) {
		this.vitrail$item = item;
		this.vitrail$model = modelId;
	}

	@Override
	public @Nullable Item vitrail$displayedItem() {
		return this.vitrail$item;
	}

	@Override
	public @Nullable Identifier vitrail$displayedModel() {
		return this.vitrail$model;
	}

	@Inject(method = "clear", at = @At("HEAD"), require = 1)
	private void vitrail$forgetItem(CallbackInfo callback) {
		this.vitrail$item = null;
		this.vitrail$model = null;
	}

	@Inject(method = "submit", at = @At("HEAD"), require = 1)
	private void vitrail$begin(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
			int overlayCoords, int outlineColor, CallbackInfo callback) {
		this.vitrail$heldBlockEntity = EntityIdentifiers.blockEntity();

		Item item = this.vitrail$item;
		if (item == null) {
			return;
		}

		// A bucket of powder snow is a block item and is not drawn as one, which is the one exception
		// Iris carries and it carries it by class.
		if (item instanceof BlockItem block && !(item instanceof SolidBucketItem)) {
			EntityIdentifiers.blockEntity(BLOCK_ITEM);
			EntityIdentifiers.item(BlockStateIds.id(block.getBlock().defaultBlockState()));

			return;
		}

		// The model the item names comes first and its registry key second, so that a resource pack
		// which points two items at one model has the pack answer once for both.
		Identifier name = this.vitrail$model != null
				? this.vitrail$model
				: BuiltInRegistries.ITEM.getKey(item);
		EntityIdentifiers.item(name == null ? -1 : PackNameIds.item(name));
	}

	@Inject(method = "submit", at = @At("RETURN"), require = 1)
	private void vitrail$end(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
			int overlayCoords, int outlineColor, CallbackInfo callback) {
		EntityIdentifiers.blockEntity(this.vitrail$heldBlockEntity);
		EntityIdentifiers.item(0);
	}
}
