package dev.vitrail.screen;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jspecify.annotations.Nullable;

/**
 * The gap a pack writes {@code <empty>} for, and the padding a last row short of a full set of
 * columns is filled out with. This is Iris's {@code AbstractElementWidget.EMPTY}.
 * <p>
 * A blank is layout, not nothing: the corpus holds seven hundred and seventy three of them against
 * nineteen hundred and forty two settings, and it is how a pack aligns its columns by hand. So it
 * takes up a cell and draws nothing in it, and it answers with an empty rectangle and no focus path
 * so that neither a click nor the tab key can land on it.
 */
public final class BlankWidget extends PageWidget {

	@Override
	public void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered) {
	}

	@Override
	public ScreenRectangle getRectangle() {
		return ScreenRectangle.empty();
	}

	@Override
	public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
		return null;
	}
}
