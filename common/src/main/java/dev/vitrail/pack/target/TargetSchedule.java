package dev.vitrail.pack.target;

import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.model.ProgramNames;
import dev.vitrail.pack.source.ShaderProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which half of each target every program reads and writes, worked out once for the whole frame.
 * <p>
 * A full screen pass reads a target and writes the same one, which no API allows, so a target
 * that takes part carries two textures and the pass reads one and writes the other. Where the
 * two are at any point in the frame is not a property of the target but of how many passes have
 * written it since the frame began, so it is a schedule rather than a flag.
 * <p>
 * One convention, said once and never inverted: <b>a target in the flipped set is read from ALT
 * and written to MAIN; outside the set, the other way round.</b> Iris carries the same fact
 * twice, as {@code stageReadsFromAlt} and {@code stageWritesToAlt}, with the inversion hidden
 * between them, and that is the shape to avoid rather than to copy.
 * <p>
 * The programs that draw geometry take no part. They write the half they read, because they
 * paint over the world rather than filtering it, and they never flip anything.
 * <p>
 * A pack may also turn a target over itself, either against one program or at the opening of a
 * whole stage, {@code flip.deferred_pre.colortex3}. The second kind belongs to no program, so it
 * is applied when the walk first reaches that stage, and it is applied even when the stage runs
 * nothing at all: Iris hands those directives to each renderer at construction and the renderer
 * plays them before its own loop, valid sources or none.
 */
public final class TargetSchedule {

	/**
	 * The stages a pack may turn a target over at the opening of, in the order a frame runs them.
	 * {@code shadowcomp_pre} is not among them: it goes to the shadow targets, which are not these.
	 */
	private static final List<String> PRE_STAGES =
			List.of("begin", "prepare", "deferred", "composite");

	/** The suffix that marks the opening of a stage rather than one of its programs. */
	private static final String PRE = "_pre";

	private final List<Bound> steps;
	private final Set<Integer> doubled;
	private final Set<Integer> flippedAtEnd;
	private final Set<Integer> afterDeferred;
	private final Map<String, Map<Integer, Boolean>> forced;
	private final Map<String, Set<Integer>> passingHalves;

	private TargetSchedule(List<Bound> steps, Set<Integer> doubled, Set<Integer> flippedAtEnd,
			Set<Integer> afterDeferred, Map<String, Map<Integer, Boolean>> forced,
			Map<String, Set<Integer>> passingHalves) {
		this.steps = List.copyOf(steps);
		// Sorted rather than Set.copyOf: these end up in a log, and an index that moves about
		// between two runs of the same pack reads as a difference that is not one.
		this.doubled = sortedCopy(doubled);
		this.flippedAtEnd = sortedCopy(flippedAtEnd);
		this.afterDeferred = sortedCopy(afterDeferred);

		Map<String, Map<Integer, Boolean>> copied = new LinkedHashMap<>();
		forced.forEach((program, indices) -> copied.put(program, Map.copyOf(indices)));
		this.forced = Map.copyOf(copied);

		Map<String, Set<Integer>> halves = new LinkedHashMap<>();
		passingHalves.forEach((program, indices) -> halves.put(program, sortedCopy(indices)));
		this.passingHalves = Map.copyOf(halves);
	}

	public enum Side { MAIN, ALT }

	/**
	 * One pass of the frame, with the targets it writes.
	 *
	 * @param fullscreen a gbuffers pass writes the half it reads and takes no part in the walk
	 */
	public record Step(String program, List<Integer> writes, boolean fullscreen) {
	}

	/** One step with its halves already chosen. */
	public record Bound(String program, List<Integer> writes, boolean fullscreen,
			Set<Integer> readsAlt, Set<Integer> writesAlt) {

		public Side read(int index) {
			return this.readsAlt.contains(index) ? Side.ALT : Side.MAIN;
		}

		public Side write(int index) {
			return this.writesAlt.contains(index) ? Side.ALT : Side.MAIN;
		}
	}

	/**
	 * Chooses which half every pass reads and writes, by walking the passes in order.
	 *
	 * @param steps in render order, not in directive order
	 */
	public static TargetSchedule of(List<Step> steps, List<ShaderProperties.FlipDirective> explicit) {
		return of(steps, explicit, List.of());
	}

	/**
	 * The same walk, also answering for the programs of {@code passing}, which the place draws no
	 * pass for and which the walk therefore stops at without binding anything.
	 *
	 * @param steps   in render order, not in directive order
	 * @param passing the programs a compute hangs off that this place draws no pass for, in the
	 *                same order. They enter no step: nothing of them is drawn, they write no target
	 *                and they turn none over, so every count taken off {@link #steps()} is the same
	 *                with them and without. What the walk owes them is the one thing Iris gives its
	 *                own compute-only pass and nothing else, the halves standing at that point of
	 *                the frame ({@code pipeline/CompositeRenderer.java:134} takes the snapshot,
	 *                {@code :137-145} builds the pass and flips nothing after it)
	 */
	public static TargetSchedule of(List<Step> steps, List<ShaderProperties.FlipDirective> explicit,
			List<String> passing) {
		Map<String, Map<Integer, Boolean>> forced = byProgram(explicit);
		Set<Integer> flipped = new LinkedHashSet<>();
		Set<Integer> doubled = new TreeSet<>();
		List<Bound> bound = new ArrayList<>();
		Map<String, Set<Integer>> halves = new LinkedHashMap<>();
		Set<Integer> afterDeferred = null;
		int deferredRank = ProgramNames.frameRank("deferred");
		int opened = 0;

		for (Item item : merge(steps, passing)) {
			int reached =
					ProgramNames.frameRank(ProgramNames.familyOf(TargetName.bareName(item.program())));

			// The snapshot is taken between the last deferred and the first thing after it, with
			// the deferred stage opened and the composite one not. That is the moment Iris takes
			// flippedAfterTranslucent at: the deferred renderer has played its own pre directives
			// and run, and the composite renderer has not yet played its.
			if (afterDeferred == null && reached > deferredRank) {
				opened = openStages(opened, deferredRank, forced, flipped, doubled);
				afterDeferred = sortedCopy(flipped);
			}

			opened = openStages(opened, reached, forced, flipped, doubled);

			// Its stage is open and no pass of it has run, which is where Iris takes the snapshot
			// it hands a compute whose program has no valid source. Recorded and not bound: a step
			// here would say a pass runs, and none does.
			if (item.step() == null) {
				halves.put(TargetName.bareName(item.program()), sortedCopy(flipped));
				continue;
			}

			Step step = item.step();
			Set<Integer> readsAlt = sortedCopy(flipped);
			Set<Integer> writesAlt = new TreeSet<>();

			for (int index : step.writes()) {
				// A geometry pass writes where it reads. A full screen one writes the other half,
				// which is the whole point of having two.
				if (step.fullscreen() != flipped.contains(index)) {
					writesAlt.add(index);
				}
			}

			bound.add(new Bound(step.program(), List.copyOf(step.writes()), step.fullscreen(),
					readsAlt, sortedCopy(writesAlt)));

			if (!step.fullscreen()) {
				continue;
			}

			doubled.addAll(step.writes());

			Map<Integer, Boolean> here =
					forced.getOrDefault(TargetName.bareName(step.program()), Map.of());
			for (int index : step.writes()) {
				if (!Boolean.FALSE.equals(here.get(index))) {
					flip(flipped, index);
				}
			}

			// Transcribed from Iris rather than tidied: a target the program writes and that a
			// directive also asks to flip is flipped twice, which leaves it where it was.
			here.forEach((index, shouldFlip) -> {
				if (shouldFlip) {
					flip(flipped, index);
					// A directive may turn over a target no pass writes. Every read past this
					// point still lands on the far half, so that half has to exist.
					doubled.add(index);
				}
			});
		}

		// A stage the place ships nothing for still opens, at the end of the walk if nowhere else,
		// and the snapshot is still taken: a place running nothing past the deferreds still draws
		// its translucents, on the halves the walk stands at when the deferred stage has opened.
		if (afterDeferred == null) {
			opened = openStages(opened, deferredRank, forced, flipped, doubled);
			afterDeferred = sortedCopy(flipped);
		}

		openStages(opened, Integer.MAX_VALUE, forced, flipped, doubled);

		return new TargetSchedule(bound, doubled, flipped, afterDeferred, forced, halves);
	}

	/** One thing the walk stops at: a pass the place draws, or a program it only ships a compute for. */
	private record Item(String program, Step step) {
	}

	/**
	 * The two lists laid end to end in frame order, each program of {@code passing} put where its
	 * own name would have put a pass of it.
	 * <p>
	 * Both lists arrive in {@link ProgramNames#frameOrder}, which is the order the steps were built
	 * in and the order Iris fills the array a stage walks, so this is a merge and not a sort.
	 * <p>
	 * They meet on one name and one only, a program shipped with its fragment half and not its
	 * vertex one: the plan's walk reads the fragments, so it made a step of it, and the plan calls
	 * it passing all the same because half a source draws nothing. Such a place is refused whole
	 * before a frame is drawn from it, so the compute landing after that step rather than in its
	 * place is a plan nobody reads.
	 */
	private static List<Item> merge(List<Step> steps, List<String> passing) {
		if (passing.isEmpty()) {
			return steps.stream().map(step -> new Item(step.program(), step)).toList();
		}

		Comparator<String> order = ProgramNames.frameOrder();
		List<Item> items = new ArrayList<>();
		int next = 0;
		for (Step step : steps) {
			String against = TargetName.bareName(step.program());
			while (next < passing.size()
					&& order.compare(TargetName.bareName(passing.get(next)), against) < 0) {
				items.add(new Item(passing.get(next++), null));
			}

			items.add(new Item(step.program(), step));
		}

		while (next < passing.size()) {
			items.add(new Item(passing.get(next++), null));
		}

		return List.copyOf(items);
	}

	/**
	 * Turns over what the pack asked to have turned over at the opening of every stage the walk has
	 * now reached, and says which stage to look at next.
	 * <p>
	 * Driven by the rank of the step rather than by its family, so that a stage the place ships no
	 * program for is still opened, in its own place in the frame, on the way past. Iris hands these
	 * directives to a renderer at construction and the renderer plays them before its loop, whether
	 * or not it has a single valid source, and a bascule lost here would be lost for the rest of
	 * the frame without a word.
	 *
	 * @param opened  the first stage of {@link #PRE_STAGES} not yet opened
	 * @param reached the frame rank the walk has arrived at
	 * @return the new first stage not yet opened
	 */
	private static int openStages(int opened, int reached,
			Map<String, Map<Integer, Boolean>> forced, Set<Integer> flipped, Set<Integer> doubled) {
		int next = opened;
		while (next < PRE_STAGES.size() && ProgramNames.frameRank(PRE_STAGES.get(next)) <= reached) {
			forced.getOrDefault(PRE_STAGES.get(next) + PRE, Map.of()).forEach((index, shouldFlip) -> {
				if (shouldFlip) {
					flip(flipped, index);
					// Nothing else will double it: no step writes it, so without this the alternate
					// half never exists and every read past here silently lands back on the main one.
					doubled.add(index);
				}
			});

			next++;
		}

		return next;
	}

	public List<Bound> steps() {
		return this.steps;
	}

	public Optional<Bound> step(String program) {
		String wanted = TargetName.bareName(program);

		return this.steps.stream().filter(step -> TargetName.bareName(step.program()).equals(wanted))
				.findFirst();
	}

	/**
	 * The halves a program this place draws no pass for stands on, for the computes that hang off
	 * it and that run whether it is there or not.
	 * <p>
	 * It writes nothing and turns nothing over, so only the read side of the answer means anything:
	 * a compute of it samples {@code colortexN} and stores into {@code colorimgN} on the one half
	 * Iris binds for both ({@code pipeline/CompositeRenderer.java:454} hands the samplers that
	 * snapshot and {@code :458} hands the images the same one), which is the half the next pass of
	 * the chain reads.
	 * <p>
	 * Empty for every program that is not one of these, which is every program that draws: ask
	 * {@link #step} for those. Both answers are filled for one name only, the name the merge above
	 * describes, and no frame is drawn off a plan holding it.
	 */
	public Optional<Bound> passing(String program) {
		return Optional.ofNullable(this.passingHalves.get(TargetName.bareName(program)))
				.map(readsAlt -> new Bound(program, List.of(), true, readsAlt, Set.of()));
	}

	/**
	 * The same step, taken as if the program ran after the deferred stage: its halves are read off
	 * the walk as the last deferred leaves it, {@code deferred_pre} directives played and
	 * {@code composite_pre} ones not.
	 * <p>
	 * This is Iris's rule for the translucent chunk pass, and for it alone. Every gbuffers program
	 * is handed the {@code flippedAfterPrepare} snapshot except {@code Pass.TRANSLUCENT}, which is
	 * handed {@code flippedAfterTranslucent}, the state the deferred passes leave behind; that one
	 * line is the whole difference between the water and the terrain in Iris's Sodium wiring. The
	 * walk keeps every geometry step at the geometry rank, before the deferreds, so asking
	 * {@link #step} for a program that really draws after them hands back the halves of the wrong
	 * moment: the pass would write one half while every composite read the other, and nothing on
	 * either side would say a word.
	 * <p>
	 * Only a geometry step can be re-taken this way. It writes the half it reads and flips nothing,
	 * so moving it in the frame changes which snapshot its halves come from and nothing else. A
	 * full screen step is itself a flip, and re-binding one here would contradict the walk.
	 */
	public Optional<Bound> stepAfterDeferred(String program) {
		return step(program)
				.filter(step -> !step.fullscreen())
				.map(step -> new Bound(step.program(), step.writes(), false, this.afterDeferred,
						within(this.afterDeferred, step.writes())));
	}

	/** The indices of {@code writes} that are flipped, which is where a geometry step writes ALT. */
	private static Set<Integer> within(Set<Integer> flipped, List<Integer> writes) {
		Set<Integer> found = new TreeSet<>();
		for (int index : writes) {
			if (flipped.contains(index)) {
				found.add(index);
			}
		}

		return sortedCopy(found);
	}

	/**
	 * The only targets that could need a second texture: those a full screen step writes, plus
	 * any a {@code flip} directive turns over on its own.
	 */
	public Set<Integer> doubled() {
		return this.doubled;
	}

	/**
	 * Which targets still hold their fresh content on the alternate side once every step has run.
	 * The next frame starts from an empty flipped set, so these are the ones a copy has to bring
	 * back. Kept rather than derived from the last step: the last step is the final today and the
	 * derivation would break in silence the day a pack ships none.
	 */
	public Set<Integer> flippedAtEnd() {
		return this.flippedAtEnd;
	}

	/**
	 * The same answer restricted to a chosen few programs. Nothing in the engine asks this any
	 * more, now that the schedule only carries what runs, and it is kept because the corpus
	 * measurements are taken with it and their figures are frozen.
	 */
	public Set<Integer> doubledFor(Set<String> programs) {
		Set<String> wanted = new LinkedHashSet<>();
		programs.forEach(program -> wanted.add(TargetName.bareName(program)));

		Set<Integer> found = new TreeSet<>();
		for (Bound step : this.steps) {
			String name = TargetName.bareName(step.program());
			if (!step.fullscreen() || !wanted.contains(name)) {
				continue;
			}

			found.addAll(step.writes());
			this.forced.getOrDefault(name, Map.of()).forEach((index, shouldFlip) -> {
				if (shouldFlip) {
					found.add(index);
				}
			});
		}

		return sortedCopy(found);
	}

	private static Set<Integer> sortedCopy(Set<Integer> indices) {
		return Collections.unmodifiableSet(new TreeSet<>(indices));
	}

	private static void flip(Set<Integer> flipped, int index) {
		if (!flipped.remove(index)) {
			flipped.add(index);
		}
	}

	private static Map<String, Map<Integer, Boolean>> byProgram(
			List<ShaderProperties.FlipDirective> explicit) {
		Map<String, Map<Integer, Boolean>> forced = new LinkedHashMap<>();

		for (ShaderProperties.FlipDirective directive : explicit) {
			TargetName.index(directive.buffer()).ifPresent(index ->
					forced.computeIfAbsent(TargetName.bareName(directive.program()),
							_ -> new LinkedHashMap<>())
							.put(index, directive.value()));
		}

		return forced;
	}
}
