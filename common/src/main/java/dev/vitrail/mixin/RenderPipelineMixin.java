package dev.vitrail.mixin;

import dev.vitrail.render.EntityMesh;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.jspecify.annotations.Nullable;

/**
 * Hands the game's own entity pipelines the mesh this engine really builds, which is the whole answer
 * to what becomes of a draw a loaded pack does not serve.
 * <p>
 * Iris's own answer, one method for one question
 * ({@code mixin/MixinRenderPipeline.iris$change}): a pipeline declaring the game's entity format
 * reports the extended one instead, under the same gate as the mesh, so the two move together and
 * cannot disagree. {@code EntityMesh} carries why the gate has to be a settled answer here where Iris
 * reads its own live.
 * <p>
 * <strong>One door and not two.</strong> This is not only what the compiled pipeline binds: a draw of
 * the level takes its format from {@code RenderType.format()}, which is
 * {@code getVertexFormatBinding(0)} on this very pipeline ({@code RenderType:80}), and
 * {@code RenderTypeFeatureRenderer:88} hands that to {@code StagedVertexBuffer.appendDraw}, which is
 * what the {@code BufferBuilder} of the draw is then built with. So the mesh, the staging buffer and
 * the pipeline's vertex input all follow from this one answer, and there is nowhere for them to fall
 * out of step. Iris needs a second injection for the mesh because it swaps at the builder instead.
 * <p>
 * <strong>Nothing of this engine's own is touched</strong>, {@code EntityMesh.binding} asking by
 * identity: a pipeline built here already binds the extended format, which is not the game's field.
 */
@Mixin(RenderPipeline.class)
public abstract class RenderPipelineMixin {

	/**
	 * This pipeline's bindings with the entity one exchanged, worked out once: the format is a
	 * constant and the array behind a pipeline is final, so the answer for a given pipeline never
	 * moves. Null until it is first wanted, and it is only ever wanted while the mesh carries.
	 * <p>
	 * It is the declared array itself where no binding moved, which is every pipeline of the game but
	 * the entity ones, so a hundred pipelines hold one field and allocate nothing.
	 */
	@Unique
	private VertexFormat @Nullable [] vitrail$carried;

	@Inject(method = "getVertexFormatBindings", at = @At("RETURN"), cancellable = true, require = 1)
	private void vitrail$bindings(CallbackInfoReturnable<VertexFormat[]> callback) {
		if (!EntityMesh.carrying()) {
			return;
		}

		VertexFormat[] declared = callback.getReturnValue();
		if (declared == null) {
			return;
		}

		VertexFormat[] carried = this.vitrail$carried;
		if (carried == null) {
			carried = exchange(declared);
			this.vitrail$carried = carried;
		}

		callback.setReturnValue(carried);
	}

	@Inject(method = "getVertexFormatBinding", at = @At("RETURN"), cancellable = true, require = 1)
	private void vitrail$binding(int bindingIndex, CallbackInfoReturnable<VertexFormat> callback) {
		callback.setReturnValue(EntityMesh.binding(callback.getReturnValue()));
	}

	@Unique
	private static VertexFormat[] exchange(VertexFormat[] declared) {
		VertexFormat[] carried = declared.clone();
		boolean moved = false;
		for (int binding = 0; binding < carried.length; binding++) {
			VertexFormat one = EntityMesh.binding(carried[binding]);
			moved = moved || one != carried[binding];
			carried[binding] = one;
		}

		return moved ? carried : declared;
	}
}
