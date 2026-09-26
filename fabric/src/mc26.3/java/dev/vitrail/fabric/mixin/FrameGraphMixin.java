package dev.vitrail.fabric.mixin;

import dev.vitrail.platform.EngineStages;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The head of the level's frame on Fabric, where NeoForge posts its frame graph setup event.
 * <p>
 * The 26.3 half. The level's render no longer takes the model view matrix: it reads the camera's
 * rotation off the camera state, and that is the matrix NeoForge hands its event on this game, so
 * it is the one handed on here.
 */
@Mixin(LevelRenderer.class)
public abstract class FrameGraphMixin {

	@Inject(method = "render", at = @At("HEAD"), require = 1)
	private void vitrail$frameGraphSetup(GraphicsResourceAllocator allocator, boolean renderOutline,
			CameraRenderState cameraState, GpuBufferSlice terrainFog, Vector4f fogColor,
			boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
		EngineStages.frameGraphSetup(cameraState.viewRotationMatrix, cameraState.pos);
	}
}
