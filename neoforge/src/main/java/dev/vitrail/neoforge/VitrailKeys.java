package dev.vitrail.neoforge;

import dev.vitrail.screen.ScreenText;
import dev.vitrail.screen.SettingsScreen;
import dev.vitrail.Vitrail;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.lwjgl.glfw.GLFW;

/**
 * One key that opens the settings screen, on I, which is the key Iris binds and therefore the one
 * a player who has configured a pack before will try first.
 * <p>
 * Registered on the mod bus and polled on the game bus, because those are the two buses those
 * events are posted on. Polling on a tick rather than on a key event is deliberate: the game only
 * feeds key mappings while no screen is open, so the shortcut cannot open a second copy of this
 * screen over the first, and it costs one boolean a tick.
 */
public final class VitrailKeys {

	public static final KeyMapping.Category CATEGORY =
			new KeyMapping.Category(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "keybinds"));

	public static final KeyMapping OPEN =
			new KeyMapping(ScreenText.OPEN_SETTINGS, GLFW.GLFW_KEY_I, CATEGORY);

	private VitrailKeys() {
	}

	public static void register(IEventBus modBus) {
		modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
			event.registerCategory(CATEGORY);
			event.register(OPEN);
		});

		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, _ -> {
			if (!OPEN.consumeClick()) {
				return;
			}

			// A tick can carry more than one press, and the rest are dropped rather than opened.
			// Opening one screen per press would stack them, each holding the one before it as the
			// screen to go back to, so leaving would take as many Escapes as the key was tapped.
			while (OPEN.consumeClick()) {
				// Nothing to do: the queue is only being emptied.
			}

			Minecraft minecraft = Minecraft.getInstance();
			minecraft.gui.setScreen(new SettingsScreen(minecraft.gui.screen()));
		});
	}
}
