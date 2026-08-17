package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuOption;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * A setting walked through by clicking. This is Iris's {@code StringElementWidget}.
 * <p>
 * The walk goes through the option's own list and wraps at both ends, so a value the pack ships
 * outside that list is still reachable: it was appended to the list rather than corrected, which
 * {@link MenuOption} does and says why.
 * <p>
 * A setting the pack offered fewer than two values for is built as one of these too, as it is in
 * Iris, which has no separate shape for it: a list of one wraps onto itself, so a click on the
 * headings packs write as settings queues the value that is already there. Twenty two of the corpus
 * are that.
 */
public class ValueWidget extends OptionWidget {

	/** The blue Iris draws a value in, which is what tells a value apart from its name at a glance. */
	private static final int VALUE_BLUE = 0xFF6688ff;

	protected final MenuOption option;

	/** Where in the option's own list the pending value sits. Held here because a drag moves it. */
	protected int index;

	private String applied = "";

	public ValueWidget(MenuOption option) {
		this.option = option;
	}

	@Override
	public void init(ScreenHost host) {
		super.init(host);
		this.index = this.option.indexOf(host.values().pending(this.option.name()));
		this.applied = host.values().applied(this.option.name());
		setLabel(ScreenText.fromPack(host.lang().option(this.option.name())));
	}

	@Override
	public void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered) {
		measure(0);
		drawWithValue(graphics, hovered || isFocused());

		// Off the cell's own corner when the keyboard brought us here, since there is no mouse to
		// hang it on and it would otherwise be drawn wherever the mouse was last left.
		if (this.usedKeyboard) {
			tryTooltip(graphics, bounds().right(), bounds().position().y(), hovered);
		} else {
			tryTooltip(graphics, mouseX, mouseY, hovered);
		}
	}

	@Override
	protected String name() {
		return this.option.name();
	}

	/** The value the cell is showing, which is not yet the value in the file while a drag is on. */
	protected final String value() {
		return this.option.at(this.index);
	}

	@Override
	protected Component valueLabel() {
		return ScreenText
				.fromPack(host().lang().value(this.option.name(), value()))
				.withColor(VALUE_BLUE);
	}

	protected final void queue() {
		host().values().queue(this.option.name(), value());
	}

	/**
	 * Kept inside the list rather than left to wrap when it is read, because a slider divides by the
	 * number of steps and an index outside them would put its handle off the end of its own track.
	 */
	private void step(int by) {
		this.index = Math.floorMod(this.index + by, Math.max(1, this.option.size()));
		queue();
	}

	@Override
	protected boolean nextValue() {
		step(1);

		return true;
	}

	@Override
	protected boolean previousValue() {
		step(-1);

		return true;
	}

	@Override
	protected boolean originalValue() {
		this.index = this.option.indexOf(this.option.defaultValue());
		queue();

		return true;
	}

	@Override
	protected boolean modified() {
		return !this.applied.equals(value());
	}

	@Override
	protected Optional<String> comment() {
		return host().lang().optionComment(this.option.name());
	}
}
