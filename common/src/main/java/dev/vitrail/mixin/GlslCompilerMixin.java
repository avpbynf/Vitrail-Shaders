package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import dev.vitrail.glsl.LoadClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Lets a sampled or stored 3D image through the bind-group walk, and clocks the one call that
 * turns GLSL into a module, for {@link LoadClock}.
 * <p>
 * {@code addToBindGroup} refuses anything whose SPIR-V dimension is not 2D or Cube. SpvDim3D is 2.
 * Pretending it is 2D is enough for the check; the view that is actually bound is the 3D one
 * {@code StorageImages} allocated.
 * <p>
 * The clock sits here and not at this engine's own call sites, because the game's compiler is
 * the funnel and the call sites are not: the background warmup goes through
 * {@code GeometryProgram}, but the terrain, every composite pass and anything a first draw or a
 * resource reload still owes goes through {@code precompilePipeline}, which lands in this same
 * method without a line of this engine on the way. Clocking the funnel counts every road once;
 * clocking a call site counted one road and read as all of them. A wrap rather than a pair of
 * injections, so that a refusal thrown by the compile is still counted: the span ends in a
 * finally, and a module that failed still cost what it cost.
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

	@WrapMethod(method = "createIntermediary", require = 1)
	private IntermediaryShaderModule vitrail$clock(String filename, String source, ShaderType type,
			Operation<IntermediaryShaderModule> original) {
		long began = System.nanoTime();
		try {
			return original.call(filename, source, type);
		} finally {
			LoadClock.module(System.nanoTime() - began);
		}
	}
}
