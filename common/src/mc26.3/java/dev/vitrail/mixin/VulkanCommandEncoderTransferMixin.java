package dev.vitrail.mixin;

import dev.vitrail.render.timing.TransferProbe;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Feeds {@link TransferProbe} from the one Vulkan encoder every {@code CommandEncoder} shares, which
 * is the only place that knows whether a pass is open whoever asked.
 * <p>
 * One injection per call rather than a name matching several overloads: the five-argument
 * {@code copyTextureToBuffer} hands over to the nine-argument one, and counting both would count one
 * copy twice.
 * <p>
 * The 26.3 half: the same probe on the backend encoder of that game, whose region clear names a
 * mip level beside the region.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderTransferMixin {

	@Shadow
	private VulkanRenderPass currentRenderPass;

	@Unique
	private Supplier<String> vitrail$open() {
		return this.currentRenderPass == null ? null : this.currentRenderPass.getLabel();
	}

	@Inject(method = "writeToBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Ljava/nio/ByteBuffer;)V",
			at = @At("HEAD"))
	private void vitrail$writeToBuffer(CallbackInfo ci) {
		TransferProbe.seen("writeToBuffer", vitrail$open());
	}

	@Inject(method = "copyToBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
			+ "Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V", at = @At("HEAD"))
	private void vitrail$copyToBuffer(CallbackInfo ci) {
		TransferProbe.seen("copyToBuffer", vitrail$open());
	}

	@Inject(method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
			at = @At("HEAD"))
	private void vitrail$writeToTexture(CallbackInfo ci) {
		TransferProbe.seen("writeToTexture", vitrail$open());
	}

	@Inject(method = "copyBufferToTexture(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;IIII"
			+ "Lcom/mojang/renderpearl/api/textures/GpuTexture;IIIIII)V", at = @At("HEAD"))
	private void vitrail$copyBufferToTexture(CallbackInfo ci) {
		TransferProbe.seen("copyBufferToTexture", vitrail$open());
	}

	@Inject(method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;"
			+ "Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V", at = @At("HEAD"))
	private void vitrail$copyTextureToBuffer(CallbackInfo ci) {
		TransferProbe.seen("copyTextureToBuffer", vitrail$open());
	}

	@Inject(method = "copyTextureToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;"
			+ "Lcom/mojang/renderpearl/api/textures/GpuTexture;IIIIIII)V", at = @At("HEAD"))
	private void vitrail$copyTextureToTexture(CallbackInfo ci) {
		TransferProbe.seen("copyTextureToTexture", vitrail$open());
	}

	@Inject(method = "clearColorTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;)V",
			at = @At("HEAD"))
	private void vitrail$clearColorTexture(CallbackInfo ci) {
		TransferProbe.seen("clearColorTexture", vitrail$open());
	}

	@Inject(method = "clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V", at = @At("HEAD"))
	private void vitrail$clearDepthTexture(CallbackInfo ci) {
		TransferProbe.seen("clearDepthTexture", vitrail$open());
	}

	@Inject(method = "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;"
			+ "Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V", at = @At("HEAD"))
	private void vitrail$clearColorAndDepthTextures(CallbackInfo ci) {
		TransferProbe.seen("clearColorAndDepthTextures", vitrail$open());
	}

	@Inject(method = "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;"
			+ "Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;DIIIII)V", at = @At("HEAD"))
	private void vitrail$clearColorAndDepthRegion(CallbackInfo ci) {
		TransferProbe.seen("clearColorAndDepthTextures (region)", vitrail$open());
	}

	@Inject(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)"
			+ "Lcom/mojang/renderpearl/backend/api/RenderPassBackend;", at = @At("HEAD"))
	private void vitrail$createRenderPass(CallbackInfoReturnable<?> cir) {
		TransferProbe.seen("createRenderPass", vitrail$open());
	}
}
