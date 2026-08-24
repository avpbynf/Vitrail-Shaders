package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.vitrail.render.StorageBuffers;
import dev.vitrail.render.StorageImages;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Pushes a storage-image descriptor, a 3D sampled view, and a storage-buffer descriptor for
 * names {@link StorageImages} and {@link StorageBuffers} hold.
 * <p>
 * The game always writes a combined image sampler from a 2D {@code GpuTextureView}, and a
 * uniform buffer from a {@code GpuBufferSlice}. Complementary needs type 3 and a 3D view on
 * {@code voxel_img}, a 3D sampled view on {@code voxel_sampler}, and type 7 plus the VMA handle
 * on {@code blockDataBuffer}.
 */
@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin {

	@Unique
	private static final ThreadLocal<VulkanBindGroupLayout.Entry> CURRENT = new ThreadLocal<>();

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;", ordinal = 0))
	private Object vitrail$entry(List<?> entries, int index, Operation<Object> original) {
		Object entry = original.call(entries, index);
		if (entry instanceof VulkanBindGroupLayout.Entry named) {
			CURRENT.set(named);
		}

		return entry;
	}

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkDescriptorImageInfo$Buffer;imageView(J)"
							+ "Lorg/lwjgl/vulkan/VkDescriptorImageInfo$Buffer;"))
	private VkDescriptorImageInfo.Buffer vitrail$view(VkDescriptorImageInfo.Buffer info, long view,
			Operation<VkDescriptorImageInfo.Buffer> original) {
		StorageImages.Bound bound = currentBound();
		if (bound != null) {
			view = bound.view();
		}

		return original.call(info, view);
	}

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkDescriptorImageInfo$Buffer;sampler(J)"
							+ "Lorg/lwjgl/vulkan/VkDescriptorImageInfo$Buffer;"))
	private VkDescriptorImageInfo.Buffer vitrail$sampler(VkDescriptorImageInfo.Buffer info,
			long sampler, Operation<VkDescriptorImageInfo.Buffer> original) {
		StorageImages.Bound bound = currentBound();
		if (bound != null && bound.storage()) {
			sampler = 0L;
		}

		return original.call(info, sampler);
	}

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkDescriptorBufferInfo$Buffer;buffer(J)"
							+ "Lorg/lwjgl/vulkan/VkDescriptorBufferInfo$Buffer;"))
	private VkDescriptorBufferInfo.Buffer vitrail$buffer(VkDescriptorBufferInfo.Buffer info,
			long buffer, Operation<VkDescriptorBufferInfo.Buffer> original) {
		StorageBuffers.Bound bound = currentBuffer();
		if (bound != null) {
			buffer = bound.buffer();
		}

		return original.call(info, buffer);
	}

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkDescriptorBufferInfo$Buffer;range(J)"
							+ "Lorg/lwjgl/vulkan/VkDescriptorBufferInfo$Buffer;"))
	private VkDescriptorBufferInfo.Buffer vitrail$range(VkDescriptorBufferInfo.Buffer info,
			long range, Operation<VkDescriptorBufferInfo.Buffer> original) {
		StorageBuffers.Bound bound = currentBuffer();
		if (bound != null) {
			range = bound.range();
		}

		return original.call(info, range);
	}

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkWriteDescriptorSet;descriptorType(I)"
							+ "Lorg/lwjgl/vulkan/VkWriteDescriptorSet;"))
	private VkWriteDescriptorSet vitrail$type(VkWriteDescriptorSet set, int type,
			Operation<VkWriteDescriptorSet> original) {
		StorageImages.Bound image = currentBound();
		if (type == 1 && image != null && image.storage()) {
			type = 3;
		}

		if (type == 6 && currentBuffer() != null) {
			type = 7;
		}

		return original.call(set, type);
	}

	@Unique
	private static StorageImages.Bound currentBound() {
		VulkanBindGroupLayout.Entry entry = CURRENT.get();
		return entry == null ? null : StorageImages.bound(entry.name());
	}

	@Unique
	private static StorageBuffers.Bound currentBuffer() {
		VulkanBindGroupLayout.Entry entry = CURRENT.get();
		return entry == null ? null : StorageBuffers.bound(entry.name());
	}
}
