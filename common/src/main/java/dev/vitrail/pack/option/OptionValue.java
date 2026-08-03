package dev.vitrail.pack.option;

import java.util.Locale;

/**
 * A value chosen for a setting, by a profile or by the player.
 * <p>
 * The difference between a boolean and a text value is not cosmetic. Turning a toggle on means
 * uncommenting its declaration and nothing else, while giving it text means rewriting the
 * declaration; and a toggle turned off produces no line at all rather than a line saying
 * false. Collapsing the two into a string loses that.
 */
public final class OptionValue {

	private static final OptionValue ON = new OptionValue(true, true, null);
	private static final OptionValue OFF = new OptionValue(true, false, null);

	private final boolean bool;
	private final boolean enabled;
	private final String text;

	private OptionValue(boolean bool, boolean enabled, String text) {
		this.bool = bool;
		this.enabled = enabled;
		this.text = text;
	}

	public static OptionValue on() {
		return ON;
	}

	public static OptionValue off() {
		return OFF;
	}

	public static OptionValue of(String text) {
		return new OptionValue(false, false, text);
	}

	/**
	 * Reads a value written as text. Only the four words {@code on}, {@code true}, {@code off} and
	 * {@code false} become booleans; a pack whose allowed values are {@code 0} and {@code 1} keeps
	 * them as text, because turning a toggle on and giving a setting the text "1" are two
	 * different edits to the pack's source.
	 * <p>
	 * A pack that writes {@code #define X true} is therefore uncommented rather than rewritten,
	 * which is what {@code options.txt} has done since it existed.
	 */
	public static OptionValue parse(String text) {
		String value = text.trim();

		return switch (value.toLowerCase(Locale.ROOT)) {
			case "on", "true" -> ON;
			case "off", "false" -> OFF;
			default -> of(value);
		};
	}

	public boolean isBoolean() {
		return this.bool;
	}

	public boolean asBoolean() {
		return this.enabled;
	}

	/** The chosen text, or null when this is a boolean. */
	public String text() {
		return this.text;
	}

	/**
	 * The inverse of {@link #parse(String)}, so that a screen can work in text and convert only at
	 * the edge. A boolean comes back as {@code on} or {@code off} rather than as true or false:
	 * both read back the same, and it is the pair a settings file is written with.
	 */
	public String asText() {
		if (!this.bool) {
			return this.text;
		}

		return this.enabled ? "on" : "off";
	}

	@Override
	public String toString() {
		return this.bool ? Boolean.toString(this.enabled) : this.text;
	}
}
