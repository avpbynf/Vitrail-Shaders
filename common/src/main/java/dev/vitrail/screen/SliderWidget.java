package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuOption;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The same setting, walked through by dragging. The vanilla slider works in a double from zero to
 * one and we work in an index, so the two are joined by {@code index / (size - 1)} in both
 * directions; using one formula for drawing and another for dragging, as Iris does, is what makes
 * a handle fail to reach the end of its own track.
 * <p>
 * The handle snaps to the index it landed on rather than staying where the mouse left it, so that
 * what is drawn and what will be written are never two different things.
 * <p>
 * Only the two ends of that conversion are written here. Dragging, the sprites, the grab mode the
 * keyboard enters on return, and the narration are the game's. The arrow keys are the exception,
 * and the reason is in {@link #keyPressed}.
 */
public final class SliderWidget extends AbstractSliderButton {

	private final MenuOption option;
	private final ScreenHost host;

	public SliderWidget(MenuOption option, ScreenHost host, int width) {
		super(0, 0, width, DEFAULT_HEIGHT, Component.empty(), 0.0);
		this.option = option;
		this.host = host;
		setTooltipDelay(ScreenText.TOOLTIP_DELAY);
		syncFromValues();
	}

	public void syncFromValues() {
		this.active = !this.host.values().forced(this.option.name());
		this.value = ratio(index());
		updateMessage();
		setTooltip(this.host.lang().optionComment(this.option.name())
				.map(comment -> Tooltip.create(ScreenText.fromPack(comment)))
				.orElse(null));
	}

	@Override
	protected void updateMessage() {
		String name = this.option.name();
		String value = this.host.values().pending(name);
		setMessage(ScreenText.setting(ScreenText.fromPack(this.host.lang().option(name)),
				ScreenText.fromPack(this.host.lang().value(name, value)),
				this.host.values().modified(name)));
	}

	@Override
	protected void applyValue() {
		int index = (int) Math.round(this.value * steps());
		this.value = ratio(index);
		this.host.values().queue(this.option.name(), this.option.at(index));
		this.host.refresh();
	}

	/** One index per arrow key. Vanilla steps by one pixel of track, which an index ignores. */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!isActive()) {
			return false;
		}

		if (this.canChangeValue && (event.isLeft() || event.isRight())) {
			setValue(ratio(Mth.clamp(index() + (event.isLeft() ? -1 : 1), 0, steps())));
			return true;
		}

		return super.keyPressed(event);
	}

	private int index() {
		return this.option.indexOf(this.host.values().pending(this.option.name()));
	}

	private double ratio(int index) {
		return index / (double) steps();
	}

	/** How many gaps the track holds. Never zero, a slider over one value being a division by it. */
	private int steps() {
		return Math.max(1, this.option.size() - 1);
	}
}
