package dev.vitrail.pack.option;

import java.util.HashSet;
import java.util.Set;

/**
 * The constants a pack is allowed to configure, and it is a closed list.
 * <p>
 * A {@code const} declaration is not a setting just because it carries a value list: the
 * reference only configures the names it knows the engine reads, twenty five of them plus
 * seven spellings generated per shadow color buffer ({@code OptionAnnotatedSource.java:55-96},
 * eight buffers per {@code PackShadowDirectives.java:19}). Every other constant is a plain
 * constant of the program, whatever its comment offers.
 * <p>
 * The line this draws is visible on screen: BSL declares {@code shadowMapBias} with a value
 * list, and the reference shows nothing for it. Treating every constant as a setting instead
 * put a slider there, made the name rewritable in place, and let it into the table
 * {@code shaders.properties} conditionals read, three things a pack author never saw happen.
 */
public final class ConstOptions {

	private static final Set<String> NAMES = names();

	private ConstOptions() {
	}

	/** Whether a {@code const} of this name is a setting rather than a plain constant. */
	public static boolean isOption(String name) {
		return NAMES.contains(name);
	}

	private static Set<String> names() {
		Set<String> names = new HashSet<>(Set.of(
				"shadowMapResolution",
				"shadowDistance",
				"voxelDistance",
				"shadowDistanceRenderMul",
				"entityShadowDistanceMul",
				"shadowIntervalSize",
				"generateShadowMipmap",
				"generateShadowColorMipmap",
				"shadowHardwareFiltering",
				"shadowtex0Mipmap",
				"shadowtexMipmap",
				"shadowtex1Mipmap",
				"shadowtex0Nearest",
				"shadowtexNearest",
				"shadow0MinMagNearest",
				"shadowtex1Nearest",
				"shadow1MinMagNearest",
				"wetnessHalflife",
				"drynessHalflife",
				"eyeBrightnessHalflife",
				"centerDepthHalflife",
				"sunPathRotation",
				"ambientOcclusionLevel",
				"superSamplingLevel",
				"noiseTextureResolution"));

		for (int i = 0; i < 8; i++) {
			names.add("shadowcolor" + i + "Mipmap");
			names.add("shadowColor" + i + "Mipmap");
			names.add("shadowcolor" + i + "Nearest");
			names.add("shadowColor" + i + "Nearest");
			names.add("shadowcolor" + i + "MinMagNearest");
			names.add("shadowColor" + i + "MinMagNearest");
			names.add("shadowHardwareFiltering" + i);
		}

		return Set.copyOf(names);
	}
}
