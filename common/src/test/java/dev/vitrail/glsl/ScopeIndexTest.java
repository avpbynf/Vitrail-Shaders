package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link ScopeIndex} to the walk down a list that it replaces: is there a range recorded under
 * this name that holds this line.
 * <p>
 * The reference is that walk as it was written, a loop over every range ever recorded that stops at
 * the first that matches. It is asked the same questions as the index, in the same order as the
 * ranges were added, with the two interleaved, over ranges that are disjoint the way the scopes of
 * functions are and over ranges that overlap the way nothing a compiler has read does.
 */
class ScopeIndexTest {

	private record Range(String name, int from, int to) {
	}

	/** The straightforward reading: every range in the order it was recorded, until one holds the line. */
	private static boolean walk(List<Range> ranges, String name, int line) {
		for (Range range : ranges) {
			if (range.name().equals(name) && line >= range.from() && line <= range.to()) {
				return true;
			}
		}

		return false;
	}

	@Test
	void answersLikeTheWalkOverRandomRangesAddedAndAskedInAnyOrder() {
		String[] names = {"image", "tex", "depth", "shadowtex0", "colortex0"};
		Random random = new Random(0x5C09E);
		for (int run = 0; run < 200; run++) {
			ScopeIndex index = new ScopeIndex();
			List<Range> reference = new ArrayList<>();
			for (int step = 0; step < 150; step++) {
				if (random.nextInt(3) < 2) {
					// Wide and narrow, before and after each other, backwards now and then, and below
					// nought: the index says nothing about how the ranges are laid out.
					int from = random.nextInt(120) - 10;
					int to = from + random.nextInt(60) - (random.nextInt(8) == 0 ? 30 : 0);
					String name = names[random.nextInt(names.length)];
					index.add(name, from, to);
					reference.add(new Range(name, from, to));
				} else {
					String name = random.nextInt(10) == 0 ? "never" : names[random.nextInt(names.length)];
					int line = random.nextInt(200) - 20;

					assertEquals(walk(reference, name, line), index.covers(name, line),
							"run " + run + ", step " + step + ", " + name + " at " + line);
				}
			}
		}
	}

	@Test
	void anAskAfterEveryAddSeesTheAddedRange() {
		ScopeIndex index = new ScopeIndex();
		assertFalse(index.covers("image", 5));

		index.add("image", 10, 20);
		assertFalse(index.covers("image", 9));
		assertTrue(index.covers("image", 10));
		assertTrue(index.covers("image", 20));
		assertFalse(index.covers("image", 21));

		// Begins before the first and ends after it: the earlier beginning must not hide the later end.
		index.add("image", 2, 40);
		assertTrue(index.covers("image", 30));
		assertTrue(index.covers("image", 2));
		assertFalse(index.covers("image", 1));
		assertFalse(index.covers("tex", 30));
	}

	@Test
	void manyScopesOfOneNameAreToldApartAtTheirEdges() {
		// One helper per five lines, every one of them taking its sampler as image.
		ScopeIndex index = new ScopeIndex();
		List<Range> reference = new ArrayList<>();
		for (int function = 0; function < 5000; function++) {
			int from = function * 5;
			int to = from + 3;
			index.add("image", from, to);
			reference.add(new Range("image", from, to));
		}

		for (int line = -2; line < 25_002; line += 1) {
			assertEquals(walk(reference, "image", line), index.covers("image", line), "line " + line);
		}
	}

	@Test
	void aRangeThatEndsBeforeItBeginsHoldsNothing() {
		ScopeIndex index = new ScopeIndex();
		index.add("image", 10, 5);

		for (int line = 0; line < 20; line++) {
			assertFalse(index.covers("image", line), "line " + line);
		}
	}
}
