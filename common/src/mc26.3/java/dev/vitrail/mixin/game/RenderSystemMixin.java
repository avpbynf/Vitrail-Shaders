package dev.vitrail.mixin.game;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.vitrail.render.GraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.jspecify.annotations.Nullable;

/**
 * Where 26.3 empties its pipelines at a resource reload, and the two caches it looks pipelines up
 * in.
 * <p>
 * 26.2 emptied the device's one cache in {@code clearPipelineCache}, and the engine's pipelines,
 * which lived in that same cache, went with it. 26.3 builds a new cache at every resource load and
 * swaps it in here, closing the old one; the engine's pipelines live in {@code GraphicsApi}'s own
 * map instead, so this is where that map is emptied too, with the live pack carried over as on
 * 26.2. The two caches themselves are reached through {@link RenderSystemAccessor}.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {

	@Inject(method = "setCurrentPipelineCache", at = @At("HEAD"), require = 1)
	private static void vitrail$purge(PipelineCache cache,
			CallbackInfoReturnable<@Nullable PipelineCache> callback) {
		GraphicsApi.purge();
	}

	/**
	 * Answers the game's lookup of a compiled pipeline out of the engine's own map first. On 26.2
	 * the device held one cache and a pipeline this engine compiled was in it, so Sodium's renderer
	 * setting the terrain pipeline this engine handed it found the pack's compile. 26.3 looks the
	 * description up in caches that hold only what the game compiled, and compiles a miss from the
	 * game's own sources, which hold no line of a pack: without this, that lookup would build an
	 * invalid pipeline in the middle of Sodium's pass.
	 */
	@Inject(method = "getCompiledPipelineNullable", at = @At("HEAD"), cancellable = true,
			require = 1)
	private static void vitrail$ours(RenderPipeline pipeline,
			CallbackInfoReturnable<@Nullable CompiledRenderPipeline> callback) {
		CompiledRenderPipeline ours = GraphicsApi.held(pipeline);
		if (ours != null) {
			callback.setReturnValue(ours);
		}
	}

	/**
	 * Notes which description the game compiled what it hands out from, which a hook on a pass
	 * asks now that a pass is handed the compiled object and not the description.
	 */
	@Inject(method = "getCompiledPipelineNullable", at = @At("RETURN"), require = 1)
	private static void vitrail$note(RenderPipeline pipeline,
			CallbackInfoReturnable<@Nullable CompiledRenderPipeline> callback) {
		GraphicsApi.noteGameCompiled(pipeline, callback.getReturnValue());
	}
}
