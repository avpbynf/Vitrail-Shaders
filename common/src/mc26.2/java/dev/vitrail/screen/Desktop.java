package dev.vitrail.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.nio.file.Path;

/**
 * What the settings screen asks of the desktop the game runs on: to show a folder in the system's
 * file browser, and whether the game's window covers the whole screen. Each is one call, and the two
 * games make that call under different names, which is the whole reason this class exists.
 * <p>
 * <b>This is the Minecraft 26.2 half.</b> A class of the same name with the same two methods sits
 * under {@code src/mc26.3/} for 26.3, and a build compiles exactly one of them. Each half makes the
 * call its own game makes for the same job, so the screen behaves like the game's own screens beside
 * it under either game. This half is the two calls the screen made itself before there were two
 * games, moved here unchanged.
 * <p>
 * Named for the desktop rather than for the platform because {@code Vitrail.platform()} already means
 * the mod loader everywhere else in this tree.
 */
public final class Desktop {

	private Desktop() {
	}

	/**
	 * Shows a folder in the system's file browser, the way the game's own "open pack folder" button
	 * does: {@code Util.getPlatform().openPath}, which hands the folder's address to the system.
	 */
	public static void openPath(Path path) {
		Util.getPlatform().openPath(path);
	}

	/**
	 * Whether the game's window covers the screen, asked of the window itself.
	 * <p>
	 * The window and not the video setting, because on 26.2 the two can disagree: fullscreen asked for
	 * on the command line puts the window there without touching the setting, and a fullscreen switch
	 * that finds no monitor to use leaves the window where it was and the setting on. The window is
	 * the one that is actually over the desktop, which is what the question is about.
	 */
	public static boolean fullscreen() {
		return Minecraft.getInstance().getWindow().isFullscreen();
	}
}
