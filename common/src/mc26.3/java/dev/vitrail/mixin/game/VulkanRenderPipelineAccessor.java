package dev.vitrail.mixin.game;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The descriptor set layout a compiled pipeline was built with, which 26.3 keeps in a private field
 * where 26.2 answered it through {@code layout().handle()}: {@code VulkanRenderPassMixin} asks
 * {@code WideSamplerSets} about it at every push.
 */
@Mixin(VulkanRenderPipeline.class)
public interface VulkanRenderPipelineAccessor {

	@Accessor("descriptorSetLayout")
	long vitrail$setLayout();
}
