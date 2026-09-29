package dev.vitrail.sodium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.joml.FrustumIntersection;
import org.junit.jupiter.api.Test;

/**
 * The cube round the camera that a shadow distance bounds the walk with, and the safe zone that may
 * outrank it. Sodium subtracts the camera before it asks, so every box here is camera relative and
 * the cube is a comparison against the distance itself.
 * <p>
 * The numbers are hand picked around the edges: a box that only touches the cube is kept, a box the
 * cube holds whole is INSIDE (which lets Sodium stop testing the subtree under it) and one it merely
 * reaches is INTERSECT. Each axis is failed on its own, so a comparison written for one axis on another
 * is caught.
 */
class ShadowBoxTest {

	private static final int INSIDE = FrustumIntersection.INSIDE;
	private static final int INTERSECT = FrustumIntersection.INTERSECT;

	private static ShadowBox box(float distance) {
		return new ShadowBox(distance, -1.0F);
	}

	// -- whether any part of a box is within the distance ------------------------------------

	@Test
	void aBoxAroundTheCameraIsWithinAnyDistanceAndAFarOneIsNot() {
		ShadowBox box = box(32.0F);

		assertTrue(box.inside(-1, -1, -1, 1, 1, 1));
		assertTrue(box.inside(-32, -32, -32, 32, 32, 32));
		assertTrue(box.inside(-500, -500, -500, 500, 500, 500), "one that spans the cube reaches it");
		assertFalse(box.inside(40, 40, 40, 50, 50, 50));
		assertFalse(box.inside(-50, -50, -50, -40, -40, -40));
	}

	@Test
	void aBoxThatOnlyTouchesTheCubeIsWithinIt() {
		ShadowBox box = box(32.0F);

		assertTrue(box.inside(32, 0, 0, 40, 1, 1), "min on the plus face");
		assertTrue(box.inside(-40, 0, 0, -32, 1, 1), "max on the minus face");
		assertTrue(box.inside(0, 32, 0, 1, 40, 1));
		assertTrue(box.inside(0, -40, 0, 1, -32, 1));
		assertTrue(box.inside(0, 0, 32, 1, 1, 40));
		assertTrue(box.inside(0, 0, -40, 1, 1, -32));
	}

	@Test
	void aBoxJustPastTheCubeOnAnyOneAxisIsNotWithinIt() {
		ShadowBox box = box(32.0F);

		assertFalse(box.inside(32.001F, 0, 0, 40, 1, 1));
		assertFalse(box.inside(-40, 0, 0, -32.001F, 1, 1));
		assertFalse(box.inside(0, 32.001F, 0, 1, 40, 1));
		assertFalse(box.inside(0, -40, 0, 1, -32.001F, 1));
		assertFalse(box.inside(0, 0, 32.001F, 1, 1, 40));
		assertFalse(box.inside(0, 0, -40, 1, 1, -32.001F));
	}

	@Test
	void theThreeAxesAreIndependentSoOneAxisOutIsEnough() {
		ShadowBox box = box(10.0F);

		assertFalse(box.inside(11, -1, -1, 12, 1, 1));
		assertFalse(box.inside(-1, 11, -1, 1, 12, 1));
		assertFalse(box.inside(-1, -1, 11, 1, 1, 12));
		assertTrue(box.inside(-1, -1, -1, 1, 1, 1));
	}

	@Test
	void aBoxAsThinAsAPointIsWithinExactlyWhenThePointIs() {
		ShadowBox box = box(8.0F);

		assertTrue(box.inside(8, 8, 8, 8, 8, 8), "a corner of the cube");
		assertTrue(box.inside(-8, -8, -8, -8, -8, -8));
		assertFalse(box.inside(8.0001F, 0, 0, 8.0001F, 0, 0));
		assertTrue(box.inside(0, 0, 0, 0, 0, 0));
	}

	@Test
	void aDistanceOfNothingKeepsOnlyABoxThatTouchesTheCamera() {
		ShadowBox box = box(0.0F);

		assertTrue(box.inside(-1, -1, -1, 1, 1, 1));
		assertTrue(box.inside(0, 0, 0, 5, 5, 5));
		assertFalse(box.inside(0.5F, 0, 0, 5, 5, 5));
	}

	@Test
	void aDistanceThatIsNotANumberKeepsNothingAndAnInfiniteOneEverything() {
		assertFalse(box(Float.NaN).inside(-1, -1, -1, 1, 1, 1));
		assertFalse(box(Float.NaN).inside(-1.0E30F, -1.0E30F, -1.0E30F, 1.0E30F, 1.0E30F, 1.0E30F));
		assertTrue(box(Float.POSITIVE_INFINITY).inside(1.0E30F, 1.0E30F, 1.0E30F, 2.0E30F, 2.0E30F, 2.0E30F));
	}

	@Test
	void aBoxWithNanCoordinatesIsNeverWithinBecauseEveryComparisonFails() {
		// The walk then drops it. Sodium never hands one over, so this is only a guard's shape.
		assertFalse(box(32.0F).inside(Float.NaN, 0, 0, 1, 1, 1));
		assertFalse(box(32.0F).inside(0, 0, 0, 1, 1, Float.NaN));
	}

	@Test
	void aNegativeDistanceKeepsOnlyBoxesWiderThanItsMagnitude() {
		// ShadowCullFrustum.of wraps nothing for a negative bound, so no ShadowBox is built with one.
		// If one were, "at most the distance" and "at least minus the distance" cross over.
		ShadowBox box = box(-5.0F);

		assertFalse(box.inside(-1, -1, -1, 1, 1, 1));
		assertTrue(box.inside(-6, -6, -6, 6, 6, 6));
	}

	// -- held whole, or only reached ----------------------------------------------------------

	@Test
	void aBoxWhollyWithinTheDistanceIsInsideAndThatIsInclusive() {
		ShadowBox box = box(32.0F);

		assertEquals(INSIDE, box.wholeOrPart(-1, -1, -1, 1, 1, 1));
		assertEquals(INSIDE, box.wholeOrPart(-32, -32, -32, 32, 32, 32), "touching every face still holds it");
		assertEquals(INSIDE, box.wholeOrPart(0, 0, 0, 0, 0, 0));
	}

	@Test
	void aBoxThatReachesPastTheCubeOnAnyOneSideIsOnlyAnIntersection() {
		ShadowBox box = box(32.0F);

		assertEquals(INTERSECT, box.wholeOrPart(-32.001F, -1, -1, 1, 1, 1));
		assertEquals(INTERSECT, box.wholeOrPart(-1, -1, -1, 32.001F, 1, 1));
		assertEquals(INTERSECT, box.wholeOrPart(-1, -32.001F, -1, 1, 1, 1));
		assertEquals(INTERSECT, box.wholeOrPart(-1, -1, -1, 1, 32.001F, 1));
		assertEquals(INTERSECT, box.wholeOrPart(-1, -1, -32.001F, 1, 1, 1));
		assertEquals(INTERSECT, box.wholeOrPart(-1, -1, -1, 1, 1, 32.001F));
	}

	@Test
	void aWiderSafeZoneHoldsWholeWhatTheDistanceOnlyReaches() {
		ShadowBox box = new ShadowBox(32.0F, 48.0F);

		assertEquals(INSIDE, box.wholeOrPart(-40, -40, -40, 40, 40, 40), "the safe zone outranks the distance");
		assertEquals(INSIDE, box.wholeOrPart(-48, -48, -48, 48, 48, 48));
		assertEquals(INTERSECT, box.wholeOrPart(-48.001F, 0, 0, 1, 1, 1));
		assertEquals(INTERSECT, box.wholeOrPart(-60, -60, -60, 60, 60, 60));
	}

	@Test
	void aNarrowerSafeZoneChangesNothingBecauseTheDistanceHoldsAnythingItHolds() {
		ShadowBox box = new ShadowBox(32.0F, 16.0F);

		assertEquals(INSIDE, box.wholeOrPart(-10, -10, -10, 10, 10, 10));
		assertEquals(INSIDE, box.wholeOrPart(-30, -30, -30, 30, 30, 30), "beyond the safe zone, inside the distance");
		assertEquals(INTERSECT, box.wholeOrPart(-33, 0, 0, 1, 1, 1));
	}

	@Test
	void noSafeZoneIsANegativeOneAndNothingIsHeldWholeByIt() {
		ShadowBox none = new ShadowBox(32.0F, -1.0F);
		ShadowBox smallNegative = new ShadowBox(32.0F, -0.001F);

		assertEquals(INTERSECT, none.wholeOrPart(-40, -40, -40, 40, 40, 40));
		assertEquals(INTERSECT, smallNegative.wholeOrPart(-40, -40, -40, 40, 40, 40));
	}

	@Test
	void aSafeZoneOfNothingIsASafeZoneAndHoldsOnlyTheOrigin() {
		// The distance is put out of the picture, so the answer is the safe zone's alone.
		ShadowBox box = new ShadowBox(-1.0F, 0.0F);

		assertEquals(INSIDE, box.wholeOrPart(0, 0, 0, 0, 0, 0));
		assertEquals(INTERSECT, box.wholeOrPart(-1, -1, -1, 1, 1, 1));
		assertEquals(INTERSECT, box.wholeOrPart(0, 0, 0, 0.001F, 0, 0));
		assertEquals(INSIDE, new ShadowBox(-1.0F, 16.0F).wholeOrPart(-16, -16, -16, 16, 16, 16));
	}

	@Test
	void everyAxisOfTheSafeZoneIsCheckedAndNotOnlyTheFirst() {
		ShadowBox box = new ShadowBox(8.0F, 24.0F);

		assertEquals(INSIDE, box.wholeOrPart(-20, -20, -20, 20, 20, 20));
		assertEquals(INTERSECT, box.wholeOrPart(-20, -20, -20, 25, 20, 20));
		assertEquals(INTERSECT, box.wholeOrPart(-20, -25, -20, 20, 20, 20));
		assertEquals(INTERSECT, box.wholeOrPart(-20, -20, -20, 20, 20, 25));
		assertEquals(INTERSECT, box.wholeOrPart(-25, -20, -20, 20, 20, 20));
		assertEquals(INTERSECT, box.wholeOrPart(-20, -20, -25, 20, 20, 20));
	}

	@Test
	void theTwoAnswersAgreeOnSeededRandomBoxes() {
		Random random = new Random(20260928);
		for (int i = 0; i < 20_000; i++) {
			float distance = random.nextInt(4) == 0 ? -1.0F : random.nextFloat() * 64.0F;
			float safeZone = random.nextInt(3) == 0 ? -1.0F : random.nextFloat() * 96.0F;
			float cx = random.nextFloat() * 160 - 80;
			float cy = random.nextFloat() * 160 - 80;
			float cz = random.nextFloat() * 160 - 80;
			float hx = random.nextFloat() * 24;
			float hy = random.nextFloat() * 24;
			float hz = random.nextFloat() * 24;
			float[] lo = {cx - hx, cy - hy, cz - hz};
			float[] hi = {cx + hx, cy + hy, cz + hz};

			ShadowBox box = new ShadowBox(distance, safeZone);

			// A second reading, per axis, of "some part of the box is within d" and "all of it is".
			boolean reaches = true;
			boolean holds = true;
			boolean holdsSafe = safeZone >= 0.0F;
			for (int axis = 0; axis < 3; axis++) {
				reaches &= hi[axis] >= -distance && lo[axis] <= distance;
				holds &= lo[axis] >= -distance && hi[axis] <= distance;
				holdsSafe &= lo[axis] >= -safeZone && hi[axis] <= safeZone;
			}

			String at = "distance " + distance + " safe " + safeZone + " box " + lo[0] + "," + lo[1] + ","
					+ lo[2] + " to " + hi[0] + "," + hi[1] + "," + hi[2];
			assertEquals(reaches, box.inside(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]), at);
			assertEquals((holds || holdsSafe) ? INSIDE : INTERSECT,
					box.wholeOrPart(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]), at);
		}
	}
}
