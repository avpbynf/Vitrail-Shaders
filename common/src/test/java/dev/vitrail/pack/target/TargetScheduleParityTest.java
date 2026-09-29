package dev.vitrail.pack.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.source.ShaderProperties.FlipDirective;
import dev.vitrail.pack.target.ParityModel.Chain;
import dev.vitrail.pack.target.ParityModel.Expected;
import dev.vitrail.pack.target.ParityModel.Half;
import dev.vitrail.pack.target.ParityModel.Pass;
import dev.vitrail.pack.target.ParityModel.Row;
import dev.vitrail.pack.target.ParityModel.Shape;
import dev.vitrail.pack.target.ParityModel.Stage;
import dev.vitrail.pack.target.TargetSchedule.Bound;
import dev.vitrail.pack.target.TargetSchedule.Side;
import dev.vitrail.pack.target.TargetSchedule.Step;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Holds the schedule to the one sentence the frame documents for every colour target: a read lands
 * on the half produced by the last write before it, and on the primary half if there was none.
 * <p>
 * A wrong parity looks like a correct image, so nothing in a running game would fail if the walk
 * were off by one pass. What stands in for the image here is {@link ParityModel}, a reading of the
 * same sentence that shares no code with {@link TargetSchedule}, run pass by pass over hand built
 * chains whose answers are worked out on paper in the comments, and over seeded random ones.
 */
class TargetScheduleParityTest {

	// Hand built chains first, then the reference against the schedule, then the rules one at a time.

	private static Step geometry(String program, Integer... writes) {
		return new Step(program, List.of(writes), false);
	}

	private static Step screen(String program, Integer... writes) {
		return new Step(program, List.of(writes), true);
	}

	private static FlipDirective flip(String program, String buffer, boolean value) {
		return new FlipDirective(program, buffer, value);
	}

	private static Set<Integer> set(Integer... indices) {
		return new TreeSet<>(Arrays.asList(indices));
	}

	private static Bound bound(TargetSchedule schedule, String program) {
		return schedule.step(program).orElseThrow();
	}

	/**
	 * A chain shaped like BSL's: the world's gbuffers, one deferred, five composites and a final,
	 * colortex4 being the temporal history that composite3 reads and writes in the same pass.
	 * <p>
	 * Worked out by hand, M for the primary half and A for the alternate, every target starting on
	 * M and a full screen pass writing the half it does not read:
	 * <pre>
	 *                    reads                   writes
	 * gbuffers_*         0:M 1:M 2:M             0:M 1:M 2:M   (geometry paints over what it reads)
	 * deferred           0:M 3:M                 0:A 3:A
	 * composite          0:A 1:M 3:A             0:M 3:M
	 * composite1         0:M 3:M                 0:A
	 * composite2         0:A                     0:M
	 * composite3         0:M 4:M                 0:A 4:A
	 * composite4         0:A                     0:M
	 * final              0:M 3:M 4:A
	 * </pre>
	 * So colortex4, the history, ends the frame on A and is the one target the frame has to swap
	 * back; colortex1 and colortex2 are only ever painted by geometry and are never doubled.
	 */
	@Test
	void handWorkedBslChainLandsWhereTheSentenceSays() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				geometry("gbuffers_basic", 0, 1),
				geometry("gbuffers_terrain", 0, 1, 2),
				geometry("gbuffers_water", 0, 1, 2),
				geometry("gbuffers_hand", 0, 1, 2),
				screen("deferred", 0, 3),
				screen("composite", 0, 3),
				screen("composite1", 0),
				screen("composite2", 0),
				screen("composite3", 0, 4),
				screen("composite4", 0),
				screen("final")), List.of());

		assertEquals(set(), bound(schedule, "gbuffers_terrain").readsAlt());
		assertEquals(set(), bound(schedule, "gbuffers_terrain").writesAlt());

		Bound deferred = bound(schedule, "deferred");
		assertEquals(Side.MAIN, deferred.read(0));
		assertEquals(Side.MAIN, deferred.read(3));
		assertEquals(Side.ALT, deferred.write(0));
		assertEquals(Side.ALT, deferred.write(3));

		Bound composite = bound(schedule, "composite");
		assertEquals(Side.ALT, composite.read(0));
		assertEquals(Side.MAIN, composite.read(1));
		assertEquals(Side.ALT, composite.read(3));
		assertEquals(Side.MAIN, composite.write(0));
		assertEquals(Side.MAIN, composite.write(3));

		Bound first = bound(schedule, "composite1");
		assertEquals(Side.MAIN, first.read(0));
		assertEquals(Side.MAIN, first.read(3));
		assertEquals(Side.ALT, first.write(0));

		Bound second = bound(schedule, "composite2");
		assertEquals(Side.ALT, second.read(0));
		assertEquals(Side.MAIN, second.write(0));

		Bound third = bound(schedule, "composite3");
		assertEquals(Side.MAIN, third.read(0));
		assertEquals(Side.MAIN, third.read(4));
		assertEquals(Side.ALT, third.write(0));
		assertEquals(Side.ALT, third.write(4));

		Bound fourth = bound(schedule, "composite4");
		assertEquals(Side.ALT, fourth.read(0));
		assertEquals(Side.MAIN, fourth.write(0));

		Bound last = bound(schedule, "final");
		assertEquals(Side.MAIN, last.read(0));
		assertEquals(Side.MAIN, last.read(3));
		assertEquals(Side.ALT, last.read(4));

		assertEquals(set(4), schedule.flippedAtEnd());
		assertEquals(set(0, 3, 4), schedule.doubled());
	}

	/**
	 * The translucent chunk pass is drawn after the deferred stage, on the halves that stage left.
	 * <p>
	 * Worked out on the chain above. The world's gbuffers write 0, 1 and 2 on M, and the one deferred
	 * pass turned 0 and 3 over once, so gbuffers_water drawn after it writes colortex0 on A and
	 * colortex1 and colortex2 on M, which is what the frame documents: the pre-deferred side flipped
	 * once per deferred pass that wrote the target.
	 */
	@Test
	void translucentGeometryStandsOnTheHalvesTheDeferredStageLeft() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				geometry("gbuffers_terrain", 0, 1, 2),
				geometry("gbuffers_water", 0, 1, 2),
				screen("deferred", 0, 3),
				screen("composite", 0, 3),
				screen("final")), List.of());

		Bound before = bound(schedule, "gbuffers_water");
		assertEquals(Side.MAIN, before.write(0));

		Bound after = schedule.stepAfterDeferred("gbuffers_water").orElseThrow();
		assertEquals(Side.ALT, after.read(0));
		assertEquals(Side.ALT, after.write(0));
		assertEquals(Side.MAIN, after.read(1));
		assertEquals(Side.MAIN, after.write(1));
		assertEquals(Side.MAIN, after.write(2));
		assertEquals(Side.ALT, after.read(3));
		assertFalse(after.fullscreen());
	}

	/** Two deferred passes writing one target turn it over twice, so the water is back on M. */
	@Test
	void twoDeferredWritersLeaveTheTranslucentPassWhereItStarted() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				geometry("gbuffers_water", 0),
				screen("deferred", 0),
				screen("deferred1", 0),
				screen("composite", 0)), List.of());

		// gbuffers_water: M. deferred: r M w A. deferred1: r A w M. So the snapshot has colortex0 on M,
		// the water is drawn there, and composite reads what deferred1 wrote.
		assertEquals(Side.MAIN, schedule.stepAfterDeferred("gbuffers_water").orElseThrow().write(0));
		assertEquals(Side.MAIN, bound(schedule, "composite").read(0));
	}

	/** Only a geometry pass can be taken again after the deferreds: a full screen pass is a flip itself. */
	@Test
	void aFullScreenPassCannotBeReTakenAfterTheDeferreds() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				geometry("gbuffers_water", 0),
				screen("deferred", 0),
				screen("composite", 0)), List.of());

		assertEquals(Optional.empty(), schedule.stepAfterDeferred("composite"));
		assertEquals(Optional.empty(), schedule.stepAfterDeferred("nothing_here"));
	}

	// ------------------------------------------------------------------------------------------
	// The pack's own flip directives, each worked out on the smallest chain that shows it
	// ------------------------------------------------------------------------------------------

	/** Three passes over colortex0 and the final, with the pack's lines in front. */
	private static TargetSchedule threeOverZero(FlipDirective... directives) {
		return TargetSchedule.of(List.of(
				screen("composite", 0),
				screen("composite1", 0),
				screen("composite2", 0),
				screen("final")), List.of(directives));
	}

	/**
	 * Without a directive: composite reads M writes A, composite1 reads A writes M, composite2 reads
	 * M writes A, and the final reads A. The frame ends with colortex0 on A.
	 */
	@Test
	void withoutDirectivesEveryPassAlternates() {
		TargetSchedule schedule = threeOverZero();

		assertEquals(Side.MAIN, bound(schedule, "composite").read(0));
		assertEquals(Side.ALT, bound(schedule, "composite1").read(0));
		assertEquals(Side.MAIN, bound(schedule, "composite2").read(0));
		assertEquals(Side.ALT, bound(schedule, "final").read(0));
		assertEquals(set(0), schedule.flippedAtEnd());
	}

	/**
	 * {@code flip.composite1.colortex0=false}: composite1 still writes the half it does not read, but
	 * the target is not turned over afterwards, so composite2 reads what composite1 read.
	 * <p>
	 * composite: r M w A. composite1: r A w M, not turned over, still A. composite2: r A w M, turned
	 * over to M. The final reads M and the frame ends with nothing flipped.
	 */
	@Test
	void aFalseDirectiveKeepsATargetWhereItWas() {
		TargetSchedule schedule = threeOverZero(flip("composite1", "colortex0", false));

		Bound second = bound(schedule, "composite1");
		assertEquals(Side.ALT, second.read(0));
		assertEquals(Side.MAIN, second.write(0));
		assertEquals(Side.ALT, bound(schedule, "composite2").read(0));
		assertEquals(Side.MAIN, bound(schedule, "final").read(0));
		assertEquals(set(), schedule.flippedAtEnd());
	}

	/**
	 * {@code flip.composite1.colortex0=true} on a target the pass also writes is a second turn on
	 * top of its own, which leaves the target where it was: the same answer as the false line.
	 */
	@Test
	void aTrueDirectiveOnAWrittenTargetTurnsItOverTwice() {
		TargetSchedule schedule = threeOverZero(flip("composite1", "colortex0", true));
		TargetSchedule kept = threeOverZero(flip("composite1", "colortex0", false));

		for (String program : List.of("composite", "composite1", "composite2", "final")) {
			assertEquals(bound(kept, program).readsAlt(), bound(schedule, program).readsAlt(), program);
			assertEquals(bound(kept, program).writesAlt(), bound(schedule, program).writesAlt(), program);
		}

		assertEquals(kept.flippedAtEnd(), schedule.flippedAtEnd());
	}

	/**
	 * {@code flip.composite1.colortex5=true} turns over a target nothing writes. Every read of it
	 * from there on lands on the far half, so that half has to exist: colortex5 is doubled although
	 * no pass writes it, and the frame ends with it flipped.
	 */
	@Test
	void aTrueDirectiveOnAnUnwrittenTargetDoublesIt() {
		TargetSchedule schedule = threeOverZero(flip("composite1", "colortex5", true));

		assertEquals(Side.MAIN, bound(schedule, "composite1").read(5));
		assertEquals(Side.ALT, bound(schedule, "composite2").read(5));
		assertEquals(Side.ALT, bound(schedule, "final").read(5));
		assertEquals(set(0, 5), schedule.doubled());
		assertEquals(set(0, 5), schedule.flippedAtEnd());
	}

	/**
	 * A false line names a target and turns nothing over, so a target no pass writes is not doubled by
	 * it: the second half only exists for a target that is written by a full screen pass or turned over.
	 * The frame's own wording, "or when a flip directive names it", is looser than that.
	 */
	@Test
	void aFalseDirectiveOnAnUnwrittenTargetDoublesNothing() {
		TargetSchedule schedule = threeOverZero(flip("composite1", "colortex5", false));

		assertEquals(set(0), schedule.doubled());
		assertEquals(set(0), schedule.flippedAtEnd());
		assertEquals(Side.MAIN, bound(schedule, "final").read(5));
	}

	/**
	 * A flip can name a target that nothing of the place writes or samples, so nothing allocates it: the
	 * schedule doubles it all the same, and it is the renderer's to say that no texture exists for
	 * either half. Pinned so that whoever reads the doubled set does not take it for the allocated one.
	 */
	@Test
	void theDoubledSetIsNotTheAllocatedSet() {
		TargetSchedule schedule = TargetSchedule.of(List.of(screen("composite", 0)),
				List.of(flip("deferred_pre", "colortex6", true)));

		assertEquals(set(0, 6), schedule.doubled());
	}

	/** A legacy name resolves to its index: {@code gaux1} is colortex4. */
	@Test
	void aLegacyNameInADirectiveNamesItsTarget() {
		TargetSchedule schedule = threeOverZero(flip("composite1", "gaux1", true));

		assertEquals(Side.ALT, bound(schedule, "composite2").read(4));
		assertEquals(set(0, 4), schedule.doubled());
	}

	/** A buffer that is no colour target names nothing, and neither does a program not in the chain. */
	@Test
	void directivesThatNameNothingChangeNothing() {
		TargetSchedule plain = threeOverZero();
		TargetSchedule noise = threeOverZero(
				flip("composite1", "foo", true),
				flip("composite1", "colortex32", true),
				flip("composite9", "colortex0", true),
				flip("shadowcomp_pre", "colortex3", true),
				flip("final_pre", "colortex3", true));

		assertEquals(plain.doubled(), noise.doubled());
		assertEquals(plain.flippedAtEnd(), noise.flippedAtEnd());
		for (String program : List.of("composite", "composite1", "composite2", "final")) {
			assertEquals(bound(plain, program).readsAlt(), bound(noise, program).readsAlt(), program);
		}
	}

	/** A geometry pass takes no part in the walk, so a line naming one does nothing. */
	@Test
	void aDirectiveOnAGeometryPassIsIgnored() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				geometry("gbuffers_terrain", 1),
				screen("composite", 0),
				screen("final")), List.of(flip("gbuffers_terrain", "colortex1", true)));

		assertEquals(set(0), schedule.doubled());
		assertEquals(Side.MAIN, bound(schedule, "final").read(1));
	}

	/** Said twice for one program and target, the last line stands. */
	@Test
	void theLastLineForATargetStands() {
		TargetSchedule offThenOn = threeOverZero(
				flip("composite1", "colortex0", false), flip("composite1", "colortex0", true));
		TargetSchedule onThenOff = threeOverZero(
				flip("composite1", "colortex0", true), flip("composite1", "colortex0", false));
		TargetSchedule plain = threeOverZero();

		// off then on is a written target turned over twice, on then off is a kept one: both leave
		// the target on A after composite1, where a plain chain would have it on M.
		assertEquals(Side.ALT, bound(offThenOn, "composite2").read(0));
		assertEquals(Side.ALT, bound(onThenOff, "composite2").read(0));
		assertEquals(Side.MAIN, bound(plain, "composite2").read(0));
	}

	/**
	 * {@code flip.deferred_pre.colortex6=true} belongs to no program: it is played when the walk
	 * first reaches the deferred stage. composite, the first pass past it, reads colortex6 on A, and
	 * so does the snapshot the world's translucents are drawn on.
	 */
	@Test
	void aStageOpeningDirectiveIsPlayedWhenTheStageIsReached() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				geometry("gbuffers_water", 6),
				screen("composite", 0),
				screen("final")), List.of(flip("deferred_pre", "colortex6", true)));

		assertEquals(Side.MAIN, bound(schedule, "gbuffers_water").read(6));
		assertEquals(Side.ALT, schedule.stepAfterDeferred("gbuffers_water").orElseThrow().read(6));
		assertEquals(Side.ALT, bound(schedule, "composite").read(6));
		assertEquals(set(0, 6), schedule.doubled());
	}

	/**
	 * The composite stage opens after the translucents' snapshot is taken: a line for
	 * {@code composite_pre} moves what the composites read and not what the water was drawn on.
	 */
	@Test
	void theCompositeOpeningComesAfterTheDeferredSnapshot() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				geometry("gbuffers_water", 6),
				screen("deferred", 0),
				screen("composite", 0),
				screen("final")), List.of(flip("composite_pre", "colortex6", true)));

		assertEquals(Side.MAIN, schedule.stepAfterDeferred("gbuffers_water").orElseThrow().read(6));
		assertEquals(Side.MAIN, bound(schedule, "deferred").read(6));
		assertEquals(Side.ALT, bound(schedule, "composite").read(6));
	}

	/**
	 * A stage the place ships nothing for still opens, and in its own place in the frame: a pack with
	 * only a geometry pass and composites has its prepare opening played before the geometry.
	 */
	@Test
	void anEmptyStageStillOpensAtItsPlace() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				geometry("gbuffers_terrain", 3),
				screen("composite", 0)), List.of(
						flip("begin_pre", "colortex1", true),
						flip("prepare_pre", "colortex3", true)));

		assertEquals(Side.ALT, bound(schedule, "gbuffers_terrain").read(1));
		assertEquals(Side.ALT, bound(schedule, "gbuffers_terrain").read(3));
		assertEquals(Side.ALT, bound(schedule, "gbuffers_terrain").write(3));
	}

	/**
	 * With no composite the composite stage still opens, ahead of the first thing past it, which is
	 * the final: a line for {@code composite_pre} is what the final reads, and the snapshot the
	 * translucents stand on has been taken before it.
	 */
	@Test
	void theCompositeStageOpensAtTheFinalWhenNoCompositeRan() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				geometry("gbuffers_water", 6),
				screen("deferred", 0),
				screen("final")), List.of(flip("composite_pre", "colortex6", true)));

		assertEquals(Side.MAIN, schedule.stepAfterDeferred("gbuffers_water").orElseThrow().read(6));
		assertEquals(Side.MAIN, bound(schedule, "deferred").read(6));
		assertEquals(Side.ALT, bound(schedule, "final").read(6));
		assertEquals(Side.ALT, bound(schedule, "final").read(0));
		assertEquals(set(0, 6), schedule.flippedAtEnd());
	}

	/** Even a place that runs nothing at all plays its stage openings, and doubles what they turn. */
	@Test
	void anEmptyChainStillPlaysTheOpenings() {
		TargetSchedule schedule = TargetSchedule.of(List.of(),
				List.of(flip("composite_pre", "colortex2", true), flip("deferred_pre", "colortex3", false)));

		assertEquals(List.of(), schedule.steps());
		assertEquals(set(2), schedule.doubled());
		assertEquals(set(2), schedule.flippedAtEnd());
	}

	/**
	 * A compute whose program draws nothing stands where its name falls, on the halves at that
	 * moment. composite1 has no pass here: composite wrote colortex0 on A, so what composite1's
	 * computes read is A, and composite2 reads the same half, since the compute turns nothing over.
	 */
	@Test
	void aProgramThatDrawsNothingSeesTheHalvesStandingAtItsMoment() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				screen("composite", 0),
				screen("composite2", 0),
				screen("final")), List.of(), List.of("composite1"));

		Bound standing = schedule.passing("composite1").orElseThrow();
		assertEquals(Side.ALT, standing.read(0));
		assertEquals(Side.ALT, bound(schedule, "composite2").read(0));
		assertEquals(List.of("composite", "composite2", "final"),
				schedule.steps().stream().map(Bound::program).toList());
		assertEquals(Optional.empty(), schedule.passing("composite2"));
	}

	// ------------------------------------------------------------------------------------------
	// The reference against the schedule
	// ------------------------------------------------------------------------------------------

	private static void assertMatchesReference(Chain chain, String label) {
		List<Pass> walked = ParityModel.walked(chain);
		TargetSchedule schedule = TargetSchedule.of(ParityModel.steps(walked),
				ParityModel.directives(chain.flips()), ParityModel.passing(walked));

		assertScheduleMatches(schedule, ParityModel.interpret(walked, chain.flips()), label);
	}

	/** Every read, write, set and snapshot of a schedule against the reference's answer for its chain. */
	static void assertScheduleMatches(TargetSchedule schedule, Expected expected, String label) {

		assertEquals(expected.rows().size(), schedule.steps().size(), label + " step count");
		for (int at = 0; at < expected.rows().size(); at++) {
			Row row = expected.rows().get(at);
			Bound step = schedule.steps().get(at);
			String where = label + " at " + row.pass().program();

			assertEquals(row.pass().program(), step.program(), where);
			assertEquals(row.pass().writes(), step.writes(), where);
			assertEquals(row.pass().fullscreen(), step.fullscreen(), where);

			Set<Integer> readsAlt = new TreeSet<>();
			for (int target = 0; target < ParityModel.UNIVERSE; target++) {
				assertEquals(row.reads().of(target).side(), step.read(target),
						where + " reads colortex" + target);
				if (row.reads().of(target) == Half.ALTERNATE) {
					readsAlt.add(target);
				}
			}

			assertEquals(readsAlt, step.readsAlt(), where);

			Set<Integer> writesAlt = new TreeSet<>();
			for (int target : row.pass().writes()) {
				assertEquals(row.writes().get(target).side(), step.write(target),
						where + " writes colortex" + target);
				if (row.writes().get(target) == Half.ALTERNATE) {
					writesAlt.add(target);
				}
			}

			assertEquals(writesAlt, step.writesAlt(), where);
		}

		for (var passing : expected.passing().entrySet()) {
			Bound standing = schedule.passing(passing.getKey()).orElseThrow();
			for (int target = 0; target < ParityModel.UNIVERSE; target++) {
				assertEquals(passing.getValue().of(target).side(), standing.read(target),
						label + " compute-only " + passing.getKey() + " reads colortex" + target);
			}

			assertEquals(Optional.empty(), schedule.step(passing.getKey()), label);
		}

		assertEquals(expected.doubled(), schedule.doubled(), label + " doubled");
		assertEquals(expected.flippedAtEnd(), schedule.flippedAtEnd(), label + " flipped at end");

		for (Row row : expected.rows()) {
			if (row.pass().fullscreen()) {
				assertEquals(Optional.empty(), schedule.stepAfterDeferred(row.pass().program()), label);
				continue;
			}

			Bound after = schedule.stepAfterDeferred(row.pass().program()).orElseThrow();
			for (int target = 0; target < ParityModel.UNIVERSE; target++) {
				assertEquals(expected.afterDeferred().of(target).side(), after.read(target),
						label + " " + row.pass().program() + " after the deferreds reads colortex" + target);
			}

			for (int target : row.pass().writes()) {
				assertEquals(expected.afterDeferred().of(target).side(), after.write(target),
						label + " " + row.pass().program() + " after the deferreds writes colortex" + target);
			}
		}
	}

	/**
	 * Without a pack directive the reference and the schedule are not the only two witnesses of the
	 * sentence: the schedule's own answers can be replayed against it directly. Writes are taken from
	 * what the schedule says, reads from what it says, and a read must land on the half the last write
	 * before it left, or on the primary half when there was none. A full screen pass may never read the
	 * half it writes, and a geometry pass always does.
	 */
	private static void assertSentenceHolds(Chain chain, String label) {
		List<Pass> walked = ParityModel.walked(chain);
		TargetSchedule schedule = TargetSchedule.of(ParityModel.steps(walked), List.of(),
				ParityModel.passing(walked));

		Side[] landed = new Side[ParityModel.UNIVERSE];
		Arrays.fill(landed, Side.MAIN);
		boolean checkedAfterDeferred = false;
		for (Pass pass : walked) {
			if (!checkedAfterDeferred && pass.stage().compareTo(Stage.DEFERRED) > 0) {
				assertTranslucentsStandOnLastWrites(walked, schedule, landed, label);
				checkedAfterDeferred = true;
			}

			if (pass.computeOnly()) {
				Bound standing = schedule.passing(pass.program()).orElseThrow();
				for (int target = 0; target < ParityModel.UNIVERSE; target++) {
					assertEquals(landed[target], standing.read(target),
							label + " compute-only " + pass.program() + " reads colortex" + target);
				}

				continue;
			}

			Bound step = bound(schedule, pass.program());
			for (int target = 0; target < ParityModel.UNIVERSE; target++) {
				assertEquals(landed[target], step.read(target),
						label + " " + pass.program() + " reads colortex" + target);
			}

			for (int target : pass.writes()) {
				if (pass.fullscreen()) {
					assertNotEquals(step.read(target), step.write(target),
							label + " " + pass.program() + " reads the half it writes: colortex" + target);
				} else {
					assertEquals(step.read(target), step.write(target),
							label + " " + pass.program() + " paints over a half it does not read: colortex"
									+ target);
				}

				landed[target] = step.write(target);
			}
		}

		if (!checkedAfterDeferred) {
			assertTranslucentsStandOnLastWrites(walked, schedule, landed, label);
		}

		Set<Integer> flipped = new TreeSet<>();
		for (int target = 0; target < ParityModel.UNIVERSE; target++) {
			if (landed[target] == Side.ALT) {
				flipped.add(target);
			}
		}

		assertEquals(flipped, schedule.flippedAtEnd(), label + " flipped at end");
	}

	private static void assertTranslucentsStandOnLastWrites(List<Pass> walked, TargetSchedule schedule,
			Side[] landed, String label) {
		for (Pass pass : walked) {
			if (pass.fullscreen() || pass.computeOnly()) {
				continue;
			}

			Bound after = schedule.stepAfterDeferred(pass.program()).orElseThrow();
			for (int target = 0; target < ParityModel.UNIVERSE; target++) {
				assertEquals(landed[target], after.read(target),
						label + " " + pass.program() + " drawn after the deferreds reads colortex" + target);
			}

			for (int target : pass.writes()) {
				assertEquals(landed[target], after.write(target), label + " " + pass.program()
						+ " drawn after the deferreds writes colortex" + target);
			}
		}
	}

	/**
	 * The count of flips, which is what the frame documents for a translucent geometry pass: it
	 * writes the pre-deferred side flipped once per deferred pass that wrote the target. The simpler
	 * rule, that the two sides are the same, is false as soon as one deferred pass writes the target.
	 */
	private static void assertTranslucentIsPreDeferredFlippedOncePerDeferredWriter(Chain chain,
			String label) {
		List<Pass> walked = ParityModel.walked(chain);
		TargetSchedule schedule = TargetSchedule.of(ParityModel.steps(walked), List.of(),
				ParityModel.passing(walked));

		for (Pass pass : walked) {
			if (pass.fullscreen() || pass.computeOnly()) {
				continue;
			}

			Bound before = bound(schedule, pass.program());
			Bound after = schedule.stepAfterDeferred(pass.program()).orElseThrow();
			for (int target : pass.writes()) {
				long deferredWriters = walked.stream()
						.filter(other -> other.stage() == Stage.DEFERRED && !other.computeOnly())
						.filter(other -> other.writes().contains(target))
						.count();
				Side expected = deferredWriters % 2 == 0 ? before.write(target) : opposite(before.write(target));

				assertEquals(expected, after.write(target), label + " " + pass.program() + " colortex"
						+ target + " after " + deferredWriters + " deferred writers");
			}
		}
	}

	private static Side opposite(Side side) {
		return side == Side.MAIN ? Side.ALT : Side.MAIN;
	}

	private static final Shape NO_DIRECTIVES = new Shape(3, 30, 8, false, true);

	@Test
	void everyReadLandsOnTheHalfOfTheLastWriteBeforeIt() {
		for (long seed = 0; seed < 2500; seed++) {
			assertSentenceHolds(ParityModel.random(seed, NO_DIRECTIVES), "seed " + seed);
		}
	}

	@Test
	void everyReadLandsOnTheHalfOfTheLastWriteInPacksTheSizeOfRealOnes() {
		Shape real = new Shape(40, 150, 16, false, false);
		for (long seed = 10_000; seed < 10_150; seed++) {
			assertSentenceHolds(ParityModel.random(seed, real), "seed " + seed);
		}
	}

	@Test
	void theTranslucentPassIsTheEarlierSideFlippedOncePerDeferredWriter() {
		for (long seed = 20_000; seed < 22_000; seed++) {
			assertTranslucentIsPreDeferredFlippedOncePerDeferredWriter(
					ParityModel.random(seed, NO_DIRECTIVES), "seed " + seed);
		}
	}

	@Test
	void theScheduleAgreesWithTheReferenceWithoutDirectives() {
		for (long seed = 30_000; seed < 32_500; seed++) {
			assertMatchesReference(ParityModel.random(seed, NO_DIRECTIVES), "seed " + seed);
		}
	}

	@Test
	void theScheduleAgreesWithTheReferenceWithDirectivesAndComputesAlone() {
		for (long seed = 40_000; seed < 43_000; seed++) {
			assertMatchesReference(ParityModel.random(seed, Shape.SMALL), "seed " + seed);
		}
	}

	@Test
	void theScheduleAgreesWithTheReferenceInPacksTheSizeOfRealOnes() {
		for (long seed = 50_000; seed < 50_400; seed++) {
			assertMatchesReference(ParityModel.random(seed, Shape.REAL), "seed " + seed);
		}
	}

	/** The passes the pack or the user cuts are never in the walk, so the later ones move a half. */
	@Test
	void theScheduleAgreesWithTheReferenceOnceSomePassesAreCutOut() {
		for (long seed = 60_000; seed < 61_500; seed++) {
			Chain chain = ParityModel.withCuts(ParityModel.random(seed, Shape.SMALL), seed);
			assertMatchesReference(chain, "seed " + seed);
		}
	}

	/** The shapes above must actually reach the branches they are there for. */
	@Test
	void theSeededChainsReachEveryBranchOfTheWalk() {
		int falseLines = 0;
		int trueLines = 0;
		int stageOpenings = 0;
		int computeOnly = 0;
		int unwrittenFlips = 0;
		int deferredWithWriters = 0;
		int noFinal = 0;
		for (long seed = 40_000; seed < 43_000; seed++) {
			Chain chain = ParityModel.random(seed, Shape.SMALL);
			for (ParityModel.Flip flip : chain.flips()) {
				if (flip.program().endsWith("_pre")) {
					stageOpenings += flip.value() ? 1 : 0;
				} else if (flip.value()) {
					trueLines++;
				} else {
					falseLines++;
				}
			}

			computeOnly += (int) chain.passes().stream().filter(Pass::computeOnly).count();
			deferredWithWriters += (int) chain.passes().stream()
					.filter(pass -> pass.stage() == Stage.DEFERRED && !pass.computeOnly()).count();
			noFinal += chain.passes().stream().anyMatch(pass -> pass.stage() == Stage.FINAL) ? 0 : 1;
			for (ParityModel.Flip flip : chain.flips()) {
				boolean written = chain.passes().stream()
						.anyMatch(pass -> pass.program().equals(flip.program())
								&& pass.writes().contains(flip.target()));
				if (flip.value() && !flip.program().endsWith("_pre") && !written) {
					unwrittenFlips++;
				}
			}
		}

		assertTrue(falseLines > 100, "false lines: " + falseLines);
		assertTrue(trueLines > 100, "true lines: " + trueLines);
		assertTrue(stageOpenings > 100, "stage openings: " + stageOpenings);
		assertTrue(computeOnly > 100, "compute only programs: " + computeOnly);
		assertTrue(unwrittenFlips > 50, "flips of a target the pass does not write: " + unwrittenFlips);
		assertTrue(deferredWithWriters > 500, "deferred passes: " + deferredWithWriters);
		assertTrue(noFinal > 100, "chains without a final: " + noFinal);
	}

	// ------------------------------------------------------------------------------------------
	// The sets the schedule hands out
	// ------------------------------------------------------------------------------------------

	/** The sets end up in a log, and an index that moves between two runs reads as a difference. */
	@Test
	void setsAreHandedOutSortedWhateverOrderTheyWereBuiltIn() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				screen("composite", 9, 2, 5),
				screen("composite1", 7, 3)), List.of(flip("composite1", "colortex1", true)));

		assertEquals(List.of(1, 2, 3, 5, 7, 9), new ArrayList<>(schedule.doubled()));
		assertEquals(List.of(1, 2, 3, 5, 7, 9), new ArrayList<>(schedule.flippedAtEnd()));
	}

	/**
	 * A target a program writes twice in its own list is turned over once per mention by the
	 * remove-else-add, which no chain of a real pack does and the plan refuses further up. Pinned as
	 * it is so that nobody mistakes the schedule for the place that refuses it.
	 */
	@Test
	void aTargetNamedTwiceInOneListIsTurnedOverTwice() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				screen("composite", 0, 0),
				screen("final")), List.of());

		assertEquals(set(), schedule.flippedAtEnd());
		assertEquals(set(0), schedule.doubled());
	}

	/** {@code doubledFor} is the answer restricted to a few programs, kept for the frozen figures. */
	@Test
	void doubledForCountsOnlyTheNamedFullScreenPassesAndTheirForcedTargets() {
		TargetSchedule schedule = TargetSchedule.of(List.of(
				geometry("gbuffers_terrain", 1),
				screen("deferred", 3),
				screen("composite", 0),
				screen("composite1", 0, 4)),
				List.of(flip("composite", "colortex6", true), flip("composite1", "colortex7", false)));

		assertEquals(set(0, 6), schedule.doubledFor(Set.of("composite")));
		assertEquals(set(0, 4), schedule.doubledFor(Set.of("world0/composite1")));
		assertEquals(set(), schedule.doubledFor(Set.of("gbuffers_terrain")));
		assertEquals(set(0, 3, 4, 6), schedule.doubledFor(Set.of("deferred", "composite", "composite1")));
	}
}
