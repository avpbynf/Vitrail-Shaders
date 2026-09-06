package dev.vitrail.render;

import dev.vitrail.Vitrail;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether the shadow map has to be drawn again this frame, or whether the one on hand still says
 * the truth.
 * <p>
 * The second walk of the world is the most expensive thing this engine does. Measured on
 * 6 September 2026, at eye level in open terrain with Complementary Unbound at its factory profile,
 * the {@code shadow chunk} pass alone costs 4.12 ms of a 7.05 ms frame, and cutting the pack's
 * shadows entirely takes that frame to 1.96 ms. Nothing else in the frame is worth a third of it.
 * <p>
 * <strong>And most of that work is redrawn identically.</strong> The map holds the terrain lit from
 * the sun, and between two frames of a player standing still nothing in it moves: not the terrain,
 * not the sun by any amount a texel would notice. What changes is the camera, and the map is
 * anchored on the camera through the grid snap of {@link ViewMatrices}, so it stops being the right
 * map once the camera has walked far enough for its box to have moved.
 * <p>
 * <strong>What makes the reuse sound is that the pack is told which map it has.</strong> The engine
 * already publishes a pair of shadow matrices distinct from the fresh ones, because the map is
 * drawn at the end of a frame and sampled through the next: {@code mapShadowModelView} is the drawn
 * matrix moved onto the current camera. Reusing a map for several frames is the same mechanism with
 * a longer arm, the anchor moving only on the frames the map is really drawn. A pass sampling the
 * map therefore transforms with the matrix that map was built with, whatever its age, and the
 * lookup lands where it did.
 *
 * <h2>What it does not cover, and what that looks like</h2>
 *
 * <strong>Casters that move freeze with the map.</strong> A mob, a boat, a falling block or the
 * player's own shadow keep the position they had on the frame the map was drawn, and jump to the
 * new one when it is drawn again. That is the whole visible cost of this, it grows with the
 * interval, and it is why the interval is small and why this is off unless it is asked for.
 * <p>
 * <strong>And it is judged by walking, not by capturing.</strong> The lag is counted in frames, so
 * what it looks like depends on the frame rate: three frames at two hundred a second is fifteen
 * milliseconds and reads as a bug on the player's own shadow, while the same three frames leave a
 * pinned camera's capture below the noise of two relaunches. The setting that survived a walk is
 * {@link #DEFAULT_FRAMES}, and anything above it is for a measurement rather than for playing.
 * <p>
 * <strong>A pack that voxelises into its shadow pass must not be amortised at all</strong>, its
 * shadow programs writing a volume the rest of the frame reads rather than only a depth. That
 * refusal is made by the caller, which is the one place that knows.
 *
 * <h2>How it is armed</h2>
 *
 * A file {@code vitrail/amortise-shadow} in the instance, holding the number of frames a map may be
 * kept for, empty meaning {@link #DEFAULT_FRAMES}. Absent means off, and off has to be exactly the
 * engine as it was: the anchor then moves every frame, which is what the published pair already
 * did before any of this existed.
 * <p>
 * A file rather than a system property, for the same reason the pass census is one: a launcher is a
 * place a session cannot reach, and a file beside the pack is a place it can, so the same jar
 * measures both states in one launch.
 */
public final class ShadowAmortisation {

	/**
	 * What an empty arming file asks for: the map kept for ONE frame after the one that drew it, so
	 * it is never more than two frames old.
	 * <p>
	 * Three was the first value and it is visibly wrong. Walked in game on 6 September 2026 at three
	 * frames, the player's own shadow lags the player plainly enough to read as a bug, and a mob's
	 * does the same. At one it is not seen. That is the whole width of the setting: the artefact
	 * grows with it, the gain grows with it, and the eye finds the edge between them long before any
	 * capture does. The captures of that afternoon, taken on a pinned camera where nothing moves,
	 * put the difference BELOW the noise two relaunches make on their own and would have signed off
	 * on three.
	 */
	private static final int DEFAULT_FRAMES = 1;

	/**
	 * The most frames a map may be kept for, whatever the file says. Past this the frozen casters
	 * stop reading as a cheap shadow and start reading as a bug, and an arming file left behind by
	 * a session that has gone is a road nobody chose: a ceiling is what keeps that road short.
	 */
	private static final int MAX_FRAMES = 16;

	/**
	 * How far the camera may walk from where the map was drawn before it is drawn again, in blocks.
	 * <p>
	 * The map covers a box around the camera, so walking moves ground into the box that was never
	 * drawn into it, and that ground comes out unshadowed. Four blocks is a quarter of a section and
	 * well inside the smallest shadow distance of the corpus; it is not derived from the box, and a
	 * pack asking for a very short shadow distance would want it smaller.
	 */
	private static final double MOVE_BLOCKS = 4.0;

	/**
	 * How far the sun may turn, in turns, before the map is drawn again. A tenth of a degree, which
	 * at the ordinary day length is about six ticks: the trigger that fires is the frame count, not
	 * this one, and this one is here for a world whose time is being set rather than run.
	 */
	private static final float ANGLE_TURNS = 0.0003F;

	private static final String ARM_FILE = "amortise-shadow";

	/** Read once per pack load rather than once per frame, like every other arming file. */
	private static int frames = -1;

	private static final Vector3d anchorCamera = new Vector3d();

	private static float anchorAngle;

	/**
	 * What this frame would anchor on if it draws. Taken at the head of the frame rather than at the
	 * draw, because the map is drawn with the matrices built at the head of the frame: an anchor
	 * read at the end of the frame would be the camera after everything in between had moved it,
	 * and the published pair would then be measured from a place the map was not drawn around.
	 */
	private static final Vector3d pendingCamera = new Vector3d();

	private static float pendingAngle;

	private static boolean seeded;

	private static int sinceDraw;

	private static boolean drawThisFrame = true;

	private static boolean drewLastFrame = true;

	private static boolean drewSinceBegin;

	private ShadowAmortisation() {
	}

	/**
	 * Decides, once, whether the map will be drawn at the end of this frame, and answers whether it
	 * was drawn at the end of the last one.
	 * <p>
	 * Called from the shadow half of the frame setup, before the fresh matrices are built, because
	 * the answer decides which pair the sampling passes of this frame are handed.
	 *
	 * @param camera      where the camera stands this frame, unshifted
	 * @param shadowAngle the casting body's angle in turns, as {@link ViewMatrices} takes it
	 * @param amortisable whether the pack allows it at all: false forces a draw every frame
	 * @return whether the map on hand was drawn at the end of the previous frame
	 */
	public static boolean beginFrame(Vector3dc camera, float shadowAngle, boolean amortisable) {
		// What the previous frame ACTUALLY did, not what it planned: the stage refuses to open on
		// its own account (no chain, no device, an OpenGL boot, a pack whose shadow programs were
		// turned down), and on those frames the map is older than the plan says. The anchor has to
		// follow the map and never the intention, or the pack would be handed matrices for a map
		// that was never drawn.
		drewLastFrame = drewSinceBegin;
		drewSinceBegin = false;

		pendingCamera.set(camera);
		pendingAngle = shadowAngle;

		int asked = armed();
		drawThisFrame = !seeded || asked <= 0 || !amortisable
				|| sinceDraw >= asked
				|| anchorCamera.distance(camera) >= MOVE_BLOCKS
				|| Math.abs(shadowAngle - anchorAngle) >= ANGLE_TURNS;

		return drewLastFrame;
	}

	/** Whether the stage should walk the world and draw, as settled at the head of this frame. */
	public static boolean drawThisFrame() {
		return drawThisFrame;
	}

	/**
	 * Taken by the stage once the map has really been drawn. What it anchors on is what this frame
	 * was set up with, not what the world looks like now: see {@link #pendingCamera}.
	 */
	public static void drawn() {
		anchorCamera.set(pendingCamera);
		anchorAngle = pendingAngle;
		sinceDraw = 0;
		seeded = true;
		drewSinceBegin = true;
	}

	/** Counted at the close of a frame that kept the map, which is what the interval counts. */
	public static void kept() {
		sinceDraw++;
	}

	/**
	 * Forgets the map, at a pack load and wherever else the chain is torn down. The next frame draws
	 * whatever the file says: a map from another pack is not a map.
	 */
	public static void forget() {
		seeded = false;
		sinceDraw = 0;
		drawThisFrame = true;
		drewLastFrame = true;
		drewSinceBegin = false;
		frames = -1;
	}

	/** The interval asked for, in frames, or nought when the file is not there. */
	private static int armed() {
		if (frames >= 0) {
			return frames;
		}

		frames = 0;
		try {
			Path file = Vitrail.platform().gameDirectory().resolve("vitrail").resolve(ARM_FILE);
			if (!Files.isRegularFile(file)) {
				return frames;
			}

			String asked = Files.readString(file).trim();
			// A file that is there and holds a typo still arms, at the default: it was put there on
			// purpose, and answering it with silence is how a measurement gets waited for and never
			// comes. The same reading the pass census makes of its own file.
			frames = asked.isEmpty() ? DEFAULT_FRAMES : parse(asked);
			frames = Math.min(Math.max(frames, 0), MAX_FRAMES);
			Vitrail.logger().info("Shadow map amortised: kept for {} frame(s) after the one that "
					+ "draws it, so casters that move are that many frames late in it", frames);
		} catch (IOException | RuntimeException ignored) {
			frames = 0;
		}

		return frames;
	}

	private static int parse(String asked) {
		try {
			return Integer.parseInt(asked);
		} catch (NumberFormatException ignored) {
			return DEFAULT_FRAMES;
		}
	}
}
