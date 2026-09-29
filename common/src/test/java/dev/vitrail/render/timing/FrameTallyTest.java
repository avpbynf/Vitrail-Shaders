package dev.vitrail.render.timing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.vitrail.render.timing.FrameTally.Family;
import dev.vitrail.render.timing.FrameTally.Kind;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What a frame census sums and how it says so, driven by plain calls with no game running: which
 * bind was redundant, whose a draw is, which programs wrote their block twice, and the lines a
 * window comes to.
 * <p>
 * The passes and the pipelines are bare objects, because all the tally asks of either is whether it
 * is the same one as last time.
 */
class FrameTallyTest {

	private final FrameTally tally = new FrameTally();

	// -- redundant binds -----------------------------------------------------------------------

	@Test
	void aBindOfWhatThePassAlreadyHoldsIsRedundant() {
		Object pass = new Object();
		Object first = new Object();
		Object second = new Object();

		this.tally.bound(pass, first, Family.ENTITY);
		this.tally.bound(pass, first, Family.ENTITY);
		this.tally.bound(pass, first, Family.ENTITY);
		this.tally.bound(pass, second, Family.ENTITY);

		assertEquals(4, this.tally.binds(Family.ENTITY));
		assertEquals(2, this.tally.redundant(Family.ENTITY));
	}

	@Test
	void goingBackToAnEarlierPipelineIsNotARepeat() {
		Object pass = new Object();
		Object first = new Object();
		Object second = new Object();

		// The pass holds the second by then, so setting the first again is a change of pipeline and
		// costs what one costs.
		this.tally.bound(pass, first, Family.ENTITY);
		this.tally.bound(pass, second, Family.ENTITY);
		this.tally.bound(pass, first, Family.ENTITY);

		assertEquals(0, this.tally.redundant(Family.ENTITY));
	}

	@Test
	void theSamePipelineOnANewPassIsAFirstBind() {
		Object pipeline = new Object();

		this.tally.bound(new Object(), pipeline, Family.CHAIN);
		this.tally.bound(new Object(), pipeline, Family.CHAIN);

		assertEquals(2, this.tally.binds(Family.CHAIN));
		assertEquals(0, this.tally.redundant(Family.CHAIN));
	}

	@Test
	void aFrameForgetsThePassItEndedOn() {
		Object pass = new Object();
		Object pipeline = new Object();

		this.tally.bound(pass, pipeline, Family.TERRAIN);
		this.tally.endFrame();
		this.tally.bound(pass, pipeline, Family.TERRAIN);

		assertEquals(0, this.tally.redundant(Family.TERRAIN));
	}

	@Test
	void aRepeatIsFiledUnderTheFamilyItWasBoundAs() {
		Object pass = new Object();
		Object pipeline = new Object();

		this.tally.bound(pass, pipeline, Family.SKY);
		this.tally.bound(pass, pipeline, Family.SKY);

		assertEquals(1, this.tally.redundant(Family.SKY));
		assertEquals(0, this.tally.redundant(Family.ENTITY));
	}

	// -- draws ---------------------------------------------------------------------------------

	@Test
	void aDrawBelongsToTheFamilyOfThePipelineItsPassHolds() {
		Object pass = new Object();

		this.tally.bound(pass, new Object(), Family.ENTITY);
		this.tally.drawn(pass);
		this.tally.drawn(pass);
		this.tally.bound(pass, new Object(), Family.PARTICLE);
		this.tally.drawn(pass);

		assertEquals(2, this.tally.draws(Family.ENTITY));
		assertEquals(1, this.tally.draws(Family.PARTICLE));
	}

	@Test
	void aDrawIntoAPassNothingWasBoundOnIsSomebodyElses() {
		this.tally.bound(new Object(), new Object(), Family.ENTITY);
		this.tally.drawn(new Object());

		assertEquals(1, this.tally.draws(Family.OTHER));
		assertEquals(0, this.tally.draws(Family.ENTITY));
	}

	// -- blocks --------------------------------------------------------------------------------

	@Test
	void aProgramWrittenTwiceInAFrameIsRewrittenOnceHoweverOftenItIsWritten() {
		FrameCensus.Block block = new FrameCensus.Block();

		this.tally.geometryWritten(block);
		this.tally.geometryWritten(block);
		this.tally.geometryWritten(block);
		this.tally.geometryWritten(block);
		this.tally.endFrame();

		assertTrue(this.tally.lines(1.0).contains("  4.0 geometry block writes over 1.0 programs, 1.0 of "
				+ "them written more than once (0 would be ideal), 0.0 chain block writes, 0.0 far "
				+ "terrain block writes"), this.tally.lines(1.0).toString());
	}

	@Test
	void aProgramDrawnInSeveralRunsIsPlacedOnceAFrame() {
		FrameCensus.Block block = new FrameCensus.Block();

		this.tally.blockPlaced(block, true);
		this.tally.blockPlaced(block, true);
		this.tally.blockPlaced(block, true);
		this.tally.endFrame();
		this.tally.blockPlaced(block, true);
		this.tally.endFrame();

		// Two frames, and the program is one of the drawn ones in each.
		assertTrue(this.tally.lines(1.0).stream().anyMatch(line -> line.startsWith("  1.0 drawn geometry "
				+ "programs keep their block in the ring they share, 0.0 in a ring of their own")),
				this.tally.lines(1.0).toString());
	}

	@Test
	void programsOnTheirOwnRingAreCountedApartFromTheOnesOnTheSharedRing() {
		this.tally.blockPlaced(new FrameCensus.Block(), true);
		this.tally.blockPlaced(new FrameCensus.Block(), true);
		this.tally.blockPlaced(new FrameCensus.Block(), false);
		this.tally.blockPlaced(new FrameCensus.Block(), false);
		this.tally.endFrame();

		assertTrue(this.tally.lines(1.0).stream().anyMatch(line -> line.startsWith("  2.0 drawn geometry "
				+ "programs keep their block in the ring they share, 2.0 in a ring of their own")),
				this.tally.lines(1.0).toString());
	}

	@Test
	void theSharedRingSaysWhereItStandsOnlyOnceItHasACapacity() {
		this.tally.endFrame();

		assertTrue(this.tally.lines(1.0).stream().noneMatch(line -> line.startsWith("  the shared ring")));

		this.tally.blockRing(1 << 20, 4096, 8192, 3);
		this.tally.clear();
		this.tally.endFrame();

		// A window empties and the ring does not: what it says is where it stands, not a count.
		assertTrue(this.tally.lines(1.0).contains("  the shared ring holds 3 blocks in 4096 of its "
				+ "1048576 bytes, 8192 bytes at most"), this.tally.lines(1.0).toString());

		this.tally.blockRing(0, 0, 0, 0);

		assertTrue(this.tally.lines(1.0).stream().noneMatch(line -> line.startsWith("  the shared ring")));
	}

	@Test
	void aNewFrameStartsEveryProgramOver() {
		FrameCensus.Block first = new FrameCensus.Block();
		FrameCensus.Block second = new FrameCensus.Block();

		this.tally.geometryWritten(first);
		this.tally.geometryWritten(second);
		this.tally.endFrame();
		this.tally.geometryWritten(first);
		this.tally.endFrame();

		// Three programs written over two frames and none of them twice in one: a program that writes
		// once a frame is the number to want, and is not a rewrite.
		assertTrue(this.tally.lines(1.0).contains("  1.5 geometry block writes over 1.5 programs, 0.0 of "
				+ "them written more than once (0 would be ideal), 0.0 chain block writes, 0.0 far "
				+ "terrain block writes"), this.tally.lines(1.0).toString());
	}

	@Test
	void aClearEmptiesTheCountsAndNotTheFrameInProgress() {
		FrameCensus.Block block = new FrameCensus.Block();

		this.tally.geometryWritten(block);
		this.tally.clear();
		this.tally.geometryWritten(block);
		this.tally.endFrame();

		// Emptied at the report, which lands in the middle of a frame's calls as often as not: the
		// second write is still the second write of that frame, and is counted as a rewrite.
		assertTrue(this.tally.lines(1.0).stream()
				.anyMatch(line -> line.contains("1.0 of them written more than once")),
				this.tally.lines(1.0).toString());
	}

	@Test
	void aProgramFoundStandingInItsPassIsCountedApartFromOneThatWasSet() {
		this.tally.programBound();
		this.tally.programKept();
		this.tally.programKept();
		this.tally.programKept();
		this.tally.endFrame();

		// One set and three kept over one frame: the first is the number to want at one a program
		// and pass, and the second says how many binds found their program standing in the pass.
		assertTrue(this.tally.lines(1.0).contains("  1.0 program binds, each one a uniform block and "
				+ "its samplers set on the pass, and 3.0 more that found the program already "
				+ "standing in it and set only the images the draw brought"),
				this.tally.lines(1.0).toString());
	}

	@Test
	void aClearEmptiesTheBindsThatFoundTheirProgramStanding() {
		this.tally.programKept();
		this.tally.programKept();
		this.tally.clear();
		this.tally.programBound();
		this.tally.endFrame();

		assertTrue(this.tally.lines(1.0).stream()
				.anyMatch(line -> line.contains("1.0 program binds") && line.contains("and 0.0 more")),
				this.tally.lines(1.0).toString());
	}

	// -- sets that changed nothing -------------------------------------------------------------

	@Test
	void aSetOfWhatThePassAlreadyHoldsChangedNothing() {
		Object pass = new Object();
		Object slice = new Object();

		this.tally.set(Kind.BLOCK, pass, "Block", slice, null, true);
		this.tally.set(Kind.BLOCK, pass, "Block", slice, null, true);
		this.tally.set(Kind.BLOCK, pass, "Block", new Object(), null, true);

		assertEquals(3, this.tally.sets(Kind.BLOCK));
		assertEquals(1, this.tally.unchanged(Kind.BLOCK));
	}

	@Test
	void aSliceIsTheSameSliceByItsValueAndNotByItsObject() {
		Object pass = new Object();

		// The game hands a fresh slice for each draw that names the same bytes, so the comparison is
		// the slice's own equality and never the object's.
		this.tally.set(Kind.TRANSFORMS, pass, "Transforms", new String("a slice"), null, true);
		this.tally.set(Kind.TRANSFORMS, pass, "Transforms", new String("a slice"), null, true);

		assertEquals(1, this.tally.unchanged(Kind.TRANSFORMS));
	}

	@Test
	void anImageOnAnotherSamplerIsAnotherSet() {
		Object pass = new Object();
		Object view = new Object();
		Object sampler = new Object();

		this.tally.set(Kind.TEXTURE, pass, "gtexture", view, sampler, true);
		this.tally.set(Kind.TEXTURE, pass, "gtexture", view, new Object(), true);
		this.tally.set(Kind.TEXTURE, pass, "gtexture", new Object(), sampler, true);

		assertEquals(3, this.tally.sets(Kind.TEXTURE));
		assertEquals(0, this.tally.unchanged(Kind.TEXTURE));
	}

	@Test
	void theSameValueOnAnotherPassIsAFirstSet() {
		Object slice = new Object();

		this.tally.set(Kind.BLOCK, new Object(), "Block", slice, null, true);
		this.tally.set(Kind.BLOCK, new Object(), "Block", slice, null, true);

		assertEquals(0, this.tally.unchanged(Kind.BLOCK));
	}

	@Test
	void everyNameOfAPassHoldsItsOwnValue() {
		Object pass = new Object();
		Object view = new Object();
		Object sampler = new Object();

		this.tally.set(Kind.TEXTURE, pass, "gtexture", view, sampler, true);
		this.tally.set(Kind.TEXTURE, pass, "normals", view, sampler, true);
		this.tally.set(Kind.TEXTURE, pass, "gtexture", view, sampler, true);
		this.tally.set(Kind.TEXTURE, pass, "normals", view, sampler, true);

		assertEquals(4, this.tally.sets(Kind.TEXTURE));
		assertEquals(2, this.tally.unchanged(Kind.TEXTURE));
	}

	@Test
	void aSetNobodyCountsStillMovesWhatThePassHolds() {
		Object pass = new Object();
		Object first = new Object();
		Object second = new Object();

		// A bind that finds the program not standing in the pass writes every name it declares and
		// is not what the figure is about, but it is what the pass holds afterwards: the count that
		// follows is against it.
		this.tally.set(Kind.TEXTURE, pass, "noisetex", first, null, false);
		this.tally.set(Kind.TEXTURE, pass, "noisetex", first, null, true);
		this.tally.set(Kind.TEXTURE, pass, "noisetex", second, null, false);
		this.tally.set(Kind.TEXTURE, pass, "noisetex", first, null, true);

		assertEquals(2, this.tally.sets(Kind.TEXTURE));
		assertEquals(1, this.tally.unchanged(Kind.TEXTURE));
	}

	@Test
	void theKindsAreCountedApart() {
		Object pass = new Object();
		Object value = new Object();

		this.tally.set(Kind.BLOCK, pass, "Block", value, null, true);
		this.tally.set(Kind.BLOCK, pass, "Block", value, null, true);
		this.tally.set(Kind.TEXTURE, pass, "gtexture", value, null, true);
		this.tally.set(Kind.TRANSFORMS, pass, "Transforms", value, null, true);
		this.tally.set(Kind.TRANSFORMS, pass, "Transforms", value, null, true);
		this.tally.set(Kind.TRANSFORMS, pass, "Transforms", value, null, true);

		assertEquals(2, this.tally.sets(Kind.BLOCK));
		assertEquals(1, this.tally.unchanged(Kind.BLOCK));
		assertEquals(1, this.tally.sets(Kind.TEXTURE));
		assertEquals(0, this.tally.unchanged(Kind.TEXTURE));
		assertEquals(3, this.tally.sets(Kind.TRANSFORMS));
		assertEquals(2, this.tally.unchanged(Kind.TRANSFORMS));
	}

	@Test
	void aFrameEndForgetsWhatThePassHeld() {
		Object pass = new Object();
		Object slice = new Object();

		this.tally.set(Kind.BLOCK, pass, "Block", slice, null, true);
		this.tally.endFrame();
		this.tally.set(Kind.BLOCK, pass, "Block", slice, null, true);

		assertEquals(0, this.tally.unchanged(Kind.BLOCK));
	}

	@Test
	void aClearEmptiesTheCountsOfTheSets() {
		Object pass = new Object();
		Object slice = new Object();

		this.tally.set(Kind.BLOCK, pass, "Block", slice, null, true);
		this.tally.set(Kind.BLOCK, pass, "Block", slice, null, true);
		this.tally.clear();

		assertEquals(0, this.tally.sets(Kind.BLOCK));
		assertEquals(0, this.tally.unchanged(Kind.BLOCK));
	}

	@Test
	void theSetsThatChangedNothingAreSaidByKindOverTheSetsOfThatKind() {
		Object pass = new Object();
		Object view = new Object();
		Object sampler = new Object();
		Object slice = new Object();

		// Two frames. Block: two sets, both a move. Texture: four sets of which one repeats.
		// Transforms: two sets of which one repeats.
		this.tally.set(Kind.BLOCK, pass, "Block", slice, null, true);
		this.tally.set(Kind.TEXTURE, pass, "gtexture", view, sampler, true);
		this.tally.set(Kind.TEXTURE, pass, "gtexture", view, sampler, true);
		this.tally.set(Kind.TRANSFORMS, pass, "Transforms", slice, null, true);
		this.tally.set(Kind.TRANSFORMS, pass, "Transforms", slice, null, true);
		this.tally.endFrame();
		this.tally.set(Kind.BLOCK, pass, "Block", slice, null, true);
		this.tally.set(Kind.TEXTURE, pass, "gtexture", view, sampler, true);
		this.tally.set(Kind.TEXTURE, pass, "gtexture", new Object(), sampler, true);
		this.tally.endFrame();

		assertTrue(this.tally.lines(1.0).contains("  1.0 sets that changed nothing (the pass already "
				+ "held that value: 0 would be ideal), of those a program made while already "
				+ "standing in its pass: block 0.0 of 1.0, texture 0.5 of 2.0, transforms 0.5 of 1.0"),
				this.tally.lines(1.0).toString());
	}

	// -- families ------------------------------------------------------------------------------

	@Test
	void everyNameAGeometryFamilyGoesByHasARow() {
		assertEquals(Family.TERRAIN, Family.named("chunk"));
		assertEquals(Family.ENTITY, Family.named("entity"));
		assertEquals(Family.PARTICLE, Family.named("particles"));
		assertEquals(Family.DISTANT, Family.named("far terrain"));
		assertEquals(Family.SKY, Family.named("sky"));
		assertEquals(Family.SKY, Family.named("weather"));
		assertEquals(Family.SKY, Family.named("cloud"));
	}

	@Test
	void aNameNobodyKnowsIsFiledWithTheStrangers() {
		assertEquals(Family.OTHER, Family.named("hand"));
	}

	// -- the words -----------------------------------------------------------------------------

	@Test
	void aWindowWithNoFrameSaysNothing() {
		this.tally.bound(new Object(), new Object(), Family.ENTITY);

		assertTrue(this.tally.lines(5.0).isEmpty());
	}

	@Test
	void everyNumberIsAnAverageOverTheFramesOfTheWindow() {
		// Entity: a pass bound to one pipeline five times and to another once, so six binds of which
		// four repeat, and six draws into it.
		Object entities = new Object();
		Object pipeline = new Object();
		for (int i = 0; i < 5; i++) {
			this.tally.bound(entities, pipeline, Family.ENTITY);
		}

		this.tally.bound(entities, new Object(), Family.ENTITY);
		for (int i = 0; i < 6; i++) {
			this.tally.drawn(entities);
		}

		// Terrain: two binds on passes of their own, and no draw the pass could have seen.
		this.tally.bound(new Object(), new Object(), Family.TERRAIN);
		this.tally.bound(new Object(), new Object(), Family.TERRAIN);

		// Everybody else's: four binds on passes of their own, two draws into the last.
		Object stranger = new Object();
		for (int i = 0; i < 3; i++) {
			this.tally.bound(new Object(), new Object(), Family.OTHER);
		}

		this.tally.bound(stranger, new Object(), Family.OTHER);
		this.tally.drawn(stranger);
		this.tally.drawn(stranger);

		for (int i = 0; i < 8; i++) {
			this.tally.pushed(i < 2);
		}

		for (int i = 0; i < 40; i++) {
			this.tally.described();
		}

		for (int i = 0; i < 6; i++) {
			this.tally.programBound();
		}

		for (int i = 0; i < 10; i++) {
			this.tally.programKept();
		}

		this.tally.chainWritten();
		this.tally.chainWritten();
		for (int i = 0; i < 4; i++) {
			this.tally.farWritten();
		}

		for (int i = 0; i < 6; i++) {
			this.tally.rotated();
		}

		for (int i = 0; i < 20; i++) {
			this.tally.farSection(false);
		}

		this.tally.farPass(false);
		this.tally.farPass(false);
		for (int i = 0; i < 10; i++) {
			this.tally.farSection(true);
		}

		this.tally.farPass(true);
		this.tally.farPass(true);

		for (int i = 0; i < 6; i++) {
			this.tally.readied();
		}

		for (int i = 0; i < 4; i++) {
			this.tally.readyPrepared();
		}

		this.tally.readyDone();
		this.tally.readyDone();

		// Four sets of the pass's images of which one repeats, and two blocks and no transforms.
		Object pass = new Object();
		Object view = new Object();
		Object sampler = new Object();
		this.tally.set(Kind.TEXTURE, pass, "gtexture", view, sampler, true);
		this.tally.set(Kind.TEXTURE, pass, "gtexture", view, sampler, true);
		this.tally.set(Kind.TEXTURE, pass, "normals", view, sampler, true);
		this.tally.set(Kind.TEXTURE, pass, "specular", view, sampler, true);
		this.tally.set(Kind.BLOCK, pass, "Block", view, null, true);
		this.tally.set(Kind.BLOCK, pass, "Block", new Object(), null, true);

		// One program written twice in the first frame and once in the second, another once in the
		// first: four writes over three program-frames, one of them a rewrite.
		FrameCensus.Block twice = new FrameCensus.Block();
		FrameCensus.Block other = new FrameCensus.Block();
		FrameCensus.Block alone = new FrameCensus.Block();
		this.tally.blockPlaced(twice, true);
		this.tally.geometryWritten(twice);
		this.tally.blockPlaced(twice, true);
		this.tally.geometryWritten(twice);
		this.tally.blockPlaced(other, true);
		this.tally.geometryWritten(other);
		this.tally.blockPlaced(alone, false);
		this.tally.endFrame();
		this.tally.blockPlaced(twice, true);
		this.tally.geometryWritten(twice);
		this.tally.endFrame();
		this.tally.blockRing(1 << 20, 8192, 12288, 5);

		assertEquals(List.of(
				"Frame census over 10.0 s, 2 frames, an average frame:",
				"  6.0 pipeline binds, 2.0 of them redundant (the pass already held that pipeline: "
						+ "0 would be ideal), 4.0 draws",
				"       3.0 binds      2.0 redundant      3.0 draws  entity",
				"       1.0 binds      0.0 redundant        - draws  terrain",
				"       2.0 binds      0.0 redundant      1.0 draws  not this engine's",
				"  4.0 descriptor pushes, 5.0 descriptors each, 1.0 bound as an allocated set "
						+ "instead (a push a draw is the most it can be)",
				"  3.0 program binds, each one a uniform block and its samplers set on the pass, "
						+ "and 5.0 more that found the program already standing in it and set only "
						+ "the images the draw brought",
				"  0.5 sets that changed nothing (the pass already held that value: 0 would be "
						+ "ideal), of those a program made while already standing in its pass: block "
						+ "0.0 of 1.0, texture 0.5 of 2.0, transforms 0.0 of 0.0",
				"  2.0 geometry block writes over 1.5 programs, 0.5 of them written more than once "
						+ "(0 would be ideal), 1.0 chain block writes, 2.0 far terrain block writes",
				"  3.0 ring rotations, each one a fence created",
				"  1.5 drawn geometry programs keep their block in the ring they share, 0.5 in a "
						+ "ring of their own (0 would be ideal)",
				"  the shared ring holds 5 blocks in 8192 of its 1048576 bytes, 12288 bytes at most",
				"  far terrain section uniforms, one descriptor push each: 10.0 over 1.0 camera "
						+ "passes, 5.0 over 1.0 shadow passes",
				"  3.0 PackChain.ready() calls, 2.0 of them reaching the targets' prepare and 1.0 "
						+ "handing a frame back (one full prepare would do)"),
				this.tally.lines(10.0));
	}

	@Test
	void aFamilyThatBoundNothingAndDrewNothingHasNoRow() {
		this.tally.bound(new Object(), new Object(), Family.ENTITY);
		this.tally.endFrame();

		List<String> lines = this.tally.lines(1.0);

		assertTrue(lines.stream().anyMatch(line -> line.endsWith("entity")), lines.toString());
		assertTrue(lines.stream().noneMatch(line -> line.endsWith("far terrain")), lines.toString());
	}

	@Test
	void theFarTerrainLineIsLeftOutWhereNoFarPassRan() {
		this.tally.endFrame();

		assertTrue(this.tally.lines(1.0).stream().noneMatch(line -> line.startsWith("  far terrain")));
	}

	@Test
	void clearingEmptiesTheWindow() {
		this.tally.bound(new Object(), new Object(), Family.ENTITY);
		this.tally.pushed(false);
		this.tally.endFrame();

		this.tally.clear();

		assertEquals(0, this.tally.frames());
		assertEquals(0, this.tally.binds(Family.ENTITY));
		assertTrue(this.tally.lines(1.0).isEmpty());
	}

	// -- the hooks -----------------------------------------------------------------------------

	@Test
	void theHooksCountNothingWhileTheSwitchIsOff() {
		assumeFalse(PassTimings.enabled(), "the switch is on for this run, which is what is being tested");

		FrameTally live = FrameCensus.tally();
		long before = live.binds(Family.OTHER) + live.draws(Family.OTHER) + live.frames();

		FrameCensus.bind(new Object(), new Object(), null);
		FrameCensus.draw(new Object());
		FrameCensus.push(false);
		FrameCensus.descriptor();
		FrameCensus.rotated();
		FrameCensus.geometryBlockWritten(new FrameCensus.Block());
		FrameCensus.endFrame();

		assertEquals(before, live.binds(Family.OTHER) + live.draws(Family.OTHER) + live.frames());
	}
}
