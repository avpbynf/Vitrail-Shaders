package dev.vitrail.render;

import dev.vitrail.mixin.TextureManagerAccessor;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Names the image a draw really binds, by asking the game which of its own textures that view
 * belongs to.
 * <p>
 * <strong>This exists because reading the wiring is not the same as reading the picture.</strong>
 * The road from a render type to a sampler is four handovers long - the type names an identifier,
 * the game resolves it to a view at prepare time, {@code EntityDraw} takes the view named
 * {@code Sampler0} out of the prepared type, and {@link GeometryProgram} binds it wherever the pack
 * asked for the atlas - and every one of them reads right while the screen says otherwise. A line
 * that names the image closes that gap with an observation instead of a fifth reading.
 * <p>
 * Answered once per program at its first draw and never per draw: the map is the game's own and
 * holds every texture it has loaded, so a lookup walks it whole.
 */
final class GameImages {

	private GameImages() {
	}

	/**
	 * What to call the image behind a view, which is its identifier wherever the game holds one.
	 *
	 * @param view the image bound, or null where the pass has none of its own
	 */
	static String name(GpuTextureView view) {
		if (view == null) {
			return "one white pixel, this pass having no image of its own";
		}

		String size = view.getWidth(0) + "x" + view.getHeight(0);
		Minecraft minecraft = Minecraft.getInstance();
		TextureManager manager = minecraft == null ? null : minecraft.getTextureManager();
		if (manager == null) {
			return "an unnamed " + size + " image";
		}

		Map<Identifier, AbstractTexture> loaded = ((TextureManagerAccessor) manager).vitrail$byPath();
		for (Map.Entry<Identifier, AbstractTexture> entry : loaded.entrySet()) {
			if (view.equals(viewOf(entry.getValue()))) {
				return entry.getKey() + ", " + size;
			}
		}

		// Every image of the pack's own is one of these, the colour targets first of all, so this is
		// the ordinary answer for a sampler the chain fills rather than a fault.
		return "an image the game does not hold, " + size;
	}

	/**
	 * The view a loaded texture carries, or null before anything has uploaded one.
	 * <p>
	 * Caught rather than tested, and there is no test to make: a texture registered for the next
	 * reload holds no view until that reload applies one, and the getter answers that with a throw.
	 * A line that names an image is not worth a crash.
	 */
	private static GpuTextureView viewOf(AbstractTexture texture) {
		try {
			return texture.getTextureView();
		} catch (IllegalStateException absent) {
			return null;
		}
	}
}
