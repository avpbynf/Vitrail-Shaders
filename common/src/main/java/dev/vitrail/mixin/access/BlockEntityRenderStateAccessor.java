package dev.vitrail.mixin.access;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The block state a block entity stands in, which the render state keeps to itself.
 * <p>
 * Every other field of that class is public and this one is not, and there is no getter beside it.
 * It is what {@code block.properties} is asked about, the pack's number for a block entity being the
 * number it gave that state, so the alternative would be the block entity TYPE, which is a coarser
 * question than the one the pack asked.
 */
@Mixin(BlockEntityRenderState.class)
public interface BlockEntityRenderStateAccessor {

	@Accessor("blockState")
	BlockState vitrail$blockState();
}
