package dev.vitrail.mixin;

import dev.vitrail.render.MipmapCommands;
import dev.vitrail.render.timing.PassBarrier;
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
import org.lwjgl.vulkan.VkViewport;
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
 * was the only public path, each one ending on a full memory barrier, and the game refuses one on a
 * level whose shorter side shifts to nought, which every chain that runs to one texel on its longer
 * side has. Iris pays one driver call. The blit loop is that call: transfer barriers between
 * levels so each read sees the write below it, then one barrier naming what a filled chain owes
 * the rest of the frame.
 * <p>
 * Closing a pass still runs the encoder's full barrier. Our own labels start with {@code Vitrail}
 * and get a write-then-sample barrier instead, the rest of the frame keeping the original wait.
 * {@link PassBarrier} puts the original wait back on ours too, and between the blits of a chain in
 * place of the transfer barriers, for a machine where the narrow ones are suspected of a wrong
 * image.
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
	 * on Apple Silicon. Our own passes need write-then-sample and write-then-write, and get a
	 * dependency naming those two rather than the whole of memory.
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
		if (label != null && !PassBarrier.full() && vitrail$ours(label)) {
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
				// The shader stages are here for the image stores of a pack's own geometry, which
				// land in the volumes its compute reads and its gbuffers sample.
				.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR)
				.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT_KHR
						| KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR)
				// The vertex stage samples too, and leaving it out is not theoretical: a sampler
				// declared in a pack's vertex unit is kept and bound like any other
				// (ProgramTranslator collects the samplers of every stage), so a program whose
				// vertex shader reads a target the pass before it wrote would race the write.
				.dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR
						| KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT_KHR)
				// The writes sit here beside the reads, and each of the three is reached every frame:
				// a colour target the next pass writes, or that the emptying writes through its
				// load-op; the depth image the next geometry pass tests and writes, and that the
				// shadow map's own emptying clears; and the mip blit filling the levels of a target
				// whose base the pass that just closed wrote. Named as reads alone, all three were
				// write-after-write with nothing between them, which a driver is free to run in
				// either order.
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
		// The wide wait, when armed, stands where each transfer barrier would: a reporter whose
		// image is still wrong with it on has then cleared the synchronisation of this road too,
		// rather than of the passes alone.
		boolean wide = PassBarrier.full();

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

				if (wide) {
					VulkanCommandEncoder.memoryBarrier(commands, stack);
				} else if (level + 1 < levels) {
					vitrail$transferBarrier(commands, stack, vkImage, aspect, level);
				}
			}

			if (!wide) {
				vitrail$chainTailBarrier(commands, stack);
			}
		}

		return true;
	}

	/**
	 * The same call the pass constructor makes, with the extent said rather than the one shifted
	 * off the attachment. Dynamic state on the pass's own command buffer, so it holds until the
	 * next pass opens and sets its own.
	 */
	@Override
	public boolean vitrail$viewport(int width, int height) {
		if (this.currentRenderPass == null || width <= 0 || height <= 0) {
			return false;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
					.x(0.0F)
					.y(0.0F)
					.width(width)
					.height(height)
					.minDepth(0.0F)
					.maxDepth(1.0F);
			VK12.vkCmdSetViewport(vitrail$commandBuffer(), 0, viewport);
		}

		return true;
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
	 * stage ever meets it. The wide wait never reaches here: while it is armed, the game's own
	 * barrier already stands after the last blit.
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
