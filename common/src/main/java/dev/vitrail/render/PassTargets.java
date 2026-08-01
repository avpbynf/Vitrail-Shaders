package dev.vitrail.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;

/**
 * The two intermediate targets the chain writes into and reads back from.
 * <p>
 * The targets themselves are kept from one frame to the next; their texture views are not.
 * A resize destroys and recreates both textures, and nothing on the Vulkan backend checks
 * whether a bound view is still alive, so a view held across a resize is a silent
 * use-after-free rather than an exception. Views are therefore looked up again every frame,
 * right before they are used.
 */
final class PassTargets {

	// The main target is RGBA8_UNORM and the last pass draws into it, so every pipeline in
	// the chain has to declare that format. Using one format throughout means one pipeline
	// per pass instead of one per pass and per format.
	private static final GpuFormat FORMAT = GpuFormat.RGBA8_UNORM;

	private TextureTarget first;
	private TextureTarget second;

	/**
	 * Makes both targets exist at the given size, allocating or resizing as needed. Must be
	 * called on the render thread and outside of any render pass: creating a texture records
	 * a barrier into the current command buffer.
	 *
	 * @return false when nothing usable could be prepared, in which case nothing may be drawn
	 */
	boolean ensureSize(int width, int height) {
		if (width <= 0 || height <= 0) {
			return false;
		}

		if (this.first == null) {
			// No depth. None of the passes reads or writes it, and a depth attachment would
			// add one more size that has to agree with the colour one.
			this.first = new TextureTarget("Vitrail target A", width, height, false, FORMAT);
			this.second = new TextureTarget("Vitrail target B", width, height, false, FORMAT);
		} else if (this.first.width != width || this.first.height != height) {
			this.first.resize(width, height);
			this.second.resize(width, height);
		}

		return true;
	}

	TextureTarget first() {
		return this.first;
	}

	TextureTarget second() {
		return this.second;
	}

	/**
	 * Releases both targets. Nothing in the game notices a render target that is never
	 * released, so this has to be called from the client shutdown event by hand.
	 */
	void close() {
		if (this.first != null) {
			this.first.destroyBuffers();
			this.first = null;
		}

		if (this.second != null) {
			this.second.destroyBuffers();
			this.second = null;
		}
	}
}
