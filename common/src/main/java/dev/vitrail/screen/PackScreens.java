package dev.vitrail.screen;

import dev.vitrail.HostReport;

import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Which screen a door of this mod to the packs opens: this engine's own on Vulkan, and an offer to
 * switch to Vulkan on any other backend, Iris beside it or not.
 * <p>
 * Off Vulkan this engine draws nothing, so a pack picked or a setting moved on its screen would change
 * nothing on screen, and beside Iris would write files Iris never reads while the pack Iris draws
 * stayed where it was. The page of Sodium's video settings and the Config button of NeoForge's mod
 * list therefore open {@link BackendPlaceholder} there. That is Iris's own answer the other way round:
 * on Vulkan its Sodium page opens the same offer in place of its pack screen,
 * {@code IrisConfig.java:50-51}. The keys open nothing off Vulkan ({@link SettingsKey}).
 * <p>
 * The device is asked rather than the options: a launch argument forcing Vulkan over a file that asks
 * for OpenGL leaves Iris hooked for OpenGL and this engine drawing, and this engine's screen is then
 * the one that changes the picture.
 */
public final class PackScreens {

	private PackScreens() {
	}

	/** The screen to open over {@code parent}. */
	public static Screen open(@Nullable Screen parent) {
		if (HostReport.otherBackend()) {
			return new BackendPlaceholder(parent);
		}

		return new SettingsScreen(parent);
	}
}
