package dev.vitrail.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;

/**
 * The scrolling list a pack's option page is drawn in, where a row holds widgets and hands them back
 * so that hit testing, focus, arrow navigation and narration come from the game. This is Iris's
 * {@code IrisContainerObjectSelectionList.java}, and its whole substance is where the scrollbar goes:
 * against the right edge of the screen, where the game's own list puts it a little way in from the
 * middle, which is what makes the list read as full width.
 * <p>
 * Iris has a sibling of this over {@code AbstractSelectionList}, for the lists whose entries paint
 * themselves rather than holding widgets, and there is none here because there cannot be:
 * {@code AbstractSelectionList.Entry} is protected in 26.2, so no class outside that package can
 * bound a type parameter on it. The pack list carries the same four lines itself, reaching the entry
 * type as a subclass does.
 */
public class WidgetList<E extends ContainerObjectSelectionList.Entry<E>>
		extends ContainerObjectSelectionList<E> {

	private static final int SCROLLBAR_INSET = 6;

	public WidgetList(Minecraft minecraft, int width, int height, int top, int itemHeight) {
		super(minecraft, width, height, top, itemHeight);
	}

	@Override
	protected int scrollBarX() {
		return this.width - SCROLLBAR_INSET;
	}
}
