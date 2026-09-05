package dev.vitrail.render;

import dev.vitrail.pack.model.BlendMode;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Turns what a pack asked to blend with into what the backend takes.
 * <p>
 * The translation is a lookup and not a table of equivalences: OptiFine names its factors after
 * GL's own constants and {@link BlendFactor} spells them the same way, so {@code SRC_ALPHA} is
 * {@code SRC_ALPHA}. That is why the pack side carries the names as text and this side is the only
 * place that knows the enum, which keeps the reader of packs free of every engine API.
 * <p>
 * A name this backend has no constant for gives no blending rather than a guess, and is said once.
 * The two that GL has and Vulkan does not, the dual source factors, are the case this is for.
 */
final class BlendFunctions {

	/** Names already refused, so that a program drawn every frame says it once. */
	private static final Set<String> REFUSED = new LinkedHashSet<>();

	private BlendFunctions() {
	}

	/**
	 * The blend state for a program, or empty for one that must not blend.
	 * <p>
	 * Empty is also what an unreadable factor gives, and the two are not confused in the log: a
	 * pack asking for no blending is silent, and a pack asking for something this backend cannot
	 * express is named.
	 *
	 * @param mode what the pack asked for, or null when it asked for nothing, in which case the
	 *             caller's own default stands and this returns that default untouched
	 */
	static Optional<BlendFunction> of(BlendMode mode, Optional<BlendFunction> fallback) {
		if (mode == null) {
			return fallback;
		}

		if (mode.off()) {
			return Optional.empty();
		}

		BlendFactor srcRgb = factor(mode.srcRgb());
		BlendFactor dstRgb = factor(mode.dstRgb());
		BlendFactor srcAlpha = factor(mode.srcAlpha());
		BlendFactor dstAlpha = factor(mode.dstAlpha());
		if (srcRgb == null || dstRgb == null || srcAlpha == null || dstAlpha == null) {
			return fallback;
		}

		return Optional.of(new BlendFunction(srcRgb, dstRgb, srcAlpha, dstAlpha));
	}

	private static BlendFactor factor(String name) {
		try {
			return BlendFactor.valueOf(name);
		} catch (IllegalArgumentException e) {
			if (REFUSED.add(name)) {
				Vitrail.logger().warn("A pack asks to blend with {}, which this backend has no "
						+ "factor for, so the programs that ask for it keep the blending the engine "
						+ "would have used", name);
			}

			return null;
		}
	}
}
