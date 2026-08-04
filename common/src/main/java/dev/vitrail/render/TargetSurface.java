package dev.vitrail.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.util.Mth;

/**
 * One colour target of a pack: its texture, the view a sampler reads it whole through, and one view
 * per mip level for the reduction to draw into.
 * <p>
 * This exists because {@link com.mojang.blaze3d.pipeline.TextureTarget} cannot express a mip chain.
 * {@code RenderTarget.createBuffers} calls {@code createTexture} with a level count of one, hard
 * coded, and offers no way to ask for more; a target a pack reads at a lod therefore cannot be one.
 * Everything else it did for us was one texture, one view and a resize, which is what is here.
 * <p>
 * The level count is a property of the target rather than of a program: several programs may read
 * the same target at a lod, and the chain that serves them is one. It is taken from the SMALLER
 * dimension, {@code log2(min(width, height)) + 1}, and that is a correction rather than a choice.
 * The device would accept a chain as long as the larger dimension allows, but
 * {@link GpuTexture#getWidth} shifts without clamping, and the render pass that writes a level
 * takes its area straight from that shift: on an ultrawide screen a chain sized on the width
 * reaches a level whose height shifts to nought, and the pass that would fill it is asked to cover
 * nothing. Sized on the smaller dimension every level has both extents at one texel or more.
 * <p>
 * What that costs is nothing the packs can feel: on 2560x1080 the last level is two texels by one
 * instead of one by one. BSL's automatic exposure asks for
 * {@code log2(viewHeight * AUTO_EXPOSURE_RADIUS)}, which is bounded by the height, so the level it
 * lands on exists either way.
 */
final class TargetSurface implements AutoCloseable {

	/** Copied from what the game asks for its own targets: sampled, drawn into, and copied both ways. */
	private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
			| GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

	private final String label;
	private final GpuFormat format;
	private final boolean mipped;

	private GpuTexture texture;
	private GpuTextureView view;

	/**
	 * One view per level, each covering a single level, for a render pass to attach. Null for a
	 * surface with no chain, where the only attachment is {@link #view}.
	 */
	private GpuTextureView[] levelViews;

	private int width;
	private int height;

	/**
	 * @param mipped whether this target is read at a lod by any program of the place, which is what
	 *               decides that it costs a chain. A target nothing samples that way carries one
	 *               level and one view, exactly as before there were chains at all
	 */
	TargetSurface(String label, GpuFormat format, boolean mipped, int width, int height) {
		this.label = label;
		this.format = format;
		this.mipped = mipped;
		allocate(width, height);
	}

	/** The chain is as long as the smaller dimension allows, and one level when nothing reads a lod. */
	static int levelsFor(boolean mipped, int width, int height) {
		return mipped ? Mth.log2(Math.min(width, height)) + 1 : 1;
	}

	int width() {
		return this.width;
	}

	int height() {
		return this.height;
	}

	int levels() {
		return this.texture == null ? 0 : this.texture.getMipLevels();
	}

	GpuTexture texture() {
		return this.texture;
	}

	/** What level nought costs, which is what this surface would have cost with no chain at all. */
	long baseBytes() {
		return (long) this.width * this.height * this.format.blockSize();
	}

	/**
	 * What the whole surface costs, chain included. Summed level by level rather than taken as the
	 * four thirds a full chain tends towards: the levels floor at one texel, so the tail of a target
	 * that is not square is heavier than the ratio suggests.
	 */
	long bytes() {
		long texels = 0L;
		for (int level = 0; level < levels(); level++) {
			texels += (long) Math.max(1, this.width >> level) * Math.max(1, this.height >> level);
		}

		return texels * this.format.blockSize();
	}

	/** The whole chain, which is what a sampler binds: a lod read past the base needs the levels in view. */
	GpuTextureView view() {
		return this.view;
	}

	/**
	 * One level on its own, for the reduction to draw into. A render pass attaches a view and writes
	 * whatever it covers, so a view of one level is how a single level is written without touching
	 * the rest of the chain.
	 */
	GpuTextureView levelView(int level) {
		if (this.levelViews == null || level < 0 || level >= this.levelViews.length) {
			return null;
		}

		return this.levelViews[level];
	}

	/**
	 * Makes this surface exist at the given size, reallocating when it moved.
	 *
	 * @return true when anything was allocated, which is the caller's signal that a clear is owed
	 */
	boolean resize(int width, int height) {
		if (this.texture != null && this.width == width && this.height == height) {
			return false;
		}

		close();
		allocate(width, height);

		return true;
	}

	private void allocate(int width, int height) {
		GpuDevice device = RenderSystem.getDevice();
		int levels = levelsFor(this.mipped, width, height);

		this.width = width;
		this.height = height;
		this.texture = device.createTexture(this.label, USAGE, this.format, width, height, 1, levels);
		this.view = device.createTextureView(this.texture);

		if (levels > 1) {
			this.levelViews = new GpuTextureView[levels];
			for (int level = 0; level < levels; level++) {
				this.levelViews[level] = device.createTextureView(this.texture, level, 1);
			}
		}
	}

	/**
	 * Frees the texture and every view onto it. The views go first: closing a texture does not close
	 * the views onto it, and nothing on the Vulkan backend checks that a bound view is still alive.
	 */
	@Override
	public void close() {
		if (this.levelViews != null) {
			for (GpuTextureView levelView : this.levelViews) {
				if (levelView != null) {
					levelView.close();
				}
			}

			this.levelViews = null;
		}

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
