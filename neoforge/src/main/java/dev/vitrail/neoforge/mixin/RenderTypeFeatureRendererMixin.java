package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.EntityDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Draws the game's own entity geometry with the programs the pack ships for it.
 * <p>
 * <strong>This class is the door and {@code PreparedRenderType} is not</strong>, which is the one
 * thing worth knowing before moving either of these handlers. {@code drawFromBuffer} opens a render
 * pass per draw, in a try-with-resources, with a single colour attachment: a pack's gbuffers program
 * writes up to eight and none of them could be attached from in there. Its only caller in the whole
 * game is {@code executeGroup}, which holds every draw of a group and has no pass open at all, so
 * that is where a pass covering several draws can be opened. The method is public and not final, and
 * the twelve feature renderers that draw with a render type all inherit it without redefining it.
 * The particles are not among the twelve and are not reached at all: their renderer implements the
 * interface directly rather than extending this class, so it has an {@code executeGroup} of its own
 * that this mixin never sees, and they are a family of their own for that reason.
 * <p>
 * <strong>The wrap replaces the draw rather than adding to it.</strong> {@link EntityDraw} answers
 * whether it recorded the draw itself, and only a no lets the game's own call through. A yes has
 * left a pass open, deliberately: the next draw of the group usually wants the same one, and it is
 * closed by the first draw this engine does not serve or by the handler below.
 * <p>
 * <strong>Both handlers are required, which the rest of this package is not.</strong> The mixin
 * configuration sets {@code defaultRequire} to nought, so an injection that stops matching is
 * normally dropped in silence; these two are a pair and the second is what closes what the first
 * opens, so half of them applying is a render pass left standing over whatever the game draws next.
 * Refusing to start is the better half of that bargain, and it is the only failure of the two that
 * names itself.
 */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMixin {

	@WrapOperation(method = "executeGroup", require = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;"
							+ "drawFromBuffer("
							+ "Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;"
							+ ")V"))
	private void vitrail$draw(PreparedRenderType renderType, StagedVertexBuffer.ExecuteInfo info,
			Operation<Void> original) {
		if (!EntityDraw.draw(renderType, info)) {
			original.call(renderType, info);
		}
	}

	/**
	 * Closes the pass the group left open, which a group whose last draw was this engine's always
	 * does.
	 * <p>
	 * At the return of the group and not at the head of the next one: what the game draws between two
	 * groups is drawn in a pass of its own, and the encoder allows one at a time, so a pass left
	 * standing here would not leak but refuse.
	 */
	@Inject(method = "executeGroup", at = @At("RETURN"), require = 1)
	private void vitrail$close(FeatureFrameContext context, int groupIndex, List<?> submits,
			boolean strictlyOrdered, CallbackInfo callback) {
		EntityDraw.endGroup();
	}
}
