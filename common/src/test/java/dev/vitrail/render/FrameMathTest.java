package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the frame clock, the celestial angle, the eye table and the darkness term to Iris's numbers.
 * <p>
 * Each is a hand-worked table first. Where the arithmetic was re-cut around parameters, the inline
 * form it replaced is kept here as a reference and compared bit for bit on seeded random inputs,
 * NaN and infinities included: a pack reads these values, so a changed rounding is a changed picture.
 */
class FrameMathTest {

	@AfterEach
	void putTheStaticsBack() {
		ShadowAmortisation.forget();
	}

	// ---- the clock ----

	@Test
	void aFrameTimeIsAWholeNumberOfMillisecondsOverAThousand() {
		assertEquals(0.0F, FrameMath.frameTime(0L));
		assertEquals(0.0F, FrameMath.frameTime(999_999L), "under a millisecond is nothing");
		assertEquals(0.001F, FrameMath.frameTime(1_000_000L));
		assertEquals(0.001F, FrameMath.frameTime(1_999_999L), "cut, not rounded");
		assertEquals(0.016F, FrameMath.frameTime(16_666_666L), "sixty frames a second");
		assertEquals(1.0F, FrameMath.frameTime(1_000_000_000L));
		assertEquals(-0.001F, FrameMath.frameTime(-1_500_000L), "a clock stepping back cuts towards nought");
	}

	@Test
	void theFrameTimeIsTheCutMillisecondsInDoubleWithinOneFloatStep() {
		Random random = new Random(11L);
		for (int i = 0; i < 20_000; i++) {
			long elapsed = (long) (random.nextDouble() * Math.pow(10.0, random.nextInt(12)));
			double exact = Math.floor(elapsed / 1.0e6) / 1000.0;
			float got = FrameMath.frameTime(elapsed);
			assertEquals(exact, got, Math.ulp((float) exact), "elapsed " + elapsed + " ns");
		}
	}

	@Test
	void theCounterAddsAFrameTimeAndFallsToNoughtAtAnHourNotToTheExcess() {
		assertEquals(0.016F, FrameMath.counterAfter(0.0F, 0.016F));
		assertEquals(3599.0F, FrameMath.counterAfter(3598.5F, 0.5F));

		assertEquals(0.0F, FrameMath.counterAfter(3599.0F, 1.0F), "exactly an hour");
		assertEquals(0.0F, FrameMath.counterAfter(3599.5F, 2.0F), "and not the half hour left over");
		assertEquals(3599.75F, FrameMath.counterAfter(3599.5F, 0.25F), "just under");
	}

	/**
	 * The signed gap, in seconds, between the float counter and the exact sum of the same frame times
	 * since the last wrap, at its largest over an hour and a fifth of frames of the given length.
	 */
	private static double worstDrift(int frameMillis) {
		float step = FrameMath.frameTime(frameMillis * 1_000_000L);
		float counter = 0.0F;
		double exact = 0.0;
		double worst = 0.0;
		for (long frame = 0; frame < 3600L * 1200L / frameMillis; frame++) {
			float next = FrameMath.counterAfter(counter, step);
			if (next < counter) {
				exact = 0.0;
			} else {
				exact += step;
			}

			counter = next;
			if (Math.abs(counter - exact) > Math.abs(worst)) {
				worst = counter - exact;
			}
		}

		return worst;
	}

	@Test
	void knownBug_theFloatCounterRunsAwayFromTrueTimeByTheFloatStepAtTheRunningValue() {
		// Every addition rounds to the float step at the running value, which is a quarter of a
		// millisecond past 2048 seconds, and a frame time is rarely a whole number of those. The rounding
		// has a sign per frame rate and so does not average out: sixty frames a second finish the hour
		// ten seconds ahead, and two hundred and fifty finish it twenty seven seconds behind. Iris
		// accumulates the same way, which is why it is reproduced, so this is characterised and not
		// corrected. The figures are exact: the arithmetic is deterministic.
		assertEquals(9.971, worstDrift(16), 0.001, "16 ms frames, the counter ahead of time (seconds)");
		assertEquals(-26.684, worstDrift(4), 0.001, "4 ms frames, the counter behind time (seconds)");
		assertEquals(12.446, worstDrift(7), 0.001, "7 ms frames");
		assertEquals(3.201, worstDrift(10), 0.001, "10 ms frames, the nearest to exact of these");
	}

	@Test
	void theFrameCounterWrapsAtSevenHundredTwentyThousandSevenHundredTwenty() {
		assertEquals(1, FrameMath.nextFrame(0));
		assertEquals(720_719, FrameMath.nextFrame(720_718));
		assertEquals(0, FrameMath.nextFrame(720_719));
		assertEquals(1, FrameMath.nextFrame(720_720));

		// The number is divisible by every count of frames a pack takes a modulus by up to sixteen.
		for (int n = 1; n <= 16; n++) {
			assertEquals(0, 720_720 % n, "divisible by " + n);
		}
	}

	@Test
	void theOldInlineClockAndTheHelpersAgreeBitForBitOnRandomInputs() {
		Random random = new Random(7L);
		for (int i = 0; i < 100_000; i++) {
			long elapsed = random.nextInt(4) == 0 ? random.nextLong() : (long) (random.nextDouble() * 5.0e10);
			float counter = random.nextInt(50) == 0 ? Float.NaN : random.nextFloat() * 4000.0F - 100.0F;
			int frame = random.nextInt(1_000_000);

			float oldFrameTime = (elapsed / 1000L) / 1000L / 1000.0F;
			float oldCounter = counter;
			oldCounter += oldFrameTime;
			if (oldCounter >= 3600.0F) {
				oldCounter = 0.0F;
			}

			int oldFrame = (frame + 1) % 720720;

			assertEquals(Float.floatToIntBits(oldFrameTime), Float.floatToIntBits(FrameMath.frameTime(elapsed)));
			assertEquals(Float.floatToIntBits(oldCounter),
					Float.floatToIntBits(FrameMath.counterAfter(counter, oldFrameTime)), "counter " + counter);
			assertEquals(oldFrame, FrameMath.nextFrame(frame));
		}
	}

	// ---- the celestial angle ----

	@Test
	void theSunAngleIsTheDegreesPlusNinetyWrappedByOneStep() {
		assertEquals(90.0F, FrameMath.sunAngle(0.0F), "noon");
		assertEquals(180.0F, FrameMath.sunAngle(90.0F));
		assertEquals(360.0F, FrameMath.sunAngle(270.0F), "exactly a turn is kept, not wrapped to nought");
		assertEquals(1.0F, FrameMath.sunAngle(271.0F));
		assertEquals(89.9F, FrameMath.sunAngle(359.9F), 1.0e-4F);
		assertEquals(0.0F, FrameMath.sunAngle(-90.0F));
		assertEquals(359.0F, FrameMath.sunAngle(-91.0F));
		assertEquals(180.0F, FrameMath.sunAngle(-270.0F));
	}

	@Test
	void theSunAngleWrapsOnceAndIsNotAModulus() {
		// One step, exactly as Iris does it: an angle two turns out stays out of range, and a shader
		// reading either side of the wrap sees the discontinuity in the same place.
		assertEquals(730.0F, FrameMath.sunAngle(1000.0F));
		assertEquals(-50.0F, FrameMath.sunAngle(-500.0F));
		assertTrue(Float.isNaN(FrameMath.sunAngle(Float.NaN)));
		assertEquals(Float.POSITIVE_INFINITY, FrameMath.sunAngle(Float.POSITIVE_INFINITY));
	}

	@Test
	void theOldInlineSunAngleAndTheHelperAgreeBitForBitOnRandomInputs() {
		Random random = new Random(3L);
		for (int i = 0; i < 100_000; i++) {
			float degrees = random.nextInt(100) == 0 ? Float.NaN : (random.nextFloat() - 0.5F) * 1500.0F;
			float angle = degrees + 90.0F;
			if (angle < 0.0F) {
				angle += 360.0F;
			} else if (angle > 360.0F) {
				angle -= 360.0F;
			}

			assertEquals(Float.floatToIntBits(angle), Float.floatToIntBits(FrameMath.sunAngle(degrees)),
					"degrees " + degrees);
		}
	}

	@Test
	void noonIsAShadowAngleOfAQuarterAndTheLightIsThenStraightUp() {
		// The chain a pack depends on end to end: the game's sun angle (nought at noon), plus ninety, over
		// three hundred and sixty, into the light's view. Sunset is the west and sunrise the east.
		float[] degrees = {0.0F, 90.0F, 270.0F};
		double[][] light = {{0.0, 1.0, 0.0}, {-1.0, 0.0, 0.0}, {1.0, 0.0, 0.0}};
		for (int i = 0; i < degrees.length; i++) {
			ViewMatrices view = new ViewMatrices();
			view.advance(new Matrix4f(), new Matrix4f(), new Matrix4f().perspective(1.2F, 1.7F, 0.05F, 192.0F),
					192.0F, 12);
			float shadowAngle = FrameMath.sunAngle(degrees[i]) / 360.0F;
			view.advanceShadow(shadowAngle, 0.0F, 0.0F, new Vector3d(), 160.0F, -100.0F, 100.0F, false, 0.0F,
					0.0F, true);

			Matrix4f inverse = new Matrix4f(view.drawnShadowModelViewInverse());
			Vector4f up = inverse.transform(new Vector4f(0.0F, 0.0F, 1.0F, 0.0F));
			assertEquals(light[i][0], up.x, 1.0e-6, "sun angle " + degrees[i] + " x");
			assertEquals(light[i][1], up.y, 1.0e-6, "sun angle " + degrees[i] + " y");
			assertEquals(light[i][2], up.z, 1.0e-6, "sun angle " + degrees[i] + " z");
		}
	}

	// ---- what the eye is in ----

	@Test
	void theEyeTableIsIrisAndNotTheOrdinalsOfTheGamesEnum() {
		for (FogType type : FogType.values()) {
			int expected = switch (type) {
				case WATER -> 1;
				case LAVA -> 2;
				case POWDER_SNOW -> 3;
				default -> 0;
			};
			assertEquals(expected, FrameMath.eyeInWater(type, false), type.name());
		}
	}

	@Test
	void lavaDoesNotCountToASpectatorAndNothingElseChanges() {
		assertEquals(0, FrameMath.eyeInWater(FogType.LAVA, true));
		assertEquals(1, FrameMath.eyeInWater(FogType.WATER, true));
		assertEquals(3, FrameMath.eyeInWater(FogType.POWDER_SNOW, true));
		assertEquals(0, FrameMath.eyeInWater(FogType.NONE, true));
		assertEquals(0, FrameMath.eyeInWater(FogType.ATMOSPHERIC, false));
	}

	// ---- the darkness effect ----

	@Test
	void theDarknessTermIsNoughtWithNoEffectAndWithNoOption() {
		assertEquals(0.0F, FrameMath.darknessLight(0, 0.0F, 0.0F, 1.0F));
		assertEquals(0.0F, FrameMath.darknessLight(0, 0.0F, 1.0F, 0.0F));
	}

	@Test
	void theDarknessTermAtATickOfNoughtIsForty5HundredthsOfTheBlendTimesTheScaleSquared() {
		assertEquals(0.45F, FrameMath.darknessLight(0, 0.0F, 1.0F, 1.0F), 1.0e-6F);
		assertEquals(0.45F * 0.5F * 0.25F, FrameMath.darknessLight(0, 0.0F, 0.5F, 0.5F), 1.0e-6F);
	}

	@Test
	void theOptionScaleEntersTwiceSoDoublingItQuadruplesTheTerm() {
		// The trap the comment names: dropping either occurrence halves the result, which is a term that
		// is right on the default setting and wrong on every other.
		for (int tick = 0; tick < 20; tick++) {
			float once = FrameMath.darknessLight(tick, 0.25F, 0.75F, 0.5F);
			float twice = FrameMath.darknessLight(tick, 0.25F, 0.75F, 1.0F);
			assertTrue(once > 0.0F, "a positive term at tick " + tick);
			assertEquals(4.0F * once, twice, "tick " + tick);
		}
	}

	@Test
	void theDarknessTermPulsesOverFortyTicksAndIsNoughtWhereTheCosineIsNegative() {
		assertEquals(0.0F, FrameMath.darknessLight(40, 0.0F, 1.0F, 1.0F), "half way, the cosine is minus one");
		assertEquals(0.0F, FrameMath.darknessLight(30, 0.0F, 1.0F, 1.0F), "a cosine under nought");
		assertTrue(FrameMath.darknessLight(70, 0.0F, 1.0F, 1.0F) > 0.0F, "and back up for the next pulse");
		assertEquals(0.45F, FrameMath.darknessLight(80, 0.0F, 1.0F, 1.0F), 1.0e-6F, "a whole period is eighty ticks");
	}

	@Test
	void theDarknessTermFollowsTheCosineInDoubleWithinTheGamesLookupTable() {
		// Mth.cos is a 65,536 entry table, so it is not the cosine: the measured worst gap against
		// Math.cos over these inputs is 1.51e-4, and the tolerance is three times it.
		Random random = new Random(5L);
		double worst = 0.0;
		for (int i = 0; i < 50_000; i++) {
			int tick = random.nextInt(2000);
			float pt = random.nextFloat();
			float blend = random.nextFloat();
			float scale = random.nextFloat() * 2.0F;
			double reference = Math.max(0.0, Math.cos((tick - (double) pt) * Math.PI * 0.025) * 0.45
					* blend * scale) * scale;
			worst = Math.max(worst, Math.abs(reference - FrameMath.darknessLight(tick, pt, blend, scale)));
		}

		assertTrue(worst < 4.5e-4, "worst gap to Math.cos " + worst + ", measured on the code as it stands: "
				+ "1.51e-4");
		assertEquals(Mth.cos(1.0F), (float) Math.cos(1.0), 1.0e-4F);
		assertFalse(worst == 0.0, "the table is not exact, so the tolerance above is not vacuous");
	}
}
