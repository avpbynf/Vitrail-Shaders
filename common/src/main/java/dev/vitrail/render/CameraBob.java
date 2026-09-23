package dev.vitrail.render;

import dev.vitrail.Vitrail;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

/**
 * The walk bob, the damage tilt, the nausea rotation and the portal skew, taken apart from the
 * projection they are multiplied into.
 * <p>
 * <strong>The game puts all four in the projection matrix; every shader pack expects them in the
 * model view.</strong> That is not a preference, it is what OptiFine did and therefore what the
 * packs are written against: {@code bobView} pushed a translation and a rotation onto the model view
 * stack, and {@code gbufferProjection} never saw them. Iris says the same thing in the javadoc of
 * its own {@code MixinModelViewBobbing}, and moves them for the same reason.
 * <p>
 * The symptom of getting this wrong is not subtle once it is named, and it took a player walking to
 * find it: anything a pack places on screen from a direction slides with the bob. On Complementary
 * it is the glow of the sun and the moon, on BSL the light of the held torch, and the two look like
 * different bugs.
 * <p>
 * <strong>What is done here changes nothing the game draws.</strong> Iris moves the four out of the
 * game's own projection, and then has to put the bob back by hand for the held item, which is drawn
 * with the same model view and is meant to stay still. This engine leaves the game's matrices alone
 * and splits them only where a pack reads them: the projection a pack is given is the camera's own,
 * before all four, and the model view a pack is given is {@code bob * view}. The product of the two
 * is the matrix the world was really drawn with, so the geometry lands in exactly the same pixels,
 * and a position rebuilt from {@code depthtex0} comes back to the same place it started.
 * <p>
 * Rebuilding the four from game state is not open here, {@code spinningEffectTime} and
 * {@code spinningEffectSpeed} being private with no accessor. This is
 * the third way: the multiplications themselves are intercepted, so nothing is reconstructed and
 * nothing is guessed.
 */
public final class CameraBob {

	private static final Matrix4f IDENTITY = new Matrix4f();

	private static final Matrix4f TAKEN = new Matrix4f();
	private static final Matrix4f CHECK = new Matrix4f();

	/**
	 * The first of the four on its own, which is the walk bob and the damage tilt and nothing else.
	 * <p>
	 * Kept apart from {@link #TAKEN} because the hand wants exactly this much and no more. The game
	 * gives its own hand the same pose and leaves the nausea and the portal out of it, those two
	 * being a distortion of the world rather than of the arm, so a hand built on the accumulation
	 * would skew and spin with a portal the game's own does not.
	 * <p>
	 * Unlike the accumulation it is NOT dropped at the frame boundary, and it may not be: the boundary
	 * falls at the first geometry of the frame and the hand is drawn long after it, so a value cleared
	 * there would always be read as the identity. What replaces it is the next frame's capture, which
	 * happens once per level render and before anything is drawn.
	 */
	private static final Matrix4f POSE = new Matrix4f();

	private static boolean taken;
	private static boolean trusted = true;

	/**
	 * One flag per warning, and not one for both. They say different things and the quiet one
	 * happens first: nothing took the bob is a frame where the game did not apply one, which is
	 * ordinary, while a product that does not match is the engine missing a term the game applies,
	 * which is the check this class exists for. Sharing a flag let the ordinary one silence the
	 * serious one for the rest of the session.
	 */
	private static boolean warnedNotTaken;
	private static boolean warnedMismatch;

	/**
	 * One flag for the third way this check can end, which is the one that is not a fault at all: a
	 * frame that drew with nothing captured to compare against.
	 */
	private static boolean warnedUncaptured;

	private static boolean announced;

	/**
	 * Which of the three answers {@link #agrees} gave on the last frame it was asked. It exists for
	 * one reader, the F3 line, and for one case: what this class says about the split is said once
	 * and never again, so a session that starts well and goes wrong later has said nothing at all.
	 * The two pictures differ only while the player walks, which is the worst moment to be reading a
	 * file, and a player who cannot open one is left with a fault they can see and no word for it.
	 */
	private static Split split = Split.HELD;

	/**
	 * Where the two matrices parted on the frame the split was refused, in the chat's words, and
	 * empty while they have not. It is said rather than only counted because the size of the
	 * difference is what names the fault and nothing else does.
	 */
	private static String mismatch = "";

	/**
	 * The three answers of {@link #agrees}, in the order they are worth reading: the first is what
	 * every session stands in and each of the other two names a different fault.
	 */
	public enum Split {
		/** The split held, so a pack reads the bob in the model view, where OptiFine put it. */
		HELD,
		/** Nothing took a bob this frame, so a pack reads one in the projection. */
		NOT_TAKEN,
		/** The two matrices did not multiply back to the truth, so the split is off. */
		UNTRUSTED
	}

	/** What the last frame's split came to, which is what the F3 line reads. */
	public static Split lastSplit() {
		return split;
	}

	/** Where the two matrices parted, or empty while they have not. */
	public static String lastMismatch() {
		return mismatch;
	}

	private CameraBob() {
	}

	/**
	 * The first of the four, which is also the one that always happens: the pose holding the walk
	 * bob and the damage tilt. It starts the frame's accumulation rather than adding to it.
	 */
	public static void take(Matrix4fc bob) {
		TAKEN.set(bob);
		POSE.set(bob);
		taken = true;
	}

	/**
	 * The walk bob and the damage tilt alone, for the hand, or the identity when nothing has ever
	 * taken one.
	 * <p>
	 * Answered whatever {@link #trusted} says, unlike {@link #taken()}. That flag is about whether
	 * the projection and the model view a pack reads still multiply back to what the level was drawn
	 * with, which is a question about the split; the hand is not split, it is one matrix built here
	 * and handed to the device, and dropping the bob out of it would make the arm the one thing on
	 * screen that does not move with the walk.
	 */
	static Matrix4fc pose() {
		return POSE;
	}

	/** The nausea and portal rotation, appended in the order the game applies it. */
	public static void rotate(float angle, Vector3fc axis) {
		TAKEN.rotate(angle, axis);
	}

	/** The nausea and portal skew, likewise. */
	public static void scale(float x, float y, float z) {
		TAKEN.scale(x, y, z);
	}

	/**
	 * What to pre-multiply a pack's model view by, or the identity when this frame took nothing.
	 * Never null, so that a caller cannot forget the case.
	 */
	static Matrix4fc taken() {
		return taken && trusted ? TAKEN : IDENTITY;
	}

	/**
	 * Whether the split may be used at all, checked against the matrix the level was really drawn
	 * with rather than assumed.
	 * <p>
	 * This is the whole safety of the thing. If the game ever multiplies something into the
	 * projection that this does not intercept, the pack would be handed a projection missing a term
	 * and a model view that does not make up for it, which is a picture that looks entirely
	 * plausible and reprojects wrong. So the two are multiplied back together and compared with what
	 * was captured on its way to the device: they have to be the same matrix, and when they are not,
	 * the split is abandoned for the session and the engine goes back to publishing the drawn
	 * projection whole.
	 *
	 * @param camera   the camera's own projection, before any of the four
	 * @param rendered the projection the level was drawn with, after all four
	 */
	static boolean agrees(Matrix4fc camera, Matrix4fc rendered) {
		// Asked before anything else, because the answer below is per frame and the refusal is not.
		// One product that did not match takes taken() down to the identity for good, so a later
		// frame that happened to agree would publish the clean projection against a model view with
		// no bob in it: the four terms would then be in neither of the two matrices the pack is
		// handed, which is worse than the frame that failed.
		if (!trusted) {
			split = Split.UNTRUSTED;

			return false;
		}

		if (!taken) {
			// Said once, because the quiet answer and the failed one look alike from here: nothing
			// took the bob either when the game stopped multiplying it in or when this engine
			// stopped being able to see it, and both leave a pack reading a projection that swings.
			if (!warnedNotTaken) {
				warnedNotTaken = true;
				Vitrail.logger().warn("Nothing took the walk bob out of the projection this frame, so "
						+ "a pack reads it where OptiFine never put it and anything it places on "
						+ "screen from a direction will slide as the player walks");
			}

			split = Split.NOT_TAKEN;

			return false;
		}

		// The check has nothing to check against, and that is not a fault. What is handed in then is
		// the camera's OWN projection, which is the clean one, so the two sides of the comparison
		// would be `camera times TAKEN` and `camera`: they differ by the bob and by nothing else, so
		// the check fails on every frame a player is walking and takes the split down for the
		// session on a frame that was in fact drawn exactly as this engine and every pack expect.
		//
		// The capture exists to catch a term the four interceptions do not see. With nothing
		// captured there is nothing to catch it with, and refusing the split does not make the check
		// work: it only publishes the bob in the projection, which is the one place a pack cannot
		// read it. A live capture is a witness, and a missing one is not a disagreement.
		if (!CapturedProjection.present()) {
			if (!warnedUncaptured) {
				warnedUncaptured = true;
				Vitrail.logger().warn("The level's projection was not captured on a frame that drew, so "
						+ "the walk bob cannot be checked against it. The split is kept, and the bob "
						+ "goes to the model view where a pack reads one");
			}

			split = Split.HELD;

			return true;
		}

		CHECK.set(camera).mul(TAKEN);
		// Loose enough for one matrix product of single precision, tight enough that a whole missing
		// term cannot pass: the smallest of the four, the damage tilt at rest, still moves a
		// coefficient by more than a thousandth.
		if (!CHECK.equals(rendered, 1.0E-4F)) {
			trusted = false;
			split = Split.UNTRUSTED;
			mismatch = detail(CHECK, rendered);
			if (!warnedMismatch) {
				warnedMismatch = true;
				Vitrail.logger().warn("The camera's projection times the bob is not the projection the "
						+ "level was drawn with, so this engine is missing a term the game applies. "
						+ "The bob stays in the projection, where packs do not expect it, rather than "
						+ "publishing two matrices that do not multiply back to the truth");
			}

			return false;
		}

		if (!announced) {
			announced = true;
			Vitrail.logger().info("The walk bob, the damage tilt, the nausea and the portal are "
					+ "published in gbufferModelView and taken out of gbufferProjection, where a pack "
					+ "expects them. The two multiply back to the matrix the level was drawn with");
		}

		split = Split.HELD;

		return true;
	}

	/**
	 * The largest term of the difference between the two matrices, where it sits, and whether a
	 * capture was there at all to be compared against.
	 * <p>
	 * <strong>The size is what says which fault this is, and nothing else does.</strong> A term off
	 * by a fraction is this engine failing to see part of one of the four effects. A term off by the
	 * whole of a projection is the camera state handed to the comparison not being the one the level
	 * was drawn with, which no arithmetic in this class can mend. The two want different repairs,
	 * they look identical in a line of a log, and a printer that says which is cheaper than either.
	 */
	private static String detail(Matrix4fc left, Matrix4fc right) {
		float worst = 0.0F;
		int row = 1;
		int column = 1;

		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				float difference = Math.abs(left.get(c, r) - right.get(c, r));
				if (difference > worst) {
					worst = difference;
					row = r + 1;
					column = c + 1;
				}
			}
		}

		String capture = CapturedProjection.present()
				? "a capture was there, so the two really differ"
				: "no capture was there, so the comparison was against the camera's own projection";

		return "row " + row + " column " + column + " by " + Math.round(worst * 1000.0F)
				+ "/1000, and " + capture;
	}

	/** Forgets the frame's capture, so a frame that took nothing is not handed the last one's. */
	public static void clear() {
		taken = false;
	}
}
