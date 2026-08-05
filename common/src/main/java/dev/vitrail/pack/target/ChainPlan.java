package dev.vitrail.pack.target;

import dev.vitrail.pack.program.ProgramNames;
import dev.vitrail.pack.program.ProgramResolver;
import dev.vitrail.pack.program.TerrainPass;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * The other thing it owns is the honesty of the picture. The chunk passes fill the targets the
 * pack sends them to and the game's own finished frame stands in for the gbuffers nothing draws
 * yet, in a single target; every other target a pass reads before anything writes it holds a clear
 * colour. Which ones those are, and why, is worked out here and said in the pack's own terms,
 * because the alternative is a plausible image nobody can account for.
 */
public final class ChainPlan {

	/** A render pass carries at most eight colour attachments, and its pipeline as many states. */
	public static final int MAX_ATTACHMENTS = 8;

	/**
	 * Where the deferred stage sits in the frame. Everything at this rank or below belongs before the
	 * world's translucents, everything above it after the world.
	 */
	public static final int DEFERRED_RANK = ProgramNames.frameRank("deferred");

	private static final String FINAL = "final";

	/** What the game's frame is painted in place of. Its fallback tree is followed, not its file. */
	private static final String SEED_PROGRAM = "gbuffers_terrain";

	private final String place;
	/** What the game may draw its sky with, in the OptiFine split. Not ours to choose. */
	private static final List<String> SKY_PROGRAMS =
			List.of("gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_clouds");

	private final List<Pass> passes;
	private final Pass last;
	private final Seed seed;
	private final Map<TerrainPass, Pass> geometry;

	/**
	 * Where each sky program's outputs belong, by the bare name the pack serves it under.
	 * <p>
	 * Keyed by name and not by an enum of passes, unlike the terrain: the game draws the sky in
	 * eight passes of its own but they share three programs between them, and what decides the
	 * halves is the program rather than the pass. All of them draw before the deferred stage, so
	 * all of them read the snapshot the prepares leave, which is the ordinary geometry walk.
	 */
	private final Map<String, Pass> sky;

	private final List<Integer> swapBack;
	private final List<String> refusals;
	private final List<String> notes;

	private ChainPlan(String place, List<Pass> passes, Pass last, Seed seed,
			Map<TerrainPass, Pass> geometry, Map<String, Pass> sky, List<Integer> swapBack,
			List<String> refusals, List<String> notes) {
		this.place = place;
		this.passes = List.copyOf(passes);
		this.last = last;
		this.seed = seed;
		this.geometry = Map.copyOf(geometry);
		this.sky = Map.copyOf(sky);
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

		/**
		 * Where in the frame this pass belongs, on the scale {@link ProgramNames#frameRank} uses.
		 * <p>
		 * Read off the name and never off a position in a list. A stage boundary expressed as an
		 * index into the running order shifts the moment one program of the chain is refused, and it
		 * shifts without a word: the passes still run, in the right order, at the wrong moment.
		 */
		public int frameRank() {
			return ProgramNames.frameRank(ProgramNames.familyOf(this.program));
		}

		/**
		 * Whether this pass belongs to the deferred stage, which OptiFine runs after the opaque
		 * geometry and before the translucent geometry rather than after the world.
		 */
		public boolean deferred() {
			return frameRank() == ProgramNames.frameRank("deferred");
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

		// Worked out before the verdicts rather than in the return, so that they are handed the
		// answer instead of asking for it twice: what the chunk passes write is exactly what the
		// verdicts must not blame the clear for.
		Map<TerrainPass, Pass> geometry = geometryOf(plan, resolver, notes);
		Map<String, Pass> sky = skyOf(plan, resolver, notes);

		Seed seed = seedOf(plan, resolver, notes);
		verdicts(plan, seed, geometry, passes, last, notes);

		Set<Integer> back = new TreeSet<>(plan.schedule().flippedAtEnd());
		back.retainAll(plan.persistent());

		return new ChainPlan(plan.place(), passes, last, seed, geometry, sky, List.copyOf(back),
				refusals, notes);
	}

	/**
	 * The attachments of every chunk pass this engine can draw, keyed by the pass and never by the
	 * file that serves it. One file usually serves two of the three, and the file is still not the
	 * key: the passes it serves stand on either side of the deferred stage, so the same draw
	 * buffers land on different halves depending on which pass is asking. Iris keys the same
	 * answer the same way, one flip snapshot per {@code Pass}.
	 * <p>
	 * The walk before the deferreds is shared by the solid and the cutout pass, so its answer is
	 * computed once per file, which is also what keeps a broken file from being reported twice.
	 */
	private static Map<TerrainPass, Pass> geometryOf(TargetPlan plan, ProgramResolver resolver,
			List<String> notes) {
		Map<TerrainPass, Pass> geometry = new EnumMap<>(TerrainPass.class);
		Map<String, Pass> before = new LinkedHashMap<>();
		for (TerrainPass pass : TerrainPass.values()) {
			// A shadow pass writes shadowcolor and never colortex, so this walk has no answer for
			// it: its draw buffers index a set of targets this plan does not hold, and a number read
			// as the wrong family would send the shadow map's albedo into somebody's normals.
			if (pass.shadow()) {
				continue;
			}

			Optional<String> served = resolver.lookup(plan.place(), pass.program())
					.map(ProgramResolver.Resolution::servedBy);
			if (served.isEmpty()) {
				continue;
			}

			String servedBy = served.get();
			Pass attachments;
			if (pass.afterDeferred()) {
				attachments = geometryOf(plan, servedBy, notes, true);
			} else {
				// containsKey rather than computeIfAbsent, because null is an answer here and
				// recomputing it would say the same note once per pass the file serves.
				if (!before.containsKey(servedBy)) {
					before.put(servedBy, geometryOf(plan, servedBy, notes, false));
				}

				attachments = before.get(servedBy);
			}

			if (attachments != null) {
				geometry.put(pass, attachments);
			}
		}

		return geometry;
	}

	/**
	 * The same walk for the sky, asked once per program the game may draw it with.
	 * <p>
	 * The three names are the OptiFine split and they are not ours to choose: untextured geometry
	 * goes to {@code gbuffers_skybasic}, the sun and the moon to {@code gbuffers_skytextured}, the
	 * clouds to {@code gbuffers_clouds}. Each is looked up through the fallback tree, so a pack that
	 * ships none of them still answers here through {@code gbuffers_basic} or
	 * {@code gbuffers_textured} if it has those.
	 * <p>
	 * Always the walk BEFORE the deferreds. The sky is drawn at the third rank of the frame, between
	 * the prepares and the terrain, so its halves are the ones the prepares leave; only the
	 * translucent chunk pass is re-taken after the deferred stage, and that rule is its alone.
	 */
	private static Map<String, Pass> skyOf(TargetPlan plan, ProgramResolver resolver,
			List<String> notes) {
		Map<String, Pass> sky = new LinkedHashMap<>();

		// Computed once per serving FILE and not once per program, the same reason the geometry walk
		// gives above: the fallback tree sends skybasic to gbuffers_basic and both skytextured and
		// clouds to gbuffers_textured, so one file commonly serves two or three of these names, and
		// walking it again would say the same note about it twice. containsKey rather than
		// computeIfAbsent, because null is an answer here.
		Map<String, Pass> byFile = new LinkedHashMap<>();
		for (String program : SKY_PROGRAMS) {
			Optional<String> served = resolver.lookup(plan.place(), program)
					.map(ProgramResolver.Resolution::servedBy);
			if (served.isEmpty()) {
				continue;
			}

			String servedBy = served.get();
			if (!byFile.containsKey(servedBy)) {
				byFile.put(servedBy, geometryOf(plan, servedBy, notes, false));
			}

			Pass attachments = byFile.get(servedBy);
			if (attachments != null) {
				sky.put(program, attachments);
			}
		}

		return sky;
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

		return attachmentsOf(plan, program, writes, refusals);
	}

	/**
	 * The same walk for a geometry program, which is asked for by name rather than found in the
	 * running chain.
	 * <p>
	 * Every reason to answer nothing goes to the notes and none of them refuses anything: a geometry
	 * program is not part of the chain, so a place that cannot carry its targets is a place where
	 * the geometry writes one attachment instead of several, not a place that draws nothing. A pack
	 * declaring no draw buffer on its geometry is the ordinary case rather than a fault, which is
	 * why it is not even said here: {@link #notes()} already carries one line naming all of them.
	 */
	private static Pass geometryOf(TargetPlan plan, String program, List<String> notes,
			boolean afterDeferred) {
		List<Integer> writes = plan.writes(program);
		if (writes.isEmpty()) {
			return null;
		}

		Optional<TargetSchedule.Bound> bound = afterDeferred
				? plan.schedule().stepAfterDeferred(program)
				: plan.schedule().step(program);

		return attachmentsOf(plan, program, writes, notes, bound);
	}

	private static Pass attachmentsOf(TargetPlan plan, String program, List<Integer> writes,
			List<String> problems) {
		return attachmentsOf(plan, program, writes, problems, plan.schedule().step(program));
	}

	private static Pass attachmentsOf(TargetPlan plan, String program, List<Integer> writes,
			List<String> problems, Optional<TargetSchedule.Bound> bound) {
		if (writes.size() > MAX_ATTACHMENTS) {
			problems.add(program + " writes " + writes.size() + " targets and one pass carries at "
					+ "most " + MAX_ATTACHMENTS + ": " + writes);
			return null;
		}

		if (new LinkedHashSet<>(writes).size() != writes.size()) {
			problems.add(program + " names the same target twice in its draw buffers, " + writes
					+ ", and one image cannot be two attachments of one pass");
			return null;
		}

		if (bound.isEmpty()) {
			problems.add(program + " writes " + writes + " and the schedule holds no step for it");
			return null;
		}

		List<Attachment> attachments = new ArrayList<>();
		TargetSize size = null;
		for (int index : writes) {
			if (!plan.allocated().contains(index)) {
				problems.add(program + " writes " + TargetName.canonical(index)
						+ ", which nothing of this place allocates");
				return null;
			}

			TargetSize here = plan.directives().size(index);
			if (size == null) {
				size = here;
			} else if (!size.equals(here)) {
				// One pass, one render area: the backend refuses attachments of two sizes, and it
				// refuses them at the first draw rather than at load time.
				problems.add(program + " writes targets of two sizes, " + describe(size) + " and "
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
	 * <p>
	 * The chunk passes count from where they are drawn for the same reason, and they are counted at
	 * all because they really are drawn: the opaque halves land where the seed does, the translucent
	 * one after the last deferred, which is the whole reason its targets are taken on the other
	 * snapshot. Reading the geometry off the schedule alone, as this did, blamed the clear for a
	 * half {@code gbuffers_water} had just written.
	 */
	private static void verdicts(TargetPlan plan, Seed seed, Map<TerrainPass, Pass> world,
			List<Pass> passes, Pass last, List<String> notes) {
		List<Pass> ordered = new ArrayList<>(passes);
		if (last != null) {
			ordered.add(last);
		}

		// Every target a gbuffers program declares, less the ones the chunk passes really fill. What
		// is left is written by geometry alone and by nothing this engine puts in the pack's targets.
		Set<Integer> undrawn = new TreeSet<>();
		plan.schedule().steps().stream()
				.filter(step -> !step.fullscreen())
				.forEach(step -> undrawn.addAll(step.writes()));

		// One entry per point of the frame the world goes in at, the solid and the cutout pass sharing
		// theirs. A set rather than a list: those two are usually one file, hence one identical Pass.
		int afterDeferred = pastDeferred(ordered);
		Map<Integer, Set<Pass>> drawn = new LinkedHashMap<>();
		world.forEach((pass, drawing) -> {
			drawn.computeIfAbsent(pass.afterDeferred() ? afterDeferred : plan.geometryAt(),
					_ -> new LinkedHashSet<>()).add(drawing);
			undrawn.removeAll(drawing.targets());
		});

		Set<Attachment> filled = new LinkedHashSet<>();
		Set<Attachment> told = new LinkedHashSet<>();
		for (int at = 0; at < ordered.size(); at++) {
			if (seed != null && at == seed.at()) {
				filled.add(new Attachment(seed.target(), seed.side()));
			}

			for (Pass drawing : drawn.getOrDefault(at, Set.of())) {
				filled.addAll(drawing.attachments());
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

				notes.add(verdict(plan, undrawn, ordered, drawn, at, half, pass.program()));
			}

			filled.addAll(pass.attachments());
		}
	}

	/**
	 * How many of {@code ordered} run before the world's translucents, which is where the chunk pass
	 * that draws them goes in.
	 * <p>
	 * Counted off the ranks rather than off the length of the deferred stage, so that a place
	 * shipping no deferred at all still puts them before its composites instead of at the end.
	 */
	private static int pastDeferred(List<Pass> ordered) {
		int at = 0;
		while (at < ordered.size() && ordered.get(at).frameRank() <= DEFERRED_RANK) {
			at++;
		}

		return at;
	}

	/**
	 * @param undrawn the targets geometry writes and no pass of this engine fills
	 * @param drawn   the world's own passes, by the point of the frame they are drawn at
	 */
	private static String verdict(TargetPlan plan, Set<Integer> undrawn, List<Pass> ordered,
			Map<Integer, Set<Pass>> drawn, int at, Attachment half, String reader) {
		String name = TargetName.canonical(half.target());
		String clear = ", so " + reader + " reads what the clear left there";
		String before = ", so " + reader + " reads the frame before, and the clear colour on the "
				+ "first one";

		if (undrawn.contains(half.target())) {
			// Two families end up here and the wording covers both: the geometry nothing draws yet,
			// entities and particles, and the sky, whose programs are drawn but into the attachment
			// the game opened its own pass with, SkyProgram handing its body no writes at all.
			return name + " is written by geometry, none of which this engine draws into the pack's "
					+ "targets" + clear;
		}

		String later = writtenLater(ordered, drawn, at, half);
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

	/**
	 * The first thing that fills this half after the reader, the world included: a prepare reads
	 * targets the chunk passes fill later in the same frame, and naming the composite that gets to
	 * them afterwards would send the next diagnostic to the wrong end of the chain.
	 * <p>
	 * The bound runs one past the last full screen pass, because a place shipping neither composite
	 * nor final still draws its translucents, and they still fill what they write.
	 */
	private static String writtenLater(List<Pass> ordered, Map<Integer, Set<Pass>> drawn, int at,
			Attachment half) {
		for (int next = at + 1; next <= ordered.size(); next++) {
			// The world goes in ahead of the full screen pass standing at the same point, which is
			// the order the walk fills them in.
			for (Pass drawing : drawn.getOrDefault(next, Set.of())) {
				if (drawing.attachments().contains(half)) {
					return drawing.program();
				}
			}

			if (next < ordered.size() && ordered.get(next).attachments().contains(half)) {
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

	/**
	 * Where one chunk pass's outputs belong, in draw buffer order and each on the half the
	 * schedule gives it, or empty when this place cannot answer.
	 * <p>
	 * Keyed by the pass and not by the file that serves it, because the halves are the pass's: the
	 * translucent pass draws after the deferred stage and its targets are on the sides the
	 * deferreds leave them, even when the very same file serves the solid pass before them.
	 * <p>
	 * Empty is not a failure and covers three cases, all of them normal for a pack: it declares no
	 * draw buffer on its geometry, which most of the corpus does on at least one place; it writes a
	 * target this place does not allocate; or its targets are not all the same size, which one
	 * render pass cannot carry. The last two are said in {@link #notes()}.
	 */
	public Optional<Pass> geometry(TerrainPass pass) {
		return Optional.ofNullable(this.geometry.get(pass));
	}

	/**
	 * Where one sky program's outputs belong, in draw buffer order and each on the half the schedule
	 * gives it, or empty when this place cannot answer.
	 * <p>
	 * Empty covers the same three normal cases as {@link #geometry}, plus one of its own: a pack that
	 * ships no sky program at all and whose fallback tree leads nowhere. None of them is a failure,
	 * and a place that cannot answer leaves the game drawing its own sky.
	 *
	 * @param program the bare name, {@code gbuffers_skybasic}, not the file that ends up serving it
	 */
	public Optional<Pass> sky(String program) {
		return Optional.ofNullable(this.sky.get(program));
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
