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
 * <strong>Only the OPAQUE world is kept.</strong> Everything that moves is drawn into the restored
 * map on every frame, so a mob, a boat, a falling block and the player's own shadow are exact
 * whatever the interval. What ages is the ground: a block placed or broken, or a section that
 * finishes loading, keeps casting the shadow it had until the map is drawn again.
 * <p>
 * <strong>And it is judged by walking, not by capturing.</strong> A lag counted in frames looks
 * like whatever the frame rate makes of it, and a pinned camera cannot see it at all: captures
 * taken on one put the difference below the noise two relaunches make on their own. Keeping the
 * whole finished map, which is what this did before the movers were drawn back into it, froze them
 * too, and three frames of that reads as a bug on the player's own shadow at two hundred a second.
 * <p>
 * <strong>A pack that voxelises into its shadow pass must not be amortised at all</strong>, its
 * shadow programs writing a volume the rest of the frame reads rather than only a depth. That
 * refusal is made by the caller, which is the one place that knows.
 *
 * <h2>How it is set</h2>
 *
 * A slider on the engine page, from nought to {@link #MAX_FRAMES}, stored in
 * {@code vitrail/amortise-shadow} beside the pack. Nought has to be exactly the engine as it was:
 * the anchor then moves every frame, which is what the published pair already did before any of
 * this existed.
 * <p>
 * A file of its own rather than a line in the game's options, like the module cache ceiling: it is
 * read at the head of a frame, written by the screen, and belongs to the install rather than to a
 * pack or a world. It also means a session can flip it without a keyboard, which is how both sides
 * of it were measured in one jar.
 */
public final class ShadowAmortisation {

	/**
	 * What an empty arming file asks for: the map kept for ONE frame after the one that drew it, so
	 * it is never more than two frames old.
	 * <p>
	 * One is where a walk left it, and that walk was made against the map kept WHOLE, where three
	 * frames lagged the player's own shadow plainly enough to read as a bug and one was not seen.
	 * The movers are drawn back in now, so what that walk judged is gone and nobody has yet walked
	 * what remains, which is the ground. The value stays where the harsher behaviour put it rather
	 * than being widened on the strength of a lag nobody has looked at.
	 * <p>
	 * The captures of that walk, taken on a pinned camera where nothing moves, put the difference
	 * BELOW the noise two relaunches make on their own and would have signed off on three. An eye
	 * finds this edge and an instrument does not.
	 */
	public static final int DEFAULT_FRAMES = 1;

	/** Nought is the engine as it was: the map drawn every frame, and every caster exact. */
	public static final int MIN_FRAMES = 0;

	/**
	 * The most frames a map may be kept for, whatever the file says. Three is where a walk of the
	 * map kept whole found the artefact, so the selector stops one short of it. Inherited rather
	 * than measured against the ground alone: see {@link #DEFAULT_FRAMES}.
	 */
	public static final int MAX_FRAMES = 2;

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

	/**
	 * Where the value lives, beside the pack rather than in the game's options: it is read at the
	 * head of a frame by the engine and written by the settings screen, and it belongs to the
	 * install rather than to any pack or world.
	 */
	private static final String SETTING_FILE = "amortise-shadow";

	/** Read from the file the first time it is asked for, and authoritative from then on. */
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

	private static boolean saidRefused;

	private static int countedFrames;
	private static int countedDraws;

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
		// Counted here rather than by the stage: what the interval counts is frames since the map
		// was last FILLED, and this is the one place that sees every frame whatever the stage did.
		if (!drewLastFrame) {
			sinceDraw++;
		}

		pendingCamera.set(camera);
		pendingAngle = shadowAngle;

		int asked = frames();
		// Said once per pack, because a setting that does nothing and says nothing is worse than a
		// setting that is not there: this pack refuses the reuse on its own account, and the player
		// moving the slider would otherwise watch the frame rate not move.
		if (asked > 0 && !amortisable && !saidRefused) {
			saidRefused = true;
			Vitrail.logger().info("This pack voxelises into its shadow pass, so the map is drawn "
					+ "every frame whatever the reuse setting says");
		}

		// A rate and not a one-shot line: what has to be proved is how OFTEN the world is walked for
		// the light, and a message saying it happened once says nothing about the frame after it.
		if (asked > 0 && ++countedFrames >= 600) {
			Vitrail.logger().info("Shadow map: the opaque world was drawn into it {} times in the "
					+ "last {} frames", countedDraws, countedFrames);
			countedFrames = 0;
			countedDraws = 0;
		}

		drawThisFrame = !seeded || asked <= 0 || !amortisable
				|| sinceDraw >= asked
				|| anchorCamera.distance(camera) >= MOVE_BLOCKS
				|| Math.abs(shadowAngle - anchorAngle) >= ANGLE_TURNS;

		return drewLastFrame;
	}

	/**
	 * Whether the OPAQUE world is drawn into the map this frame, settled at the head of it.
	 * <p>
	 * Everything else in the stage runs whatever this answers: the walk, so Sodium keeps answering
	 * visibility from the light's tree, the casters that move, and the translucent world, which
	 * costs five per cent of what the opaque one does.
	 */
	public static boolean drawTerrainThisFrame() {
		return drawThisFrame;
	}

	/**
	 * Taken by the stage once the map has really been drawn. What it anchors on is what this frame
	 * was set up with, not what the world looks like now: see {@link #pendingCamera}.
	 */
	public static void drawn() {
		countedDraws++;
		anchorCamera.set(pendingCamera);
		anchorAngle = pendingAngle;
		sinceDraw = 0;
		seeded = true;
		drewSinceBegin = true;
	}

	/**
	 * Forgets the MAP, at a pack load and wherever else the chain is torn down: one pack's shadow
	 * map is not another's, and the next frame draws before anything samples it.
	 * <p>
	 * The interval is not forgotten with it. It is a setting of the install, not of the pack, and
	 * re-reading it here is what an earlier version did: {@code beginFrame} runs once per FRAME
	 * despite its name, so the value was read off the disk sixty times a second and the map was
	 * dropped just as often, which is the amortisation doing nothing while announcing itself.
	 */
	public static void forget() {
		seeded = false;
		sinceDraw = 0;
		drawThisFrame = true;
		drewLastFrame = true;
		drewSinceBegin = false;
		saidRefused = false;
	}

	/**
	 * How many frames a map may be kept for after the one that drew it. Nought is the engine as it
	 * was. Read from the file the first time, and from memory after that.
	 */
	public static int frames() {
		if (frames < 0) {
			frames = clamp(read());
			// Said once, and only when it is on: a shadow one frame late is the first thing to
			// suspect for a shadow artefact, and a session reading a log has no other way to know
			// the map it is looking at is not this frame's.
			if (frames > 0) {
				Vitrail.logger().info("Shadow map kept for {} frame(s) after the one that draws it, "
						+ "so the ground in it is that many frames old, everything that moves being "
						+ "drawn afresh", frames);
			}
		}

		return frames;
	}

	/**
	 * Takes the value the settings screen chose, writes it beside the pack and keeps the live
	 * answer. It applies at the next frame: no pack reload and no restart.
	 */
	public static void setFrames(int asked) {
		frames = clamp(asked);
		try {
			Path file = file();
			Files.createDirectories(file.getParent());
			Files.writeString(file, frames + "\n");
		} catch (IOException | RuntimeException e) {
			// Kept live anyway: a value that cannot be stored is still the one the player asked for
			// this session, and a selector that springs back to its old place says nothing at all.
			Vitrail.logger().warn("Could not store the shadow amortisation, it holds for this "
					+ "session only", e);
		}
	}

	private static int read() {
		try {
			Path file = file();
			if (!Files.isRegularFile(file)) {
				return DEFAULT_FRAMES;
			}

			String asked = Files.readString(file).trim();
			// A file that is there and holds a typo reads as the default rather than as nought: it
			// was written on purpose, and answering it with the engine switched off is a gain that
			// disappears without a word.
			return asked.isEmpty() ? DEFAULT_FRAMES : Integer.parseInt(asked);
		} catch (IOException | RuntimeException ignored) {
			return DEFAULT_FRAMES;
		}
	}

	private static Path file() {
		return Vitrail.platform().gameDirectory().resolve("vitrail").resolve(SETTING_FILE);
	}

	private static int clamp(int asked) {
		return Math.min(Math.max(asked, MIN_FRAMES), MAX_FRAMES);
	}
}
