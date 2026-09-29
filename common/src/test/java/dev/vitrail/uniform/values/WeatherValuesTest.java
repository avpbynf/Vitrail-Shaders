package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitrail.uniform.FakeWorld;
import dev.vitrail.uniform.UniformCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the weather to what a pack is written against: {@code wetness} is the rain smoothed with a
 * rise the pack sets and a fall it cannot, the sky colour is unpacked in the order the game packs
 * it, and the fixed function fog is the game's fog with a scale that is a reciprocal.
 * <p>
 * The smoothing is checked against the closed form of a half life in deciseconds, which is
 * {@code 1 - 2^(-seconds * 10 / h)} of the gap per interval, and every read of a frame is
 * checked against every other.
 */
class WeatherValuesTest {

	private final UniformCatalog engine = UniformCatalog.engine();
	private final FakeWorld world = new FakeWorld();

	@BeforeEach
	void forgetHistory() {
		FrameSmoothed.forgetAll();
	}

	private float wetness() {
		return ValueReads.read(this.engine, "wetness", this.world).f(0);
	}

	private void frame(int number) {
		this.world.frameCounter = number;
		this.world.frameTime = 0.1F;
	}

	/** The fraction of a gap a half life of {@code halfLife} deciseconds closes in {@code seconds}. */
	private static double covered(double halfLife, double seconds) {
		return 1.0 - Math.pow(2.0, -seconds * 10.0 / halfLife);
	}

	@Test
	void wetnessStartsAtTheRainAndDoesNotClimbOutOfZero() {
		this.world.rainStrength = 0.8F;
		frame(1);

		assertEquals(0.8F, wetness());
	}

	@Test
	void wetnessRisesAtTheHalfLifeThePackSet() {
		this.world.wetnessHalfLife = 10.0F;
		this.world.drynessHalfLife = 200.0F;
		this.world.rainStrength = 0.0F;
		frame(1);
		wetness();

		this.world.rainStrength = 1.0F;
		float value = 0.0F;
		for (int frame = 2; frame <= 11; frame++) {
			frame(frame);
			value = wetness();
		}

		// Ten frames of a tenth of a second, one half life of one second: half the gap.
		assertEquals(covered(10.0, 1.0), value, 2.0e-3);
	}

	@Test
	void wetnessFallsAtTheFixedHalfLifeAndNeverAtThePacks() {
		// The pack's rise is fast, one tenth of a second, and the fall the world hands over is 200
		// deciseconds. A fall that used the rise would be gone in a blink.
		this.world.wetnessHalfLife = 1.0F;
		this.world.drynessHalfLife = 200.0F;
		this.world.rainStrength = 1.0F;
		frame(1);
		wetness();

		this.world.rainStrength = 0.0F;
		float value = 1.0F;
		for (int frame = 2; frame <= 11; frame++) {
			frame(frame);
			value = wetness();
		}

		assertEquals(1.0 - covered(200.0, 1.0), value, 2.0e-3);
	}

	@Test
	void theRiseAndTheFallUseTheirOwnHalfLivesInBothDirections() {
		this.world.wetnessHalfLife = 10.0F;
		this.world.drynessHalfLife = 10.0F;
		this.world.rainStrength = 1.0F;
		frame(1);
		wetness();

		this.world.rainStrength = 0.0F;
		float value = 1.0F;
		for (int frame = 2; frame <= 11; frame++) {
			frame(frame);
			value = wetness();
		}

		assertEquals(0.5, value, 2.0e-3, "with the two equal, one second closes half the gap in either direction");
	}

	@Test
	void wetnessStepsOncePerFrameHoweverManyPassesReadIt() {
		this.world.wetnessHalfLife = 10.0F;
		this.world.drynessHalfLife = 200.0F;
		this.world.rainStrength = 0.0F;
		frame(1);
		wetness();

		this.world.rainStrength = 1.0F;
		frame(2);
		float first = wetness();
		for (int pass = 0; pass < 40; pass++) {
			assertEquals(first, wetness(), "pass " + pass);
		}

		assertEquals(covered(10.0, 0.1), first, 1.0e-5);
	}

	@Test
	void wetnessHoldsOnAFrameThatMeasuredNothing() {
		this.world.rainStrength = 0.0F;
		frame(1);
		wetness();

		this.world.rainStrength = 1.0F;
		frame(2);
		this.world.frameTime = 0.0F;

		assertEquals(0.0F, wetness());
	}

	@Test
	void rainAndThunderAreTheGamesOwnAndNotSmoothed() {
		this.world.rainStrength = 0.6F;
		this.world.thunderStrength = 0.3F;
		frame(1);
		wetness();
		this.world.rainStrength = 0.9F;
		this.world.thunderStrength = 0.1F;
		frame(2);

		assertEquals(0.9F, ValueReads.read(this.engine, "rainStrength", this.world).f(0));
		assertEquals(0.1F, ValueReads.read(this.engine, "thunderStrength", this.world).f(0));
	}

	@Test
	void theSkyColourIsUnpackedRedFromTheThirdByteAndIgnoresTheFourth() {
		int[] packed = { 0x336699, 0xFF336699, 0x00000000, 0xFFFFFFFF, 0x010203, 0xFF0000, 0x00FF00, 0x0000FF };
		float[][] expected = { { 0x33, 0x66, 0x99 }, { 0x33, 0x66, 0x99 }, { 0, 0, 0 }, { 255, 255, 255 },
				{ 1, 2, 3 }, { 255, 0, 0 }, { 0, 255, 0 }, { 0, 0, 255 } };

		for (int i = 0; i < packed.length; i++) {
			this.world.skyColorPacked = packed[i];

			assertArrayEquals(new float[] { expected[i][0] / 255.0F, expected[i][1] / 255.0F, expected[i][2] / 255.0F },
					ValueReads.floats(ValueReads.read(this.engine, "skyColor", this.world), 3),
					Integer.toHexString(packed[i]));
		}
	}

	@Test
	void theFixedFunctionFogIsTheGamesFogWithAScaleThatIsTheReciprocalOfTheRange() {
		this.world.fogR = 0.5F;
		this.world.fogG = 0.25F;
		this.world.fogB = 0.75F;
		this.world.fogA = 0.875F;
		this.world.fogDensity = 0.3F;
		this.world.fogStart = 24.0F;
		this.world.fogEnd = 88.0F;

		// r g b a, density, start, end, scale = 1 / (88 - 24) = 1/64.
		assertArrayEquals(new float[] { 0.5F, 0.25F, 0.75F, 0.875F, 0.3F, 24.0F, 88.0F, 1.0F / 64.0F },
				ValueReads.floats(ValueReads.read(this.engine, "of_Fog", this.world), 8));
	}

	@Test
	void theFogScaleIsNoughtForAnEmptyRangeAndNotAnInfinity() {
		this.world.fogStart = 40.0F;
		this.world.fogEnd = 40.0F;

		float[] fog = ValueReads.floats(ValueReads.read(this.engine, "of_Fog", this.world), 8);

		assertEquals(0.0F, fog[7]);
	}

	@Test
	void theFogScaleFollowsTheSignOfARangeThatRunsBackwards() {
		// An end before the start is a range the game does not produce; the reciprocal is taken as it
		// stands and comes out negative.
		this.world.fogStart = 100.0F;
		this.world.fogEnd = 50.0F;

		assertEquals(-1.0F / 50.0F, ValueReads.floats(ValueReads.read(this.engine, "of_Fog", this.world), 8)[7]);
	}

	@Test
	void theFogDensityInTheStructIsClampedAtNoughtLikeTheUniformOfTheSameName() {
		this.world.fogDensity = -2.0F;

		assertEquals(0.0F, ValueReads.floats(ValueReads.read(this.engine, "of_Fog", this.world), 8)[4]);
		assertEquals(0.0F, ValueReads.read(this.engine, "fogDensity", this.world).f(0));
	}

	@Test
	void theFogColourUniformIsRedGreenBlueAndLeavesTheAlphaToTheStruct() {
		this.world.fogR = 0.125F;
		this.world.fogG = 0.25F;
		this.world.fogB = 0.5F;
		this.world.fogA = 0.75F;

		assertArrayEquals(new float[] { 0.125F, 0.25F, 0.5F },
				ValueReads.floats(ValueReads.read(this.engine, "fogColor", this.world), 3));
	}
}
