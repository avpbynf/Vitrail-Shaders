package dev.vitrail.pack.menu;

import java.util.List;

/**
 * One page, with the column count already resolved: what the pack asked for, or three past
 * eighteen slots and two below, which is Iris's rule and the one packs are laid out against.
 */
public record MenuPage(String name, List<MenuSlot> slots, int columns) {

	public MenuPage {
		slots = List.copyOf(slots);
	}
}
