package dev.vitrail.pack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The chain of one place, unfolded into what a render pass needs: which attachments, in which
 * order, on which side, at which size.
 * <p>
 * It decides nothing about the ping pong. The schedule has already walked the frame and this
 * only reads it, which is what keeps a single answer to "which half does this pass write".
 * What it does own is the guards: an API that throws at the first draw is an API whose refusal
 * belongs at load time, with the program named.
 * <p>
 * The other thing it owns is the honesty of the picture. Nothing draws geometry yet, so the
 * game's own finished frame stands in for the gbuffers and lands in a single target; every other
 * target a pass reads before anything writes it holds a clear colour. Which ones those are, and
 * why, is worked out here and said in the pack's own terms, because the alternative is a plausible
 * image nobody can account for.
 */
public final class ChainPlan {

	/** A render pass carries at most eight colour attachments, and its pipeline as many states. */
	public static final int MAX_ATTACHMENTS = 8;

	private static final String FINAL = "final";

	/** What the game's frame is painted in place of. Its fallback tree is followed, not its file. */
	private static final String SEED_PROGRAM = "gbuffers_terrain";

	private final String place;
	private final List<Pass> passes;
	private final Pass last;
	private final Seed seed;
	private final List<Integer> swapBack;
	private final List<String> refusals;
	private final List<String> notes;

	private ChainPlan(String place, List<Pass> passes, Pass last, Seed seed, List<Integer> swapBack,
			List<String> refusals, List<String> notes) {
		this.place = place;
		this.passes = List.copyOf(passes);
		this.last = last;
		this.seed = seed;
		this.swapBack = List.copyOf(swapBack);
		this.refusals = List.copyOf(refusals);
		this.notes = List.copyOf(notes);
	}

	public record Attachment(int target, TargetSchedule.Side side) {
	}

	/**
	 * @param attachments in draw buffer order, at most eight, no index named twice. Empty on the
	 *                    final alone, which writes the game's own target and no colortex; on
	 *                    everything in {@link #passes()} there is at least one, so nothing has to
	 *                    pad index zero, which the command encoder refuses outright
	 * @param size        the one size every attachment of this pass shares
	 * @param inferred    the pack named no draw buffer and colortex0 was inferred, as Iris does
	 */
	public record Pass(String program, List<Attachment> attachments, TargetSize size,
			boolean inferred) {

		public Pass {
			attachments = List.copyOf(attachments);
		}

		/** The targets, without their side, which is what a log and a sampler check want. */
		public List<Integer> targets() {
			return this.attachments.stream().map(Attachment::target).toList();
		}
	}

	/**
	 * Where the game's finished frame is painted in place of the gbuffers.
	 *
	 * @param from the geometry program that would have written it, for the log
	 * @param at   how many of {@link #passes()} run before it, which is where the world would have
	 *             been drawn. Carried rather than left to the caller: a begin or a prepare runs
	 *             ahead of the world, the schedule gave the seed its half on that footing, and a
	 *             frame painting it anywhere else would contradict the walk without erroring
	 */
	public record Seed(int target, TargetSchedule.Side side, String from, int at) {
	}

	public static ChainPlan of(TargetPlan plan, ProgramResolver resolver) {
		return of(plan, resolver, List.of());
	}

	/**
	 * @param refused why programs of this place can have no pipeline built for them at all, in
	 *                whole sentences. They are put first because the caller shows one of them and
	 *                the one worth showing is the one nothing downstream could have worked around
	 */
	public static ChainPlan of(TargetPlan plan, ProgramResolver resolver, List<String> refused) {
		List<Pass> passes = new ArrayList<>();
		List<String> refusals = new ArrayList<>(refused);
		List<String> notes = new ArrayList<>();
		Pass last = null;

		for (String program : plan.running()) {
			if (program.equals(FINAL)) {
				last = new Pass(program, List.of(), TargetSize.ofScreen(), false);
				continue;
			}

			Pass pass = passOf(plan, program, refusals);
			if (pass != null) {
				passes.add(pass);
			}
		}

		if (last == null) {
			// Not a refusal: whoever loaded the chain has already decided whether a place without
			// a final is worth drawing, and every pass here still writes what it says it writes.
			notes.add("this place runs no final, so nothing the chain writes reaches the screen");
		}

		Seed seed = seedOf(plan, resolver, notes);
		verdicts(plan, seed, passes, last, notes);

		Set<Integer> back = new TreeSet<>(plan.schedule().flippedAtEnd());
		back.retainAll(plan.persistent());

		return new ChainPlan(plan.place(), passes, last, seed, List.copyOf(back), refusals, notes);
	}

	private static Pass passOf(TargetPlan plan, String program, List<String> refusals) {
		List<Integer> writes = plan.writes(program);
		if (writes.isEmpty()) {
			// Every full screen program is inferred to colortex0, so the only way here is a file
			// the expander could not read at all.
			refusals.add(program + " is meant to run and this plan knows nothing it writes, which "
					+ "is what an entry point that could not be read looks like");
			return null;
		}

		if (writes.size() > MAX_ATTACHMENTS) {
			refusals.add(program + " writes " + writes.size() + " targets and one pass carries at "
					+ "most " + MAX_ATTACHMENTS + ": " + writes);
			return null;
		}

		if (new LinkedHashSet<>(writes).size() != writes.size()) {
			refusals.add(program + " names the same target twice in its draw buffers, " + writes
					+ ", and one image cannot be two attachments of one pass");
			return null;
		}

		Optional<TargetSchedule.Bound> bound = plan.schedule().step(program);
		if (bound.isEmpty()) {
			refusals.add(program + " is meant to run and the schedule holds no step for it");
			return null;
		}

		List<Attachment> attachments = new ArrayList<>();
		TargetSize size = null;
		for (int index : writes) {
			if (!plan.allocated().contains(index)) {
				refusals.add(program + " writes " + TargetName.canonical(index)
						+ ", which nothing of this place allocates");
				return null;
			}

			TargetSize here = plan.directives().size(index);
			if (size == null) {
				size = here;
			} else if (!size.equals(here)) {
				// One pass, one render area: the backend refuses attachments of two sizes, and it
				// refuses them at the first draw rather than at load time.
				refusals.add(program + " writes targets of two sizes, " + describe(size) + " and "
						+ describe(here) + " for " + TargetName.canonical(index));
				return null;
			}

			attachments.add(new Attachment(index, bound.get().write(index)));
		}

		return new Pass(program, attachments, size, plan.inferredWrites().contains(program));
	}

	private static Seed seedOf(TargetPlan plan, ProgramResolver resolver, List<String> notes) {
		Optional<ProgramResolver.Resolution> resolution = resolver.lookup(plan.place(), SEED_PROGRAM);
		if (resolution.isEmpty()) {
			notes.add("nothing here serves " + SEED_PROGRAM + ", so the game's own frame is painted "
					+ "nowhere and every program of the chain reads clear colours");
			return null;
		}

		// The fallback tree is followed rather than the file: Sildur's ships no gbuffers_terrain
		// and inherits it from gbuffers_textured, whose draw buffers are 412 and not 0.
		String from = resolution.get().servedBy();
		List<Integer> writes = plan.writes(from);
		if (writes.isEmpty()) {
			notes.add(from + " serves " + SEED_PROGRAM + " and declares no draw buffer, so there is "
					+ "nowhere to paint the game's own frame");
			return null;
		}

		int target = writes.get(0);
		if (!plan.allocated().contains(target)) {
			notes.add(from + " serves " + SEED_PROGRAM + " and writes " + TargetName.canonical(target)
					+ ", which nothing of this place allocates, so the game's own frame is painted "
					+ "nowhere");
			return null;
		}

		TargetSchedule.Side side = plan.schedule().step(from)
				.map(step -> step.write(target))
				.orElse(TargetSchedule.Side.MAIN);

		return new Seed(target, side, from, plan.geometryAt());
	}

	/**
	 * What will be wrong once the chain is drawn, in the pack's own terms.
	 * <p>
	 * The unit is the half, not the target: a pass that reads and writes one target reads the half
	 * it is not writing, so asking whether "the target" has been written answers the wrong
	 * question and answers it reassuringly. A half is filled from the moment something in this
	 * frame has written it, and anything read before that holds either a clear colour or the
	 * frame before. Which of the two, and why, is what these lines say.
	 * <p>
	 * The seed counts from where it is drawn and not from the start of the frame. A begin or a
	 * prepare runs before the world does, so it reads its target as the clear left it, and calling
	 * the seed filled from the first pass onwards would drop exactly the notes those passes need.
	 */
	private static void verdicts(TargetPlan plan, Seed seed, List<Pass> passes, Pass last,
			List<String> notes) {
		Set<Attachment> filled = new LinkedHashSet<>();

		Set<Integer> geometry = new TreeSet<>();
		plan.schedule().steps().stream()
				.filter(step -> !step.fullscreen())
				.forEach(step -> geometry.addAll(step.writes()));

		List<Pass> ordered = new ArrayList<>(passes);
		if (last != null) {
			ordered.add(last);
		}

		Set<Attachment> told = new LinkedHashSet<>();
		for (int at = 0; at < ordered.size(); at++) {
			if (seed != null && at == seed.at()) {
				filled.add(new Attachment(seed.target(), seed.side()));
			}

			Pass pass = ordered.get(at);
			Optional<TargetSchedule.Bound> bound = plan.schedule().step(pass.program());

			for (int index : plan.samples(pass.program())) {
				Attachment half = new Attachment(index, bound
						.map(step -> step.read(index))
						.orElse(TargetSchedule.Side.MAIN));
				if (filled.contains(half) || !told.add(half)) {
					continue;
				}

				notes.add(verdict(plan, geometry, ordered, at, half, pass.program()));
			}

			filled.addAll(pass.attachments());
		}
	}

	private static String verdict(TargetPlan plan, Set<Integer> geometry, List<Pass> ordered, int at,
			Attachment half, String reader) {
		String name = TargetName.canonical(half.target());
		String clear = ", so " + reader + " reads what the clear left there";
		String before = ", so " + reader + " reads the frame before, and the clear colour on the "
				+ "first one";

		if (geometry.contains(half.target())) {
			return name + " is written by programs that draw the world, and none of those run"
					+ clear;
		}

		String later = writtenLater(ordered, at, half);
		if (later != null) {
			return name + " is not written until " + later + ", later in the same frame" + before;
		}

		String off = disabledWriter(plan, half.target());
		if (off != null) {
			return name + " is written only by " + off + ", which this place does not run" + clear;
		}

		// Nothing fills this half at any point of the frame. Whether that is a hole or a history
		// buffer is decided by the pack's own clear directive and by nothing else.
		return plan.persistent().contains(half.target())
				? "nothing writes the half of " + name + " that " + reader + " reads, and the pack "
						+ "keeps that target between frames" + before
				: name + " is written by no program of this place, so it holds its clear colour for "
						+ "ever, and " + reader + " reads that";
	}

	private static String writtenLater(List<Pass> ordered, int at, Attachment half) {
		for (int next = at + 1; next < ordered.size(); next++) {
			if (ordered.get(next).attachments().contains(half)) {
				return ordered.get(next).program();
			}
		}

		return null;
	}

	/** Named rather than lumped in with the targets nobody writes: the pack can switch it back on. */
	private static String disabledWriter(TargetPlan plan, int index) {
		List<String> writers = plan.disabled().entrySet().stream()
				.filter(entry -> plan.writes(entry.getKey()).contains(index))
				.map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
				.toList();

		return writers.isEmpty() ? null : String.join(", ", writers);
	}

	private static String describe(TargetSize size) {
		return size.relative()
				? String.format(Locale.ROOT, "%.3f by %.3f of the screen", size.width(), size.height())
				: (int) size.width() + " by " + (int) size.height() + " pixels";
	}

	public String place() {
		return this.place;
	}

	/** Full screen, frame order, the final EXCLUDED. */
	public List<Pass> passes() {
		return this.passes;
	}

	/** The final. Its attachments are always empty: it writes the game's own target. */
	public Optional<Pass> last() {
		return Optional.ofNullable(this.last);
	}

	public Optional<Seed> seed() {
		return Optional.ofNullable(this.seed);
	}

	/** ALT to MAIN at end of frame: still flipped, and kept between frames. */
	public List<Integer> swapBack() {
		return this.swapBack;
	}

	/** Why the chain must not be drawn at all. Empty means it may be. */
	public List<String> refusals() {
		return this.refusals;
	}

	/** What will be wrong once it is drawn, in the pack's own terms. */
	public List<String> notes() {
		return this.notes;
	}
}
