package dev.vitrail.mixin;

import dev.vitrail.render.BlockEntityGeometry;
import dev.vitrail.render.BlockEntityOrigin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The same for geometry a block entity draws by hand rather than through a model.
 * <p>
 * Three block entity renderers of the game take this road. Most of what they draw blends, so it is
 * no row of the entity table and never reaches the door; the mark is taken all the same, because
 * whether a given renderer blends is the game's to change and this file would not notice.
 */
@Mixin(CustomFeatureRenderer.Submit.class)
public abstract class CustomSubmitMixin implements BlockEntityOrigin {

	@Unique
	private boolean vitrail$blockEntity;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$capture(PoseStack.Pose pose, RenderType renderType,
			SubmitNodeCollector.CustomGeometryRenderer renderer, CallbackInfo callback) {
		this.vitrail$blockEntity = BlockEntityGeometry.submitting();
	}

	@Override
	public boolean vitrail$fromBlockEntity() {
		return this.vitrail$blockEntity;
	}
}
