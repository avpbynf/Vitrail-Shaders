package dev.vitrail.pack.model;

import dev.vitrail.pack.model.TargetFormat;

import java.util.List;
import java.util.Optional;

/**
 * One {@code image.NAME} directive, as Iris reads it and before anything is allocated.
 * <p>
 * The grammar is Iris's, {@code ShaderProperties.java} around the {@code image.} handler: a
 * sampler name or {@code none}, a pixel format, an internal format, a pixel type, whether to
 * clear, whether the size is a fraction of the screen, then the dimensions. Sixteen is the
 * ceiling Iris enforces. Nothing here touches a device.
 *
 * @param sampler empty when the pack wrote {@code none}, so the image is stored and never sampled
 * @see <a href="https://github.com/IrisShaders/Iris">Iris, LGPL-3.0</a>
 */
public record ImageInformation(String name, Optional<String> sampler, PackTexture.Shape shape,
		PixelFormat pixelFormat, TargetFormat.Resolution internalFormat, PixelType pixelType,
		int width, int height, int depth, boolean clear, boolean relative, float relativeWidth,
		float relativeHeight) {

	/** Iris refuses a seventeenth. Complementary Ultra sits well under it. */
	public static final int LIMIT = 16;

	/**
	 * What {@link dev.vitrail.pack.source.ShaderProperties#imageDirectives} answered, including
	 * the lines that were live and could not be read.
	 */
	public record Reading(List<ImageInformation> images, List<String> dropped) {

		public Reading {
			images = List.copyOf(images);
			dropped = List.copyOf(dropped);
		}

		public static Reading empty() {
			return new Reading(List.of(), List.of());
		}
	}

	/** One clause for the log, saying the name, the sampler, the shape and the size. */
	public String describe() {
		String size = this.relative
				? this.relativeWidth + "x" + this.relativeHeight + " of the screen"
				: switch (this.shape) {
					case TEXTURE_1D -> Integer.toString(this.width);
					case TEXTURE_2D -> this.width + "x" + this.height;
					case TEXTURE_3D -> this.width + "x" + this.height + "x" + this.depth;
					case TEXTURE_RECTANGLE -> this.width + "x" + this.height;
				};

		return this.name
				+ this.sampler.map(sampler -> " as " + sampler).orElse("")
				+ " " + this.shape.name() + " " + this.internalFormat.declared() + " " + size
				+ (this.clear ? ", cleared each frame" : "");
	}
}
