package dev.vitrail.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
	private final OptionIndex index;
	private final SettingSet settings;

	public IncludeExpander(ShaderPackSource source, OptionIndex index, SettingSet settings) {
		this.source = source;
		this.index = index;
		this.settings = settings;
	}

	public ExpandedUnit expand(Path entry) throws IOException {
		State state = new State(this.settings.unitDefines(this.index));

		for (Map.Entry<String, String> define : this.settings.headerDefines(this.index).entrySet()) {
			state.output.add("#define " + define.getKey()
					+ (define.getValue().isEmpty() ? "" : " " + define.getValue()));
		}

		expandFile(entry, 0, state);

		return new ExpandedUnit(this.source.rel(entry), List.copyOf(state.output), state.version, state.toStats());
	}

	private void expandFile(Path file, int depth, State state) throws IOException {
		String relative = this.source.rel(file);
		state.maxDepth = Math.max(state.maxDepth, depth);

		if (depth > MAX_DEPTH) {
			state.tooDeep++;
			state.output.add("#error include nesting too deep at " + relative);
			return;
		}

		// A file already on the current path is a cycle. The measuring prototype had no such
		// check and leaned on the depth limit instead, which meant a malformed pack was expanded
		// thirty-two times over before anything said so.
		if (!state.onPath.add(relative)) {
			state.cycles++;
			state.output.add("#error include cycle at " + relative);
			return;
		}

		if (!state.everSeen.add(relative)) {
			state.duplicates++;
		}

		ConditionStack conditions = new ConditionStack();

		for (String line : this.source.readLines(file)) {
			if (handleCondition(line, conditions, state)) {
				state.output.add(line);
				continue;
			}

			Matcher include = INCLUDE.matcher(line);
			if (include.matches()) {
				state.seen++;
				if (conditions.active()) {
					follow(file, include.group(1), depth, state);
				} else {
					state.skipped++;
					state.output.add("// include not taken: " + include.group(1));
				}

				continue;
			}

			if (!conditions.active()) {
				// Kept as it was, so that what the compiler discards is what is written here.
				state.output.add(line);
				continue;
			}

			Matcher version = VERSION.matcher(line);
			if (version.matches()) {
				// A unit has exactly one version directive and it is the entry file's. Later
				// ones come from includes and would be an error where they land.
				if (state.version == null) {
					state.version = version.group(1).replaceAll("//.*", "").trim();
					state.output.add(line);
				}

				continue;
			}

			state.output.add(track(line, state));
		}

		state.onPath.remove(relative);
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
			state.output.add("#error include not found: " + spec);
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
		String rewritten = OptionRewriter.apply(line, this.settings.chosen());
		Matcher applied = DEFINE.matcher(rewritten);

		if (applied.matches()) {
			state.defines.put(applied.group(1), applied.group(2).replaceAll("//.*", "").trim());
		} else if (original.matches()) {
			// The line declared something and no longer does: a switch that was turned off.
			state.defines.remove(original.group(1));
		}

		return rewritten;
	}

	/** One flattened unit, ready for a translator and then for the compiler. */
	public record ExpandedUnit(String entry, List<String> lines, String version, ExpansionStats stats) {

		public String text() {
			return String.join("\n", this.lines);
		}
	}

	private static final class State {

		private final Map<String, String> defines;
		private final List<String> output = new ArrayList<>();
		private final Set<String> onPath = new HashSet<>();
		private final Set<String> everSeen = new HashSet<>();

		private String version;
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

		private ExpansionStats toStats() {
			return new ExpansionStats(this.seen, this.followed, this.skipped, this.missing,
					this.duplicates, this.cycles, this.maxDepth, this.tooDeep, this.conditionals,
					this.undecidable);
		}
	}
}
