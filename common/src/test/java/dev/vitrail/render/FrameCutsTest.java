package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.ProgramNames;
import dev.vitrail.pack.program.ProgramResolver;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.render.FrameCuts.Cut;
import dev.vitrail.render.FrameCuts.Reach;
import dev.vitrail.render.FrameCuts.Standalone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds which cut of the frame a program belongs to, where the chain's list is cut, and which of a
 * cut's standalone computes a step of the walk takes.
 * <p>
 * A compute of a program nothing draws is placed by the cut its FAMILY names and never by an index
 * in the list, and the plan counts the same three boundaries off the same ranks. Two answers to
 * either produce no error, only a compute that runs in the wrong moment of the frame, so the
 * plans here are the real ones, read out of small packs written for the purpose.
 */
class FrameCutsTest {

	private static final String VERTEX = """
			#version 120
			varying vec2 texcoord;
			void main() {
				gl_Position = ftransform();
				texcoord = gl_MultiTexCoord0.xy;
			}
			""";

	private static final String FRAGMENT = """
			#version 120
			/* DRAWBUFFERS:0 */
			uniform sampler2D colortex0;
			varying vec2 texcoord;
			void main() {
				gl_FragData[0] = texture2D(colortex0, texcoord);
			}
			""";

	@TempDir
	Path directory;

	/** The chain of a pack that ships exactly those programs, each with both halves. */
	private ChainPlan planOf(String... programs) throws IOException {
		Path shaders = Files.createDirectories(this.directory.resolve("shaders"));
		for (String program : programs) {
			Files.writeString(shaders.resolve(program + ".vsh"), VERTEX);
			Files.writeString(shaders.resolve(program + ".fsh"), FRAGMENT);
		}

		try (OpenedPack pack = OpenedPack.open(this.directory, Map.of(), "")) {
			TargetPlan targets = TargetPlan.build(pack.source(), pack.options(), pack.settings(),
					pack.properties(), "world0");
			DimensionSet dimensions = DimensionSet.discover(pack.source());
			ProgramSet sets = ProgramSet.enumerate(pack.source(), dimensions);

			return ChainPlan.of(targets, ProgramResolver.resolve(sets, dimensions, Set.of()));
		}
	}

	private static List<String> names(List<ChainPlan.Pass> passes) {
		return passes.stream().map(ChainPlan.Pass::program).toList();
	}

	@Test
	void keepsTheChainInFrameOrderWithTheFinalLast() throws IOException {
		ChainPlan plan = planOf("composite1", "final", "begin", "deferred", "composite", "prepare");

		assertEquals(List.of("begin", "prepare", "deferred", "composite", "composite1", "final"),
				names(FrameCuts.ordered(plan)));
		assertEquals(List.of("begin", "prepare", "deferred", "composite", "composite1"),
				names(plan.passes()), "the plan's own list leaves the final out");
	}

	@Test
	void endsOnTheLastCompositeWhereThePackShipsNoFinal() throws IOException {
		ChainPlan plan = planOf("composite", "composite2");

		assertTrue(plan.last().isEmpty());
		assertEquals(List.of("composite", "composite2"), names(FrameCuts.ordered(plan)));
	}

	@Test
	void ordersAPlanWithNoPassAtAll() throws IOException {
		ChainPlan plan = planOf("final");

		assertEquals(List.of("final"), names(FrameCuts.ordered(plan)));
	}

	@Test
	void putsEveryPassInTheCutTheBoundariesOfThePlanSayItIsIn() throws IOException {
		ChainPlan plan = planOf("begin", "begin1", "prepare", "deferred", "deferred1", "deferred2",
				"composite", "composite1", "composite2", "final");
		List<ChainPlan.Pass> ordered = FrameCuts.ordered(plan);

		assertEquals(2, plan.beginEnd());
		assertEquals(3, plan.prepareEnd());
		assertEquals(6, plan.deferredEnd());
		for (int at = 0; at < ordered.size(); at++) {
			Cut expected = at < plan.beginEnd() ? Cut.AHEAD_OF_SHADOWS
					: at < plan.prepareEnd() ? Cut.BEFORE_WORLD
					: at < plan.deferredEnd() ? Cut.BEFORE_TRANSLUCENTS
					: Cut.AFTER_TRANSLUCENTS;

			assertEquals(expected, Cut.of(ordered.get(at).program()), ordered.get(at).program());
		}
	}

	@Test
	void placesAProgramByItsFamily() {
		assertEquals(Cut.AHEAD_OF_SHADOWS, Cut.of("begin"));
		assertEquals(Cut.AHEAD_OF_SHADOWS, Cut.of("begin7"));
		assertEquals(Cut.BEFORE_WORLD, Cut.of("shadowcomp"));
		assertEquals(Cut.BEFORE_WORLD, Cut.of("prepare"));
		assertEquals(Cut.BEFORE_WORLD, Cut.of("prepare12"));
		assertEquals(Cut.BEFORE_TRANSLUCENTS, Cut.of("deferred"));
		assertEquals(Cut.BEFORE_TRANSLUCENTS, Cut.of("deferred3"));
		assertEquals(Cut.AFTER_TRANSLUCENTS, Cut.of("composite"));
		assertEquals(Cut.AFTER_TRANSLUCENTS, Cut.of("composite99"));
		assertEquals(Cut.AFTER_TRANSLUCENTS, Cut.of("final"));
	}

	@Test
	void placesAComputeByTheFamilyOfTheProgramItHangsOff() {
		assertEquals(Cut.BEFORE_WORLD, Cut.of("prepare_a"), "Pegasus's prepare compute");
		assertEquals(Cut.BEFORE_WORLD, Cut.of("prepare3_b"));
		assertEquals(Cut.AFTER_TRANSLUCENTS, Cut.of("composite3_a"));
		assertEquals(Cut.BEFORE_TRANSLUCENTS, Cut.of("deferred_c"));
	}

	@Test
	void readsTheFamilyOffTheBareNameAndNotTheFolder() {
		assertEquals(Cut.BEFORE_WORLD, Cut.of("world-1/prepare1"));
		assertEquals(Cut.AFTER_TRANSLUCENTS, Cut.of("world0/composite"));
		assertEquals(Cut.AHEAD_OF_SHADOWS, Cut.of("a/b/begin"));
	}

	@Test
	void placesGeometryWithTheDeferredsAndAnythingUnnamedAfterTheWorld() {
		assertEquals(Cut.BEFORE_TRANSLUCENTS, Cut.of("gbuffers_terrain"));
		assertEquals(Cut.AFTER_TRANSLUCENTS, Cut.of("something_else"), "a name that is no program");
	}

	@Test
	void neverPutsAProgramInAnEarlierCutThanOneThatRunsBeforeIt() {
		List<String> names = new ArrayList<>();
		for (String family : List.of("begin", "shadowcomp", "prepare", "deferred", "composite")) {
			names.add(family);
			for (int slot = 1; slot <= 99; slot += 7) {
				names.add(family + slot);
				names.add(family + slot + "_a");
			}
		}

		names.add("final");
		for (String earlier : names) {
			for (String later : names) {
				if (ProgramNames.before(earlier, later)) {
					assertTrue(Cut.of(earlier).compareTo(Cut.of(later)) <= 0,
							earlier + " runs before " + later);
				}
			}
		}
	}

	@Test
	void takesTheStandalonesOnTheIndexTheWalkHasReached() {
		Standalone waiting = standalone(3);

		assertTrue(Reach.AT_INDEX.takes(waiting, 3));
		assertFalse(Reach.AT_INDEX.takes(waiting, 2));
		assertFalse(Reach.AT_INDEX.takes(waiting, 4));
	}

	@Test
	void takesEveryStandaloneAtOrPastTheEndOfTheRange() {
		Standalone waiting = standalone(3);

		assertTrue(Reach.PAST_END.takes(waiting, 3));
		assertTrue(Reach.PAST_END.takes(waiting, 2));
		assertTrue(Reach.PAST_END.takes(waiting, 0));
		assertFalse(Reach.PAST_END.takes(waiting, 4));
	}

	@Test
	void reachesEachStandaloneOnceWhateverTheRangeIs() {
		for (int size = 0; size <= 5; size++) {
			for (int at = 0; at <= size; at++) {
				Standalone waiting = standalone(at);
				int taken = 0;
				for (int index = 0; index < size; index++) {
					taken += Reach.AT_INDEX.takes(waiting, index) ? 1 : 0;
				}

				taken += Reach.PAST_END.takes(waiting, size) ? 1 : 0;
				assertEquals(1, taken, "at " + at + " in a range of " + size);
			}
		}
	}

	@Test
	void standsAComputeAfterTheLastPassThatSortsBeforeIt() {
		List<String> drawn = List.of("composite", "composite1", "composite2", "composite4");
		TargetSchedule schedule = scheduleOf(drawn, List.of("composite3"));

		List<Standalone> standing = FrameCuts.standaloneOf(Set.of("composite3"), schedule, drawn);

		assertEquals(1, standing.size());
		Standalone waiting = standing.get(0);
		assertEquals("composite3", waiting.program());
		assertEquals(3, waiting.at(), "after composite2 and before composite4");
		assertEquals(Cut.AFTER_TRANSLUCENTS, waiting.cut());
		assertEquals(schedule.passing("composite3").orElseThrow(), waiting.step());
		assertEquals(Set.of(0), waiting.step().readsAlt(), "the half three flips have left it on");
	}

	@Test
	void standsAComputeBeyondTheLastPassAtTheLengthOfTheList() {
		List<String> drawn = List.of("composite", "composite1");

		List<Standalone> standing = FrameCuts.standaloneOf(Set.of("composite7"),
				scheduleOf(drawn, List.of("composite7")), drawn);

		assertEquals(2, standing.get(0).at());
	}

	@Test
	void standsAComputeOfAnEarlierFamilyAheadOfEveryPassInItsOwnCut() {
		List<String> drawn = List.of("composite", "composite1");

		List<Standalone> standing = FrameCuts.standaloneOf(Set.of("prepare"),
				scheduleOf(drawn, List.of("prepare")), drawn);

		assertEquals(0, standing.get(0).at(), "Pegasus: a prepare compute in an all composite chain");
		assertEquals(Cut.BEFORE_WORLD, standing.get(0).cut());
	}

	@Test
	void standsAComputeOfAPlaceThatDrawsNoPassAtNought() {
		List<Standalone> standing = FrameCuts.standaloneOf(Set.of("composite3"),
				scheduleOf(List.of(), List.of("composite3")), List.of());

		assertEquals(0, standing.get(0).at());
	}

	@Test
	void answersNothingForAPlaceWithNoStandaloneCompute() {
		List<String> drawn = List.of("composite", "composite1");

		assertEquals(List.of(), FrameCuts.standaloneOf(Set.of(), scheduleOf(drawn, List.of()), drawn));
	}

	@Test
	void settlesComputesOnTheSameIndexInFrameOrderWhateverOrderTheSetGivesThem() {
		List<String> alone = List.of("begin", "prepare", "deferred", "composite", "composite3");
		TargetSchedule schedule = scheduleOf(List.of(), alone);
		List<Cut> cuts = List.of(Cut.AHEAD_OF_SHADOWS, Cut.BEFORE_WORLD, Cut.BEFORE_TRANSLUCENTS,
				Cut.AFTER_TRANSLUCENTS, Cut.AFTER_TRANSLUCENTS);

		List<List<String>> orders = new ArrayList<>();
		permute(new ArrayList<>(alone), 0, orders);
		assertEquals(120, orders.size());
		for (List<String> order : orders) {
			List<Standalone> standing = FrameCuts.standaloneOf(new LinkedHashSet<>(order), schedule,
					List.of());

			assertEquals(alone, standing.stream().map(Standalone::program).toList(), order.toString());
			assertEquals(cuts, standing.stream().map(Standalone::cut).toList(), order.toString());
			assertTrue(standing.stream().allMatch(waiting -> waiting.at() == 0), order.toString());
		}
	}

	@Test
	void leavesOutAComputeTheScheduleGivesNoHalves() {
		List<String> drawn = List.of("composite", "composite5");
		TargetSchedule schedule = scheduleOf(drawn, List.of("composite4"));

		List<Standalone> standing = FrameCuts.standaloneOf(Set.of("composite3", "composite4"),
				schedule, drawn);

		assertEquals(List.of("composite4"), standing.stream().map(Standalone::program).toList());
	}

	@Test
	void countsThePassesThatSortBeforeAComputeOnRandomChains() {
		Random random = new Random(20260928L);
		List<String> pool = new ArrayList<>();
		for (String family : List.of("begin", "prepare", "deferred", "composite")) {
			pool.add(family);
			for (int slot = 1; slot <= 12; slot++) {
				pool.add(family + slot);
			}
		}

		for (int round = 0; round < 200; round++) {
			List<String> drawn = new ArrayList<>();
			for (String name : pool) {
				if (random.nextInt(3) == 0) {
					drawn.add(name);
				}
			}

			drawn.sort(ProgramNames.frameOrder());
			String program = pool.get(random.nextInt(pool.size()));
			TargetSchedule schedule = scheduleOf(drawn, List.of(program));

			List<Standalone> standing = FrameCuts.standaloneOf(Set.of(program), schedule, drawn);

			// The reading that needs no walk: how many drawn passes the name sorts after.
			long before = drawn.stream().filter(name -> ProgramNames.before(name, program)).count();
			assertEquals(1, standing.size(), program);
			assertEquals(before, standing.get(0).at(), program + " among " + drawn);
			assertEquals(Cut.of(program), standing.get(0).cut(), program);
		}
	}

	/** The schedule of a place that draws those programs, each a full screen write of colortex0. */
	private static TargetSchedule scheduleOf(List<String> drawn, List<String> alone) {
		List<TargetSchedule.Step> steps = drawn.stream()
				.map(program -> new TargetSchedule.Step(program, List.of(0), true)).toList();
		List<String> passing = new ArrayList<>(alone);
		passing.sort(ProgramNames.frameOrder());

		return TargetSchedule.of(steps, List.of(), passing);
	}

	private static void permute(List<String> names, int from, List<List<String>> into) {
		if (from == names.size()) {
			into.add(List.copyOf(names));

			return;
		}

		for (int at = from; at < names.size(); at++) {
			Collections.swap(names, from, at);
			permute(names, from + 1, into);
			Collections.swap(names, from, at);
		}
	}

	private static Standalone standalone(int at) {
		return new Standalone(Cut.AFTER_TRANSLUCENTS, at, "composite" + at,
				TargetSchedule.of(List.of(), List.of(), List.of("composite" + at))
						.passing("composite" + at).orElseThrow());
	}
}
