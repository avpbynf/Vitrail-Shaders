package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuValues;
import dev.vitrail.pack.source.PackLang;

/**
 * The little the widgets need to know about the screen holding them, so that the two can be
 * written without one waiting on the other.
 */
public interface ScreenHost {

	MenuValues values();

	PackLang lang();

	/** Re-reads every widget on the page from the values. One click can move ten widgets. */
	void refresh();

	void openPage(String name);
}
