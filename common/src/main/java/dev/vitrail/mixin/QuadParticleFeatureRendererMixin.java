package dev.vitrail.mixin;

import dev.vitrail.render.ParticleDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Draws the game's quad particles with the programs the pack ships for them.
 * <p>
 * <strong>The entities' mixin cannot reach this class and that is the reason it exists.</strong>
 * Twelve feature renderers draw through {@code RenderTypeFeatureRenderer.executeGroup} and inherit it
 * unchanged; this one implements the interface directly and has an {@code executeGroup} of its own,
 * which opens a render pass, walks the layers of the group and sets a pipeline and an atlas for each.
 * So the shape here is the sky's and the weather's rather than the entities': the pass is replaced
 * where it is opened and the pipeline swapped where it is set.
 * <p>
 * <strong>Which half is being drawn is read off the submits and not worked out here.</strong> The
 * game reads the same field to decide which layers go into the group and which target they go to, so
 * taking it from anywhere else would be a second answer to a question already asked.
 * <p>
 * <strong>All four handlers are required</strong>, which the rest of this package is not. The pass
 * and the pipeline are a pair, and half of them applying binds a pipeline carrying eight colour
 * states into a pass carrying one, which throws by name. The other two each leave a picture with
 * nothing in the log: particles drawn with the block of the group before, or a group that kept this
 * engine's program after the one that opened it was forgotten.
 */
@Mixin(QuadParticleFeatureRenderer.class)
public abstract class QuadParticleFeatureRendererMixin {

	/**
	 * Prepares the pack's program for the half about to be drawn and hands back the pass it wants
	 * opened.
	 * <p>
	 * The submits cannot be empty here: the renderer only records a group when they are not, and it
	 * is indexing that record one line above. Read defensively all the same, an empty list being a
	 * frame with no particles rather than anything to report.
	 */
	@WrapOperation(method = "executeGroup", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
							+ "Ljava/util/function/Supplier;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Ljava/util/Optional;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Ljava/util/OptionalDouble;"
							+ ")Lcom/mojang/blaze3d/systems/RenderPass;"))
	private RenderPass vitrail$open(CommandEncoder encoder, Supplier<String> label,
			GpuTextureView colour, Optional<?> clearColour, GpuTextureView depth,
			OptionalDouble clearDepth, Operation<RenderPass> original,
			@Local(argsOnly = true) List<QuadParticleFeatureRenderer.Submit> submits) {
		RenderPipeline pipeline = submits.isEmpty()
				? null
				: ParticleDraw.group(submits.getFirst().translucent(), colour, depth);
		RenderPassDescriptor descriptor = pipeline == null ? null : ParticleDraw.descriptor();

		return descriptor == null
				? original.call(encoder, label, colour, clearColour, depth, clearDepth)
				: encoder.createRenderPass(descriptor);
	}

	@WrapOperation(method = "drawLayers", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline("
							+ "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private static void vitrail$pipeline(RenderPass pass, RenderPipeline pipeline,
			Operation<Void> original) {
		original.call(pass, ParticleDraw.pipeline(pipeline));
	}

	/**
	 * Lets the game bind the layer's own atlas and keeps what it bound, then binds the pack's block
	 * and samplers over it, one line before the draw that reads them.
	 * <p>
	 * In {@code drawLayers} and not in {@code executeGroup}, which is what makes it the LAYER's atlas
	 * rather than the group's: one group is drawn off the block atlas, the item atlas and the
	 * particle atlas between its layers. The game's own binding costs nothing and its name is not the
	 * one that is read, the descriptor flush walking the layout of the pipeline that is really bound.
	 */
	@WrapOperation(method = "drawLayers", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture("
							+ "Ljava/lang/String;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Lcom/mojang/blaze3d/textures/GpuSampler;)V"))
	private static void vitrail$texture(RenderPass pass, String name, GpuTextureView view,
			GpuSampler sampler, Operation<Void> original) {
		original.call(pass, name, view, sampler);
		ParticleDraw.texture(pass, view, sampler);
	}

	/** Forgets the group, so that the next one cannot be handed this one's block. */
	@Inject(method = "executeGroup", at = @At("RETURN"), require = 1)
	private void vitrail$close(FeatureFrameContext context, int groupIndex, List<?> submits,
			boolean strictlyOrdered, CallbackInfo callback) {
		ParticleDraw.endGroup();
	}
}
