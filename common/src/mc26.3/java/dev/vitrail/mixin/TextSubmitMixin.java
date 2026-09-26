package dev.vitrail.mixin;

import dev.vitrail.render.BlockEntityGeometry;
import dev.vitrail.render.BlockEntityOrigin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
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
 * them, its renderer submitting a model and nothing else.
 * <p>
 * At construction and not at storage, which is what {@code ModelSubmitMixin} says: the submission is
 * built inside the submit call, while the dispatcher's mark is still up.
 * <p>
 * <strong>The 26.3 half.</strong> The submission is a pose, a display mode, a light and a content,
 * where 26.2 carried the string, its place and its colours field by field; the content is the text
 * or the box of a text display, which 26.3 submits through {@code submitTextBackground} as a
 * submission of the same kind. A name plate is one as well on this game, built by the submit node
 * collection for the entity renderer that asked for it: it is marked with whatever the dispatcher
 * has up at that moment, which is nothing, since no block entity renderer draws one. Only the
 * constructor's arguments changed, and the mark is taken the same way.
 */
@Mixin(TextFeatureRenderer.Submit.class)
public abstract class TextSubmitMixin implements BlockEntityOrigin {

	@Unique
	private boolean vitrail$blockEntity;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$capture(Matrix4fc pose, Font.DisplayMode displayMode, int lightCoords,
			TextFeatureRenderer.Content content, CallbackInfo callback) {
		this.vitrail$blockEntity = BlockEntityGeometry.submitting();
	}

	@Override
	public boolean vitrail$fromBlockEntity() {
		return this.vitrail$blockEntity;
	}
}
