package dev.vitrail.screen;

import dev.vitrail.Vitrail;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * The drawing this screen does that the game does not do for it: the button behind a row element,
 * the panel behind a comment, and the small icons of the option page's top row.
 * <p>
 * All of it comes from Iris, {@code GuiUtil.java}, down to the atlas: the sprites are cut at the
 * coordinates its own file uses, so a pack author who knows that screen recognises this one. The
 * atlas itself is Iris's {@code widgets.png}, carried over under its licence and named in NOTICE.
 * <p>
 * <b>One line of Iris's is deliberately absent, and it is a Vulkan matter.</b> Iris enables blending
 * by hand before each blit, {@code GuiUtil.java:69} and {@code GuiUtil.java:197}, through
 * {@code com.mojang.blaze3d.opengl.GlStateManager}. That class is the OpenGL backend's own state
 * machine and there is no context for it to talk to here, so the call is not merely useless but
 * unsound. It costs the image nothing: {@code RenderPipelines.GUI_TEXTURED} already declares the
 * blend state it wants, which is why those calls are dead weight in Iris too, left over from the
 * versions before pipelines carried it. Its own comments still say "(1.16)".
 */
public final class ScreenDraw {

	public static final Identifier WIDGETS =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "textures/gui/widgets.png");

	/** The atlas is square and this is its side, which every blit needs to map its coordinates. */
	private static final int ATLAS = 256;

	/** How tall the button sprites are cut, and the height the nine slice is written against. */
	private static final int SPRITE_HEIGHT = 20;

	/** How wide they are cut. A button wider than this repeats nothing and would stretch. */
	private static final int SPRITE_WIDTH = 200;

	private static final Component ELLIPSIS = Component.literal("...");

	private ScreenDraw() {
	}

	/**
	 * Draws a button, cut from the atlas at the same coordinates the game's own widgets file uses.
	 * <p>
	 * Four blits rather than a nine slice: the sprite is taken from its left half and its right half,
	 * and from its top half and its bottom half, so that a button of any width up to the sprite's own
	 * keeps both of its ends and loses only the middle. Odd widths and heights are handled by giving
	 * the remainder to the far half rather than by rounding, which is what keeps a button's right
	 * edge exactly on its right edge.
	 *
	 * @param hovered  drawn lit, which the row also uses for the focused element
	 * @param disabled drawn grey, and it wins over hovered
	 */
	public static void button(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
			boolean hovered, boolean disabled) {
		int halfWidth = width / 2;
		int halfHeight = height / 2;
		int farWidth = width - halfWidth;
		int farHeight = height - halfHeight;

		// Which of the three button sprites to take, and the order is the game's: disabled sits above
		// the plain one, which sits above the lit one.
		int v = disabled ? 46 : hovered ? 86 : 66;

		// The right hand columns are taken from the sprite's own right edge, so that a narrow button
		// keeps the rounded end rather than a slice of the middle.
		int rightU = SPRITE_WIDTH - farWidth;
		// Likewise the bottom rows, measured from the sprite's bottom rather than from its top.
		int bottomV = v + (SPRITE_HEIGHT - farHeight);

		graphics.blit(RenderPipelines.GUI_TEXTURED, WIDGETS,
				x, y, 0, v, halfWidth, halfHeight, ATLAS, ATLAS);
		graphics.blit(RenderPipelines.GUI_TEXTURED, WIDGETS,
				x + halfWidth, y, rightU, v, farWidth, halfHeight, ATLAS, ATLAS);
		graphics.blit(RenderPipelines.GUI_TEXTURED, WIDGETS,
				x, y + halfHeight, 0, bottomV, halfWidth, farHeight, ATLAS, ATLAS);
		graphics.blit(RenderPipelines.GUI_TEXTURED, WIDGETS,
				x + halfWidth, y + halfHeight, rightU, bottomV, farWidth, farHeight, ATLAS, ATLAS);
	}

	/** A near opaque black panel with a light border, which is what a comment is read against. */
	public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		int border = 0xDEDEDEDE;
		int inner = 0xDE000000;

		graphics.fill(RenderPipelines.GUI, x, y, x + width, y + 1, border);
		graphics.fill(RenderPipelines.GUI, x, y + height - 1, x + width, y + height, border);
		graphics.fill(RenderPipelines.GUI, x, y + 1, x + 1, y + height - 1, border);
		graphics.fill(RenderPipelines.GUI, x + width - 1, y + 1, x + width, y + height - 1, border);
		graphics.fill(RenderPipelines.GUI, x + 1, y + 1, x + width - 1, y + height - 1, inner);
	}

	/** One line of text on a panel of its own width, which is how a pack's page name is drawn. */
	public static void textPanel(Font font, GuiGraphicsExtractor graphics, Component text, int x,
			int y) {
		panel(graphics, x, y, font.width(text) + 8, 16);
		graphics.text(font, text, x + 4, y + 4, 0xFFFFFFFF);
	}

	/**
	 * Cuts a text down to a width, ending it in an ellipsis when it had to be cut. Formatting on the
	 * component as a whole survives; formatting on a run inside it does not.
	 */
	public static MutableComponent shorten(Font font, MutableComponent text, int width) {
		if (font.width(text) <= width) {
			return text;
		}

		String kept = font.plainSubstrByWidth(text.getString(), width - font.width(ELLIPSIS));

		return Component.literal(kept).append(ELLIPSIS).setStyle(text.getStyle());
	}

	/**
	 * The translation if the client has one, and the given text if it does not. A pack's own name for
	 * a setting is the fallback, so this is what lets a translated name win without every pack
	 * needing one.
	 * <p>
	 * Asked of the loaded language rather than of {@code I18n.exists}, which is what Iris asks,
	 * {@code GuiUtil.java:147}: that method is gone in 26.2, {@code I18n} having been cut back to
	 * {@code get} alone. The language it read is the one answered here, so this is the same question
	 * put to the same object.
	 */
	public static MutableComponent translatedOr(MutableComponent fallback, String key,
			Object... format) {
		return Language.getInstance().has(key) ? Component.translatable(key, format) : fallback;
	}

	/**
	 * The click a button makes, for the elements of a row, which are not buttons as far as the game is
	 * concerned and so are not given it.
	 */
	public static void clickSound() {
		Minecraft.getInstance().getSoundManager()
				.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
	}

	/**
	 * One sprite of the atlas, drawn at a point. The coordinates are Iris's, so its icons land where
	 * its own row put them.
	 */
	public record Icon(int u, int v, int width, int height) {

		public static final Icon SEARCH = new Icon(0, 0, 7, 8);
		public static final Icon CLOSE = new Icon(7, 0, 5, 6);
		public static final Icon REFRESH = new Icon(12, 0, 10, 10);
		public static final Icon EXPORT = new Icon(22, 0, 7, 8);
		public static final Icon EXPORT_LIT = new Icon(29, 0, 7, 8);
		public static final Icon IMPORT = new Icon(22, 8, 7, 8);
		public static final Icon IMPORT_LIT = new Icon(29, 8, 7, 8);

		public void draw(GuiGraphicsExtractor graphics, int x, int y) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, WIDGETS, x, y, this.u, this.v,
					this.width, this.height, ATLAS, ATLAS);
		}
	}
}
