package dev.vitrail.mixin;

import dev.vitrail.render.CameraBob;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Copies the four transforms {@code renderLevel} multiplies into the projection, and changes
 * nothing.
 * <p>
 * Every one of these calls the original operation, so the game's projection comes out of this method
 * exactly as it went in and the world is drawn with the same matrix as before. What is taken is a
 * copy, and {@link CameraBob} says why a pack needs the four apart: they belong in the model view,
 * where OptiFine put them and where every pack still expects them.
 * <p>
 * There are four multiplications and three of them are conditional. The pose comes first and always
 * happens, which is what makes it the one that starts the frame's copy; the rotation, the skew and
 * the rotation back only run under nausea or a portal. Intercepting the three would be pointless
 * without the first and dangerous without each other, so the invariant that they were all caught is
 * checked against the drawn matrix rather than trusted.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@WrapOperation(method = "renderLevel",
			at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;"))
	private Matrix4f vitrail$bob(Matrix4f projection, Matrix4fc bob, Operation<Matrix4f> original) {
		CameraBob.take(bob);

		return original.call(projection, bob);
	}

	@WrapOperation(method = "renderLevel",
			at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;rotate(FLorg/joml/Vector3fc;)Lorg/joml/Matrix4f;"))
	private Matrix4f vitrail$spin(Matrix4f projection, float angle, Vector3fc axis,
			Operation<Matrix4f> original) {
		CameraBob.rotate(angle, axis);

		return original.call(projection, angle, axis);
	}

	@WrapOperation(method = "renderLevel",
			at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;scale(FFF)Lorg/joml/Matrix4f;"))
	private Matrix4f vitrail$skew(Matrix4f projection, float x, float y, float z,
			Operation<Matrix4f> original) {
		CameraBob.scale(x, y, z);

		return original.call(projection, x, y, z);
	}
}
