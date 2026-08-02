package dev.vitrail.pack.menu;

/**
 * One slot of a settings page. A pack lays its pages out by position, so a blank is as much a
 * part of the layout as an option is: the corpus holds seven hundred and seventy three of them
 * against nineteen hundred and forty two options, and dropping them would collapse every column
 * a pack aligned by hand.
 * <p>
 * Sealed rather than open, so that a form the screen forgets to draw is a compile error rather
 * than a blank square nobody notices.
 */
public sealed interface MenuSlot {

	record Blank() implements MenuSlot {
	}

	record Option(MenuOption option) implements MenuSlot {
	}

	/**
	 * A link to another page, written {@code [NAME]}. Pages are flat and joined by name.
	 *
	 * @param resolved whether that page exists. One link in the corpus points at a page that was
	 *                 never written, and it is shown greyed rather than dropped.
	 */
	record Link(String page, boolean resolved) implements MenuSlot {
	}

	record Profiles() implements MenuSlot {
	}
}
