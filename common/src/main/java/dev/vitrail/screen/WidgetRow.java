package dev.vitrail.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A row of small controls laid out left to right inside one entry of a list, which is how both lists
 * carry their top row: the pack list's shaders toggle beside its refresh, and the option page's reset
 * beside its import and export. This is Iris's {@code IrisElementRow.java}.
 * <p>
 * An element is not a widget as far as the game is concerned, which is the whole reason this class
 * exists: a list entry cannot hand back children that overlap its own row, so hit testing, hover and
 * focus are worked out here from the width each element was added with. What that costs is the click
 * sound, which a real button would be given and these have to ask for.
 */
public final class WidgetRow {

	/** Between two elements. One pixel, which is what makes the row read as a single control. */
	private static final int DEFAULT_SPACING = 1;

	/** Insertion ordered: the row is drawn and walked in the order elements were added. */
	private final Map<Element, Integer> widths = new LinkedHashMap<>();

	private final int spacing;

	private int x;
	private int y;
	private int width;
	private int height;

	public WidgetRow(int spacing) {
		this.spacing = spacing;
	}

	public WidgetRow() {
		this(DEFAULT_SPACING);
	}

	/** How wide the whole row came out, which is what a right aligned row is placed from. */
	public int width() {
		return this.width;
	}

	public WidgetRow add(Element element, int width) {
		Integer had = this.widths.put(element, width);
		// Adding an element twice sets its width rather than counting it twice, which is what lets
		// setWidth go through here.
		this.width += width + this.spacing - (had == null ? 0 : had + this.spacing);

		return this;
	}

	public void setWidth(Element element, int width) {
		if (this.widths.containsKey(element)) {
			add(element, width);
		}
	}

	/** Draws the row from its top left corner. */
	public void draw(GuiGraphicsExtractor graphics, int x, int y, int height, int mouseX, int mouseY,
			float a, boolean rowHovered) {
		this.x = x;
		this.y = y;
		this.height = height;

		int at = x;
		for (Map.Entry<Element, Integer> held : this.widths.entrySet()) {
			int width = held.getValue();
			held.getKey().draw(graphics, at, y, width, height, mouseX, mouseY, a,
					rowHovered && over(at, width, mouseX, mouseY));
			at += width + this.spacing;
		}
	}

	/** Draws the row from its top right corner, which is where the option page's tools sit. */
	public void drawRightAligned(GuiGraphicsExtractor graphics, int x, int y, int height, int mouseX,
			int mouseY, float a, boolean rowHovered) {
		draw(graphics, x - this.width, y, height, mouseX, mouseY, a, rowHovered);
	}

	private boolean over(int sectionX, int sectionWidth, double mouseX, double mouseY) {
		return mouseX > sectionX && mouseX < sectionX + sectionWidth
				&& mouseY > this.y && mouseY < this.y + this.height;
	}

	private Optional<Element> hovered(double mouseX, double mouseY) {
		int at = this.x;
		for (Map.Entry<Element, Integer> held : this.widths.entrySet()) {
			int width = held.getValue();
			if (over(at, width, mouseX, mouseY)) {
				return Optional.of(held.getKey());
			}

			at += width + this.spacing;
		}

		return Optional.empty();
	}

	private Optional<Element> focused() {
		return this.widths.keySet().stream().filter(Element::isFocused).findFirst();
	}

	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return hovered(event.x(), event.y())
				.map(element -> element.mouseClicked(event, doubleClick))
				.orElse(false);
	}

	public boolean mouseReleased(MouseButtonEvent event) {
		return hovered(event.x(), event.y()).map(element -> element.mouseReleased(event))
				.orElse(false);
	}

	public boolean keyPressed(KeyEvent event) {
		return focused().map(element -> element.keyPressed(event)).orElse(false);
	}

	public List<? extends GuiEventListener> children() {
		return List.copyOf(new ArrayList<>(this.widths.keySet()));
	}

	/** One control of the row, drawn on the same button face every other control here uses. */
	public abstract static class Element implements GuiEventListener {

		public boolean disabled;

		private boolean hovered;
		private boolean focused;
		private ScreenRectangle bounds = ScreenRectangle.empty();

		public void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
				int mouseX, int mouseY, float a, boolean hovered) {
			this.bounds = new ScreenRectangle(x, y, width, height);
			// Taken on BEFORE the face is drawn, where Iris assigns it after,
			// {@code IrisElementRow.java:162-164}. Its face therefore lights from the previous frame's
			// reading while its label lights from this one, so entering and leaving an element shows one
			// frame of the two disagreeing. Nothing about a pack depends on it and the fix is the order
			// of two statements.
			this.hovered = hovered;

			ScreenDraw.button(graphics, x, y, width, height, this.hovered || isFocused(),
					this.disabled);
			drawLabel(graphics, x, y, width, height, mouseX, mouseY, a, this.hovered);
		}

		public abstract void drawLabel(GuiGraphicsExtractor graphics, int x, int y, int width,
				int height, int mouseX, int mouseY, float a, boolean hovered);

		public boolean isHovered() {
			return this.hovered;
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
	}

	/** An element that answers a left click and the confirmation keys, and nothing else. */
	public abstract static class Pressable<T extends Pressable<T>> extends Element {

		private final Function<T, Boolean> onClick;

		protected Pressable(Function<T, Boolean> onClick) {
			this.onClick = onClick;
		}

		@SuppressWarnings("unchecked")
		private boolean press() {
			return this.onClick.apply((T) this);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			if (this.disabled) {
				return false;
			}

			return event.button() == GLFW.GLFW_MOUSE_BUTTON_1
					? press()
					: super.mouseClicked(event, doubleClick);
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			return event.isConfirmation() && press();
		}
	}

	/** A control whose label is one sprite of the atlas, lit with a second one when hovered. */
	public static final class IconElement extends Pressable<IconElement> {

		private final ScreenDraw.Icon icon;
		private final ScreenDraw.Icon lit;

		public IconElement(ScreenDraw.Icon icon, ScreenDraw.Icon lit,
				Function<IconElement, Boolean> onClick) {
			super(onClick);
			this.icon = icon;
			this.lit = lit;
		}

		public IconElement(ScreenDraw.Icon icon, Function<IconElement, Boolean> onClick) {
			this(icon, icon, onClick);
		}

		@Override
		public void drawLabel(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
				int mouseX, int mouseY, float a, boolean hovered) {
			ScreenDraw.Icon drawn = !this.disabled && (hovered || isFocused()) ? this.lit : this.icon;
			int iconX = x + (width - drawn.width()) / 2;
			int iconY = y + (height - drawn.height()) / 2;

			drawn.draw(graphics, iconX, iconY);
		}
	}

	/** A control whose label is a word, which is what the shaders toggle is. */
	public static final class TextElement extends Pressable<TextElement> {

		/** How tall a line of the game's font is, for centring a label in a row of any height. */
		private static final int LINE = 8;

		private final Font font = Minecraft.getInstance().font;

		private Component text;

		public TextElement(Component text, Function<TextElement, Boolean> onClick) {
			super(onClick);
			this.text = text;
		}

		public void setText(Component text) {
			this.text = text;
		}

		public Component text() {
			return this.text;
		}

		@Override
		public void drawLabel(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
				int mouseX, int mouseY, float a, boolean hovered) {
			int textX = x + (width - this.font.width(this.text)) / 2;
			int textY = y + (height - LINE) / 2;

			graphics.text(this.font, this.text, textX, textY, 0xFFFFFFFF);
		}
	}
}
