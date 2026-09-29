package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/**
 * Holds the names a colour target answers to: {@code colortexN}, the eight from before the format
 * was numbered, and the {@code colorimgN} a compute stores through.
 */
class TargetNameTest {

	private static final List<String> LEGACY = List.of("gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2",
			"gaux3", "gaux4");

	@Test
	void colortexAnswersWithItsNumberUpToThirtyOne() {
		assertEquals(OptionalInt.of(0), TargetName.index("colortex0"));
		assertEquals(OptionalInt.of(7), TargetName.index("colortex7"));
		assertEquals(OptionalInt.of(10), TargetName.index("colortex10"));
		assertEquals(OptionalInt.of(31), TargetName.index("colortex31"));
		assertEquals(32, TargetName.MAX_TARGETS);
	}

	@Test
	void theEightOlderNamesAnswerWithTheirPosition() {
		for (int index = 0; index < LEGACY.size(); index++) {
			assertEquals(OptionalInt.of(index), TargetName.index(LEGACY.get(index)), LEGACY.get(index));
		}
	}

	@Test
	void whatIsNeitherIsNoTarget() {
		for (String name : List.of("", "colortex", "colortex32", "colortex99", "colortex100", "colortex007",
				"colortexA", "colortex-1", "colortex 1", "colortex1 ", "Colortex1", "COLORTEX1", "gaux5", "gaux0",
				"depthtex0", "gdepthtex", "shadowcolor0", "colorimg1", "colortex1x", "gcolor0", "GCOLOR")) {
			assertTrue(TargetName.index(name).isEmpty(), "'" + name + "'");
		}
	}

	@Test
	void aLeadingZeroIsTolerated() {
		assertEquals(OptionalInt.of(7), TargetName.index("colortex07"));
		assertEquals(OptionalInt.of(0), TargetName.index("colortex00"));
	}

	/** Digits of other scripts pass {@code Character.isDigit} and {@code Integer.parseInt} alike. */
	@Test
	void knownBug_aDigitOfAnotherScriptIsATarget() {
		assertEquals(OptionalInt.of(3), TargetName.index("colortex\u0663"));
		assertEquals(OptionalInt.of(3), TargetName.imageIndex("colorimg\u0663"));
	}

	@Test
	void colorimgAnswersLikeColortexAndTheOlderNamesHaveNoImageSpelling() {
		assertEquals(OptionalInt.of(0), TargetName.imageIndex("colorimg0"));
		assertEquals(OptionalInt.of(12), TargetName.imageIndex("colorimg12"));
		assertEquals(OptionalInt.of(31), TargetName.imageIndex("colorimg31"));

		for (String name : List.of("", "colorimg", "colorimg32", "colorimg100", "colorimgA", "colortex3",
				"gaux4img", "gcolorimg", "gcolor", "Colorimg1")) {
			assertTrue(TargetName.imageIndex(name).isEmpty(), "'" + name + "'");
		}
	}

	@Test
	void aTargetIsNamedByNumberOrByItsOlderNameAndNothingPastTheEighth() {
		assertEquals("colortex0", TargetName.canonical(0));
		assertEquals("colortex7", TargetName.canonical(7));
		assertEquals("colortex31", TargetName.canonical(31));

		for (int index = 0; index < LEGACY.size(); index++) {
			assertEquals(Optional.of(LEGACY.get(index)), TargetName.legacyAlias(index));
		}
		assertTrue(TargetName.legacyAlias(8).isEmpty());
		assertTrue(TargetName.legacyAlias(31).isEmpty());
		assertTrue(TargetName.legacyAlias(-1).isEmpty());
	}

	@Test
	void everyCanonicalNameAnswersWithItsNumber() {
		for (int index = 0; index < TargetName.MAX_TARGETS; index++) {
			assertEquals(OptionalInt.of(index), TargetName.index(TargetName.canonical(index)));
		}
	}

	@Test
	void aDirectiveNameSplitsIntoItsTargetAndItsSuffix() {
		assertEquals(Optional.of(new TargetName.Suffixed(3, "Format")), TargetName.split("colortex3Format"));
		assertEquals(Optional.of(new TargetName.Suffixed(12, "Clear")), TargetName.split("colortex12Clear"));
		assertEquals(Optional.of(new TargetName.Suffixed(0, "MipmapEnabled")), TargetName.split("colortex0MipmapEnabled"));
		assertEquals(Optional.of(new TargetName.Suffixed(5, "Format")), TargetName.split("gaux2Format"));
		assertEquals(Optional.of(new TargetName.Suffixed(0, "Clear")), TargetName.split("gcolorClear"));
		assertEquals(Optional.of(new TargetName.Suffixed(1, "Format")), TargetName.split("gdepthFormat"));
		assertEquals(Optional.of(new TargetName.Suffixed(3, "Mipmap")), TargetName.split("compositeMipmap"));
	}

	@Test
	void aDirectiveNameWithNoTargetOrNoSuffixDoesNotSplit() {
		for (String name : List.of("", "colortex3", "colortex", "colortexFormat", "colortex99Format",
				"colortex123Format", "colortex32Format", "gaux1", "gcolor", "gaux5Format", "shadowcolor0Format",
				"Colortex3Format", "depthtex0Format")) {
			assertTrue(TargetName.split(name).isEmpty(), "'" + name + "'");
		}
	}

	@Test
	void aBareProgramNameDropsItsDirectory() {
		assertEquals("composite1", TargetName.bareName("world0/composite1"));
		assertEquals("composite1", TargetName.bareName("composite1"));
		assertEquals("c", TargetName.bareName("a/b/c"));
		assertEquals("x", TargetName.bareName("/x"));
		assertEquals("", TargetName.bareName("dir/"));
		assertEquals("", TargetName.bareName(""));
		assertEquals("gbuffers_terrain", TargetName.bareName("world-1/gbuffers_terrain"));
	}
}
