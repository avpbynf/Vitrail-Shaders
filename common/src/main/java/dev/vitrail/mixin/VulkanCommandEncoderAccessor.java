package dev.vitrail.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The command buffer the encoder is recording into this frame, which is where storage-image
 * clears and compute dispatches have to go: a buffer of our own would race the pass the shadow
 * geometry just closed.
 */
@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {

	@Invoker("commandBuffer")
	VkCommandBuffer vitrail$commandBuffer();

	@Accessor("currentRenderPass")
	VulkanRenderPass vitrail$currentRenderPass();
}
