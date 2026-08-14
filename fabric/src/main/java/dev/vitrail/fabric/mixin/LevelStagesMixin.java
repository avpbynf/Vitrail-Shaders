package dev.vitrail.fabric.mixin;

import dev.vitrail.platform.EngineStages;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.textures.GpuSampler;
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
 * All three are inside one lambda, the body {@code addMainPass} hands to {@code FramePass.executes},
 * and NeoForge posts its three events from that same body: the calls this wraps are the very lines
 * its patch sits between. Naming a lambda in a mixin is what it costs, and it is what Iris pays too
 * ({@code MojLambdas.RENDER_MAIN_PASS}). The name holds because the game jar of one version is
 * fixed, and it was read off that jar rather than guessed: {@code addMainPass} carries exactly one
 * lambda in 26.2, and the compiler numbers it per method, so it is {@code $0} in the bare game and
 * {@code $0} in the NeoForge one as well.
 * <p>
 * The wraps run the call and then the stage, never the other way round, because every one of these
 * stages is an "after". {@code require = 1} on each: an injector that stopped binding would leave a
 * frame that draws the world and skips one stage of the pack's chain, which is a picture rather than
 * a crash, and this whole package exists to keep those apart.
 * <p>
 * Two of the names read the wrong way round and it is worth having the order written out: the opaque
 * chunk group, then the opaque blocks stage, then {@code executeSolid}, then the opaque features
 * stage, then {@code executeTranslucent}, then the translucent features stage, then the outlines and
 * the translucent chunk group. The features are drawn before the water.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelStagesMixin {

	/**
	 * The first of the two {@code renderGroup} calls, which is the opaque one. Ordinal rather than a
	 * test on the group, so that the binding fails loudly if the pass ever stops making both calls
	 * from here.
	 */
	@WrapOperation(method = "lambda$addMainPass$0",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
							+ "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
							+ "Lcom/mojang/blaze3d/textures/GpuSampler;)V",
					ordinal = 0),
			require = 1)
	private void vitrail$afterOpaqueBlocks(ChunkSectionsToRender sections,
			ChunkSectionLayerGroup group, GpuSampler sampler, Operation<Void> original) {
		original.call(sections, group, sampler);
		EngineStages.afterOpaqueBlocks();
	}

	@WrapOperation(method = "lambda$addMainPass$0",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
							+ "executeSolid()V"),
			require = 1)
	private void vitrail$afterOpaqueFeatures(FeatureRenderDispatcher.PreparedFrame frame,
			Operation<Void> original) {
		original.call(frame);
		EngineStages.afterOpaqueFeatures();
	}

	@WrapOperation(method = "lambda$addMainPass$0",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
							+ "executeTranslucent()V"),
			require = 1)
	private void vitrail$afterTranslucentFeatures(FeatureRenderDispatcher.PreparedFrame frame,
			Operation<Void> original) {
		original.call(frame);
		EngineStages.afterTranslucentFeatures();
	}
}
