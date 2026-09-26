package dev.vitrail.mixin.game;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The map behind one of the game's pipeline caches, which 26.3 keeps per resource load where 26.2
 * kept one on the device. {@code GraphicsApi.dropEntityPipelines} takes the entity pipelines out of
 * it when the entity mesh's answer moves, as the 26.2 device mixin took them out of the device's.
 */
@Mixin(PipelineCache.class)
public interface PipelineCacheAccessor {

	@Accessor("cache")
	Map<RenderPipeline, CompiledRenderPipeline> vitrail$cache();
}
