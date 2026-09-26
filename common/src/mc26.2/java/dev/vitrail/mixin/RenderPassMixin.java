package dev.vitrail.mixin;

import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.ParticleDraw;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves a geometry pass open when the next program still writes the same images, and answers for
 * every draw recorded into the pass a particle group holds, whoever records it.
 * <p>
 * Closing would end the backend pass; {@link GeometryHold} is what decides that the FBO has not
 * moved. The particle hooks live on the pass and not on the renderer for the reason
 * {@link QuadParticleFeatureRendererMixin} gives: the game's own draws go through
 * {@code drawLayers}, but a mod may record draws of its own into the same pass from a handler of
 * its own, and a hook on the renderer's method sees none of those. {@link ParticleDraw} scopes
 * both to the one pass the group opened and stays out of the way on every other pass of the frame.
 */
@Mixin(RenderPass.class)
public abstract class RenderPassMixin {

	@Inject(method = "close", at = @At("HEAD"), cancellable = true, require = 1)
	private void vitrail$keep(CallbackInfo callback) {
		if (GeometryHold.keep((RenderPass) (Object) this)) {
			callback.cancel();
		}
	}

	@ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true, require = 1)
	private RenderPipeline vitrail$particlePipeline(RenderPipeline pipeline) {
		return ParticleDraw.pipeline((RenderPass) (Object) this, pipeline);
	}

	@Inject(method = "bindTexture", at = @At("TAIL"), require = 1)
	private void vitrail$particleAtlas(String name, GpuTextureView view, GpuSampler sampler,
			CallbackInfo callback) {
		ParticleDraw.texture((RenderPass) (Object) this, name, view, sampler);
	}
}
