package dev.vitrail.mixin;

import dev.vitrail.render.PackChain;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the world's depth before the game throws it away for the gizmos it draws over everything.
 * <p>
 * The 26.3 half. The gizmos a player sees through the terrain, a chunk border or a pathfinding
 * node, are drawn by {@code executeAlwaysOnTop}, at the tail of the main pass's runnable once the
 * world, its outlines and its see-through features are done and no pass is open. Where the frame
 * does not need a depth consistent across the frame, that method opens its pass on the main target
 * with the depth emptied to the far plane; this engine's copy of the scene's depth has to be taken
 * before that, at the head of the same method, for the reason the 26.2 half gives: a pack reading
 * the far plane over the whole screen draws a plausible picture and a false one. Where the frame does
 * need that depth, the gizmos go to a depth image of their own and the copy costs nothing it would
 * not have cost at the chain's own point.
 * <p>
 * The method runs only on the frames where something is drawn always on top, so on most frames this
 * never runs and the chain keeps the depth at its own point, which is then still whole.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	/**
	 * {@code require} written out where the config already defaults to it, because this is the hook
	 * whose failure to bind gives back exactly the picture it was written against.
	 */
	@Inject(method = "executeAlwaysOnTop", at = @At("HEAD"), require = 1)
	private void vitrail$scene(CallbackInfo callback) {
		PackChain.markSceneDepth();
	}
}
