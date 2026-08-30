package dev.vitrail.mixin;

import dev.vitrail.render.MipmapCommands;
import dev.vitrail.render.PassBarrier;
import dev.vitrail.render.PassImages;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Two encoder internals the public API does not expose.
 * <p>
 * Mip chains go through {@code vkCmdBlitImage}, which is what {@code glGenerateMipmap} is on this
 * backend: scaled copies on the same command buffer, not a render pass per level. A pass per level
 * was the only public path, and each one ends with a full memory barrier. Iris pays one driver
 * call. The blit loop is that call: transfer barriers between levels so each read sees the write
 * below it, then one barrier naming what a filled chain owes the rest of the frame.
 * <p>
 * Closing a pass still runs the encoder's full barrier. Our own labels start with {@code Vitrail}
 * and get a write-then-sample barrier on the colour and depth images that pass wrote, plus a
 * storage-only memory barrier for the volumes a pack's geometry {@code imageStore}s into. The rest
 * of the frame keeps the original wait. {@link PassBarrier} puts the original wait back on ours
 * too, and sends the mip chain back to a pass per level, for a machine where the narrow one is
 * suspected of a wrong image.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin implements MipmapCommands {

	@Shadow
	private VulkanRenderPass currentRenderPass;

	@Invoker("commandBuffer")
	abstract VkCommandBuffer vitrail$commandBuffer();

	@Unique
	private Supplier<String> vitrail$closingLabel;

	@Inject(method = "createRenderPass", at = @At("RETURN"), require = 1)
	private void vitrail$rememberImages(RenderPassDescriptor descriptor,
			CallbackInfoReturnable<RenderPassBackend> cir) {
		Supplier<String> label = descriptor.label();
		if (label != null && vitrail$ours(label)) {
			PassImages.remember(descriptor);
		}
	}

	@Inject(method = "submitRenderPass", at = @At("HEAD"), require = 1)
	private void vitrail$rememberLabel(CallbackInfo ci) {
		this.vitrail$closingLabel = this.currentRenderPass == null
				? null
				: this.currentRenderPass.getLabel();
	}

	/**
	 * The game ends every pass with a full memory barrier. Iris does not: it binds an FBO and
	 * draws. On MoltenVK that full barrier is a Metal wait, which is the extra queue-submit count
	 * on Apple Silicon. Our own passes need write-then-sample and write-then-write, and get a
	 * per-image barrier on the attachments they wrote rather than a wait over the whole of memory.
	 * <p>
	 * <strong>Write-then-write is half of it, and leaving it out is what an FBO gives for free.</strong>
	 * Under OpenGL two draws into the bound framebuffer land in the order they were issued, so Iris
	 * never has to say it; two Vulkan passes writing the same image are ordered by nothing at all.
	 * That is every target the chain turns over, and the emptying now rides the load-op of the pass
	 * that first attaches it rather than being a clear of its own, so a pass and the clear meant to
	 * precede it are two writes to one image with no dependency between them.
	 * <p>
	 * <strong>Both ends also name what a pack's compute programs move.</strong>
	 * A pass of ours no longer writes attachments and nothing else: a pack's geometry stores into
	 * its own volumes from the fragment stage, so a storage write is one of the things a closing
	 * pass leaves behind. And what reads next is no longer graphics alone, which is the half that
	 * the barrier standing beside the dispatch cannot cover: that one carries storage writes as
	 * its source, so it orders the stores of the shadow geometry against the floodfill, and
	 * nothing in it names the ATTACHMENT writes of the same pass. The pack's compute samples the
	 * shadow map through the same views the graphics passes bind, and shadowtex0 is a depth
	 * attachment we wrote.
	 */
	@Redirect(method = "submitRenderPass", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier("
					+ "Lorg/lwjgl/system/MemoryStack;)V"),
			require = 1)
	private void vitrail$afterPass(VulkanCommandEncoder self, MemoryStack stack) {
		Supplier<String> label = this.vitrail$closingLabel;
		this.vitrail$closingLabel = null;
		VkCommandBuffer commands = vitrail$commandBuffer();
		// The wide wait first, so that arming it takes every pass back to what the game does and
		// leaves nothing of the narrow one standing. {@link PassBarrier} carries why that switch
		// exists at all.
		if (label != null && vitrail$ours(label) && !PassBarrier.full()) {
			vitrail$framebufferBarrier(commands, stack);
		} else {
			if (label != null && vitrail$ours(label)) {
				PassImages.take();
			}

			VulkanCommandEncoder.memoryBarrier(commands, stack);
		}
	}

	@Unique
	private static boolean vitrail$ours(Supplier<String> label) {
		String name = label.get();
		return name != null && name.startsWith("Vitrail");
	}

	/**
	 * Iris binds an FBO and draws, with no barrier
	 * ({@code pipeline/IrisRenderingPipeline.java:1383-1388}). Closing a pass here is
	 * {@code vkCmdEndRendering} then a barrier ({@code VulkanCommandEncoder.submitRenderPass} at
	 * 324-334): dynamic rendering does not keep a framebuffer bound, so the next pass is ordered
	 * by nothing unless we say so. Cost to the image is none when this list of attachments is
	 * complete. Layouts stay GENERAL, which is how every game texture lives
	 * ({@code docs/internals/game-graphics-api.md}).
	 * <p>
	 * Storage writes stay a {@code VkMemoryBarrier2} of their own: the VMA volumes a pack
	 * {@code imageStore}s into are not named from this encoder. An empty attachment list falls
	 * back to the previous global wait rather than emitting an incomplete image list.
	 */
	@Unique
	private static void vitrail$framebufferBarrier(VkCommandBuffer commands, MemoryStack stack) {
		PassImages.Snapshot written = PassImages.take();
		int images = written.empty() ? 0 : vitrail$vulkanAttachments(written);
		if (images == 0) {
			vitrail$globalPassBarrier(commands, stack);

			return;
		}

		VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images, stack);
		int at = 0;
		for (GpuTextureView colour : written.colours()) {
			if (vitrail$vulkan(colour) != null) {
				vitrail$fillAttachment(barriers.get(at++), stack, colour);
			}
		}

		if (vitrail$vulkan(written.depth()) != null) {
			vitrail$fillAttachment(barriers.get(at), stack, written.depth());
		}

		VkDependencyInfo info = VkDependencyInfo.calloc(stack)
				.sType$Default()
				.pMemoryBarriers(vitrail$storageBarrier(stack))
				.pImageMemoryBarriers(barriers);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands, info);
	}

	/**
	 * The previous close-of-pass wait, kept for the road where the attachment list did not
	 * arrive. It names every access a pass of ours can leave behind, at the cost of covering all
	 * of device memory.
	 */
	@Unique
	private static void vitrail$globalPassBarrier(VkCommandBuffer commands, MemoryStack stack) {
		VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR)
				.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR)
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR)
				.dstAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR);
		VkDependencyInfo info = VkDependencyInfo.calloc(stack)
				.sType$Default()
				.pMemoryBarriers(barrier);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands, info);
	}

	@Unique
	private static VkMemoryBarrier2.Buffer vitrail$storageBarrier(MemoryStack stack) {
		return VkMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR)
				.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR)
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR)
				.dstAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR);
	}

	@Unique
	private static void vitrail$fillAttachment(VkImageMemoryBarrier2 barrier, MemoryStack stack,
			GpuTextureView view) {
		VulkanGpuTexture image = vitrail$vulkan(view);
		if (image == null) {
			return;
		}

		int aspect = VulkanConst.formatAspectMask(image.getFormat());
		boolean depth = (aspect & VK10.VK_IMAGE_ASPECT_DEPTH_BIT) != 0
				|| (aspect & VK10.VK_IMAGE_ASPECT_STENCIL_BIT) != 0;
		VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
				.aspectMask(aspect)
				.baseMipLevel(view.baseMipLevel())
				.levelCount(view.mipLevels())
				.baseArrayLayer(0)
				.layerCount(Math.max(1, image.getDepthOrLayers()));
		barrier.sType$Default()
				.srcStageMask(depth
						? KHRSynchronization2.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT_KHR
						: KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR)
				.srcAccessMask(depth
						? KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR
						: KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR)
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR
						| (depth
								? KHRSynchronization2.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT_KHR
										| KHRSynchronization2.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT_KHR
								: KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR))
				.dstAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR
						| (depth
								? KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT_KHR
										| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR
								: KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT_KHR
										| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR))
				.oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
				.newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
				.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
				.image(image.vkImage())
				.subresourceRange(range);
	}

	@Unique
	private static int vitrail$vulkanAttachments(PassImages.Snapshot written) {
		int count = 0;
		for (GpuTextureView colour : written.colours()) {
			if (vitrail$vulkan(colour) != null) {
				count++;
			}
		}

		if (vitrail$vulkan(written.depth()) != null) {
			count++;
		}

		return count;
	}

	@Unique
	private static VulkanGpuTexture vitrail$vulkan(GpuTextureView view) {
		return view != null && view.texture() instanceof VulkanGpuTexture image ? image : null;
	}

	@Override
	public boolean vitrail$generateMipmaps(GpuTexture texture) {
		// Refused while the wide wait is armed, so the caller goes back to a pass per level and
		// every one of those ends on the game's own barrier. The switch would otherwise leave the
		// mip chain on transfer barriers alone, and a reporter whose image is still wrong with it on
		// would clear the synchronisation for a road it never put back.
		if (PassBarrier.full() || this.currentRenderPass != null
				|| !(texture instanceof VulkanGpuTexture image)) {
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

			vitrail$chainTailBarrier(commands, stack);
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

	/**
	 * What the filled chain owes the rest of the frame, named instead of the whole of memory. The
	 * only writes standing unsynchronised at this point are the blits themselves: the pass that
	 * wrote the base has already closed on its own barrier, and that is also what ordered the
	 * first blit's read. What can touch the chain next is a sampled read from any shader stage, a
	 * pass writing the base again, or another transfer; the image is a colour target, so no depth
	 * stage ever meets it. The wide wait never reaches here: {@code vitrail$generateMipmaps}
	 * refuses while it is armed, and the caller then pays a pass per level, each closing on the
	 * game's own barrier.
	 */
	@Unique
	private static void vitrail$chainTailBarrier(VkCommandBuffer commands, MemoryStack stack) {
		VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack)
				.sType$Default()
				.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR)
				.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR)
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR)
				.dstAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR);
		VkDependencyInfo info = VkDependencyInfo.calloc(stack)
				.sType$Default()
				.pMemoryBarriers(barrier);
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
