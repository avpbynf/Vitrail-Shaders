package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.BlockEntityOrigin;

import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Carries a draw's origin from the moment its geometry was gathered to the moment it is executed.
 * <p>
 * A draw is the last object the two halves of the frame have in common: it is made while the
 * features are prepared, out of one or more submissions, and it is handed back by name when the
 * frame graph runs. Nothing else survives the gap, which is why the mark lands here rather than on
 * the render type or on the prepared render type, neither of which knows a chest from a mob.
 * <p>
 * No injection at all, only a field and the two accessors that read it, so there is nothing here to
 * stop matching.
 */
@Mixin(StagedVertexBuffer.Draw.class)
public abstract class StagedVertexBufferDrawMixin implements BlockEntityOrigin {

	@Unique
	private boolean vitrail$blockEntity;

	@Override
	public boolean vitrail$fromBlockEntity() {
		return this.vitrail$blockEntity;
	}

	@Override
	public void vitrail$fromBlockEntity(boolean fromBlockEntity) {
		this.vitrail$blockEntity = fromBlockEntity;
	}
}
