package dev.vitrail.fabric;

import dev.vitrail.screen.MenuButton;
import dev.vitrail.screen.SettingsKey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * The two ways into the settings screen, wired to Fabric API.
 * <p>
 * This is the only place in the mod that needs Fabric API at all, and it is where the bare game
 * offers nothing: a key mapping has to be appended to an array the options own, and a laid out
 * screen has to be handed over after the game has built it. Both are exactly what those two modules
 * do, so writing a mixin for either would be reimplementing them.
 * <p>
 * The list {@code Screens.getWidgets} hands back is the screen's own and is added to in place, so
 * measuring the row and adding the button both go through it.
 */
final class FabricMenuAndKey {

	private FabricMenuAndKey() {
	}

	static void register() {
		KeyMappingHelper.registerKeyMapping(SettingsKey.OPEN);
		ClientTickEvents.END_CLIENT_TICK.register(_ -> SettingsKey.pollAndOpen());

		ScreenEvents.AFTER_INIT.register((_, screen, _, _) -> {
			if (screen instanceof PauseScreen || screen instanceof TitleScreen) {
				MenuButton.add(Screens.getWidgets(screen), screen, Screens.getWidgets(screen)::add);
			}
		});
	}
}
