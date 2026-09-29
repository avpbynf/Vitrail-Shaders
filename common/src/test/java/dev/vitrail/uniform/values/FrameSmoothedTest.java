package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.FakeWorld;
import dev.vitrail.uniform.Smoothed;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.Val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link FrameSmoothed} to the one rule it exists for: a smoothed value steps when the frame
 * number moves and at no other time, so forty passes reading it in one frame get one number and
 * converge at the speed of the frame and not of the number of passes.
 * <p>
 * The expected values are the closed form of a half life: with a half life of {@code h}
 * deciseconds, a frame of {@code dt} seconds closes {@code 1 - 2^(-dt * 10 / h)} of the gap.
 */
class FrameSmoothedTest {

	private final FakeWorld world = new FakeWorld();

	@BeforeEach
	void forgetHistory() {
		FrameSmoothed.forgetAll();
	}

	private static float covered(float halfLife, float dt) {
		return (float) (1.0 - Math.pow(2.0, -dt * 10.0 / halfLife));
	}

	private void frame(int number, float dt) {
		this.world.frameCounter = number;
		this.world.frameTime = dt;
	}

	@Test
	void theFirstValueIsTheRawOneAndNotAClimbOutOfZero() {
		FrameSmoothed smoothed = new FrameSmoothed();
		frame(1, 0.05F);

		assertEquals(0.8F, smoothed.get(this.world, 0.8F, 10.0F, 10.0F));
	}

	@Test
	void everyReadInAFrameAnswersTheSameNumberWhateverIsAskedOfIt() {
		FrameSmoothed smoothed = new FrameSmoothed();
		frame(1, 0.05F);
		smoothed.get(this.world, 0.0F, 10.0F, 10.0F);

		frame(2, 0.5F);
		float once = smoothed.get(this.world, 1.0F, 10.0F, 10.0F);

		for (int pass = 0; pass < 40; pass++) {
			assertEquals(once, smoothed.get(this.world, 1.0F, 10.0F, 10.0F), "pass " + pass);
		}

		assertEquals(once, smoothed.get(this.world, 55.0F, 1.0F, 1.0F), "the arguments of a later read are not used");
		assertEquals(covered(10.0F, 0.5F), once, 1.0e-6F, "and it moved once, by one frame's worth");
	}

	@Test
	void fortyPassesConvergeAtTheSpeedOfOneFrameNotFortyOfThem() {
		FrameSmoothed smoothed = new FrameSmoothed();
		frame(1, 0.05F);
		smoothed.get(this.world, 0.0F, 10.0F, 10.0F);

		float value = 0.0F;
		for (int frame = 2; frame <= 21; frame++) {
			frame(frame, 0.05F);
			for (int pass = 0; pass < 40; pass++) {
				value = smoothed.get(this.world, 1.0F, 10.0F, 10.0F);
			}
		}

		// Twenty frames of a twentieth of a second is one second, one half life: half the gap.
		assertEquals(0.5F, value, 2.0e-3F);
	}

	@Test
	void aFrameNumberThatChangesInAnyDirectionIsANewFrame() {
		FrameSmoothed smoothed = new FrameSmoothed();
		frame(100, 0.1F);
		smoothed.get(this.world, 0.0F, 10.0F, 10.0F);

		frame(3, 0.1F);
		float backwards = smoothed.get(this.world, 1.0F, 10.0F, 10.0F);

		assertEquals(covered(10.0F, 0.1F), backwards, 1.0e-6F, "a level joined at a lower counter still steps");
	}

	@Test
	void aFrameThatMeasuresNothingHoldsTheValueButStillCountsAsTheFrame() {
		FrameSmoothed smoothed = new FrameSmoothed();
		frame(1, 0.1F);
		smoothed.get(this.world, 1.0F, 10.0F, 10.0F);

		frame(2, 0.0F);
		assertEquals(1.0F, smoothed.get(this.world, 0.0F, 10.0F, 10.0F));

		// The same frame again, now with a duration: it has already been spent, so nothing moves.
		this.world.frameTime = 0.1F;
		assertEquals(1.0F, smoothed.get(this.world, 0.0F, 10.0F, 10.0F));

		frame(3, 0.1F);
		assertEquals(1.0F - covered(10.0F, 0.1F), smoothed.get(this.world, 0.0F, 10.0F, 10.0F), 1.0e-6F);
	}

	@Test
	void risesAtTheFirstHalfLifeAndFallsAtTheSecond() {
		FrameSmoothed smoothed = new FrameSmoothed();
		frame(1, 0.1F);
		smoothed.get(this.world, 0.0F, 10.0F, 200.0F);

		frame(2, 0.1F);
		float risen = smoothed.get(this.world, 1.0F, 10.0F, 200.0F);
		assertEquals(covered(10.0F, 0.1F), risen, 1.0e-6F);

		frame(3, 0.1F);
		float fallen = smoothed.get(this.world, 0.0F, 10.0F, 200.0F);
		assertEquals(risen * (1.0F - covered(200.0F, 0.1F)), fallen, 1.0e-6F);
	}

	@Test
	void twoAccumulatorsNeverShareAValue() {
		FrameSmoothed first = new FrameSmoothed();
		FrameSmoothed second = new FrameSmoothed();
		frame(1, 0.1F);

		assertEquals(3.0F, first.get(this.world, 3.0F, 10.0F, 10.0F));
		assertEquals(9.0F, second.get(this.world, 9.0F, 10.0F, 10.0F));
	}

	@Test
	void forgettingDropsTheValueTheFrameAndTheHistoryOfEveryAccumulator() {
		FrameSmoothed first = new FrameSmoothed();
		FrameSmoothed second = new FrameSmoothed();
		frame(1, 0.1F);
		first.get(this.world, 1.0F, 10.0F, 10.0F);
		second.get(this.world, 1.0F, 10.0F, 10.0F);
		frame(2, 0.1F);
		first.get(this.world, 0.0F, 10.0F, 10.0F);
		second.get(this.world, 0.0F, 10.0F, 10.0F);

		FrameSmoothed.forgetAll();

		// The same frame number as before the reset: a memory of it would answer the old value.
		assertEquals(7.0F, first.get(this.world, 7.0F, 10.0F, 10.0F), "outright, on the same frame number");
		assertEquals(4.0F, second.get(this.world, 4.0F, 10.0F, 10.0F));
	}

	@Test
	void forgettingResetsTheEnginesOwnAccumulatorsToo() {
		// Wetness and the eye's brightness are held by the catalogue, and a dimension change or a pack
		// load drops them with everything else.
		UniformCatalog engine = UniformCatalog.engine();
		frame(1, 0.1F);
		this.world.rainStrength = 1.0F;
		Val val = new Val();
		engine.source("wetness").read(this.world, val);
		assertEquals(1.0F, val.f(0));

		frame(2, 0.1F);
		this.world.rainStrength = 0.0F;
		engine.source("wetness").read(this.world, val);
		assertTrue(val.f(0) > 0.9F, "it fades and does not fall to the rain");

		FrameSmoothed.forgetAll();
		frame(3, 0.1F);
		engine.source("wetness").read(this.world, val);
		assertEquals(0.0F, val.f(0), "the first value after a forget is the raw one");
	}

	@Test
	void aPacksOwnAccumulatorIsForgottenWithTheEngines() {
		Smoothed mine = FrameSmoothed.tracked();
		Smoothed other = FrameSmoothed.tracked();
		assertNotSame(mine, other);

		mine.updateAndGet(1.0F, 10.0F, 10.0F, 0.1F);
		other.updateAndGet(1.0F, 10.0F, 10.0F, 0.1F);
		float moved = mine.updateAndGet(0.0F, 10.0F, 10.0F, 0.1F);
		assertTrue(moved > 0.0F && moved < 1.0F);

		FrameSmoothed.forgetAll();

		assertEquals(5.0F, mine.updateAndGet(5.0F, 10.0F, 10.0F, 0.1F), "outright again");
		assertEquals(6.0F, other.updateAndGet(6.0F, 10.0F, 10.0F, 0.1F));
	}
}
