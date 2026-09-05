package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The single channel font sheets, seen through a view that reads the one channel four times.
 * <p>
 * <strong>What this exists to prevent is a glyph drawn as an opaque red box.</strong> A glyph baked
 * from a TrueType provider answers false to {@code isColored}, where the bitmap and unihex
 * providers a vanilla font is made of answer true, so its sheet is allocated
 * {@code GpuFormat.R8_UNORM} rather than {@code RGBA8_UNORM} ({@code FontTexture.java:31}) and a
 * sampler over it hands back the coverage in red, nought in green and blue, and one in alpha. The
 * game answers that in its own fragment stage, which the three grayscale pipelines compile with the
 * {@code IS_GRAYSCALE} define and which is one line,
 * {@code vec4 texColor = texture(Sampler0, texCoord0).rrrr}
 * ({@code assets/minecraft/shaders/core/text.fsh}). A pack's {@code gbuffers_entities_translucent}
 * has no such line and no way to know it needs one: it multiplies {@code gtexture} by the vertex
 * colour, reads alpha one everywhere, and passes the alpha test over the whole quad.
 * <p>
 * <strong>This is Iris's answer, moved onto the backend this engine draws with.</strong> Iris
 * swizzles the ALBEDO TEXTURE for as long as an intensity program is bound, setting
 * {@code GL_TEXTURE_SWIZZLE_RGBA} to red four times
 * ({@code pipeline/programs/ExtendedShader.java:198-201}, on the rows
 * {@code ShaderKey.isIntensity} names at {@code pipeline/programs/ShaderKey.java:128}) and putting
 * the identity back at the next program use ({@code gl/IrisRenderSystem.java:527-533}). Vulkan has
 * no such state on a texture: the mapping belongs to the IMAGE VIEW and is fixed when the view is
 * created. So the swizzle is a view of this engine's over the game's own image, built once per
 * sheet and handed to the pack's program in place of the game's view;
 * {@code VulkanGpuTextureViewMixin} is what writes the component mapping while {@link #swizzling()}
 * is up, the blaze3d API exposing no way to ask for one.
 * <p>
 * <strong>The game's own view is untouched</strong>, which is what a view of our own buys and why
 * the mixin is keyed on a flag rather than on the format: the game's text fragment reads
 * {@code .rrrr} off the raw sheet, and a sheet swizzled underneath it would still work but every
 * other reader of that image would silently change. What the second view costs is one
 * {@code VkImageView}, and a font stitches its glyphs into 256 by 256 pages with a sheet each.
 * <p>
 * <strong>The sheets are followed rather than owned.</strong> A view holds its image alive
 * ({@code VulkanGpuTexture.removeViews} destroying only at nought), so ours are closed as soon as
 * the game closes the sheet under them, which is what a font reload does. The map is walked at
 * every lookup rather than emptied on a hook: it holds one entry per grayscale page, which is
 * nought for a resource pack that ships no TrueType provider.
 */
public final class GlyphIntensity {

	/**
	 * Our view per game sheet, by identity: two {@code GpuTexture} instances are the same sheet only
	 * when they are the same object, and the class defines no equality of its own.
	 */
	private static final Map<GpuTexture, GpuTextureView> VIEWS = new IdentityHashMap<>();

	/**
	 * Raised around the one call that creates a swizzled view, and read by the mixin that writes the
	 * component mapping.
	 * <p>
	 * Per thread, which {@code VulkanBindGroupLayoutMixin} is the precedent for and which is what a
	 * flag on a shared builder owes: {@code GpuDevice.createTextureView} asserts no thread, so a
	 * view another thread happened to be building inside this window would come out swizzled too.
	 */
	private static final ThreadLocal<Boolean> SWIZZLING = ThreadLocal.withInitial(() -> false);

	private GlyphIntensity() {
	}

	/**
	 * The view a pack's program samples for one grayscale draw, which is ours where one can be made
	 * and the game's where it cannot.
	 * <p>
	 * Handing the game's back is a real answer and not a silent failure: the glyph is then drawn
	 * red, which is the defect, but the frame is drawn. The two ways in are a device this engine has
	 * not got yet and a sheet that is not single channel, the second being what would happen if a
	 * grayscale pipeline were ever handed a coloured sheet: swizzling that one would drain the green
	 * and the blue out of a coloured glyph, which is a worse picture than not swizzling at all.
	 */
	static GpuTextureView view(GpuTextureView sheet) {
		release();

		GpuTexture texture = sheet.texture();
		if (texture.isClosed() || texture.getFormat() != GpuFormat.R8_UNORM) {
			return sheet;
		}

		GpuTextureView held = VIEWS.get(texture);
		if (held != null) {
			return held;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return sheet;
		}

		SWIZZLING.set(true);
		GpuTextureView made;
		try {
			made = device.createTextureView(texture, sheet.baseMipLevel(), sheet.mipLevels());
		} catch (RuntimeException e) {
			Vitrail.logger().error("Could not build a swizzled view of the font sheet "
					+ texture.getLabel() + ", so the pack reads it with one channel and draws its "
					+ "text red", e);

			return sheet;
		} finally {
			SWIZZLING.set(false);
		}

		VIEWS.put(texture, made);

		return made;
	}

	/** Whether the view being created right now is one of ours and wants the mapping. */
	public static boolean swizzling() {
		return SWIZZLING.get();
	}

	/**
	 * Lets go of the views whose sheet the game has closed, which is the only thing keeping those
	 * images alive by then.
	 */
	private static void release() {
		Iterator<Map.Entry<GpuTexture, GpuTextureView>> held = VIEWS.entrySet().iterator();
		while (held.hasNext()) {
			Map.Entry<GpuTexture, GpuTextureView> entry = held.next();
			if (!entry.getKey().isClosed()) {
				continue;
			}

			entry.getValue().close();
			held.remove();
		}
	}
}
