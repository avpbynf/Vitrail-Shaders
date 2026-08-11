package dev.vitrail.pack.target;

import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.program.BlendMode;
import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.program.ProgramNames;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;

import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;

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
import java.util.stream.Collectors;

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
 * The schedule describes exactly the programs that run and no others, which is why the programs
 * a pack switches off are dropped here rather than anywhere downstream. Removing a pass changes
 * the half every later pass reads and writes, so a second walk taken over a different set would
 * disagree with this one, and the disagreement produces no error at all: only an image that is
 * plausible and wrong. There is therefore one walk, it lives here, and nothing else in the
 * engine is allowed to redo it.
 * <p>
 * What a program is switched off <em>by</em> does not change what is allocated. A target a
 * silent program declares a format for is still allocated, because the pack may switch that
 * program back on without the plan being rebuilt from a different set of files.
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

	/** What {@link #disabled()} says when the pack keeps a pass and {@code passes=} does not. */
	public static final String LEFT_OUT = "passes=";

	/**
	 * What {@link #disabled()} says when the backend cannot build the pass at all. Told apart from
	 * {@link #LEFT_OUT} because one of the two is a choice and the other is a wall, and a reader
	 * looking for why the picture is short a pass has to be able to tell which.
	 */
	public static final String UNBINDABLE = "sampler this backend cannot bind";

	/** Where the world is drawn in the frame order, which is what the scene seed stands in for. */
	private static final int GEOMETRY_RANK = ProgramNames.GEOMETRY_RANK;

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
	private final List<String> running;
	private final Map<String, String> disabled;
	private final Map<String, List<Integer>> writes;

	/**
	 * What each program asks to blend with, by its bare name, for the whole program form of the
	 * directive. Only the programs that say something are in here; a program that says nothing is
	 * answered by whatever the engine would have used anyway, which is not this plan's business.
	 */
	private final Map<String, BlendMode> blend;

	private final Set<String> inferred;
	private final Map<String, Set<Integer>> samples;
	private final int geometryAt;
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
		this.running = List.copyOf(draft.running);
		this.disabled = Collections.unmodifiableMap(new LinkedHashMap<>(draft.disabled));
		this.writes = Collections.unmodifiableMap(new LinkedHashMap<>(draft.effective));
		this.blend = blendOf(draft);
		this.inferred = Collections.unmodifiableSet(new TreeSet<>(draft.inferred));
		this.samples = Collections.unmodifiableMap(new LinkedHashMap<>(draft.samples));
		this.geometryAt = draft.geometryAt;
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
	 * Reads what a place declares about its colour targets, and schedules the chain over them.
	 *
	 * @param dimension the directory the running programs come from, for instance {@code world0}
	 */
	public static TargetPlan build(ShaderPackSource source, OptionIndex options, SettingSet settings,
			ShaderProperties properties, String dimension) throws IOException {
		return build(source, options, settings, properties, dimension, ChainFilter.ALL);
	}

	/**
	 * The same, over the part of the chain the user asked to keep.
	 *
	 * @param filter what the user asked to run on top of what the pack keeps. The schedule is
	 *               rebuilt on what it leaves rather than trimmed afterwards, because a pass
	 *               taken out moves the half every later pass reads.
	 */
	public static TargetPlan build(ShaderPackSource source, OptionIndex options, SettingSet settings,
			ShaderProperties properties, String dimension, ChainFilter filter) throws IOException {
		DimensionSet dimensions = DimensionSet.discover(source);
		ProgramSet programs = ProgramSet.enumerate(source, dimensions);

		// A dimension directory replaces the root rather than being layered over it, and what
		// decides is that the directory EXISTS, never what it holds. Iris builds a program set for
		// a folder it finds and hands back the base set only when the folder is named and absent,
		// so a pack shipping an empty world0 draws nothing in the overworld rather than falling
		// back to the root. Reproduced rather than improved on: emptying a dimension is the only
		// way a pack has of saying "nothing here", and reading the root instead would overrule it.
		boolean present = !dimension.equals(ProgramSet.ROOT)
				&& source.topLevelDirectories().contains(dimension);

		Draft draft = new Draft();
		draft.packName = source.packName();
		draft.dimension = dimension;
		draft.place = present ? dimension : ProgramSet.ROOT;
		draft.properties = properties;

		List<ProgramSet.ProgramKey> entries = fragmentsOf(programs, draft.place);

		read(source, options, settings, properties, entries, draft);
		walk(properties, options, settings.globalDefines(options), entries, filter, draft);

		draft.computes = programs.keys().stream()
				.filter(key -> key.stage() == ProgramStage.COMPUTE && key.dimension().equals(draft.place))
				.map(key -> key.name().baseName())
				.sorted()
				.distinct()
				.toList();

		return new TargetPlan(draft);
	}

	/**
	 * The one walk of the frame: what runs, in what order, writing what. Everything downstream
	 * reads its answer and none of it works the answer out again.
	 */
	private static void walk(ShaderProperties properties, OptionIndex options,
			Map<String, String> defines, List<ProgramSet.ProgramKey> entries, ChainFilter filter,
			Draft draft) {
		Map<String, Boolean> toggles = properties.programToggles(defines, options);
		Map<String, String> conditions = properties.programConditions(defines);
		List<TargetSchedule.Step> steps = new ArrayList<>();
		int rank = 0;

		for (ProgramSet.ProgramKey key : sorted(entries, ProgramNames::frameRank)) {
			String family = key.name().family();
			String name = key.name().baseName();

			// Noted while the entries are still in frame order, because running() drops the geometry
			// that marks the spot and nothing downstream could then work out where the world goes.
			if (draft.geometryAt < 0 && ProgramNames.frameRank(family) >= GEOMETRY_RANK) {
				draft.geometryAt = draft.running.size();
			}

			// A shadow composite runs over the shadow targets, with a flip counter of its own, so
			// its draw buffers name shadowcolor and never colortex. Left in the walk it would
			// allocate colour targets on their indices, draw a full screen pass over them and move
			// the half every later pass reads. It belongs to a stage of its own, after the shadow
			// map is drawn and over its colour buffers, and this engine has no such stage.
			if (ProgramNames.shadowComposite(family)) {
				draft.shadowComposites.add(name);
				continue;
			}

			boolean fullscreen = !ProgramNames.geometry(family);
			List<Integer> writes = writesOf(name, ProgramNames.shadowGeometry(family), draft);
			draft.effective.put(name, writes);

			// A geometry pass writes the half it reads and flips nothing, so keeping it in the walk
			// costs nothing and holds the side the gbuffers programs read, six families of which run.
			if (!fullscreen) {
				steps.add(new TargetSchedule.Step(name, writes, false));
				continue;
			}

			// The key is the path the pack wrote and only that. BSL conditions world0/composite1
			// and world-1/composite1 on two different expressions, so falling back to the bare
			// name would run in the Nether a pass the pack switched off there.
			String path = draft.place.isEmpty() ? name : draft.place + "/" + name;
			if (Boolean.FALSE.equals(toggles.get(path))) {
				draft.disabled.put(name, conditions.getOrDefault(path, "shaders.properties"));
				continue;
			}

			// The final is neither counted nor filtered: without it nothing reaches the screen at
			// all, and "no passes" has to mean the picture this chain is measured against.
			if (!name.equals(FINAL)) {
				// The rank is spent before anything is refused, so that a second walk taken with
				// more refusals hands out the ranks the first one did. Renumbering here would make
				// passes=6 mean six other programs the moment one of them turned out unbuildable.
				int position = rank++;
				if (!filter.accepts(name, position)) {
					draft.disabled.put(name, filter.refuses(name) ? UNBINDABLE : LEFT_OUT);
					continue;
				}
			}

			draft.running.add(name);
			steps.add(new TargetSchedule.Step(name, writes, true));
		}

		// A place shipping nothing from the geometry stage onwards still draws its world somewhere,
		// and that is after everything this walk did keep.
		if (draft.geometryAt < 0) {
			draft.geometryAt = draft.running.size();
		}

		draft.schedule = TargetSchedule.of(steps, properties.flips());
	}

	/**
	 * What a program really writes, which is not always what it says. Iris infers a single
	 * attachment zero when a fragment declares none, and the packs are written against that, so a
	 * program with no directive is sent to colortex0 and colortex0 is allocated for it.
	 * <p>
	 * <strong>Geometry is inferred too, and it used to be left out.</strong> The reason it was left
	 * out expired: nothing of it was drawn, so the inference would have paid for a target on behalf of
	 * a program that never ran. The terrain, the sky, the clouds, the weather, the particles and the
	 * entities are drawn now, and what the exemption really bought was a program of the pack drawing
	 * into the GAME'S target instead of the pack's, where nothing of the chain reports it back. Body
	 * Camera is the measured case and it is total rather than cosmetic: it
	 * has no clouds at all, at either setting of the pack, because its {@code gbuffers_clouds}
	 * declares no draw buffer and its image goes somewhere nothing collects. Iris reads the same file
	 * as {@code /* DRAWBUFFERS:0 *}{@code /}, {@code shaderpack/properties/ProgramDirectives.java:55}.
	 * <p>
	 * What it costs, measured on the corpus in August 2026, is TWO colour targets over twenty five
	 * places, and both are Sildur's: its Nether and its End allocate a colortex0 for the
	 * {@code gbuffers_water} that now infers one. Its root does not, the only thing it ships without
	 * a directive there being its shadow program. Everywhere else the target was already allocated,
	 * and what those places gain is an answer rather than an allocation - Body Camera's two lower
	 * dimensions had nowhere at all to paint the scene seed, and now have one. The comparison with
	 * Iris is not that it pays the same cost: it offers a pack thirty two indices rather than the
	 * sparse set this plan allocates ({@code gl/IrisLimits.java:11}) and creates the images behind
	 * them on demand, so an inferred nought costs it nothing to name.
	 *
	 * @param shadowGeometry whether this is a program drawn from the light, which is the one family
	 *                       held out. Its draw buffers index the shadow targets, which this plan does
	 *                       not hold, so inferring here would allocate a colour target on behalf of a
	 *                       program that never writes one and record it as written, which is the
	 *                       reading {@code ChainPlan} refuses in as many words. What Iris does with a
	 *                       shadow program that declares nothing is NOT to infer one target: it takes
	 *                       the unknown as {@code {0, 1}} and binds both shadow colour buffers,
	 *                       {@code pipeline/programs/ShaderCreator.java:331}. This engine binds
	 *                       shadowcolor0 alone whatever the pack declares
	 *                       ({@code render/GeometryProgram.java:392}), which is a divergence of its
	 *                       own and older than this walk: a pack counting on shadowcolor1 loses it,
	 *                       and nothing here would say so
	 */
	private static List<Integer> writesOf(String name, boolean shadowGeometry, Draft draft) {
		// The final writes the game's own target, never a colortex, so it flips nothing however
		// its directive reads.
		if (name.equals(FINAL)) {
			return List.of();
		}

		// The geometry drawn from the light contributes NO colour writes, declared or inferred. Its
		// indices are shadowcolor and they are remembered raw elsewhere; letting the declared ones
		// through here would put them back in the schedule, and with them in every verdict.
		if (shadowGeometry) {
			return List.of();
		}

		List<Integer> declared = draft.writes.getOrDefault(name, List.of());
		if (!declared.isEmpty() || draft.unexpanded.contains(name)) {
			return declared;
		}

		draft.inferred.add(name);
		draft.written.add(0);

		return List.of(0);
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
				draft.unexpanded.add(key.name().baseName());
				continue;
			}

			ExpandedUnit unit;
			try {
				unit = expander.expand(file.get());
			} catch (IOException | RuntimeException e) {
				// One unreadable composite must not cost the pack every other target it declares.
				draft.unreadable.add(key.file());
				draft.unexpanded.add(key.name().baseName());
				continue;
			}

			draft.programsRead++;
			// Its format table counts whatever the program is: Iris folds the directives of every
			// fragment stage it reads, shadow composites included, and a const written there is
			// written for the whole place.
			builder.accept(key.file(), unit);

			// What it writes does not, for anything bound to the shadow targets: those indices name
			// shadowcolor, so allocating colour targets for them would pay for images no program of
			// this place ever touches, and recording them as WRITTEN is how a verdict comes to tell a
			// reader that geometry fills a colortex nothing fills. Both families that draw there are
			// caught: the shadow composites, and the geometry drawn from the light.
			//
			// The second was missing and it was not inert. Both Complementary include a shadow
			// program declaring DRAWBUFFERS:01, so colortex1 was allocated and reported written on
			// their account in all three of their places, six notes, when no camera geometry of
			// either pack writes index one at all.
			String family = key.name().family();
			if (ProgramNames.shadowComposite(family)) {
				continue;
			}

			// A SAMPLER is not ambiguous the way a draw buffer is: colortexN means colortexN whichever
			// end of the world the program is drawn from, so the scan below runs for the shadow
			// geometry too. Skipping it with the writes, which is what the first shape of this guard
			// did, leaves a colour target sampled only from the light unallocated and bound to the
			// white stand-in.
			boolean fromTheLight = ProgramNames.shadowGeometry(family);
			List<Integer> writes = DrawBuffers.parse(unit);

			// Remembered even for the shadow, and not folded into what is allocated. The raw
			// declaration is what tells a program that declares nothing from one whose indices were
			// dropped here, and a note that cannot tell them apart says "declaring no draw buffer"
			// about a program declaring three.
			draft.writes.put(key.name().baseName(), writes);
			if (fromTheLight) {
				draft.fromTheLight.add(key.name().baseName());
			} else {
				draft.written.addAll(writes);
			}

			Set<Integer> indices = new TreeSet<>();
			for (String sampler : samplers(unit)) {
				TargetName.index(sampler).ifPresent(index -> {
					draft.sampled.add(index);
					indices.add(index);
				});
			}

			draft.samples.put(key.name().baseName(), indices);
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

			for (String declarator : matcher.group(2).split(",", -1)) {
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

	/**
	 * The full screen programs of this place that both the pack and the filter keep, in frame
	 * order, the final last. This is the same set, in the same order, that {@link #schedule}
	 * describes, and there is no other.
	 */
	public List<String> running() {
		return this.running;
	}

	/**
	 * Full screen programs this place ships and does not run, by bare name, with the reason. The
	 * reason is the pack's own expression when the pack switched it off, {@code MOTION_BLUR} or
	 * {@code false}, and {@link #LEFT_OUT} when {@code passes=} did, so a log can tell the two
	 * apart by printing {@code name (reason)}.
	 */
	public Map<String, String> disabled() {
		return this.disabled;
	}

	/** The draw buffers of one program after inference, by bare name. Empty for the final. */
	public List<Integer> writes(String program) {
		return this.writes.getOrDefault(bareName(program), List.of());
	}

	/** Programs whose draw buffers nobody wrote down and that were sent to colortex0. */
	public Set<String> inferredWrites() {
		return this.inferred;
	}

	/**
	 * The colour targets one program samples, read from its own live declarations rather than
	 * from a translation, so that the whole chain can be checked without compiling anything.
	 */
	public Set<Integer> samples(String program) {
		return this.samples.getOrDefault(bareName(program), Set.of());
	}

	/**
	 * Where the world is drawn among {@link #running()}: how many of those programs come before it.
	 * <p>
	 * The scene seed carries in what this engine still leaves to the game - the entities above all,
	 * already lit and already tone mapped - so this is where that seed goes. It is the point OptiFine
	 * draws the world at, which is not where the families this engine does draw fill their targets:
	 * the chunk renderer has finished with the opaque ones before the first pass of the chain runs.
	 * It is answered here rather than downstream because {@link #running()} holds no geometry
	 * to mark the spot, and a frame that painted the seed anywhere else would contradict the very
	 * schedule that gave it its half: a begin or a prepare writing the same target would land on
	 * the wrong side of it, and one sampling it would be handed this frame's world where the walk
	 * says it reads a clear colour.
	 */
	public int geometryAt() {
		return this.geometryAt;
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
								: (int) size.width() + " by " + (int) size.height() + " pixels")
						+ (size.overCap() ? ", capped at " + TargetSize.MAX_DIMENSION
								+ " texels a side, which is all this engine will allocate" : ""));
			}
		}

		if (!persistent.isEmpty()) {
			notes.add("targets the pack keeps between frames: " + persistent);
		}

		notes.addAll(directives.conflicts());

		if (!directives.mipmapRequests().isEmpty()) {
			notes.add(directives.mipmapRequests().size() + " programs read lods, on "
					+ directives.mipmapped().size() + " targets that carry a mip chain filled by the "
					+ "engine's own reduction before each of those programs draws: "
					+ directives.mipmapRequests());
		}

		List<String> unreadable = draft.properties.blend().stream()
				.filter(directive -> directive.buffer() == null)
				.filter(directive -> BlendMode.parse(directive.value()).isEmpty())
				.map(directive -> directive.program() + "=" + directive.value())
				.toList();
		if (!unreadable.isEmpty()) {
			notes.add("blend directives in a form this engine does not express, so those programs "
					+ "keep the blending the engine would have used: " + unreadable);
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

		if (!draft.shadowComposites.isEmpty()) {
			notes.add("shadow composites skipped, they draw full screen over the shadow colour "
					+ "buffers on a flip counter of their own and this engine runs no stage there, "
					+ "so their draw buffers name no colour target of this place: "
					+ draft.shadowComposites);
		}

		// In frame order rather than sorted, so that the line reads the way the chain runs, and
		// each one carries what switched it off: the pack's own expression, or the filter.
		if (!draft.disabled.isEmpty()) {
			notes.add("full screen programs this place ships and does not run: "
					+ draft.disabled.entrySet().stream()
							.map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
							.collect(Collectors.joining(", ")));
		}

		// Geometry is in this line since the inference took it in, and the line it replaces is worth
		// remembering: it named the same programs and ended "which Iris would send to colortex0 and
		// this plan does not", which is a defect written out as a note and left there.
		if (!draft.inferred.isEmpty()) {
			notes.add("programs declaring no draw buffer, sent to colortex0 as Iris does: "
					+ new TreeSet<>(draft.inferred));
		}

		// And the geometry drawn from the light, said rather than dropped: it is the one family whose
		// draw buffers this plan reads and then does not use, so a reader who greps for a shadow
		// program and finds it nowhere would conclude it was never read at all.
		//
		// The list is the programs THEMSELVES and carries no claim about what they declare. Building
		// it from "writes nothing" instead, which is what the first shape of this note did, called
		// every one of them undeclared: thirteen of the twenty five places gained a line saying
		// "declaring no draw buffer" about a shadow program declaring one, Reverie's being
		// RENDERTARGETS:0,2,1.
		if (!draft.fromTheLight.isEmpty()) {
			notes.add("programs drawn from the light, whose draw buffers would name shadowcolor and "
					+ "are therefore read as no colour target of this place, declared or not: "
					+ new TreeSet<>(draft.fromTheLight));
		}

		// The defect this closes: a target a pack declares a format for and nothing allocates
		// simply vanished from the inventory, so nothing told a reader whether it was unread or
		// dropped on the way.
		List<Integer> declaredOnly = directives.declared().stream()
				.filter(index -> !ordered.contains(index))
				.sorted()
				.toList();
		if (!declaredOnly.isEmpty()) {
			notes.add("targets this pack declares a format for and that no program of this place "
					+ "writes or samples, so nothing is allocated for them: " + declaredOnly);
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

	/**
	 * The whole program blend directives, read once and kept by bare name.
	 * <p>
	 * The per buffer form is left out here and named in the notes instead: one pipeline carries one
	 * blend function for every target it writes, so honouring half of such a directive would be
	 * worse than honouring none of it. A value in a form this engine cannot read is left out too,
	 * and named the same way.
	 */
	private static Map<String, BlendMode> blendOf(Draft draft) {
		Map<String, BlendMode> blend = new LinkedHashMap<>();
		for (ShaderProperties.BlendDirective directive : draft.properties.blend()) {
			if (directive.buffer() != null) {
				continue;
			}

			BlendMode.parse(directive.value())
					.ifPresent(mode -> blend.put(bareName(directive.program()), mode));
		}

		return Collections.unmodifiableMap(blend);
	}

	/**
	 * What this program asks to blend with, or empty when it asks for nothing and the engine's own
	 * choice stands.
	 *
	 * @param program the bare name, {@code gbuffers_water}, not the file that ends up serving it
	 */
	public Optional<BlendMode> blend(String program) {
		return Optional.ofNullable(this.blend.get(bareName(program)));
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

	/**
	 * A program is named here by itself, {@code composite1}, while the rest of the engine names
	 * it by where it lives, {@code world0/composite1}. Both are accepted, as in the schedule, so
	 * that neither side has to remember which form the other one uses.
	 */
	private static String bareName(String program) {
		int slash = program.lastIndexOf('/');

		return slash < 0 ? program : program.substring(slash + 1);
	}

	private static final class Draft {

		private final Map<String, List<Integer>> writes = new LinkedHashMap<>();
		private final Map<String, List<Integer>> effective = new LinkedHashMap<>();
		private final Map<String, Set<Integer>> samples = new LinkedHashMap<>();
		private final Set<String> inferred = new TreeSet<>();
		private final Set<String> unexpanded = new TreeSet<>();

		/** Geometry drawn from the light, whose draw buffers this plan reads and does not use. */
		private final Set<String> fromTheLight = new TreeSet<>();

		private final List<String> running = new ArrayList<>();
		private final Map<String, String> disabled = new LinkedHashMap<>();
		private final Set<String> shadowComposites = new TreeSet<>();
		private final Set<Integer> written = new TreeSet<>();
		private final Set<Integer> sampled = new TreeSet<>();
		private final List<String> unreadable = new ArrayList<>();

		private int geometryAt = -1;
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
