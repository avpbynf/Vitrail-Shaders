package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout.Entry;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.render.GeometryStage;
import dev.vitrail.render.storage.StorageBuffers;
import dev.vitrail.render.storage.StorageImages;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Emits {@code VK_DESCRIPTOR_TYPE_STORAGE_IMAGE} for an {@code image.NAME} uniform, and
 * {@code VK_DESCRIPTOR_TYPE_STORAGE_BUFFER} for a {@code bufferObject} block.
 * <p>
 * The Java enum has no storage-image or storage-buffer arm, so the layout would otherwise write
 * a combined image sampler (type 1) for {@code voxel_img} and a uniform buffer (type 6) for
 * {@code blockDataBuffer}. Complementary writes both with {@code imageStore} / SSBO stores.
 */
@Mixin(VulkanBindGroupLayout.class)
public abstract class VulkanBindGroupLayoutMixin {

	@Unique
	private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();

	@WrapOperation(method = "create", require = 1,
			at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;"))
	private static Object vitrail$entry(List<?> entries, int index, Operation<Object> original) {
		Object entry = original.call(entries, index);
		if (entry instanceof Entry named) {
			CURRENT.set(named);
		}

		return entry;
	}

	@WrapOperation(method = "create", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;descriptorType(I)"
							+ "Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;"))
	private static VkDescriptorSetLayoutBinding vitrail$storageType(
			VkDescriptorSetLayoutBinding binding, int type, Operation<VkDescriptorSetLayoutBinding> original) {
		Entry entry = CURRENT.get();
		if (type == 1 && entry != null && (StorageImages.storageBinding(entry.name())
				|| CustomImages.storage(entry.name()))) {
			type = 3;
		}

		if (type == 6 && entry != null && StorageBuffers.named(entry.name())) {
			type = 7;
		}

		return original.call(binding, type);
	}

	/**
	 * Adds the geometry bit to the vertex and fragment the game writes, on the layouts of the
	 * pipelines that carry a geometry stage. A binding is only readable from the stages its flags
	 * name, and the pair the game writes names the two stages around the middle one and not the
	 * middle one itself, so the uniform iterationT's terrain geometry stage reads would be out of
	 * its reach.
	 * <p>
	 * Narrowed to those layouts rather than written on every one of the process: this method builds
	 * the layout of every pipeline the game and every other mod compiles, and a flag nothing reads
	 * is still a flag nobody asked for. The question is asked of the thread, which is where the
	 * stage in flight is held, and this call is the last of the compile that put it there.
	 */
	@WrapOperation(method = "create", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;stageFlags(I)"
							+ "Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;"))
	private static VkDescriptorSetLayoutBinding vitrail$geometryStage(
			VkDescriptorSetLayoutBinding binding, int flags,
			Operation<VkDescriptorSetLayoutBinding> original) {
		return original.call(binding,
				GeometryStage.buildingHere() ? flags | GeometryStage.STAGE_BIT : flags);
	}
}
