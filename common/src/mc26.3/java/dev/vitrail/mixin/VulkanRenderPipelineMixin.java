package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.render.WideSamplerSets;
import dev.vitrail.render.storage.StorageBuffers;
import dev.vitrail.render.storage.StorageImages;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.LongBuffer;
import java.util.List;

/**
 * Writes the descriptor set layout of a pipeline the way the pack's shaders declare it.
 * <p>
 * The 26.3 half of what {@code VulkanBindGroupLayoutMixin} did on 26.2, where the layout was built
 * by a class of its own: 26.3 builds it inside {@code VulkanRenderPipeline.compile}, one binding per
 * uniform the pipeline declares, and knows three kinds. The engine declares a storage buffer as a
 * uniform buffer and a storage image as a sampled image, so here each binding whose name the engine
 * serves as storage is written as the storage kind it is. And a layout that MoltenVK could not push,
 * a stage reading past its pushed sampler limit, loses the push flag and is recorded for
 * {@code VulkanRenderPassMixin}, as on 26.2.
 */
@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanRenderPipelineMixin {

	/** The uniform whose binding the loop is writing, on the thread compiling this pipeline. */
	@Unique
	private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

	@WrapOperation(method = "compile", require = 1,
			at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;",
					ordinal = 0))
	private static Object vitrail$uniform(List<?> uniforms, int index, Operation<Object> original) {
		Object uniform = original.call(uniforms, index);
		if (uniform instanceof BindGroupLayout.UniformDescription described) {
			CURRENT.set(described.name());
		}

		return uniform;
	}

	@WrapOperation(method = "compile", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;descriptorType(I)"
							+ "Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;"))
	private static VkDescriptorSetLayoutBinding vitrail$storageType(
			VkDescriptorSetLayoutBinding binding, int type,
			Operation<VkDescriptorSetLayoutBinding> original) {
		String name = CURRENT.get();
		if (type == VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER && name != null
				&& (StorageImages.storageBinding(name) || CustomImages.storage(name)
						|| TargetName.imageIndex(name).isPresent())) {
			type = VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
		}

		if (type == VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER && name != null
				&& StorageBuffers.named(name)) {
			type = VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
		}

		return original.call(binding, type);
	}

	/**
	 * Takes the push flag off a layout MoltenVK could not push, and records which road the handle
	 * that comes back takes. At the driver call, so the count is taken over the types the layout
	 * really carries once the rewrite above has run.
	 */
	@WrapOperation(method = "compile", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VK12;vkCreateDescriptorSetLayout("
							+ "Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkDescriptorSetLayoutCreateInfo;"
							+ "Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private static int vitrail$allocatedSets(VkDevice device, VkDescriptorSetLayoutCreateInfo info,
			VkAllocationCallbacks allocator, LongBuffer handle, Operation<Integer> original,
			@Local(argsOnly = true) BackendRenderPipeline.CreateInfo pipeline) {
		CURRENT.remove();
		boolean allocated = WideSamplerSets.dropPush(info, pipeline.name());
		int result = original.call(device, info, allocator, handle);
		if (result == VK12.VK_SUCCESS) {
			WideSamplerSets.created(handle.get(0), allocated);
		}

		return result;
	}
}
