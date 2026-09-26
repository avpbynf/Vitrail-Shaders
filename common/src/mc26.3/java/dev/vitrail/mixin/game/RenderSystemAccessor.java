package dev.vitrail.mixin.game;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import org.jspecify.annotations.Nullable;

/** The two caches 26.3 looks the game's pipelines up in, in the order it asks them. */
@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {

	/** The cache the current resource load compiled, or null before the first one. */
	@Accessor("currentPipelineCache")
	static @Nullable PipelineCache vitrail$current() {
		throw new AssertionError();
	}

	/** The cache the game falls back on for what the current one does not hold. */
	@Accessor("fallbackPipelineCache")
	static @Nullable PipelineCache vitrail$fallback() {
		throw new AssertionError();
	}
}
