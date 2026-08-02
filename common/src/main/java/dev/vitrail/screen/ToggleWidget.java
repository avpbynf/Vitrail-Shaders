package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuOption;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A bare {@code #define}, on or off, green or red, and neither when it sits where the pack left
 * it. Cycling flips rather than walking the list, so the order the two values were read in cannot
 * matter.
 */
public final class ToggleWidget extends OptionWidget {

	private static final String ON = "on";
	private static final String OFF = "off";

	public ToggleWidget(MenuOption option, ScreenHost host, int width) {
		super(option, host, width);
		syncFromValues();
	}

	@Override
	protected Component valueLabel() {
		String value = host().values().pending(option().name());
		String named = host().lang().value(option().name(), value);
		MutableComponent label = named.equals(value)
				? CommonComponents.optionStatus(ON.equals(value)).copy()
				: ScreenText.fromPack(named);

		return value.equals(option().defaultValue())
				? label
				: label.withStyle(ON.equals(value) ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	@Override
	protected void cycle(int direction) {
		String value = host().values().pending(option().name());
		host().values().queue(option().name(), ON.equals(value) ? OFF : ON);
	}
}
