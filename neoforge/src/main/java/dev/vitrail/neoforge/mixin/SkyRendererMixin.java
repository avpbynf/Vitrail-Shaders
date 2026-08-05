package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.SkyDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the sky disc with the pack's own {@code gbuffers_skybasic} instead of the game's shader.
 * <p>
 * The sky is the one piece of the world that opens its own render passes: {@code SkyRenderer} makes
 * one per element, sets a pipeline of the game's and draws a buffer built once at startup. So the
 * hook is not the one the entities will use, and it is smaller: the pipeline is swapped where it is
 * set, and everything else about the pass stays the game's, its attachment included.
 * <p>
 * <strong>Two injections and not one, and the split is the point.</strong> Compiling a pipeline,
 * making a buffer or clearing a target records a barrier into the very command buffer a pass would
 * be recording into, and a clear refuses outright while one is open. All of that happens at the
 * head, before the pass exists; what happens inside it is a pipeline being named and a few names
 * being bound.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

	/**
	 * The pipeline this frame's disc is drawn with, worked out before the pass opens and read inside
	 * it. Null the moment anything is missing, and the game then draws its own sky.
	 * <p>
	 * A field of the mixin and not a static of {@code SkyDraw}, because it belongs to one call of
	 * one method: the renderer is one object and the sky is drawn once a frame.
	 */
	private RenderPipeline vitrail$disc;

	@Inject(method = "renderSkyDisc", at = @At("HEAD"))
	private void vitrail$prepare(int skyColor, CallbackInfo callback) {
		this.vitrail$disc = SkyDraw.disc();
	}

	@WrapOperation(
			method = "renderSkyDisc",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline("
							+ "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void vitrail$pipeline(RenderPass pass, RenderPipeline pipeline,
			Operation<Void> original) {
		RenderPipeline ours = this.vitrail$disc;
		if (ours == null) {
			original.call(pass, pipeline);

			return;
		}

		// Bound right after the pipeline and before the game binds its own names into the same pass.
		// The order of the binds does not matter, only that every name the bound pipeline declares
		// carries something by the time the draw is recorded.
		original.call(pass, ours);
		SkyDraw.bind(pass, ours);
	}
}
