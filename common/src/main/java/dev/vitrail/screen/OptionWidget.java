package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuOption;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * The shared half of an option widget: the pack's name for the setting, a colon, and its value.
 * <p>
 * The label turns amber when the pending value differs from the applied one. That colour is the
 * only thing standing between a player and not knowing whether the world they are looking at
 * matches the screen they are reading, so it is not decoration.
 * <p>
 * A setting {@code vitrail/options.txt} forces is drawn inactive, and so is one the pack named on
 * a page but never described. Nothing else has to be written for either case: an inactive button
 * greys its own label, asks the game for the "not allowed" cursor, refuses the click and the
 * focus, and keeps its tooltip readable.
 */
public abstract class OptionWidget extends AbstractButton {

	private final MenuOption option;
	private final ScreenHost host;

	protected OptionWidget(MenuOption option, ScreenHost host, int width) {
		super(0, 0, width, Button.DEFAULT_HEIGHT, Component.empty());
		this.option = option;
		this.host = host;
	}

	public final MenuOption option() {
		return this.option;
	}

	protected final ScreenHost host() {
		return this.host;
	}

	/** The value as the pack names it, prefix and suffix applied. */
	protected abstract Component valueLabel();

	/** @param direction {@code +1} for the next value, {@code -1} for the previous one */
	protected abstract void cycle(int direction);

	protected Component nameLabel() {
		return ScreenText.fromPack(this.host.lang().option(this.option.name()));
	}

	protected boolean modified() {
		return this.host.values().modified(this.option.name());
	}

	protected boolean forced() {
		return this.host.values().forced(this.option.name());
	}

	protected Optional<String> comment() {
		return this.host.lang().optionComment(this.option.name());
	}

	protected void toDefault() {
		this.host.values().reset(this.option.name());
	}

	/**
	 * Re-reads the values and rebuilds message, colour and tooltip. Called at the end of every
	 * subclass's constructor, which is also why the delay is set here rather than in ours: a
	 * constructor of an abstract class has no business calling into a widget it does not own yet.
	 */
	public final void syncFromValues() {
		this.active = !forced() && this.option.form() != MenuOption.Form.FIXED;
		setTooltipDelay(ScreenText.TOOLTIP_DELAY);
		setMessage(ScreenText.setting(nameLabel(), valueLabel(), modified()));
		setTooltip(comment().map(text -> Tooltip.create(ScreenText.fromPack(text))).orElse(null));
	}

	/** Zero and one, so that a right click can walk backwards the way Iris's does. */
	@Override
	protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
		return buttonInfo.button() == 0 || buttonInfo.button() == 1;
	}

	/**
	 * Shift and a click give the value the pack ships, and so does control and a key, but shift
	 * and a key walk backwards: a keyboard has no second button to walk back with.
	 */
	@Override
	public void onPress(InputWithModifiers input) {
		boolean fromMouse = input instanceof MouseButtonEvent;
		if (input.hasControlDown() || (fromMouse && input.hasShiftDown())) {
			toDefault();
		} else {
			boolean backwards = input instanceof MouseButtonEvent mouse
					? mouse.button() == 1
					: input.hasShiftDown();
			cycle(backwards ? -1 : 1);
		}

		this.host.refresh();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		extractDefaultSprite(graphics);
		extractDefaultLabel(
				graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
