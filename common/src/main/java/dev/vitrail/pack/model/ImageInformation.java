package dev.vitrail.pack.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

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

	/**
	 * One {@code image.NAME} value, Iris's word counts, or a reason this line cannot be kept.
	 * <p>
	 * Returns null when the image was added. A size that is not a number is looked up in
	 * {@code defines}, which is the substitute for Iris running the preprocessor over the file
	 * before this parser sees a token.
	 */
	public static String parse(String name, String value, Map<String, String> defines,
			List<ImageInformation> images) {
		String[] parts = value.split(" ", -1);
		if (parts.length < 6) {
			return "expected at least six words";
		}

		Optional<String> sampler = parts[0].isEmpty() || parts[0].equalsIgnoreCase("none")
				? Optional.empty()
				: Optional.of(parts[0]);
		Optional<PixelFormat> format = PixelFormat.parse(parts[1]);
		TargetFormat.Resolution internal = TargetFormat.resolve(parts[2]);
		Optional<PixelType> pixelType = PixelType.parse(parts[3]);
		if (format.isEmpty() || pixelType.isEmpty()
				|| internal.reason() == TargetFormat.Reason.UNKNOWN) {
			return "format " + parts[1] + " internal " + parts[2] + " pixel type " + parts[3];
		}

		boolean clear = Boolean.parseBoolean(parts[4]);
		boolean relative = Boolean.parseBoolean(parts[5]);
		PackTexture.Shape shape;
		int width;
		int height;
		int depth;
		float relativeWidth = 0;
		float relativeHeight = 0;
		if (relative) {
			if (parts.length != 8) {
				return "a relative image takes two size words";
			}

			try {
				relativeWidth = Float.parseFloat(parts[6]);
				relativeHeight = Float.parseFloat(parts[7]);
			} catch (NumberFormatException e) {
				return "relative size is not a number";
			}

			shape = PackTexture.Shape.TEXTURE_2D;
			width = 0;
			height = 0;
			depth = 0;
		} else if (parts.length == 7) {
			OptionalInt size = dimension(parts[6], defines);
			if (size.isEmpty()) {
				return "size is not a number";
			}

			shape = PackTexture.Shape.TEXTURE_1D;
			width = size.getAsInt();
			height = 0;
			depth = 0;
		} else if (parts.length == 8) {
			OptionalInt sizeX = dimension(parts[6], defines);
			OptionalInt sizeY = dimension(parts[7], defines);
			if (sizeX.isEmpty() || sizeY.isEmpty()) {
				return "size is not a number";
			}

			shape = PackTexture.Shape.TEXTURE_2D;
			width = sizeX.getAsInt();
			height = sizeY.getAsInt();
			depth = 0;
		} else if (parts.length == 9) {
			OptionalInt sizeX = dimension(parts[6], defines);
			OptionalInt sizeY = dimension(parts[7], defines);
			OptionalInt sizeZ = dimension(parts[8], defines);
			if (sizeX.isEmpty() || sizeY.isEmpty() || sizeZ.isEmpty()) {
				return "size is not a number";
			}

			shape = PackTexture.Shape.TEXTURE_3D;
			width = sizeX.getAsInt();
			height = sizeY.getAsInt();
			depth = sizeZ.getAsInt();
		} else {
			return "unknown image type";
		}

		images.add(new ImageInformation(name, sampler, shape, format.get(), internal,
				pixelType.get(), width, height, depth, clear, relative, relativeWidth,
				relativeHeight));

		return null;
	}


	/** A size token, or the same name looked up in the pack's settings when it is not a number. */
	private static OptionalInt dimension(String token, Map<String, String> defines) {
		try {
			return OptionalInt.of(Integer.parseInt(token));
		} catch (NumberFormatException e) {
			String value = defines.get(token);
			if (value == null) {
				return OptionalInt.empty();
			}

			try {
				return OptionalInt.of(Integer.parseInt(value.trim()));
			} catch (NumberFormatException ignored) {
				return OptionalInt.empty();
			}
		}
	}
}
