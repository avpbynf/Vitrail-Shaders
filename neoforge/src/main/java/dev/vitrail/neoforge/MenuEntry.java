package dev.vitrail.neoforge;

import dev.vitrail.screen.ScreenText;
import dev.vitrail.screen.SettingsScreen;
import dev.vitrail.Vitrail;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashMap;
import java.util.Map;

/**
 * Adds one icon to the row of small buttons the title screen and the pause menu each already carry,
 * opening the settings screen from the menu the player is already in.
 * <p>
 * Two screens, one piece of code, because the two rows are the same shape. The pause menu builds
 * its row into a {@code LinearLayout} and {@code TitleScreen} places its four by hand through
 * {@code getHorizontalPosition}, but both are laid out by the time any mod sees the screen, so
 * there is nothing to add a child to either way: the position has to be worked out from the buttons
 * that are there. They are found by shape rather than by type, because the rows mix plain
 * {@code SpriteIconButton} with whatever {@code CommonButtons.friends} returns and, on the title
 * screen, with NeoForge's own mods button, and the only thing they all agree on is being twenty by
 * twenty on one line. Reading the row rather than naming its members is also what keeps this from
 * caring how many mods have already added to it.
 * <p>
 * No mixin, and none needed: {@code ScreenEvent.Init.Post} is public and is how NeoForge itself
 * expects a mod to reach a vanilla screen. The failure mode is deliberately silent. If a row ever
 * stops looking like a row, no button is added and that screen is untouched, which is a missing
 * shortcut rather than a broken screen; the key bound in {@link VitrailKeys} still opens it, and on
 * the title screen the row is the only way in, so a player who loses it loses a shortcut and not a
 * setting.
 */
public final class MenuEntry {

	/** The size every button in that row has, and the sieve used to recognise them. */
	private static final int ICON = 20;

	/** The gap both screens put between them, so ours does not look bolted on. */
	private static final int GAP = 4;

	private static final Identifier SPRITE =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "icon/vitrail");

	private MenuEntry() {
	}

	public static void register() {
		NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, event -> {
			if (event.getScreen() instanceof PauseScreen
					|| event.getScreen() instanceof TitleScreen) {
				add(event);
			}
		});
	}

	private static void add(ScreenEvent.Init.Post event) {
		// Two passes, and the second one is the whole point. Square buttons are only used to
		// recognise WHICH line the row is on; the right edge is then measured over everything
		// sitting on that line, whatever its size. Measuring it over the square ones alone put
		// this button exactly on top of the last icon, because the pause menu ends its row with
		// one that is not square.
		Map<Integer, Integer> countByRow = new HashMap<>();
		for (GuiEventListener listener : event.getListenersList()) {
			if (listener instanceof AbstractWidget widget
					&& widget.getWidth() == ICON && widget.getHeight() == ICON) {
				countByRow.merge(widget.getY(), 1, Integer::sum);
			}
		}

		int row = 0;
		int best = 0;
		for (Map.Entry<Integer, Integer> entry : countByRow.entrySet()) {
			if (entry.getValue() > best) {
				best = entry.getValue();
				row = entry.getKey();
			}
		}

		// One button of the right size is a coincidence; two side by side are a row.
		if (best < 2) {
			return;
		}

		// To the LEFT of the row, and that is not a matter of taste. The right hand end is where
		// a mod that draws an icon without registering a widget puts it, and this instance has
		// one: the pause menu layout holds five buttons, whose centred row really does end where
		// the count says, yet a sixth icon is painted past it. Nothing that is invisible to
		// getListenersList can be avoided by measuring, so the only safe slot is the one before
		// the row, which no layout ever grows into. On the title screen the same slot happens to
		// be the one next to NeoForge's mods button, which is where a player looks for this.
		int left = Integer.MAX_VALUE;
		for (GuiEventListener listener : event.getListenersList()) {
			if (listener instanceof AbstractWidget widget
					&& widget.getY() < row + ICON && widget.getY() + widget.getHeight() > row
					&& widget.getWidth() <= ICON * 2) {
				left = Math.min(left, widget.getX());
			}
		}

		SpriteIconButton button = SpriteIconButton
				.builder(Component.translatable(ScreenText.TITLE),
						_ -> Minecraft.getInstance().gui
								.setScreen(new SettingsScreen(event.getScreen())),
						true)
				.width(ICON)
				// Even, and never the odd fifteen the game's own icons of that row use, because the
				// button places a sprite at its centre minus half its width in whole pixels: an odd
				// sprite in a twenty wide button lands three pixels from one edge and two from the
				// other. Fourteen leaves three on each side, which is a margin rather than a sprite
				// pressed against the border.
				.sprite(SPRITE, 14, 14)
				.withTootip()
				.build();
		// The row was centred before this button widened it, so everything on it moves half a button
		// right and the new one takes the slot that opens on the left. Recentring rather than growing
		// the row leftwards is what keeps it under the wide buttons above and below it.
		//
		// It composes with another mod doing the same, whichever order the two run in: each one only
		// ever shifts by half of what it added itself. What it cannot reach is an icon painted
		// without a widget, which this instance has one of; that one keeps its place while the row
		// moves, and the day it looks wrong is the day it has to be measured some other way.
		int shift = (ICON + GAP) / 2;
		for (GuiEventListener listener : event.getListenersList()) {
			if (listener instanceof AbstractWidget widget
					&& widget.getY() < row + ICON && widget.getY() + widget.getHeight() > row
					&& widget.getWidth() <= ICON * 2) {
				widget.setX(widget.getX() + shift);
			}
		}

		button.setPosition(left - ICON - GAP + shift, row);
		event.addListener(button);
	}
}
