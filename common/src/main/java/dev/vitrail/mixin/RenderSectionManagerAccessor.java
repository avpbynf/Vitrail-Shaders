package dev.vitrail.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the walk state the shadow stage has to move without going through
 * {@code prepareRender}.
 * <p>
 * {@code finalizeRenderLists} lowers the rebuild flag on its way out, whoever called it. The
 * shadow stage calls it at the end of a frame with the light's viewport, so without putting the
 * flag back the camera's own finalize at the top of the next frame would keep the light's lists,
 * and the world would be drawn from the sun.
 * <p>
 * The second walk also has to bump the frame counter: the per-region lists only reset on the
 * first walk of a number, and a second walk under the same one appends until they overflow.
 * {@code prepareRender} does that bump and then rotates Sodium's indirect command ring. The
 * rotation is the cost this accessor exists to skip. The camera already rotated at the top of
 * the frame; doing it again fences a buffer the GPU is still reading and stalls the render
 * thread on the busy frames.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public interface RenderSectionManagerAccessor {

	@Accessor("needsRenderListUpdate")
	void vitrail$setNeedsRenderListUpdate(boolean value);

	@Accessor("frame")
	int vitrail$getFrame();

	@Accessor("frame")
	void vitrail$setFrame(int frame);

	@Accessor("cameraChanged")
	boolean vitrail$cameraChanged();

	@Invoker("invalidateRenderLists")
	void vitrail$invalidateRenderLists();
}
