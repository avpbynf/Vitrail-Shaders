package dev.vitrail.render;

import com.mojang.blaze3d.textures.GpuTexture;

/**
 * The Vulkan equivalent of {@code glGenerateMipmap}: fill every level past the base by blitting
 * each into the next, on the frame's command buffer, with one barrier at the end rather than a
 * render pass per level.
 * <p>
 * Implemented on the command encoder backend. The public encoder has no such method, which is why
 * this is a duck typed onto it.
 */
public interface MipmapCommands {

	/**
	 * @return true when the chain was filled, false when this backend cannot, in which case the
	 *         caller keeps the draw-based reduction
	 */
	boolean vitrail$generateMipmaps(GpuTexture texture);
}
