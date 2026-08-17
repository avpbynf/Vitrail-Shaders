package dev.vitrail.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * The shared half of a setting on a pack's page: the pack's name for it on the left, its value in a
 * sunken box on the right, and the gestures that walk through the values. This is Iris's
 * {@code BaseOptionElementWidget}, drawing coordinates included, so that a pack laid out against that
 * screen lands the same way here.
 * <p>
 * The label turns amber when the pending value differs from the applied one, which is the only thing
 * standing between a player and not knowing whether the world they are looking at matches the screen
 * they are reading. The colour is Iris's own, {@link ScreenText#MODIFIED}.
 * <p>
 * <b>The one thing here that is not Iris's is the forced layer</b>, and it is a fact about this engine
 * rather than a choice about the screen: {@code vitrail/options.txt} holds settings down from outside,
 * which is what makes a pass provable, and Iris has no such file to reproduce. A held down setting is
 * drawn grey and refuses the gesture, because a click losing to that file in silence would be worse
 * than a cell that says it cannot be moved. The screen's header says how many there are.
 */
public abstract class OptionWidget extends PageWidget {

	/** Between the name and the value box, and written out as Iris writes it. */
	private static final Component DIVIDER = Component.literal(": ");

	private static final int WHITE = 0xFFFFFFFF;

	/** Where a line of text sits inside a cell of the page's row height, and Iris's number. */
	private static final int TEXT_INSET = 7;

	/** How far in from the left edge the name starts. */
	private static final int LABEL_INSET = 6;

	protected static final Component SET_TO_DEFAULT =
			Component.translatable(ScreenText.SET_TO_DEFAULT).withStyle(ChatFormatting.GREEN);

	/** Set by {@link #init}, so nothing here may be read before the page has been built. */
	private @Nullable ScreenHost host;

	/** The name with {@link #DIVIDER} on it, which is what is measured and cut. */
	private MutableComponent label = Component.empty();

	/** The name on its own, for the tooltip that gives back what the cut took. */
	private MutableComponent plainLabel = Component.empty();

	private @Nullable Component trimmedLabel;
	private @Nullable Component valueLabel;

	private boolean labelTrimmed;
	private int maxLabelWidth;
	private int valueSectionWidth;

	/**
	 * Whether this cell was reached with the keyboard, which moves its tooltip off the mouse and onto
	 * the cell's own corner. Iris reads it off the focus once per frame.
	 */
	protected boolean usedKeyboard;

	@Override
	public void init(ScreenHost host) {
		this.host = host;
		this.valueLabel = null;
		this.trimmedLabel = null;
	}

	protected final ScreenHost host() {
		if (this.host == null) {
			throw new IllegalStateException("the page was drawn before it was built");
		}

		return this.host;
	}

	/** The setting's name, as this engine's settings files spell it. */
	protected abstract String name();

	/** The value as the pack names it, prefix and suffix applied. */
	protected abstract Component valueLabel();

	/** Queues the next value along, and answers whether anything moved. */
	protected abstract boolean nextValue();

	protected abstract boolean previousValue();

	/** Queues the value the pack itself ships, which is what shift and a click ask for. */
	protected abstract boolean originalValue();

	protected abstract boolean modified();

	/** The pack's own words about this setting, when its language file carries any. */
	protected abstract Optional<String> comment();

	protected final void setLabel(MutableComponent name) {
		this.plainLabel = name;
		this.label = name.copy().append(DIVIDER);
	}

	/** The name on its own, without the divider the label carries. */
	protected final Component plainLabel() {
		return this.plainLabel;
	}

	/** The value label as {@link #measure} last built it, rather than built again for a frame. */
	protected final Component valueText() {
		return this.valueLabel == null ? Component.empty() : this.valueLabel;
	}

	/** Whether {@code vitrail/options.txt} is holding this setting down. */
	protected final boolean forced() {
		return host().values().forced(name());
	}

	/**
	 * The name and the value, joined the way the game joins its own so that the separator follows the
	 * player's language: French puts a space before the colon and Japanese uses a full width one. The
	 * drawn label cannot be reused, being cut to fit and carrying a colour.
	 */
	@Override
	protected Component narration() {
		return CommonComponents.optionNameValue(this.plainLabel, valueText());
	}

	@Override
	public Optional<Component> commentTitle() {
		return Optional.of(this.plainLabel);
	}

	@Override
	public Optional<Component> commentBody() {
		return comment().map(ScreenText::fromPack);
	}

	/**
	 * Works out how wide the value box has to be and how much of the name is left over for the label,
	 * once per frame before anything is drawn.
	 *
	 * @param minValueSectionWidth the least the box may be, which each kind of setting chooses: a
	 *                             toggle keeps two words the same width, a slider needs room for a
	 *                             track, and the profile selector takes whatever the name leaves
	 */
	protected final void measure(int minValueSectionWidth) {
		this.usedKeyboard = isFocused();

		if (this.valueLabel == null) {
			this.valueLabel = valueLabel();
		}

		Font font = Minecraft.getInstance().font;
		this.valueSectionWidth = Math.max(minValueSectionWidth, font.width(this.valueLabel) + 8);
		this.maxLabelWidth = (bounds().width() - 8) - this.valueSectionWidth;

		// Cut again only when the answer to "does it fit" has changed, which is what keeps a resize
		// from rebuilding every label on every frame.
		if (this.trimmedLabel == null
				|| (font.width(this.label) > this.maxLabelWidth) != this.labelTrimmed) {
			updateLabels();
		}

		this.labelTrimmed = font.width(this.label) > this.maxLabelWidth;
	}

	protected final void updateLabels() {
		Font font = Minecraft.getInstance().font;
		MutableComponent cut = ScreenDraw.shorten(font, this.label.copy(), this.maxLabelWidth);

		this.trimmedLabel = modified() ? cut.withColor(ScreenText.MODIFIED) : cut;
		this.valueLabel = valueLabel();
	}

	protected final void drawWithValue(GuiGraphicsExtractor graphics, boolean hovered) {
		drawWithValue(graphics, hovered, -1.0F, 0);
	}

	/**
	 * Draws the cell, its value box, and a thin marker inside that box showing where a slider's value
	 * sits along its range.
	 *
	 * @param sliderPosition between nought and one, or negative for no marker at all
	 */
	protected final void drawWithValue(GuiGraphicsExtractor graphics, boolean hovered,
			float sliderPosition, int sliderWidth) {
		ScreenRectangle at = bounds();
		int x = at.position().x();
		int y = at.position().y();

		ScreenDraw.button(graphics, x, y, at.width(), at.height(), hovered, forced());
		ScreenDraw.button(graphics, at.right() - (this.valueSectionWidth + 2), y + 2,
				this.valueSectionWidth, at.height() - 4, false, true);

		if (sliderPosition >= 0.0F) {
			int space = (this.valueSectionWidth - 4) - sliderWidth;
			int position = (at.right() - this.valueSectionWidth) + (int) (sliderPosition * space);

			ScreenDraw.button(graphics, position, y + 4, sliderWidth, at.height() - 8, false, false);
		}

		Font font = Minecraft.getInstance().font;
		graphics.text(font, this.trimmedLabel, x + LABEL_INSET, y + TEXT_INSET, WHITE);
		graphics.text(font, this.valueLabel,
				(at.right() - 2) - this.valueSectionWidth / 2 - font.width(this.valueLabel) / 2,
				y + TEXT_INSET, WHITE);
	}

	/**
	 * The tooltip a cell offers: what shift and a click would do while shift is held, and otherwise
	 * the whole of a name that had to be cut. The second is not offered while the comment panel is up,
	 * since the panel already carries that name on its first line.
	 */
	protected final void tryTooltip(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {
		if (Minecraft.getInstance().hasShiftDown()) {
			tooltip(graphics, SET_TO_DEFAULT, x, y, hovered);
		} else if (this.labelTrimmed && !host().showingComment()) {
			tooltip(graphics, this.plainLabel, x, y, hovered);
		}
	}

	/** Drawn after every row, so that the row below this one cannot be drawn over it. */
	protected final void tooltip(GuiGraphicsExtractor graphics, Component text, int x, int y,
			boolean hovered) {
		if (hovered) {
			host().onTop(() -> ScreenDraw.textPanel(Minecraft.getInstance().font, graphics, text,
					x + 2, y - 16));
		}
	}

	/**
	 * Left walks forwards, right walks backwards, and shift with either gives the value the pack
	 * ships. Iris answers both buttons here, which is why a right click is not the context menu it is
	 * everywhere else on this screen.
	 */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_1 && event.button() != GLFW.GLFW_MOUSE_BUTTON_2) {
			return super.mouseClicked(event, doubleClick);
		}

		if (forced()) {
			return true;
		}

		boolean moved = Minecraft.getInstance().hasShiftDown() && originalValue();
		if (!moved) {
			moved = event.button() == GLFW.GLFW_MOUSE_BUTTON_1 ? nextValue() : previousValue();
		}

		if (moved) {
			host().refresh();
		}

		ScreenDraw.clickSound();

		return true;
	}

	/**
	 * The same three gestures on a keyboard, which has no second button to walk backwards with: shift
	 * takes that job and control gives the pack's own value.
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!event.isConfirmation()) {
			return false;
		}

		if (forced()) {
			return true;
		}

		boolean moved;
		if (event.hasControlDown()) {
			moved = originalValue();
		} else if (event.hasShiftDown()) {
			moved = previousValue();
		} else {
			moved = nextValue();
		}

		if (moved) {
			host().refresh();
		}

		ScreenDraw.clickSound();

		return true;
	}
}
