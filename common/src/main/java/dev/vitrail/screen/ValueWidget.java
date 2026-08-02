package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuOption;

import net.minecraft.network.chat.Component;

/**
 * A setting walked through by clicking. The walk goes through the option's own list and wraps at
 * both ends, so a value the pack ships outside that list is still reachable: it was appended to
 * the list rather than corrected.
 * <p>
 * An option the pack offered fewer than two values for is built as one of these too. It draws
 * itself the same way and simply never becomes active, which is what shows the player that the
 * pack named it on a page without ever describing it.
 */
public final class ValueWidget extends OptionWidget {

	public ValueWidget(MenuOption option, ScreenHost host, int width) {
		super(option, host, width);
		syncFromValues();
	}

	@Override
	protected Component valueLabel() {
		String name = option().name();
		return ScreenText.fromPack(host().lang().value(name, host().values().pending(name)));
	}

	@Override
	protected void cycle(int direction) {
		String name = option().name();
		int index = option().indexOf(host().values().pending(name));
		host().values().queue(name, option().at(index + direction));
	}
}
