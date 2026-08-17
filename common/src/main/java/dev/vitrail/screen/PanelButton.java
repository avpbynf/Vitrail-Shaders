package dev.vitrail.screen;

// Imported although it is in this package: a nested type is not otherwise in scope by its own name,
// and @Nullable cannot annotate a qualified one.
import dev.vitrail.screen.ScreenDraw.Icon;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A button drawn from our own atlas rather than from the game's widget sprites, which is what gives
 * the screen the look of Iris's, {@code IrisButton.java}. Everything else about it is the game's: the
 * press, the focus, the narration and the tooltip all come from {@link Button}.
 * <p>
 * <b>Iris's fade is deliberately absent, because on its own branch it fades nothing.</b> Its button
 * carries a {@code SmoothedFloat} and overrides {@code getAlpha}, but the three
 * {@code RenderSystem.setShaderColor} calls that used to apply it are commented out with a note about
 * a version port, {@code IrisButton.java:37}, {@code IrisButton.java:43} and
 * {@code IrisButton.java:47}. In this game's widget base the value is stored and read by nowhere,
 * {@code AbstractWidget.java:189-194}, so overriding it changes no pixel. Porting it would have been
 * porting a mechanism whose effect is nil in the reference too.
 */
public final class PanelButton extends Button {

	/**
	 * What an icon on this kind of button is laid down through, and both numbers are measured off the
	 * atlas rather than chosen.
	 * <p>
	 * The one icon drawn this way is the circular arrow, which is the one sprite of the file nothing
	 * in the reference ever draws, so nothing there decided how bright it should be and it comes out
	 * of the file at pure white. Beside the eye it mirrors, which never goes past 170 in any of its
	 * pixels, that reads as a much louder button than its neighbour for no reason anybody chose.
	 * {@link #ICON_REST} multiplies white down to exactly that 170.
	 * <p>
	 * {@link #ICON_LIT} is darker rather than brighter, which is the atlas's own answer to hovering:
	 * its second sprite for each icon of the rows is the darker one, measured at about two thirds of
	 * the first, because the face of the button lightens under the mouse and the icon has to keep its
	 * contrast against it. The same two thirds are applied here.
	 */
	private static final int ICON_REST = 0xFFAAAAAA;
	private static final int ICON_LIT = 0xFF737373;

	/** One sprite of the atlas in place of a word, for a button too narrow to hold one. */
	private final @Nullable Icon icon;

	private PanelButton(int x, int y, int width, int height, Component message,
			@Nullable Icon icon, OnPress onPress) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
		this.icon = icon;
	}

	public static PanelButton of(int x, int y, int width, int height, Component message,
			Runnable action) {
		return new PanelButton(x, y, width, height, message, null, _ -> action.run());
	}

	public static PanelButton of(int x, int y, int width, int height, Component message,
			Runnable action, @Nullable Tooltip tooltip) {
		PanelButton button = of(x, y, width, height, message, action);
		button.setTooltip(tooltip);

		return button;
	}

	/**
	 * The same button with a sprite where its label would be. The message is kept rather than dropped:
	 * it is what the narrator reads out and what a tooltip is built from.
	 */
	public static PanelButton icon(int x, int y, int size, Icon icon, Component message,
			Runnable action) {
		PanelButton button = new PanelButton(x, y, size, size, message, icon, _ -> action.run());
		button.setTooltip(Tooltip.create(message));

		return button;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		ScreenDraw.button(graphics, getX(), getY(), getWidth(), getHeight(),
				isHoveredOrFocused(), !isActive());

		if (this.icon != null) {
			this.icon.draw(graphics, getX() + (getWidth() - this.icon.width()) / 2,
					getY() + (getHeight() - this.icon.height()) / 2,
					isHoveredOrFocused() ? ICON_LIT : ICON_REST);

			return;
		}

		// The label is the game's, so it greys itself when inactive and narrates like every other.
		extractDefaultLabel(
				graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
	}
}
