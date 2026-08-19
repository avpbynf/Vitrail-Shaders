package dev.vitrail.pack.program;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What a pack's {@code blend.<program>} directive asks for, as the pack spells it.
 * <p>
 * Held as the four factor NAMES rather than as anything of the renderer, because this package is
 * kept free of every engine API: that is what lets the whole of it run against the corpus without
 * starting the game. The names are GL's own and the backend's enum spells them the same way, so
 * the translation on the other side is a lookup rather than a table of equivalences.
 * <p>
 * OptiFine's form is {@code blend.<program>=<srcRgb> <dstRgb> <srcAlpha> <dstAlpha>}, or the word
 * {@code off} for a program that must not blend at all. Two factors are accepted as well and mean
 * what {@code glBlendFunc} means: the same pair for colour and for alpha.
 * <p>
 * The per buffer form, {@code blend.<program>.<buffer>}, is NOT this: one pipeline carries one
 * blend function for every target it writes, so a pack that asks for different blending per draw
 * buffer is named in the plan's notes instead of being half honoured.
 */
public record BlendMode(boolean off, String srcRgb, String dstRgb, String srcAlpha, String dstAlpha) {

	/** What a program blends with when it says nothing, which is what the game would have used. */
	public static final BlendMode OFF = new BlendMode(true, "", "", "", "");

	/**
	 * Reads one directive's value, or empty when it is a form this engine does not express.
	 * <p>
	 * Empty is not the same as {@link #OFF} and the difference matters: off is a pack saying do not
	 * blend, and empty is this engine saying it did not understand, which the caller reports rather
	 * than silently treating as either.
	 */
	public static Optional<BlendMode> parse(String value) {
		if (value == null) {
			return Optional.empty();
		}

		String trimmed = value.trim();
		if (trimmed.equalsIgnoreCase("off")) {
			return Optional.of(OFF);
		}

		// One space, the separator Iris gives the blend directives, and not a run of whitespace: a
		// double space between two factors leaves an empty word between them there, so the value
		// stops being four factors and the override is dropped.
		List<String> factors = List.of(trimmed.toUpperCase(Locale.ROOT).split(" ", -1));
		if (factors.size() == 4) {
			return Optional.of(new BlendMode(false, factors.get(0), factors.get(1), factors.get(2),
					factors.get(3)));
		}

		// glBlendFunc's two argument form: one pair, used for the colour and for the alpha alike.
		if (factors.size() == 2) {
			return Optional.of(new BlendMode(false, factors.get(0), factors.get(1), factors.get(0),
					factors.get(1)));
		}

		return Optional.empty();
	}

	/** The four names in the order a reader of the pack's own line would expect them. */
	@Override
	public String toString() {
		return this.off ? "off" : this.srcRgb + " " + this.dstRgb + " " + this.srcAlpha + " "
				+ this.dstAlpha;
	}
}
