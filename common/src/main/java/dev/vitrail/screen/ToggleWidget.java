package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuOption;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Optional;

/**
 * A bare {@code #define}, on or off. This is Iris's {@code BooleanElementWidget}.
 * <p>
 * <b>Neither word is coloured while the setting sits where the pack left it</b>, and Iris's comment
 * gives the reason: red for Off and green for On had players turning things on that did not need to
 * be on, red reading as something wrong rather than as a state. So a value equal to the pack's own is
 * drawn white, and only a value the player moved carries a colour.
 * <p>
 * The two words are the game's own, {@link CommonComponents#optionStatus}, where Iris ships them as
 * two strings of its own; they are the same words, and the game's are translated wherever the game
 * is. A pack's name for one of the two values is not consulted, which is also Iris's rule here and
 * not an oversight: only a value walked through by {@link ValueWidget} reads {@code value.NAME.VALUE}.
 */
public final class ToggleWidget extends OptionWidget {

	/** The least the value box may be, so that On and Off do not resize it. Iris's number. */
	private static final int VALUE_WIDTH = 28;

	private static final String ON = "on";
	private static final String OFF = "off";

	private final MenuOption option;

	private boolean value;
	private boolean applied;

	public ToggleWidget(MenuOption option) {
		this.option = option;
	}

	@Override
	public void init(ScreenHost host) {
		super.init(host);
		this.value = on(host.values().pending(this.option.name()));
		this.applied = on(host.values().applied(this.option.name()));
		setLabel(ScreenText.fromPack(host.lang().option(this.option.name())));
	}

	private static boolean on(String value) {
		return ON.equals(value);
	}

	@Override
	public void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered) {
		measure(VALUE_WIDTH);
		drawWithValue(graphics, hovered || isFocused());
		tryTooltip(graphics, mouseX, mouseY, hovered);
	}

	@Override
	protected String name() {
		return this.option.name();
	}

	@Override
	protected Component valueLabel() {
		MutableComponent text = CommonComponents.optionStatus(this.value).copy();

		return this.value == on(this.option.defaultValue())
				? text
				: text.withStyle(this.value ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	private void queue() {
		host().values().queue(this.option.name(), this.value ? ON : OFF);
	}

	/** Flips rather than walking the list, so the order the two values were read in cannot matter. */
	@Override
	protected boolean nextValue() {
		this.value = !this.value;
		queue();

		return true;
	}

	@Override
	protected boolean previousValue() {
		return nextValue();
	}

	@Override
	protected boolean originalValue() {
		this.value = on(this.option.defaultValue());
		queue();

		return true;
	}

	@Override
	protected boolean modified() {
		return this.value != this.applied;
	}

	@Override
	protected Optional<String> comment() {
		return host().lang().optionComment(this.option.name());
	}
}
