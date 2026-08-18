package dev.vitrail.mixin;

import dev.vitrail.render.EntityIdentifiers;
import dev.vitrail.render.SubmittedIdentifiers;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * And the same for the fire on a burning mob, which is a submission of its own and comes off the
 * entity mesh like the mob under it.
 * <p>
 * The dispatcher submits it inside the very call that knows which mob is being handed in, so the
 * answer is true here and gone by the time the quads are built. Iris marks it too
 * ({@code mixin/entity_render_context/MixinFlameFeatureRenderer.java}). What a pack loses without it
 * is narrow and real: the fire reads nought where the mob it wraps reads its own number, so a pack
 * that treats a kind of mob specially treats its flame as something else.
 */
@Mixin(FlameFeatureRenderer.Submit.class)
public abstract class FlameSubmitMixin implements SubmittedIdentifiers {

	@Unique
	private long vitrail$identifiers;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$capture(PoseStack.Pose pose, EntityRenderState state, Quaternionf rotation,
			CallbackInfo callback) {
		this.vitrail$identifiers = EntityIdentifiers.packed();
	}

	@Override
	public long vitrail$identifiers() {
		return this.vitrail$identifiers;
	}
}
