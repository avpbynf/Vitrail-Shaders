package dev.vitrail.fabric.mixin;

import dev.vitrail.platform.EngineStages;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of the level on Fabric, right after the level renderer returns, which is the line
 * NeoForge posts its after-level event on.
 * <p>
 * The 26.3 half: {@code renderLevel} takes no argument there, and the level's render takes the
 * frame's state rather than the tracker and the model view matrix.
 */
@Mixin(GameRenderer.class)
public abstract class AfterLevelMixin {

	@Inject(method = "renderLevel",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;"
							+ "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Z"
							+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
							+ "Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
							+ "Lorg/joml/Vector4f;ZZ)V",
					shift = At.Shift.AFTER),
			require = 1)
	private void vitrail$afterLevel(CallbackInfo ci) {
		EngineStages.afterLevel();
	}
}
