package dev.vitrail.pack.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.RenderStage;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Holds the table of the chunk renderer's passes: which program of the pack serves each, what it
 * discards at, whether it blends, and where in the frame it sits. The numbers are Iris's, and a cutout
 * threshold of a tenth in place of a half is leaves with the wrong silhouette.
 */
class TerrainPassTest {

	@Test
	void namesTheProgramThatServesEachPass() {
		assertEquals("gbuffers_terrain_solid", TerrainPass.SOLID.program());
		assertEquals("gbuffers_terrain_cutout", TerrainPass.CUTOUT.program());
		assertEquals("gbuffers_water", TerrainPass.TRANSLUCENT.program());
		assertEquals("shadow_solid", TerrainPass.SHADOW_SOLID.program());
		assertEquals("shadow_cutout", TerrainPass.SHADOW_CUTOUT.program());
		assertEquals("shadow_water", TerrainPass.SHADOW_TRANSLUCENT.program());
	}

	@Test
	void gaveEachPassTheAlphaTestIrisGivesIt() {
		assertEquals(AlphaTest.OFF, TerrainPass.SOLID.alphaTest(Map.of(), "gbuffers_terrain"));
		assertEquals(AlphaTest.CUTOUT, TerrainPass.CUTOUT.alphaTest(Map.of(), "gbuffers_terrain"));
		assertEquals(AlphaTest.NON_ZERO, TerrainPass.TRANSLUCENT.alphaTest(Map.of(), "gbuffers_water"));
		assertEquals(AlphaTest.OFF, TerrainPass.SHADOW_SOLID.alphaTest(Map.of(), "shadow"));
		assertEquals(AlphaTest.CUTOUT, TerrainPass.SHADOW_CUTOUT.alphaTest(Map.of(), "shadow"));
		assertEquals(AlphaTest.OFF, TerrainPass.SHADOW_TRANSLUCENT.alphaTest(Map.of(), "shadow"));
		assertEquals(0.5F, AlphaTest.CUTOUT.reference());
		assertEquals(0.0001F, AlphaTest.NON_ZERO.reference());
	}

	@Test
	void anOverrideIsKeyedByTheFileThatServesThePassAndMovesEveryPassThatFileServes() {
		AlphaTest tenth = new AlphaTest(AlphaTest.Function.GREATER, 0.1F);
		Map<String, AlphaTest> overrides = Map.of("gbuffers_terrain", tenth);

		// One gbuffers_terrain serves the solid and the cutout pass, and the override reaches both.
		assertSame(tenth, TerrainPass.SOLID.alphaTest(overrides, "gbuffers_terrain"));
		assertSame(tenth, TerrainPass.CUTOUT.alphaTest(overrides, "gbuffers_terrain"));
		// Keyed by the name of the file and not the name asked for.
		assertEquals(AlphaTest.CUTOUT, TerrainPass.CUTOUT.alphaTest(overrides, "gbuffers_terrain_cutout"));
		assertEquals(AlphaTest.NON_ZERO, TerrainPass.TRANSLUCENT.alphaTest(overrides, "gbuffers_water"));
	}

	@Test
	void saysWhichPassesBlendWhichAreShadowAndWhichWriteTheCoverageMask() {
		assertFalse(TerrainPass.SOLID.blended());
		assertFalse(TerrainPass.CUTOUT.blended());
		assertTrue(TerrainPass.TRANSLUCENT.blended());
		assertFalse(TerrainPass.SHADOW_TRANSLUCENT.blended());

		assertFalse(TerrainPass.SOLID.shadow());
		assertTrue(TerrainPass.SHADOW_SOLID.shadow());
		assertTrue(TerrainPass.SHADOW_CUTOUT.shadow());
		assertTrue(TerrainPass.SHADOW_TRANSLUCENT.shadow());

		assertTrue(TerrainPass.SOLID.covers());
		assertTrue(TerrainPass.CUTOUT.covers());
		assertFalse(TerrainPass.TRANSLUCENT.covers());
		assertFalse(TerrainPass.SHADOW_SOLID.covers());
	}

	@Test
	void mapsAWorldPassOntoTheShadowPassThatSharesItsMeshAndNothingForAShadowPass() {
		assertSame(TerrainPass.SHADOW_SOLID, TerrainPass.SOLID.inShadow());
		assertSame(TerrainPass.SHADOW_CUTOUT, TerrainPass.CUTOUT.inShadow());
		assertSame(TerrainPass.SHADOW_TRANSLUCENT, TerrainPass.TRANSLUCENT.inShadow());
		assertNull(TerrainPass.SHADOW_SOLID.inShadow());
		assertNull(TerrainPass.SHADOW_CUTOUT.inShadow());
		assertNull(TerrainPass.SHADOW_TRANSLUCENT.inShadow());
	}

	@Test
	void placesOnlyTheTranslucentWorldPassAfterTheDeferredStage() {
		for (TerrainPass pass : TerrainPass.values()) {
			assertEquals(pass == TerrainPass.TRANSLUCENT, pass.afterDeferred(), pass.name());
		}
	}

	@Test
	void tellsAPackWhichHalfOfTheWorldItIsDrawingAndAShadowHalfAnswersLikeItsWorldHalf() {
		assertEquals(RenderStage.TERRAIN_SOLID, TerrainPass.SOLID.stage());
		assertEquals(RenderStage.TERRAIN_SOLID, TerrainPass.SHADOW_SOLID.stage());
		assertEquals(RenderStage.TERRAIN_CUTOUT, TerrainPass.CUTOUT.stage());
		assertEquals(RenderStage.TERRAIN_CUTOUT, TerrainPass.SHADOW_CUTOUT.stage());
		assertEquals(RenderStage.TERRAIN_TRANSLUCENT, TerrainPass.TRANSLUCENT.stage());
		assertEquals(RenderStage.TERRAIN_TRANSLUCENT, TerrainPass.SHADOW_TRANSLUCENT.stage());
	}
}
