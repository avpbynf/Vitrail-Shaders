package dev.vitrail.dh;

import dev.vitrail.Vitrail;

import org.joml.Vector2f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The projection Distant Horizons draws its far terrain with, its planes, its render distance and
 * its rendering switch.
 * <p>
 * <strong>Why this exists at all.</strong> The far terrain is rasterised in a volume of DH's own:
 * {@code RenderUtil.setDhProjectionMatrix} takes the game's matrix and overwrites its z row with
 * clip planes of its own, a near plane pulled in to seven and a half blocks and a far plane out
 * past the last LOD. Everything this engine serves a pack about the far terrain hangs off that
 * row: the volume the {@code dhProjection} names answer, the projection the pack's own
 * {@code dh_} programs are rasterised under, and the two planes and the distance published beside
 * them. {@link #zRow} is the half of it only DH can answer.
 * <p>
 * <strong>The projection is reached by an ugly road, and the published ones are wrong.</strong>
 * {@code IDhApiRenderProxy.getNearClipPlaneDistanceInBlocks} and the {@code nearClipPlane} field of
 * the event parameter both answer {@code RenderUtil.getNearClipPlaneInBlocks()}, which is the value
 * BEFORE the clamp to seven and a half the matrix itself is built with: at any ordinary render
 * distance the two are more than a factor of ten apart. The matrix DH really rasterised with is
 * held in one place, the private render parameter of its client entry point, so that is what is
 * read. Iris reaches that same matrix off the public event parameter and sets it on its generic
 * object program, {@code compat/dh/IrisGenericRenderProgram.java:240}; its terrain, water and
 * shadow programs are handed a projection Iris builds instead, {@code compat/dh/DHCompat.java:54}.
 * <p>
 * <strong>Why reflection.</strong> That field belongs to a class this cannot be built against:
 * compiling against a locally built jar would tie this repository to a path on one machine, so the
 * reads are made by name instead and simply stay quiet on any DH that cannot answer them.
 * <p>
 * Nothing here throws into a frame. Every failure resolves once, says so once, and answers nothing
 * forever after, because a far terrain that is merely flat is a picture and a far terrain that
 * rises inside the render is not.
 */
public final class DhDepth {

	/** DH's entry point holds its proxies in static fields that stay null until the mod is up. */
	private static final String DELAYED = "com.seibel.distanthorizons.api.DhApi$Delayed";

	/** Where DH keeps the one frame of render parameters it hands its own renderer. */
	private static final String CLIENT_API =
			"com.seibel.distanthorizons.core.api.internal.ClientApi";

	/** Resolved once, on the first frame that asks. Null means "asked and cannot be served". */
	private static Field renderParamsField;
	private static Field dhProjectionField;
	private static Field projectionScaleField;
	private static Field projectionOffsetField;

	private static Field configsField;
	private static Method graphicsMethod;
	private static Method renderDistanceMethod;
	private static Method renderingEnabledMethod;
	private static Method valueMethod;

	/** Blocks across a chunk, which is what turns DH's own setting into what a pack reads. */
	private static final int CHUNK = 16;

	private static boolean resolved;
	private static boolean usable;

	/**
	 * Latched off on its own, and the line is drawn by what the answer is for rather than by which
	 * accessor gave it. How far DH draws is a number handed to a pack, so losing it must not stop
	 * the volume being read for an image that still draws; whether DH is drawing at all is the
	 * question of there being anything to serve, so losing that one latches the whole of it, which
	 * is why {@link #renderingEnabled} sets the flag beside this one.
	 */
	private static boolean distanceUsable = true;

	private DhDepth() {
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
	 * unclamped near the class comment carries.
	 * <p>
	 * <strong>Both planes therefore carry a number Iris does not publish, and that is a divergence
	 * rather than a preference.</strong> Iris answers the near with
	 * {@code renderProxy.getNearClipPlaneDistanceInBlocks} at
	 * {@code compat/dh/DHCompatInternal.java:133}, published through
	 * {@code uniforms/CommonUniforms.java:185}, and the far with a formula of its own,
	 * {@code (chunkRenderDistance * 16 + 512) * sqrt(2)} at
	 * {@code compat/dh/DHCompatInternal.java:124}. Both are right there and neither is right here,
	 * because under Iris the LODs are drawn by Iris's own programs against exactly those two planes,
	 * and DH itself switches to Iris's far formula when it sees Iris,
	 * {@code core/util/RenderUtil.java:289}. Here DH draws the LODs itself, between the planes its
	 * own matrix carries. What both engines publish is the same fact, the pair the far terrain was
	 * rasterised against; the numbers differ only because the terrain was. Serving Iris's numbers
	 * here would cost a pack every distance it works out from them: the near it publishes is the
	 * unclamped one the class comment carries, and since the clamp only ever pulls that plane in, it
	 * is always the further out of the two. Its far is out by the ratio of the two formulas.
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

		if (!usable || !distanceUsable) {
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
			distanceUsable = false;
			Vitrail.logger().warn("Distant Horizons' render distance cannot be read, so a pack is "
					+ "given the game's own distance and clip planes instead of that mod's, for the "
					+ "rest of this session", e);
			return -1;
		}
	}

	/**
	 * Whether the far terrain is there to be had: DH answering, and DH's own rendering switched on.
	 * <p>
	 * <strong>The second half is Iris's condition and not a caution of ours.</strong> Iris poses
	 * {@code DISTANT_HORIZONS} on the mod being loaded AND {@code DHCompat.hasRenderingEnabled()},
	 * {@code gl/shader/StandardMacros.java:64}, which is a live read of
	 * {@code configs.graphics().renderingEnabled()}, {@code compat/dh/DHCompatInternal.java:143}.
	 * Without it a player who switches DH's rendering off keeps a pack that has been told the far
	 * terrain is there, and the pack then takes its distant road over every pixel of sky.
	 * <p>
	 * Read live rather than kept, because that switch is reachable without leaving the world:
	 * {@link dev.vitrail.render.PackDefines} watches this answer the way it watches the world's own
	 * symbols, so a flip reads the pack again. Iris does the same with a reload of its own,
	 * {@code compat/dh/DHCompatInternal.java:148}.
	 */
	public static boolean present() {
		if (!resolved) {
			resolve();
		}

		return usable && renderingEnabled();
	}

	/**
	 * DH's own rendering switch. False while DH is still starting up, which is not a failure and
	 * latches nothing off: the switch is read again on the next frame that asks.
	 */
	private static boolean renderingEnabled() {
		try {
			Object configs = configsField.get(null);
			if (configs == null) {
				return false;
			}

			Object value = renderingEnabledMethod.invoke(graphicsMethod.invoke(configs));

			return valueMethod.invoke(value) instanceof Boolean on && on;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			usable = false;
			Vitrail.logger().warn("Distant Horizons' rendering switch cannot be read, so its far "
					+ "terrain will stay flat for the rest of this session", e);

			return false;
		}
	}

	private static void resolve() {
		resolved = true;

		try {
			Class<?> delayed = Class.forName(DELAYED);

			resolveProjection();

			configsField = delayed.getField("configs");
			graphicsMethod = configsField.getType().getMethod("graphics");
			renderDistanceMethod = graphicsMethod.getReturnType().getMethod("chunkRenderDistance");
			renderingEnabledMethod = graphicsMethod.getReturnType().getMethod("renderingEnabled");
			valueMethod = renderDistanceMethod.getReturnType().getMethod("getValue");

			usable = true;
			Vitrail.logger().info("Distant Horizons found, its far terrain's volume, planes and "
					+ "switch will be read");
		} catch (ClassNotFoundException e) {
			// The ordinary case: DH is simply not installed. Not worth a line above debug.
			usable = false;
		} catch (ReflectiveOperationException | RuntimeException e) {
			usable = false;
			Vitrail.logger().info("Distant Horizons is installed but not in a shape a projection can "
					+ "be read out of, so its far terrain will stay flat: {}", e.toString());
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
