package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds the tree a missing program falls back through, taken from Iris: every chain has to end,
 * has to end on a root, and has to stay inside the names the tree knows.
 */
class ProgramFallbacksTest {

	private static final Set<String> ROOTS = Set.of("shadow", "gbuffers_basic", "dh_terrain", "dh_shadow", "final");

	@Test
	void theTreeHoldsThirtyNineNames() {
		// 7 shadow, 27 gbuffers, 4 Distant Horizons and the final pass.
		assertEquals(39, ProgramFallbacks.names().size());
	}

	@Test
	void everyChainStartsWithTheProgramAskedForAndEndsOnARoot() {
		for (String name : ProgramFallbacks.names()) {
			List<String> chain = ProgramFallbacks.chain(name);

			assertEquals(name, chain.get(0), name);
			assertTrue(ROOTS.contains(chain.get(chain.size() - 1)), name + " ends on " + chain);
			assertEquals(chain.size(), new HashSet<>(chain).size(), name + " walks a name twice: " + chain);
			assertTrue(ProgramFallbacks.names().containsAll(chain), name + " leaves the tree: " + chain);
			assertTrue(chain.size() <= 6, name + " is deeper than any real chain: " + chain);
		}
	}

	@Test
	void onlyTheFiveRootsHaveNoParent() {
		for (String name : ProgramFallbacks.names()) {
			assertEquals(ROOTS.contains(name), ProgramFallbacks.chain(name).size() == 1, name);
		}
		assertEquals(5, ROOTS.size());
		assertTrue(ProgramFallbacks.names().containsAll(ROOTS));
	}

	@Test
	void everyNameInTheTreeIsAProgramNameOfItsOwnFamily() {
		for (String name : ProgramFallbacks.names()) {
			ProgramNames.ProgramName program = ProgramNames.parse(name).orElseThrow(() -> new AssertionError(name));

			assertEquals(name, program.family());
			assertEquals(-1, program.slot());
			assertFalse(program.isCompute(), name);
		}
	}

	@Test
	void terrainWaterAndTheHeldItemWalkTheLitTexturedRoad() {
		assertEquals(List.of("gbuffers_water", "gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured",
				"gbuffers_basic"), ProgramFallbacks.chain("gbuffers_water"));
		assertEquals(List.of("gbuffers_hand_water", "gbuffers_hand", "gbuffers_textured_lit", "gbuffers_textured",
				"gbuffers_basic"), ProgramFallbacks.chain("gbuffers_hand_water"));
		assertEquals(List.of("gbuffers_block_translucent", "gbuffers_block", "gbuffers_terrain",
				"gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
				ProgramFallbacks.chain("gbuffers_block_translucent"));
		assertEquals(List.of("gbuffers_entities_translucent", "gbuffers_entities", "gbuffers_textured_lit",
				"gbuffers_textured", "gbuffers_basic"), ProgramFallbacks.chain("gbuffers_entities_translucent"));
	}

	@Test
	void itemsEntitiesWeatherAndParticlesFallBackToALitTexturedProgram() {
		for (String name : List.of("gbuffers_item", "gbuffers_entities", "gbuffers_hand", "gbuffers_weather",
				"gbuffers_particles", "gbuffers_terrain")) {
			assertEquals("gbuffers_textured_lit", ProgramFallbacks.chain(name).get(1), name);
		}
	}

	@Test
	void texturedAndLineProgramsFallBackToBasicAndCloudsToTextured() {
		assertEquals(List.of("gbuffers_line", "gbuffers_basic"), ProgramFallbacks.chain("gbuffers_line"));
		assertEquals(List.of("gbuffers_textured", "gbuffers_basic"), ProgramFallbacks.chain("gbuffers_textured"));
		assertEquals(List.of("gbuffers_skybasic", "gbuffers_basic"), ProgramFallbacks.chain("gbuffers_skybasic"));
		assertEquals(List.of("gbuffers_clouds", "gbuffers_textured", "gbuffers_basic"),
				ProgramFallbacks.chain("gbuffers_clouds"));
	}

	@Test
	void theShadowFamilyFallsBackToTheOneShadowProgramAndLightningThroughEntities() {
		assertEquals(List.of("shadow_solid", "shadow"), ProgramFallbacks.chain("shadow_solid"));
		assertEquals(List.of("shadow_lightning", "shadow_entities", "shadow"),
				ProgramFallbacks.chain("shadow_lightning"));
		assertEquals(List.of("shadow"), ProgramFallbacks.chain("shadow"));
	}

	@Test
	void distantHorizonsWaterAndGenericFallBackToItsTerrainAndItsShadowStandsAlone() {
		assertEquals(List.of("dh_water", "dh_terrain"), ProgramFallbacks.chain("dh_water"));
		assertEquals(List.of("dh_generic", "dh_terrain"), ProgramFallbacks.chain("dh_generic"));
		assertEquals(List.of("dh_shadow"), ProgramFallbacks.chain("dh_shadow"));
	}

	@Test
	void theNumberedFamiliesAndTheFinalPassHaveNoFallbackAtAll() {
		for (String name : List.of("final", "composite", "composite3", "deferred2", "prepare", "begin", "setup",
				"shadowcomp1")) {
			assertEquals(List.of(name), ProgramFallbacks.chain(name), name);
		}
	}

	@Test
	void aNameNobodyKnowsIsItsOwnChainAndNothingIsNoChain() {
		assertEquals(List.of("bogus"), ProgramFallbacks.chain("bogus"));
		assertEquals(List.of(""), ProgramFallbacks.chain(""));
		assertEquals(List.of(), ProgramFallbacks.chain(null));
	}

	@Test
	void aChainCannotBeChanged() {
		List<String> chain = ProgramFallbacks.chain("gbuffers_water");

		assertThrows(UnsupportedOperationException.class, () -> chain.add("x"));
		assertThrows(UnsupportedOperationException.class, () -> ProgramFallbacks.names().add("x"));
	}

	@Test
	void everyChainHasTheSameStageFamilyThroughout() {
		for (String name : ProgramFallbacks.names()) {
			for (String parent : ProgramFallbacks.chain(name)) {
				assertEquals(ProgramNames.shadowGeometry(name), ProgramNames.shadowGeometry(parent),
						name + " crosses from the light to the camera at " + parent);
				assertEquals(distantTerrain(name), distantTerrain(parent),
						name + " leaves Distant Horizons at " + parent);
			}
		}
	}

	/** The Distant Horizons programs drawn from the camera, its shadow being a family of its own. */
	private static boolean distantTerrain(String name) {
		return name.startsWith("dh_") && !name.equals("dh_shadow");
	}

	@Test
	void onlyTheSpiderEyesHaveABlendOfTheirOwn() {
		BlendMode eyes = ProgramFallbacks.blendOverride("gbuffers_spidereyes");

		assertEquals(new BlendMode(false, "SRC_ALPHA", "ONE", "ZERO", "ONE"), eyes);
		for (String name : ProgramFallbacks.names()) {
			if (!name.equals("gbuffers_spidereyes")) {
				assertNull(ProgramFallbacks.blendOverride(name), name);
			}
		}
		assertNull(ProgramFallbacks.blendOverride("bogus"));
	}
}
