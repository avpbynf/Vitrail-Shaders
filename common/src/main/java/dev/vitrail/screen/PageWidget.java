package dev.vitrail.screen;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * One cell of a pack's settings page: a setting, a link to another page, the profile selector, or the
 * gap a pack writes {@code <empty>} for. This is Iris's {@code AbstractElementWidget} and its
 * {@code CommentedElementWidget} in one class, the second existing there only to add the two comment
 * methods and being extended by everything the first is.
 * <p>
 * <b>Not a widget as far as the game is concerned</b>, which is the same arrangement
 * {@link WidgetRow} uses and for a related reason: a row of cells is one list entry, and the cells
 * are laid out across it by dividing its width. So the cell is a {@link GuiEventListener} that is
 * handed its place once per frame by the row, and the click sound, which a real button would be
 * given, has to be asked for.
 * <p>
 * <b>Narration is the one thing here Iris does not do</b>: its cells answer {@code NONE} and are
 * skipped by the narrator ({@code AbstractElementWidget.narrationPriority}). Nothing in 26.2 forces
 * that, and the screen this replaces read out like every other button in the game, so it is put back
 * rather than lost with the rest of the port. It costs no pixel and no gesture, which is why it is
 * not a divergence to weigh: a pack author sees the same screen either way.
 * <p>
 * Only the focused cell narrates. A screen reader is driven by the keyboard, so focus is the question
 * it asks; answering for the hovered cell as well would need the row to hand its reading down a frame
 * earlier than it does.
 */
public abstract class PageWidget implements GuiEventListener, NarratableEntry {

	private ScreenRectangle bounds = ScreenRectangle.empty();
	private boolean focused;

	/**
	 * Re-reads everything this cell shows. Called once when the page is built and again on every
	 * refresh, which is what lets one click move ten cells: a profile queues twenty values and every
	 * cell showing one of them has to say so.
	 */
	public void init(ScreenHost host) {
	}

	/** Where the row put this cell, set once per frame just before it is drawn. */
	public final void place(ScreenRectangle bounds) {
		this.bounds = bounds;
	}

	protected final ScreenRectangle bounds() {
		return this.bounds;
	}

	public abstract void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered);

	/** What the comment panel puts on its first line, when this cell has a comment at all. */
	public Optional<Component> commentTitle() {
		return Optional.empty();
	}

	public Optional<Component> commentBody() {
		return Optional.empty();
	}

	@Override
	public boolean isFocused() {
		return this.focused;
	}

	@Override
	public void setFocused(boolean focused) {
		this.focused = focused;
	}

	@Override
	public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
		return isFocused() ? null : ComponentPath.leaf(this);
	}

	@Override
	public ScreenRectangle getRectangle() {
		return this.bounds;
	}

	/** What a screen reader should say about this cell, or nothing for one that is not reachable. */
	protected Component narration() {
		return Component.empty();
	}

	@Override
	public NarrationPriority narrationPriority() {
		return isFocused() ? NarrationPriority.FOCUSED : NarrationPriority.NONE;
	}

	@Override
	public void updateNarration(NarrationElementOutput output) {
		Component said = narration();
		if (!said.getString().isEmpty()) {
			// The game's own wording for a button, so that a cell is announced the way every other
			// control the player has already met is announced.
			output.add(NarratedElementType.TITLE,
					Component.translatable("gui.narrate.button", said));
		}
	}
}
