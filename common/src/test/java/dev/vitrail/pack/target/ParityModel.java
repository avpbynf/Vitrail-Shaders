package dev.vitrail.pack.target;

import dev.vitrail.pack.source.ShaderProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A second, independent reading of the one rule the frame documents for every colour target:
 * <blockquote>a read lands on the half produced by the last write before it, and on the primary
 * half if there was none</blockquote>
 * <p>
 * It shares no code and no representation with {@link TargetSchedule}. The schedule keeps one set of
 * "flipped" indices and turns a target over by remove-else-add; this keeps, for every target, the
 * half its LAST WRITE landed on, and derives everything else from that sentence: a full screen pass
 * writes the half it does not read, a geometry pass paints over the half it reads, a target is
 * doubled when a full screen pass writes it. The pack's own {@code flip} directives are the one
 * place the sentence is overridden on purpose, and they are modelled here from
 * {@code docs/internals/render-targets.md} and the OptiFine grammar, not from the schedule's code.
 * <p>
 * The stage a program belongs to is given by construction and never parsed from its name, and the
 * order of the stages is the order of {@code docs/frame.md}, so a disagreement about where a stage
 * begins shows up as a failing comparison instead of being inherited.
 */
final class ParityModel {

	/** How many colour targets a pack can name, {@code colortex0} to {@code colortex31}. */
	static final int UNIVERSE = 32;

	/** In the order the frame runs them. The shadow composite stage has its own targets and is not here. */
	enum Stage { BEGIN, PREPARE, GEOMETRY, DEFERRED, COMPOSITE, FINAL }

	enum Half {
		PRIMARY, ALTERNATE;

		Half other() {
			return this == PRIMARY ? ALTERNATE : PRIMARY;
		}

		TargetSchedule.Side side() {
			return this == PRIMARY ? TargetSchedule.Side.MAIN : TargetSchedule.Side.ALT;
		}
	}

	/**
	 * One program of a chain as its author wrote it.
	 *
	 * @param writes      the draw buffers, in declaration order. Empty on the final alone
	 * @param reads       the colour targets the program declares a sampler for
	 * @param inferred    the pack declares no draw buffer, so the target is colortex0 and the file
	 *                    carries no directive at all
	 * @param computeOnly no fragment stage draws: only a compute hangs off this moment of the frame
	 */
	record Pass(Stage stage, int slot, String program, List<Integer> writes, Set<Integer> reads,
			boolean inferred, boolean computeOnly) {

		boolean fullscreen() {
			return this.stage != Stage.GEOMETRY;
		}
	}

	/**
	 * One {@code flip.<program>.<buffer>} line of shaders.properties.
	 *
	 * @param program {@code composite2}, or {@code deferred_pre} for the opening of a stage
	 * @param buffer  the spelling the pack used for the target, which may be a legacy name
	 */
	record Flip(String program, int target, String buffer, boolean value) {
	}

	/**
	 * A whole chain: the passes in frame order, the pack's own flips, the targets it keeps between
	 * frames, and the programs it or the user cuts out.
	 *
	 * @param disabled programs the pack turns off with {@code program.<name>.enabled=false}
	 * @param limit    {@code passes=N} of the pass filter, or -1 for all
	 */
	record Chain(List<Pass> passes, List<Flip> flips, Set<Integer> keeps, Set<String> disabled,
			int limit) {
	}

	/** What every target was on at one point of the walk: the half a read of it lands on. */
	record Reads(List<Half> halves) {

		Half of(int target) {
			return this.halves.get(target);
		}
	}

	/** A pass with the halves the reference gives it. */
	record Row(Pass pass, Reads reads, Map<Integer, Half> writes) {
	}

	/** The reference's whole answer for one chain. */
	record Expected(List<Row> rows, Map<String, Reads> passing, Set<Integer> doubled,
			Reads atEnd, Reads afterDeferred) {

		Set<Integer> flippedAtEnd() {
			Set<Integer> flipped = new TreeSet<>();
			for (int target = 0; target < UNIVERSE; target++) {
				if (this.atEnd.of(target) == Half.ALTERNATE) {
					flipped.add(target);
				}
			}

			return flipped;
		}

		Row row(String program) {
			return this.rows.stream().filter(row -> row.pass().program().equals(program)).findFirst()
					.orElseThrow();
		}
	}

	private static final List<Stage> PRE_STAGES =
			List.of(Stage.BEGIN, Stage.PREPARE, Stage.DEFERRED, Stage.COMPOSITE);

	private ParityModel() {
	}

	// ------------------------------------------------------------------------------------------
	// The reference walk
	// ------------------------------------------------------------------------------------------

	/**
	 * Walks the passes in frame order, tracking for every target the half its last write landed on.
	 * A target nothing has written is on the primary half.
	 */
	static Expected interpret(List<Pass> passes, List<Flip> flips) {
		Half[] latest = new Half[UNIVERSE];
		Arrays.fill(latest, Half.PRIMARY);

		Set<Integer> doubled = new TreeSet<>();
		List<Row> rows = new ArrayList<>();
		Map<String, Reads> passing = new LinkedHashMap<>();
		Reads afterDeferred = null;
		int opened = 0;

		for (Pass pass : passes) {
			// The first thing past the deferred stage finds it over: its opening flips have been
			// played, the composite stage's have not, and this is the picture the world's
			// translucents are drawn on.
			if (afterDeferred == null && pass.stage().compareTo(Stage.DEFERRED) > 0) {
				opened = open(opened, Stage.DEFERRED, latest, doubled, flips);
				afterDeferred = snapshot(latest);
			}

			opened = open(opened, pass.stage(), latest, doubled, flips);
			Reads reads = snapshot(latest);

			if (pass.computeOnly()) {
				passing.put(pass.program(), reads);
				continue;
			}

			Map<Integer, Half> writes = new LinkedHashMap<>();
			if (!pass.fullscreen()) {
				// Painted over the half it reads, and nothing turns over.
				pass.writes().forEach(target -> writes.put(target, latest[target]));
				rows.add(new Row(pass, reads, writes));
				continue;
			}

			// Never the half it reads: that is what two halves are for.
			pass.writes().forEach(target -> writes.put(target, latest[target].other()));
			rows.add(new Row(pass, reads, writes));

			// The write is published once the pass is over, unless the pack said not to.
			for (int target : pass.writes()) {
				doubled.add(target);
				if (!Boolean.FALSE.equals(directive(flips, pass.program(), target))) {
					latest[target] = writes.get(target);
				}
			}

			// A flip the pack asks for on top turns the target over once more, written or not. On a
			// target the pass also wrote that is a second turn, which leaves it where it began.
			for (int target : forced(flips, pass.program())) {
				latest[target] = latest[target].other();
				doubled.add(target);
			}
		}

		if (afterDeferred == null) {
			opened = open(opened, Stage.DEFERRED, latest, doubled, flips);
			afterDeferred = snapshot(latest);
		}

		open(opened, null, latest, doubled, flips);

		return new Expected(rows, passing, doubled, snapshot(latest), afterDeferred);
	}

	private static Reads snapshot(Half[] latest) {
		return new Reads(List.of(latest));
	}

	/**
	 * Plays the opening flips of every stage up to {@code through} that has not been opened yet, and
	 * says how many have been. A null stage means the end of the frame: everything left opens.
	 */
	private static int open(int opened, Stage through, Half[] latest, Set<Integer> doubled,
			List<Flip> flips) {
		int next = opened;
		while (next < PRE_STAGES.size()
				&& (through == null || PRE_STAGES.get(next).compareTo(through) <= 0)) {
			String name = PRE_STAGES.get(next).name().toLowerCase(Locale.ROOT) + "_pre";
			for (int target : forced(flips, name)) {
				latest[target] = latest[target].other();
				doubled.add(target);
			}

			next++;
		}

		return next;
	}

	/** The last thing the pack said about one target under one program, or null. */
	private static Boolean directive(List<Flip> flips, String program, int target) {
		Boolean found = null;
		for (Flip flip : flips) {
			if (flip.program().equals(program) && flip.target() == target) {
				found = flip.value();
			}
		}

		return found;
	}

	/** The targets a program is asked to turn over, each once, in the order first asked. */
	private static List<Integer> forced(List<Flip> flips, String program) {
		Set<Integer> targets = new LinkedHashSet<>();
		for (Flip flip : flips) {
			if (flip.program().equals(program)) {
				targets.add(flip.target());
			}
		}

		return targets.stream()
				.filter(target -> Boolean.TRUE.equals(directive(flips, program, target)))
				.toList();
	}

	// ------------------------------------------------------------------------------------------
	// What the schedule is handed and what a pack's files say
	// ------------------------------------------------------------------------------------------

	static List<TargetSchedule.Step> steps(List<Pass> passes) {
		return passes.stream()
				.filter(pass -> !pass.computeOnly())
				.map(pass -> new TargetSchedule.Step(pass.program(), pass.writes(), pass.fullscreen()))
				.toList();
	}

	static List<String> passing(List<Pass> passes) {
		return passes.stream().filter(Pass::computeOnly).map(Pass::program).toList();
	}

	static List<ShaderProperties.FlipDirective> directives(List<Flip> flips) {
		return flips.stream()
				.map(flip -> new ShaderProperties.FlipDirective(flip.program(), flip.buffer(), flip.value()))
				.toList();
	}

	/**
	 * The passes that really run: the pack's own switches applied first, and then the user's
	 * {@code passes=N}, which counts the full screen passes the pack keeps, the final never among
	 * them. Cut out before the walk and never trimmed after it, because a pass taken out moves the
	 * half every later pass reads.
	 */
	static List<Pass> walked(Chain chain) {
		List<Pass> kept = new ArrayList<>();
		int position = 0;
		for (Pass pass : chain.passes()) {
			if (chain.disabled().contains(pass.program())) {
				continue;
			}

			if (pass.fullscreen() && pass.stage() != Stage.FINAL) {
				if (chain.limit() >= 0 && position >= chain.limit()) {
					position++;
					continue;
				}

				position++;
			}

			kept.add(pass);
		}

		return kept;
	}

	/** The files of a pack that says exactly this chain, keyed by their path under {@code shaders/}. */
	static Map<String, String> files(Chain chain) {
		Map<String, String> files = new LinkedHashMap<>();
		StringBuilder properties = new StringBuilder();
		for (Flip flip : chain.flips()) {
			properties.append("flip.").append(flip.program()).append('.').append(flip.buffer())
					.append('=').append(flip.value() ? "true" : "false").append('\n');
		}

		for (String program : chain.disabled()) {
			properties.append("program.").append(program).append(".enabled=false\n");
		}

		files.put("shaders.properties", properties.toString());

		for (Pass pass : chain.passes()) {
			if (pass.computeOnly()) {
				continue;
			}

			StringBuilder file = new StringBuilder("#version 330 compatibility\n");
			if (!pass.inferred() && !pass.writes().isEmpty()) {
				file.append(directiveText(pass.program(), pass.writes())).append('\n');
			}

			if (!chain.keeps().isEmpty() && pass.program().equals(firstFragment(chain))) {
				file.append("/*\n");
				chain.keeps().forEach(target ->
						file.append("const bool colortex").append(target).append("Clear = false;\n"));
				file.append("*/\n");
			}

			for (int target : pass.reads()) {
				file.append("uniform sampler2D ").append(samplerName(target)).append(";\n");
			}

			file.append("void main() {\n\tgl_FragData[0] = vec4(1.0);\n}\n");
			files.put(pass.program() + ".fsh", file.toString());
		}

		return files;
	}

	private static String firstFragment(Chain chain) {
		return chain.passes().get(0).program();
	}

	/** The two spellings of the draw buffer directive, which one a pack uses is its own affair. */
	private static String directiveText(String program, List<Integer> writes) {
		boolean digits = writes.stream().allMatch(target -> target < 10);
		if (digits && program.length() % 2 == 0) {
			StringBuilder text = new StringBuilder("/* DRAWBUFFERS:");
			writes.forEach(text::append);

			return text.append(" */").toString();
		}

		StringBuilder text = new StringBuilder("/* RENDERTARGETS: ");
		for (int at = 0; at < writes.size(); at++) {
			text.append(at == 0 ? "" : ",").append(writes.get(at));
		}

		return text.append(" */").toString();
	}

	private static final List<String> LEGACY =
			List.of("gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4");

	/** Odd targets under a legacy name where one exists, the rest as {@code colortexN}. */
	static String samplerName(int target) {
		return target < LEGACY.size() && target % 2 == 1 ? LEGACY.get(target) : "colortex" + target;
	}

	// ------------------------------------------------------------------------------------------
	// Seeded chains
	// ------------------------------------------------------------------------------------------

	private static final List<String> GEOMETRY = List.of("gbuffers_basic", "gbuffers_textured",
			"gbuffers_hand", "gbuffers_hand_water", "gbuffers_skybasic", "gbuffers_skytextured",
			"gbuffers_clouds", "gbuffers_entities", "gbuffers_weather", "gbuffers_beaconbeam",
			"gbuffers_spidereyes", "gbuffers_armor_glint");

	/**
	 * What one seed asks of the generator. The defaults are the size of a real pack: BSL runs some
	 * forty passes, Complementary well over a hundred, on eight to sixteen targets.
	 */
	record Shape(int minPasses, int maxPasses, int targets, boolean flips, boolean passing) {

		static final Shape SMALL = new Shape(3, 30, 8, true, true);
		static final Shape REAL = new Shape(40, 150, 16, true, false);
	}

	/**
	 * A chain of a pack that could exist: a handful of geometry programs, then a run of numbered
	 * stages with gaps in their numbers, then the final. Which targets a pass names is drawn from a
	 * pool that leans on the low indices, as packs do.
	 */
	static Chain random(long seed, Shape shape) {
		Random random = new Random(seed);
		List<Integer> pool = new ArrayList<>();
		for (int target = 0; target < UNIVERSE; target++) {
			pool.add(target);
		}

		Collections.shuffle(pool, random);
		List<Integer> targets = new ArrayList<>(pool.subList(0, shape.targets()));
		if (!targets.contains(0)) {
			targets.set(0, 0);
		}

		List<Pass> passes = new ArrayList<>();
		int total = shape.minPasses() + random.nextInt(shape.maxPasses() - shape.minPasses() + 1);

		// The stage sizes are drawn as shares of the total, the composites taking most of it.
		int begins = share(random, total, 0.04);
		int prepares = share(random, total, 0.06);
		int deferreds = share(random, total, 0.12);
		int composites = Math.min(96, Math.max(1, total - begins - prepares - deferreds));

		numbered(passes, random, targets, Stage.BEGIN, "begin", begins, shape.passing());
		numbered(passes, random, targets, Stage.PREPARE, "prepare", prepares, shape.passing());

		// The two programs the chain reads the world through are always there, the rest of the
		// geometry is a random handful. They stand in the order of their file names, which is where
		// a pack's own directory listing puts them and which decides nothing about the halves.
		List<Pass> world = new ArrayList<>();
		geometry(world, random, targets, "gbuffers_terrain");
		geometry(world, random, targets, "gbuffers_water");
		int extra = random.nextInt(5);
		List<String> names = new ArrayList<>(GEOMETRY);
		Collections.shuffle(names, random);
		for (String name : names.subList(0, extra)) {
			geometry(world, random, targets, name);
		}

		world.sort(Comparator.comparing(Pass::program));
		passes.addAll(world);

		numbered(passes, random, targets, Stage.DEFERRED, "deferred", deferreds, shape.passing());
		numbered(passes, random, targets, Stage.COMPOSITE, "composite", composites, shape.passing());

		if (random.nextInt(10) < 8) {
			passes.add(new Pass(Stage.FINAL, 0, "final", List.of(), pick(random, targets, 1, 3),
					false, false));
		}

		List<Flip> flips = new ArrayList<>();
		if (shape.flips()) {
			flips = flipsFor(random, passes, targets);
		}

		Set<Integer> keeps = new TreeSet<>();
		for (int target : targets) {
			if (random.nextInt(8) == 0) {
				keeps.add(target);
			}
		}

		return new Chain(List.copyOf(passes), List.copyOf(flips), keeps, Set.of(), -1);
	}

	/** The same chain with some of its passes turned off by the pack and a cut made by the user. */
	static Chain withCuts(Chain chain, long seed) {
		Random random = new Random(seed);
		Set<String> disabled = new TreeSet<>();
		for (Pass pass : chain.passes()) {
			boolean protectedName = pass.program().equals("gbuffers_terrain")
					|| pass.program().equals("gbuffers_water")
					|| pass.computeOnly();
			if (!protectedName && random.nextInt(9) == 0) {
				disabled.add(pass.program());
			}
		}

		int limit = random.nextInt(3) == 0 ? random.nextInt(chain.passes().size() + 1) : -1;

		return new Chain(chain.passes(), chain.flips(), chain.keeps(), disabled, limit);
	}

	private static int share(Random random, int total, double fraction) {
		int most = (int) Math.round(total * fraction);

		return most == 0 ? random.nextInt(2) : random.nextInt(most + 1);
	}

	private static void geometry(List<Pass> passes, Random random, List<Integer> targets, String name) {
		boolean inferred = random.nextInt(12) == 0;
		List<Integer> writes = inferred ? List.of(0) : pick(random, targets, 1, 4).stream().toList();
		passes.add(new Pass(Stage.GEOMETRY, -1, name, writes, pick(random, targets, 0, 2), inferred,
				false));
	}

	/** Slots drawn without repeats, in order, so a pack may write composite, composite1, composite4. */
	private static void numbered(List<Pass> passes, Random random, List<Integer> targets, Stage stage,
			String family, int count, boolean allowPassing) {
		// At most a hundred slots, composite99 being the last name the format allows.
		int range = Math.min(100, Math.max(count + 3, count * 3 / 2));
		TreeSet<Integer> slots = new TreeSet<>();
		while (slots.size() < Math.min(count, range)) {
			slots.add(random.nextInt(range));
		}

		List<Pass> stagePasses = new ArrayList<>();
		for (int slot : slots) {
			String program = slot == 0 ? family : family + slot;
			boolean inferred = random.nextInt(20) == 0;
			List<Integer> writes = inferred ? List.of(0) : written(random, targets);
			stagePasses.add(new Pass(stage, slot, program, writes, pick(random, targets, 1, 4),
					inferred, false));
		}

		// A program shipped as a compute alone has no pass and no slot in the count. Its name never
		// meets a drawing pass's, which is the one case the schedule leaves undefined.
		if (allowPassing) {
			for (int slot = 0; slot < range; slot++) {
				if (!slots.contains(slot) && random.nextInt(8) == 0) {
					String program = slot == 0 ? family : family + slot;
					stagePasses.add(new Pass(stage, slot, program, List.of(), Set.of(), false, true));
				}
			}
		}

		stagePasses.sort(Comparator.comparingInt(Pass::slot));
		passes.addAll(stagePasses);
	}

	/** Mostly colortex0, as in every pack, and sometimes a second or third target beside it. */
	private static List<Integer> written(Random random, List<Integer> targets) {
		List<Integer> writes = new ArrayList<>();
		if (random.nextInt(10) < 7) {
			writes.add(0);
		}

		int more = random.nextInt(3);
		while (more-- > 0) {
			int target = targets.get(random.nextInt(targets.size()));
			if (!writes.contains(target)) {
				writes.add(target);
			}
		}

		if (writes.isEmpty()) {
			writes.add(targets.get(random.nextInt(targets.size())));
		}

		Collections.shuffle(writes, random);

		return writes;
	}

	private static Set<Integer> pick(Random random, List<Integer> targets, int least, int most) {
		int count = least + random.nextInt(most - least + 1);
		Set<Integer> picked = new TreeSet<>();
		for (int at = 0; at < count; at++) {
			picked.add(targets.get(random.nextInt(targets.size())));
		}

		return picked;
	}

	private static List<Flip> flipsFor(Random random, List<Pass> passes, List<Integer> targets) {
		List<Flip> flips = new ArrayList<>();
		for (Pass pass : passes) {
			if (pass.computeOnly()) {
				continue;
			}

			int roll = random.nextInt(100);
			if (pass.writes().isEmpty()) {
				// The final writes no colour target and may still be asked to turn one over.
				if (roll < 6) {
					flips.add(flip(random, pass.program(), targets.get(random.nextInt(targets.size())),
							true));
				}

				continue;
			}

			if (roll < 8) {
				// Keep a target where it was although the pass wrote it: a history buffer.
				int target = pass.writes().get(random.nextInt(pass.writes().size()));
				flips.add(flip(random, pass.program(), target, false));
			} else if (roll < 13) {
				// Turn a target over on top, written by this pass or not.
				int target = targets.get(random.nextInt(targets.size()));
				flips.add(flip(random, pass.program(), target, true));
			} else if (roll < 14) {
				// Said twice, the last one standing.
				int target = pass.writes().get(0);
				flips.add(flip(random, pass.program(), target, true));
				flips.add(flip(random, pass.program(), target, false));
			}
		}

		for (Stage stage : PRE_STAGES) {
			int roll = random.nextInt(100);
			if (roll < 12) {
				int target = targets.get(random.nextInt(targets.size()));
				flips.add(flip(random, stage.name().toLowerCase(Locale.ROOT) + "_pre", target, true));
			} else if (roll < 16) {
				int target = targets.get(random.nextInt(targets.size()));
				flips.add(flip(random, stage.name().toLowerCase(Locale.ROOT) + "_pre", target, false));
			}
		}

		return flips;
	}

	private static Flip flip(Random random, String program, int target, boolean value) {
		boolean legacy = target < LEGACY.size() && random.nextBoolean();

		return new Flip(program, target, legacy ? LEGACY.get(target) : "colortex" + target, value);
	}

	// ------------------------------------------------------------------------------------------
	// Odds and ends the tests share
	// ------------------------------------------------------------------------------------------

	/** The targets some full screen or geometry pass of the walk writes or samples. */
	static Set<Integer> named(List<Pass> passes) {
		Set<Integer> named = new TreeSet<>();
		for (Pass pass : passes) {
			named.addAll(pass.writes());
			named.addAll(pass.reads());
		}

		return named;
	}

	static Map<String, Pass> byProgram(List<Pass> passes) {
		Map<String, Pass> byName = new TreeMap<>();
		passes.forEach(pass -> byName.put(pass.program(), pass));

		return byName;
	}
}
