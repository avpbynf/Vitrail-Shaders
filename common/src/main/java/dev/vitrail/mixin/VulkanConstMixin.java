package dev.vitrail.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanConst;
import dev.vitrail.render.TextureUsage;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a colour target be written by a compute shader.
 * <p>
 * A pack's compute pass may write a colour target directly, {@code layout(rgba16f) uniform image2D
 * colorimg4} in Photon's sky harmonics, and Vulkan refuses a storage descriptor on an image created
 * without {@code VK_IMAGE_USAGE_STORAGE_BIT}. The game's conversion knows four usages and never
 * this one, so the bit is added on the way out whenever {@link TextureUsage} says the texture being
 * created on this thread asked for it. No other texture takes the bit: the flag is raised by the
 * engine's own surfaces for one call each, on their own thread, and lowered right after.
 */
@Mixin(VulkanConst.class)
public abstract class VulkanConstMixin {

	@Inject(method = "textureUsageToVk(ILcom/mojang/blaze3d/GpuFormat;)I", at = @At("RETURN"),
			cancellable = true)
	private static void vitrail$storageUsage(int usage, GpuFormat format,
			CallbackInfoReturnable<Integer> info) {
		if (TextureUsage.storageRequested()) {
			info.setReturnValue(info.getReturnValueI() | VK10.VK_IMAGE_USAGE_STORAGE_BIT);
		}
	}
}
