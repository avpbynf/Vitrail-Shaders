package dev.vitrail.dh;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector2f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Distant Horizons' own depth image, and the projection it was drawn with.
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
 * <strong>The image is not in the game's volume, and that is why a projection is handed back
 * beside it.</strong> The two windows agree, both being a reversed Z over zero to one cleared to
 * zero, and the agreement stops there: {@code RenderUtil.setDhProjectionMatrix} takes the game's
 * matrix and overwrites its z row with clip planes of DH's own, a near plane pulled in to seven and
 * a half blocks and a far plane out past the last LOD. A hill a thousand blocks off then carries a
 * value the game's own volume reads as eight blocks off, which is the player's face. Folded raw the
 * far terrain would not be flat, it would be in the way. So the fold is a conversion, and
 * {@link #zRow} is the half of it only DH can answer.
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
 * <strong>The projection is reached by an uglier road, and the published ones are wrong.</strong>
 * {@code IDhApiRenderProxy.getNearClipPlaneDistanceInBlocks} and the {@code nearClipPlane} field of
 * the event parameter both answer {@code RenderUtil.getNearClipPlaneInBlocks()}, which is the value
 * BEFORE the clamp to seven and a half the matrix itself is built with: at any ordinary render
 * distance the two are more than a factor of ten apart. The matrix DH really rasterised with is
 * held in one place, the private render parameter of its client entry point, so that is what is
 * read. Iris never needs it, its own programs drawing the LODs with a projection Iris supplies.
 * <p>
 * Nothing here throws into a frame. Every failure resolves once, says so once, and answers
 * {@code null} forever after, because a far terrain that is merely flat is a picture and a far
 * terrain that rises inside the render is not. The image and the projection stand or fall together:
 * half a conversion is worse than none.
 */
public final class DhDepth {

	/** DH's entry point holds its proxies in static fields that stay null until the mod is up. */
	private static final String DELAYED = "com.seibel.distanthorizons.api.DhApi$Delayed";

	/** Where DH keeps the one frame of render parameters it hands its own renderer. */
	private static final String CLIENT_API =
			"com.seibel.distanthorizons.core.api.internal.ClientApi";

	/** Resolved once, on the first frame that asks. Null means "asked and cannot be served". */
	private static Method depthWrapperMethod;
	private static Method textureViewMethod;
	private static Field payloadField;
	private static Field renderProxyField;

	private static Field renderParamsField;
	private static Field dhProjectionField;
	private static Field projectionScaleField;
	private static Field projectionOffsetField;

	private static Field configsField;
	private static Method graphicsMethod;
	private static Method renderDistanceMethod;
	private static Method valueMethod;

	/** Blocks across a chunk, which is what turns DH's own setting into what a pack reads. */
	private static final int CHUNK = 16;

	private static boolean resolved;
	private static boolean usable;

	private DhDepth() {
	}

	/**
	 * DH's depth image as it stands this frame, or null when DH is absent, not yet up, still on its
	 * OpenGL renderer, or a build too old to answer.
	 * <p>
	 * The image is reversed Z over zero to one, cleared to zero. That is the window the game
	 * rasterises in as well, but not the volume: {@link #zRow} carries what has to be undone before
	 * a value from here means anything beside a value of the game's. The conversion a pack reads is
	 * a third one again, and belongs to {@code PackDepth}.
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

	/**
	 * The z row of the matrix DH drew this frame with, as its scale and its offset: the two terms
	 * that turn an eye distance into the value its image holds. False while there is none to be had.
	 * <p>
	 * Two terms and not the two clip planes, because two terms are what the arithmetic wants and the
	 * planes would have to be turned into them anyway. Read every frame rather than kept: DH moves
	 * both of its planes with its own render distance, with the player's height above the world and
	 * with the vanilla render distance its overdraw is worked out from.
	 *
	 * @param dest filled with the pair, and left alone when this answers false
	 */
	public static boolean zRow(Vector2f dest) {
		if (!resolved) {
			resolve();
		}

		if (!usable) {
			return false;
		}

		try {
			Object params = renderParamsField.get(null);
			Object matrix = params == null ? null : dhProjectionField.get(params);
			if (matrix == null) {
				return false;
			}

			float offset = projectionOffsetField.getFloat(matrix);
			if (offset == 0.0F) {
				// Still the identity it was made as: DH has not rendered a frame of this world yet.
				return false;
			}

			dest.set(projectionScaleField.getFloat(matrix), offset);

			return true;
			// The error is caught with the rest and on purpose: the first read of that field is what
			// loads DH's client entry point, and a class of DH's that will not load is a far terrain
			// this engine goes without rather than a frame this engine drops.
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			usable = false;
			Vitrail.logger().warn("Distant Horizons' projection cannot be read, so its depth cannot be "
					+ "converted and its far terrain will stay flat for the rest of this session", e);
			return false;
		}
	}

	/**
	 * DH's own near and far clip planes this frame, in blocks, out of the row it drew with. False
	 * while there is none to be had.
	 * <p>
	 * Taken from the matrix and not from {@code getNearClipPlaneDistanceInBlocks}, which answers the
	 * unclamped near the class comment carries. Iris publishes the unclamped one and is right to:
	 * under Iris the LODs are drawn by Iris's own programs with a projection Iris builds from it, so
	 * there it IS the plane the far terrain was rasterised against. Here DH draws them itself, so
	 * the plane a pack has to be told is DH's own.
	 *
	 * @param dest filled with the near plane and then the far one, and left alone on false
	 */
	public static boolean planes(Vector2f dest) {
		if (!zRow(dest)) {
			return false;
		}

		float scale = dest.x;
		float offset = dest.y;
		if (scale <= 0.0F) {
			return false;
		}

		dest.set(offset / (1.0F + scale), offset / scale);

		return true;
	}

	/**
	 * How far DH draws, in blocks, or -1 when it cannot be asked. Blocks and not chunks, because
	 * blocks is what a pack does arithmetic with and what Iris publishes under the same name.
	 */
	public static int renderDistanceBlocks() {
		if (!resolved) {
			resolve();
		}

		if (!usable) {
			return -1;
		}

		try {
			Object configs = configsField.get(null);
			if (configs == null) {
				return -1;
			}

			Object value = renderDistanceMethod.invoke(graphicsMethod.invoke(configs));

			return (valueMethod.invoke(value) instanceof Integer chunks) ? chunks * CHUNK : -1;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			usable = false;
			Vitrail.logger().warn("Distant Horizons' render distance cannot be read, so its far "
					+ "terrain will stay flat for the rest of this session", e);
			return -1;
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

			resolveProjection();

			configsField = delayed.getField("configs");
			graphicsMethod = configsField.getType().getMethod("graphics");
			renderDistanceMethod = graphicsMethod.getReturnType().getMethod("chunkRenderDistance");
			valueMethod = renderDistanceMethod.getReturnType().getMethod("getValue");

			usable = true;
			Vitrail.logger().info("Distant Horizons found, its far terrain will be given a depth");
		} catch (ClassNotFoundException e) {
			// The ordinary case: DH is simply not installed. Not worth a line above debug.
			usable = false;
		} catch (ReflectiveOperationException | RuntimeException e) {
			usable = false;
			Vitrail.logger().info("Distant Horizons is installed but not in a shape a depth can be "
					+ "read out of, so its far terrain will stay flat: {}", e.toString());
		}
	}

	/**
	 * Opens the road to the matrix. The field it starts at is private, which is the whole reason
	 * this is a road rather than a call, and the two it ends at are public members of the published
	 * event parameter DH's own render parameter extends.
	 */
	private static void resolveProjection() throws ReflectiveOperationException {
		// Without initialising it, which matters: the symbols are wanted before the first pack is
		// read and that class builds its render parameter out of DH's own injector as it loads. The
		// first read of the field below is what runs it, and that read is a frame being drawn, by
		// which time DH is up.
		Class<?> clientApi = Class.forName(CLIENT_API, false, DhDepth.class.getClassLoader());
		renderParamsField = clientApi.getDeclaredField("RENDER_PARAMS");
		renderParamsField.setAccessible(true);

		dhProjectionField = renderParamsField.getType().getField("dhProjectionMatrix");

		// Named for what they do and not for what they are called, because the two conventions
		// disagree: this is m22 and m23 of DH's own matrix class, which numbers the row first where
		// JOML numbers the column first and calls the same pair m22 and m32.
		Class<?> matrixType = dhProjectionField.getType();
		projectionScaleField = matrixType.getField("m22");
		projectionOffsetField = matrixType.getField("m23");
	}
}
