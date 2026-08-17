package dev.vitrail.fabric;

import dev.vitrail.screen.SettingsKey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

/**
 * The key that opens the settings screen, wired to Fabric API.
 * <p>
 * This is the only place in the mod that needs Fabric API at all, and it is where the bare game
 * offers nothing: a key mapping has to be appended to an array the options own. That is exactly what
 * this module does, so writing a mixin for it would be reimplementing it.
 * <p>
 * What a press does is in {@link SettingsKey}, with the mapping itself, because NeoForge registers
 * the same one its own way.
 */
final class FabricKey {

	private FabricKey() {
	}

	static void register() {
		KeyMappingHelper.registerKeyMapping(SettingsKey.OPEN);
		ClientTickEvents.END_CLIENT_TICK.register(_ -> SettingsKey.pollAndOpen());
	}
}
