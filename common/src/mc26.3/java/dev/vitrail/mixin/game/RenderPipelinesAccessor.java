package dev.vitrail.mixin.game;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The snippet every weather pipeline of the game is built from, which 26.3 keeps private and builds
 * one pipeline from where 26.2 built two. {@code GamePipelines} builds the other one from it.
 */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {

	@Accessor("WEATHER_SNIPPET")
	static RenderPipeline.Snippet vitrail$weatherSnippet() {
		throw new AssertionError("an accessor's body is replaced as the class is loaded");
	}
}
