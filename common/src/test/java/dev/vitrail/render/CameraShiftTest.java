package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

/**
 * Holds the published camera position to Iris's rule: only x and z are ever shifted, always by a
 * whole multiple of thirty thousand, and the shift moves the current and the previous position by
 * the same amount so that the difference between them, which is all a motion vector is, survives.
 * <p>
 * The hand-worked cases pin the numbers a pack can observe. The property test is a second reading
 * that shares no code with the class: a long seeded walk with jumps and teleports, checked against
 * the invariants the rule promises. Positions are multiples of 1/1024, so every sum in the walk is
 * exact in a double and the invariants are compared for equality, not within a tolerance.
 */
class CameraShiftTest {

	private static final double RANGE = 30000.0;

	private static void advance(CameraShift shift, double x, double y, double z) {
		shift.advance(new Vec3(x, y, z));
	}

	private static void assertAt(String what, double x, double y, double z, Vector3dc actual) {
		assertEquals(x, actual.x(), what + " x");
		assertEquals(y, actual.y(), what + " y");
		assertEquals(z, actual.z(), what + " z");
	}

	@Test
	void theFirstFramePublishesThePositionAndHasItselfAsItsHistory() {
		CameraShift shift = new CameraShift();
		advance(shift, 100.5, 64.0, -200.25);

		assertAt("shifted", 100.5, 64.0, -200.25, shift.shifted());
		assertAt("previous shifted", 100.5, 64.0, -200.25, shift.previousShifted());
		assertAt("unshifted", 100.5, 64.0, -200.25, shift.unshifted());
		assertAt("previous unshifted", 100.5, 64.0, -200.25, shift.previousUnshifted());
	}

	@Test
	void walkingInsideTheRangeShiftsNothingAndTheHistoryIsTheFrameBefore() {
		CameraShift shift = new CameraShift();
		double x = 29_000.0;
		for (int frame = 0; frame < 50; frame++) {
			double before = x;
			x += 0.75;
			advance(shift, x, 70.0, 12.0);

			assertEquals(x, shift.shifted().x(), "frame " + frame);
			if (frame > 0) {
				assertEquals(before, shift.previousShifted().x(), "history at frame " + frame);
			}
		}
	}

	@Test
	void crossingTheWalkRangeShiftsByOneRangeAndKeepsTheStepBetweenTheFrames() {
		CameraShift shift = new CameraShift();
		advance(shift, 29_999.0, 64.0, 0.0);
		advance(shift, 30_001.0, 64.0, 0.0);

		// 30001 % 30000 = 1, so the shift is minus one range, and the frame before goes with it.
		assertAt("shifted", 1.0, 64.0, 0.0, shift.shifted());
		assertAt("previous shifted", -1.0, 64.0, 0.0, shift.previousShifted());
		assertAt("unshifted", 30_001.0, 64.0, 0.0, shift.unshifted());
		assertAt("previous unshifted", 29_999.0, 64.0, 0.0, shift.previousUnshifted());

		// And the next frame is measured from the shifted position, not from the raw one.
		advance(shift, 30_002.0, 64.0, 0.0);
		assertAt("shifted, next frame", 2.0, 64.0, 0.0, shift.shifted());
		assertAt("previous shifted, next frame", 1.0, 64.0, 0.0, shift.previousShifted());
	}

	@Test
	void theNegativeSideShiftsTheOtherWayAndKeepsItsSign() {
		CameraShift shift = new CameraShift();
		advance(shift, 0.0, 64.0, -29_999.5);
		advance(shift, 0.0, 64.0, -30_000.75);

		// Java's remainder keeps the sign of the dividend, so this is -0.75 and the shift is +30000.
		assertAt("shifted", 0.0, 64.0, -0.75, shift.shifted());
		assertAt("previous shifted", 0.0, 64.0, 0.5, shift.previousShifted());
	}

	@Test
	void aTeleportInsideTheRangeIsNotShiftedBecauseTheRemainderIsTheWholeValue() {
		CameraShift shift = new CameraShift();
		advance(shift, 0.0, 64.0, 0.0);
		advance(shift, 5_000.0, 64.0, 0.0);

		assertAt("shifted", 5_000.0, 64.0, 0.0, shift.shifted());
		assertAt("previous shifted", 0.0, 64.0, 0.0, shift.previousShifted());
	}

	@Test
	void theTeleportTestOnlyShowsOnAPositionOfExactlyTheRange() {
		// Below the range the remainder is the whole value, so the shift is nought whatever the jump, and
		// above it the first half of the rule already fires. What is left for the second half is a
		// position of exactly thirty thousand: reached by a jump it is shifted, reached by walking it is
		// not. Iris's own arithmetic, and the reason the teleport range is worth keeping.
		CameraShift jumped = new CameraShift();
		advance(jumped, 0.0, 64.0, 0.0);
		advance(jumped, 30_000.0, 64.0, 0.0);
		assertAt("jumped", 0.0, 64.0, 0.0, jumped.shifted());
		assertAt("jumped, previous", -30_000.0, 64.0, 0.0, jumped.previousShifted());

		CameraShift walked = new CameraShift();
		advance(walked, 29_999.5, 64.0, 0.0);
		advance(walked, 30_000.0, 64.0, 0.0);
		assertAt("walked", 30_000.0, 64.0, 0.0, walked.shifted());
	}

	@Test
	void aTeleportPastTheRangeShiftsByWholeRangesAndTheFrameBeforeIsCarriedFarOut() {
		// Iris's own arithmetic: the previous position moves by the same amount, so it ends up ninety
		// thousand blocks out on the other side. It is what keeps the step at ninety five thousand.
		CameraShift shift = new CameraShift();
		advance(shift, 0.0, 64.0, 0.0);
		advance(shift, 95_000.0, 64.0, 0.0);

		assertAt("shifted", 5_000.0, 64.0, 0.0, shift.shifted());
		assertAt("previous shifted", -90_000.0, 64.0, 0.0, shift.previousShifted());
		assertEquals(95_000.0, shift.shifted().x() - shift.previousShifted().x());
	}

	@Test
	void aCameraAtTheEdgeOfTheWorldIsShiftedBackToWhereAFloatCanResolveIt() {
		CameraShift shift = new CameraShift();
		advance(shift, 30_000_000.0, 64.0, -30_000_000.0);

		assertAt("shifted", 0.0, 64.0, 0.0, shift.shifted());
		assertAt("unshifted", 30_000_000.0, 64.0, -30_000_000.0, shift.unshifted());

		// The next frame keeps the shift it has, so a step of a quarter block is still a quarter block.
		advance(shift, 29_999_999.75, 64.0, -29_999_999.5);
		assertAt("shifted, next frame", -0.25, 64.0, 0.5, shift.shifted());
		assertAt("previous shifted, next frame", 0.0, 64.0, 0.0, shift.previousShifted());

		// The reason for the whole class: at this distance a float steps by two blocks, and after the
		// shift it steps by less than a hundredth of a block.
		CameraShift fresh = new CameraShift();
		advance(fresh, 29_999_999.75, 64.0, -29_999_999.5);
		assertEquals(2.0F, Math.ulp((float) fresh.unshifted().x()), "the raw float step");
		assertEquals(29_999.75, fresh.shifted().x(), "shifted x, exact in a double");
		assertEquals(-29_999.5, fresh.shifted().z());
		assertTrue(Math.ulp((float) fresh.shifted().x()) < 0.01F, "the published float step");
	}

	@Test
	void theAltitudeIsNeverShifted() {
		CameraShift shift = new CameraShift();
		advance(shift, 0.0, 5_000_000.5, 0.0);
		advance(shift, 100_000.0, -7_000_000.25, 100_000.0);

		assertEquals(-7_000_000.25, shift.shifted().y());
		assertEquals(5_000_000.5, shift.previousShifted().y());
		assertEquals(-7_000_000.25, shift.unshifted().y());
	}

	@Test
	void aResetForgetsTheShiftAndSeedsTheNextFrameWithItself() {
		CameraShift shift = new CameraShift();
		advance(shift, 0.0, 64.0, 0.0);
		advance(shift, 95_000.0, 64.0, 0.0);
		shift.reset();

		assertAt("shifted", 0.0, 0.0, 0.0, shift.shifted());
		assertAt("unshifted", 0.0, 0.0, 0.0, shift.unshifted());

		advance(shift, 100.0, 70.0, 5.0);
		assertAt("shifted after", 100.0, 70.0, 5.0, shift.shifted());
		assertAt("previous after", 100.0, 70.0, 5.0, shift.previousShifted());
	}

	@Test
	void aNaNFrameIsNotStuckInTheShift() {
		// The comparisons in the rule are all false for a NaN, so the frame is published as NaN and the
		// shift is left as it was. The frame after it is right, its previous position being the NaN.
		CameraShift shift = new CameraShift();
		advance(shift, 100.0, 64.0, 100.0);
		advance(shift, Double.NaN, 64.0, 100.0);
		assertTrue(Double.isNaN(shift.shifted().x()));
		assertEquals(100.0, shift.shifted().z());

		advance(shift, 101.0, 64.0, 100.0);
		assertEquals(101.0, shift.shifted().x());
		assertTrue(Double.isNaN(shift.previousShifted().x()));

		advance(shift, 30_000_001.0, 64.0, 100.0);
		assertEquals(1.0, shift.shifted().x(), "a real shift after it still works");
	}

	@Test
	void knownBug_anInfinitePositionPoisonsTheShiftUntilTheNextReset() {
		// Infinity % 30000 is NaN, so the shift becomes NaN and every position after it is NaN too.
		// Nothing in the game hands the camera an infinite coordinate; pinned so that it is known.
		CameraShift shift = new CameraShift();
		advance(shift, 0.0, 64.0, 0.0);
		advance(shift, Double.POSITIVE_INFINITY, 64.0, 0.0);
		advance(shift, 10.0, 64.0, 0.0);

		assertTrue(Double.isNaN(shift.shifted().x()), "a perfectly ordinary position is now NaN");
		assertEquals(10.0, shift.unshifted().x(), "the raw one is fine");

		shift.reset();
		advance(shift, 10.0, 64.0, 0.0);
		assertEquals(10.0, shift.shifted().x());
	}

	@Test
	void aLongSeededWalkKeepsEveryInvariantTheRulePromises() {
		Random random = new Random(20_260_928L);
		CameraShift shift = new CameraShift();
		double x = 0.0;
		double y = 64.0;
		double z = 0.0;
		double previousX = x;
		double previousZ = z;
		int shifts = 0;
		double lastShiftX = 0.0;
		double lastShiftZ = 0.0;

		for (int frame = 0; frame < 20_000; frame++) {
			previousX = x;
			previousZ = z;
			switch (random.nextInt(20)) {
				case 0 -> {
					// A teleport anywhere up to the edge of the world, on the 1/1024 grid.
					x = grid(random, 30_000_000.0);
					z = grid(random, 30_000_000.0);
				}
				case 1 -> {
					// A hop inside a couple of thousand blocks: sometimes a teleport by the rule's
					// own thousand block test, sometimes not.
					x += grid(random, 2_000.0);
					z += grid(random, 2_000.0);
				}
				default -> {
					x += grid(random, 1.0);
					z += grid(random, 1.0);
				}
			}

			y = 64.0 + grid(random, 200.0);
			advance(shift, x, y, z);

			Vector3dc now = shift.shifted();
			Vector3dc before = shift.previousShifted();
			String at = "frame " + frame + " at (" + x + ", " + z + ")";

			// The first frame has itself for a history.
			double expectedPreviousX = frame == 0 ? x : previousX;
			double expectedPreviousZ = frame == 0 ? z : previousZ;

			// The altitude and the raw position are never touched.
			assertEquals(y, now.y(), at + " altitude");
			assertEquals(x, shift.unshifted().x(), at + " raw x");
			assertEquals(z, shift.unshifted().z(), at + " raw z");
			assertEquals(expectedPreviousX, shift.previousUnshifted().x(), at + " previous raw x");
			assertEquals(expectedPreviousZ, shift.previousUnshifted().z(), at + " previous raw z");

			// Shifted by whole multiples of the range and nothing else.
			double offsetX = x - now.x();
			double offsetZ = z - now.z();
			assertTrue(offsetX % RANGE == 0.0, at + " x offset " + offsetX + " is a whole range");
			assertTrue(offsetZ % RANGE == 0.0, at + " z offset " + offsetZ + " is a whole range");
			if (offsetX != lastShiftX || offsetZ != lastShiftZ) {
				shifts++;
			}

			lastShiftX = offsetX;
			lastShiftZ = offsetZ;

			// The step between the two frames survives the shift exactly.
			assertEquals(x - expectedPreviousX, now.x() - before.x(), at + " x step");
			assertEquals(z - expectedPreviousZ, now.z() - before.z(), at + " z step");

			// What is published now can always be resolved by a float.
			assertTrue(Math.abs(now.x()) <= RANGE && Math.abs(now.z()) <= RANGE,
					at + " published position (" + now.x() + ", " + now.z() + ") is inside the range");
		}

		assertTrue(shifts > 200, "the walk really crossed the range many times: " + shifts);
		assertFalse(Double.isNaN(shift.shifted().x()));
	}

	/** A value on the 1/1024 grid in [-limit, limit). */
	private static double grid(Random random, double limit) {
		return Math.floor((random.nextDouble() * 2.0 - 1.0) * limit * 1024.0) / 1024.0;
	}
}
