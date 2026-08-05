package dev.vitrail.render;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * The depth of the opaque world, taken before anything translucent is drawn, which the OptiFine
 * model calls {@code depthtex1}.
 * <p>
 * A class of its own because it is the one image of a place that is neither a colour target nor the
 * shadow map: no directive stands behind it, it has no clear colour and no ping pong, and the moment
 * it is taken is a point of the frame rather than anything the plan carries. {@link ColorTargets}
 * holds it so that everything which binds a sampler already has it to hand.
 */
final class PackDepth {

	private GpuTexture texture;
	private GpuTextureView view;

	/**
	 * Copies the game's depth as it stands. Must run on the render thread, outside any render pass,
	 * and at the right moment of the frame, which is the caller's to know.
	 * <p>
	 * The copy carries the source's own format, taken from the texture rather than assumed: the
	 * game's depth is {@code D32_FLOAT} until a mod asks NeoForge for a stencil, and a depth copy
	 * with any other format than its source is refused outright by the encoder.
	 */
	void copy(CommandEncoder encoder, GpuTexture depth) {
		if (depth == null) {
			return;
		}

		int width = depth.getWidth(0);
		int height = depth.getHeight(0);
		if (this.texture != null && (this.texture.getWidth(0) != width
				|| this.texture.getHeight(0) != height
				|| this.texture.getFormat() != depth.getFormat())) {
			release();
		}

		if (this.texture == null) {
			this.texture = RenderSystem.getDevice().createTexture(() -> "Vitrail depthtex1",
					GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, depth.getFormat(),
					width, height, 1, 1);
			this.view = RenderSystem.getDevice().createTextureView(this.texture);
		}

		encoder.copyTextureToTexture(depth, this.texture, 0, 0, 0, 0, 0, width, height);
	}

	/**
	 * The copy {@link #copy} last took, or null before the first one. Looked up at every use like
	 * every other view of a place: a resize destroys and recreates it.
	 */
	GpuTextureView view() {
		return this.view;
	}

	/**
	 * Frees the image and the view onto it. The view goes first: closing a texture does not close
	 * the views onto it, and nothing on this backend notices one that has outlived its texture.
	 */
	void release() {
		if (this.view != null) {
			this.view.close();
			this.view = null;
		}

		if (this.texture != null) {
			this.texture.close();
			this.texture = null;
		}
	}
}
