package dev.vitrail.render;

import com.mojang.blaze3d.textures.GpuTexture;

/**
 * The Vulkan equivalent of {@code glGenerateMipmap}: fill every level past the base by blitting
 * each into the next, on the frame's command buffer, with one barrier at the end. The extents
 * are floored, which is what lets a chain run to one texel on its longer side: the game's own
 * per-level sizes shift without flooring, and it refuses a render pass on a level whose shorter
 * side reaches nought.
 * <p>
 * Implemented on the command encoder backend. The public encoder has no such method, which is why
 * this is a duck typed onto it.
 */
public interface MipmapCommands {

	/**
	 * @return true when the chain was filled, false when this backend cannot or a pass is open,
	 *         in which case the chain is left as it was
	 */
	boolean vitrail$generateMipmaps(GpuTexture texture);

	/**
	 * Gives the pass open on this encoder a viewport of the size said, from the origin.
	 * <p>
	 * The game sets a pass's viewport from the attachment's extent shifted by its level, without
	 * flooring it, and offers no call that moves it afterwards. The last levels of a chain that
	 * runs to one texel on the longer side have a shorter side that shifts to nought, and a draw
	 * into one of those rasterises nothing under the viewport the game set.
	 *
	 * @return false when no pass is open on this encoder or this backend cannot, in which case
	 *         the level keeps the viewport it was given
	 */
	boolean vitrail$viewport(int width, int height);
}
