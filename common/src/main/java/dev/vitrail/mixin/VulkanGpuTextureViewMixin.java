package dev.vitrail.mixin;

import dev.vitrail.render.GlyphIntensity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Writes {@code VK_COMPONENT_SWIZZLE_R} into all four components of the view {@link GlyphIntensity}
 * is asking for, and of no other.
 * <p>
 * The backend leaves the component mapping at the identity, {@code VkImageViewCreateInfo} being
 * zeroed and never given one, and nothing in the blaze3d API takes a mapping: the only two ways to
 * a view are {@code GpuDevice.createTextureView} and its mip range. So the request is carried on a
 * flag rather than in an argument, and it is read here, inside the one call that raises it.
 * <p>
 * Wrapped on {@code format} rather than injected before {@code vkCreateImageView}: the format call
 * hands back the create info itself, so the mapping is written on the object the constructor is
 * about to submit without the local having to be reached for. {@code VulkanBindGroupLayoutMixin}
 * does the same on the same kind of builder.
 */
@Mixin(VulkanGpuTextureView.class)
public abstract class VulkanGpuTextureViewMixin {

	@WrapOperation(method = "<init>", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkImageViewCreateInfo;format(I)"
							+ "Lorg/lwjgl/vulkan/VkImageViewCreateInfo;"))
	private VkImageViewCreateInfo vitrail$intensity(VkImageViewCreateInfo info, int format,
			Operation<VkImageViewCreateInfo> original) {
		VkImageViewCreateInfo built = original.call(info, format);
		if (GlyphIntensity.swizzling()) {
			built.components()
					.r(VK10.VK_COMPONENT_SWIZZLE_R)
					.g(VK10.VK_COMPONENT_SWIZZLE_R)
					.b(VK10.VK_COMPONENT_SWIZZLE_R)
					.a(VK10.VK_COMPONENT_SWIZZLE_R);
		}

		return built;
	}
}
