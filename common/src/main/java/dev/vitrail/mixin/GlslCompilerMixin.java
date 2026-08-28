package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import dev.vitrail.glsl.LoadClock;
import dev.vitrail.render.SpirvCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Lets a sampled or stored 3D image through the bind-group walk, puts {@link SpirvCache} on both
 * sides of the one call that turns GLSL into SPIR-V, and clocks the whole of it for
 * {@link LoadClock}.
 * <p>
 * {@code addToBindGroup} refuses anything whose SPIR-V dimension is not 2D or Cube. SpvDim3D is 2.
 * Pretending it is 2D is enough for the check; the view that is actually bound is the 3D one
 * {@code StorageImages} allocated.
 * <p>
 * The cache is two handlers on {@code createIntermediary} rather than one, and the split is the
 * point: the head is where a compile can still be skipped, and the call to {@code createFromSpirv}
 * is the last instant at which the bytes shaderc emitted have not yet been rewritten by the
 * reflection that reads them. Storing them from anywhere later would store a module already bent
 * to one pipeline's bindings.
 * <p>
 * The clock sits here and not at this engine's own call sites, because the game's compiler is
 * the funnel and the call sites are not: the background warmup goes through
 * {@code GeometryProgram}, but the terrain, every composite pass and anything a first draw or a
 * resource reload still owes goes through {@code precompilePipeline}, which lands in this same
 * method without a line of this engine on the way. Clocking the funnel counts every road once;
 * clocking a call site counted one road and read as all of them. A wrap rather than a pair of
 * injections, so that a refusal thrown by the compile is still counted: the span ends in a
 * finally, and a module that failed still cost what it cost.
 * <p>
 * The wrap encloses the two cache handlers rather than sitting beside them, because MixinExtras
 * applies a method wrap after every other injector has gone into the body. So a load served off
 * the disk is still clocked, and the figure stays the cost of getting a module whichever way it
 * came.
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

	/**
	 * Hands back a stored module instead of compiling one, when there is one that answers to this
	 * exact text.
	 * <p>
	 * The reflection is run here rather than skipped, on the untouched bytes, so what the caller
	 * receives is what a compile would have built and not a shortcut around it. A module the
	 * reflection refuses is not an error and not a crash: the buffer is dropped, nothing is
	 * cancelled, and the compiler runs the way it always did.
	 * <p>
	 * The text keyed on is the one the head is handed, which is one injection short of the one
	 * shaderc sees: the method's first line adds the compiler's own two global defines. They are
	 * built once in its constructor out of literals and nothing can move them, so they say the same
	 * thing about every unit and cannot tell two of them apart.
	 */
	@Inject(method = "createIntermediary", at = @At("HEAD"), cancellable = true, require = 1)
	private void vitrail$fromDisk(String filename, String source, ShaderType type,
			CallbackInfoReturnable<IntermediaryShaderModule> callback) {
		ByteBuffer stored = SpirvCache.lookup(filename, source, type.name());
		if (stored == null) {
			return;
		}

		IntermediaryShaderModule module;
		try {
			module = IntermediaryShaderModule.createFromSpirv(filename, stored);
		} catch (ShaderCompileException | RuntimeException e) {
			SpirvCache.rejected(stored, String.valueOf(e.getMessage()));

			return;
		}

		callback.setReturnValue(module);
		SpirvCache.served();
	}

	/**
	 * Keeps what shaderc emitted, copied before the call that rewrites it and written only once
	 * that call has come back, so nothing a pack broke is ever stored.
	 */
	@WrapOperation(method = "createIntermediary", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;"
							+ "createFromSpirv(Ljava/lang/String;Ljava/nio/ByteBuffer;)"
							+ "Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;"))
	private IntermediaryShaderModule vitrail$toDisk(String filename, ByteBuffer spirv,
			Operation<IntermediaryShaderModule> original) {
		byte[] raw = SpirvCache.copyOf(spirv);
		IntermediaryShaderModule module = original.call(filename, spirv);
		SpirvCache.store(raw);

		return module;
	}
}
