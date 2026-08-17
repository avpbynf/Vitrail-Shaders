package dev.vitrail.neoforge;

import dev.vitrail.screen.SettingsKey;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Registers {@link SettingsKey} and asks it once a tick.
 * <p>
 * On the mod bus and on the game bus respectively, because those are the two buses those events are
 * posted on.
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

		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, _ -> SettingsKey.poll());
	}
}
