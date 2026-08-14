package dev.vitrail.fabric.mixin;

import dev.vitrail.platform.EngineStages;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The pack's whole chain, drawn where the level renderer has just returned and nothing else has
 * touched the main target yet.
 * <p>
 * The one point of the frame this engine cannot do without, and on NeoForge it is a public event
 * posted from this exact line. Here it is the line itself: injected after the call rather than at
 * the return of the method, because the hand, the screen effects and the crosshair are all drawn
 * further down and the chain has to be under them.
 */
@Mixin(GameRenderer.class)
public abstract class AfterLevelMixin {

	@Inject(method = "renderLevel",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;"
							+ "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
							+ "Lnet/minecraft/client/DeltaTracker;Z"
							+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
							+ "Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
							+ "Lorg/joml/Vector4f;Z)V",
					shift = At.Shift.AFTER),
			require = 1)
	private void vitrail$afterLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
		EngineStages.afterLevel();
	}
}
