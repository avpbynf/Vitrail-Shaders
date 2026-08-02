package dev.vitrail.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;

/**
 * A jump to another page, written {@code [NAME]} by the pack. Pages are flat and joined by name,
 * so this carries a name and not a page.
 * <p>
 * A link naming a page nobody wrote is drawn inactive rather than dropped. One of the corpus's
 * three hundred and nine links does exactly that, and a pack that ships one broken name is still
 * a working pack.
 */
public final class LinkWidget extends AbstractButton {

	private final String page;
	private final ScreenHost host;

	/** @param resolved whether a page of that name exists */
	public LinkWidget(String page, boolean resolved, ScreenHost host, int width) {
		super(0, 0, width, Button.DEFAULT_HEIGHT, ScreenText.fromPack(host.lang().page(page)));
		this.page = page;
		this.host = host;
		this.active = resolved;
		setTooltipDelay(ScreenText.TOOLTIP_DELAY);
		host.lang().pageComment(this.page)
				.ifPresent(comment -> setTooltip(Tooltip.create(ScreenText.fromPack(comment))));
	}

	@Override
	public void onPress(InputWithModifiers input) {
		this.host.openPage(this.page);
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
