package dev.vitrail.screen;

import dev.vitrail.pack.load.PackLoader;
import dev.vitrail.ScreenText;
import dev.vitrail.Vitrail;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;

/**
 * The list of packs in the folder, which is what the screen opens on. This is Iris's
 * {@code ShaderPackSelectionList.java}: the row at the top that switches shaders on and off, one row
 * per pack, the line at the bottom saying a pack can be dropped in, and the entry offering somewhere
 * to get one when the folder is empty.
 * <p>
 * <b>Clicking a row selects it and nothing more.</b> No file is written and no pack is read: the
 * screen's Apply and its Done are what make a selection real, which is Iris's arrangement and is why
 * a player can look through a folder of eight packs without paying for eight loads.
 * <p>
 * <b>The folder is watched rather than polled.</b> A pack dropped in appears on its own, off a
 * {@link WatchService} registered on the directory and drained once per frame. Iris does the same, and
 * it is why neither this view nor Iris's has a Reload: nothing about the folder has to be asked for.
 * <p>
 * The four lines putting the scrollbar against the right edge sit here rather than in a shared base
 * class, because {@code AbstractSelectionList.Entry} is protected and only a subclass can name it.
 */
public final class PackList extends AbstractSelectionList<PackList.BaseEntry> {

	private static final Component DROP_LABEL = Component
			.translatable(ScreenText.PACK_DROP)
			.withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY);

	/**
	 * The background Iris blits under its list, which is the screen's own backdrop texture rather than
	 * the darker one the game's lists use. Taken as it is, since it is what its screen looks like; the
	 * in world variant is the game's answer to the same question and is kept, where Iris names the one
	 * texture and so loses that distinction in a world.
	 */
	private static final Identifier IN_WORLD_BACKGROUND =
			Identifier.withDefaultNamespace("textures/gui/inworld_menu_background.png");

	/** Below this the background and the separators are not drawn at all, which is Iris's cut. */
	private static final float INVISIBLE = 0.02F;

	/** The least alpha the separators are ever drawn at once they are drawn, also Iris's. */
	private static final float FAINTEST = 0.01F;

	/**
	 * Where a pack comes from when the folder is empty. Iris sends people to Modrinth from here,
	 * which is a store this mod is not on, so the address is the CurseForge search instead. It asks
	 * for the shader class and nothing else: the same search filtered on a game version answers
	 * with the packs whose author has uploaded a file tagged for it, which is close to none of them
	 * in the weeks after a game release, exactly when somebody arrives here with an empty folder.
	 */
	private static final String PACK_SITE =
			"https://www.curseforge.com/minecraft/search?class=shaders";

	private static final int ROW_HEIGHT = 20;

	/** How far in from the right edge the scrollbar sits, which is the bar's own width. */
	private static final int SCROLLBAR_INSET = 6;

	/** How wide a row is drawn, and how far it is kept off the edges of a wide window. */
	private static final int ROW_WIDTH = 308;
	private static final int ROW_MARGIN = 50;

	/** A row is nudged down by this, so the list does not sit against the separator above it. */
	private static final int ROW_NUDGE = 2;

	/** Where a line of text sits inside a row of {@link #ROW_HEIGHT}, and Iris's numbers. */
	private static final int TEXT_HEIGHT = 11;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int LABEL_GREY = 0xFFC2C2C2;

	/** The pack being drawn, in the yellow Iris marks it in. */
	private static final int APPLIED_YELLOW = 0xFFFFF263;

	/** Every pack while shaders are off, since none of them is being drawn. */
	private static final int DISABLED_GREY = 0xFFA2A2A2;

	private final PackHost host;
	private final Path gameDirectory;
	private final ToggleEntry toggleRow;
	private final @Nullable WatchService watcher;
	private final @Nullable WatchKey key;

	private boolean watching;
	private @Nullable PackEntry applied;

	public PackList(PackHost host, Minecraft minecraft, Path gameDirectory, String chosen,
			boolean shadersEnabled, int width, int height, int top) {
		super(minecraft, width, height, top + 4, ROW_HEIGHT);
		this.host = host;
		this.gameDirectory = gameDirectory;
		this.toggleRow = new ToggleEntry(this, shadersEnabled);

		WatchService service = null;
		WatchKey registered = null;
		try {
			service = FileSystems.getDefault().newWatchService();
			registered = PackLoader.directory(gameDirectory).register(service,
					StandardWatchEventKinds.ENTRY_CREATE,
					StandardWatchEventKinds.ENTRY_MODIFY,
					StandardWatchEventKinds.ENTRY_DELETE);
			this.watching = true;
		} catch (IOException | RuntimeException e) {
			// A folder that cannot be watched is not a broken screen: the list is built once either
			// way, and what is lost is a pack dropped in while it is open appearing on its own.
			Vitrail.logger().warn("Vitrail could not watch {} for new packs",
					PackLoader.directory(gameDirectory), e);
			this.watching = false;
		}

		this.watcher = service;
		this.key = registered;

		refresh(chosen);
	}

	@Override
	protected int scrollBarX() {
		return this.width - SCROLLBAR_INSET;
	}

	/**
	 * Nothing, which is Iris's answer too. Every row narrates itself, so a list that also announced a
	 * name of its own would say one twice.
	 */
	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}

	@Override
	public int getRowWidth() {
		return Math.min(ROW_WIDTH, this.width - ROW_MARGIN);
	}

	@Override
	public int getRowTop(int row) {
		return super.getRowTop(row) + ROW_NUDGE;
	}

	/**
	 * Swallows Up on the first row, so that walking up the list with a keyboard stops at the toggle
	 * instead of leaving the list for whatever the screen puts above it. Iris's, and the reason is that
	 * the row above the list is the title.
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isUp() && getFocused() == children().getFirst()) {
			return true;
		}

		return super.keyPressed(event);
	}

	/** Drains what the folder watcher saw, then draws. One rebuild however many events arrived. */
	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float a) {
		if (this.watching && this.key != null) {
			for (WatchEvent<?> event : this.key.pollEvents()) {
				if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
					continue;
				}

				refresh(chosenName());
				break;
			}

			// A key that cannot be reset is a folder that went away, and nothing is watched after that.
			this.watching = this.key.reset();
		}

		super.extractWidgetRenderState(graphics, mouseX, mouseY, a);
	}

	/** Lets go of the folder watcher. The screen calls this on its way out. */
	public void close() throws IOException {
		if (this.key != null) {
			this.key.cancel();
		}

		if (this.watcher != null) {
			this.watcher.close();
		}
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor graphics) {
		float alpha = this.host.listAlpha();
		if (alpha < INVISIBLE) {
			return;
		}

		Identifier texture = this.minecraft.level == null
				? Screen.MENU_BACKGROUND
				: IN_WORLD_BACKGROUND;
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, getX(), getY(), getRight(),
				getBottom() + (int) scrollAmount(), getWidth(), getHeight(), 32, 32);
	}

	@Override
	protected void extractListSeparators(GuiGraphicsExtractor graphics) {
		float alpha = this.host.listAlpha();
		if (alpha < INVISIBLE) {
			return;
		}

		// The fade reaches the blit's own colour, which is the one place on this screen where it is
		// honoured at all: everything else Iris hands it to is a widget alpha nothing reads.
		int tint = ARGB.colorFromFloat(Math.max(alpha, FAINTEST), 1.0F, 1.0F, 1.0F);
		boolean inWorld = this.minecraft.level != null;
		Identifier header = inWorld ? Screen.INWORLD_HEADER_SEPARATOR : Screen.HEADER_SEPARATOR;
		Identifier footer = inWorld ? Screen.INWORLD_FOOTER_SEPARATOR : Screen.FOOTER_SEPARATOR;

		graphics.blit(RenderPipelines.GUI_TEXTURED, header, getX(), getY() - 2, 0.0F, 0.0F,
				getWidth(), 2, 32, 2, tint);
		graphics.blit(RenderPipelines.GUI_TEXTURED, footer, getX(), getBottom(), 0.0F, 0.0F,
				getWidth(), 2, 32, 2, tint);
	}

	/**
	 * Reads the folder again and rebuilds every row, keeping whichever pack is named selected.
	 *
	 * @param chosen the file name of the pack to show as selected, or empty for none
	 */
	public void refresh(String chosen) {
		clearEntries();

		List<Path> packs;
		try {
			packs = PackLoader.candidates(this.gameDirectory);
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Vitrail could not list {}",
					PackLoader.directory(this.gameDirectory), e);
			addEntry(this.toggleRow);
			this.toggleRow.packsPresent = false;
			// Not translated, and Iris gives the reason for leaving its own untranslated: this is seen
			// very rarely, and what it is really for is getting the reader to the log, where the cause
			// is. Usually a folder this game cannot read.
			addEntry(new LabelEntry(Component
					.literal("Your shader pack folder could not be read. See the log.")
					.withStyle(ChatFormatting.RED)));

			return;
		}

		addEntry(this.toggleRow);
		// Iris offers somewhere to get a pack when there is none, and the toggle is dead for the same
		// reason: switching shaders on with an empty folder would do nothing and say nothing.
		this.toggleRow.packsPresent = !packs.isEmpty();
		if (packs.isEmpty()) {
			// Untranslated, as Iris leaves its own: the address it opens is in English either way.
			addEntry(new PinnedEntry(Component.literal("Download Shaders"), this::openPackSite));
		}

		PackEntry selected = null;
		for (Path pack : packs) {
			String name = pack.getFileName().toString();
			PackEntry entry = new PackEntry(this, name);
			addEntry(entry);

			if (name.equals(chosen)) {
				selected = entry;
			}
		}

		if (selected != null) {
			setSelected(selected);
			setFocused(selected);
			centerScrollOn(selected);
			this.applied = selected;
		}

		addEntry(new LabelEntry(DROP_LABEL));
	}

	/** Which pack the list has selected, empty when none of its rows is one. */
	public String chosenName() {
		return getSelected() instanceof PackEntry entry ? entry.packName : "";
	}

	/** Whether the toggle is showing shaders as on, which is what the screen applies. */
	public boolean shadersEnabled() {
		return this.toggleRow.shadersEnabled;
	}

	/** Whether the folder has a pack at all, which decides whether the toggle answers a click. */
	public boolean packsPresent() {
		return this.toggleRow.packsPresent;
	}

	/**
	 * Marks whatever row is selected as the one being drawn, which is what puts it in yellow. Called
	 * by the screen when it applies, since that is the moment the selection becomes the image.
	 */
	public void markSelectedApplied() {
		this.applied = getSelected() instanceof PackEntry entry ? entry : null;
	}

	/** Selects a pack by name, which is what a pack dropped onto the screen asks for. */
	public void select(String name) {
		for (BaseEntry entry : children()) {
			if (entry instanceof PackEntry pack && pack.packName.equals(name)) {
				setSelected(entry);

				return;
			}
		}
	}

	/**
	 * Somewhere to get a pack, through the game's own "do you want to open this link" screen so that
	 * nothing is opened without being asked for and the address is shown before it is followed. Iris
	 * offers a page of its own from the same place.
	 */
	private void openPackSite() {
		Screen here = this.minecraft.gui.screen();
		this.minecraft.gui.setScreen(new ConfirmLinkScreen(followed -> {
			if (followed) {
				Util.getPlatform().openUri(PACK_SITE);
			}

			this.minecraft.gui.setScreen(here);
		}, PACK_SITE, true));
	}

	/** Every row of this list, whatever it draws. */
	public abstract static class BaseEntry extends AbstractSelectionList.Entry<BaseEntry> {

		protected BaseEntry() {
		}
	}

	/** A line of text and nothing else, which is the drag and drop hint and the folder's failures. */
	public static final class LabelEntry extends BaseEntry {

		private final Component label;

		LabelEntry(Component label) {
			this.label = label;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
				boolean hovered, float a) {
			graphics.centeredText(Minecraft.getInstance().font, this.label,
					getContentX() + getContentWidth() / 2 - 2,
					getContentY() + (getContentHeight() - TEXT_HEIGHT) / 2, LABEL_GREY);
		}
	}

	/** The row at the top of the list, which switches shaders on and off for the whole engine. */
	public static final class ToggleEntry extends BaseEntry {

		private final PackList list;

		private boolean shadersEnabled;
		private boolean packsPresent = true;

		ToggleEntry(PackList list, boolean shadersEnabled) {
			this.list = list;
			this.shadersEnabled = shadersEnabled;
		}

		private void setShadersEnabled(boolean shadersEnabled) {
			this.shadersEnabled = shadersEnabled;
			this.list.host.shadersToggled();
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
				boolean hovered, float a) {
			int x = getContentX();
			int y = getContentY();
			int width = getContentWidth();
			int height = getContentHeight();

			ScreenDraw.button(graphics, x - 2, y - 2, width, height + 2, hovered, !this.packsPresent);
			graphics.centeredText(Minecraft.getInstance().font, label(), x + width / 2 - 2,
					y + (height - TEXT_HEIGHT) / 2, WHITE);
		}

		private Component label() {
			if (!this.packsPresent) {
				return Component.translatable(ScreenText.SHADERS_NONE_PRESENT)
						.withStyle(ChatFormatting.GRAY);
			}

			return Component.translatable(this.shadersEnabled
					? ScreenText.SHADERS_ENABLED
					: ScreenText.SHADERS_DISABLED);
		}

		private boolean press() {
			if (!this.packsPresent) {
				return false;
			}

			setShadersEnabled(!this.shadersEnabled);
			ScreenDraw.clickSound();

			return true;
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			return press();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			return event.isConfirmation() && press();
		}

		@Override
		public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
			return isFocused() ? null : ComponentPath.leaf(this);
		}

		@Override
		public boolean isFocused() {
			return this.list.getFocused() == this;
		}
	}

	/** A row that looks like the toggle and does one thing, used when the folder is empty. */
	public static final class PinnedEntry extends BaseEntry {

		private final Component label;
		private final Runnable action;

		PinnedEntry(Component label, Runnable action) {
			this.label = label;
			this.action = action;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
				boolean hovered, float a) {
			int x = getContentX();
			int y = getContentY();
			int width = getContentWidth();
			int height = getContentHeight();

			ScreenDraw.button(graphics, x - 2, y - 2, width, height + 2, hovered, false);
			graphics.centeredText(Minecraft.getInstance().font, this.label, x + width / 2 - 2,
					y + (height - TEXT_HEIGHT) / 2, WHITE);
		}

		private boolean press() {
			ScreenDraw.clickSound();
			this.action.run();

			return true;
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			return press();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			return event.isConfirmation() && press();
		}
	}

	/** One pack of the folder. */
	public static final class PackEntry extends BaseEntry {

		private final PackList list;
		private final String packName;

		private ScreenRectangle bounds = ScreenRectangle.empty();

		PackEntry(PackList list, String packName) {
			this.list = list;
			this.packName = packName;
		}

		public String packName() {
			return this.packName;
		}

		@Override
		public ScreenRectangle getRectangle() {
			return this.bounds;
		}

		private boolean isApplied() {
			return this.list.applied == this;
		}

		private boolean isSelected() {
			return this.list.getSelected() == this;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
				boolean hovered, float a) {
			int x = getContentX();
			int y = getContentY();
			int width = getContentWidth();
			int height = getContentHeight();
			this.bounds = new ScreenRectangle(x, y, width, height);

			if (hovered) {
				ScreenDraw.button(graphics, x - 2, y - 2, width + 4, height + 4, true, false);
			}

			Font font = Minecraft.getInstance().font;
			boolean enabled = this.list.toggleRow.shadersEnabled;
			boolean under = isMouseOver(mouseX, mouseY);

			// Measured against the bold form so that a name does not grow past the row the moment the
			// mouse is over it, and cut with the ellipsis written out rather than through the shared
			// helper, which is what Iris does here.
			String name = this.packName;
			if (font.width(Component.literal(name).withStyle(ChatFormatting.BOLD))
					> this.list.getRowWidth() - 3) {
				name = font.plainSubstrByWidth(name, this.list.getRowWidth() - 8) + "...";
			}

			MutableComponent text = Component.literal(name);
			if (under) {
				text = text.withStyle(ChatFormatting.BOLD);
			}

			int color = WHITE;
			if (enabled && isApplied()) {
				color = APPLIED_YELLOW;
			}

			if (!enabled && !under) {
				color = DISABLED_GREY;
			}

			graphics.centeredText(font, text, x + width / 2 - 2,
					y + (height - TEXT_HEIGHT) / 2, color);
		}

		/**
		 * Selects this pack, and switches shaders on if they were off.
		 * <p>
		 * Switching them on is Iris's answer to a real confusion, which its own comment records: before
		 * it, a pack could not be picked at all while shaders were off, and players did not work out
		 * that the toggle came first. Clicking a pack is unambiguous about wanting to see it.
		 */
		private boolean press() {
			boolean did = false;
			if (!this.list.toggleRow.shadersEnabled) {
				this.list.toggleRow.setShadersEnabled(true);
				did = true;
			}

			if (!isSelected()) {
				// Itself, where Iris looks its own row up by an index it was built with,
				// ShaderPackSelectionList.java:524. The index is the position among the list's rows
				// rather than among the packs, so it counts the toggle above them, and it would count
				// wrong the day another row joins it. The row is already the answer.
				this.list.setSelected(this);
				did = true;
			}

			this.list.host.packChosen();

			return did;
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			return event.button() == 0 && press();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			return event.isConfirmation() && press();
		}

		@Override
		public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
			return isFocused() ? null : ComponentPath.leaf(this);
		}

		@Override
		public boolean isFocused() {
			return this.list.getFocused() == this;
		}
	}
}
