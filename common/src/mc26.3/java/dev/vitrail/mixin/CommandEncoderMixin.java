package dev.vitrail.mixin;

import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.LevelPass;
import dev.vitrail.render.timing.PassTimings;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts every render pass, texture clear and texture copy the encoder is asked for, and brackets
 * each pass with the two timestamps {@link PassTimings} reads when the timed report is on.
 * <p>
 * Every overload of {@code createRenderPass} funnels into the descriptor one, so one injection
 * sees every pass whoever opens it. Clears and copies each end with the same full memory barrier
 * the backend puts after a pass, which is why they are counted beside it.
 * <p>
 * A geometry hold must end before any of those: a copy or a clear cannot be recorded inside a
 * pass, and a composite that samples what geometry just wrote has to see the store. Leftover
 * Immediate draws and Distant Horizons' GenericObjectRenderer that write the same images are
 * the exception: they keep the hold, the way Iris leaves the default framebuffer bound. A buffer
 * or texture transfer ends it too, unless a family is still drawing into it.
 * <p>
 * The 26.3 half: the encoder is an interface there, and the class every call lands in is
 * {@code FrontendCommandEncoder}, which carries the same methods; the clear with a region names a
 * mip level beside it. Every one of those ends the level's pass as well, {@link LevelPass} saying
 * why: on this game the level draws through one pass, and what the engine records between two of
 * its phases could not be recorded inside it.
 */
@Mixin(FrontendCommandEncoder.class)
public abstract class CommandEncoderMixin {

	@Inject(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)"
			+ "Lcom/mojang/renderpearl/api/commands/RenderPass;",
			at = @At("HEAD"),
			cancellable = true,
			require = 1)
	private void vitrail$flushHold(RenderPassDescriptor descriptor,
			CallbackInfoReturnable<RenderPass> cir) {
		// The level's pass first: whatever is opened now, the pass the game drew the level into so
		// far has to end before it, and the level's next draw opens it again.
		LevelPass.suspendCurrent();
		RenderPass leftover = GeometryHold.leftover(descriptor);
		if (leftover != null) {
			cir.setReturnValue(leftover);

			return;
		}

		if (!GeometryHold.opening()) {
			// The pass's own label is the cause, and it is already a supplier: naming it costs
			// nothing until a census asks, which is why the prefix is added there and not here.
			GeometryHold.flush(descriptor.label());
		}
	}

	@Inject(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)"
			+ "Lcom/mojang/renderpearl/api/commands/RenderPass;",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;createRenderPass("
							+ "Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)"
							+ "Lcom/mojang/renderpearl/backend/api/RenderPassBackend;"),
			require = 1)
	private void vitrail$openPass(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
		PassTimings.open((CommandEncoder) (Object) this, descriptor.label());
	}

	@Inject(method = "submitRenderPass", at = @At("TAIL"), require = 1)
	private void vitrail$closePass(CallbackInfo ci) {
		PassTimings.close((CommandEncoder) (Object) this);
	}

	@Inject(method = "clearColorTexture", at = @At("HEAD"), require = 1)
	private void vitrail$clearColour(CallbackInfo ci) {
		LevelPass.suspendCurrent();
		GeometryHold.flush(() -> "a texture clear");
		PassTimings.censusClear();
	}

	@Inject(method = "clearDepthTexture", at = @At("HEAD"), require = 1)
	private void vitrail$clearDepth(CallbackInfo ci) {
		LevelPass.suspendCurrent();
		GeometryHold.flush(() -> "a texture clear");
		PassTimings.censusClear();
	}

	@Inject(method = "clearColorAndDepthTextures("
			+ "Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;"
			+ "Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
			at = @At("HEAD"), require = 1)
	private void vitrail$clearColourAndDepth(CallbackInfo ci) {
		LevelPass.suspendCurrent();
		GeometryHold.flush(() -> "a texture clear");
		PassTimings.censusClear();
	}

	@Inject(method = "clearColorAndDepthTextures("
			+ "Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;"
			+ "Lcom/mojang/renderpearl/api/textures/GpuTexture;DIIIII)V",
			at = @At("HEAD"), require = 1)
	private void vitrail$clearColourAndDepthRegion(CallbackInfo ci) {
		LevelPass.suspendCurrent();
		GeometryHold.flush(() -> "a texture clear");
		PassTimings.censusClear();
	}

	/**
	 * A fence is refused inside a pass like a copy is. The shadow casters' storage recycles its
	 * staged vertex buffers behind one at the end of the walk, and where the pack drew the last
	 * caster that caster's pass is still held, the level's pass it was drawn through having nothing
	 * left to reopen for.
	 */
	@Inject(method = "createFence", at = @At("HEAD"), require = 1)
	private void vitrail$fence(CallbackInfoReturnable<?> cir) {
		LevelPass.suspendCurrent();
		GeometryHold.flush(() -> "a fence");
	}

	@Inject(method = "copyTextureToTexture", at = @At("HEAD"), require = 1)
	private void vitrail$copyTexture(CallbackInfo ci) {
		LevelPass.suspendCurrent();
		GeometryHold.flush(() -> "a texture copy");
		PassTimings.censusCopy();
	}

	/**
	 * Ends a hold nothing is drawing into before a buffer or texture transfer is recorded.
	 * <p>
	 * Every {@code createCommandEncoder()} is a new facade over the one Vulkan encoder, so the
	 * facade's own refusal of a transfer inside a pass only sees the passes that same instance
	 * opened. A transfer asked for through any other instance went straight into the pass the hold
	 * kept open: the hand's projection block and its vertex upload into the pass of whichever family
	 * drew last, the translucent terrain included. Vulkan forbids a transfer
	 * inside a render pass, so what a driver makes of one is its own affair and differs between them.
	 * <p>
	 * The two-argument {@code writeToTexture} hands over to the six-argument one and is not listed;
	 * both read backs are, the short one checking for a pass of its own, and ending a hold twice is
	 * ending it once.
	 */
	@Inject(method = {
			"writeToBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Ljava/nio/ByteBuffer;)V",
			"copyToBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V",
			"writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;"
					+ "IIII)V",
			"writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
			"copyBufferToTexture(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;IIII"
					+ "Lcom/mojang/renderpearl/api/textures/GpuTexture;IIIIII)V",
			"copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"
					+ "JLjava/lang/Runnable;I)V",
			"copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"
					+ "JLjava/lang/Runnable;IIIII)V"},
			at = @At("HEAD"), require = 7)
	private void vitrail$transfer(CallbackInfo ci) {
		LevelPass.suspendCurrent();
		GeometryHold.flushIdle(() -> "a buffer or texture transfer");
	}
}
