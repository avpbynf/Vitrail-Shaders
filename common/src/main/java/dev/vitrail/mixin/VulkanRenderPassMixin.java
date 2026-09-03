package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import dev.vitrail.render.SettledDescriptors;
import dev.vitrail.render.ShadowCompare;
import dev.vitrail.render.StorageBuffers;
import dev.vitrail.render.StorageImages;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * <p>
 * After the first push of a geometry program into a pass, {@link SettledDescriptors} shrinks the
 * write list to uniforms plus sampled names that follow the draw. The substitutions below still
 * run on every name that is written; a name left out keeps what the last full push put there,
 * which holds only as long as no other pipeline is bound into the pass, so every
 * {@code setPipeline} is reported and a foreign one puts the next push back to its full width.
 */
@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin {

	@Unique
	private static final ThreadLocal<VulkanBindGroupLayout.Entry> CURRENT = new ThreadLocal<>();

	@Shadow
	protected VulkanRenderPipeline pipeline;

	@Inject(method = "setPipeline", at = @At("TAIL"), require = 1)
	private void vitrail$pipelineBound(RenderPipeline bound, CallbackInfo callback) {
		SettledDescriptors.bound(bound);
	}

	@WrapOperation(method = "pushDescriptors", require = 2,
			at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
	private int vitrail$size(List<?> entries, Operation<Integer> original) {
		return SettledDescriptors.size(entries, original.call(entries), info());
	}

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;", ordinal = 0))
	private Object vitrail$entry(List<?> entries, int index, Operation<Object> original) {
		Object entry = original.call(entries, SettledDescriptors.index(index, info()));
		if (entry instanceof VulkanBindGroupLayout.Entry named) {
			CURRENT.set(named);
		}

		return entry;
	}

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkWriteDescriptorSet;dstBinding(I)"
							+ "Lorg/lwjgl/vulkan/VkWriteDescriptorSet;"))
	private VkWriteDescriptorSet vitrail$binding(VkWriteDescriptorSet set, int binding,
			Operation<VkWriteDescriptorSet> original) {
		return original.call(set, SettledDescriptors.index(binding, info()));
	}

	@WrapOperation(method = "pushDescriptors", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRPushDescriptor;vkCmdPushDescriptorSetKHR("
							+ "Lorg/lwjgl/vulkan/VkCommandBuffer;IJI"
							+ "Lorg/lwjgl/vulkan/VkWriteDescriptorSet$Buffer;)V"))
	private void vitrail$pushed(VkCommandBuffer commands, int bindPoint, long layout, int set,
			VkWriteDescriptorSet.Buffer writes, Operation<Void> original) {
		original.call(commands, bindPoint, layout, set, writes);
		SettledDescriptors.afterPush(info());
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
		} else if (ShadowCompare.noted()) {
			// Behind the one flag: until the first pack that compares is loaded, every pass of the
			// game's own pays a volatile read here and nothing else. Once one has been, the flag
			// stays up for the session and the per-name lookup is the price of having the road.
			VulkanBindGroupLayout.Entry entry = CURRENT.get();
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
	private RenderPipeline info() {
		return this.pipeline == null ? null : this.pipeline.info();
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
