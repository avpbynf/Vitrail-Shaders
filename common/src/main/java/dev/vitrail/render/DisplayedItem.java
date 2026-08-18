package dev.vitrail.render;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import org.jspecify.annotations.Nullable;

/**
 * Which item a render state was built for, kept on the state because the state itself does not keep
 * it.
 * <p>
 * What the game keeps is what has to be DRAWN, quads and transforms, and the item is gone by then.
 * The pack asks about the item, so the answer is taken where the stack is still in hand, at the one
 * call that fills the state, and read back where the state is submitted. It is the same shape as
 * {@link BlockEntityOrigin} for the same reason and Iris carries it the same way
 * ({@code mixin/entity_render_context/ItemStackStateMixin.java}).
 * <p>
 * The model identifier is kept beside the item and is not the same question: an item may name a
 * model of its own, and that name is what the pack is asked about first, so that two items sharing
 * one model are one number and a resource pack that renames a model is followed.
 * <p>
 * Outside the mixin package, like its neighbours: a class in there is read as a mixin.
 */
public interface DisplayedItem {

	void vitrail$displayedItem(@Nullable Item item, @Nullable Identifier modelId);

	@Nullable Item vitrail$displayedItem();

	@Nullable Identifier vitrail$displayedModel();
}
