package dev.vitrail.neoforge;

import dev.vitrail.screen.SettingsKey;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Registers {@link SettingsKey}, on the mod bus because that is the bus the event is posted on.
 * Asking it once a tick is the tick stage's business, reached from {@code VitrailNeoForge}.
 */
public final class VitrailKeys {

	private VitrailKeys() {
	}

	public static void register(IEventBus modBus) {
		modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
			event.registerCategory(SettingsKey.CATEGORY);
			event.register(SettingsKey.OPEN);
			event.register(SettingsKey.RELOAD);
		});
	}
}
