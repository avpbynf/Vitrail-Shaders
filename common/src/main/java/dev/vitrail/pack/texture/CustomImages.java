package dev.vitrail.pack.texture;

import dev.vitrail.pack.model.ImageInformation;
import dev.vitrail.pack.model.TargetFormat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The storage images the loaded pack declared.
 * <p>
 * Complementary gates voxel lighting on {@code IRIS_FEATURE_CUSTOM_IMAGES}, and the define is
 * posed because the pipe behind it is there: the allocation, the 3D bind and the shadow compute
 * dispatch, which is what a pack reading the symbol goes on to use.
 */
public final class CustomImages {

	/**
	 * Every word GLSL takes as an image format, which is what {@link #glslLayout} spells and so
	 * exactly the formats a target can be given. Read to tell a format apart from the other things
	 * a {@code layout} carries, {@code binding} and {@code set} and their values.
	 */
	private static final Set<String> LAYOUT_FORMATS = Arrays.stream(TargetFormat.values())
			.map(CustomImages::glslLayout)
			.collect(Collectors.toUnmodifiableSet());

	private static volatile Map<String, ImageInformation> byName = Map.of();
	private static volatile Set<String> names = Set.of();

	private CustomImages() {
	}

	/** Whether this word names an image format, as opposed to anything else a layout carries. */
	public static boolean isLayoutFormat(String word) {
		return LAYOUT_FORMATS.contains(word);
	}

	/** Records the live {@code image.NAME} lines of the pack about to be translated. */
	public static void install(ImageInformation.Reading reading) {
		Map<String, ImageInformation> images = new LinkedHashMap<>();
		for (ImageInformation image : reading.images()) {
			images.put(image.name(), image);
			image.sampler().ifPresent(sampler -> images.putIfAbsent(sampler, image));
		}

		byName = Map.copyOf(images);
		names = namesOf(reading);
	}

	public static void clear() {
		byName = Map.of();
		names = Set.of();
	}

	/**
	 * Every name an {@code image.} directive hangs on, the image and the sampler that reads the
	 * same volume, read straight off the directives.
	 * <p>
	 * This is what {@link #named} answers once {@link #install} has run, and it is exposed so that
	 * a caller needing the answer BEFORE that asks the same question of the same text instead of
	 * asking a registry that still holds the pack before. A target plan is built while the registry
	 * is another pack's, and a name read as unserved there and as an image here is a target
	 * allocated for nobody, or a name handed a colour target the plan never opened.
	 */
	public static Set<String> namesOf(ImageInformation.Reading reading) {
		Set<String> named = new LinkedHashSet<>();
		for (ImageInformation image : reading.images()) {
			named.add(image.name());
			image.sampler().ifPresent(named::add);
		}

		return Set.copyOf(named);
	}

	/** Image name or sampler name hanging off an {@code image.} directive. */
	public static boolean named(String name) {
		return names.contains(name);
	}

	/** The same names as a set, for a reader that asks about more than one of them. */
	public static Set<String> names() {
		return names;
	}

	/** The image uniform itself, as opposed to the sampler that reads the same volume. */
	public static boolean storage(String name) {
		ImageInformation image = byName.get(name);
		return image != null && image.name().equals(name);
	}

	/**
	 * The GLSL layout format a storage image declaration needs on Vulkan, where Iris's GL bind
	 * supplies the format at bind time and the pack often writes none.
	 */
	public static Optional<String> layoutFormat(String name) {
		ImageInformation image = byName.get(name);
		if (image == null || !image.name().equals(name)) {
			return Optional.empty();
		}

		return Optional.of(glslLayout(image.internalFormat().used()));
	}

	public static Optional<ImageInformation> image(String name) {
		return Optional.ofNullable(byName.get(name));
	}

	static String glslLayout(TargetFormat format) {
		return switch (format) {
			case R8_UNORM -> "r8";
			case R8_SNORM -> "r8_snorm";
			case RG8_UNORM -> "rg8";
			case RG8_SNORM -> "rg8_snorm";
			case RGBA8_UNORM -> "rgba8";
			case RGBA8_SNORM -> "rgba8_snorm";
			case R16_UNORM -> "r16";
			case R16_SNORM -> "r16_snorm";
			case RG16_UNORM -> "rg16";
			case RG16_SNORM -> "rg16_snorm";
			case RGBA16_UNORM -> "rgba16";
			case RGBA16_SNORM -> "rgba16_snorm";
			case R8_UINT -> "r8ui";
			case R8_SINT -> "r8i";
			case RG8_UINT -> "rg8ui";
			case RG8_SINT -> "rg8i";
			case RGBA8_UINT -> "rgba8ui";
			case RGBA8_SINT -> "rgba8i";
			case R16_UINT -> "r16ui";
			case R16_SINT -> "r16i";
			case RG16_UINT -> "rg16ui";
			case RG16_SINT -> "rg16i";
			case RGBA16_UINT -> "rgba16ui";
			case RGBA16_SINT -> "rgba16i";
			case R32_UINT -> "r32ui";
			case R32_SINT -> "r32i";
			case RG32_UINT -> "rg32ui";
			case RG32_SINT -> "rg32i";
			case RGBA32_UINT -> "rgba32ui";
			case RGBA32_SINT -> "rgba32i";
			case R16_FLOAT -> "r16f";
			case RG16_FLOAT -> "rg16f";
			case RGBA16_FLOAT -> "rgba16f";
			case R32_FLOAT -> "r32f";
			case RG32_FLOAT -> "rg32f";
			case RGBA32_FLOAT -> "rgba32f";
			case RGB10A2_UNORM -> "rgb10_a2";
			case RGB10A2_UINT -> "rgb10_a2ui";
			case RG11B10_FLOAT -> "r11f_g11f_b10f";
		};
	}
}
