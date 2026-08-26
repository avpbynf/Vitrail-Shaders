package dev.vitrail.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the entity outline target, the one screen-sized target of the level renderer that is
 * allocated once rather than taken from the frame's resource pool. The other screen-sized targets
 * of the world's frame, which exist only while the transparency post chain does, are described
 * anew each frame from the main target's size, so the render scale moves them by moving that
 * size; this one would stay at the window's size and the outline would be drawn into a corner of
 * it, so the scale resizes it alongside.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererOutlineAccessor {

	@Accessor("entityOutlineTarget")
	RenderTarget vitrail$entityOutlineTarget();
}
