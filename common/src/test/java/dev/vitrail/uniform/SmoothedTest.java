package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link Smoothed} to the exponential it is: a half life in DECISECONDS, the gap to the value
 * halving every half life whatever the frame rate, and none of it applied to the first value.
 * <p>
 * The expected numbers are closed forms worked out here: after a time {@code t} with a half life of
 * {@code h} deciseconds the accumulator has covered {@code 1 - 2^(-t / (h / 10))} of the way. The
 * unit is the thing worth pinning, so the tests name the two neighbours a wrong unit produces (a
 * half life read as seconds, and one read as game ticks) and require the answer to be neither.
 */
class SmoothedTest {

	/** The fraction of the gap covered after {@code seconds} with a half life of {@code halfLife} deciseconds. */
	private static double covered(double halfLife, double seconds) {
		return 1.0 - Math.pow(2.0, -seconds / (halfLife / 10.0));
	}

	/** Starts an accumulator at {@code from} and feeds it {@code to} for {@code frames} frames of {@code dt}. */
	private static float run(float from, float to, float up, float down, int frames, float dt) {
		Smoothed smoothed = new Smoothed();
		smoothed.updateAndGet(from, up, down, dt);

		float value = from;
		for (int i = 0; i < frames; i++) {
			value = smoothed.updateAndGet(to, up, down, dt);
		}

		return value;
	}

	@Test
	void aHalfLifeOfTenDecisecondsIsOneSecond() {
		assertEquals(0.5, Smoothed.blend(10.0F, 1.0F), 1.0e-6);
		assertEquals(0.75, Smoothed.blend(10.0F, 2.0F), 1.0e-6);
		assertEquals(0.5, Smoothed.blend(20.0F, 2.0F), 1.0e-6);
		assertEquals(0.5, Smoothed.blend(5.0F, 0.5F), 1.0e-6);
		assertEquals(covered(20.0, 1.0), Smoothed.blend(20.0F, 1.0F), 1.0e-6);
		assertEquals(covered(300.0, 7.5), Smoothed.blend(300.0F, 7.5F), 1.0e-6);
	}

	@Test
	void aWrongUnitIsAValueThatMovesAndIsNotThisOne() {
		// Read as seconds, a half life of 10 takes ten seconds: one second covers 1 - 2^-0.1.
		double asSeconds = 1.0 - Math.pow(2.0, -1.0 / 10.0);
		// Read as game ticks of a twentieth of a second, it takes half a second: a second covers 3/4.
		double asTicks = 1.0 - Math.pow(2.0, -1.0 / (10.0 / 20.0));

		float blend = Smoothed.blend(10.0F, 1.0F);

		assertTrue(Math.abs(blend - asSeconds) > 0.4, "not seconds: " + blend + " against " + asSeconds);
		assertTrue(Math.abs(blend - asTicks) > 0.2, "not ticks: " + blend + " against " + asTicks);
	}

	@Test
	void theFirstValueIsTakenOutrightWhateverTheFrameOrTheHalfLives() {
		for (float dt : new float[] { 0.0F, -1.0F, 0.016F, 30.0F }) {
			assertEquals(7.5F, new Smoothed().updateAndGet(7.5F, 10.0F, 10.0F, dt), "dt " + dt);
		}

		assertEquals(-3.0F, new Smoothed().updateAndGet(-3.0F, 1.0e9F, 1.0e9F, 0.016F));
	}

	@Test
	void coversHalfTheGapInOneHalfLifeAndThreeQuartersInTwo() {
		// A thousand frames of a millisecond is a second; ten deciseconds.
		float afterOne = run(0.0F, 1.0F, 10.0F, 10.0F, 1000, 0.001F);
		float afterTwo = run(0.0F, 1.0F, 10.0F, 10.0F, 2000, 0.001F);

		assertEquals(0.5F, afterOne, 1.0e-3F);
		assertEquals(0.75F, afterTwo, 1.0e-3F);
	}

	@Test
	void followsTheClosedFormAtAnyStartAndTarget() {
		float[][] cases = { { 0.0F, 1.0F }, { 1.0F, 0.0F }, { -5.0F, 20.0F }, { 240.0F, 15.0F } };
		for (float[] pair : cases) {
			float value = run(pair[0], pair[1], 30.0F, 30.0F, 500, 0.01F);

			double expected = pair[1] + (pair[0] - pair[1]) * (1.0 - covered(30.0, 5.0));
			assertEquals(expected, value, 1.0e-3 * (1.0 + Math.abs(pair[1] - pair[0])), pair[0] + " to " + pair[1]);
		}
	}

	@Test
	void reachesTheSameValueWhateverTheFrameRate() {
		// Two seconds, cut into frames of two seconds, half a second, a tenth, a sixtieth-ish and a
		// millisecond: the same closed form, so the same value.
		float expected = (float) covered(15.0, 2.0);
		int[] frames = { 1, 4, 20, 125, 2000 };
		float[] dts = { 2.0F, 0.5F, 0.1F, 0.016F, 0.001F };

		for (int i = 0; i < frames.length; i++) {
			assertEquals(expected, run(0.0F, 1.0F, 15.0F, 15.0F, frames[i], dts[i]), 2.0e-3F, "dt " + dts[i]);
		}
	}

	@Test
	void theRiseAndTheFallHaveHalfLivesOfTheirOwn() {
		// Rising uses the first, falling the second; a slow rise with a fast fall and the reverse.
		Smoothed smoothed = new Smoothed();
		smoothed.updateAndGet(0.0F, 10.0F, 1000.0F, 0.001F);

		float risen = 0.0F;
		for (int i = 0; i < 1000; i++) {
			risen = smoothed.updateAndGet(1.0F, 10.0F, 1000.0F, 0.001F);
		}
		assertEquals(0.5F, risen, 1.0e-3F, "a second at a half life of one second, the fall's 100 ignored");

		float fallen = risen;
		for (int i = 0; i < 1000; i++) {
			fallen = smoothed.updateAndGet(0.0F, 10.0F, 1000.0F, 0.001F);
		}
		assertTrue(fallen > 0.49F, "a second at a half life of one hundred seconds barely moves it: " + fallen);

		Smoothed swapped = new Smoothed();
		swapped.updateAndGet(1.0F, 1000.0F, 10.0F, 0.001F);
		float fell = 1.0F;
		for (int i = 0; i < 1000; i++) {
			fell = swapped.updateAndGet(0.0F, 1000.0F, 10.0F, 0.001F);
		}
		assertEquals(0.5F, fell, 1.0e-3F, "and a fall at the fast half life halves it");
	}

	@Test
	void aValueEqualToTheAccumulatorIsNeitherARiseNorAMoveAtAll() {
		Smoothed smoothed = new Smoothed();
		smoothed.updateAndGet(4.0F, 1.0F, 1.0e9F, 0.016F);

		assertEquals(4.0F, smoothed.updateAndGet(4.0F, 1.0F, 1.0F, 0.016F));
	}

	@Test
	void aHalfLifeOfZeroIsNoSmoothingAtAllForAFrameThatLasted() {
		assertEquals(1.0F, Smoothed.blend(0.0F, 0.001F));
		assertEquals(1.0F, Smoothed.blend(0.0F, 30.0F));

		Smoothed smoothed = new Smoothed();
		smoothed.updateAndGet(2.0F, 0.0F, 0.0F, 0.016F);
		assertEquals(9.0F, smoothed.updateAndGet(9.0F, 0.0F, 0.0F, 0.016F), "a rise");
		assertEquals(-1.0F, smoothed.updateAndGet(-1.0F, 0.0F, 0.0F, 0.016F), "and a fall");
	}

	@Test
	void aFrameThatMeasuresNothingHoldsTheAccumulatorWhereItStands() {
		// Zero times an infinite decay constant is NaN, and a frame clock that rounds to the
		// millisecond hands out zeroes: the blend is NaN there, and the update must not fold it in.
		assertTrue(Float.isNaN(Smoothed.blend(0.0F, 0.0F)));
		assertEquals(0.0F, Smoothed.blend(10.0F, 0.0F));

		for (float dt : new float[] { 0.0F, -0.0F, -0.016F }) {
			Smoothed smoothed = new Smoothed();
			smoothed.updateAndGet(3.0F, 0.0F, 0.0F, 0.016F);

			assertEquals(3.0F, smoothed.updateAndGet(8.0F, 0.0F, 0.0F, dt), "dt " + dt);
			assertEquals(3.0F, smoothed.updateAndGet(-8.0F, 10.0F, 10.0F, dt), "dt " + dt);
			assertEquals(8.0F, smoothed.updateAndGet(8.0F, 0.0F, 0.0F, 0.001F), "and it carries on after");
		}
	}

	@Test
	void resetMakesTheNextValueTheFirstAgain() {
		Smoothed smoothed = new Smoothed();
		smoothed.updateAndGet(1.0F, 10.0F, 10.0F, 0.016F);
		smoothed.updateAndGet(0.0F, 10.0F, 10.0F, 0.016F);

		smoothed.reset();

		assertEquals(42.0F, smoothed.updateAndGet(42.0F, 10.0F, 10.0F, 0.016F), "outright, not climbing out of zero");
		assertTrue(smoothed.updateAndGet(0.0F, 10.0F, 10.0F, 0.016F) > 0.0F, "and smoothed from then on");
	}

	@Test
	void anAccumulatorIsNeverSharedWithAnother() {
		Smoothed a = new Smoothed();
		Smoothed b = new Smoothed();
		a.updateAndGet(1.0F, 10.0F, 10.0F, 0.016F);
		b.updateAndGet(100.0F, 10.0F, 10.0F, 0.016F);

		assertEquals(1.0F, a.updateAndGet(1.0F, 10.0F, 10.0F, 0.016F));
		assertEquals(100.0F, b.updateAndGet(100.0F, 10.0F, 10.0F, 0.016F));
	}

	@Test
	void aNaNValueStaysInTheAccumulatorUntilItIsReset() {
		// The same as the reference does, and worth knowing: nothing in the update recovers from it.
		Smoothed smoothed = new Smoothed();
		smoothed.updateAndGet(1.0F, 10.0F, 10.0F, 0.016F);
		smoothed.updateAndGet(Float.NaN, 10.0F, 10.0F, 0.016F);

		assertTrue(Float.isNaN(smoothed.updateAndGet(1.0F, 10.0F, 10.0F, 0.016F)));

		smoothed.reset();
		assertEquals(1.0F, smoothed.updateAndGet(1.0F, 10.0F, 10.0F, 0.016F));
	}

	@Test
	void aNegativeHalfLifeGivesANegativeFactorAndMovesAwayFromTheValue() {
		// Nothing clamps a pack's directive. Pinned as it is: the accumulator runs away from the
		// value it is fed instead of towards it.
		assertTrue(Smoothed.blend(-10.0F, 1.0F) < 0.0F);

		Smoothed smoothed = new Smoothed();
		smoothed.updateAndGet(0.0F, -10.0F, -10.0F, 0.1F);

		assertTrue(smoothed.updateAndGet(1.0F, -10.0F, -10.0F, 0.1F) < 0.0F);
	}
}
