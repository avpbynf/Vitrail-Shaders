package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds the three small lookups that name a moment of the frame: the pipeline stage a file is by
 * its extension, the texture stage a program belongs to, and the render stage a pack is told.
 */
class StageNamesTest {

	// ---- ProgramStage ------------------------------------------------------------------------

	@Test
	void anExtensionAloneDecidesTheStageInAnyCase() {
		assertEquals(Optional.of(ProgramStage.VERTEX), ProgramStage.fromExtension("vsh"));
		assertEquals(Optional.of(ProgramStage.FRAGMENT), ProgramStage.fromExtension("fsh"));
		assertEquals(Optional.of(ProgramStage.FRAGMENT), ProgramStage.fromExtension("FSH"));
		assertEquals(Optional.of(ProgramStage.GEOMETRY), ProgramStage.fromExtension("gsh"));
		assertEquals(Optional.of(ProgramStage.COMPUTE), ProgramStage.fromExtension("Csh"));
		assertEquals(Optional.of(ProgramStage.TESSELLATION_CONTROL), ProgramStage.fromExtension("tcs"));
		assertEquals(Optional.of(ProgramStage.TESSELLATION_EVALUATION), ProgramStage.fromExtension("tes"));
	}

	@Test
	void aSharedBodyIsNeverAStage() {
		for (String extension : new String[] {"glsl", "inc", "settings", "", ".fsh", "fsh ", "vsh1", "txt"}) {
			assertTrue(ProgramStage.fromExtension(extension).isEmpty(), "'" + extension + "'");
		}
	}

	@Test
	void everyStageRoundTripsThroughItsExtension() {
		for (ProgramStage stage : ProgramStage.values()) {
			assertEquals(Optional.of(stage), ProgramStage.fromExtension(stage.extension()));
			assertEquals(Optional.of(stage), ProgramStage.ofFile("composite1." + stage.extension()));
		}
		assertEquals(6, ProgramStage.values().length);
	}

	@Test
	void aFileIsReadByItsLastExtension() {
		assertEquals(Optional.of(ProgramStage.VERTEX), ProgramStage.ofFile("a/b.c.vsh"));
		assertEquals(Optional.of(ProgramStage.FRAGMENT), ProgramStage.ofFile("composite.FSH"));
		assertEquals(Optional.of(ProgramStage.FRAGMENT), ProgramStage.ofFile(".fsh"));
		assertTrue(ProgramStage.ofFile("composite").isEmpty());
		assertTrue(ProgramStage.ofFile("composite.").isEmpty());
		assertTrue(ProgramStage.ofFile("composite.fsh.glsl").isEmpty());
		assertTrue(ProgramStage.ofFile("").isEmpty());
	}

	// ---- TextureStage ------------------------------------------------------------------------

	@Test
	void theSevenWordsAPackWritesBetweenTextureAndTheSampler() {
		assertEquals(Optional.of(TextureStage.SETUP), TextureStage.parse("setup"));
		assertEquals(Optional.of(TextureStage.BEGIN), TextureStage.parse("begin"));
		assertEquals(Optional.of(TextureStage.SHADOWCOMP), TextureStage.parse("shadowcomp"));
		assertEquals(Optional.of(TextureStage.PREPARE), TextureStage.parse("prepare"));
		assertEquals(Optional.of(TextureStage.GBUFFERS), TextureStage.parse("gbuffers"));
		assertEquals(Optional.of(TextureStage.DEFERRED), TextureStage.parse("deferred"));
		assertEquals(Optional.of(TextureStage.COMPOSITE), TextureStage.parse("composite"));
		assertEquals(7, TextureStage.values().length);
	}

	@Test
	void aWordOutsideTheSevenIsNoStage() {
		for (String word : new String[] {"", "shadow", "final", "Composite", "COMPOSITE", "gbuffers_terrain",
			"composite1", " composite", "dh"}) {
			assertTrue(TextureStage.parse(word).isEmpty(), "'" + word + "'");
		}
	}

	@Test
	void aProgramBelongsToTheStageOfItsFamily() {
		assertEquals(Optional.of(TextureStage.COMPOSITE), TextureStage.of("composite4"));
		assertEquals(Optional.of(TextureStage.COMPOSITE), TextureStage.of("composite4_a"));
		assertEquals(Optional.of(TextureStage.COMPOSITE), TextureStage.of("final"));
		assertEquals(Optional.of(TextureStage.GBUFFERS), TextureStage.of("gbuffers_water"));
		assertEquals(Optional.of(TextureStage.GBUFFERS), TextureStage.of("gbuffers_bogus"));
		assertEquals(Optional.of(TextureStage.GBUFFERS), TextureStage.of("shadow"));
		assertEquals(Optional.of(TextureStage.GBUFFERS), TextureStage.of("shadow_solid"));
		assertEquals(Optional.of(TextureStage.GBUFFERS), TextureStage.of("dh_terrain"));
		assertEquals(Optional.of(TextureStage.SHADOWCOMP), TextureStage.of("shadowcomp1"));
		assertEquals(Optional.of(TextureStage.PREPARE), TextureStage.of("prepare"));
		assertEquals(Optional.of(TextureStage.BEGIN), TextureStage.of("begin"));
		assertEquals(Optional.of(TextureStage.SETUP), TextureStage.of("setup2"));
		assertEquals(Optional.of(TextureStage.DEFERRED), TextureStage.of("deferred3"));
		assertTrue(TextureStage.of("bogus").isEmpty());
		assertTrue(TextureStage.of("").isEmpty());
	}

	@Test
	void everyNameOfTheFallbackTreeIsInGbuffersExceptTheFinalPass() {
		for (String name : ProgramFallbacks.names()) {
			assertEquals(Optional.of(name.equals("final") ? TextureStage.COMPOSITE : TextureStage.GBUFFERS),
					TextureStage.of(name), name);
		}
	}

	// ---- RenderStage -------------------------------------------------------------------------

	@Test
	void aRenderStageSymbolIsThePrefixAndTheConstantName() {
		assertEquals("MC_RENDER_STAGE_NONE", RenderStage.NONE.symbol());
		assertEquals("MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED", RenderStage.TERRAIN_CUTOUT_MIPPED.symbol());
		assertEquals("MC_RENDER_STAGE_HAND_TRANSLUCENT", RenderStage.HAND_TRANSLUCENT.symbol());

		for (RenderStage stage : RenderStage.values()) {
			assertEquals("MC_RENDER_STAGE_" + stage.name(), stage.symbol());
		}
	}

	@Test
	void theRenderStagesAreNumberedAsIrisNumbersThem() {
		assertEquals(24, RenderStage.values().length);
		assertEquals(0, RenderStage.NONE.ordinal());
		assertEquals(1, RenderStage.SKY.ordinal());
		assertEquals(8, RenderStage.TERRAIN_SOLID.ordinal());
		assertEquals(11, RenderStage.ENTITIES.ordinal());
		assertEquals(17, RenderStage.TERRAIN_TRANSLUCENT.ordinal());
		assertEquals(23, RenderStage.HAND_TRANSLUCENT.ordinal());
	}
}
