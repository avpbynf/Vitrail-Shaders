package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.TerrainDraw;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import net.caffeinemc.mods.sodium.client.gpu.device.context.VKDrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds the terrain program's uniform block and its samplers into the pass Sodium has just opened.
 * <p>
 * This method is the hook rather than {@code DefaultChunkRenderer#render} because it is handed both
 * things the bind needs, the pass and the pipeline that was bound into it, as arguments. Reaching
 * them inside {@code render} would mean capturing a local, which is the same information held less
 * reliably.
 * <p>
 * It runs immediately after {@code setPipeline} and for every chunk pass, ours and Sodium's alike,
 * so the pipeline argument decides. Everything the bound pipeline's layout declares has to be bound
 * before the draw or it throws by name; the converse is free, which is why the four bindings Sodium
 * emits unconditionally afterwards cost nothing when our pipeline is the one bound.
 */
@Mixin(value = VKDrawContext.class, remap = false)
public abstract class VKDrawContextMixin {

	@Inject(method = "setContext", at = @At("HEAD"))
	private void vitrail$bind(RenderPass pass, RenderPipeline pipeline, CallbackInfo callback) {
		TerrainDraw.bind(pass, pipeline);
	}
}
