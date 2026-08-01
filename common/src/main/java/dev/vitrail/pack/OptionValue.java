package dev.vitrail.pack;

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

	@Override
	public String toString() {
		return this.bool ? Boolean.toString(this.enabled) : this.text;
	}
}
