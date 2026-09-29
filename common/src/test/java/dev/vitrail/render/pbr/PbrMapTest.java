package dev.vitrail.render.pbr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntUnaryOperator;

import org.joml.Vector4fc;
import org.junit.jupiter.api.Test;

/**
 * Holds the mip reduction of the two material maps to what a pack reads back out of them, without a
 * device: a texel is an ARGB int and a level is four of them averaged.
 * <p>
 * The expected values are worked by hand from the labPBR channel table (smoothness, metal or
 * reflectance, porosity or subsurface, emission) and from a second reading of the rule written here
 * as groups in a map, which shares no line with {@link PbrMap}'s counting loops. Colours are built and
 * taken apart with shifts, not with the game's {@code ARGB}, so a channel that lands in the wrong
 * byte is caught by a number and not by two functions agreeing with each other.
 */
class PbrMapTest {

	private static int argb(int alpha, int red, int green, int blue) {
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static int a(int color) {
		return color >>> 24;
	}

	private static int r(int color) {
		return (color >> 16) & 0xFF;
	}

	private static int g(int color) {
		return (color >> 8) & 0xFF;
	}

	private static int b(int color) {
		return color & 0xFF;
	}

	// The second reading of the labPBR rule. Metal ids are discrete, so every id from 230 up is a
	// class of its own and everything below is one class; the other two are a single threshold.
	private static int metalClass(int value) {
		return value < 230 ? -1 : value;
	}

	private static int porosityClass(int value) {
		return value < 65 ? 0 : 1;
	}

	private static int emissionClass(int value) {
		return value < 255 ? 0 : 1;
	}

	/** Group the four by class in order of first appearance; the biggest group wins, the first on a tie. */
	private static int referenceByClass(IntUnaryOperator classOf, int... values) {
		Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
		for (int value : values) {
			groups.computeIfAbsent(classOf.applyAsInt(value), key -> new ArrayList<>()).add(value);
		}

		List<Integer> winner = null;
		for (List<Integer> group : groups.values()) {
			if (winner == null || group.size() > winner.size()) {
				winner = group;
			}
		}

		long sum = 0;
		for (int value : winner) {
			sum += value;
		}

		return (int) Math.floorDiv(sum, winner.size());
	}

	/**
	 * The counting loops of {@link PbrMap#blend} as they stand in the tree today, with their two
	 * scratch arrays. Kept as a third reading, so that a rewrite of the counting cannot move a
	 * single bit and still pass.
	 */
	private static int legacyByClass(IntUnaryOperator classOf, int first, int second, int third,
			int fourth) {
		int[] values = {first, second, third, fourth};
		int[] classes = {classOf.applyAsInt(first), classOf.applyAsInt(second),
				classOf.applyAsInt(third), classOf.applyAsInt(fourth)};

		int winner = classes[0];
		int best = 0;
		for (int candidate = 0; candidate < classes.length; candidate++) {
			int count = 0;
			for (int against : classes) {
				if (against == classes[candidate]) {
					count++;
				}
			}

			if (count > best) {
				best = count;
				winner = classes[candidate];
			}
		}

		int sum = 0;
		int taken = 0;
		for (int index = 0; index < values.length; index++) {
			if (classes[index] == winner) {
				sum += values[index];
				taken++;
			}
		}

		return sum / taken;
	}

	private static int referenceMean(int... values) {
		long sum = 0;
		for (int value : values) {
			sum += value;
		}

		return (int) Math.floorDiv(sum, values.length);
	}

	private static int referenceLabPbr(int first, int second, int third, int fourth) {
		int[] texels = {first, second, third, fourth};
		int[] alpha = new int[4];
		int[] red = new int[4];
		int[] green = new int[4];
		int[] blue = new int[4];
		for (int i = 0; i < 4; i++) {
			alpha[i] = a(texels[i]);
			red[i] = r(texels[i]);
			green[i] = g(texels[i]);
			blue[i] = b(texels[i]);
		}

		return argb(referenceByClass(PbrMapTest::emissionClass, alpha), referenceMean(red),
				referenceByClass(PbrMapTest::metalClass, green),
				referenceByClass(PbrMapTest::porosityClass, blue));
	}

	private static int referencePlain(int first, int second, int third, int fourth) {
		int[] texels = {first, second, third, fourth};
		int[] alpha = new int[4];
		int[] red = new int[4];
		int[] green = new int[4];
		int[] blue = new int[4];
		for (int i = 0; i < 4; i++) {
			alpha[i] = a(texels[i]);
			red[i] = r(texels[i]);
			green[i] = g(texels[i]);
			blue[i] = b(texels[i]);
		}

		return argb(referenceMean(alpha), referenceMean(red), referenceMean(green), referenceMean(blue));
	}

	// -- names and constants ------------------------------------------------------------------

	@Test
	void namesAreTheTwoWordsAPackDeclares() {
		assertEquals("normals", PbrMap.NORMALS.sampler());
		assertEquals("specular", PbrMap.SPECULAR.sampler());
		assertEquals("_n", PbrMap.NORMALS.suffix());
		assertEquals("_s", PbrMap.SPECULAR.suffix());
		assertEquals(2, PbrMap.values().length, "a third map would need its own blend rule");
	}

	@Test
	void namedFindsAMapOnTheExactNameAndOnlyThat() {
		assertSame(PbrMap.NORMALS, PbrMap.named("normals"));
		assertSame(PbrMap.SPECULAR, PbrMap.named("specular"));

		assertNull(PbrMap.named(null));
		assertNull(PbrMap.named(""));
		assertNull(PbrMap.named("Normals"));
		assertNull(PbrMap.named("SPECULAR"));
		assertNull(PbrMap.named("normals "));
		assertNull(PbrMap.named("_n"));
		assertNull(PbrMap.named("NORMALS"), "the constant's own name is not the sampler name");
		assertNull(PbrMap.named("gcolor"));
	}

	@Test
	void theFlatValueOfTheNormalsIsThePaleBlueAndOfTheSpecularNothing() {
		Vector4fc normals = PbrMap.NORMALS.missing();
		assertEquals(127 / 255.0F, normals.x());
		assertEquals(127 / 255.0F, normals.y());
		assertEquals(1.0F, normals.z());
		assertEquals(1.0F, normals.w());

		Vector4fc specular = PbrMap.SPECULAR.missing();
		assertEquals(0.0F, specular.x());
		assertEquals(0.0F, specular.y());
		assertEquals(0.0F, specular.z());
		assertEquals(0.0F, specular.w());
	}

	@Test
	void theFlatValueIsANewObjectEveryCallSoNoCallerCanMoveIt() {
		assertNotSame(PbrMap.NORMALS.missing(), PbrMap.NORMALS.missing());
		assertEquals(PbrMap.NORMALS.missing(), PbrMap.NORMALS.missing());
	}

	@Test
	void onlyTheSpecularMapUnderLabPbrRefusesToBlendNeighbours() {
		assertTrue(PbrMap.NORMALS.interpolates(false));
		assertTrue(PbrMap.NORMALS.interpolates(true));
		assertTrue(PbrMap.SPECULAR.interpolates(false));
		assertFalse(PbrMap.SPECULAR.interpolates(true));
	}

	// -- the plain average --------------------------------------------------------------------

	@Test
	void plainMeanIsPerChannelAndFloorsTowardsZero() {
		int first = argb(255, 10, 20, 30);
		int second = argb(255, 20, 30, 40);
		int third = argb(255, 30, 40, 50);
		int fourth = argb(0, 40, 50, 61);

		// alpha 765 / 4 = 191.25, red 100 / 4 = 25, green 140 / 4 = 35, blue 181 / 4 = 45.25
		int expected = argb(191, 25, 35, 45);

		assertEquals(expected, PbrMap.NORMALS.blend(first, second, third, fourth, false));
		assertEquals(expected, PbrMap.SPECULAR.blend(first, second, third, fourth, false));
		assertEquals(expected, PbrMap.NORMALS.blend(first, second, third, fourth, true),
				"labPBR changes the specular map and nothing else");
	}

	@Test
	void plainMeanDoesNotGoThroughTheSrgbCurveThatTheGamesColourMeanUses() {
		// The game's meanLinear of black and white is about 188 in each colour channel; a vector
		// stored in red and green averages to the middle of the byte, 127.
		int black = argb(255, 0, 0, 0);
		int white = argb(255, 255, 255, 255);
		int mixed = PbrMap.NORMALS.blend(black, white, black, white, false);

		assertEquals(argb(255, 127, 127, 127), mixed);
	}

	@Test
	void plainMeanKeepsEachChannelInItsByteAtTheExtremes() {
		int full = argb(255, 255, 255, 255);
		int empty = argb(0, 0, 0, 0);

		assertEquals(full, PbrMap.NORMALS.blend(full, full, full, full, false));
		assertEquals(empty, PbrMap.NORMALS.blend(empty, empty, empty, empty, false));
		// 255 * 4 must not spill into the neighbour: no overflow of the sum into the next byte.
		assertEquals(argb(0, 255, 0, 255),
				PbrMap.NORMALS.blend(argb(0, 255, 0, 255), argb(0, 255, 0, 255), argb(0, 255, 0, 255),
						argb(0, 255, 0, 255), false));
		assertEquals(argb(255, 0, 0, 0),
				PbrMap.NORMALS.blend(argb(255, 0, 0, 0), argb(255, 0, 0, 0), argb(255, 0, 0, 0),
						argb(255, 0, 0, 0), false));
	}

	@Test
	void plainMeanSumsAlphaOf255FourTimesWithoutSignTrouble() {
		// The alpha byte is the top one: 4 * 255 does not fit a byte but the mean does, and the top
		// bit set must not make the int negative in a way that loses the channel.
		int result = PbrMap.NORMALS.blend(argb(255, 1, 2, 3), argb(255, 1, 2, 3), argb(255, 1, 2, 3),
				argb(255, 1, 2, 3), false);

		assertEquals(argb(255, 1, 2, 3), result);
		assertTrue(result < 0, "opaque ARGB is a negative int");
	}

	// -- labPBR: the specular map -------------------------------------------------------------

	@Test
	void labPbrSmoothnessInRedIsAlwaysAPlainMean() {
		int result = PbrMap.SPECULAR.blend(argb(0, 10, 0, 0), argb(0, 20, 0, 0), argb(0, 30, 0, 0),
				argb(0, 255, 0, 0), true);

		// 315 / 4 = 78.75
		assertEquals(78, r(result));
	}

	@Test
	void labPbrMetalIsAveragedInsideTheClassThatWinsTheQuad() {
		// three dielectrics and a metal: the dielectrics win and the metal id 230 is left out of it
		assertEquals(20, g(blendGreen(10, 20, 30, 230)));
		// two texels of metal 230 beat a dielectric and a different metal
		assertEquals(230, g(blendGreen(230, 231, 230, 20)));
		// two dielectrics beat two distinct metals
		assertEquals(20, g(blendGreen(230, 231, 20, 21)));
		// every metal id is a class of its own, so four different ids are four single-vote classes and
		// the first one keeps the quad
		assertEquals(230, g(blendGreen(230, 231, 232, 233)));
		assertEquals(255, g(blendGreen(255, 254, 253, 252)));
	}

	@Test
	void labPbrTiesGoToTheEarliestTexelOfTheFour() {
		// two against two, first texel a dielectric
		assertEquals(20, g(blendGreen(20, 230, 21, 230)));
		// two against two, first texel a metal
		assertEquals(230, g(blendGreen(230, 20, 230, 21)));
		// a tie between four singletons of dielectric is impossible, but three metals and a
		// dielectric with different ids is a three way tie of one against one against one against one
		assertEquals(20, g(blendGreen(20, 230, 231, 232)));
	}

	@Test
	void labPbrMetalThresholdIsBetween229And230() {
		assertEquals(229, g(blendGreen(229, 229, 229, 229)));
		// 229 is a dielectric so it averages with the other dielectrics
		assertEquals((229 + 100 + 100) / 3, g(blendGreen(229, 100, 100, 230)));
		// 230 is the first metal id and does not average with 229
		assertEquals(230, g(blendGreen(230, 230, 229, 100)));
	}

	@Test
	void labPbrPorosityAndSubsurfaceSplitAt65() {
		// 64 is the top porosity, 65 the bottom subsurface, so they never mix
		assertEquals((64 + 10) / 2, b(blendBlue(64, 65, 66, 10)));
		assertEquals((65 + 66) / 2, b(blendBlue(65, 66, 64, 10)));
		assertEquals(64, b(blendBlue(64, 64, 64, 64)));
		assertEquals(65, b(blendBlue(65, 65, 65, 65)));
		// a one against three: the three subsurface texels win over a porosity one
		assertEquals((100 + 200 + 255) / 3, b(blendBlue(0, 100, 200, 255)));
	}

	@Test
	void labPbrEmissionIsOnlyTheTopValueAndKeepsItApart() {
		// 255 is "no emission" in the alpha of the specular map, 0 to 254 the emission strength
		assertEquals(255, a(blendAlpha(255, 255, 0, 100)));
		assertEquals(255, a(blendAlpha(254, 255, 255, 255)));
		assertEquals((254 + 254 + 0) / 3, a(blendAlpha(254, 254, 255, 0)));
		assertEquals((10 + 20 + 30 + 40) / 4, a(blendAlpha(10, 20, 30, 40)));
	}

	@Test
	void labPbrPutsEachRuleOnItsOwnChannelAtOnce() {
		int first = argb(255, 200, 10, 64);
		int second = argb(255, 100, 230, 65);
		int third = argb(0, 50, 230, 66);
		int fourth = argb(0, 30, 20, 10);

		// alpha 255,255,0,0 tie -> first class (255): 255
		// red (200+100+50+30)/4 = 95
		// green 10,230,230,20: classes dielectric, 230, 230, dielectric -> tie, first is dielectric: 15
		// blue 64,65,66,10: classes 0,1,1,0 -> tie, first is 0: (64+10)/2 = 37
		assertEquals(argb(255, 95, 15, 37), PbrMap.SPECULAR.blend(first, second, third, fourth, true));
	}

	@Test
	void identicalTexelsComeBackUnchangedForEveryValueOfEveryChannel() {
		for (PbrMap map : PbrMap.values()) {
			for (boolean labPbr : new boolean[] {false, true}) {
				for (int value = 0; value < 256; value++) {
					int texel = argb(value, 255 - value, value, (value * 7) & 0xFF);
					assertEquals(texel, map.blend(texel, texel, texel, texel, labPbr),
							map + " labPbr=" + labPbr + " value=" + value);
				}
			}
		}
	}

	@Test
	void theResultDoesNotDependOnWhichTexelIsWhereExceptOnATie() {
		// Order matters only through the tie rule, so a permutation that keeps a strict majority in
		// the same class gives the same texel.
		int[] majority = {argb(255, 1, 10, 10), argb(255, 2, 11, 11), argb(255, 3, 12, 12),
				argb(255, 4, 230, 200)};
		int expected = PbrMap.SPECULAR.blend(majority[0], majority[1], majority[2], majority[3], true);
		int[][] orders = {{3, 0, 1, 2}, {0, 3, 1, 2}, {1, 2, 3, 0}, {2, 1, 0, 3}};
		for (int[] order : orders) {
			assertEquals(expected, PbrMap.SPECULAR.blend(majority[order[0]], majority[order[1]],
					majority[order[2]], majority[order[3]], true));
		}
	}

	// -- a second reading, over a fixed set and a seeded random one ---------------------------

	@Test
	void agreesWithTheSecondReadingOnTheBoundaryValues() {
		int[] edges = {0, 1, 63, 64, 65, 66, 127, 128, 228, 229, 230, 231, 232, 253, 254, 255};
		Random random = new Random(0x50B12L);
		for (int i = 0; i < 20_000; i++) {
			int[] texels = new int[4];
			for (int t = 0; t < 4; t++) {
				texels[t] = argb(edges[random.nextInt(edges.length)], edges[random.nextInt(edges.length)],
						edges[random.nextInt(edges.length)], edges[random.nextInt(edges.length)]);
			}

			assertEquals(referenceLabPbr(texels[0], texels[1], texels[2], texels[3]),
					PbrMap.SPECULAR.blend(texels[0], texels[1], texels[2], texels[3], true),
					"labPBR case " + i);
			assertEquals(referencePlain(texels[0], texels[1], texels[2], texels[3]),
					PbrMap.SPECULAR.blend(texels[0], texels[1], texels[2], texels[3], false),
					"plain case " + i);
			assertEquals(referencePlain(texels[0], texels[1], texels[2], texels[3]),
					PbrMap.NORMALS.blend(texels[0], texels[1], texels[2], texels[3], true),
					"normals case " + i);
		}
	}

	@Test
	void agreesWithTheSecondReadingOnUniformRandomTexels() {
		Random random = new Random(20260928L);
		for (int i = 0; i < 20_000; i++) {
			int[] texels = new int[4];
			for (int t = 0; t < 4; t++) {
				texels[t] = random.nextInt();
			}

			assertEquals(referenceLabPbr(texels[0], texels[1], texels[2], texels[3]),
					PbrMap.SPECULAR.blend(texels[0], texels[1], texels[2], texels[3], true));
			assertEquals(referencePlain(texels[0], texels[1], texels[2], texels[3]),
					PbrMap.NORMALS.blend(texels[0], texels[1], texels[2], texels[3], false));
		}
	}

	@Test
	void agreesWithBothReadingsOnEveryQuadOfBoundaryValuesInEachChannel() {
		int[] values = {0, 1, 63, 64, 65, 128, 229, 230, 231, 253, 254, 255};
		int count = 0;
		for (int p : values) {
			for (int q : values) {
				for (int s : values) {
					for (int t : values) {
						int alpha = PbrMap.SPECULAR.blend(argb(p, 0, 0, 0), argb(q, 0, 0, 0),
								argb(s, 0, 0, 0), argb(t, 0, 0, 0), true);
						int green = PbrMap.SPECULAR.blend(argb(0, 0, p, 0), argb(0, 0, q, 0),
								argb(0, 0, s, 0), argb(0, 0, t, 0), true);
						int blue = PbrMap.SPECULAR.blend(argb(0, 0, 0, p), argb(0, 0, 0, q),
								argb(0, 0, 0, s), argb(0, 0, 0, t), true);

						String at = p + "," + q + "," + s + "," + t;
						assertEquals(legacyByClass(PbrMapTest::emissionClass, p, q, s, t), a(alpha), at);
						assertEquals(referenceByClass(PbrMapTest::emissionClass, p, q, s, t), a(alpha), at);
						assertEquals(legacyByClass(PbrMapTest::metalClass, p, q, s, t), g(green), at);
						assertEquals(referenceByClass(PbrMapTest::metalClass, p, q, s, t), g(green), at);
						assertEquals(legacyByClass(PbrMapTest::porosityClass, p, q, s, t), b(blue), at);
						assertEquals(referenceByClass(PbrMapTest::porosityClass, p, q, s, t), b(blue), at);
						count++;
					}
				}
			}
		}

		assertEquals(12 * 12 * 12 * 12, count);
	}

	@Test
	void agreesWithTheLegacyCountingOnSeededRandomTexels() {
		Random random = new Random(0x7E57B10CL);
		IntUnaryOperator metal = value -> value < 230 ? 0 : value - 229;
		IntUnaryOperator porosity = value -> value < 65 ? 0 : 1;
		IntUnaryOperator emission = value -> value < 255 ? 0 : 1;
		for (int i = 0; i < 50_000; i++) {
			int t0 = random.nextInt();
			int t1 = random.nextInt();
			int t2 = random.nextInt();
			int t3 = random.nextInt();
			// Half of the cases squeezed into few distinct values so that ties and majorities are common.
			if ((i & 1) == 0) {
				t0 = squeeze(t0);
				t1 = squeeze(t1);
				t2 = squeeze(t2);
				t3 = squeeze(t3);
			}

			int expected = argb(legacyByClass(emission, a(t0), a(t1), a(t2), a(t3)),
					legacyMean(r(t0), r(t1), r(t2), r(t3)),
					legacyByClass(metal, g(t0), g(t1), g(t2), g(t3)),
					legacyByClass(porosity, b(t0), b(t1), b(t2), b(t3)));

			assertEquals(expected, PbrMap.SPECULAR.blend(t0, t1, t2, t3, true), "case " + i);
		}
	}

	private static int legacyMean(int first, int second, int third, int fourth) {
		return (first + second + third + fourth) / 4;
	}

	/** Each channel to one of a handful of values around the labPBR boundaries. */
	private static int squeeze(int texel) {
		int[] pool = {0, 64, 65, 229, 230, 231, 255};
		return argb(pool[a(texel) % pool.length], pool[r(texel) % pool.length],
				pool[g(texel) % pool.length], pool[b(texel) % pool.length]);
	}

	// -- helpers ------------------------------------------------------------------------------

	private static int blendGreen(int first, int second, int third, int fourth) {
		return PbrMap.SPECULAR.blend(argb(0, 0, first, 0), argb(0, 0, second, 0), argb(0, 0, third, 0),
				argb(0, 0, fourth, 0), true);
	}

	private static int blendBlue(int first, int second, int third, int fourth) {
		return PbrMap.SPECULAR.blend(argb(0, 0, 0, first), argb(0, 0, 0, second), argb(0, 0, 0, third),
				argb(0, 0, 0, fourth), true);
	}

	private static int blendAlpha(int first, int second, int third, int fourth) {
		return PbrMap.SPECULAR.blend(argb(first, 0, 0, 0), argb(second, 0, 0, 0), argb(third, 0, 0, 0),
				argb(fourth, 0, 0, 0), true);
	}
}
