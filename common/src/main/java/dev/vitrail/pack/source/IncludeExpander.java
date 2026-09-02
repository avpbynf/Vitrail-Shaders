package dev.vitrail.pack.source;

import dev.vitrail.pack.option.OptionRewriter;
import dev.vitrail.pack.option.SettingSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flattens one entry file into a single unit: includes spliced in, settings applied where they
 * are declared, dead branches left in place for the compiler to drop.
 * <p>
 * Which includes are followed depends on the settings, because the directive can sit inside a
 * conditional. That makes the include graph a function of the settings rather than a property
 * of the pack, and it is why a unit has to be rebuilt when a setting changes rather than
 * patched.
 * <p>
 * An include in a branch that is off becomes a comment rather than staying as it was. Keeping
 * it would leave a directive GLSL has no notion of sitting in the text, which is a gamble on
 * how the compiler treats directives inside a block it is discarding.
 */
public final class IncludeExpander {

	/** Deep enough for anything real: the worst of the corpus reaches five. */
	private static final int MAX_DEPTH = 32;

	/**
	 * A depth limit alone does not bound the work. Includes form a graph, not a tree, and a
	 * file that includes the next one twice doubles at every level: eighteen small files with no
	 * cycle at all produce half a million lines and take forty seconds, and thirty-two levels
	 * would run for hours before running out of memory. Neither of those is catchable where this
	 * is called from, so the total is bounded too.
	 */
	private static final int MAX_FILES = 20_000;

	private static final int MAX_LINES = 400_000;

	/**
	 * Lines are not the cost that matters downstream. Four hundred thousand of them held one
	 * pack's worth of tokens in a gigabyte and a half, which is an {@code OutOfMemoryError} rather
	 * than an exception anyone can catch. The worst unit of the corpus is four hundred kilobytes,
	 * so this leaves an order of magnitude and still bounds the translator that reads it.
	 */
	private static final int MAX_CHARACTERS = 4_000_000;

	private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s+[<\"](.+?)[>\"].*$");
	private static final Pattern IF_DEFINED = Pattern.compile("^\\s*#\\s*(ifdef|ifndef)\\s+([A-Za-z_]\\w*).*$");
	private static final Pattern IF = Pattern.compile("^\\s*#\\s*if\\s+(.*)$");
	private static final Pattern ELIF = Pattern.compile("^\\s*#\\s*elif\\s+(.*)$");
	private static final Pattern ELSE = Pattern.compile("^\\s*#\\s*else\\b.*$");
	private static final Pattern ENDIF = Pattern.compile("^\\s*#\\s*endif\\b.*$");
	private static final Pattern DEFINE = Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s*(.*)$");
	private static final Pattern UNDEF = Pattern.compile("^\\s*#\\s*undef\\s+([A-Za-z_]\\w*).*$");
	private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\s+(.*)$");

	private final ShaderPackSource source;
	private final SettingSet settings;

	public IncludeExpander(ShaderPackSource source, SettingSet settings) {
		this.source = source;
		this.settings = settings;
	}

	public ExpandedUnit expand(Path entry) throws IOException {
		State state = new State(this.settings.unitDefines());

		expandFile(entry, 0, state);

		return new ExpandedUnit(this.source.rel(entry), List.copyOf(state.output), state.version,
				state.toStats(), state.live);
	}

	private void expandFile(Path file, int depth, State state) throws IOException {
		String relative = this.source.rel(file);
		state.maxDepth = Math.max(state.maxDepth, depth);

		if (depth > MAX_DEPTH) {
			state.tooDeep++;
			state.emit("#error include nesting too deep at " + relative, true);
			return;
		}

		if (++state.filesExpanded > MAX_FILES || state.overBudget()) {
			state.giveUp(relative);
			return;
		}

		// A file already on the current path is a cycle. The measuring prototype had no such
		// check and leaned on the depth limit instead, which meant a malformed pack was expanded
		// thirty-two times over before anything said so.
		if (!state.onPath.add(relative)) {
			state.cycles++;
			state.emit("#error include cycle at " + relative, true);
			return;
		}

		if (!state.everSeen.add(relative)) {
			state.duplicates++;
		}

		ConditionStack conditions = new ConditionStack();

		for (Logical logical : logicalLines(this.source.readLines(file))) {
			// Checked here and not only on the way in. One file is enough on its own: a pack that
			// ships a single shader of a million lines never expands anything, so a budget read
			// once per file would never be read at all.
			if (state.overBudget()) {
				state.giveUp(relative);
				break;
			}

			String line = logical.text();
			if (handleCondition(line, conditions, state)) {
				state.emit(logical.physical(), true);
				continue;
			}

			Matcher include = INCLUDE.matcher(line);
			if (include.matches()) {
				state.seen++;
				if (conditions.active()) {
					follow(file, include.group(1), depth, state);
				} else {
					state.skipped++;
					state.emit("// include not taken: " + include.group(1), false);
				}

				continue;
			}

			if (!conditions.active()) {
				// Kept as it was, so that what the compiler discards is what is written here.
				state.emit(logical.physical(), false);
				continue;
			}

			Matcher version = VERSION.matcher(line);
			if (version.matches()) {
				// A unit has exactly one version directive and it is the entry file's. Later
				// ones come from includes and would be an error where they land.
				if (state.version == null) {
					state.version = version.group(1).replaceAll("//.*", "").trim();
					state.emit(logical.physical(), true);
				}

				continue;
			}

			String tracked = track(line, state);
			if (tracked.equals(line)) {
				state.emit(logical.physical(), true);
			} else {
				state.emit(tracked, true);
			}
		}

		state.onPath.remove(relative);
	}

	/**
	 * One line as the compiler reads it, and the lines of the file it was written over.
	 * <p>
	 * A backslash before a line break joins the next line onto this one before the compiler reads
	 * a single directive, so the directives are matched and the conditions decided on the joined
	 * text. Read line by line as written, a condition continued over three lines is decided on its
	 * first: Photon undefines its waving switches under one written that way, the fragment on the
	 * first line could not be decided and so stood for true, the switches came off the table, and
	 * the include they guard was skipped while the compiler, seeing the whole condition, kept the
	 * code that calls into it.
	 * <p>
	 * What is EMITTED stays the lines as written, because the compiler joins them again and joins
	 * once: Bliss ends a comment with three backslashes and a blank line under it, the compiler
	 * takes the last backslash with the blank line and the comment ends there, and a joined line
	 * written out would end with the two backslashes left, which the compiler would then take
	 * with the line of code below. Sixteen units lost a declaration that way. The one line written
	 * out joined is a {@code #define} a setting rewrote, which no pack of the corpus continues.
	 */
	private record Logical(String text, List<String> physical) {
	}

	private static List<Logical> logicalLines(List<String> lines) {
		List<Logical> logical = new ArrayList<>(lines.size());
		StringBuilder joined = new StringBuilder();
		List<String> physical = new ArrayList<>();
		for (String line : lines) {
			physical.add(line);
			if (line.endsWith("\\")) {
				joined.append(line, 0, line.length() - 1);
				continue;
			}

			joined.append(line);
			logical.add(new Logical(joined.toString(), List.copyOf(physical)));
			joined.setLength(0);
			physical.clear();
		}

		if (!physical.isEmpty()) {
			logical.add(new Logical(joined.toString(), List.copyOf(physical)));
		}

		return logical;
	}

	/** Returns true when the line was a conditional directive and has been accounted for. */
	private boolean handleCondition(String line, ConditionStack conditions, State state) {
		Matcher ifDefined = IF_DEFINED.matcher(line);
		if (ifDefined.matches()) {
			state.conditionals++;
			boolean defined = state.defines.containsKey(ifDefined.group(2));
			conditions.ifDirective(ifDefined.group(1).equals("ifdef") == defined);
			return true;
		}

		Matcher iff = IF.matcher(line);
		if (iff.matches()) {
			state.conditionals++;
			conditions.ifDirective(decide(iff.group(1), state));
			return true;
		}

		Matcher elif = ELIF.matcher(line);
		if (elif.matches()) {
			state.conditionals++;
			conditions.elifDirective(() -> decide(elif.group(1), state));
			return true;
		}

		if (ELSE.matcher(line).matches()) {
			conditions.elseDirective();
			return true;
		}

		if (ENDIF.matcher(line).matches()) {
			conditions.endifDirective();
			return true;
		}

		return false;
	}

	/**
	 * An expression that cannot be decided is treated as true. Pulling in code that turns out to
	 * be unnecessary costs a compiler error at worst, and it names the line; dropping code that
	 * was needed removes a function, and the error then points somewhere unrelated.
	 */
	private boolean decide(String expression, State state) {
		Optional<Boolean> value = PreprocessorExpression.evaluate(expression, state.defines);
		if (value.isEmpty()) {
			state.undecidable++;
			return true;
		}

		return value.get();
	}

	private void follow(Path from, String spec, int depth, State state) throws IOException {
		Optional<Path> target = spec.startsWith("/")
				? this.source.resolveInsideShaders(spec)
				: this.source.resolveRelativeTo(from, spec);

		if (target.isEmpty()) {
			state.missing++;
			// Left in the text on purpose: the compiler then names the file that is absent,
			// rather than the load quietly producing a unit with a hole in it.
			state.emit("#error include not found: " + spec, true);
			return;
		}

		state.followed++;
		expandFile(target.get(), depth + 1, state);
	}

	/**
	 * Applies the chosen settings to a declaration and keeps the define table in step with the
	 * text actually written, so that a later conditional sees what the compiler will see.
	 */
	private String track(String line, State state) {
		Matcher undef = UNDEF.matcher(line);
		if (undef.matches()) {
			state.defines.remove(undef.group(1));
			return line;
		}

		Matcher original = DEFINE.matcher(line);
		String rewritten = OptionRewriter.apply(line, this.settings.chosen(), this.settings.scale());
		Matcher applied = DEFINE.matcher(rewritten);

		if (applied.matches()) {
			state.defines.put(applied.group(1), applied.group(2).replaceAll("//.*", "").trim());
		} else if (original.matches()) {
			// The line declared something and no longer does: a switch that was turned off.
			state.defines.remove(original.group(1));
		}

		return rewritten;
	}

	/**
	 * One flattened unit, ready for a translator and then for the compiler.
	 *
	 * @param live which lines came from a branch that was taken. Dead branches are kept in the
	 *             text, because the compiler has to see the same code the expander did, but a
	 *             translator that moves a declaration has to know: lifting a uniform out of a
	 *             branch nobody takes makes it unconditional, and packs do declare the same name
	 *             as a uniform in one branch and as an ordinary global in the other. Conditional
	 *             lines themselves count as taken; they are directives, never declarations.
	 */
	public record ExpandedUnit(String entry, List<String> lines, String version,
			ExpansionStats stats, BitSet live) {

		public ExpandedUnit {
			live = (BitSet) live.clone();
		}

		public String text() {
			return String.join("\n", this.lines);
		}

		public boolean isLive(int line) {
			return this.live.get(line);
		}
	}

	private static final class State {

		private final Map<String, String> defines;
		private final List<String> output = new ArrayList<>();
		private final BitSet live = new BitSet();
		private final Set<String> onPath = new HashSet<>();
		private final Set<String> everSeen = new HashSet<>();

		private String version;
		private long characters;
		private int filesExpanded;
		private int exhausted;
		private int seen;
		private int followed;
		private int skipped;
		private int missing;
		private int duplicates;
		private int cycles;
		private int maxDepth;
		private int tooDeep;
		private int conditionals;
		private int undecidable;

		private State(Map<String, String> defines) {
			this.defines = new LinkedHashMap<>(defines);
		}

		private void emit(String line, boolean taken) {
			this.live.set(this.output.size(), taken);
			this.output.add(line);
			this.characters += line.length() + 1;
		}

		private void emit(List<String> lines, boolean taken) {
			lines.forEach(line -> emit(line, taken));
		}

		private boolean overBudget() {
			return this.output.size() > MAX_LINES || this.characters > MAX_CHARACTERS;
		}

		/** Said once and then quietly, otherwise the message itself becomes the runaway. */
		private void giveUp(String relative) {
			if (this.exhausted++ == 0) {
				emit("#error include budget exhausted at " + relative, true);
			}
		}

		private ExpansionStats toStats() {
			return new ExpansionStats(this.seen, this.followed, this.skipped, this.missing,
					this.duplicates, this.cycles, this.maxDepth, this.tooDeep, this.conditionals,
					this.undecidable, this.exhausted);
		}
	}
}
