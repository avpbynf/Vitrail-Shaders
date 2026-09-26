package dev.vitrail.screen;

import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * What the settings screen asks of the desktop the game runs on: to show a folder in the system's
 * file browser, and whether the game's window covers the whole screen. Each is one call, and the two
 * games make that call under different names, which is the whole reason this class exists.
 * <p>
 * <b>This is the Minecraft 26.3 half.</b> A class of the same name with the same two methods sits
 * under {@code src/mc26.2/} for 26.2, and a build compiles exactly one of them. Each half makes the
 * call its own game makes for the same job, so the screen behaves like the game's own screens beside
 * it under either game.
 * <p>
 * Named for the desktop rather than for the platform because {@code Vitrail.platform()} already means
 * the mod loader everywhere else in this tree.
 */
public final class Desktop {

	private Desktop() {
	}

	/**
	 * Shows a folder in the system's file browser, the way the game's own "open pack folder" button
	 * does. 26.3 took the call off {@code Util.getPlatform()} and put it on {@code Blaze3D}, which
	 * turns the folder into a {@code file:} address and hands it to SDL off the game's thread.
	 */
	public static void openPath(Path path) {
		Blaze3D.openPath(path);
	}

	/**
	 * Whether the game's window covers the screen, asked of the video setting.
	 * <p>
	 * 26.3's window no longer answers this in public; what it knows about it is private. What it does
	 * instead is keep the setting in step with itself: it writes the setting back whenever SDL reports
	 * that the window entered or left fullscreen, and whenever a switch it was asked for fails. So the
	 * setting is the game's own record of where the window is, a fullscreen asked for on the command
	 * line included: the window reports that one into the setting as soon as it has got there. Between
	 * a change of the setting and the frame that applies it, it says what was asked for, which is also
	 * what 26.2's window says in that same moment.
	 */
	public static boolean fullscreen() {
		return Minecraft.getInstance().options.fullscreen().get();
	}
}
