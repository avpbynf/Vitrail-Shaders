package dev.vitrail.pack;

import dev.vitrail.pack.IncludeExpander.ExpandedUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything one dimension of a pack needs to know about its colour targets, worked out once
 * when the pack is read.
 * <p>
 * Nothing here happens during a frame, and that is the design rather than an optimisation. A
 * target allocated halfway through a frame because a pass turned out to sample it is the bug
 * Iris had to work around, so the set of targets is closed at load time: every index a fragment
 * entry point of this dimension writes or samples exists, and no other does. The set is sparse
 * on purpose, holes and all, because five of the eight packs sample a target well past the
 * highest one they fill and allocating the gap costs between thirty and seventy megabytes at
 * 1080p for nothing.
 * <p>
 * The schedule is computed for the whole chain even though only one program runs today. It costs
 * nothing to compute and it is measured against the corpus, whereas an allocation for a program
 * that does not run is a texture nobody ever writes; so the plan describes the chain and
 * {@link TargetSchedule#doubledFor(Set)} decides what is actually paid for.
 * <p>
 * One thing this cannot report exactly and a reader should know: a directive is placed by the
 * entry point that pulled it in and by its line in the flattened unit, not by the file that
 * declares it. The expander does not carry provenance, so a table written once in a shared
 * include is attributed to whichever program was read last.
 */
public final class TargetPlan {

	/**
	 * Sampler declarations are found in the text rather than by translating, because thirty one
	 * programs are read here and one of them is translated. Optional precision qualifiers are
	 * allowed for; several packs still write them.
	 */
	private static final Pattern SAMPLER = Pattern.compile(
			"^\\s*uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?([iu]?sampler\\w*)\\s+([^;]*);.*$");

	private static final String FINAL = "final";

	/** Past this, a pack is asking for more memory than a plan should hand out without a word. */
	private static final long WARN_BYTES = 512L * 1024 * 1024;

	private final String packName;
	private final String dimension;
	private final String place;
	private final TargetDirectives directives;
	private final TargetSchedule schedule;
	private final Set<Integer> written;
	private final Set<Integer> sampled;
	private final Set<Integer> allocated;
	private final List<Integer> ordered;
	private final Set<Integer> persistent;
	private final List<String> unreadable;
	private final List<String> notes;
	private final int programsRead;
	private final long expandMillis;

	private TargetPlan(Draft draft) {
		this.packName = draft.packName;
		this.dimension = draft.dimension;
		this.place = draft.place;
		this.directives = draft.directives;
		this.schedule = draft.schedule;
		this.written = Collections.unmodifiableSet(new TreeSet<>(draft.written));
		this.sampled = Collections.unmodifiableSet(new TreeSet<>(draft.sampled));

		TreeSet<Integer> allocated = new TreeSet<>(draft.written);
		allocated.addAll(draft.sampled);
		this.allocated = Collections.unmodifiableSet(allocated);
		this.ordered = List.copyOf(allocated);

		TreeSet<Integer> persistent = new TreeSet<>();
		allocated.stream().filter(index -> !draft.directives.clears(index)).forEach(persistent::add);
		this.persistent = Collections.unmodifiableSet(persistent);

		this.unreadable = List.copyOf(draft.unreadable);
		this.programsRead = draft.programsRead;
		this.expandMillis = draft.expandMillis;
		this.notes = List.copyOf(notesFor(draft, this.ordered, this.persistent));
	}

	/**
	 * @param dimension the directory the running programs come from, for instance {@code world0}
	 */
	public static TargetPlan build(ShaderPackSource source, OptionIndex options, SettingSet settings,
			ShaderProperties properties, String dimension) throws IOException {
		DimensionSet dimensions = DimensionSet.discover(source);
		ProgramSet programs = ProgramSet.enumerate(source, dimensions);

		// A dimension directory replaces the root rather than being layered over it, so a pack
		// that ships nothing under world0 is read from the root and not from both.
		List<ProgramSet.ProgramKey> here = fragmentsOf(programs, dimension);
		Draft draft = new Draft();
		draft.packName = source.packName();
		draft.dimension = dimension;
		draft.place = here.isEmpty() ? ProgramSet.ROOT : dimension;
		draft.properties = properties;

		List<ProgramSet.ProgramKey> entries = here.isEmpty()
				? fragmentsOf(programs, ProgramSet.ROOT)
				: here;

		read(source, options, settings, properties, entries, draft);

		List<TargetSchedule.Step> steps = new ArrayList<>();
		for (ProgramSet.ProgramKey key : sorted(entries, TargetPlan::frameRank)) {
			String name = key.name().baseName();
			// The final pass writes the game's own target, never a colortex, so it flips nothing
			// however its directive reads.
			List<Integer> writes = name.equals(FINAL)
					? List.of()
					: draft.writes.getOrDefault(name, List.of());
			steps.add(new TargetSchedule.Step(name, writes, !geometry(key.name().family())));
		}

		draft.schedule = TargetSchedule.of(steps, properties.flips());
		draft.computes = programs.keys().stream()
				.filter(key -> key.stage() == ProgramStage.COMPUTE && key.dimension().equals(draft.place))
				.map(key -> key.name().baseName())
				.sorted()
				.distinct()
				.toList();

		return new TargetPlan(draft);
	}

	private static void read(ShaderPackSource source, OptionIndex options, SettingSet settings,
			ShaderProperties properties, List<ProgramSet.ProgramKey> entries, Draft draft) {
		IncludeExpander expander = new IncludeExpander(source, options, settings);
		TargetDirectives.Builder builder = TargetDirectives.builder();
		long began = System.nanoTime();

		// Iris's order, from ProgramSet.locateDirectives, and the last live declaration wins. One
		// unit is held at a time: the worst of the corpus expands to four hundred kilobytes and
		// one dimension has up to forty six of them.
		for (ProgramSet.ProgramKey key : sorted(entries, TargetPlan::directiveRank)) {
			Optional<Path> file = source.file(key.file());
			if (file.isEmpty()) {
				draft.unreadable.add(key.file());
				continue;
			}

			ExpandedUnit unit;
			try {
				unit = expander.expand(file.get());
			} catch (IOException | RuntimeException e) {
				// One unreadable composite must not cost the pack every other target it declares.
				draft.unreadable.add(key.file());
				continue;
			}

			draft.programsRead++;
			builder.accept(key.file(), unit);

			List<Integer> writes = DrawBuffers.parse(unit);
			draft.writes.put(key.name().baseName(), writes);
			draft.written.addAll(writes);

			for (String sampler : samplers(unit)) {
				TargetName.index(sampler).ifPresent(draft.sampled::add);
			}
		}

		builder.accept(properties, settings.globalDefines(options));
		draft.directives = builder.build();
		draft.expandMillis = (System.nanoTime() - began) / 1_000_000L;
	}

	/** Every name declared as a sampler on a live line, whatever the sampler is for. */
	private static List<String> samplers(ExpandedUnit unit) {
		List<String> names = new ArrayList<>();
		List<String> lines = unit.lines();

		for (int line = 0; line < lines.size(); line++) {
			if (!unit.isLive(line)) {
				continue;
			}

			Matcher matcher = SAMPLER.matcher(lines.get(line));
			if (!matcher.matches()) {
				continue;
			}

			for (String declarator : matcher.group(2).split(",")) {
				String name = declarator.trim();
				int bracket = name.indexOf('[');
				if (bracket >= 0) {
					name = name.substring(0, bracket).trim();
				}

				if (!name.isEmpty()) {
					names.add(name);
				}
			}
		}

		return names;
	}

	public String packName() {
		return this.packName;
	}

	public String dimension() {
		return this.dimension;
	}

	/** Where the entry points were actually read from: the dimension, or the root when it ships none. */
	public String place() {
		return this.place;
	}

	public TargetDirectives directives() {
		return this.directives;
	}

	public TargetSchedule schedule() {
		return this.schedule;
	}

	/** Written or sampled by some program of the place. Nothing else is allocated. */
	public Set<Integer> allocated() {
		return this.allocated;
	}

	/** Sorted, holes and all: a pack declaring 0 to 9 and 16 has eleven entries. */
	public List<Integer> ordered() {
		return this.ordered;
	}

	public Set<Integer> written() {
		return this.written;
	}

	public Set<Integer> sampled() {
		return this.sampled;
	}

	/** {@code colortexNClear = false}. Read, exposed, and only honoured past the full clear. */
	public Set<Integer> persistent() {
		return this.persistent;
	}

	public int programsRead() {
		return this.programsRead;
	}

	public long expandMillis() {
		return this.expandMillis;
	}

	/** Entry points that could not be expanded, by name. One bad composite must not stop a pack. */
	public List<String> unreadable() {
		return this.unreadable;
	}

	/** One line per promotion, replacement, unknown name, conflict, or thing we will not do. */
	public List<String> notes() {
		return this.notes;
	}

	public long bytesAt(int screenWidth, int screenHeight, Set<Integer> doubledNow) {
		long total = 0L;

		for (int index : this.ordered) {
			TargetSize size = this.directives.size(index);
			long pixels = (long) size.width(screenWidth) * size.height(screenHeight);
			long bytes = pixels * this.directives.format(index).used().bytesPerPixel();
			total += doubledNow.contains(index) ? bytes * 2L : bytes;
		}

		return total;
	}

	private static List<String> notesFor(Draft draft, List<Integer> ordered, Set<Integer> persistent) {
		List<String> notes = new ArrayList<>(draft.directives.notes());
		TargetDirectives directives = draft.directives;

		for (int index : ordered) {
			TargetFormat.Resolution format = directives.format(index);
			if (format.reason() != TargetFormat.Reason.EXACT) {
				notes.add(TargetName.canonical(index) + " asked for " + format.declared()
						+ ", allocated " + format.used() + " (" + format.reason() + ") from "
						+ directives.formatSource(index)
						+ (format.alphaAdded() ? ", clear alpha forced to 1 to match GL" : ""));
			}

			TargetSize size = directives.size(index);
			if (!size.full()) {
				notes.add(TargetName.canonical(index) + " is sized by shaders.properties, "
						+ (size.relative() ? size.width() + " by " + size.height() + " of the screen"
								: (int) size.width() + " by " + (int) size.height() + " pixels"));
			}
		}

		if (ordered.contains(0) && !directives.declaresClearColour(0)) {
			notes.add("colortex0 is cleared to opaque black: nothing supplies the fog colour yet");
		}

		if (!persistent.isEmpty()) {
			notes.add("targets the pack keeps between frames: " + persistent);
		}

		notes.addAll(directives.conflicts());

		if (!directives.mipmapRequests().isEmpty()) {
			notes.add(directives.mipmapRequests().size() + " programs ask for mipmaps on "
					+ mipmappedTargets(directives) + " targets, and 26.2 has no way to generate "
					+ "them: " + directives.mipmapRequests());
		}

		List<String> perBuffer = draft.properties.blend().stream()
				.filter(directive -> directive.buffer() != null)
				.map(directive -> directive.program() + "." + directive.buffer())
				.toList();
		if (!perBuffer.isEmpty()) {
			notes.add("per buffer blending is not expressible, one pipeline carries one blend "
					+ "function for every target it writes: " + perBuffer);
		}

		if (!draft.computes.isEmpty()) {
			notes.add("compute programs skipped, no stage exists for them yet: " + draft.computes);
		}

		List<String> silent = draft.writes.entrySet().stream()
				.filter(entry -> entry.getValue().isEmpty() && !entry.getKey().equals(FINAL))
				.map(Map.Entry::getKey)
				.sorted()
				.toList();
		if (!silent.isEmpty()) {
			notes.add("programs declaring no draw buffer, which Iris would send to colortex0 and "
					+ "this plan does not: " + silent);
		}

		List<Integer> neverWritten = draft.sampled.stream()
				.filter(index -> !draft.written.contains(index))
				.sorted()
				.toList();
		if (!neverWritten.isEmpty()) {
			notes.add("sampled and never written by this pack, so they read their clear colour: "
					+ neverWritten);
		}

		if (!draft.unreadable.isEmpty()) {
			notes.add("entry points that could not be expanded and declare nothing: "
					+ draft.unreadable);
		}

		return notes;
	}

	private static int mipmappedTargets(TargetDirectives directives) {
		Set<Integer> targets = new TreeSet<>();
		directives.mipmapRequests().values().forEach(targets::addAll);

		return targets.size();
	}

	private static List<ProgramSet.ProgramKey> fragmentsOf(ProgramSet programs, String place) {
		return programs.keys().stream()
				.filter(key -> key.stage() == ProgramStage.FRAGMENT)
				.filter(key -> key.dimension().equals(place))
				.toList();
	}

	private static List<ProgramSet.ProgramKey> sorted(List<ProgramSet.ProgramKey> entries,
			ToIntFunction<String> rank) {
		return entries.stream()
				.sorted(Comparator
						.comparingInt((ProgramSet.ProgramKey key) -> rank.applyAsInt(key.name().family()))
						.thenComparingInt(key -> key.name().slot())
						.thenComparing(ProgramSet.ProgramKey::file))
				.toList();
	}

	/**
	 * The order Iris folds directives in, from {@code ProgramSet.locateDirectives}. Setup programs
	 * are not in it: they go to the compute list there and are only ever read for their work group
	 * size, so they declare no format however they are written.
	 */
	private static int directiveRank(String family) {
		return switch (family) {
			case "shadowcomp" -> 0;
			case "begin" -> 1;
			case "prepare" -> 2;
			case "deferred" -> 4;
			case "composite" -> 5;
			default -> 3;
		};
	}

	/** The order the frame runs in, which is not the order directives are folded in. */
	private static int frameRank(String family) {
		if (geometry(family)) {
			return 3;
		}

		return switch (family) {
			case "begin" -> 0;
			case "shadowcomp" -> 1;
			case "prepare" -> 2;
			case "deferred" -> 4;
			case "composite" -> 5;
			default -> 6;
		};
	}

	/** A pass drawn over the world rather than over a quad, which never flips anything. */
	private static boolean geometry(String family) {
		return family.startsWith("gbuffers") || family.startsWith("dh_")
				|| family.equals("shadow") || family.startsWith("shadow_");
	}

	/** Warned about rather than refused: a stutter is easier to read than a black screen. */
	public boolean heavy(int screenWidth, int screenHeight, Set<Integer> doubledNow) {
		return bytesAt(screenWidth, screenHeight, doubledNow) > WARN_BYTES;
	}

	private static final class Draft {

		private final Map<String, List<Integer>> writes = new LinkedHashMap<>();
		private final Set<Integer> written = new TreeSet<>();
		private final Set<Integer> sampled = new TreeSet<>();
		private final List<String> unreadable = new ArrayList<>();

		private String packName;
		private String dimension;
		private String place;
		private ShaderProperties properties;
		private TargetDirectives directives;
		private TargetSchedule schedule;
		private List<String> computes = List.of();
		private int programsRead;
		private long expandMillis;
	}
}
