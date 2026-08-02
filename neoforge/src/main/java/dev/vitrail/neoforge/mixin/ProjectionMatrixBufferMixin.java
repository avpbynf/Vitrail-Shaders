package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.CapturedProjection;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Copies the projection the level is about to be drawn with, and does nothing else.
 * <p>
 * The one mixin in the engine, and it exists because the matrix in question is never stored.
 * {@code GameRenderer.renderLevel} takes the camera's projection, multiplies in the walk bob, the
 * damage tilt, the nausea rotation and the portal skew, hands the result straight to this method
 * and lets it go. The camera's own field is the version before all four, and the difference is
 * invisible standing still and wrong as soon as the player walks.
 * <p>
 * No filter is needed on the target. This overload, the one taking a bare matrix rather than a
 * {@code Projection}, has exactly one caller in the whole game and it is the world; the seven other
 * users of this class all go through the other overload. The sanity test lives in
 * {@link CapturedProjection} rather than here, so that this stays a copy.
 */
@Mixin(ProjectionMatrixBuffer.class)
public class ProjectionMatrixBufferMixin {

	@Inject(method = "getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
			at = @At("HEAD"))
	private void vitrail$capture(Matrix4f projectionMatrix,
			CallbackInfoReturnable<GpuBufferSlice> callback) {
		CapturedProjection.capture(projectionMatrix);
	}
}
