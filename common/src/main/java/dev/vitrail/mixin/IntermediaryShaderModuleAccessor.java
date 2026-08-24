package dev.vitrail.mixin;

import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * The sampler and uniform-buffer lists on a reflected module, without naming the package-private
 * record types those lists hold.
 */
@Mixin(IntermediaryShaderModule.class)
@SuppressWarnings("rawtypes")
public interface IntermediaryShaderModuleAccessor {

	@Accessor("samplers")
	@SuppressWarnings("rawtypes")
	List vitrail$samplers();

	@Accessor("uniformBuffers")
	@SuppressWarnings("rawtypes")
	List vitrail$uniformBuffers();
}
