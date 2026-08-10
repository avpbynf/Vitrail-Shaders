package dev.vitrail.pack.target;

import java.util.Map;
import java.util.Optional;

/**
 * What {@code size.buffer.<name>} asks a target to be.
 * <p>
 * The rule is Iris's and it has one surprise in it: a value that contains a dot is a fraction of
 * the screen and a value without one is a count of pixels, so {@code 0.5 0.5} is half the window
 * and {@code 960 540} is nine hundred and sixty pixels whatever the window does. The two are
 * told apart by punctuation and by nothing else.
 * <p>
 * The other surprise is that the value need not be a number at all. Complementary writes
 * {@code size.buffer.colortex1 = REFLECTION_RES REFLECTION_RES}, and {@code REFLECTION_RES} is
 * one of its own settings, declared {@code 0.5} by default. Iris preprocesses the properties
 * file with the settings resolved before it reads the key, so the substitution has to happen
 * here or four of the five lines in the corpus fall back to full screen without a word. Only
 * Mellow's reads as it stands.
 * <p>
 * One divergence from Iris, deliberate: it lets the two axes disagree about being relative, and
 * this does not, because the answer is one flag. No pack in the corpus mixes them; one that did
 * would get a full sized target and a note rather than a wrong guess.
 */
public record TargetSize(boolean relative, float width, float height) {

	/** How far a setting may point at another setting before the chain is called a loop. */
	private static final int MAX_SUBSTITUTIONS = 8;

	/**
	 * How wide a side of a target this engine will allocate, the same ceiling the shadow map keeps
	 * and for the same reason: it is what Vulkan guarantees of a 2D image, and the number is the
	 * pack's own with nothing between it and the allocator. {@code size.buffer.colortex0 = 100000
	 * 100000} asks for forty gigabytes on one target, and the whole corpus writes fractions.
	 */
	public static final int MAX_DIMENSION = 16384;

	public static TargetSize ofScreen() {
		return new TargetSize(true, 1.0F, 1.0F);
	}

	/**
	 * @param value   the two tokens as written in shaders.properties
	 * @param defines the settings already resolved, because a pack writes
	 *                {@code size.buffer.colortex1 = REFLECTION_RES REFLECTION_RES}
	 */
	public static Optional<TargetSize> parse(String value, Map<String, String> defines) {
		String[] parts = value.trim().split("\\s+", -1);
		if (parts.length != 2) {
			return Optional.empty();
		}

		String horizontal = substitute(parts[0], defines);
		String vertical = substitute(parts[1], defines);
		boolean relative = horizontal.contains(".");
		if (relative != vertical.contains(".")) {
			return Optional.empty();
		}

		try {
			return Optional.of(new TargetSize(relative, Float.parseFloat(horizontal),
					Float.parseFloat(vertical)));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	public int width(int screenWidth) {
		return scale(screenWidth, this.width);
	}

	public int height(int screenHeight) {
		return scale(screenHeight, this.height);
	}

	public boolean full() {
		return this.relative && this.width == 1.0F && this.height == 1.0F;
	}

	private int scale(int screen, float value) {
		// Never zero: a target of no pixels is refused by the allocator rather than ignored, and
		// a pack asking for a twentieth of a small window is one rounding away from it. Never
		// larger than MAX_DIMENSION either, and in long, because the float this multiplies has no
		// bound of its own and an allocation that fails takes every other target down with it.
		return Math.clamp(this.relative ? (long) (screen * value) : (long) value, 1, MAX_DIMENSION);
	}

	/**
	 * Whether the pack asked for more than {@link #MAX_DIMENSION} outright, so that the plan can say
	 * the target is served smaller than it was written. A relative size is not weighed here: it is
	 * measured against a window the device has already allocated, and a pack writing a multiple of
	 * the screen rather than a fraction of it is named by the note that prints the factor.
	 */
	public boolean overCap() {
		return !this.relative && (this.width > MAX_DIMENSION || this.height > MAX_DIMENSION);
	}

	private static String substitute(String token, Map<String, String> defines) {
		String current = token.trim();
		for (int step = 0; step < MAX_SUBSTITUTIONS; step++) {
			String next = defines.get(current);
			if (next == null) {
				return current;
			}

			current = next.trim();
		}

		return current;
	}
}
