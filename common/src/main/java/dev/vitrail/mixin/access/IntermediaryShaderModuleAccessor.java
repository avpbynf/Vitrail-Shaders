package dev.vitrail.mixin.access;

import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * The four reflected lists on a module, without naming the package-private record types those
 * lists hold.
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

	@Accessor("outputs")
	@SuppressWarnings("rawtypes")
	List vitrail$outputs();

	@Accessor("inputs")
	@SuppressWarnings("rawtypes")
	List vitrail$inputs();
}
