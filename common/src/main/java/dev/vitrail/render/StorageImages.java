package dev.vitrail.render;

import dev.vitrail.mixin.CommandEncoderAccessor;
import dev.vitrail.mixin.GpuDeviceAccessor;
import dev.vitrail.mixin.VulkanCommandEncoderAccessor;
import dev.vitrail.pack.target.TargetFormat;
import dev.vitrail.pack.texture.ImageInformation;
import dev.vitrail.pack.texture.PackTexture;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The storage images a pack declared with {@code image.NAME}, allocated on the Vulkan device.
 * <p>
 * The Java texture facade has no storage bit and no three-dimensional texture, so these go through
 * VMA the way the compute probe did: {@code VulkanConst.textureUsageToVk} never sets
 * {@code VK_IMAGE_USAGE_STORAGE_BIT}. Mixins on the game's bind-group walk swap the 3D view in
 * for both {@code imageStore} names and the sampler hanging off the same directive.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris GlImage, LGPL-3.0</a>
 */
public final class StorageImages implements AutoCloseable {

	private static volatile StorageImages current = none();

	private final ImageInformation.Reading declared;
	private final List<Allocated> allocated = new ArrayList<>();
	private int lastWidth;
	private int lastHeight;
	private boolean laidOut;

	StorageImages(ImageInformation.Reading declared) {
		this.declared = declared;
	}

	static StorageImages none() {
		return new StorageImages(ImageInformation.Reading.empty());
	}

	/**
	 * The image currently allocated for this name, whether the pack wrote it as the image uniform
	 * or as the sampler on the same {@code image.} line. Mixins look it up while pushing
	 * descriptors.
	 */
	public static Bound bound(String name) {
		return current.lookup(name);
	}

	public static boolean storageBinding(String name) {
		Bound bound = bound(name);
		return bound != null && bound.storage();
	}

	void install() {
		current = this;
	}

	private Bound lookup(String name) {
		for (Allocated image : this.allocated) {
			if (image.declared.name().equals(name)) {
				return new Bound(image.view, true, image.declared.internalFormat().used().integer());
			}

			if (image.declared.sampler().filter(name::equals).isPresent()) {
				return new Bound(image.view, false, image.declared.internalFormat().used().integer());
			}
		}

		return null;
	}

	/**
	 * One bound view. {@code storage} is the image uniform ({@code voxel_img}); a sampler name
	 * hanging off the same directive is sampled, not stored.
	 */
	public record Bound(long view, boolean storage, boolean integer) {
	}

	/**
	 * Allocates every absolute image once, and rebuilds the relative ones when the screen moves.
	 * Failures are named and skipped: one volume is not worth taking the pack down.
	 */
	void ensure(int screenWidth, int screenHeight) {
		install();
		if (this.declared.images().isEmpty()) {
			return;
		}

		boolean first = this.allocated.isEmpty();
		boolean resized = screenWidth != this.lastWidth || screenHeight != this.lastHeight;
		if (!first && !resized) {
			return;
		}

		VulkanDevice vulkan = vulkan();
		if (vulkan == null) {
			return;
		}

		if (first) {
			for (ImageInformation image : this.declared.images()) {
				if (image.relative()) {
					continue;
				}

				try {
					this.allocated.add(Allocated.create(vulkan, image, image.width(), image.height(),
							Math.max(image.depth(), 1)));
					Vitrail.logger().info("storage image {}", image.describe());
				} catch (RuntimeException e) {
					Vitrail.logger().warn("storage image {} could not be allocated: {}",
							image.describe(), e.toString());
				}
			}
		}

		if (first || resized) {
			List<Allocated> kept = new ArrayList<>();
			for (Allocated image : this.allocated) {
				if (image.relative) {
					image.destroy(vulkan);
					this.laidOut = false;
				} else {
					kept.add(image);
				}
			}

			this.allocated.clear();
			this.allocated.addAll(kept);
			for (ImageInformation image : this.declared.images()) {
				if (!image.relative()) {
					continue;
				}

				int width = Math.max(1, (int) (screenWidth * image.relativeWidth()));
				int height = Math.max(1, (int) (screenHeight * image.relativeHeight()));
				try {
					this.allocated.add(Allocated.create(vulkan, image, width, height, 1));
					Vitrail.logger().info("storage image {} at {}x{}", image.describe(), width,
							height);
				} catch (RuntimeException e) {
					Vitrail.logger().warn("storage image {} could not be allocated: {}",
							image.describe(), e.toString());
				}
			}
		}

		this.lastWidth = screenWidth;
		this.lastHeight = screenHeight;
		layoutIfNeeded();
	}

	/**
	 * Empties every image the pack asked to clear, which Complementary's voxel volume is. Called
	 * at the top of the shadow stage, before geometry writes, matching Iris clearing custom images
	 * before the shadow map is drawn.
	 */
	void clearMarked(CommandEncoder encoder) {
		GpuRecording.endPass(encoder);
		VkCommandBuffer commands = commands(encoder);
		if (commands == null) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			// The volume being emptied was sampled by the previous frame's gbuffers and stored by
			// the previous dispatch, and nothing else orders those against a transfer write.
			GpuRecording.beforeTransfer(commands, stack);
			for (Allocated image : this.allocated) {
				if (!image.declared.clear()) {
					continue;
				}

				clearImage(commands, stack, image);
			}

			GpuRecording.afterTransfer(commands, stack);
		}
	}

	private void layoutIfNeeded() {
		if (this.laidOut || this.allocated.isEmpty()) {
			return;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}

		CommandEncoder encoder = device.createCommandEncoder();
		GpuRecording.endPass(encoder);
		VkCommandBuffer commands = commands(encoder);
		if (commands == null) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (Allocated image : this.allocated) {
				// Only the images created since the last pass here. An UNDEFINED transition
				// DISCARDS contents, so re-running it over a surviving absolute volume on a
				// window resize would throw away the floodfill the frames carry forward.
				if (image.laidOut) {
					continue;
				}

				image.laidOut = true;
				VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(1, stack)
						.sType$Default();
				VkImageMemoryBarrier barrier = barriers.get(0);
				barrier.oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
				barrier.newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
				barrier.srcAccessMask(0);
				barrier.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT
						| VK12.VK_ACCESS_SHADER_WRITE_BIT
						| VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
				barrier.srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
				barrier.dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
				barrier.image(image.image);
				barrier.subresourceRange().set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
				VK12.vkCmdPipelineBarrier(commands, VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
						VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barriers);
			}

			// Only the images the pack marked clear. Complementary's two floodfill volumes are
			// 500 MiB each at Ultra: zeroing them here with the 773 MiB SSBO fill on the same
			// command buffer is what killed the device two seconds later (Windows TDR). Voxel
			// is clear=true and is emptied each shadow frame by clearMarked. Floodfill is
			// written by shadowcomp on this same first dispatch.
		}

		this.laidOut = true;
	}

	private static void clearImage(VkCommandBuffer commands, MemoryStack stack, Allocated image) {
		VkClearColorValue colour = VkClearColorValue.calloc(stack);
		if (image.declared.internalFormat().used().integer()) {
			IntBuffer ints = colour.int32();
			ints.put(0, 0).put(1, 0).put(2, 0).put(3, 0);
		} else {
			colour.float32().put(0, 0.0F).put(1, 0.0F).put(2, 0.0F).put(3, 0.0F);
		}

		VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
		range.set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
		VK12.vkCmdClearColorImage(commands, image.image, VK12.VK_IMAGE_LAYOUT_GENERAL, colour, range);
	}

	private static VkCommandBuffer commands(CommandEncoder encoder) {
		return ((CommandEncoderAccessor) encoder).vitrail$backend() instanceof VulkanCommandEncoder vulkan
				? ((VulkanCommandEncoderAccessor) vulkan).vitrail$commandBuffer()
				: null;
	}

	int count() {
		return this.allocated.size();
	}

	@Override
	public void close() {
		if (current == this) {
			current = none();
		}

		VulkanDevice vulkan = vulkan();
		if (vulkan != null) {
			this.allocated.forEach(image -> image.destroy(vulkan));
		}

		this.allocated.clear();
		this.lastWidth = 0;
		this.lastHeight = 0;
		this.laidOut = false;
	}

	private static VulkanDevice vulkan() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return null;
		}

		GpuDeviceBackend backend = ((GpuDeviceAccessor) device).vitrail$backend();
		return backend instanceof VulkanDevice vulkan ? vulkan : null;
	}

	/**
	 * One VMA image. The view is what a storage binding and a sampler will both name, once those
	 * roads exist.
	 */
	private static final class Allocated {

		private final ImageInformation declared;
		private final boolean relative;
		private long image;
		private long allocation;
		private long view;

		/** Whether the one UNDEFINED-to-GENERAL transition of this image's life has been recorded. */
		private boolean laidOut;

		private Allocated(ImageInformation declared, boolean relative, long image, long allocation,
				long view) {
			this.declared = declared;
			this.relative = relative;
			this.image = image;
			this.allocation = allocation;
			this.view = view;
		}

		private static Allocated create(VulkanDevice vulkan, ImageInformation declared, int width,
				int height, int depth) {
			int vkFormat = vkFormat(declared.internalFormat().used());
			int type = imageType(declared.shape());
			int viewType = viewType(declared.shape());
			int extentHeight = Math.max(height, 1);
			int extentDepth = Math.max(depth, 1);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default();
				imageInfo.imageType(type);
				imageInfo.extent().set(Math.max(width, 1), extentHeight, extentDepth);
				imageInfo.mipLevels(1);
				imageInfo.arrayLayers(1);
				imageInfo.format(vkFormat);
				imageInfo.tiling(VK12.VK_IMAGE_TILING_OPTIMAL);
				imageInfo.initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
				imageInfo.usage(VK12.VK_IMAGE_USAGE_STORAGE_BIT
						| VK12.VK_IMAGE_USAGE_SAMPLED_BIT
						| VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
						| VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT);
				imageInfo.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
				imageInfo.samples(VK12.VK_SAMPLE_COUNT_1_BIT);
				VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack);
				allocationInfo.usage(8);
				LongBuffer imagePtr = stack.callocLong(1);
				PointerBuffer allocationPtr = stack.callocPointer(1);
				VulkanUtils.crashIfFailure(vulkan,
						Vma.vmaCreateImage(vulkan.vma(), imageInfo, allocationInfo, imagePtr,
								allocationPtr, null),
						"storage image " + declared.name());
				long image = imagePtr.get(0);
				long allocation = allocationPtr.get(0);

				VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
				viewInfo.image(image);
				viewInfo.viewType(viewType);
				viewInfo.format(vkFormat);
				viewInfo.subresourceRange().set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
				LongBuffer viewPtr = stack.callocLong(1);
				try {
					VulkanUtils.crashIfFailure(vulkan,
							VK12.vkCreateImageView(vulkan.vkDevice(), viewInfo, null, viewPtr),
							"storage image view " + declared.name());
				} catch (RuntimeException e) {
					Vma.vmaDestroyImage(vulkan.vma(), image, allocation);
					throw e;
				}

				return new Allocated(declared, declared.relative(), image, allocation, viewPtr.get(0));
			}
		}

		/**
		 * Frees the handles through the game's deferred queue, never inline: up to two frames are
		 * still in flight with descriptors naming this view, and freeing under them is the device
		 * loss a settings change to a bigger volume turned from latent into certain. Same rule as
		 * {@code StalePipelines}: destruction has no safe instant in a running session, only a
		 * deferred one.
		 */
		private void destroy(VulkanDevice vulkan) {
			long view = this.view;
			long image = this.image;
			long allocation = this.allocation;
			this.view = 0L;
			this.image = 0L;
			this.allocation = 0L;
			GpuRecording.destroyLater(() -> {
				if (view != 0L) {
					VK12.vkDestroyImageView(vulkan.vkDevice(), view, null);
				}

				if (image != 0L) {
					Vma.vmaDestroyImage(vulkan.vma(), image, allocation);
				}
			});
		}
	}

	private static int imageType(PackTexture.Shape shape) {
		return switch (shape) {
			case TEXTURE_1D -> VK12.VK_IMAGE_TYPE_1D;
			case TEXTURE_3D -> VK12.VK_IMAGE_TYPE_3D;
			case TEXTURE_2D, TEXTURE_RECTANGLE -> VK12.VK_IMAGE_TYPE_2D;
		};
	}

	private static int viewType(PackTexture.Shape shape) {
		return switch (shape) {
			case TEXTURE_1D -> VK12.VK_IMAGE_VIEW_TYPE_1D;
			case TEXTURE_3D -> VK12.VK_IMAGE_VIEW_TYPE_3D;
			case TEXTURE_2D, TEXTURE_RECTANGLE -> VK12.VK_IMAGE_VIEW_TYPE_2D;
		};
	}

	private static int vkFormat(TargetFormat format) {
		return switch (format) {
			case R8_UNORM -> VK12.VK_FORMAT_R8_UNORM;
			case R8_SNORM -> VK12.VK_FORMAT_R8_SNORM;
			case RG8_UNORM -> VK12.VK_FORMAT_R8G8_UNORM;
			case RG8_SNORM -> VK12.VK_FORMAT_R8G8_SNORM;
			case RGBA8_UNORM -> VK12.VK_FORMAT_R8G8B8A8_UNORM;
			case RGBA8_SNORM -> VK12.VK_FORMAT_R8G8B8A8_SNORM;
			case R16_UNORM -> VK12.VK_FORMAT_R16_UNORM;
			case R16_SNORM -> VK12.VK_FORMAT_R16_SNORM;
			case RG16_UNORM -> VK12.VK_FORMAT_R16G16_UNORM;
			case RG16_SNORM -> VK12.VK_FORMAT_R16G16_SNORM;
			case RGBA16_UNORM -> VK12.VK_FORMAT_R16G16B16A16_UNORM;
			case RGBA16_SNORM -> VK12.VK_FORMAT_R16G16B16A16_SNORM;
			case R8_UINT -> VK12.VK_FORMAT_R8_UINT;
			case R8_SINT -> VK12.VK_FORMAT_R8_SINT;
			case RG8_UINT -> VK12.VK_FORMAT_R8G8_UINT;
			case RG8_SINT -> VK12.VK_FORMAT_R8G8_SINT;
			case RGBA8_UINT -> VK12.VK_FORMAT_R8G8B8A8_UINT;
			case RGBA8_SINT -> VK12.VK_FORMAT_R8G8B8A8_SINT;
			case R16_UINT -> VK12.VK_FORMAT_R16_UINT;
			case R16_SINT -> VK12.VK_FORMAT_R16_SINT;
			case RG16_UINT -> VK12.VK_FORMAT_R16G16_UINT;
			case RG16_SINT -> VK12.VK_FORMAT_R16G16_SINT;
			case RGBA16_UINT -> VK12.VK_FORMAT_R16G16B16A16_UINT;
			case RGBA16_SINT -> VK12.VK_FORMAT_R16G16B16A16_SINT;
			case R32_UINT -> VK12.VK_FORMAT_R32_UINT;
			case R32_SINT -> VK12.VK_FORMAT_R32_SINT;
			case RG32_UINT -> VK12.VK_FORMAT_R32G32_UINT;
			case RG32_SINT -> VK12.VK_FORMAT_R32G32_SINT;
			case RGBA32_UINT -> VK12.VK_FORMAT_R32G32B32A32_UINT;
			case RGBA32_SINT -> VK12.VK_FORMAT_R32G32B32A32_SINT;
			case R16_FLOAT -> VK12.VK_FORMAT_R16_SFLOAT;
			case RG16_FLOAT -> VK12.VK_FORMAT_R16G16_SFLOAT;
			case RGBA16_FLOAT -> VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
			case R32_FLOAT -> VK12.VK_FORMAT_R32_SFLOAT;
			case RG32_FLOAT -> VK12.VK_FORMAT_R32G32_SFLOAT;
			case RGBA32_FLOAT -> VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
			case RGB10A2_UNORM -> VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32;
			case RGB10A2_UINT -> VK12.VK_FORMAT_A2B10G10R10_UINT_PACK32;
			case RG11B10_FLOAT -> VK12.VK_FORMAT_B10G11R11_UFLOAT_PACK32;
		};
	}
}
