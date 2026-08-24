package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Lets a sampled or stored 3D image through the bind-group walk.
 * <p>
 * {@code addToBindGroup} refuses anything whose SPIR-V dimension is not 2D or Cube. SpvDim3D is 2.
 * Pretending it is 2D is enough for the check; the view that is actually bound is the 3D one
 * {@code StorageImages} allocated.
 */
@Mixin(GlslCompiler.class)
public abstract class GlslCompilerMixin {

	@WrapOperation(method = "addToBindGroup", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vulkan/glsl/SpvSampler;dimensions()I"))
	private static int vitrail$allow3d(@Coerce Object sampler, Operation<Integer> original) {
		int dimension = original.call(sampler);
		return dimension == 2 ? 1 : dimension;
	}
}
