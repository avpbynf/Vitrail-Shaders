package dev.vitrail.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A button whose whole face is one sprite of an atlas, with the plain, the lit and the grey states
 * stacked underneath each other at a fixed spacing. This is Iris's {@code OldImageButton}, which
 * exists there for the same reason it exists here: the game's own {@link net.minecraft.client.gui.components.ImageButton}
 * takes a {@code WidgetSprites}, three named sprites from the atlas the game stitches, and our
 * widgets file is a plain texture with coordinates rather than named sprites.
 * <p>
 * Used for the one button that has no room for a word: the eye that hides the screen so the world
 * behind it can be looked at, which F1 also reaches.
 */
public final class TextureButton extends Button {

	private static final int ATLAS = 256;

	private final Identifier texture;
	private final int u;
	private final int v;

	/** How far down the atlas the next state sits: lit at one step, grey at two. */
	private final int step;

	private TextureButton(int x, int y, int width, int height, int u, int v, int step,
			Identifier texture, OnPress onPress, Component message) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
		this.texture = texture;
		this.u = u;
		this.v = v;
		this.step = step;
	}

	public static TextureButton of(int x, int y, int width, int height, int u, int v, int step,
			Identifier texture, Component message, Runnable action) {
		return new TextureButton(x, y, width, height, u, v, step, texture, _ -> action.run(),
				message);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// Grey wins over lit, which is the order the game's own image button reads them in.
		int row = this.v;
		if (!isActive()) {
			row = this.v + this.step * 2;
		} else if (isHoveredOrFocused()) {
			row = this.v + this.step;
		}

		graphics.blit(RenderPipelines.GUI_TEXTURED, this.texture, getX(), getY(), this.u, row,
				this.width, this.height, ATLAS, ATLAS);
	}
}
