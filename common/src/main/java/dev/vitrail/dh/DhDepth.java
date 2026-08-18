package dev.vitrail.dh;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.textures.GpuTextureView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Distant Horizons' own depth image, when there is one to be had.
 * <p>
 * <strong>Why this exists at all.</strong> DH draws its far terrain into its own colour and depth
 * images and then composites the colour onto the game's texture with depth writing and depth
 * testing both switched off. So its colour reaches the pack the way any family that does not draw
 * through the pack reaches it, through the seed, and its depth reaches nothing. Every effect a pack
 * indexes on depth therefore reads sky where the far terrain stands: fog at the far plane, a focal
 * distance at infinity, water that does not know what is behind it. Folding this image into the
 * game's depth before {@code PackDepth} takes its copies is what makes those effects right, and it
 * is the whole of what this package is for.
 * <p>
 * <strong>Why reflection.</strong> The accessors that hand back a rendering-API neutral object,
 * {@code getDhDepthTextureBlazeWrapper} and its colour twin, were added to DH's API on
 * 11 July 2026, four days after the version number moved past 3.2.0-b. No released build of DH
 * carries them, so there is no coordinate to compile against; the older accessors that are released
 * hand back an OpenGL name, which is exactly the thing that cannot exist on this backend. Compiling
 * against a locally built jar would tie this repository to a path on one machine, so the call is
 * made by name instead and simply stays quiet on any DH that cannot answer it. When a build
 * carrying those methods ships, this class can become an ordinary typed dependency and the shape of
 * it will not change.
 * <p>
 * Nothing here throws into a frame. Every failure resolves once, says so once, and answers
 * {@code null} forever after, because a far terrain that is merely flat is a picture and a far
 * terrain that raises inside the render is not.
 */
public final class DhDepth {

	/** DH's entry point holds its proxies in static fields that stay null until the mod is up. */
	private static final String DELAYED = "com.seibel.distanthorizons.api.DhApi$Delayed";

	/** Resolved once, on the first frame that asks. Null means "asked and cannot be served". */
	private static Method depthWrapperMethod;
	private static Method textureViewMethod;
	private static Field payloadField;
	private static Field renderProxyField;

	private static boolean resolved;
	private static boolean usable;

	private DhDepth() {
	}

	/**
	 * DH's depth image as it stands this frame, or null when DH is absent, not yet up, still on its
	 * OpenGL renderer, or a build too old to answer.
	 * <p>
	 * The image is reversed Z over zero to one, cleared to zero, which is the same window the game
	 * rasterises in, so a merge is a comparison and not a conversion. The conversion a pack reads
	 * belongs to {@code PackDepth} and stays there.
	 */
	public static GpuTextureView view() {
		if (!resolved) {
			resolve();
		}

		if (!usable) {
			return null;
		}

		try {
			Object proxy = renderProxyField.get(null);
			if (proxy == null) {
				// DH is present but has not finished starting, which is ordinary on the first frames.
				return null;
			}

			Object result = depthWrapperMethod.invoke(proxy);
			Object wrapper = payloadField.get(result);
			if (wrapper == null) {
				// DH answers a failed result until its own texture has been created and bound.
				return null;
			}

			Object textureView = textureViewMethod.invoke(wrapper);
			return (textureView instanceof GpuTextureView view) ? view : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			usable = false;
			Vitrail.logger().warn("Distant Horizons' depth image cannot be read, its far terrain "
					+ "will stay flat for the rest of this session", e);
			return null;
		}
	}

	/** Whether anything at all can be expected from DH, without touching the frame's state. */
	public static boolean present() {
		if (!resolved) {
			resolve();
		}

		return usable;
	}

	private static void resolve() {
		resolved = true;

		try {
			Class<?> delayed = Class.forName(DELAYED);
			renderProxyField = delayed.getField("renderProxy");

			Class<?> proxyType = renderProxyField.getType();
			depthWrapperMethod = proxyType.getMethod("getDhDepthTextureBlazeWrapper");

			payloadField = depthWrapperMethod.getReturnType().getField("payload");

			// The wrapper hands its objects back as Object on purpose, so that a Vulkan texture
			// travels through an API whose older half speaks OpenGL names.
			Class<?> wrapperType = Class.forName(
					"com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper");
			textureViewMethod = wrapperType.getMethod("getTextureView");

			usable = true;
			Vitrail.logger().info("Distant Horizons found, its far terrain will be given a depth");
		} catch (ClassNotFoundException e) {
			// The ordinary case: DH is simply not installed. Not worth a line above debug.
			usable = false;
		} catch (ReflectiveOperationException | RuntimeException e) {
			usable = false;
			Vitrail.logger().info("Distant Horizons is installed but too old to hand back a "
					+ "backend neutral depth image, its far terrain will stay flat");
		}
	}
}
