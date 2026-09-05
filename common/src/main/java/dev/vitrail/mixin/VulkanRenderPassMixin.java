package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import dev.vitrail.render.PushedDescriptor;
import dev.vitrail.render.ShadowCompare;
import dev.vitrail.render.StorageBuffers;
import dev.vitrail.render.StorageImages;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Pushes a storage-image descriptor, a 3D sampled view, and a storage-buffer descriptor for
 * names {@link StorageImages} and {@link StorageBuffers} hold, and the comparison sampler for a
 * shadow name the pipeline being drawn declared a comparison.
 * <p>
 * The game always writes a combined image sampler from a 2D {@code GpuTextureView}, and a
 * uniform buffer from a {@code GpuBufferSlice}. Complementary needs type 3 and a 3D view on
 * {@code voxel_img}, a 3D sampled view on {@code voxel_sampler}, and type 7 plus the VMA handle
 * on {@code blockDataBuffer}. The comparison is the same shape of gap: no {@code GpuSampler}
 * describes one, so {@link ShadowCompare} makes it in Vulkan's own terms and this walk puts its
 * handle under the names the pipeline registered when it was built.
 */
@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin {

	@Shadow
	protected VulkanRenderPipeline pipeline;

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;", ordinal = 0))
	private Object vitrail$entry(List<?> entries, int index, Operation<Object> original) {
		Object entry = original.call(entries, index);
		if (entry instanceof VulkanBindGroupLayout.Entry named) {
			PushedDescriptor.begin(named);
		}

		return entry;
	}

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkDescriptorImageInfo$Buffer;imageView(J)"
							+ "Lorg/lwjgl/vulkan/VkDescriptorImageInfo$Buffer;"))
	private VkDescriptorImageInfo.Buffer vitrail$view(VkDescriptorImageInfo.Buffer info, long view,
			Operation<VkDescriptorImageInfo.Buffer> original) {
		StorageImages.Bound bound = PushedDescriptor.current().image();
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
		PushedDescriptor pushed = PushedDescriptor.current();
		StorageImages.Bound bound = pushed.image();
		if (bound != null && bound.storage()) {
			sampler = 0L;
		} else if (ShadowCompare.noted()) {
			// Behind the one flag: until the first pack that compares is loaded, every pass of the
			// game's own pays a volatile read here and nothing else. Once one has been, the flag
			// stays up for the session and the per-name lookup is the price of having the road.
			VulkanBindGroupLayout.Entry entry = pushed.entry();
			if (entry != null && this.pipeline != null
					&& ShadowCompare.compared(this.pipeline.info(), entry.name())) {
				sampler = ShadowCompare.sampler(this.pipeline.device());
			}
		}

		return original.call(info, sampler);
	}

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkDescriptorBufferInfo$Buffer;buffer(J)"
							+ "Lorg/lwjgl/vulkan/VkDescriptorBufferInfo$Buffer;"))
	private VkDescriptorBufferInfo.Buffer vitrail$buffer(VkDescriptorBufferInfo.Buffer info,
			long buffer, Operation<VkDescriptorBufferInfo.Buffer> original) {
		StorageBuffers.Bound bound = PushedDescriptor.current().buffer();
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
		StorageBuffers.Bound bound = PushedDescriptor.current().buffer();
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
		PushedDescriptor pushed = PushedDescriptor.current();
		StorageImages.Bound image = pushed.image();
		if (type == 1 && image != null && image.storage()) {
			type = 3;
		}

		if (type == 6 && pushed.buffer() != null) {
			type = 7;
		}

		return original.call(set, type);
	}
}
