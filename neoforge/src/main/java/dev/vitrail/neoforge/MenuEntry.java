package dev.vitrail.neoforge;

import dev.vitrail.screen.MenuButton;

import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Where NeoForge hands a laid out screen over, and how a widget is added to it. What is measured
 * and placed is in {@link MenuButton}.
 * <p>
 * No mixin, and none needed: {@code ScreenEvent.Init.Post} is public and is how NeoForge itself
 * expects a mod to reach a vanilla screen.
 */
public final class MenuEntry {

	private MenuEntry() {
	}

	public static void register() {
		NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, event -> {
			if (event.getScreen() instanceof PauseScreen
					|| event.getScreen() instanceof TitleScreen) {
				MenuButton.add(event.getListenersList(), event.getScreen(), event::addListener);
			}
		});
	}
}
