package dev.vitrail.mixin;

import dev.vitrail.render.MipmapCommands;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Two encoder internals the public API does not expose.
 * <p>
 * Mip chains go through {@code vkCmdBlitImage}, which is what {@code glGenerateMipmap} is on this
 * backend: scaled copies on the same command buffer, not a render pass per level. A pass per level
 * was the only public path, and each one ends with a full memory barrier. Iris pays one driver
 * call. The blit loop is that call: transfer barriers between levels so each read sees the write
 * below it, then the encoder's own full barrier once so the rest of the frame can sample the chain.
 * <p>
 * Closing a pass still runs the encoder's full barrier. Our own labels start with {@code Vitrail}
 * and get a write-then-sample barrier instead; the rest of the frame keeps the original wait.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin implements MipmapCommands {

	@Shadow
	private VulkanRenderPass currentRenderPass;

	@Invoker("commandBuffer")
	abstract VkCommandBuffer vitrail$commandBuffer();

	@Unique
	private Supplier<String> vitrail$closingLabel;

	@Inject(method = "submitRenderPass", at = @At("HEAD"), require = 1)
	private void vitrail$rememberLabel(CallbackInfo ci) {
		this.vitrail$closingLabel = this.currentRenderPass == null
				? null
				: this.currentRenderPass.getLabel();
	}

	/**
	 * The game ends every pass with a full memory barrier. Iris does not: it binds an FBO and
	 * draws. On MoltenVK that full barrier is a Metal wait, which is the extra queue-submit count
	 * on Apple Silicon. Our own passes only need write-then-sample, so they get that instead.
	 */
	@Redirect(method = "submitRenderPass", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier("
					+ "Lorg/lwjgl/system/MemoryStack;)V"),
			require = 1)
	private void vitrail$afterPass(VulkanCommandEncoder self, MemoryStack stack) {
		Supplier<String> label = this.vitrail$closingLabel;
		this.vitrail$closingLabel = null;
		VkCommandBuffer commands = vitrail$commandBuffer();
		if (label != null && vitrail$ours(label)) {
			vitrail$framebufferBarrier(commands, stack);
		} else {
			VulkanCommandEncoder.memoryBarrier(commands, stack);
		}
	}

	@Unique
	private static boolean vitrail$ours(Supplier<String> label) {
		String name = label.get();
		return name != null && name.startsWith("Vitrail");
	}

	@Unique
	private static void vitrail$framebufferBarrier(VkCommandBuffer commands, MemoryStack stack) {
		VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT_KHR)
				.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR)
				// The vertex stage samples too, and leaving it out is not theoretical: a sampler
				// declared in a pack's vertex unit is kept and bound like any other
				// (ProgramTranslator collects the samplers of every stage), so a program whose
				// vertex shader reads a target the pass before it wrote would race the write.
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR)
				.dstAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR);
		VkDependencyInfo info = VkDependencyInfo.calloc(stack)
				.sType$Default()
				.pMemoryBarriers(barrier);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands, info);
	}

	@Override
	public boolean vitrail$generateMipmaps(GpuTexture texture) {
		if (this.currentRenderPass != null || !(texture instanceof VulkanGpuTexture image)) {
			return false;
		}

		int levels = texture.getMipLevels();
		if (levels <= 1 || texture.isClosed()) {
			return false;
		}

		int aspect = VulkanConst.formatAspectMask(texture.getFormat());
		int filter = integer(texture) ? VK10.VK_FILTER_NEAREST : VK10.VK_FILTER_LINEAR;
		long vkImage = image.vkImage();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandBuffer commands = vitrail$commandBuffer();
			for (int level = 1; level < levels; level++) {
				int srcWidth = Math.max(1, texture.getWidth(level - 1));
				int srcHeight = Math.max(1, texture.getHeight(level - 1));
				int dstWidth = Math.max(1, texture.getWidth(level));
				int dstHeight = Math.max(1, texture.getHeight(level));

				VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
				region.srcSubresource()
						.aspectMask(aspect)
						.mipLevel(level - 1)
						.baseArrayLayer(0)
						.layerCount(1);
				region.dstSubresource()
						.aspectMask(aspect)
						.mipLevel(level)
						.baseArrayLayer(0)
						.layerCount(1);
				region.srcOffsets(0).set(0, 0, 0);
				region.srcOffsets(1).set(srcWidth, srcHeight, 1);
				region.dstOffsets(0).set(0, 0, 0);
				region.dstOffsets(1).set(dstWidth, dstHeight, 1);

				VK12.vkCmdBlitImage(commands, vkImage, VK10.VK_IMAGE_LAYOUT_GENERAL, vkImage,
						VK10.VK_IMAGE_LAYOUT_GENERAL, region, filter);

				if (level + 1 < levels) {
					vitrail$transferBarrier(commands, stack, vkImage, aspect, level);
				}
			}

			VulkanCommandEncoder.memoryBarrier(commands, stack);
			return true;
		} catch (RuntimeException e) {
			Vitrail.logger().error("Blit mipmaps failed on {}, falling back to a pass per level",
					texture.getLabel(), e);
			return false;
		}
	}

	/**
	 * The level just written becomes the source of the next blit. A full memory barrier would also
	 * do, and would split the work the way a render pass does; this stays in transfer so the whole
	 * chain can stay one blit encoder on Metal.
	 */
	@Unique
	private static void vitrail$transferBarrier(VkCommandBuffer commands, MemoryStack stack,
			long image, int aspect, int mip) {
		VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
				.aspectMask(aspect)
				.baseMipLevel(mip)
				.levelCount(1)
				.baseArrayLayer(0)
				.layerCount(1);
		VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR)
				.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR)
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR)
				.dstAccessMask(KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR)
				.oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
				.newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
				.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.image(image)
				.subresourceRange(range);
		VkDependencyInfo info = VkDependencyInfo.calloc(stack)
				.sType$Default()
				.pImageMemoryBarriers(barrier);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands, info);
	}

	@Unique
	private static boolean integer(GpuTexture texture) {
		return switch (texture.getFormat().componentType()) {
			case UINT_8, SINT_8, UINT_16, SINT_16, UINT_32, SINT_32 -> true;
			default -> false;
		};
	}
}
