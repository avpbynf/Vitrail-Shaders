package dev.vitrail.mixin;

import dev.vitrail.render.BlockEntityGeometry;
import dev.vitrail.render.BlockEntityOrigin;
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
 * <strong>All three handlers are required, and say so rather than lean on the configuration's
 * default.</strong> Writing the count out is what keeps it right when a handler moves or a target
 * gains a call, since the default is one whatever the injector really binds. The two
 * that open and close are a pair, and half of them applying is a render pass left standing over
 * whatever the game draws next. The third, which says where a draw came from, fails more quietly
 * and worse: every chest in the world would be lit as a mob, and nothing on screen or in the log
 * would say why. Refusing to start is the better half of both bargains, and it is the only failure
 * of the three that names itself.
 */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMixin {

	/**
	 * Publishes the origin of the draw about to be executed, one line before it is.
	 * <p>
	 * Here and not beside the draw itself, because this is the only call of the loop that is handed
	 * the {@code Draw}, which is what carries the mark: the handler below sees a prepared render type
	 * and an execute info, and neither of them knows a chest from a mob. The two calls are
	 * consecutive and in the same turn of the same loop, so nothing can come between them.
	 * <p>
	 * A group whose execute info comes back null leaves the mark standing, which costs nothing: the
	 * next turn sets it again and the door is not reached at all in between.
	 */
	@WrapOperation(method = "executeGroup", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;"
					+ "getExecuteInfo(Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;)"
					+ "Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;"))
	private StagedVertexBuffer.ExecuteInfo vitrail$origin(StagedVertexBuffer buffer,
			StagedVertexBuffer.Draw draw, Operation<StagedVertexBuffer.ExecuteInfo> original) {
		BlockEntityGeometry.drawing(((BlockEntityOrigin) draw).vitrail$fromBlockEntity());

		return original.call(buffer, draw);
	}

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
