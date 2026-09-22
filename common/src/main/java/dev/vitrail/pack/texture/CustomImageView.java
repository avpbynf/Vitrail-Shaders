package dev.vitrail.pack.texture;

import dev.vitrail.pack.model.TargetFormat;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A typed view of a custom image, carried in its translated resource name.
 * The name survives the translation and module caches, so a warm load binds the same view as a
 * cold one without relying on a side effect of translation. The image itself keeps its identity.
 */
public record CustomImageView(String original, TargetFormat format) {

	private static final String PREFIX = "ofCustomImageView_";
	private static final List<TargetFormat> FORMATS = Arrays.stream(TargetFormat.values())
			.sorted(Comparator.comparingInt((TargetFormat format) -> CustomImages.glslLayout(format).length())
					.reversed()).toList();

	public String name() {
		return PREFIX + CustomImages.glslLayout(this.format) + "_" + this.original;
	}

	public static Optional<CustomImageView> parse(String name) {
		if (!name.startsWith(PREFIX)) {
			return Optional.empty();
		}
		// Match the whole format prefix: snorm names contain an underscore themselves.
		for (TargetFormat format : FORMATS) {
			String head = PREFIX + CustomImages.glslLayout(format) + "_";
			if (name.startsWith(head) && name.length() > head.length()) {
				return Optional.of(new CustomImageView(name.substring(head.length()), format));
			}
		}
		return Optional.empty();
	}

	/** All formats here are uncompressed colour formats; their compatibility class is their size. */
	public static boolean compatible(TargetFormat base, TargetFormat view) {
		return base.bytesPerPixel() == view.bytesPerPixel();
	}

	/** The format the shader asks for, or empty when the base view already has the right type. */
	public static Optional<TargetFormat> requested(TargetFormat base, String type, String layout) {
		if (layout != null && !layout.isEmpty()) {
			return Arrays.stream(TargetFormat.values())
					.filter(format -> CustomImages.glslLayout(format).equals(layout)).findFirst();
		}
		String suffix = type.startsWith("uimage") || type.startsWith("usampler") ? "_UINT"
				: type.startsWith("iimage") || type.startsWith("isampler") ? "_SINT" : "";
		if (suffix.isEmpty() || base.name().endsWith(suffix)) {
			return Optional.empty();
		}
		// Integer samplers have no GLSL layout qualifier. Keep their channel widths and signedness;
		// this is a view of the bits, never a conversion from floats to integer values.
		return Arrays.stream(TargetFormat.values())
				.filter(format -> format.name().endsWith(suffix)
						&& format.components() == base.components() && compatible(base, format))
				.findFirst();
	}
}
