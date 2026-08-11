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
 * Rebuilding the four from game state was considered by an earlier reading and rightly rejected,
 * {@code spinningEffectTime} and {@code spinningEffectSpeed} being private with no accessor. This is
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

	private static boolean announced;

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

			return false;
		}

		CHECK.set(camera).mul(TAKEN);
		// Loose enough for one matrix product of single precision, tight enough that a whole missing
		// term cannot pass: the smallest of the four, the damage tilt at rest, still moves a
		// coefficient by more than a thousandth.
		if (!CHECK.equals(rendered, 1.0E-4F)) {
			trusted = false;
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

		return true;
	}

	/** Forgets the frame's capture, so a frame that took nothing is not handed the last one's. */
	public static void clear() {
		taken = false;
	}
}
