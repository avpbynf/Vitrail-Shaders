package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.FakeWorld;
import dev.vitrail.uniform.UniformCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the smoothed brightness of the player's eyes to what its comment promises: the smoothing is
 * on the number and the truncation to an integer comes after it, so the value climbs a step at a
 * time on the way out of a cave instead of snapping, and the two components have accumulators of
 * their own.
 * <p>
 * The expected numbers are the closed form of a half life in deciseconds: a frame of {@code dt}
 * seconds closes {@code 1 - 2^(-dt * 10 / h)} of the gap. Every accessor the rest of this class
 * reads has a test of its own in {@link PassThroughValuesTest}.
 */
class PlayerValuesTest {

	private final UniformCatalog engine = UniformCatalog.engine();
	private final FakeWorld world = new FakeWorld();

	@BeforeEach
	void forgetHistory() {
		FrameSmoothed.forgetAll();
	}

	private int[] smooth() {
		return ValueReads.ints(ValueReads.read(this.engine, "eyeBrightnessSmooth", this.world), 2);
	}

	private void frame(int number, float dt) {
		this.world.frameCounter = number;
		this.world.frameTime = dt;
	}

	@Test
	void theFirstFrameIsTheRawBrightnessAndNotAClimbFromNothing() {
		this.world.eyeBrightnessBlock = 12;
		this.world.eyeBrightnessSky = 240;
		frame(1, 0.05F);

		assertArrayEquals(new int[] { 12, 240 }, smooth());
	}

	@Test
	void smoothsBeforeTruncatingSoAHalfWayStepRoundsDown() {
		this.world.eyeBrightnessHalfLife = 10.0F;
		this.world.eyeBrightnessBlock = 0;
		this.world.eyeBrightnessSky = 240;
		frame(1, 1.0F);
		smooth();

		// One second, one half life: the block light goes half way from 0 to 15, 7.5, and the integer
		// is 7 (truncated) where rounding it would say 8. The sky goes half way from 240 to 0.
		this.world.eyeBrightnessBlock = 15;
		this.world.eyeBrightnessSky = 0;
		frame(2, 1.0F);

		assertArrayEquals(new int[] { 7, 120 }, smooth());
	}

	@Test
	void climbsAStepAtATimeAndConvergesFromBelowWithoutEverReachingTheTarget() {
		this.world.eyeBrightnessHalfLife = 10.0F;
		this.world.eyeBrightnessBlock = 0;
		frame(1, 0.5F);
		smooth();

		this.world.eyeBrightnessBlock = 15;
		double gap = 15.0;
		int previous = 0;
		for (int frame = 2; frame <= 40; frame++) {
			frame(frame, 0.5F);
			gap *= Math.pow(2.0, -0.5 * 10.0 / 10.0);

			int value = smooth()[0];
			assertEquals((int) (15.0 - gap), value, "frame " + frame);
			assertTrue(value >= previous && value <= 15, "never falls back, never overshoots");
			previous = value;
		}

		assertEquals(14, previous, "the last step is still a step short after twenty seconds' worth of half lives");
	}

	@Test
	void theRiseAndTheFallShareTheSingleHalfLifeTheWorldHandsOver() {
		this.world.eyeBrightnessHalfLife = 10.0F;
		this.world.eyeBrightnessBlock = 0;
		this.world.eyeBrightnessSky = 200;
		frame(1, 1.0F);
		smooth();

		this.world.eyeBrightnessBlock = 100;
		this.world.eyeBrightnessSky = 0;
		frame(2, 1.0F);

		// Half the way up for the one and half the way down for the other, in a second each.
		assertArrayEquals(new int[] { 50, 100 }, smooth());
	}

	@Test
	void everyReadInAFrameAnswersTheSameAndOnlyANewFrameMovesIt() {
		this.world.eyeBrightnessHalfLife = 10.0F;
		this.world.eyeBrightnessBlock = 0;
		frame(1, 1.0F);
		smooth();

		this.world.eyeBrightnessBlock = 100;
		frame(2, 1.0F);
		int[] first = smooth();

		for (int pass = 0; pass < 30; pass++) {
			assertArrayEquals(first, smooth(), "pass " + pass);
		}

		frame(3, 1.0F);
		assertEquals(75, smooth()[0], "the second frame closes half of what is left");
	}

	@Test
	void theTwoComponentsKeepAccumulatorsOfTheirOwn() {
		this.world.eyeBrightnessHalfLife = 10.0F;
		this.world.eyeBrightnessBlock = 40;
		this.world.eyeBrightnessSky = 80;
		frame(1, 1.0F);
		smooth();

		this.world.eyeBrightnessBlock = 40;
		this.world.eyeBrightnessSky = 160;
		frame(2, 1.0F);

		assertArrayEquals(new int[] { 40, 120 }, smooth(), "the block light stayed, the sky light moved");
	}

	@Test
	void theRawEyeBrightnessIsTheWorldsPairWithTheBlockLightFirst() {
		this.world.eyeBrightnessBlock = 3;
		this.world.eyeBrightnessSky = 14;

		assertArrayEquals(new int[] { 3, 14 },
				ValueReads.ints(ValueReads.read(this.engine, "eyeBrightness", this.world), 2));
	}
}
