package dev.vitrail.mixin;

import dev.vitrail.render.BlockEntityGeometry;
import dev.vitrail.render.BlockEntityOrigin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Puts a text submission's mark back up while that submission is turned into vertices.
 * <p>
 * The same moment {@code ModelFeatureRendererMixin} catches for a model, reached differently: this
 * renderer has no per submission method, its whole loop being inside {@code buildGroup}, and the
 * call that picks the draw is further down the stack in the glyph visitor rather than in the loop.
 * What the loop does hold is the start of each submission, and every vertex of one submission is
 * written after it and before the next, so the mark goes up there and comes down when the group is
 * finished.
 * <p>
 * <strong>{@code CustomFeatureRendererMixin} is the precedent and the shape is copied from
 * it</strong>, that renderer having no per submission method either: the same {@code buildGroup},
 * the same pair of an {@code INVOKE} on a call the loop makes for each submission and a
 * {@code RETURN} that lowers the mark, and the same {@code @Local} to reach the submission the loop
 * is on.
 * <p>
 * <strong>Iris marks the same draws from the other end.</strong> It wraps the render type a glyph
 * is about to take while a block entity is being submitted, {@code GlyphRenderTypes.select} being
 * where every glyph asks for one ({@code mixin/entity_render_context/MixinGlyphRenderType.java:19}).
 * That road is shut here for the reason {@code BlockEntityGeometry} gives for the models:
 * {@code RenderType}'s constructor is private and there is no marked subclass to wrap one in.
 * <p>
 * <strong>The 26.3 half.</strong> The loop no longer visits the prepared text itself. A submission
 * carries a content, which is either text, turned into glyphs by a static {@code renderText} that
 * makes the one to three visits 26.2 made in the loop, or the box of a text display, handed to the
 * glyph visitor directly. So the mark goes up at the one call the loop makes for every submission
 * right before either of those, reading its content, rather than at the visit, which the loop no
 * longer reaches. Both kinds of content belong to the submission the loop is on, so neither wants a
 * different answer.
 */
@Mixin(TextFeatureRenderer.class)
public abstract class TextFeatureRendererMixin {

	@Inject(method = "buildGroup", require = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Submit;"
							+ "content()Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Content;"))
	private void vitrail$begin(FeatureFrameContext context, List<TextFeatureRenderer.Submit> submits,
			CallbackInfo callback, @Local TextFeatureRenderer.Submit submit) {
		BlockEntityGeometry.building(((BlockEntityOrigin) (Object) submit).vitrail$fromBlockEntity());
	}

	@Inject(method = "buildGroup", at = @At("RETURN"), require = 1)
	private void vitrail$end(FeatureFrameContext context, List<TextFeatureRenderer.Submit> submits,
			CallbackInfo callback) {
		BlockEntityGeometry.building(false);
	}
}
