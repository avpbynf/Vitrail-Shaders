package dev.vitrail.mixin;

import dev.vitrail.render.BlockEntityGeometry;
import dev.vitrail.render.BlockEntityOrigin;

import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers, on a text submission, whether it was handed in by a block entity renderer.
 * <p>
 * This is the submission a sign's board arrives as, and it is the only block entity text in the
 * game: {@code submitText} has three callers, {@code AbstractSignRenderer} for every line of a
 * board, {@code DisplayRenderer} for a text display and {@code MapRenderer} for a map's own
 * writing, and only the first of the three is a block entity renderer. A lectern is not one of
 * them, its renderer submitting a model and nothing else. A name plate is a submission of its own
 * and is never a block entity, so it needs none of this either: {@code NameTagFeatureRenderer} is
 * reached from an entity renderer alone and its draws are marked with whatever this file leaves
 * standing, which is nothing.
 * <p>
 * At construction and not at storage, which is what {@code ModelSubmitMixin} says: the submission is
 * built inside the submit call, while the dispatcher's mark is still up.
 */
@Mixin(net.minecraft.client.renderer.feature.TextFeatureRenderer.Submit.class)
public abstract class TextSubmitMixin implements BlockEntityOrigin {

	@Unique
	private boolean vitrail$blockEntity;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$capture(Matrix4fc pose, float x, float y, FormattedCharSequence string,
			boolean dropShadow, Font.DisplayMode displayMode, int lightCoords, int color,
			int backgroundColor, int outlineColor, CallbackInfo callback) {
		this.vitrail$blockEntity = BlockEntityGeometry.submitting();
	}

	@Override
	public boolean vitrail$fromBlockEntity() {
		return this.vitrail$blockEntity;
	}
}
