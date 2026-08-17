package dev.vitrail.screen;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.menu.MenuOption;
import dev.vitrail.pack.menu.MenuPage;
import dev.vitrail.pack.menu.MenuSlot;
import dev.vitrail.pack.menu.PackMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * One page of a pack's settings: a header carrying the page's name and its tools, then a row per
 * however many columns the pack asked for. This is Iris's {@code ShaderPackOptionList}.
 * <p>
 * <b>A cell is not a widget and a row is one list entry</b>, so the cells are laid out by dividing the
 * row's width and are handed back to the game as the entry's children, which is what gives them hit
 * testing, focus and arrow navigation. {@link PageWidget} says the rest.
 * <p>
 * <b>No registry of element kinds.</b> Iris keeps two maps from class to factory,
 * {@code OptionMenuConstructor}, because its menu elements are an open hierarchy; ours are the sealed
 * {@link MenuSlot}, so the same job is a switch the compiler checks for completeness.
 * <p>
 * <b>No separators.</b> Iris blits a header and a footer separator here, guarded on the same fade its
 * pack list uses; that fade answers nought for as long as the option page is the view being shown
 * ({@code ShaderPackScreen.java:97-103}), so the two blits it guards can never run and the page has a
 * background and no lines. Kept as it is rather than as it reads.
 */
public final class OptionList extends WidgetList<OptionList.BaseEntry> {

	/** How tall a row of cells is. Iris's, and what the drawing coordinates are written against. */
	private static final int ROW_HEIGHT = 24;

	/** How wide the page is drawn, and how far it is kept off the edges of a wide window. */
	private static final int ROW_WIDTH = 400;
	private static final int ROW_MARGIN = 12;

	/**
	 * The background Iris blits under this list. The in world variant is the game's answer to the same
	 * question and is kept, exactly as {@link PackList} keeps it, where Iris names the one texture and
	 * so loses that distinction in a world.
	 */
	private static final Identifier IN_WORLD_BACKGROUND =
			Identifier.withDefaultNamespace("textures/gui/inworld_menu_background.png");

	private final ScreenHost host;
	private final List<PageWidget> cells = new ArrayList<>();

	public OptionList(ScreenHost host, Minecraft minecraft, int width, int height, int top) {
		super(minecraft, width, height, top + 4, ROW_HEIGHT);
		this.host = host;
	}

	@Override
	public int getRowWidth() {
		return Math.min(ROW_WIDTH, this.width - ROW_MARGIN);
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor graphics) {
		Identifier texture = this.minecraft.level == null
				? Screen.MENU_BACKGROUND
				: IN_WORLD_BACKGROUND;
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, getX(), getY(), getRight(),
				getBottom() + (int) scrollAmount(), getWidth(), getHeight(), 32, 32);
	}

	/** Nothing, and the class comment says why. */
	@Override
	protected void extractListSeparators(GuiGraphicsExtractor graphics) {
	}

	/**
	 * Builds the page again from the pack's own layout, which is what walking into a page and out of it
	 * both come to.
	 *
	 * @param pageName the page to show, {@code ""} being the one the pack opens on
	 */
	public void show(PackMenu menu, String pageName) {
		clearEntries();
		setScrollAmount(0);
		this.cells.clear();

		boolean sub = !pageName.isEmpty();
		MenuPage page = sub
				? menu.page(pageName).orElseGet(menu::main)
				: menu.main();
		// The pack's own name in bold on its first page, its own name for the page on any other, which
		// is what Iris puts here. A page the pack no longer lays out keeps the back button, since that
		// button is then the only way off it.
		Component heading = sub
				? ScreenText.fromPack(this.host.lang().page(pageName))
				: ScreenText.fromPack(menu.packName()).withStyle(ChatFormatting.BOLD);

		addEntry(new HeaderEntry(this.host, heading, sub));
		addCells(page.columns(), page.slots(), menu.profileNames());
	}

	/** Re-reads every cell of the page from the values. One click can move ten of them. */
	public void refresh() {
		this.cells.forEach(cell -> cell.init(this.host));
	}

	private void addCells(int columns, List<MenuSlot> slots, List<String> profiles) {
		List<PageWidget> row = new ArrayList<>();
		for (MenuSlot slot : slots) {
			PageWidget cell = cellFor(slot, profiles);
			cell.init(this.host);
			this.cells.add(cell);
			row.add(cell);

			if (row.size() >= columns) {
				addEntry(new RowEntry(this.host, row));
				// A new list rather than a cleared one: the entry above keeps the one it was given.
				row = new ArrayList<>();
			}
		}

		if (row.isEmpty()) {
			return;
		}

		// Padded out to a full row, so that a last row of one does not draw one cell across the page.
		while (row.size() < columns) {
			row.add(new BlankWidget());
		}

		addEntry(new RowEntry(this.host, row));
	}

	private PageWidget cellFor(MenuSlot slot, List<String> profiles) {
		return switch (slot) {
			case MenuSlot.Blank _ -> new BlankWidget();
			case MenuSlot.Link(String page, boolean resolved) -> new LinkWidget(page, resolved);
			case MenuSlot.Profiles _ -> new ProfileWidget(profiles);
			case MenuSlot.Option(MenuOption option) -> switch (option.form()) {
				case TOGGLE -> new ToggleWidget(option);
				case CYCLE, FIXED -> option.slider()
						? new SliderWidget(option)
						: new ValueWidget(option);
			};
		};
	}

	/** Every row of this list, whether it holds cells or the page's own header. */
	public abstract static class BaseEntry extends ContainerObjectSelectionList.Entry<BaseEntry> {

		protected BaseEntry() {
		}
	}

	/** One row of cells, laid out across the width the list gives it. */
	public static final class RowEntry extends BaseEntry {

		/** Between two cells, and the three pixels Iris takes off the whole row to centre it. */
		private static final int CELL_GAP = 2;
		private static final int ROW_TRIM = 3;

		private final ScreenHost host;
		private final List<PageWidget> cells;

		private int lastWidth = 1;
		private int lastX;

		RowEntry(ScreenHost host, List<PageWidget> cells) {
			this.host = host;
			this.cells = List.copyOf(cells);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
				boolean hovered, float a) {
			this.lastWidth = Math.max(1, getContentWidth());
			this.lastX = getContentX();

			int room = getContentWidth() - CELL_GAP * (this.cells.size() - 1) - ROW_TRIM;
			float each = (float) room / this.cells.size();

			for (int i = 0; i < this.cells.size(); i++) {
				PageWidget cell = this.cells.get(i);
				boolean under = (hovered && under(mouseX) == i) || getFocused() == cell;

				cell.place(new ScreenRectangle(getContentX() + (int) ((each + CELL_GAP) * i),
						getContentY(), (int) each, getContentHeight() + 2));
				cell.draw(graphics, mouseX, mouseY, under);

				this.host.hovered(cell, under);
			}
		}

		/**
		 * Which cell the mouse is over, worked out from where it sits across the row rather than from
		 * each cell's own rectangle, which is Iris's arrangement: the row is what knows the widths, and
		 * a click landing in the gap between two cells then goes to one of them rather than to nothing.
		 */
		private int under(int mouseX) {
			float across = (float) Mth.clamp(mouseX - this.lastX, 0, this.lastWidth) / this.lastWidth;

			return Mth.clamp((int) Math.floor(this.cells.size() * across), 0, this.cells.size() - 1);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			return this.cells.get(under((int) event.x())).mouseClicked(event, doubleClick);
		}

		@Override
		public boolean mouseReleased(MouseButtonEvent event) {
			return this.cells.get(under((int) event.x())).mouseReleased(event);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return this.cells;
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return this.cells;
		}
	}

	/**
	 * The page's name, the way back off it, and the three tools that act on the whole pack: import,
	 * export and reset.
	 */
	public static final class HeaderEntry extends BaseEntry {

		private static final Component BACK = Component.literal("< ")
				.append(CommonComponents.GUI_BACK.copy().withStyle(ChatFormatting.ITALIC));

		/**
		 * Always this one, and always live. Iris keeps a grey form too and turns the button dead until
		 * shift is held, which is its guard against losing an evening of tuning to one click; here the
		 * guard is the game's own confirmation screen, which is what this project's screen asked for
		 * before the port and what the reader has already answered elsewhere in the game. One guard and
		 * not two: a button that demands shift AND then asks reads as a button that is broken.
		 */
		private static final MutableComponent RESET = Component
				.translatable(ScreenText.RESET).withStyle(ChatFormatting.YELLOW);

		private static final MutableComponent RESET_TOOLTIP = Component
				.translatable(ScreenText.RESET_TOOLTIP).withStyle(ChatFormatting.RED);
		private static final MutableComponent IMPORT_TOOLTIP = Component
				.translatable(ScreenText.IMPORT_TOOLTIP)
				.withStyle(style -> style.withColor(TextColor.fromRgb(0xFF4da6ff)));
		private static final MutableComponent EXPORT_TOOLTIP = Component
				.translatable(ScreenText.EXPORT_TOOLTIP)
				.withStyle(style -> style.withColor(TextColor.fromRgb(0xFFfc7d3d)));

		/** The width the two word buttons are given at least, and how tall all of them are. */
		private static final int MIN_BUTTON_WIDTH = 42;
		private static final int BUTTON_HEIGHT = 16;

		/** How wide the two icon buttons are cut. */
		private static final int ICON_BUTTON_WIDTH = 15;

		/** The line under the header, in the grey Iris draws it. */
		private static final int DIVIDER = 0x66BEBEBE;

		private final ScreenHost host;
		private final Component heading;
		private final @Nullable WidgetRow backRow;
		private final WidgetRow tools = new WidgetRow();
		private final WidgetRow.TextElement reset;
		private final WidgetRow.IconElement importButton;
		private final WidgetRow.IconElement exportButton;

		HeaderEntry(ScreenHost host, Component heading, boolean hasBack) {
			this.host = host;
			this.heading = heading;

			Font font = Minecraft.getInstance().font;
			this.backRow = hasBack
					? new WidgetRow().add(new WidgetRow.TextElement(BACK, _ -> back()),
							Math.max(MIN_BUTTON_WIDTH, font.width(BACK) + 8))
					: null;

			this.reset = new WidgetRow.TextElement(RESET, _ -> reset());
			this.importButton = new WidgetRow.IconElement(ScreenDraw.Icon.IMPORT,
					ScreenDraw.Icon.IMPORT_LIT, _ -> importSettings());
			this.exportButton = new WidgetRow.IconElement(ScreenDraw.Icon.EXPORT,
					ScreenDraw.Icon.EXPORT_LIT, _ -> exportSettings());

			this.tools
					.add(this.importButton, ICON_BUTTON_WIDTH)
					.add(this.exportButton, ICON_BUTTON_WIDTH)
					.add(this.reset, Math.max(MIN_BUTTON_WIDTH, font.width(RESET) + 8));
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
				boolean hovered, float a) {
			int x = getX();
			int y = getY();
			int width = getWidth();
			int bottom = y + getHeight();

			graphics.fill(x - 3, bottom - 2, x + width, bottom - 1, DIVIDER);

			// Scrolled rather than cut, which is what the game does with a name that will not fit and
			// what keeps a pack's own long page names readable.
			graphics.textRenderer().acceptScrolling(this.heading, x + width / 2, x + 5,
					(x + width) - 10 - this.tools.width(), y + 5, y + 15);

			if (this.backRow != null) {
				this.backRow.draw(graphics, x, y, BUTTON_HEIGHT, mouseX, mouseY, a, hovered);
			}

			this.tools.drawRightAligned(graphics, (x + width) - 3, y, BUTTON_HEIGHT, mouseX, mouseY,
					a, hovered);

			Font font = Minecraft.getInstance().font;
			tooltip(graphics, font, this.reset, RESET_TOOLTIP);
			tooltip(graphics, font, this.importButton, IMPORT_TOOLTIP);
			tooltip(graphics, font, this.exportButton, EXPORT_TOOLTIP);
		}

		/** Anchored on the control's own bottom right corner, since these three sit at the edge. */
		private void tooltip(GuiGraphicsExtractor graphics, Font font, WidgetRow.Element element,
				Component text) {
			if (!element.isHovered() && !element.isFocused()) {
				return;
			}

			ScreenRectangle at = element.getRectangle();
			this.host.onTop(() -> ScreenDraw.textPanel(font, graphics, text,
					at.right() - (font.width(text) + 10), at.position().y() - 16));
		}

		private boolean back() {
			this.host.back();
			ScreenDraw.clickSound();

			return true;
		}

		/**
		 * Throws this pack's settings away and applies, once the screen has asked. The asking is the
		 * screen's job rather than this row's, since it is a screen that gets pushed over this one.
		 */
		private boolean reset() {
			ScreenDraw.clickSound();
			this.host.resetSettings();

			return true;
		}

		private boolean importSettings() {
			ScreenDraw.clickSound();
			if (!canOpenDialog()) {
				return false;
			}

			Path origin = this.host.settingsFile();
			FileDialog
					.choose(FileDialog.Kind.OPEN, "Import Shader Settings from File", origin)
					.whenComplete((chosen, failed) -> {
						if (failed != null) {
							Vitrail.logger().error("Vitrail could not open the import dialog", failed);

							return;
						}

						chosen.ifPresent(this.host::importSettings);
					});

			return true;
		}

		/**
		 * Copies this pack's settings file to wherever the player asks for it, which is what Iris does
		 * and why what is written is the applied settings rather than what is on screen: the file is
		 * the applied settings.
		 */
		private boolean exportSettings() {
			ScreenDraw.clickSound();
			if (!canOpenDialog()) {
				return false;
			}

			Path origin = this.host.settingsFile();
			FileDialog
					.choose(FileDialog.Kind.SAVE, "Export Shader Settings to File", origin)
					.whenComplete((chosen, failed) -> {
						if (failed != null) {
							Vitrail.logger().error("Vitrail could not open the export dialog", failed);

							return;
						}

						chosen.ifPresent(target -> copySettings(origin, target));
					});

			return true;
		}

		private static void copySettings(Path origin, Path target) {
			Properties settings = new Properties();
			if (Files.exists(origin)) {
				try (InputStream in = Files.newInputStream(origin)) {
					settings.load(in);
				} catch (IOException e) {
					Vitrail.logger().error("Vitrail could not read {}", origin, e);

					return;
				}
			}

			try (OutputStream out = Files.newOutputStream(target)) {
				settings.store(out, null);
			} catch (IOException e) {
				Vitrail.logger().error("Vitrail could not write {}", target, e);
			}
		}

		/**
		 * A native dialog over a full screen window hangs the game on more than one platform, which is
		 * Iris's own finding and the reason it says so rather than opening one.
		 */
		private boolean canOpenDialog() {
			if (!Minecraft.getInstance().getWindow().isFullscreen()) {
				return true;
			}

			this.host.announce(Component.translatable(ScreenText.FULLSCREEN)
					.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

			return false;
		}

		@Override
		public List<? extends GuiEventListener> children() {
			List<GuiEventListener> children = new ArrayList<>(this.tools.children());
			if (this.backRow != null) {
				children.addAll(this.backRow.children());
			}

			return children;
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			boolean wentBack = this.backRow != null && this.backRow.mouseClicked(event, doubleClick);

			return wentBack || this.tools.mouseClicked(event, doubleClick);
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (this.backRow != null && this.backRow.keyPressed(event)) {
				return true;
			}

			return this.tools.keyPressed(event);
		}

		/** Nothing, which is Iris's answer: none of the three is a widget the game can read out. */
		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of();
		}
	}
}
