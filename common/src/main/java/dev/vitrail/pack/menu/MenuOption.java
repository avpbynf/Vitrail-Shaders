package dev.vitrail.pack.menu;

import dev.vitrail.pack.PackOption;

import java.util.ArrayList;
import java.util.List;

/**
 * A setting as the screen sees it, with the questions a widget must not have to ask already
 * answered: which of the three shapes it is, and what it is allowed to hold.
 * <p>
 * The allowed values always contain the pack's own default, added when the pack forgot it.
 * Thirty eight declarations in the corpus offer a list their own default is not in, and without
 * this the value the pack ships cannot be reached by clicking. It is added here and nowhere
 * else: {@link dev.vitrail.pack.OptionIndex} reports what a pack wrote, and adding it there as
 * well would put the same value in the cycle twice.
 */
public record MenuOption(String name, Form form, String defaultValue, List<String> values,
		boolean slider) {

	private static final List<String> TOGGLE_VALUES = List.of("on", "off");

	public enum Form {
		/** A bare {@code #define}. The values are always exactly {@code on} and {@code off}. */
		TOGGLE,
		/** A value with at least two allowed values to walk through. */
		CYCLE,
		/**
		 * A value with fewer than two allowed values: shown, greyed, not changeable from here.
		 * All twenty two of these in the corpus carry a one value list, {@code [0]}, and are
		 * headings a pack writes as a setting: ABOUT in BSL, info0 to info10 in Complementary,
		 * INFO and PBR_INFORMATION in Mellow, INFO in Reverie.
		 */
		FIXED
	}

	public MenuOption {
		values = List.copyOf(values);
	}

	/**
	 * A {@code const} declaration is a value like any other here; only a bare {@code #define} is
	 * a toggle.
	 *
	 * @param slider whether {@code sliders=} names it. Honoured only for a {@link Form#CYCLE}.
	 */
	public static MenuOption of(PackOption option, boolean slider) {
		if (option.kind() == PackOption.Kind.TOGGLE) {
			return new MenuOption(option.name(), Form.TOGGLE, option.defaultOff() ? "off" : "on",
					TOGGLE_VALUES, false);
		}

		List<String> values = new ArrayList<>(option.values());
		if (!values.isEmpty() && !values.contains(option.defaultText())) {
			values.add(option.defaultText());
		}

		Form form = values.size() < 2 ? Form.FIXED : Form.CYCLE;

		return new MenuOption(option.name(), form, option.defaultText(), values,
				slider && form == Form.CYCLE);
	}

	/** Where a value sits, or the index of the default when it sits nowhere. Never -1. */
	public int indexOf(String value) {
		int found = this.values.indexOf(value);
		if (found >= 0) {
			return found;
		}

		return Math.max(0, this.values.indexOf(this.defaultValue));
	}

	/** The value at an index, wrapped with {@link Math#floorMod}, so cycling cannot fall off. */
	public String at(int index) {
		if (this.values.isEmpty()) {
			return this.defaultValue;
		}

		return this.values.get(Math.floorMod(index, this.values.size()));
	}

	public int size() {
		return this.values.size();
	}
}
