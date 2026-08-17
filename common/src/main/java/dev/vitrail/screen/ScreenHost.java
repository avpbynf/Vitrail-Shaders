package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuValues;
import dev.vitrail.pack.source.PackLang;

import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * What a pack's settings page needs from the screen holding it, so that the page and the screen can
 * be written without one waiting on the other.
 * <p>
 * Iris hands its screen straight to every widget and reaches five of its members, plus a static
 * {@code Set<Runnable>} on the screen class for the tooltips that must be drawn last
 * ({@code ShaderPackScreen.TOP_LAYER_RENDER_QUEUE}), plus a {@code NavigationController} that is a
 * deque and two delegations. Naming those is the whole of this interface: the page walks pages
 * through {@link #openPage} and {@link #back} rather than through a controller of its own, which is
 * where this project's screen already kept its history.
 */
public interface ScreenHost {

	MenuValues values();

	PackLang lang();

	/** Re-reads every widget on the page from the values. One click can move ten widgets. */
	void refresh();

	void openPage(String name);

	/** One step up: the page walked in from, or the pack list once there is none left. */
	void back();

	/**
	 * Whether the comment panel is up. A widget whose name had to be cut offers the whole of it as a
	 * tooltip, and that tooltip would be drawn over the panel saying the same thing.
	 */
	boolean showingComment();

	/**
	 * Says which cell the mouse is over, which is what decides the comment panel's contents. Told
	 * every frame by the row, for the cell under the mouse and for the ones that are not.
	 */
	void hovered(PageWidget widget, boolean hovered);

	/**
	 * Draws after every list entry, for a tooltip that would otherwise be covered by the row below the
	 * one it belongs to.
	 */
	void onTop(Runnable draw);

	/** The line under the title, for five seconds. */
	void announce(Component message);

	/** This pack's settings file, which is where import and export both start. */
	Path settingsFile();

	/** Empties this pack's settings and applies, which is what the reset button does. */
	void resetSettings();

	/** Reads a settings file the player chose and queues everything in it. */
	void importSettings(Path file);
}
