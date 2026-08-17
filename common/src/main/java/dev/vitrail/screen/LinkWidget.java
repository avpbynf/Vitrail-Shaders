package dev.vitrail.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenAxis;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * A jump to another page, written {@code [NAME]} by the pack. This is Iris's
 * {@code LinkElementWidget}. Pages are flat and joined by name, so this carries a name and not a page.
 * <p>
 * <b>Two small departures from the reference, both about a pixel or a broken pack.</b> Iris draws its
 * arrow with seven hex digits rather than eight, {@code LinkElementWidget.java:64}, so it comes out at
 * six per cent alpha and is all but invisible; it is drawn opaque here, which is plainly what was
 * meant. And a link naming a page nobody wrote is drawn grey rather than opened: one of the corpus's
 * three hundred and nine links does exactly that, {@link dev.vitrail.pack.menu.PackMenu} already knows
 * which, and Iris's answer is to walk into the page and land back on the pack's first one, which
 * reads as the screen having lost its place.
 */
public final class LinkWidget extends PageWidget {

	private static final Component ARROW = Component.literal(">");

	private static final int WHITE = 0xFFFFFFFF;

	/** Where a line of text sits inside a cell, and how far in from the right the arrow is. */
	private static final int TEXT_INSET = 7;
	private static final int ARROW_INSET = 9;

	private final String page;
	private final boolean resolved;

	private @Nullable ScreenHost host;
	private MutableComponent label = Component.empty();
	private @Nullable Component trimmedLabel;
	private boolean labelTrimmed;

	/** @param resolved whether a page of that name exists */
	public LinkWidget(String page, boolean resolved) {
		this.page = page;
		this.resolved = resolved;
	}

	@Override
	public void init(ScreenHost host) {
		this.host = host;
		this.label = ScreenText.fromPack(host.lang().page(this.page));
		this.trimmedLabel = null;
	}

	private ScreenHost host() {
		if (this.host == null) {
			throw new IllegalStateException("the page was drawn before it was built");
		}

		return this.host;
	}

	@Override
	public void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered) {
		ScreenRectangle at = bounds();
		ScreenDraw.button(graphics, at.position().x(), at.position().y(), at.width(), at.height(),
				hovered || isFocused(), !this.resolved);

		Font font = Minecraft.getInstance().font;
		int room = at.width() - ARROW_INSET;
		if (font.width(this.label) > room) {
			this.labelTrimmed = true;
		}

		if (this.trimmedLabel == null) {
			this.trimmedLabel = ScreenDraw.shorten(font, this.label.copy(), room);
		}

		// Centred, then nudged left by half of however much it still overruns the room the arrow
		// leaves, which is Iris's arithmetic and what keeps a long name off the arrow.
		int width = font.width(this.trimmedLabel);
		int overrun = Math.max(width - (at.width() - 18), 0);
		graphics.text(font, this.trimmedLabel,
				at.getCenterInAxis(ScreenAxis.HORIZONTAL) - width / 2 - overrun / 2,
				at.position().y() + TEXT_INSET, WHITE);
		graphics.text(font, ARROW, at.right() - ARROW_INSET, at.position().y() + TEXT_INSET, WHITE);

		if (hovered && this.labelTrimmed) {
			// Drawn after every row, so that the row below cannot be drawn over it.
			host().onTop(() -> ScreenDraw.textPanel(font, graphics, this.label, mouseX + 2,
					mouseY - 16));
		}
	}

	private boolean open() {
		if (!this.resolved) {
			return true;
		}

		host().openPage(this.page);
		ScreenDraw.clickSound();

		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return event.button() == GLFW.GLFW_MOUSE_BUTTON_1
				? open()
				: super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return event.isConfirmation() && open();
	}

	@Override
	public Optional<Component> commentTitle() {
		return Optional.of(this.label);
	}

	@Override
	public Optional<Component> commentBody() {
		return host().lang().pageComment(this.page).map(ScreenText::fromPack);
	}
}
