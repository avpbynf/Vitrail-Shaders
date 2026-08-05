package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuPage;
import dev.vitrail.pack.menu.MenuValues;
import dev.vitrail.pack.source.PackLang;
import dev.vitrail.pack.source.PackLoader;
import dev.vitrail.render.PackChain;
import dev.vitrail.settings.PackSession;
import dev.vitrail.settings.SettingsFile;
import dev.vitrail.Vitrail;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The pack settings screen.
 * <p>
 * Two views rather than two screens: the list of the packs in the folder, which is what it opens
 * on, and the pages of the one being drawn, reached by the button carrying {@link ScreenText#TITLE}
 * and inactive while there is nothing to configure. The list being the root is what makes going
 * back mean one thing everywhere: up one page, then to the list, then out to whatever screen asked
 * for this one. Which view it opens on is a line of {@code vitrail/options.txt},
 * {@code screen=packs} or {@code screen=settings}, read where that file's other reserved lines are.
 * <p>
 * A pack's pages are laid out by hand, in columns, with blanks used as alignment, and packs are
 * written against Iris. So the layout rules, the way a value cycles on a click, and the amber
 * label marking a change not yet applied are taken from how Iris behaves rather than invented:
 * they are what a pack's author saw when they wrote the file. The drawing is not taken from it:
 * every widget here is a vanilla button, so the nine slice sprites, the focus ring, the tooltip
 * and the narration come from the game and work on either backend.
 * <p>
 * Escape walks back one page, then out to the pack list, then out of the screen. Leaving does not
 * apply: Iris applies on the way out and that is the one convention of its screen not kept here,
 * because a pack reloaded by a player who was only looking is a second of hitch nobody asked for.
 * <p>
 * Nothing is written and nothing is recompiled on a click, and Apply is the only button that
 * writes. It writes by reading the file first and laying the pending table over it, so that an edit
 * made by hand while this screen is open and an edit made here compose instead of overwriting each
 * other.
 */
public final class SettingsScreen extends Screen implements ScreenHost {

	private static final int HEADER_HEIGHT = 33;
	private static final int FOOTER_HEIGHT = 70;
	private static final int LINE_HEIGHT = 11;
	private static final int NARROW_BUTTON = 80;
	private static final int WIDE_BUTTON = 120;
	private static final int BUTTON_GAP = 8;

	/** How often the pack folder is looked at while the list is drawn, in milliseconds. */
	private static final long FOLDER_INTERVAL = 1000L;

	/** Wide enough for the screen's own title, which is what the way into a pack's pages says. */
	private static final int SETTINGS_BUTTON = 150;

	private final @Nullable Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	/**
	 * The pages walked through to get here, so that going back follows the way in rather than a
	 * tree the pack never described. Pages are flat and joined by name, so a stack is all there is
	 * to it.
	 */
	private final Deque<String> history = new ArrayDeque<>();

	private @Nullable PackSession session;
	private @Nullable MenuValues values;
	private @Nullable PageList list;
	private @Nullable StringWidget statusLine;
	private @Nullable String error;

	/** The page being shown, "" being the one the pack opens on. */
	private String page = "";

	/** What the list was built for last time, so that a rebuild can tell a new view from the same. */
	private String shownView = "";

	private boolean listingPacks;
	private boolean rebuildQueued;

	/** What the folder held last time it was looked at, and when. See {@link #watchFolder()}. */
	private List<String> folderNames = List.of();
	private long folderLooked;

	public SettingsScreen(@Nullable Screen parent) {
		super(Component.translatable(ScreenText.TITLE));
		this.parent = parent;
		adopt(PackChain.session().orElse(null));
		// Whatever the file says when there is no pack: the other view would be an empty page, and
		// the list is where one is picked.
		this.listingPacks = PackChain.opensOnPacks() || this.session == null;
	}

	@Override
	protected void init() {
		// init runs again on every rebuild and on every resize, and the layout keeps whatever it
		// was given last time.
		this.layout.removeChildren();
		this.layout.setHeaderHeight(
				forcedCount() > 0 ? HEADER_HEIGHT + LINE_HEIGHT : HEADER_HEIGHT);
		this.layout.setFooterHeight(FOOTER_HEIGHT);
		this.layout.addToHeader(header());

		PageList previous = this.list;
		String previousView = this.shownView;
		this.list = this.layout.addToContents(new PageList(this.minecraft, this, this.width,
				this.layout.getContentHeight(), this.layout.getHeaderHeight()));
		this.layout.addToFooter(footer());
		populate();

		this.layout.visitWidgets(widget -> this.addRenderableWidget(widget));
		repositionElements();

		// Every rebuild builds a new list, which starts at the top. Applying a setting, reloading
		// and resizing the window all rebuild without changing what is on screen, so the scroll is
		// carried over for those; walking into another page is a new view and starts at its top.
		// Set after the layout, since the list clamps this against a height it only has by then.
		this.shownView = view();
		if (previous != null && previousView.equals(this.shownView)) {
			this.list.setScrollAmount(previous.scrollAmount());
		}
	}

	/** What the list is showing, told apart by name so that a rebuild knows whether it changed. */
	private String view() {
		return this.listingPacks ? "packs" : "page " + this.page;
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		if (this.list != null) {
			this.list.updateSize(this.width, this.layout);
		}
	}

	/**
	 * Two things happen between two frames rather than on the click that asked for them.
	 * <p>
	 * A rebuild is one. Rebuilding inside a button press throws the pressed widget away while the
	 * game is still holding it: {@code ContainerEventHandler.mouseClicked} focuses whatever it just
	 * clicked once the press returns, so the focus would land on a widget no longer on screen and
	 * the next Enter would press it again, walking into a page twice or reloading twice. Waiting
	 * for the frame lets that focus land on a live widget, and the {@code clearFocus} inside
	 * {@link #rebuildWidgets()} then clears it.
	 * <p>
	 * Following the loaded pack is the other. Reload, Reset and picking a pack all read the pack
	 * again underneath this screen, and so does walking through a portal; nothing tells the screen
	 * about any of it, so it looks every frame.
	 */
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		syncWithLoadedPack();
		watchFolder();
		if (this.rebuildQueued) {
			this.rebuildQueued = false;
			rebuildWidgets();
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	/** Not the default: the default pops a screen layer nobody pushed. */
	@Override
	public void onClose() {
		// Nothing pending is written on the way out, which is the whole point: Done and Escape leave
		// the world exactly as it was drawn. What was clicked and not applied goes with this screen,
		// which every entry point builds anew.
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape() && shouldCloseOnEsc() && canGoBack()) {
			back();
			return true;
		}

		return super.keyPressed(event);
	}

	public boolean canGoBack() {
		return !this.listingPacks;
	}

	/** One step up: the page walked in from, or the list of packs once there is none left. */
	public void back() {
		if (this.listingPacks) {
			return;
		}

		if (this.history.isEmpty()) {
			openPacks();
			return;
		}

		this.page = this.history.pop();
		queueRebuild();
	}

	/** Waited for by {@link #extractRenderState}, which says why. */
	private void queueRebuild() {
		this.rebuildQueued = true;
	}

	/**
	 * Takes on a reading the render layer made on its own, which is what the image was built from.
	 * <p>
	 * The same pack keeps what is pending, which is the whole point of holding that apart from the
	 * file: a line added to {@code options.txt} greys a setting without losing the click made under
	 * it. Another pack does not, since a value set on one pack has no meaning in the next one's
	 * file, and since a menu is read once and never replaced.
	 */
	private void syncWithLoadedPack() {
		PackSession loaded = PackChain.session().orElse(null);
		PackSession held = this.session;
		if (loaded == held) {
			return;
		}

		MenuValues current = this.values;
		if (loaded != null && held != null && current != null
				&& loaded.packFileName().equals(held.packFileName())) {
			this.session = loaded;
			current.rebase(loaded.saved().values(), loaded.saved().profile(), loaded.forcedText());
			dropMissingPage();
		} else {
			adopt(loaded);
		}

		queueRebuild();
	}

	@Override
	public MenuValues values() {
		// Only ever reached from a widget, and a widget only exists when a pack was read.
		return Objects.requireNonNull(this.values, "no pack is loaded");
	}

	@Override
	public PackLang lang() {
		PackSession loaded = this.session;
		return loaded == null ? PackLang.empty() : loaded.menu().lang();
	}

	@Override
	public void refresh() {
		if (this.list != null) {
			this.list.refresh();
		}

		updateStatus();
	}

	@Override
	public void openPage(String name) {
		this.history.push(this.page);
		this.page = name;
		queueRebuild();
	}

	private void populate() {
		PageList shown = this.list;
		if (shown == null) {
			return;
		}

		if (this.listingPacks) {
			shown.show(packButtons(), 1);
			return;
		}

		PackSession loaded = this.session;
		if (loaded == null) {
			shown.show(List.of(), 1);
			return;
		}

		MenuPage current = this.page.isEmpty()
				? loaded.menu().main()
				: loaded.menu().page(this.page).orElse(loaded.menu().main());
		shown.show(current, loaded.menu().profileNames());
	}

	private LinearLayout header() {
		LinearLayout header = LinearLayout.vertical().spacing(2);
		header.addChild(new StringWidget(headerTitle(), this.font),
				LayoutSettings::alignHorizontallyCenter);

		int forced = forcedCount();
		if (forced > 0) {
			Component notice = Component.translatable(ScreenText.FORCED, forced);
			header.addChild(new StringWidget(notice, this.font),
					LayoutSettings::alignHorizontallyCenter);
		}

		return header;
	}

	private LinearLayout footer() {
		LinearLayout footer = LinearLayout.vertical().spacing(4);

		this.statusLine = footer.addChild(
				new StringWidget(status(), this.font).setMaxWidth(this.width - 20),
				LayoutSettings::alignHorizontallyCenter);
		this.statusLine.setTooltip(removedTooltip());

		// Two rows, and their shape is Iris's rather than ours, because a player who has configured a
		// pack before has configured it there. Its screen carries the folder and the view switch on
		// one line and Cancel, Apply and Done on the line under it; what belongs to a pack's own
		// pages, the way back and the two buttons that touch its file, joins the first line here
		// because this screen has no breadcrumb to hang them from.
		LinearLayout tools = footer.addChild(LinearLayout.horizontal().spacing(BUTTON_GAP),
				LayoutSettings::alignHorizontallyCenter);
		if (this.listingPacks) {
			// No Reload on the list, and both references agree: neither OptiFine's pack screen nor
			// Iris's offers to read the pack again from the screen whose whole subject is which pack
			// to read. Ours offered it by accident of layout, and it reloaded the pack to answer a
			// question about a directory listing. The folder is watched instead, see watchFolder.
			tools.addChild(button(ScreenText.FOLDER, WIDE_BUTTON, this::openFolder));
			tools.addChild(settingsButton());
		} else {
			// Back only where it means something the switch does not. On a pack's first page the two
			// are one button drawn twice: walking back from there IS going to the list, and a row
			// that offers the same door under two names is a row nobody reads.
			if (!this.history.isEmpty()) {
				tools.addChild(button(CommonComponents.GUI_BACK, NARROW_BUTTON, this::back));
			}

			tools.addChild(button(ScreenText.RELOAD, NARROW_BUTTON, this::reload));
			tools.addChild(button(ScreenText.RESET, NARROW_BUTTON, this::confirmReset));
			tools.addChild(button(ScreenText.PACKS, NARROW_BUTTON, this::openPacks));
		}

		LinearLayout commit = footer.addChild(LinearLayout.horizontal().spacing(BUTTON_GAP),
				LayoutSettings::alignHorizontallyCenter);
		// The same three, in Iris's order, on both views. None of them is ever greyed: Apply with
		// nothing waiting returns without writing rather than sitting there dead, which is the one
		// thing about a commit row a player reads at a glance.
		commit.addChild(button(CommonComponents.GUI_CANCEL, NARROW_BUTTON, this::cancelAndClose));
		commit.addChild(button(ScreenText.APPLY, NARROW_BUTTON, () -> {
			apply();
			queueRebuild();
		}));
		commit.addChild(button(CommonComponents.GUI_DONE, NARROW_BUTTON, this::onClose));

		return footer;
	}

	/**
	 * The way into the loaded pack's pages. Greyed rather than hidden when there is no pack, since a
	 * button that comes and goes teaches nothing, and it says why on hover: a folder with no pack in
	 * it and a pack that failed to read look the same from here otherwise.
	 */
	private Button settingsButton() {
		Button settings = button(this.title, SETTINGS_BUTTON, this::openSettings);
		settings.active = this.session != null;
		if (!settings.active) {
			settings.setTooltip(Tooltip.create(noPackReason()));
		}

		return settings;
	}

	/** Why there is nothing to configure: none was asked for, or none could be read. */
	private static Component noPackReason() {
		return Component.translatable(
				PackChain.noPackWanted() ? ScreenText.PACK_OFF : ScreenText.NO_PACK);
	}

	private Button button(String key, int width, Runnable action) {
		return button(Component.translatable(key), width, action);
	}

	private Button button(Component label, int width, Runnable action) {
		return Button.builder(label, _ -> action.run()).width(width).build();
	}

	private Component headerTitle() {
		if (this.listingPacks) {
			return Component.translatable(ScreenText.PACKS_TITLE);
		}

		PackSession loaded = this.session;
		if (loaded == null) {
			return this.title;
		}

		String pack = loaded.menu().packName();
		return this.page.isEmpty()
				? ScreenText.fromPack(pack)
				: ScreenText.fromPack(pack + " - " + lang().page(this.page));
	}

	private Component status() {
		String failed = this.error;
		if (failed == null) {
			failed = PackChain.lastError().orElse(null);
		}

		if (failed != null) {
			return Component.translatable(ScreenText.ERROR, failed);
		}

		if (this.session == null) {
			return noPackReason();
		}

		MenuValues current = this.values;
		int pending = current == null ? 0 : current.pendingCount();
		if (pending > 0) {
			return Component.translatable(ScreenText.PENDING, pending);
		}

		// Said here because nowhere else reaches the player. A pass this backend cannot build is
		// taken out and the rest of the pack keeps drawing, which is the right thing to do and also
		// the reason it goes unnoticed: the picture is missing an effect rather than broken. The log
		// names each one, and the log is not where anyone looks.
		List<String> gone = PackChain.removedPasses();

		return gone.isEmpty()
				? Component.empty()
				: Component.translatable(ScreenText.REMOVED, gone.size());
	}

	/** The whole sentence for each pass, on hover, since the line above only carries a count. */
	private Tooltip removedTooltip() {
		List<String> gone = PackChain.removedPasses();

		return gone.isEmpty() ? null : Tooltip.create(Component.literal(String.join("\n", gone)));
	}

	private void updateStatus() {
		StringWidget line = this.statusLine;
		if (line == null) {
			return;
		}

		Component now = status();
		if (now.equals(line.getMessage())) {
			return;
		}

		line.setMessage(now);
		// The line is centred on its own width, so a shorter message has to be laid out again.
		repositionElements();
	}

	private int forcedCount() {
		MenuValues current = this.values;
		return current == null ? 0 : current.forcedShown();
	}

	/**
	 * Cancel, which is Iris's: it drops what is waiting and leaves. Leaving by any other door keeps
	 * the world as it was drawn too, which is where this screen still differs from Iris on purpose,
	 * and the difference was asked for: there, Done and Escape both write.
	 */
	private void cancelAndClose() {
		dropPending();
		onClose();
	}

	/** Throws away what was clicked and never applied, and puts the widgets back on their values. */
	private void dropPending() {
		MenuValues current = this.values;
		if (current != null) {
			current.clearPending();
		}

		refresh();
	}

	/**
	 * Writes what is pending, then has the pack read again from the file that was just written.
	 * <p>
	 * The order is not commutative. The file stays the only source of truth, so nothing is handed
	 * to the reload in memory, and the reload resynchronises the watcher on what is now on disk or
	 * it would read the same change a second time within the second. It runs on the render thread,
	 * which is where a button press already is and where the GPU buffers a reload closes have to
	 * be closed.
	 */
	private void apply() {
		PackSession loaded = this.session;
		MenuValues current = this.values;
		if (loaded == null || current == null) {
			return;
		}

		// Nothing waiting, nothing written. The button stays live rather than greying out, since a
		// commit row is read at a glance and a grey Apply says the screen is stuck; what it must not
		// do is spend a second reading the pack again to write the file it already holds.
		if (current.pendingCount() == 0) {
			return;
		}

		try {
			// Read from where the session read, which is Iris's file until we have written one of
			// our own; reading our own too early would drop everything it had imported. Written
			// only ever to ours.
			SettingsFile.Stored onDisk = SettingsFile.read(loaded.readFrom());
			current.rebase(onDisk.values(), onDisk.profile(), loaded.forcedText());
			SettingsFile.write(loaded.settingsFile(),
					new SettingsFile.Stored(current.toSave(), current.chosenProfile()));
			current.clearPending();
			this.error = null;
		} catch (IOException | RuntimeException e) {
			this.error = String.valueOf(e.getMessage());
			Vitrail.logger().error("Vitrail could not write {}", loaded.settingsFile(), e);
			// The screen stays open and the status line carries the message, which is all a failed
			// write owes now that leaving no longer writes: what was pending is still pending.
			return;
		}

		PackChain.reload(loaded.gameDirectory());
		adopt(PackChain.session().orElse(null));
	}

	/**
	 * Reads everything again from disk, which is what a file edited by hand does on its own. It
	 * goes through {@link #syncWithLoadedPack()} rather than taking the new session straight on, so
	 * that a pending value survives it: Cancel is the button that drops one, and a reload the player
	 * asked for has no more reason to drop it than a reload the watcher noticed.
	 */
	private void reload() {
		this.error = null;
		PackChain.reload(gameDirectory());
		syncWithLoadedPack();
		queueRebuild();
	}

	/**
	 * Throws away this pack's settings file, so that it goes back to what the pack itself declares.
	 * <p>
	 * Three things it deliberately does not do. It does not touch {@code vitrail/options.txt}, which
	 * is the file that forces settings from outside and is not the player's to lose here; the
	 * greyed out widgets stay greyed out after a reset, which is the honest answer. It does not
	 * touch the file Iris left in {@code shaderpacks/}, which is read but never written. And it
	 * drops what is pending rather than keeping it, because a pending value is a change the player
	 * made to the settings this is discarding, so keeping it would put back part of what was asked
	 * to be thrown away.
	 * <p>
	 * No confirmation is asked. The file holds only what differs from the pack's own defaults, so
	 * this loses a set of choices and nothing else, and the reload right after shows the result
	 * immediately.
	 */
	/**
	 * Reset asks first, and it is the only button here that does. It is also the only one that
	 * deletes something a player wrote: a settings file can hold an evening of tuning, and it sits
	 * two slots from Back on a row where every other button is harmless.
	 * <p>
	 * The confirmation is the game's own screen rather than a panel of ours, so it is worded, laid
	 * out and narrated like every other confirmation the player has already answered. Coming back
	 * hands it this same screen, which rebuilds itself: the page walked into, the scroll and what
	 * is pending are fields and none of them is touched by going away and returning.
	 */
	private void confirmReset() {
		PackSession loaded = this.session;
		if (loaded == null) {
			return;
		}

		Minecraft client = this.minecraft;
		client.gui.setScreen(new ConfirmScreen(
				yes -> {
					client.gui.setScreen(this);
					if (yes) {
						reset();
					}
				},
				Component.translatable(ScreenText.RESET_CONFIRM, loaded.packFileName()),
				Component.translatable(ScreenText.RESET_CONFIRM_DETAIL,
						loaded.settingsFile().getFileName().toString())));
	}

	private void reset() {
		PackSession loaded = this.session;
		if (loaded == null) {
			return;
		}

		try {
			SettingsFile.delete(loaded.settingsFile());
			this.error = null;
		} catch (IOException | RuntimeException e) {
			this.error = String.valueOf(e.getMessage());
			Vitrail.logger().error("Vitrail could not delete {}", loaded.settingsFile(), e);

			return;
		}

		Vitrail.logger().info("{} is back to the settings it declares itself, {} is gone",
				loaded.packFileName(), loaded.settingsFile().getFileName());
		PackChain.reload(loaded.gameDirectory());
		adopt(PackChain.session().orElse(null));
		queueRebuild();
	}

	/**
	 * Opens the folder the packs are read from, which is how a pack gets into the list in the first
	 * place. Both references put this button on this screen, and it is the reason the list has to
	 * notice a folder that changed while it is open.
	 */
	private void openFolder() {
		Path directory = PackLoader.directory(gameDirectory());
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			// Opened anyway: a folder that cannot be created is one the platform will report on
			// better than this line could, and the packs are read from it either way.
			Vitrail.logger().warn("Vitrail could not create {}", directory, e);
		}

		Util.getPlatform().openPath(directory);
	}

	/**
	 * Notices a pack dropped into the folder while this screen is open, without a button asking for
	 * it. OptiFine does the same on a timer of its own; the alternative, a Reload on the pack list,
	 * reads the whole pack again to answer a question about a directory listing.
	 * <p>
	 * Names only, and only while the list is the view being drawn. The folder is a handful of files
	 * and this runs once a second, which is the same budget the engine already spends looking at
	 * whether the world moved.
	 */
	private void watchFolder() {
		if (!this.listingPacks) {
			return;
		}

		long now = Util.getMillis();
		if (now - this.folderLooked < FOLDER_INTERVAL) {
			return;
		}

		this.folderLooked = now;
		List<String> names;
		try {
			names = PackLoader.candidates(gameDirectory()).stream()
					.map(pack -> pack.getFileName().toString())
					.toList();
		} catch (IOException e) {
			// The list keeps what it has. A folder that cannot be listed is already said once, where
			// the buttons are built, and saying it again every second would be the whole log.
			return;
		}

		if (!names.equals(this.folderNames)) {
			this.folderNames = names;
			queueRebuild();
		}
	}

	private void openPacks() {
		// Dropped on the way out, and this is the one place that drops them. A pending value belongs
		// to a page of one pack; carrying it to a list where the next click may load another pack
		// leaves it waiting for a file it was never meant for, and the count in the status line then
		// names settings the reader can no longer see.
		dropPending();
		this.listingPacks = true;
		queueRebuild();
	}

	private void openSettings() {
		this.listingPacks = false;
		queueRebuild();
	}

	private List<AbstractWidget> packButtons() {
		Path directory = PackLoader.directory(gameDirectory());
		List<Path> packs;
		try {
			packs = PackLoader.candidates(gameDirectory());
		} catch (IOException e) {
			Vitrail.logger().warn("Vitrail could not list {}", directory, e);
			packs = List.of();
		}

		PackSession loaded = this.session;
		String drawn = loaded == null ? "" : loaded.packFileName();

		List<AbstractWidget> buttons = new ArrayList<>(packs.size() + 1);
		buttons.add(noneButton());
		for (Path pack : packs) {
			String name = pack.getFileName().toString();
			Button button = Button.builder(Component.literal(name), _ -> choosePack(name))
					.width(Button.BIG_WIDTH).build();
			button.active = !name.equals(drawn);
			buttons.add(button);
		}

		return buttons;
	}

	/**
	 * Turning every pack off, first in the list rather than last, where a folder of eight would put
	 * it out of sight. It writes the same file the pack entries write, and what it asks for is the
	 * path the engine already takes when the folder is empty: nothing is read and the game keeps
	 * its own image.
	 */
	private Button noneButton() {
		// The game's own word for it, so it reads in the player's language on a client we ship no
		// translation for.
		Button none = Button.builder(Component.translatable("gui.none"),
				_ -> choosePack(PackChain.NO_PACK)).width(Button.BIG_WIDTH).build();
		none.active = !PackChain.noPackWanted();
		none.setTooltip(Tooltip.create(Component.translatable(ScreenText.PACK_OFF)));

		return none;
	}

	/**
	 * Writes the whole file name rather than the fragment {@code pack.txt} also accepts, so that
	 * two packs sharing a word cannot swap under the player, or {@link PackChain#NO_PACK} for none
	 * of them.
	 */
	private void choosePack(String line) {
		// Whatever is pending goes with the pack it was set on, unwritten. It was never this pack's
		// to write on the way past, and the next pack's file is the wrong place for it.
		Path file = PackChain.packFile(gameDirectory());
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8);
			this.error = null;
		} catch (IOException e) {
			this.error = String.valueOf(e.getMessage());
			Vitrail.logger().error("Vitrail could not write {}", file, e);
			queueRebuild();
			return;
		}

		PackChain.reload(gameDirectory());
		adopt(PackChain.session().orElse(null));
		// Left on the list, which now greys the pack it just switched to and offers its settings
		// one button away. Walking straight into them would make trying a second pack a round trip.
		queueRebuild();
	}

	/**
	 * Takes on what the render layer has just read, which is what the image was built from. A page
	 * the new pack does not lay out, and the way back to it, go with the old one.
	 */
	private void adopt(@Nullable PackSession loaded) {
		this.session = loaded;
		this.values = loaded == null ? null : MenuValues.of(loaded.menu(),
				loaded.saved().values(), loaded.saved().profile(), loaded.forcedText());
		dropMissingPage();
	}

	private void dropMissingPage() {
		PackSession loaded = this.session;
		if (loaded == null || (!this.page.isEmpty() && loaded.menu().page(this.page).isEmpty())) {
			this.page = "";
			this.history.clear();
		}
	}

	private Path gameDirectory() {
		PackSession loaded = this.session;
		return loaded == null ? Vitrail.platform().gameDirectory() : loaded.gameDirectory();
	}
}
