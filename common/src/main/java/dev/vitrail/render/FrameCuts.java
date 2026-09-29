package dev.vitrail.render;

import dev.vitrail.pack.model.ProgramNames;
import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.Vitrail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Where a frame cuts the chain, and which cut a program belongs to: the order the passes are kept
 * in, the three boundaries of the plan that make four cuts of it, and the computes that stand in a
 * cut with no pass of their own.
 * <p>
 * Nothing here touches the device or the game, only names and the plan's counts, which is what lets
 * it be checked without either. It is kept out of {@link PackChain} for that reason and not for
 * length: the answer to "which cut does this program run in" is asked by the walk and by the early
 * returns ahead of it, and two answers to it produce no error at all, only a dispatch at the wrong
 * moment of the frame.
 */
final class FrameCuts {

	private FrameCuts() {
	}

	/** The chain in frame order, the final last, which is the order everything downstream keeps. */
	static List<ChainPlan.Pass> ordered(ChainPlan plan) {
		List<ChainPlan.Pass> all = new ArrayList<>(plan.passes());
		plan.last().ifPresent(all::add);

		return all;
	}

	/** The last rank the begins' cut carries, which is the rank the plan cuts the pass list at. */
	private static final int BEGIN_RANK = ProgramNames.frameRank("begin");

	/** The same for the prepares' cut, and the same boundary of the plan. */
	private static final int PREPARE_RANK = ProgramNames.frameRank("prepare");

	/** The same for the cut that ends on the world's translucents. */
	private static final int DEFERRED_RANK = ProgramNames.frameRank("deferred");

	/**
	 * A cut of the frame: a stretch of the chain the renderer records at one moment of the game's
	 * own, drawn by one call of {@link PackChain#drawRange}.
	 * <p>
	 * A program belongs to the cut its FAMILY'S rank puts it in, and only to that one. A pass has no
	 * need to be told, holding a place in a list the cuts are windows onto; a compute of a program
	 * nothing draws holds no such place, so the cut is what it is given and the index only says
	 * where inside the cut it goes. Deciding it off the index instead read the cut out of the
	 * boundary between the two, which is the plan's count of what runs early and says nothing about
	 * a program that is not counted: Pegasus, whose whole chain is composites, has a {@code prepare}
	 * compute at index nought and a boundary at nought, and the late cut's walk ran it after the
	 * world.
	 * <p>
	 * <strong>The prepares are a cut of their own, and that is what answers for a compute of theirs
	 * against the scene seed.</strong> Noble's world0 stands a {@code prepare} compute on the first
	 * deferred pass, which is the index the seed is painted at. Carried by the cut its family names,
	 * it is dispatched before the world is drawn at all, so there is no seed in that range for it to
	 * fall on either side of; its world1, which ships the pass, runs the same step at the same moment.
	 */
	enum Cut {

		/** The begins, drawn ahead of the shadow stage and before the world. */
		AHEAD_OF_SHADOWS,

		/** The prepares, drawn behind that stage and still before the world. */
		BEFORE_WORLD,

		/** The seed and the deferreds, drawn before the world's translucents. */
		BEFORE_TRANSLUCENTS,

		/** The composites and the final, drawn after them. */
		AFTER_TRANSLUCENTS;

		/**
		 * Where the frame runs the family of that program. One comparison against the last rank each
		 * cut carries, which is how another cut is added: the ranks are the same ranks and every
		 * boundary the frame graph draws is another line through them.
		 */
		static Cut of(String program) {
			int rank = rankOf(program);
			if (rank <= BEGIN_RANK) {
				return AHEAD_OF_SHADOWS;
			}

			if (rank <= PREPARE_RANK) {
				return BEFORE_WORLD;
			}

			return rank <= DEFERRED_RANK ? BEFORE_TRANSLUCENTS : AFTER_TRANSLUCENTS;
		}
	}

	/**
	 * Where the frame runs a program, read off its family and never off a position in a list: it is
	 * what places a program the chain draws nothing for, against every boundary the frame is cut
	 * at.
	 */
	private static int rankOf(String program) {
		return ProgramNames.frameRank(ProgramNames.familyOf(TargetName.bareName(program)));
	}

	/**
	 * A compute of a program this place draws no pass for, with where the walk dispatches it.
	 *
	 * @param cut     the cut of the frame its family belongs to, which is the whole of what decides
	 *                which of the frame's four moments it runs at
	 * @param at      the index in {@link PackChain#programs} of the first pass that runs after it, or
	 *                the length of the list where every pass of the chain runs before it. Past the
	 *                end of its own cut it is dispatched at that end, the cut being where the frame
	 *                stops carrying its family at all
	 * @param program the program it hangs off, which is the name its halves and its texture stage are
	 *                read under however little of it is drawn
	 * @param step    the halves it reads, from {@link TargetSchedule#passing} and never from a pass:
	 *                there is none, and the step of the pass after it would carry the flips of every
	 *                stage opened in between
	 */
	record Standalone(Cut cut, int at, String program, TargetSchedule.Bound step) {
	}

	/**
	 * Which of a cut's standalones one dispatch takes. The walk reaches each of them once: on the
	 * index of the pass it runs before, or, past the last pass of the cut, at the end of the range.
	 */
	enum Reach {

		/** Those standing on the index the loop has reached. */
		AT_INDEX,

		/** Those no pass of the cut follows, taken at the end of the range. */
		PAST_END;

		boolean takes(Standalone waiting, int at) {
			return this == PAST_END ? waiting.at() >= at : waiting.at() == at;
		}
	}

	/**
	 * Places each of them in the walk, at the moment the program it hangs off would have run.
	 * <p>
	 * Iris puts its compute-only pass at the index the missing program holds inside its own stage
	 * ({@code CompositeRenderer.java:137-145}), so the moment is the program's place in the frame
	 * and not the head of its stage: a pack shipping {@code composite3.csh} with no
	 * {@code composite3.fsh} runs it after {@code composite2} and before {@code composite4}, and the
	 * halves it reads are the ones those two left behind. Read off the names for the same reason the
	 * plan reads its ranks off them: a position in a list moves the moment the day a pass is cut,
	 * and it moves without a word.
	 * <p>
	 * Sorted in frame order, which is what settles two that land on the same index: RenderPearl
	 * draws no full screen pass at all and ships five such computes, so all five stand on index
	 * nought, and taken in the order their file names came out of the map its {@code deferred} ran
	 * after its {@code composite3}.
	 *
	 * @param standingAlone the programs the place ships a compute for and draws no pass for
	 * @param schedule      the plan's schedule, which says the halves each of them reads
	 * @param built         the programs of the passes that are drawn, in frame order
	 */
	static List<Standalone> standaloneOf(Set<String> standingAlone, TargetSchedule schedule,
			List<String> built) {
		if (standingAlone.isEmpty()) {
			return List.of();
		}

		Comparator<String> order = ProgramNames.frameOrder();
		List<Standalone> waiting = new ArrayList<>();
		for (String program : standingAlone) {
			TargetSchedule.Bound step = schedule.passing(program).orElse(null);
			// The plan named it and the schedule did not, which is the plan disagreeing with itself
			// rather than anything a pack can cause. Left undispatched: a compute pushed with no
			// halves would be handed no colour target at all and throw once per frame.
			if (step == null) {
				Vitrail.logger().warn("compute of {} is not dispatched: the schedule gives it no "
						+ "halves to read", program);
				continue;
			}

			// The same test the plan stops its walk of the overrides on, so that the moment this
			// dispatch takes and the halves the plan says it reads are the one answer.
			int at = 0;
			while (at < built.size() && ProgramNames.before(built.get(at), program)) {
				at++;
			}

			waiting.add(new Standalone(Cut.of(program), at, program, step));
		}

		waiting.sort(Comparator.comparing(Standalone::program, order));

		return List.copyOf(waiting);
	}
}
