package dev.vitrail.neoforge.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Puts the render list rebuild flag back up after the light's walk has consumed it.
 * <p>
 * {@code finalizeRenderLists} lowers the flag on its way out, whoever called it. The shadow stage
 * calls it at the end of a frame with the light's viewport, so without this the camera's own
 * finalize at the top of the next frame would find the flag down and keep the light's lists, and
 * the world would be drawn from the sun.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public interface RenderSectionManagerAccessor {

	@Accessor("needsRenderListUpdate")
	void vitrail$setNeedsRenderListUpdate(boolean value);
}
