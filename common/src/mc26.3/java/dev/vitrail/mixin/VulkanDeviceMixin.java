package dev.vitrail.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.vitrail.render.GeometryHold;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ends a held geometry pass before the device allocates or uploads, which records work that cannot
 * sit inside a pass.
 * <p>
 * The 26.3 half. What the 26.2 mixin of this name also carried, the device's pipeline cache and the
 * entity pipelines set aside in it, has no device to live on in 26.3: the engine's pipelines are
 * {@code GraphicsApi}'s, the game's are in the pipeline caches {@code RenderSystemMixin} reaches,
 * and closing one is safe at any instant, so nothing is set aside here.
 */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {

	/** Allocation emits an initial image barrier, which cannot sit in a retained graphics pass. */
	@Inject(method = "createTexture(Ljava/lang/String;ILcom/mojang/renderpearl/api/GpuFormat;IIII)"
			+ "Lcom/mojang/renderpearl/api/textures/GpuTexture;", at = @At("HEAD"), require = 1)
	private void vitrail$flushBeforeTextureAllocation(CallbackInfoReturnable<GpuTexture> callback) {
		GeometryHold.flushIdle(() -> "texture allocation");
	}

	/** Initial buffer data is uploaded through the backend, bypassing the facade transfer hook. */
	@Inject(method = "createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)"
			+ "Lcom/mojang/renderpearl/api/buffers/GpuBuffer;", at = @At("HEAD"), require = 1)
	private void vitrail$flushBeforeBufferUpload(CallbackInfoReturnable<GpuBuffer> callback) {
		GeometryHold.flushIdle(() -> "initial buffer upload");
	}
}
