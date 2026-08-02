package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuOption;
import dev.vitrail.pack.menu.MenuPage;
import dev.vitrail.pack.menu.MenuSlot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows of one page. Slots are cut into rows of the page's column count and the last row is
 * padded with blanks, which is what keeps a pack's hand aligned columns aligned.
 * <p>
 * A row positions its widgets in {@code extractContent} and hands them back from
 * {@code children()}, which is what the game's own options list does: hit testing, focus, arrow
 * navigation and narration then come from vanilla rather than from us. A row also sets their
 * width there rather than at build time, so that resizing the window moves every widget with the
 * row instead of leaving it where it was laid out.
 */
public final class PageList extends ContainerObjectSelectionList<PageList.Row> {

	/** Vanilla's row height in the options list, and the height its widget sprites are cut for. */
	private static final int ROW_HEIGHT = 25;

	/** Between two columns. Four is what leaves three columns readable at BSL's widest page. */
	private static final int COLUMN_GAP = 4;

	/** What a row keeps on each side, {@code AbstractSelectionList.Entry.CONTENT_PADDING}. */
	private static final int ROW_PADDING = 2;

	private final ScreenHost host;

	public PageList(Minecraft minecraft, ScreenHost host, int width, int height, int top) {
		super(minecraft, width, height, top, ROW_HEIGHT);
		this.host = host;
		this.centerListVertically = false;
	}

	/**
	 * @param profileNames in declaration order, for the profile selector a page may ask for. A
	 *                     pack with none never gets that slot, {@code PackMenu} having dropped it.
	 */
	public void show(MenuPage page, List<String> profileNames) {
		int columns = Math.max(1, page.columns());
		int width = columnWidth(columns);

		List<AbstractWidget> widgets = new ArrayList<>(page.slots().size());
		for (MenuSlot slot : page.slots()) {
			widgets.add(widgetFor(slot, profileNames, width));
		}

		show(widgets, columns);
	}

	/** The same grid over widgets built elsewhere, which is how the pack list is drawn. */
	public void show(List<AbstractWidget> widgets, int columns) {
		this.clearEntries();

		int perRow = Math.max(1, columns);
		for (int start = 0; start < widgets.size(); start += perRow) {
			List<AbstractWidget> row = new ArrayList<>(widgets.subList(start,
					Math.min(start + perRow, widgets.size())));
			// The pack laid its columns out by hand and counted on the last row being as wide as
			// the others; leaving it short would shift everything in it.
			while (row.size() < perRow) {
				row.add(new BlankWidget(columnWidth(perRow)));
			}

			this.addEntry(new Row(row));
		}
	}

	/** Re-reads every widget without rebuilding the rows. One click can move ten of them. */
	public void refresh() {
		for (Row row : this.children()) {
			row.refresh();
		}
	}

	@Override
	public int getRowWidth() {
		return Math.min(400, this.width - 12);
	}

	private int columnWidth(int columns) {
		return (getRowWidth() - 2 * ROW_PADDING - COLUMN_GAP * (columns - 1)) / columns;
	}

	private AbstractWidget widgetFor(MenuSlot slot, List<String> profileNames, int width) {
		return switch (slot) {
			case MenuSlot.Blank ignored -> new BlankWidget(width);
			case MenuSlot.Link link ->
					new LinkWidget(link.page(), link.resolved(), this.host, width);
			case MenuSlot.Profiles ignored -> new ProfileWidget(profileNames, this.host, width);
			case MenuSlot.Option held -> optionWidget(held.option(), width);
		};
	}

	private AbstractWidget optionWidget(MenuOption option, int width) {
		if (option.slider()) {
			return new SliderWidget(option, this.host, width);
		}

		return option.form() == MenuOption.Form.TOGGLE
				? new ToggleWidget(option, this.host, width)
				: new ValueWidget(option, this.host, width);
	}

	public static final class Row extends ContainerObjectSelectionList.Entry<Row> {

		private final List<AbstractWidget> widgets;

		private Row(List<AbstractWidget> widgets) {
			this.widgets = List.copyOf(widgets);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
				boolean hovered, float a) {
			int columns = this.widgets.size();
			int width = (getContentWidth() - COLUMN_GAP * (columns - 1)) / columns;
			int x = getContentX();

			for (AbstractWidget widget : this.widgets) {
				widget.setWidth(width);
				widget.setPosition(x, getContentY());
				widget.extractRenderState(graphics, mouseX, mouseY, a);
				x += width + COLUMN_GAP;
			}
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return this.widgets;
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return this.widgets;
		}

		private void refresh() {
			for (AbstractWidget widget : this.widgets) {
				if (widget instanceof OptionWidget option) {
					option.syncFromValues();
				} else if (widget instanceof SliderWidget slider) {
					slider.syncFromValues();
				}
			}
		}
	}
}
