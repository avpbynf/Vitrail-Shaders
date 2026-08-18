package dev.vitrail.mixin;

import dev.vitrail.render.DisplayedItem;

import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers which item a render state was filled from, at the one call that still holds the stack.
 * <p>
 * The head and not the return, because the method may leave without touching the state at all when
 * the stack names no model, and the answer is wanted either way: a state filled by nothing is a
 * state that draws nothing, and the item behind it is still the one the pack would be asked about.
 * <p>
 * Iris hooks the same method for the same reason
 * ({@code mixin/entity_render_context/MixinItemRenderer.java:23}).
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {

	@Inject(method = "appendItemLayers", at = @At("HEAD"), require = 1)
	private void vitrail$rememberItem(ItemStackRenderState output, ItemStack item,
			ItemDisplayContext displayContext, Level level, ItemOwner owner, int seed,
			CallbackInfo callback) {
		((DisplayedItem) output)
				.vitrail$displayedItem(item.getItem(), item.get(DataComponents.ITEM_MODEL));
	}
}
