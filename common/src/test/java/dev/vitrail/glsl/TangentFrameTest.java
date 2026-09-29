package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link TangentFrame} to the word it packs a frame into: twelve bits of octahedral x, eleven
 * of octahedral y, the sign of the handedness, then eight bits of the tangent's angle in the
 * normal's own plane.
 * <p>
 * The exact words are worked out by hand for the frames whose numbers are round, from the octahedral
 * fold and the diamond angle the class comment names. The rest is measured against the errors that
 * comment states: the normal within 0.09 degrees and the tangent within 0.90, the two being what the
 * off-game harness has measured over four hundred thousand frames.
 */
class TangentFrameTest {

	private static float dot(float[] a, float[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static float[] unit(Random random) {
		float[] v = new float[3];
		float length;
		do {
			v[0] = random.nextFloat() * 2.0F - 1.0F;
			v[1] = random.nextFloat() * 2.0F - 1.0F;
			v[2] = random.nextFloat() * 2.0F - 1.0F;
			length = (float) Math.sqrt(dot(v, v));
		} while (length < 0.1F || length > 1.0F);

		return new float[] {v[0] / length, v[1] / length, v[2] / length};
	}

	private static float degrees(float[] a, float[] b) {
		double cosine = Math.max(-1.0, Math.min(1.0, dot(a, b) / Math.sqrt(dot(a, a) * dot(b, b))));

		return (float) Math.toDegrees(Math.acos(cosine));
	}

	@Test
	void theNormalOfTheZAxisIsTheEmptyWord() {
		int word = TangentFrame.pack(new float[] {0, 0, 1, 1, 0, 0, 1});

		assertEquals(0, word & 0x7FFFFF);
		assertArrayEquals(new float[] {0, 0, 1}, TangentFrame.normal(word));
	}

	@Test
	void theNormalOfTheXAxisFillsTheTwelveBitsOfXAndNothingOfY() {
		// x is at its top, 2047, in the low twelve bits: 0x7FF.
		int word = TangentFrame.pack(new float[] {1, 0, 0, 0, 1, 0, 1});

		assertEquals(0x7FF, word & 0x7FFFFF);
		assertArrayEquals(new float[] {1, 0, 0}, TangentFrame.normal(word), 1e-6F);
	}

	@Test
	void theNormalOfTheNegativeZAxisIsTheCornerOfTheOctahedronAndComesBack() {
		// The lower half is folded over the diagonals: both components at their top, 2047 and 1023.
		int word = TangentFrame.pack(new float[] {0, 0, -1, 1, 0, 0, 1});

		assertEquals(0x7FF | (0x3FF << 12), word & 0x7FFFFF);
		assertArrayEquals(new float[] {0, 0, -1}, TangentFrame.normal(word), 1e-6F);
	}

	@Test
	void theFourTangentsOfTheZAxisTakeTheFourQuartersOfTheDiamond() {
		// For the normal +z the basis is (1,0,0) and (0,1,0): +x is the angle a half, +y three quarters,
		// -x the whole turn (which wraps to nothing) and -y one quarter, in 256ths.
		float[][] tangents = {{1, 0, 0}, {0, 1, 0}, {-1, 0, 0}, {0, -1, 0}};
		int[] bytes = {128, 192, 0, 64};

		for (int at = 0; at < 4; at++) {
			int word = TangentFrame.pack(new float[] {0, 0, 1, tangents[at][0], tangents[at][1], tangents[at][2], 1});

			assertEquals(bytes[at], word >>> 24, "tangent " + at);
			float[] back = TangentFrame.tangent(TangentFrame.normal(word), word);
			assertArrayEquals(new float[] {tangents[at][0], tangents[at][1], tangents[at][2], 1.0F}, back, 1e-6F);
		}
	}

	@Test
	void aTangentWithNothingInThePlaneTakesTheMiddleOfTheRange() {
		int word = TangentFrame.pack(new float[] {0, 0, 1, 0, 0, 0, 1});

		assertEquals(128, word >>> 24);
	}

	@Test
	void theHandednessIsTheBitBetweenTheNormalAndTheAngleAndZeroIsPositive() {
		for (float sign : new float[] {1.0F, 5.0F, 0.0F}) {
			int word = TangentFrame.pack(new float[] {0, 0, 1, 1, 0, 0, sign});
			assertEquals(1, word >> 23 & 1, "sign " + sign);
			assertEquals(1.0F, TangentFrame.tangent(TangentFrame.normal(word), word)[3]);
		}

		for (float sign : new float[] {-1.0F, -0.5F}) {
			int word = TangentFrame.pack(new float[] {0, 0, 1, 1, 0, 0, sign});
			assertEquals(0, word >> 23 & 1, "sign " + sign);
			assertEquals(-1.0F, TangentFrame.tangent(TangentFrame.normal(word), word)[3]);
		}
	}

	@Test
	void everyNormalComesBackUnitAndWithinNinetyThousandthsOfADegreeOfWhereItStarted() {
		Random random = new Random(0xF4A3);
		float worst = 0.0F;

		for (int round = 0; round < 20_000; round++) {
			float[] normal = unit(random);
			int word = TangentFrame.pack(new float[] {normal[0], normal[1], normal[2], 1, 0, 0, 1});
			float[] back = TangentFrame.normal(word);

			assertEquals(1.0F, dot(back, back), 1e-5F, "unit length");
			worst = Math.max(worst, degrees(normal, back));
		}

		assertTrue(worst <= 0.10F, "worst " + worst + " degrees");
	}

	@Test
	void everyTangentComesBackAtARightAngleToTheNormalAndWithinAboutADegree() {
		Random random = new Random(0x7A46);
		float worst = 0.0F;

		for (int round = 0; round < 20_000; round++) {
			float[] normal = unit(random);
			float[] any = unit(random);
			float along = dot(any, normal);
			float[] tangent = {any[0] - along * normal[0], any[1] - along * normal[1], any[2] - along * normal[2]};
			float length = (float) Math.sqrt(dot(tangent, tangent));
			if (length < 0.05F) {
				continue;
			}

			tangent[0] /= length;
			tangent[1] /= length;
			tangent[2] /= length;
			boolean right = random.nextBoolean();
			int word = TangentFrame.pack(new float[] {normal[0], normal[1], normal[2], tangent[0], tangent[1], tangent[2],
				right ? 1 : -1});

			float[] decoded = TangentFrame.normal(word);
			float[] back = TangentFrame.tangent(decoded, word);

			assertEquals(right ? 1.0F : -1.0F, back[3]);
			assertEquals(0.0F, dot(back, decoded), 1e-5F, "at a right angle to the normal it was decoded against");
			worst = Math.max(worst, degrees(tangent, back));
		}

		assertTrue(worst <= 1.05F, "worst " + worst + " degrees");
	}

	@Test
	void theNormalIntoAnArrayTheCallerBringsIsTheSameAnswerAndTheSameArray() {
		int word = TangentFrame.pack(new float[] {0.3F, -0.5F, 0.8F, 1, 0, 0, 1});
		float[] into = new float[3];

		assertTrue(TangentFrame.normal(word, into) == into);
		assertArrayEquals(TangentFrame.normal(word), into);
	}

	@Test
	void theTwoHalvesOfTheBargainAgreeOnWhereEveryFieldOfTheWordSits() {
		List<String> lines = TangentFrame.decode(true);
		String text = String.join("\n", lines);

		// Twelve bits of x from the bottom, eleven of y above them, the sign at bit 23, the angle above it.
		assertTrue(text.contains("bitfieldExtract(int(word), 0, 12)"), text);
		assertTrue(text.contains("bitfieldExtract(int(word), 12, 11)"), text);
		assertTrue(text.contains("float(word >> 24u)"), text);
		assertTrue(text.contains("((word >> 23u) & 1u) != 0u"), text);
		// The scale of each field is what the same field holds: 2047 in twelve bits, 1023 in eleven.
		Matcher scale = Pattern.compile("vec2\\(1\\.0 / (\\d+)\\.0, 1\\.0 / (\\d+)\\.0\\)").matcher(text);
		assertTrue(scale.find(), text);
		assertEquals("2047", scale.group(1));
		assertEquals("1023", scale.group(2));
		// And the Java reading of the same word: a word with only x at its top decodes to the x axis.
		assertArrayEquals(new float[] {1, 0, 0}, TangentFrame.normal(0x7FF), 1e-6F);
		assertArrayEquals(new float[] {0, 1, 0}, TangentFrame.normal(0x3FF << 12), 1e-6F);
	}

	@Test
	void theNormalFunctionAloneIsWhatAPackReadingOnlyTheNormalGets() {
		List<String> normal = TangentFrame.decode(false);
		List<String> both = TangentFrame.decode(true);

		assertEquals("vec3 " + TangentFrame.NORMAL_OF + "(uint word) {", normal.getFirst());
		assertEquals("}", normal.getLast());
		assertEquals(normal, both.subList(0, normal.size()));
		assertEquals("vec4 " + TangentFrame.TANGENT_OF + "(vec3 normal, uint word) {", both.get(normal.size()));
		assertEquals("}", both.getLast());
		assertFalse(String.join("\n", normal).contains(TangentFrame.TANGENT_OF));
		assertThrows(UnsupportedOperationException.class, () -> both.add("x"));
		assertThrows(UnsupportedOperationException.class, () -> normal.add("x"));
	}
}
