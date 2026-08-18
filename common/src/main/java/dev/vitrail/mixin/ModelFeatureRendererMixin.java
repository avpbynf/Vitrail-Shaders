package dev.vitrail.mixin;

import dev.vitrail.render.BlockEntityGeometry;
import dev.vitrail.render.BlockEntityOrigin;
import dev.vitrail.render.EntityIdentifiers;
import dev.vitrail.render.SubmittedIdentifiers;

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
 * reads it but the grouping. {@code getVertexBuilder} is the second thing the method does, the pose
 * being set first, and everything after it only writes vertices into what it returned.
 * <p>
 * <strong>The three identifiers come back up in the same breath and go down differently.</strong>
 * The mark is read once, when the draw is picked; the identifiers are read on every vertex written
 * afterwards, which is the whole of the rest of this method. So this is not a convenience: it is the
 * only window in which they are true, and it has to cover every vertex of the submission.
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererMixin {

	@Inject(method = "prepareModel", at = @At("HEAD"), require = 1)
	private void vitrail$begin(ModelFeatureRenderer.Submit<?> submit, CallbackInfo callback) {
		BlockEntityGeometry.building(((BlockEntityOrigin) (Object) submit).vitrail$fromBlockEntity());
		EntityIdentifiers.restore(((SubmittedIdentifiers) (Object) submit).vitrail$identifiers());
	}

	@Inject(method = "prepareModel", at = @At("RETURN"), require = 1)
	private void vitrail$end(ModelFeatureRenderer.Submit<?> submit, CallbackInfo callback) {
		BlockEntityGeometry.building(false);
		EntityIdentifiers.clear();
	}
}
