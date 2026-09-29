package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.ProgramNames.ProgramName;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds what counts as the name of a program: the closed list of geometry names, the numbered
 * families whose unnumbered spelling is slot zero, the compute letter, and the order a frame runs
 * them in.
 */
class ProgramNamesTest {

	private static ProgramName parsed(String name) {
		return ProgramNames.parse(name).orElseThrow(() -> new AssertionError(name + " is not a program"));
	}

	@Test
	void aGeometryNameIsItsOwnFamilyWithNoSlot() {
		for (String name : List.of("gbuffers_terrain", "gbuffers_hand_water", "shadow", "shadow_solid", "dh_terrain",
				"dh_shadow", "final")) {
			ProgramName program = parsed(name);

			assertEquals(name, program.family());
			assertEquals(-1, program.slot());
			assertFalse(program.isCompute());
			assertEquals(name, program.baseName());
		}
	}

	@Test
	void theUnnumberedSpellingOfAFamilyIsSlotZero() {
		for (String family : List.of("composite", "deferred", "prepare", "shadowcomp", "setup", "begin")) {
			ProgramName program = parsed(family);

			assertEquals(family, program.family());
			assertEquals(0, program.slot());
			assertEquals(family, program.baseName(), "slot zero is written without a number");
		}
	}

	@Test
	void aNumberedFamilyTakesOneOrTwoDigitsUpToNinetyNine() {
		assertEquals(1, parsed("composite1").slot());
		assertEquals(9, parsed("deferred9").slot());
		assertEquals(10, parsed("prepare10").slot());
		assertEquals(99, parsed("composite99").slot());
		assertEquals("composite99", parsed("composite99").baseName());
		assertEquals(3, parsed("shadowcomp3").slot());
		assertEquals(2, parsed("setup2").slot());
		assertEquals(7, parsed("begin7").slot());
	}

	@Test
	void whatFallsOutsideTheGrammarIsNoProgram() {
		for (String name : List.of("", "bogus", "composite100", "composite1000", "composite-1", "composite+1",
				"composite 1", "composite1x", "Composite1", "COMPOSITE", "gbuffers", "gbuffers_bogus",
				"gbuffers_terrain1", "final1", "shadow1", "shadowcomp_", "shadowcompx", "dh_", "composite.1")) {
			assertTrue(ProgramNames.parse(name).isEmpty(), "'" + name + "'");
		}
	}

	@Test
	void aLeadingZeroIsAcceptedAndDroppedFromTheBaseName() {
		assertEquals(5, parsed("composite05").slot());
		assertEquals("composite5", parsed("composite05").baseName());
		assertEquals("composite", parsed("composite00").baseName());
	}

	/** {@code Character.isDigit} and {@code Integer.parseInt} both take digits of other scripts. */
	@Test
	void knownBug_aDigitOfAnotherScriptIsASlot() {
		assertEquals(3, parsed("composite\u0663").slot());
		assertEquals("composite3", parsed("composite\u0663").baseName());
	}

	@Test
	void aLetterAfterAnUnderscoreMakesAComputePass() {
		ProgramName numbered = parsed("composite21_a");
		assertEquals("composite", numbered.family());
		assertEquals(21, numbered.slot());
		assertEquals("a", numbered.computeSuffix());
		assertTrue(numbered.isCompute());
		assertEquals("composite21", numbered.baseName());

		ProgramName bare = parsed("composite_b");
		assertEquals(0, bare.slot());
		assertEquals("b", bare.computeSuffix());

		ProgramName shadow = parsed("shadow_c");
		assertEquals("shadow", shadow.family());
		assertEquals(-1, shadow.slot());
		assertEquals("c", shadow.computeSuffix());

		assertEquals("final", parsed("final_z").family());
		assertEquals("begin", parsed("begin2_a").family());
	}

	@Test
	void aComputeLetterIsRecognisedOnTheNumberedFamiliesAndTwoUnnumberedNamesOnly() {
		for (String name : List.of("gbuffers_terrain_a", "dh_terrain_a", "gbuffers_x", "composite1_A", "composite1_ab",
				"composite1_", "composite1__a", "_a", "composite1_1", "bogus_a", "dh_shadow_a")) {
			assertTrue(ProgramNames.parse(name).isEmpty(), name);
		}
	}

	@Test
	void aNamedProgramWinsOverAComputeReading() {
		// shadow_entities and shadow_block are geometry names; shadow_e and shadow_b are computes.
		assertFalse(parsed("shadow_entities").isCompute());
		assertFalse(parsed("shadow_block").isCompute());
		assertTrue(parsed("shadow_e").isCompute());
		assertTrue(parsed("shadow_b").isCompute());
	}

	@Test
	void aComputeHangsOffAPassTheChainDraws() {
		assertEquals(Optional.of("composite4"), ProgramNames.computeBase("composite4_a"));
		assertEquals(Optional.of("composite4"), ProgramNames.computeBase("composite4"));
		assertEquals(Optional.of("composite"), ProgramNames.computeBase("composite_c"));
		assertEquals(Optional.of("deferred"), ProgramNames.computeBase("deferred_b"));
		assertEquals(Optional.of("final"), ProgramNames.computeBase("final_a"));
		assertEquals(Optional.of("prepare"), ProgramNames.computeBase("prepare"));
		assertEquals(Optional.of("begin2"), ProgramNames.computeBase("begin2_c"));
	}

	@Test
	void aComputeHangsOffNothingWhereThePassIsNotDrawnByTheChain() {
		for (String name : List.of("setup_a", "setup1", "shadowcomp1_a", "shadowcomp", "shadow_a", "shadow",
				"gbuffers_terrain", "bogus", "dh_terrain")) {
			assertTrue(ProgramNames.computeBase(name).isEmpty(), name);
		}
	}

	@Test
	void theLetterOrdersTheComputesOfOnePass() {
		assertEquals("a", ProgramNames.computeLetter("composite4_a"));
		assertEquals("z", ProgramNames.computeLetter("final_z"));
		assertEquals("", ProgramNames.computeLetter("composite4"));
		assertEquals("", ProgramNames.computeLetter("bogus"));
		assertEquals("", ProgramNames.computeLetter("bogus_a"));
	}

	@Test
	void theFamilyOfAnythingIsItsFamilyOrItself() {
		assertEquals("composite", ProgramNames.familyOf("composite4"));
		assertEquals("composite", ProgramNames.familyOf("composite4_a"));
		assertEquals("gbuffers_terrain", ProgramNames.familyOf("gbuffers_terrain"));
		assertEquals("bogus", ProgramNames.familyOf("bogus"));
		assertEquals("", ProgramNames.familyOf(""));
	}

	// ---- the frame ---------------------------------------------------------------------------

	@Test
	void ranksTheFamiliesInTheOrderAFrameRunsThem() {
		assertEquals(0, ProgramNames.frameRank("begin"));
		assertEquals(1, ProgramNames.frameRank("shadowcomp"));
		assertEquals(2, ProgramNames.frameRank("prepare"));
		assertEquals(ProgramNames.GEOMETRY_RANK, ProgramNames.frameRank("gbuffers_water"));
		assertEquals(3, ProgramNames.GEOMETRY_RANK);
		assertEquals(4, ProgramNames.frameRank("deferred"));
		assertEquals(5, ProgramNames.frameRank("composite"));
		assertEquals(6, ProgramNames.frameRank("final"));
		// Setup and a name nobody knows land with the last of the frame.
		assertEquals(6, ProgramNames.frameRank("setup"));
		assertEquals(6, ProgramNames.frameRank("bogus"));
	}

	@Test
	void everyGeometryFamilyRanksWithTheWorldAndDrawsOverAQuadNever() {
		for (String family : List.of("gbuffers_terrain", "gbuffers_bogus", "gbuffers", "dh_terrain", "dh_water",
				"dh_shadow", "shadow", "shadow_solid", "shadow_")) {
			assertTrue(ProgramNames.geometry(family), family);
			assertEquals(ProgramNames.GEOMETRY_RANK, ProgramNames.frameRank(family), family);
		}

		for (String family : List.of("composite", "final", "shadowcomp", "shadowcomp1", "begin", "setup", "", "dh",
				"shadowX", "Gbuffers_terrain")) {
			assertFalse(ProgramNames.geometry(family), "'" + family + "'");
		}
	}

	@Test
	void directivesFoldInIrisOrderWhichIsNotTheFrameOrder() {
		assertEquals(0, ProgramNames.directiveRank("shadowcomp"));
		assertEquals(1, ProgramNames.directiveRank("begin"));
		assertEquals(2, ProgramNames.directiveRank("prepare"));
		assertEquals(3, ProgramNames.directiveRank("gbuffers_terrain"));
		assertEquals(4, ProgramNames.directiveRank("deferred"));
		assertEquals(5, ProgramNames.directiveRank("composite"));
		// final, setup and the shadow passes all take the default.
		assertEquals(3, ProgramNames.directiveRank("final"));
		assertEquals(3, ProgramNames.directiveRank("setup"));
		assertEquals(3, ProgramNames.directiveRank("shadow"));
	}

	@Test
	void shadowGeometryIsTheShadowFamilyAndDhShadowNotItsPrefix() {
		assertTrue(ProgramNames.shadowGeometry("shadow"));
		assertTrue(ProgramNames.shadowGeometry("shadow_solid"));
		assertTrue(ProgramNames.shadowGeometry("shadow_"));
		assertTrue(ProgramNames.shadowGeometry("dh_shadow"));
		assertFalse(ProgramNames.shadowGeometry("shadowcomp"));
		assertFalse(ProgramNames.shadowGeometry("shadowcomp2"));
		assertFalse(ProgramNames.shadowGeometry("dh_terrain"));
		assertFalse(ProgramNames.shadowGeometry("gbuffers_terrain"));
		assertTrue(ProgramNames.shadowComposite("shadowcomp"));
		assertFalse(ProgramNames.shadowComposite("shadowcomp1"), "takes a family, not a program name");
		assertFalse(ProgramNames.shadowComposite("shadow"));
	}

	@Test
	void sortsBareNamesByFamilyThenBySlotNotAlphabetically() {
		List<String> names = new ArrayList<>(List.of("final", "composite10", "composite2", "gbuffers_terrain",
				"deferred", "prepare1", "prepare", "begin", "shadowcomp", "bogus", "composite"));

		names.sort(ProgramNames.frameOrder());

		assertEquals(List.of("begin", "shadowcomp", "prepare", "prepare1", "gbuffers_terrain", "deferred",
				"composite", "composite2", "composite10", "final", "bogus"), names);
	}

	@Test
	void aNameThatParsesAsNoProgramSortsAfterEveryOneThatDoes() {
		List<String> names = new ArrayList<>(List.of("bogus", "final", "composite99"));

		names.sort(ProgramNames.frameOrder());

		assertEquals(List.of("composite99", "final", "bogus"), names);
	}

	@Test
	void beforeIsTheOrderAskedOfTwoNames() {
		assertTrue(ProgramNames.before("composite2", "composite10"));
		assertFalse(ProgramNames.before("composite10", "composite2"));
		assertTrue(ProgramNames.before("begin", "gbuffers_water"));
		assertTrue(ProgramNames.before("gbuffers_water", "deferred"));
		assertFalse(ProgramNames.before("final", "composite99"));
		assertFalse(ProgramNames.before("composite", "composite"));
	}

	@Test
	void theComputeLetterSaysNothingAboutWhereAPassFalls() {
		assertFalse(ProgramNames.before("prepare_a", "prepare"));
		assertFalse(ProgramNames.before("prepare", "prepare_a"));
		assertTrue(ProgramNames.before("prepare_z", "prepare1"));
		assertEquals(0, ProgramNames.frameOrder().compare("composite3_a", "composite3_b"));
	}

	@Test
	void twoSlotsOfDifferentFamiliesAreOrderedByFamilyFirst() {
		// composite1 is slot one and prepare9 is slot nine, and the family still decides.
		assertTrue(ProgramNames.before("prepare9", "composite1"));
		assertTrue(ProgramNames.before("deferred99", "composite"));
	}
}
