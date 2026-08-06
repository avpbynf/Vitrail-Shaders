package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.BlockEntityOrigin;
import dev.vitrail.render.BlockEntityGeometry;

import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a model submission's mark back up while that submission is turned into vertices.
 * <p>
 * <strong>This is the moment the mark becomes a draw</strong>, and it is a different moment from
 * either of the two around it. The submission happened during the level walk and is long over; the
 * drawing happens once the frame graph runs. In between, each submission is walked once and asks for
 * a vertex builder, and the builder it is given decides which draw its geometry lands in. That is
 * where a block entity has to be told apart from a mob, and it is the last place where anything
 * knows.
 * <p>
 * The whole method and not the one call, because the mark costs nothing while it is up and nothing
 * reads it but the grouping: {@code getVertexBuilder} is the first thing the method does, and what
 * follows only writes vertices into what it returned.
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererMixin {

	@Inject(method = "prepareModel", at = @At("HEAD"), require = 1)
	private void vitrail$begin(ModelFeatureRenderer.Submit<?> submit, CallbackInfo callback) {
		BlockEntityGeometry.building(((BlockEntityOrigin) (Object) submit).vitrail$fromBlockEntity());
	}

	@Inject(method = "prepareModel", at = @At("RETURN"), require = 1)
	private void vitrail$end(ModelFeatureRenderer.Submit<?> submit, CallbackInfo callback) {
		BlockEntityGeometry.building(false);
	}
}
