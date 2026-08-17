package dev.vitrail.screen;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.menu.MenuValues;
import dev.vitrail.pack.source.PackLang;
import dev.vitrail.pack.source.PackLoader;
import dev.vitrail.render.PackChain;
import dev.vitrail.settings.PackFile;
import dev.vitrail.settings.PackSession;
import dev.vitrail.settings.SettingsFile;
import dev.vitrail.uniform.Smoothed;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The pack settings screen, ported from Iris's {@code ShaderPackScreen}.
 * <p>
 * <b>Two views and one screen</b>: the list of packs in the folder, {@link PackList}, and the pages of
 * the pack being configured, {@link OptionList}. The switch between them sits above the bottom row, and
 * it applies on the way through, which is Iris's own note and not a detail: picking a pack in the list
 * without applying and then opening the settings would otherwise open the settings of the pack before
 * it.
 * <p>
 * <b>Three buttons on the bottom row, and leaving applies.</b> Cancel drops what was clicked and
 * leaves, Apply writes and reloads without leaving, Done applies and leaves. That last one is Iris's
 * arrangement and is the one convention of this screen that the screen it replaces deliberately did
 * not have; it is back, because a pack author who has used Iris expects the settings they just changed
 * to be there when they close the screen.
 * <p>
 * <b>The eye, and F1, take the screen away rather than the world.</b> Both hide every widget so that
 * the world behind can be looked at while a setting is judged, and Escape brings them back. This is
 * what makes the screen usable for the one thing it is for.
 * <p>
 * <b>Three facts this engine has and Iris does not</b> are drawn where Iris draws its own name, at the
 * bottom left: a load that failed, how many settings {@code vitrail/options.txt} is holding down, and
 * how many passes this backend could not build. Nothing else on the screen reaches the player with
 * them, and the log is not where anyone looks.
 */
public final class SettingsScreen extends Screen implements PackHost, ScreenHost {

	/** How far down the lists start, and how much room the two button rows take under them. */
	private static final int LIST_TOP = 32;
	private static final int FOOTER_ROOM = 94;

	/** The bottom row: three buttons of this width, a hundred and four apart, at this height. */
	private static final int BUTTON_WIDTH = 100;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_PITCH = 104;

	/** The row above it: two wider buttons, a hundred and fifty six apart. */
	private static final int WIDE_BUTTON_WIDTH = 152;
	private static final int WIDE_BUTTON_PITCH = 78;

	/** Where the eye sits, and where its sprite is cut from the atlas. */
	private static final int EYE_SIZE = 20;
	private static final int EYE_V = 146;

	private static final int COMMENT_PANEL_WIDTH = 314;

	/** How long a line under the title stays up, in ticks. Iris's five seconds. */
	private static final int NOTIFICATION_TICKS = 100;

	/** How many frames the mouse has to rest on a cell before its comment panel comes up. */
	private static final int COMMENT_DELAY = 10;

	/** The half lives of the two fades, in deciseconds, and Iris's numbers. */
	private static final float BLUR_HALF_LIFE = 2.0F;
	private static final float LIST_HALF_LIFE = 1.0F;

	/**
	 * How much blur the option page asks for, which is Iris's way of asking for none: the background is
	 * blurred only past a radius of one, so a tenth reads as off while still fading rather than
	 * switching.
	 */
	private static final float OPTIONS_BLUR = 0.1F;

	private static final int WHITE = 0xFFFFFFFF;

	private static final Component SELECT_TITLE = Component
			.translatable(ScreenText.SELECT_TITLE)
			.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	private static final Component CONFIGURE_TITLE = Component
			.translatable(ScreenText.CONFIGURE_TITLE)
			.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

	private final @Nullable Screen parent;

	/**
	 * The pages walked through to get here, so that going back follows the way in rather than a tree
	 * the pack never described. Pages are flat and joined by name, so a stack is all there is to it.
	 * This is Iris's {@code NavigationController}, whose whole content is this deque.
	 */
	private final Deque<String> history = new ArrayDeque<>();

	/** What has to be drawn after every list entry, so that the next row cannot cover a tooltip. */
	private final List<Runnable> topLayer = new ArrayList<>();

	private final Smoothed blurFade = new Smoothed();
	private final Smoothed listFade = new Smoothed();

	private @Nullable PackList packList;
	private @Nullable OptionList optionList;
	private @Nullable PackSession session;
	private @Nullable MenuValues values;
	private @Nullable Button viewSwitch;
	private @Nullable Button folderButton;
	private @Nullable Button eyeButton;

	private @Nullable String error;

	private @Nullable Component notification;
	private int notificationTicks;

	private @Nullable PageWidget hoveredCell;
	private Optional<Component> commentTitle = Optional.empty();
	private List<FormattedCharSequence> commentBody = List.of();
	private int commentTicks;

	/** The page being shown, "" being the one the pack opens on. */
	private String page = "";

	private boolean optionsOpen;
	private boolean guiHidden;
	private boolean dropChanges;

	/** Whether a frame has been drawn yet, which is what the list's fade fades in from. */
	private boolean started;

	private float blurAlpha;
	private float listAlpha;
	private long lastFrame;

	public SettingsScreen(@Nullable Screen parent) {
		super(Component.translatable(ScreenText.PACKS_TITLE));
		this.parent = parent;
		adopt(PackChain.session().orElse(null));
		// Whatever the file says when there is no pack: the other view would be an empty page, and the
		// list is where one is picked.
		this.optionsOpen = !PackChain.opensOnPacks() && this.session != null;
	}

	@Override
	protected void init() {
		int bottomCentre = this.width / 2 - BUTTON_WIDTH / 2;
		int topCentre = this.width / 2 - WIDE_BUTTON_WIDTH / 2;
		int listTop = LIST_TOP + 4;
		int listHeight = Math.max(0, this.height - FOOTER_ROOM - listTop);

		// Rebuilt rather than resized, which is what Iris does, with the pack the list had kept
		// selected carried across: a resize that lost it would drop a pack chosen and not yet applied.
		String chosen = this.packList == null
				? PackChain.askedFor().name()
				: this.packList.chosenName();
		boolean enabled = this.packList == null
				? PackChain.askedFor().enabled()
				: this.packList.shadersEnabled();

		// Let go of the old folder watcher first. Iris does not, and leaks a watch key per resize.
		closePackList();
		this.packList = new PackList(this, this.minecraft, gameDirectory(), chosen, enabled,
				this.width, listHeight, LIST_TOP);

		PackSession loaded = this.session;
		if (loaded == null) {
			this.optionsOpen = false;
			this.optionList = null;
		} else {
			this.optionList = new OptionList(this, this.minecraft, this.width, listHeight, LIST_TOP);
			this.optionList.show(loaded.menu(), this.page);
		}

		this.clearWidgets();

		if (!this.guiHidden) {
			addRenderableWidget(this.optionsOpen && this.optionList != null
					? this.optionList
					: this.packList);

			addRenderableWidget(PanelButton.of(bottomCentre - BUTTON_PITCH, this.height - 27,
					BUTTON_WIDTH, BUTTON_HEIGHT, CommonComponents.GUI_CANCEL,
					this::dropChangesAndClose));
			addRenderableWidget(PanelButton.of(bottomCentre, this.height - 27, BUTTON_WIDTH,
					BUTTON_HEIGHT, Component.translatable(ScreenText.APPLY), this::applyChanges));
			addRenderableWidget(PanelButton.of(bottomCentre + BUTTON_PITCH, this.height - 27,
					BUTTON_WIDTH, BUTTON_HEIGHT, CommonComponents.GUI_DONE, this::onClose));

			this.folderButton = addRenderableWidget(PanelButton.of(
					topCentre - WIDE_BUTTON_PITCH, this.height - 51, WIDE_BUTTON_WIDTH,
					BUTTON_HEIGHT, Component.translatable(ScreenText.FOLDER), this::openFolder));
			this.viewSwitch = addRenderableWidget(PanelButton.of(
					topCentre + WIDE_BUTTON_PITCH, this.height - 51, WIDE_BUTTON_WIDTH,
					BUTTON_HEIGHT, Component.empty(), this::switchView));
			refreshViewSwitch();
			addRenderableWidget(reload());
		}

		if (this.minecraft.level != null) {
			this.eyeButton = addRenderableWidget(eye());
		} else {
			this.eyeButton = null;
		}

		// Never let a comment panel outlive the page it belonged to, which is Iris's own fix for a
		// panel that stayed up after the screen had moved on.
		this.hoveredCell = null;
		this.commentTicks = 0;
	}

	/**
	 * Reads the whole pack again from disk, which nothing else here does on its own: Apply reads the
	 * pack again only when it had something to write, so a GLSL file edited by hand while the game
	 * runs would otherwise need a restart. That is the loop this engine is developed on.
	 * <p>
	 * Iris answers the same need, and answers it on a key rather than on a button: its
	 * {@code iris.keybind.reload}, which {@link SettingsKey#RELOAD} is. The button is here as well
	 * because the game feeds a key mapping only while no screen is open, so from this screen the key
	 * cannot be pressed at all. Its sprite is the circular arrow Iris cuts at 12,0 of the widgets
	 * file and never uses.
	 * <p>
	 * A sprite rather than a word, in the free space to the left of the button rows, mirroring the eye
	 * in the free space to the right.
	 */
	private Button reload() {
		return PanelButton.icon(besideRows(true), this.height - 39, EYE_SIZE, ScreenDraw.Icon.REFRESH,
				Component.translatable(ScreenText.RELOAD), () -> {
					this.error = null;
					reloadPack();
				});
	}

	/**
	 * Where a lone twenty wide button sits beside the button rows, which is Iris's arithmetic for its
	 * eye and this screen's mirror of it for the reload.
	 * <p>
	 * The rows are centred and three hundred and eight wide, so what is free is whatever the window
	 * has past them on that side: plenty of room puts the button at a fixed inset from the edge, a
	 * little centres it in what is left, and none at all presses it against the edge rather than
	 * pushing it off.
	 */
	private int besideRows(boolean onTheLeft) {
		float edge = this.width / 2.0F + (onTheLeft ? -154.0F : 154.0F);
		float free = onTheLeft ? edge : this.width - edge;

		if (free > 100.0F) {
			return onTheLeft ? 50 - EYE_SIZE : this.width - 50;
		}

		if (free < 20.0F) {
			return onTheLeft ? 0 : this.width - EYE_SIZE;
		}

		return (int) (onTheLeft ? free / 2.0F : edge + free / 2.0F) - EYE_SIZE / 2;
	}

	/**
	 * The one button with no room for a word: the eye that takes the screen away. Placed in whatever
	 * space is left to the right of the button rows, which is Iris's arithmetic.
	 */
	private Button eye() {
		int x = besideRows(false);

		Component label = Component
				.translatable(this.guiHidden ? ScreenText.GUI_SHOW : ScreenText.GUI_HIDE);
		Button button = TextureButton.of(x, this.height - 39, EYE_SIZE, EYE_SIZE,
				this.guiHidden ? EYE_SIZE : 0, EYE_V, EYE_SIZE, ScreenDraw.WIDGETS, label,
				this::toggleHidden);
		button.setTooltip(Tooltip.create(label));
		// Ten seconds, which is Iris's: the tooltip would otherwise cover the world this button exists
		// to uncover.
		button.setTooltipDelay(Duration.ofSeconds(10));

		return button;
	}

	@Override
	protected void extractBlurredBackground(GuiGraphicsExtractor graphics) {
		float wanted = (float) this.minecraft.options.getMenuBackgroundBlurriness();
		if (Math.min(wanted, this.blurAlpha) >= 1.0F) {
			graphics.blurBeforeThisStratum();
		}
	}

	/**
	 * Two things happen here rather than on the click that asked for them.
	 * <p>
	 * Following the loaded pack is one. Apply, the reset button and walking through a portal all read
	 * the pack again underneath this screen, and nothing tells the screen about any of it, so it looks
	 * every frame.
	 * <p>
	 * The two fades are the other, and their order matters: the list's fade reads {@link #started},
	 * which is set afterwards, so the first frame folds in a nought and every frame after it a one.
	 * Setting it first would give the fade its final value outright and there would be no fade at all.
	 */
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		syncWithLoadedPack();
		advanceFades();
		this.started = true;

		if (this.guiHidden) {
			if (this.eyeButton != null) {
				this.eyeButton.extractRenderState(graphics, mouseX, mouseY, a);
			}
		} else {
			super.extractRenderState(graphics, mouseX, mouseY, a);

			graphics.centeredText(this.font, this.title, this.width / 2, 8, WHITE);
			graphics.centeredText(this.font, subtitle(), this.width / 2, 21, WHITE);
			drawComment(graphics);
		}

		// After every list entry, and before the engine's own line, which nothing may cover.
		for (Runnable draw : this.topLayer) {
			draw.run();
		}

		this.topLayer.clear();

		Component note = engineNote();
		if (!note.getString().isEmpty()) {
			graphics.text(this.font, note, 2, this.height - 10, WHITE);
		}
	}

	private void advanceFades() {
		long now = Util.getMillis();
		float dt = this.lastFrame == 0L ? 0.0F : (now - this.lastFrame) / 1000.0F;
		this.lastFrame = now;

		float blur;
		if (this.guiHidden) {
			blur = 0.0F;
		} else if (this.optionsOpen) {
			blur = OPTIONS_BLUR;
		} else {
			blur = (float) this.minecraft.options.getMenuBackgroundBlurriness();
		}

		this.blurAlpha = this.blurFade.updateAndGet(blur, BLUR_HALF_LIFE, BLUR_HALF_LIFE, dt);

		float list = this.guiHidden || this.optionsOpen || !this.started ? 0.0F : 1.0F;
		this.listAlpha = this.listFade.updateAndGet(list, LIST_HALF_LIFE, LIST_HALF_LIFE, dt);
	}

	/** The notification while it lasts, and which view is being shown once it has gone. */
	private Component subtitle() {
		Component said = this.notification;
		if (said != null && this.notificationTicks > 0) {
			return said;
		}

		return this.optionsOpen ? CONFIGURE_TITLE : SELECT_TITLE;
	}

	/**
	 * The one line at the bottom left, which is where Iris draws its own name and version. Ours carries
	 * the engine's news instead, worst first: a load that failed, then what is being held down from
	 * outside, then what could not be built.
	 */
	private Component engineNote() {
		String failed = this.error == null ? PackChain.lastError().orElse(null) : this.error;
		if (failed != null) {
			return Component.translatable(ScreenText.ERROR, failed)
					.withStyle(ChatFormatting.RED);
		}

		if (this.session == null) {
			return Component
					.translatable(PackChain.noPackWanted() ? ScreenText.PACK_OFF : ScreenText.NO_PACK)
					.withStyle(ChatFormatting.GRAY);
		}

		MenuValues current = this.values;
		int forced = current == null ? 0 : current.forcedShown();
		if (forced > 0) {
			return Component.translatable(ScreenText.FORCED, forced)
					.withStyle(ChatFormatting.GOLD);
		}

		List<String> gone = PackChain.removedPasses();

		return gone.isEmpty()
				? Component.empty()
				: Component.translatable(ScreenText.REMOVED, gone.size())
						.withStyle(ChatFormatting.GRAY);
	}

	private void drawComment(GuiGraphicsExtractor graphics) {
		if (!showingComment()) {
			return;
		}

		int panelHeight = Math.max(50, 18 + this.commentBody.size() * 10);
		int x = this.width / 2 - 157;
		int y = this.height - (panelHeight + 4);

		ScreenDraw.panel(graphics, x, y, COMMENT_PANEL_WIDTH, panelHeight);
		graphics.text(this.font, this.commentTitle.orElse(Component.empty()), x + 4, y + 4, WHITE);
		for (int i = 0; i < this.commentBody.size(); i++) {
			graphics.text(this.font, this.commentBody.get(i), x + 4, y + 16 + i * 10, WHITE);
		}
	}

	@Override
	public void tick() {
		super.tick();

		if (this.notificationTicks > 0) {
			this.notificationTicks--;
		}

		if (this.hoveredCell == null) {
			this.commentTicks = 0;
		} else {
			this.commentTicks++;
		}
	}

	/**
	 * Escape unwinds one step at a time: the hidden screen comes back, then one page, then the pack
	 * list, then the screen closes. Tab swaps the two views. F1 is the eye.
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			if (this.guiHidden) {
				toggleHidden();

				return true;
			}

			if (!this.history.isEmpty()) {
				back();

				return true;
			}

			if (this.optionsOpen) {
				this.optionsOpen = false;
				rebuildWidgets();

				return true;
			}
		} else if (event.isCycleFocus()) {
			PackList list = this.packList;
			if (!this.optionsOpen && list != null) {
				// Iris presses the focused row before switching, so that Tab from the list opens the
				// settings of the pack the keyboard is on rather than of the pack that was applied.
				list.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
			}

			switchView();
			// Cleared after the rebuild put it back, so that the Tab falling through below walks the
			// new view from its start rather than from whatever the rebuild happened to focus.
			setFocused(null);
		} else if (event.key() == GLFW.GLFW_KEY_F1 && this.minecraft.level != null) {
			toggleHidden();

			return true;
		}

		// Nothing else reaches a screen that is not being drawn.
		return this.guiHidden || super.keyPressed(event);
	}

	private void toggleHidden() {
		this.guiHidden = !this.guiHidden;
		rebuildWidgets();
	}

	/**
	 * Swaps the two views, applying on the way. Iris's own note gives the reason for applying: picking
	 * a pack in the list without applying and then opening the settings would open the settings of the
	 * pack before it.
	 */
	private void switchView() {
		this.optionsOpen = !this.optionsOpen;
		applyChanges();
		rebuildWidgets();
	}

	private void refreshViewSwitch() {
		Button button = this.viewSwitch;
		if (button == null) {
			return;
		}

		button.setMessage(Component
				.translatable(this.optionsOpen ? ScreenText.PACKS : ScreenText.TITLE));
		// Dead while there is nothing to configure, and it says why on hover: a folder with no pack in
		// it and a pack that failed to read look the same from here otherwise.
		PackList list = this.packList;
		boolean anythingToConfigure = this.session != null
				&& (list == null || list.shadersEnabled())
				&& !this.session.menu().main().slots().isEmpty();
		button.active = this.optionsOpen || anythingToConfigure;
		button.setTooltip(button.active ? null : Tooltip.create(noPackReason()));
	}

	private static Component noPackReason() {
		return Component.translatable(
				PackChain.noPackWanted() ? ScreenText.PACK_OFF : ScreenText.NO_PACK);
	}

	@Override
	public float listAlpha() {
		return this.listAlpha;
	}

	/**
	 * How wide a blur this screen wants under it this frame, for the frame's own uniform to be held
	 * down to.
	 * <p>
	 * Asking for the blur, which {@link #extractBlurredBackground} does, and saying how wide it is are
	 * two different things in this game, and the second one is not the screen's to say: the radius
	 * reaches the shader through {@code GlobalSettingsUniform}, which the game fills from the option
	 * alone. So the fade is only half a fade until {@code GameRendererBlurMixin} holds that argument
	 * down to this, and it shows: the eye takes the widgets away in one frame while a blur still at
	 * its full width waits for this value to fall under one and then goes out at once. Iris does
	 * exactly the same two things, {@code ShaderPackScreen.java:136} for the asking and
	 * {@code MixinGameRenderer.java:67} for the width.
	 */
	public int blurRadius() {
		return (int) this.blurAlpha;
	}

	@Override
	public void shadersToggled() {
		refreshViewSwitch();
	}

	@Override
	public void focusBottomRow() {
		setFocused(this.folderButton);
	}

	@Override
	public MenuValues values() {
		// Only ever reached from a cell, and a cell only exists when a pack was read.
		return Objects.requireNonNull(this.values, "no pack is loaded");
	}

	@Override
	public PackLang lang() {
		PackSession loaded = this.session;

		return loaded == null ? PackLang.empty() : loaded.menu().lang();
	}

	@Override
	public void refresh() {
		if (this.optionList != null) {
			this.optionList.refresh();
		}
	}

	@Override
	public void openPage(String name) {
		this.history.push(this.page);
		this.page = name;
		showPage();
	}

	@Override
	public void back() {
		this.page = this.history.isEmpty() ? "" : this.history.pop();
		showPage();
	}

	private void showPage() {
		PackSession loaded = this.session;
		if (this.optionList != null && loaded != null) {
			this.optionList.show(loaded.menu(), this.page);
		}
	}

	@Override
	public boolean showingComment() {
		return this.commentTicks > COMMENT_DELAY && this.commentTitle.isPresent()
				&& !this.commentBody.isEmpty();
	}

	/**
	 * Takes on the comment of whichever cell the mouse is over. Told for every cell of every row on
	 * every frame, so the two branches are "this one has just become the one" and "this one has just
	 * stopped being it".
	 */
	@Override
	public void hovered(PageWidget widget, boolean hovered) {
		if (hovered && widget != this.hoveredCell) {
			this.hoveredCell = widget;
			this.commentTitle = widget.commentTitle();
			this.commentBody = widget.commentBody().map(this::wrapComment).orElse(List.of());
			this.commentTicks = 0;
		} else if (!hovered && widget == this.hoveredCell) {
			this.hoveredCell = null;
			this.commentTitle = Optional.empty();
			this.commentBody = List.of();
			this.commentTicks = 0;
		}
	}

	/**
	 * One line per sentence, then wrapped to the panel. Iris splits on a full stop followed by a space
	 * and drops a trailing one, which is what turns a pack's single run-on comment into something
	 * readable in a panel four lines tall.
	 */
	private List<FormattedCharSequence> wrapComment(Component comment) {
		String text = comment.getString();
		if (text.endsWith(".")) {
			text = text.substring(0, text.length() - 1);
		}

		List<FormattedCharSequence> lines = new ArrayList<>();
		for (String sentence : Arrays.stream(text.split("\\. [ ]*")).toList()) {
			lines.addAll(this.font.split(Component.literal(sentence), COMMENT_PANEL_WIDTH - 8));
		}

		return List.copyOf(lines);
	}

	@Override
	public void onTop(Runnable draw) {
		this.topLayer.add(draw);
	}

	@Override
	public void announce(Component message) {
		this.notification = message;
		this.notificationTicks = NOTIFICATION_TICKS;
	}

	@Override
	public Path settingsFile() {
		PackSession loaded = this.session;

		return loaded == null
				? SettingsFile.of(gameDirectory(), "")
				: loaded.settingsFile();
	}

	/**
	 * Asks first, and it is the only control on this screen that does. It is also the only one that
	 * throws away something a player wrote: a settings file can hold an evening of tuning.
	 * <p>
	 * Iris guards the same button with shift held instead ({@code ShaderPackOptionList.java:294}), and
	 * this screen asked before the port, so the confirmation is what came back. One guard and not two.
	 * The confirmation is the game's own screen rather than a panel of ours, so it is worded, laid out
	 * and narrated like every other confirmation the player has already answered. Coming back hands it
	 * this same screen, which rebuilds itself: the page walked into, the scroll and what is pending are
	 * fields and none of them is touched by going away and returning.
	 */
	@Override
	public void resetSettings() {
		PackSession loaded = this.session;
		if (loaded == null) {
			return;
		}

		Minecraft client = this.minecraft;
		client.gui.setScreen(new ConfirmScreen(
				yes -> {
					client.gui.setScreen(this);
					if (yes) {
						emptySettings();
					}
				},
				Component.translatable(ScreenText.RESET_CONFIRM, loaded.packFileName()),
				Component.translatable(ScreenText.RESET_CONFIRM_DETAIL,
						loaded.settingsFile().getFileName().toString())));
	}

	/**
	 * Empties this pack's settings file, so that it goes back to what the pack itself declares, then
	 * applies.
	 * <p>
	 * Emptied and not deleted, where Iris deletes its own ({@code Iris.java:464-471}). The two read back
	 * the same on both sides, a file carrying no value at all, and Iris removes it itself the next time
	 * it loads the pack; what emptying buys is that a player who has that file open sees it go blank.
	 * <p>
	 * Two things it deliberately does not do. It does not touch {@code vitrail/options.txt}, which is
	 * the file that forces settings from outside and is not the player's to lose here. And it drops what
	 * is pending rather than keeping it, because a pending value is a change to the settings this is
	 * discarding.
	 */
	private void emptySettings() {
		PackSession loaded = this.session;
		if (loaded == null) {
			return;
		}

		MenuValues current = this.values;
		if (current != null) {
			current.clearPending();
		}

		try {
			SettingsFile.write(loaded.settingsFile(), SettingsFile.Stored.empty());
			this.error = null;
		} catch (IOException | RuntimeException e) {
			this.error = String.valueOf(e.getMessage());
			Vitrail.logger().error("Vitrail could not empty {}", loaded.settingsFile(), e);

			return;
		}

		Vitrail.logger().info("{} is back to the settings it declares itself, {} is emptied",
				loaded.packFileName(), loaded.settingsFile().getFileName());
		reloadPack();
	}

	@Override
	public void importSettings(Path file) {
		MenuValues current = this.values;
		if (current == null) {
			return;
		}

		Map<String, String> read = new LinkedHashMap<>();
		try (InputStream in = Files.newInputStream(file)) {
			Properties properties = new Properties();
			properties.load(in);
			properties.forEach((name, value) -> read.put(name.toString(), value.toString()));
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Vitrail could not import {}", file, e);
			announce(Component
					.translatable(ScreenText.FAILED_IMPORT, name(file))
					.withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));

			return;
		}

		current.queueAll(read);
		announce(Component
				.translatable(ScreenText.IMPORTED_SETTINGS, name(file))
				.withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW));
		refresh();
	}

	/**
	 * A pack dropped onto the list is copied into the folder; a settings file dropped onto a page is
	 * imported. Which one it is depends on the view being shown, as it does in Iris.
	 */
	@Override
	public void onFilesDrop(List<Path> files) {
		if (this.optionsOpen) {
			if (files.size() != 1) {
				announce(Component.translatable(ScreenText.TOO_MANY_FILES)
						.withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));

				return;
			}

			importSettings(files.getFirst());

			return;
		}

		addPacks(files);
	}

	private void addPacks(List<Path> files) {
		PackList list = this.packList;
		if (list == null) {
			return;
		}

		List<Path> packs = files.stream().filter(PackLoader::looksLikeAPack).toList();
		Path directory = PackLoader.directory(gameDirectory());

		for (Path pack : packs) {
			String name = name(pack);
			try {
				copyInto(directory, pack);
			} catch (FileAlreadyExistsException e) {
				announce(Component.translatable(ScreenText.COPY_ERROR_EXISTS, name)
						.withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));
				list.refresh(list.chosenName());

				return;
			} catch (IOException | RuntimeException e) {
				Vitrail.logger().warn("Vitrail could not copy the pack dropped from {}", pack, e);
				announce(Component.translatable(ScreenText.COPY_ERROR, name)
						.withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));
				list.refresh(list.chosenName());

				return;
			}
		}

		list.refresh(list.chosenName());

		if (packs.isEmpty()) {
			announce((files.size() == 1
					? Component.translatable(ScreenText.FAILED_ADD_SINGLE, name(files.getFirst()))
					: Component.translatable(ScreenText.FAILED_ADD))
					.withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));

			return;
		}

		if (packs.size() == 1) {
			String name = name(packs.getFirst());
			announce(Component.translatable(ScreenText.ADDED_PACK, name)
					.withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW));
			// Selected straight away: somebody who has just dragged a pack in wants to use it.
			list.select(name);

			return;
		}

		announce(Component.translatable(ScreenText.ADDED_PACKS, packs.size())
				.withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW));
	}

	/** Copies a zip, or a whole directory, into the pack folder without overwriting what is there. */
	private static void copyInto(Path directory, Path pack) throws IOException {
		Files.createDirectories(directory);
		Path target = directory.resolve(name(pack));
		if (Files.exists(target)) {
			throw new FileAlreadyExistsException(target.toString());
		}

		if (!Files.isDirectory(pack)) {
			Files.copy(pack, target, StandardCopyOption.COPY_ATTRIBUTES);

			return;
		}

		try (Stream<Path> tree = Files.walk(pack)) {
			for (Path entry : tree.toList()) {
				Path to = target.resolve(pack.relativize(entry).toString());
				if (Files.isDirectory(entry)) {
					Files.createDirectories(to);
				} else {
					Files.createDirectories(to.getParent());
					Files.copy(entry, to, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}

	private static String name(Path path) {
		Path name = path.getFileName();

		return name == null ? path.toString() : name.toString();
	}

	/**
	 * Writes whatever the two views have changed and reads the pack again from what was written.
	 * <p>
	 * The order is not commutative. The file stays the only source of truth, so nothing is handed to
	 * the reload in memory, and the reload resynchronises the folder watcher on what is now on disk or
	 * it would read the same change a second time within the second.
	 */
	private void applyChanges() {
		PackList list = this.packList;
		if (list == null) {
			return;
		}

		PackFile asked = PackChain.askedFor();
		String chosen = list.chosenName().isEmpty() ? asked.name() : list.chosenName();
		boolean enabled = list.shadersEnabled();
		boolean samePack = chosen.equals(asked.name());

		// A pack being swapped drops what was clicked on the one before it: a value set on one pack has
		// no meaning in the next one's file, and Iris clears its own queue for the same reason.
		if (!samePack) {
			MenuValues current = this.values;
			if (current != null) {
				current.clearPending();
			}
		}

		boolean wrote = samePack && writePendingSettings();
		if (!samePack || enabled != asked.enabled()) {
			wrote |= writePackFile(chosen, enabled);
		}

		if (wrote) {
			reloadPack();
		}

		list.markSelectedApplied();
		refreshViewSwitch();
	}

	/**
	 * Lays what is pending over what is on disk and writes the result, or answers no when there is
	 * nothing pending.
	 * <p>
	 * Read again first: the file is shared with Iris and edited by hand, and laying the pending table
	 * over what is on disk is what keeps two edits from erasing each other.
	 */
	private boolean writePendingSettings() {
		PackSession loaded = this.session;
		MenuValues current = this.values;
		if (loaded == null || current == null || current.pendingCount() == 0) {
			return false;
		}

		try {
			SettingsFile.Stored onDisk = SettingsFile.read(loaded.settingsFile());
			current.rebase(onDisk.values(), loaded.forcedText());
			SettingsFile.write(loaded.settingsFile(), new SettingsFile.Stored(current.toSave()));
			current.clearPending();
			this.error = null;
		} catch (IOException | RuntimeException e) {
			this.error = String.valueOf(e.getMessage());
			Vitrail.logger().error("Vitrail could not write {}", loaded.settingsFile(), e);
			// The screen stays open and the bottom line carries the message: what was pending is still
			// pending, so nothing has been lost.
			return false;
		}

		return true;
	}

	/**
	 * Writes the whole file name rather than the fragment {@code pack.txt} also accepts, so that two
	 * packs sharing a word cannot swap under the player.
	 */
	private boolean writePackFile(String chosen, boolean enabled) {
		Path file = PackChain.packFile(gameDirectory());
		try {
			PackFile.write(file, new PackFile(chosen, enabled));
			this.error = null;
		} catch (IOException | RuntimeException e) {
			this.error = String.valueOf(e.getMessage());
			Vitrail.logger().error("Vitrail could not write {}", file, e);

			return false;
		}

		return true;
	}

	/**
	 * Has the pack read again, and does not take the reading on.
	 * <p>
	 * {@link #syncWithLoadedPack} picks it up on the next frame instead, and that is what keeps a
	 * rebuild out of a button press: {@code ContainerEventHandler.mouseClicked} focuses whatever it
	 * just clicked once the press returns, so a widget thrown away inside the press would take that
	 * focus with it and the next Enter would press a button that is no longer on screen.
	 */
	private void reloadPack() {
		PackChain.reload(gameDirectory());
	}

	private void dropChangesAndClose() {
		this.dropChanges = true;
		onClose();
	}

	/** Not the default: the default pops a screen layer nobody pushed. */
	@Override
	public void onClose() {
		if (this.dropChanges) {
			MenuValues current = this.values;
			if (current != null) {
				current.clearPending();
			}
		} else {
			applyChanges();
		}

		closePackList();
		this.minecraft.gui.setScreen(this.parent);
	}

	private void closePackList() {
		PackList list = this.packList;
		if (list == null) {
			return;
		}

		try {
			list.close();
		} catch (IOException e) {
			Vitrail.logger().warn("Vitrail could not let go of the pack folder watcher", e);
		}

		this.packList = null;
	}

	/**
	 * Opens the folder the packs are read from, which is how a pack gets into the list in the first
	 * place. Both references put this button on this screen.
	 */
	private void openFolder() {
		Path directory = PackLoader.directory(gameDirectory());
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			// Opened anyway: a folder that cannot be created is one the platform will report on better
			// than this line could.
			Vitrail.logger().warn("Vitrail could not create {}", directory, e);
		}

		Util.getPlatform().openPath(directory);
	}

	/**
	 * Takes on a reading the render layer made on its own, which is what the image was built from.
	 * <p>
	 * The same pack keeps what is pending, which is the whole point of holding that apart from the
	 * file: a line added to {@code options.txt} greys a setting without losing the click made under it.
	 * Another pack drops it, a value set on one pack having no meaning in the next one's file.
	 */
	// By identity, and here that is the opposite answer to the one EngineOptions gives on its own
	// record, on purpose. The question is whether the render layer has handed over a new reading, not
	// whether the reading says the same thing: a reload of an unchanged pack builds a session equal to
	// the held one, and by value this method would then return early and leave the screen on the menu
	// it read before.
	@SuppressWarnings("ReferenceEquality")
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
			// On the menu that was just read and not on the one held, since the same file name is not
			// the same pack: a directory pack is edited in place, and a zip is replaced under its own
			// name often enough.
			this.values = current.reread(loaded.menu(), loaded.saved().values(), loaded.forcedText());
			dropMissingPage();
		} else {
			adopt(loaded);
		}

		rebuildWidgets();
	}

	/**
	 * Takes on what the render layer has just read. A page the new pack does not lay out, and the way
	 * back to it, go with the old one.
	 */
	private void adopt(@Nullable PackSession loaded) {
		this.session = loaded;
		this.values = loaded == null
				? null
				: MenuValues.of(loaded.menu(), loaded.saved().values(), loaded.forcedText());
		// Out to the list when there is no pack left to configure, which is the view a pack is picked
		// from: turning every pack off otherwise leaves an empty page under a row of buttons.
		if (loaded == null) {
			this.optionsOpen = false;
		}

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
