package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import dev.vitrail.render.BufferBlending;
import dev.vitrail.Vitrail;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Turns on the device features the packs need and the game never asks for.
 * <p>
 * The storage images first. Complementary writes {@code voxel_img} from the shadow vertex shader
 * ({@code UpdateVoxelMap} under {@code SHADOW && VERTEX_SHADER}). Vulkan ignores those stores
 * unless {@code vertexPipelineStoresAndAtomics} is enabled, and {@code r16ui} / {@code rgba16f}
 * are not core storage formats unless {@code shaderStorageImageExtendedFormats} is on. The game's
 * {@code VulkanBackend} enables neither: its required set is draw-indirect, anisotropy and
 * dynamic rendering. Without this, floodfill runs on a volume that stays empty and the pack's
 * coloured lamps never light.
 * <p>
 * And {@code independentBlend}, which is the device's half of {@code PER_BUFFER_BLENDING}. A pass
 * writing several targets under a {@code blend.<program>.<buffer>} directive needs each attachment
 * to carry its own blend state, and without the feature Vulkan requires every element of
 * {@code pAttachments} to be identical. Answered on to {@link BufferBlending}, which is what the
 * pipeline, the refusal and the symbol all read. The other half is the game's own pipeline builder,
 * which refuses two colour targets naming different functions whatever the device can do, and
 * {@code RenderPipelineBuilderMixin} lifts on this same answer.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {

	@Unique
	private static final VulkanFeature VERTEX_STORES = new VulkanFeature(
			VulkanBackend.VK10_FEATURES_STRUCT, "vertexPipelineStoresAndAtomics",
			VkPhysicalDeviceFeatures.VERTEXPIPELINESTORESANDATOMICS);

	@Unique
	private static final VulkanFeature FRAGMENT_STORES = new VulkanFeature(
			VulkanBackend.VK10_FEATURES_STRUCT, "fragmentStoresAndAtomics",
			VkPhysicalDeviceFeatures.FRAGMENTSTORESANDATOMICS);

	@Unique
	private static final VulkanFeature EXTENDED_FORMATS = new VulkanFeature(
			VulkanBackend.VK10_FEATURES_STRUCT, "shaderStorageImageExtendedFormats",
			VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEEXTENDEDFORMATS);

	@Unique
	private static final VulkanFeature WRITE_WITHOUT_FORMAT = new VulkanFeature(
			VulkanBackend.VK10_FEATURES_STRUCT, "shaderStorageImageWriteWithoutFormat",
			VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT);

	@Unique
	private static final VulkanFeature INDEPENDENT_BLEND = new VulkanFeature(
			VulkanBackend.VK10_FEATURES_STRUCT, "independentBlend",
			VkPhysicalDeviceFeatures.INDEPENDENTBLEND);

	@Unique
	private static final String VOXELS = "voxel lighting will not write";

	@Unique
	private static final String PER_BUFFER = "a pack requiring PER_BUFFER_BLENDING is refused and "
			+ "every other one keeps a single blend function for all the targets a pass writes";

	@WrapOperation(method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;"
			+ "Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)"
			+ "Lcom/mojang/blaze3d/systems/GpuDevice;", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice("
							+ "Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;"
							+ "Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"))
	private VkDevice vitrail$deviceFeatures(Collection<String> extensions,
			VulkanPhysicalDevice physical, Set<VulkanFeature> features,
			Operation<VkDevice> original) {
		List<String> enabled = new ArrayList<>();
		enable(physical, features, VERTEX_STORES, enabled, VOXELS);
		enable(physical, features, FRAGMENT_STORES, enabled, VOXELS);
		enable(physical, features, EXTENDED_FORMATS, enabled, VOXELS);
		enable(physical, features, WRITE_WITHOUT_FORMAT, enabled, VOXELS);
		BufferBlending.serve(enable(physical, features, INDEPENDENT_BLEND, enabled, PER_BUFFER));
		if (!enabled.isEmpty()) {
			Vitrail.logger().info("Vulkan device features: {}", String.join(", ", enabled));
		}

		return original.call(extensions, physical, features);
	}

	@Unique
	private static boolean enable(VulkanPhysicalDevice physical, Set<VulkanFeature> features,
			VulkanFeature feature, List<String> enabled, String cost) {
		if (!supported(physical, feature)) {
			Vitrail.logger().warn("Vulkan device does not support {}, so {}", feature.name(), cost);
			return false;
		}

		features.add(feature);
		enabled.add(feature.name());

		return true;
	}

	@Unique
	private static boolean supported(VulkanPhysicalDevice physical, VulkanFeature feature) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceFeatures2 queried = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
			feature.struct().findOrCreateStructInPNextChain(queried, stack);
			VK12.vkGetPhysicalDeviceFeatures2(physical.vkPhysicalDevice(), queried);
			return feature.get(queried);
		}
	}
}
