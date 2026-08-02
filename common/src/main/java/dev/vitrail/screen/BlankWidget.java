package dev.vitrail.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * The gap a pack writes {@code <empty>} for, as a widget of its own.
 * <p>
 * A blank is layout, not nothing: the corpus holds seven hundred and seventy three of them
 * against nineteen hundred and forty two settings, and it is how a pack aligns its columns by
 * hand. It has to take up a cell, so it is a widget; it must not be reachable, so it is inactive,
 * and that one line is the whole of it. An inactive widget answers no to {@code isMouseOver} and
 * hands back no focus path, so neither a click nor the tab key can land on it.
 */
public final class BlankWidget extends AbstractWidget {

	public BlankWidget(int width) {
		super(0, 0, width, Button.DEFAULT_HEIGHT, Component.empty());
		this.active = false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float a) {
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}
}
