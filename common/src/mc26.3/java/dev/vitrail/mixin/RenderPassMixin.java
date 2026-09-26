package dev.vitrail.mixin;

import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.GraphicsApi;
import dev.vitrail.render.ParticleDraw;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves a geometry pass open when the next program still writes the same images, and answers for
 * every draw recorded into the pass a particle group holds, whoever records it.
 * <p>
 * Closing would end the backend pass; {@link GeometryHold} is what decides that the images have
 * not moved. The particle hooks live on the pass and not on the renderer, so a draw a mod records
 * into the same pass from a handler of its own is answered as well; {@link ParticleDraw} scopes
 * both to the one pass the group opened.
 * <p>
 * The 26.3 half. The pass every caller holds is the {@code RenderPass} interface and the object
 * behind it is {@code FrontendRenderPass}. Its {@code setPipeline} takes the compiled pipeline rather
 * than the description 26.2 took, so the description the particle swap decides on is asked of
 * {@code GraphicsApi}, and the pipeline the swap chooses is handed back compiled; a sampled image is
 * bound through {@code setUniform} rather than {@code bindTexture}.
 */
@Mixin(FrontendRenderPass.class)
public abstract class RenderPassMixin {

	@Inject(method = "close", at = @At("HEAD"), cancellable = true, require = 1)
	private void vitrail$keep(CallbackInfo callback) {
		if (GeometryHold.keep((RenderPass) (Object) this)) {
			callback.cancel();
		}
	}

	@ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true, require = 1)
	private CompiledRenderPipeline vitrail$particlePipeline(CompiledRenderPipeline compiled) {
		RenderPipeline game = GraphicsApi.descriptionOf(compiled);
		if (game == null) {
			return compiled;
		}

		RenderPipeline chosen = ParticleDraw.pipeline((RenderPass) (Object) this, game);
		if (chosen == game) {
			return compiled;
		}

		CompiledRenderPipeline ours = GraphicsApi.compiledFor(chosen);
		return ours == null ? compiled : ours;
	}

	@Inject(method = "setUniform(Ljava/lang/String;"
			+ "Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
			+ "Lcom/mojang/renderpearl/api/textures/GpuSampler;)V", at = @At("TAIL"), require = 1)
	private void vitrail$particleAtlas(String name, GpuTextureView view, GpuSampler sampler,
			CallbackInfo callback) {
		ParticleDraw.texture((RenderPass) (Object) this, name, view, sampler);
	}
}
