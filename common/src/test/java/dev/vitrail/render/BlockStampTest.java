package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

/**
 * Holds when a second write of a program's block is the write of the bytes already standing: the
 * same ring turn, the same version of the frame's values and the same four values handed in by the
 * pass, compared by their bits.
 * <p>
 * The cases are on the side a wrong answer would turn into a stale block. Every input has its own
 * case for moving, and the values are compared as held copies, so that a matrix somebody reuses for
 * the next frame, or asks the game for afresh at every draw, is judged by what it says and not by
 * which object says it.
 */
class BlockStampTest {

	private final BlockStamp stamp = new BlockStamp();

	private static Matrix4f matrix(float seed) {
		return new Matrix4f(seed, 0, 0, 0, 0, seed + 1, 0, 0, 0, 0, seed + 2, 0, 0, 0, 0, 1);
	}

	private void stampNothing(long turn, long version) {
		this.stamp.stamp(turn, version, null, null, null, null);
	}

	private boolean holdsNothing(long turn, long version) {
		return this.stamp.holds(turn, version, null, null, null, null);
	}

	// -- when nothing was written ----------------------------------------------------------------

	@Test
	void nothingIsHeldBeforeAnythingIsStamped() {
		assertFalse(holdsNothing(0, 0));
	}

	@Test
	void aClearForgetsWhatWasStamped() {
		stampNothing(3, 7);
		assertTrue(holdsNothing(3, 7));

		this.stamp.clear();

		assertFalse(holdsNothing(3, 7));
	}

	// -- the turn and the version --------------------------------------------------------------

	@Test
	void theSameTurnAndVersionWithNothingHandedInIsHeld() {
		stampNothing(3, 7);

		assertTrue(holdsNothing(3, 7));
		assertTrue(holdsNothing(3, 7));
	}

	@Test
	void aTurnOfTheRingIsNotHeld() {
		stampNothing(3, 7);

		assertFalse(holdsNothing(4, 7));
	}

	@Test
	void aMovedFrameIsNotHeldWhateverTheRingDid() {
		stampNothing(3, 7);

		assertFalse(holdsNothing(3, 8));
	}

	@Test
	void aLaterStampReplacesTheEarlierOne() {
		stampNothing(3, 7);
		stampNothing(4, 8);

		assertTrue(holdsNothing(4, 8));
		assertFalse(holdsNothing(3, 7));
	}

	// -- the four values -----------------------------------------------------------------------

	@Test
	void anEqualValueInAnotherObjectIsHeld() {
		this.stamp.stamp(1, 1, matrix(2), matrix(3), matrix(4), new Vector4f(1, 0.5F, 0.25F, 1));

		assertTrue(this.stamp.holds(1, 1, matrix(2), matrix(3), matrix(4),
				new Vector4f(1, 0.5F, 0.25F, 1)));
	}

	@Test
	void eachOfTheFourValuesMovingIsNotHeld() {
		this.stamp.stamp(1, 1, matrix(2), matrix(3), matrix(4), new Vector4f(1, 0.5F, 0.25F, 1));

		assertFalse(this.stamp.holds(1, 1, matrix(9), matrix(3), matrix(4),
				new Vector4f(1, 0.5F, 0.25F, 1)));
		assertFalse(this.stamp.holds(1, 1, matrix(2), matrix(9), matrix(4),
				new Vector4f(1, 0.5F, 0.25F, 1)));
		assertFalse(this.stamp.holds(1, 1, matrix(2), matrix(3), matrix(9),
				new Vector4f(1, 0.5F, 0.25F, 1)));
		assertFalse(this.stamp.holds(1, 1, matrix(2), matrix(3), matrix(4),
				new Vector4f(1, 0.5F, 0.25F, 0.75F)));
	}

	@Test
	void aSingleElementOfAMatrixMovingIsNotHeld() {
		this.stamp.stamp(1, 1, matrix(2), null, null, null);
		Matrix4f moved = matrix(2).m31(0.001F);

		assertFalse(this.stamp.holds(1, 1, moved, null, null, null));
	}

	@Test
	void aValueHandedInWhereNoneWasIsNotHeldAndNorIsTheOtherWayRound() {
		stampNothing(1, 1);
		assertFalse(this.stamp.holds(1, 1, matrix(2), null, null, null));
		assertFalse(this.stamp.holds(1, 1, null, matrix(2), null, null));
		assertFalse(this.stamp.holds(1, 1, null, null, matrix(2), null));
		assertFalse(this.stamp.holds(1, 1, null, null, null, new Vector4f(1, 1, 1, 1)));

		this.stamp.stamp(1, 1, matrix(2), matrix(2), matrix(2), new Vector4f(1, 1, 1, 1));
		assertFalse(this.stamp.holds(1, 1, null, matrix(2), matrix(2), new Vector4f(1, 1, 1, 1)));
		assertFalse(this.stamp.holds(1, 1, matrix(2), null, matrix(2), new Vector4f(1, 1, 1, 1)));
		assertFalse(this.stamp.holds(1, 1, matrix(2), matrix(2), null, new Vector4f(1, 1, 1, 1)));
		assertFalse(this.stamp.holds(1, 1, matrix(2), matrix(2), matrix(2), null));
	}

	@Test
	void whiteIsNotTheSameAsNoColour() {
		// A null colour is white to the block, and a white handed in is a value: the two write the
		// same bytes and are still held apart, since telling them equal is not this class's business.
		this.stamp.stamp(1, 1, null, null, null, null);

		assertFalse(this.stamp.holds(1, 1, null, null, null, new Vector4f(1, 1, 1, 1)));
	}

	@Test
	void aSourceThatIsChangedAfterItIsStampedDoesNotChangeWhatWasStamped() {
		Matrix4f reused = matrix(2);
		Vector4f colour = new Vector4f(1, 1, 1, 1);
		this.stamp.stamp(1, 1, reused, null, null, colour);

		// The pass that handed these in reuses them for the next frame: the stamp holds the bits it
		// was given, so the moved objects are not the ones it wrote from.
		reused.m00(50);
		colour.x = 0.5F;

		assertFalse(this.stamp.holds(1, 1, reused, null, null, colour));
		assertTrue(this.stamp.holds(1, 1, matrix(2), null, null, new Vector4f(1, 1, 1, 1)));
	}

	@Test
	void theSameObjectMovedBetweenTwoAsksIsNotHeld() {
		Matrix4f reused = matrix(2);
		this.stamp.stamp(1, 1, reused, null, null, null);

		reused.m30(4);

		assertFalse(this.stamp.holds(1, 1, reused, null, null, null));
	}

	@Test
	void zerosOfTheTwoSignsAreNotTheSameBits() {
		this.stamp.stamp(1, 1, null, null, null, new Vector4f(0.0F, 0, 0, 1));

		assertFalse(this.stamp.holds(1, 1, null, null, null, new Vector4f(-0.0F, 0, 0, 1)));
	}
}
