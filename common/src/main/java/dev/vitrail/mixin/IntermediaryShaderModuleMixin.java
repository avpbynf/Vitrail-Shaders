package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import dev.vitrail.render.ComputeShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Lists SPIR-V storage images and storage buffers beside the resources the game asks SPIRV-Cross
 * for, and lets a 3D image through {@code rebind}.
 * <p>
 * Type 6 is a storage image. Type 2 is a storage buffer. Type 7 is a sampled image.
 * {@code createFromSpirv} walks 1 and 7 and leaves 2 and 6 on the bindings shaderc assigned.
 * Complementary's {@code uimage3D voxel_img} and {@code blockDataBuffer} are those resources.
 * Construction of the package-private records is {@link ComputeShader}, by reflection.
 */
@Mixin(IntermediaryShaderModule.class)
public abstract class IntermediaryShaderModuleMixin {

	@Inject(method = "createFromSpirv", at = @At("RETURN"), require = 1)
	private static void vitrail$storageImages(String filename, ByteBuffer spirv,
			CallbackInfoReturnable<IntermediaryShaderModule> callback) {
		ComputeShader.appendStorageImages(callback.getReturnValue());
		ComputeShader.appendStorageBuffers(callback.getReturnValue());
	}

	@WrapOperation(method = "rebind", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vulkan/glsl/SpvSampler;dimensions()I"))
	private int vitrail$allow3d(@Coerce Object sampler, Operation<Integer> original) {
		int dimension = original.call(sampler);
		return dimension == 2 ? 1 : dimension;
	}
}
