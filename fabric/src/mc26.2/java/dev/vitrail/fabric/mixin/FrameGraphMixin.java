package dev.vitrail.fabric.mixin;

import dev.vitrail.platform.EngineStages;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The model view and the camera position of the frame the shadow stage is about to be handed, taken
 * where NeoForge posts its frame graph setup event.
 * <p>
 * At the head of the method rather than in the middle of the graph being built, which is a few
 * statements earlier than the event and reads the same two values: both are parameters of this
 * method and nothing between the two points writes to either. What the position of the event buys
 * there is the targets it also carries, and this engine puts no pass of its own in the
 * graph, so nothing here needs them.
 */
@Mixin(LevelRenderer.class)
public abstract class FrameGraphMixin {

	@Inject(method = "render", at = @At("HEAD"), require = 1)
	private void vitrail$frameGraphSetup(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker,
			boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix,
			GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
		EngineStages.frameGraphSetup(modelViewMatrix, cameraState.pos);
	}
}
