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
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
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
 * {@code VulkanBackend} enables neither. Without this, floodfill runs on a volume that stays
 * empty and the pack's coloured lamps never light.
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

	// The narrow arithmetic a pack written for a recent card asks for. RenderPearl computes in
	// float16_t, int16_t and int8_t throughout and keeps a half in its storage buffers, so its
	// modules carry the Float16, Int16, Int8 and 16 and 8 bit storage capabilities, each of which
	// is only valid on a device that enabled the matching feature. The game enables none, so a
	// module carrying one of them is invalid on the device it was built for, which the validation
	// layer refuses and no driver is bound to run. OpenGL, where Iris runs, exposes the same
	// arithmetic through GL_EXT_shader_explicit_arithmetic_types with nothing to enable.
	//
	// storageInputOutput16 is not asked for: GeForce does not have it, and the translator
	// declares a 16 bit fragment output under its 32 bit type on every card rather than answer
	// it per device (LegacyGlsl.widened). A 16 bit varying is left as the pack wrote it, and no
	// pack of the corpus declares one.
	@Unique
	private static final VulkanFeature SHADER_FLOAT16 = new VulkanFeature(
			VulkanBackend.VK12_FEATURES_STRUCT, "shaderFloat16",
			VkPhysicalDeviceVulkan12Features.SHADERFLOAT16);

	@Unique
	private static final VulkanFeature SHADER_INT8 = new VulkanFeature(
			VulkanBackend.VK12_FEATURES_STRUCT, "shaderInt8",
			VkPhysicalDeviceVulkan12Features.SHADERINT8);

	@Unique
	private static final VulkanFeature SHADER_INT16 = new VulkanFeature(
			VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt16",
			VkPhysicalDeviceFeatures.SHADERINT16);

	// Subgroup operations over those types, which the pack reaches through the extended types
	// subgroup extensions: a reduction over a half vector is refused without this one.
	@Unique
	private static final VulkanFeature SUBGROUP_EXTENDED = new VulkanFeature(
			VulkanBackend.VK12_FEATURES_STRUCT, "shaderSubgroupExtendedTypes",
			VkPhysicalDeviceVulkan12Features.SHADERSUBGROUPEXTENDEDTYPES);

	@Unique
	private static final VulkanFeature STORAGE_16 = new VulkanFeature(
			VulkanBackend.VK11_FEATURES_STRUCT, "storageBuffer16BitAccess",
			VkPhysicalDeviceVulkan11Features.STORAGEBUFFER16BITACCESS);

	@Unique
	private static final VulkanFeature UNIFORM_STORAGE_16 = new VulkanFeature(
			VulkanBackend.VK11_FEATURES_STRUCT, "uniformAndStorageBuffer16BitAccess",
			VkPhysicalDeviceVulkan11Features.UNIFORMANDSTORAGEBUFFER16BITACCESS);

	@Unique
	private static final VulkanFeature STORAGE_8 = new VulkanFeature(
			VulkanBackend.VK12_FEATURES_STRUCT, "storageBuffer8BitAccess",
			VkPhysicalDeviceVulkan12Features.STORAGEBUFFER8BITACCESS);

	@Unique
	private static final VulkanFeature UNIFORM_STORAGE_8 = new VulkanFeature(
			VulkanBackend.VK12_FEATURES_STRUCT, "uniformAndStorageBuffer8BitAccess",
			VkPhysicalDeviceVulkan12Features.UNIFORMANDSTORAGEBUFFER8BITACCESS);

	@Unique
	private static final String VOXELS = "voxel lighting will not write";

	@Unique
	private static final String NARROW = "a pack computing in 16 or 8 bit types cannot be built";

	@Unique
	private static final String NARROW_SUBGROUP =
			"a pack reducing 16 or 8 bit values across a subgroup cannot be built";

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
		enable(physical, features, SHADER_FLOAT16, enabled, NARROW);
		enable(physical, features, SHADER_INT8, enabled, NARROW);
		enable(physical, features, SHADER_INT16, enabled, NARROW);
		enable(physical, features, SUBGROUP_EXTENDED, enabled, NARROW_SUBGROUP);
		enable(physical, features, STORAGE_16, enabled, NARROW);
		enable(physical, features, UNIFORM_STORAGE_16, enabled, NARROW);
		enable(physical, features, STORAGE_8, enabled, NARROW);
		enable(physical, features, UNIFORM_STORAGE_8, enabled, NARROW);
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
