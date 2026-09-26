package dev.vitrail.fabric.mixin;

import dev.vitrail.platform.EngineStages;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The three stages the world's main pass is cut into, reached on Fabric where NeoForge reaches them
 * by event.
 * <p>
 * The 26.3 half. The phases moved out of the main pass's runnable into two methods of their own,
 * {@code executeSolid} and {@code executeClassicTransparency}, which draw into the one pass the
 * runnable opened; NeoForge's patch posts its three events from those two methods, between the very
 * calls wrapped here. The wraps run the call and then the stage, because every one of these stages
 * is an "after", and each stage ends the level's pass itself before it records anything.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelStagesMixin {

	@WrapOperation(method = "executeSolid",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
							+ "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
							+ "Lcom/mojang/renderpearl/api/commands/RenderPass;"
							+ "Lcom/mojang/renderpearl/api/textures/GpuSampler;"
							+ "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"),
			require = 1)
	private void vitrail$afterOpaqueBlocks(ChunkSectionsToRender sections,
			ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler, GpuTextureView atlas,
			boolean wireframe, Operation<Void> original) {
		original.call(sections, group, pass, sampler, atlas, wireframe);
		EngineStages.afterOpaqueBlocks();
	}

	@WrapOperation(method = "executeSolid",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
							+ "executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
			require = 1)
	private void vitrail$afterOpaqueFeatures(FeatureRenderDispatcher.PreparedFrame frame,
			RenderPass pass, Operation<Void> original) {
		original.call(frame, pass);
		EngineStages.afterOpaqueFeatures();
	}

	@WrapOperation(method = "executeClassicTransparency",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
							+ "executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
			require = 1)
	private void vitrail$afterTranslucentFeatures(FeatureRenderDispatcher.PreparedFrame frame,
			RenderPass pass, Operation<Void> original) {
		original.call(frame, pass);
		EngineStages.afterTranslucentFeatures();
	}
}
