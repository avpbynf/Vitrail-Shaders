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
 * call that picks the draw is two frames down the stack in the glyph visitor rather than in the
 * loop. What the loop does hold is the visit, and every vertex of one submission is written inside
 * it, so the mark goes up there and comes down when the group is finished.
 * <p>
 * <strong>{@code CustomFeatureRendererMixin} is the precedent and the shape is copied from
 * it</strong>, that renderer having no per submission method either: the same {@code buildGroup},
 * the same pair of an {@code INVOKE} on the call that picks the draw and a {@code RETURN} that
 * lowers the mark, and the same {@code @Local} to reach the submission the loop is on.
 * <p>
 * The injection binds to every visit of the method and there are three, one for plain text and two
 * for text carrying an outline. All three belong to the submission the loop is on, so none of them
 * wants a different answer.
 * <p>
 * <strong>Iris marks the same draws from the other end.</strong> It wraps the render type a glyph
 * is about to take while a block entity is being submitted, {@code GlyphRenderTypes.select} being
 * where every glyph asks for one ({@code mixin/entity_render_context/MixinGlyphRenderType.java:19}).
 * That road is shut here for the reason {@code BlockEntityGeometry} gives for the models:
 * {@code RenderType}'s constructor is private and there is no marked subclass to wrap one in.
 */
@Mixin(TextFeatureRenderer.class)
public abstract class TextFeatureRendererMixin {

	@Inject(method = "buildGroup", require = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Font$PreparedText;"
							+ "visit(Lnet/minecraft/client/gui/Font$GlyphVisitor;)V"))
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
