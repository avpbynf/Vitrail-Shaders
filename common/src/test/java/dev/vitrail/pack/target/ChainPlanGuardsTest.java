package dev.vitrail.pack.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.TargetSize;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.target.ChainPlan.Attachment;
import dev.vitrail.pack.target.ChainPlan.Families;
import dev.vitrail.pack.target.TargetSchedule.Side;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins what the chain refuses to hand a render pass, and says so at load: a backend that throws at
 * the first draw is a backend whose refusal belongs to the plan, with the program named. Also what
 * the chain says about the picture when the engine leaves a family of the world undrawn, and the
 * one question a family drawn before the deferreds asks of the seed.
 */
class ChainPlanGuardsTest {

	@TempDir
	Path temp;

	private static Map<String, String> pack(String... namesAndTexts) {
		Map<String, String> files = new LinkedHashMap<>();
		for (int at = 0; at < namesAndTexts.length; at += 2) {
			files.put(namesAndTexts[at], namesAndTexts[at + 1]);
		}

		return files;
	}

	private static String fragment(String header, String... samplers) {
		StringBuilder text = new StringBuilder("#version 330 compatibility\n").append(header).append('\n');
		for (String sampler : samplers) {
			text.append("uniform sampler2D ").append(sampler).append(";\n");
		}

		return text.append("void main() {\n}\n").toString();
	}

	private SyntheticPack.Read read(Map<String, String> files) {
		return SyntheticPack.read(SyntheticPack.write(this.temp, "pack", files));
	}

	@Test
	void aPassCarriesAtMostEightAttachments() {
		assertEquals(8, ChainPlan.MAX_ATTACHMENTS);

		SyntheticPack.Read read = read(pack(
				"composite.fsh", fragment("/* RENDERTARGETS: 0,1,2,3,4,5,6,7,8 */"),
				"composite1.fsh", fragment("/* RENDERTARGETS: 0,1,2,3,4,5,6,7 */")));

		assertEquals(List.of("composite writes 9 targets and one pass carries at most 8: "
				+ "[0, 1, 2, 3, 4, 5, 6, 7, 8]"), read.chain().refusals());
		assertEquals(List.of("composite1"), read.chain().passes().stream().map(ChainPlan.Pass::program).toList());
		assertEquals(8, read.chain().passes().get(0).attachments().size());
	}

	@Test
	void oneImageCannotBeTwoAttachmentsOfOnePass() {
		SyntheticPack.Read read = read(pack(
				"composite.fsh", fragment("/* DRAWBUFFERS:00 */"),
				"composite1.fsh", fragment("/* DRAWBUFFERS:12 */")));

		assertEquals(List.of("composite names the same target twice in its draw buffers, [0, 0], and one "
				+ "image cannot be two attachments of one pass"), read.chain().refusals());
		assertEquals(List.of("composite1"), read.chain().passes().stream().map(ChainPlan.Pass::program).toList());
	}

	/**
	 * One pass has one render area, so its attachments share a size. A fraction of the screen and a
	 * count of pixels are both said in the pack's own terms.
	 */
	@Test
	void theAttachmentsOfOnePassShareOneSize() {
		SyntheticPack.Read fraction = read(pack(
				"shaders.properties", "size.buffer.colortex1 = 0.5 0.5\n",
				"composite.fsh", fragment("/* DRAWBUFFERS:01 */")));
		assertEquals(List.of("composite writes targets of two sizes, 1.000 by 1.000 of the screen and 0.500 "
				+ "by 0.500 of the screen for colortex1"), fraction.chain().refusals());

		SyntheticPack.Read pixels = read(pack(
				"shaders.properties", "size.buffer.colortex1 = 960 540\n",
				"composite.fsh", fragment("/* DRAWBUFFERS:10 */")));
		assertEquals(List.of("composite writes targets of two sizes, 960 by 540 pixels and 1.000 by 1.000 "
				+ "of the screen for colortex0"), pixels.chain().refusals());

		// Two targets of the same scaled size are one size and go through.
		SyntheticPack.Read same = read(pack(
				"shaders.properties", "size.buffer.colortex1 = 0.5 0.5\nsize.buffer.colortex2 = 0.5 0.5\n",
				"composite.fsh", fragment("/* DRAWBUFFERS:12 */")));
		assertEquals(List.of(), same.chain().refusals());
		assertEquals(new TargetSize(true, 0.5F, 0.5F), same.chain().passes().get(0).size());
	}

	/**
	 * A refused pass is refused by the chain and not by the walk: the schedule still holds its step and
	 * every later pass reads the halves as if it ran. The renderer takes it out and asks for the walk
	 * again, which is what {@code ChainFilter.without} is for.
	 */
	@Test
	void aRefusedPassIsStillInTheScheduleAndStillMovesTheHalves() {
		SyntheticPack.Read read = read(pack(
				"composite.fsh", fragment("/* DRAWBUFFERS:00 */"),
				"composite1.fsh", fragment("/* DRAWBUFFERS:0 */", "colortex0"),
				"final.fsh", fragment("", "colortex0")));

		assertEquals(1, read.chain().refusals().size());
		assertTrue(read.plan().schedule().step("composite").isPresent());
		// composite names colortex0 twice, which turns it over twice: composite1 reads M, as if the
		// first pass had not touched it, and writes A, which the final reads.
		assertEquals(Side.MAIN, read.plan().schedule().step("composite1").orElseThrow().read(0));
		assertEquals(Side.ALT, read.plan().schedule().step("final").orElseThrow().read(0));
	}

	/** What the engine itself refuses stands in front of what the chain finds. */
	@Test
	void whatTheEngineRefusesIsSaidFirst() {
		Path pack = SyntheticPack.write(this.temp, "refused", pack(
				"composite.fsh", fragment("/* DRAWBUFFERS:00 */")));
		SyntheticPack.Read read = SyntheticPack.read(pack, List.of("a program of this place cannot be built"),
				Families.DEFAULT);

		assertEquals(2, read.chain().refusals().size());
		assertEquals("a program of this place cannot be built", read.chain().refusals().get(0));
		assertTrue(read.chain().refusals().get(1).startsWith("composite names the same target twice"));
	}

	/**
	 * A geometry program is not part of the chain, so what it cannot carry is a note and never a
	 * refusal, and the family it serves goes on drawing on the game's own target.
	 */
	@Test
	void aGeometryProgramThatCannotBeCarriedIsANoteAndNotARefusal() {
		SyntheticPack.Read read = read(pack(
				"gbuffers_terrain.fsh", fragment("/* RENDERTARGETS: 0,1,2,3,4,5,6,7,8 */"),
				"composite.fsh", fragment("/* DRAWBUFFERS:0 */")));

		assertEquals(List.of(), read.chain().refusals());
		assertEquals(Optional.empty(), read.chain().geometry(TerrainPass.SOLID));
		assertTrue(read.chain().notes().contains("gbuffers_terrain writes 9 targets and one pass carries at "
				+ "most 8: [0, 1, 2, 3, 4, 5, 6, 7, 8]"));
	}

	// ------------------------------------------------------------------------------------------
	// What the chain says of the picture, and what the engine draws
	// ------------------------------------------------------------------------------------------

	private Map<String, String> terrainThenDeferred() {
		return pack(
				"gbuffers_terrain.fsh", fragment("/* DRAWBUFFERS:012 */"),
				"deferred.fsh", fragment("/* DRAWBUFFERS:0 */", "colortex0", "colortex1", "colortex2"),
				"final.fsh", fragment("", "colortex0"));
	}

	@Test
	void withEveryFamilyDrawnTheWorldFillsWhatTheDeferredStageReads() {
		assertEquals(List.of(), read(terrainThenDeferred()).chain().notes());
	}

	/**
	 * With the terrain and the entities both switched off the game's shader draws them and the pack's
	 * targets hold nothing of them, except colortex0, which the seed carries in: the chain says so for
	 * the two the seed does not reach, in the pack's own terms.
	 */
	@Test
	void withTheTerrainOffOnlyTheSeedFillsTheFirstTarget() {
		Path pack = SyntheticPack.write(this.temp, "terrain-off", terrainThenDeferred());
		SyntheticPack.Read read = SyntheticPack.read(pack, List.of(), new Families(false, false, true, true));

		assertEquals(List.of(
				"colortex1 is written by geometry, none of which this engine draws into the pack's targets, "
						+ "so deferred reads what the clear left there",
				"colortex2 is written by geometry, none of which this engine draws into the pack's targets, "
						+ "so deferred reads what the clear left there"), read.chain().notes());
	}

	/**
	 * The terrain line alone is not enough to take the terrain program out of the picture: a pack whose
	 * block entities fall back on the program that serves its terrain is answered by the terrain's walk,
	 * and the entities are drawn, so the same targets are still counted as filled. Pinned as the plan
	 * says it in its own comments, since it is what makes a switch read as if it moved nothing.
	 */
	@Test
	void aProgramServingTheTerrainIsStillCountedWhereTheBlockEntitiesFallBackOnIt() {
		Path pack = SyntheticPack.write(this.temp, "terrain-off-entities-on", terrainThenDeferred());
		SyntheticPack.Read read = SyntheticPack.read(pack, List.of(), new Families(false, true, true, true));

		assertEquals(List.of(), read.chain().notes());
	}

	@Test
	void withTheSeedOffTheTerrainStillFillsEverythingItWrites() {
		Path pack = SyntheticPack.write(this.temp, "seed-off", terrainThenDeferred());
		SyntheticPack.Read read = SyntheticPack.read(pack, List.of(), new Families(true, true, true, false));

		assertEquals(List.of(), read.chain().notes());
		// The plan still says where the seed would go, whatever the line says.
		assertTrue(read.chain().seed().isPresent());
	}

	@Test
	void withNeitherTheTerrainNorTheSeedNothingOfThePicturesTargetsIsFilled() {
		Path pack = SyntheticPack.write(this.temp, "both-off", terrainThenDeferred());
		SyntheticPack.Read read = SyntheticPack.read(pack, List.of(), new Families(false, false, true, false));

		assertEquals(3, read.chain().notes().size());
		assertTrue(read.chain().notes().get(0).startsWith("colortex0 is written by geometry"));
	}

	/** A pack that ships no terrain program has no answer for it and paints the seed nowhere. */
	@Test
	void aPackWithNoTerrainPaintsTheSeedNowhere() {
		SyntheticPack.Read read = read(pack(
				"composite.fsh", fragment("/* DRAWBUFFERS:0 */", "colortex1"),
				"final.fsh", fragment("", "colortex0")));

		assertEquals(Optional.empty(), read.chain().seed());
		assertTrue(read.chain().notes().contains("nothing here serves gbuffers_terrain, so the game's own "
				+ "frame is painted nowhere and every program of the chain reads clear colours"));
		assertEquals(Optional.empty(), read.chain().geometry(TerrainPass.SOLID));
	}

	/**
	 * Where the pack ships no final the chain brings colortex0 to the screen, and it says so whether or
	 * not anything of the place allocates it: the answer is the plan's, and the renderer is the one that
	 * asks whether the target exists before it draws from it.
	 */
	@Test
	void aPackWithNoFinalMayNameAFirstTargetNothingAllocates() {
		SyntheticPack.Read read = read(pack("composite.fsh", fragment("/* DRAWBUFFERS:3 */")));

		assertEquals(Set.of(3), read.plan().allocated());
		assertEquals(Optional.of(new Attachment(0, Side.MAIN)), read.chain().present());
	}

	@Test
	void aFamilyMayTakeOverTheGameOnlyWhereItsFirstOutputLandsOnTheSeed() {
		ChainPlan.Seed seed = new ChainPlan.Seed(0, Side.MAIN, "gbuffers_terrain", 0);
		ChainPlan.Pass onSeed = new ChainPlan.Pass("gbuffers_entities",
				List.of(new Attachment(0, Side.MAIN), new Attachment(1, Side.MAIN)), TargetSize.ofScreen(), false);
		ChainPlan.Pass otherHalf = new ChainPlan.Pass("gbuffers_entities",
				List.of(new Attachment(0, Side.ALT)), TargetSize.ofScreen(), false);
		ChainPlan.Pass otherTarget = new ChainPlan.Pass("gbuffers_entities",
				List.of(new Attachment(1, Side.MAIN), new Attachment(0, Side.MAIN)), TargetSize.ofScreen(), false);
		ChainPlan.Pass none = new ChainPlan.Pass("final", List.of(), TargetSize.ofScreen(), false);

		assertTrue(ChainPlan.leadsWithSeed(seed, onSeed));
		assertFalse(ChainPlan.leadsWithSeed(seed, otherHalf));
		assertFalse(ChainPlan.leadsWithSeed(seed, otherTarget));
		assertFalse(ChainPlan.leadsWithSeed(seed, none));
		assertFalse(ChainPlan.leadsWithSeed(seed, null));
		assertFalse(ChainPlan.leadsWithSeed(null, onSeed));
	}

	/**
	 * The clouds are drawn after the main pass, so they stand on the halves the deferred stage turned
	 * over, and the sky's two programs, drawn before it, on the ones the prepares leave. Answered on
	 * the wrong side they write the dead half of the ping pong and nothing says so.
	 * <p>
	 * By hand: the deferred pass writes colortex0 on A, so the clouds write colortex0 on A and the
	 * sky, before it, on M.
	 */
	@Test
	void theCloudsStandOnTheFarSideOfTheDeferredStageAndTheSkyOnTheNearOne() {
		SyntheticPack.Read read = read(pack(
				"gbuffers_skybasic.fsh", fragment("/* DRAWBUFFERS:0 */"),
				"gbuffers_clouds.fsh", fragment("/* DRAWBUFFERS:0 */"),
				"deferred.fsh", fragment("/* DRAWBUFFERS:0 */", "colortex0"),
				"final.fsh", fragment("", "colortex0")));

		assertEquals(List.of(new Attachment(0, Side.MAIN)), read.chain().sky("gbuffers_skybasic").orElseThrow().attachments());
		assertEquals(List.of(new Attachment(0, Side.ALT)), read.chain().sky("gbuffers_clouds").orElseThrow().attachments());
		assertEquals(Optional.empty(), read.chain().sky("gbuffers_skytextured"),
				"a pack with no textured program and no basic one for it to fall back on");
	}
}
