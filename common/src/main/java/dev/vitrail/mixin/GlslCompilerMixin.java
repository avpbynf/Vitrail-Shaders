package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import dev.vitrail.glsl.LoadClock;
import dev.vitrail.render.ModuleCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Lets a sampled or stored 3D image through the bind-group walk, and puts {@link ModuleCache}
 * around the one call that turns a pack's GLSL into a module, which is also where
 * {@link LoadClock} counts what that costs.
 * <p>
 * {@code addToBindGroup} refuses anything whose SPIR-V dimension is not 2D or Cube. SpvDim3D is 2.
 * Pretending it is 2D is enough for the check; the view that is actually bound is the 3D one
 * {@code StorageImages} allocated.
 * <p>
 * The cache is around the whole method rather than inside it, and that is the difference between
 * this and caching the SPIR-V alone. {@code createIntermediary} is two costs in a row, shaderc and
 * then the SPIRV-Cross reflection reading what shaderc emitted, and the reflection is the half a
 * SPIR-V cache leaves standing. Wrapping the method skips both: the module that comes back is
 * built from the file, and nothing native runs. Nothing happens after the reflection inside the
 * method, so a module taken here is a module taken the instant it was finished, before any caller
 * has had it and before {@code rebind} has bent its bytes to one pipeline's bindings.
 * <p>
 * The clock stays on this method rather than on this engine's own call sites, because the game's
 * compiler is the funnel and the call sites are not: the background warmup goes through
 * {@code GeometryProgram}, but the terrain, every composite pass and anything a first draw or a
 * resource reload still owes goes through {@code precompilePipeline}, which lands here without a
 * line of this engine on the way. Clocking the funnel counts every road once; clocking a call site
 * counted one road and read as all of them. The span ends in a finally, so a refusal thrown by a
 * compile still costs what it cost, and a served unit is clocked like any other: the figure is what
 * getting a module took, whichever way it came.
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

	/**
	 * The text keyed on is the one this method is handed, which is two lines short of the one
	 * shaderc sees: the method splices the compiler's own two global defines in behind the
	 * version directive. They are built once in its constructor out of literals and nothing can
	 * move them, so they say the same thing about every unit and cannot tell two of them apart.
	 */
	@WrapMethod(method = "createIntermediary", require = 1)
	private IntermediaryShaderModule vitrail$module(String filename, String source, ShaderType type,
			Operation<IntermediaryShaderModule> original) {
		long began = System.nanoTime();
		try {
			String key = ModuleCache.keyOf(filename, source, type.name());
			IntermediaryShaderModule served = ModuleCache.lookup(key, filename);
			if (served != null) {
				return served;
			}

			// Counted before the call and not after it: a unit a pack broke throws out of the
			// compile, and counting on the way back would leave that load short by exactly the
			// units somebody is reading the log to find.
			ModuleCache.building();
			IntermediaryShaderModule built = original.call(filename, source, type);
			ModuleCache.store(key, built);

			return built;
		} finally {
			LoadClock.module(System.nanoTime() - began);
		}
	}
}
