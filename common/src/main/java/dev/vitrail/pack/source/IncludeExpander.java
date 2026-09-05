package dev.vitrail.pack.source;

import dev.vitrail.glsl.LoadClock;
import dev.vitrail.pack.option.OptionRewriter;
import dev.vitrail.pack.option.SettingSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>
 * The other lines that do not survive as written are the conditionals a pack wrote loosely, which
 * the compiler would refuse where the reference never shows it a conditional at all. They are
 * rewritten into a directive GLSL will take, or commented out where there is nothing for them to
 * close: see {@link #defined} and {@link #unopened}.
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
	private static final Pattern IF_DEFINED = Pattern.compile("^(\\s*)#\\s*(ifdef|ifndef)\\b(.*)$");

	/** What has to follow {@code #ifdef} for the directive to be one the compiler will read. */
	static final Pattern DEFINED_NAME = Pattern.compile("^\\s+([A-Za-z_]\\w*)(.*)$");

	private static final Pattern IF = Pattern.compile("^(\\s*)#\\s*if\\b(.*)$");
	private static final Pattern ELIF = Pattern.compile("^(\\s*)#\\s*elif\\b(.*)$");
	private static final Pattern ELSE = Pattern.compile("^\\s*#\\s*else\\b.*$");
	private static final Pattern ENDIF = Pattern.compile("^\\s*#\\s*endif\\b.*$");
	private static final Pattern DEFINE = Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s*(.*)$");
	private static final Pattern UNDEF = Pattern.compile("^\\s*#\\s*undef\\s+([A-Za-z_]\\w*).*$");
	private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\s+(.*)$");

	private final ShaderPackSource source;
	private final SettingSet settings;
	private final boolean partOfALoad;

	/**
	 * The conditionals this pack writes loosely, each said once, in the order they were met. Held
	 * on the expander and not on a unit: a loose directive lives in a shared body, so it is met
	 * again in every program that includes it, and one line per program would bury it.
	 */
	private final Set<String> loose = new LinkedHashSet<>();

	public IncludeExpander(ShaderPackSource source, SettingSet settings) {
		this(source, settings, true);
	}

	private IncludeExpander(ShaderPackSource source, SettingSet settings, boolean partOfALoad) {
		this.source = source;
		this.settings = settings;
		this.partOfALoad = partOfALoad;
	}

	/**
	 * For the pack report's walk, whose flattening is neither clocked as a load's nor kept by the
	 * opening.
	 * <p>
	 * That walk expands every entry point the pack ships, once each, and it is made on the first
	 * load of a given pack and on none of the reloads of it. Counted into the clock, it would leave
	 * the figure several times larger on one load than on the next with nothing on the line saying
	 * which of the two had just been read, where what the line is for is what a load pays every
	 * time.
	 * <p>
	 * Kept by the opening, it would find nothing there ever, no entry point being asked for twice,
	 * and would hold every unit it built alive to the end of a reading whose whole point is to
	 * throw them away, three hundred and thirty-nine of them for the widest pack of the corpus.
	 */
	public static IncludeExpander forTheReport(ShaderPackSource source, SettingSet settings) {
		return new IncludeExpander(source, settings, false);
	}

	/**
	 * One entry file flattened, and flattened once per opening of the pack and per settings it is
	 * read under.
	 * <p>
	 * <strong>The memo is what makes a load pay for a unit once.</strong> A load walks one place of
	 * the pack over and over: once for its directives, once for the chain, once for the chunk
	 * programs, and once more for every compute program it ships. Each of those walks every fragment
	 * entry of the place, and expanding one of Photon's costs tens of milliseconds, so the same unit
	 * built a dozen times was the bulk of what a load with both compiled stores warm still waited on.
	 * <p>
	 * The pack report's walk neither reads the memo nor fills it, and {@link #forTheReport} says
	 * why.
	 * <p>
	 * A unit that throws is not remembered, so whoever asks next reads the file again and meets the
	 * same failure rather than a silence.
	 */
	public ExpandedUnit expand(Path entry) throws IOException {
		String relative = this.source.rel(entry);
		if (this.partOfALoad) {
			Optional<ExpandedUnit> known = this.source.expandedUnit(this.settings, relative);
			if (known.isPresent()) {
				LoadClock.expansionServed();

				return known.get();
			}
		}

		long began = System.nanoTime();
		State state = new State(this.settings.unitDefines(), this.loose);

		expandFile(entry, 0, state);

		ExpandedUnit unit = new ExpandedUnit(relative, List.copyOf(state.output), state.version,
				state.toStats(), state.live);
		if (this.partOfALoad) {
			LoadClock.expansion(System.nanoTime() - began);
			this.source.rememberUnit(this.settings, relative, unit);
		}

		return unit;
	}

	/** What this reader read for the pack across every unit expanded so far, ready for the log. */
	public List<String> looseConditionals() {
		return List.copyOf(this.loose);
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
			String directive = handleCondition(logical, relative, conditions, state);
			if (directive != null) {
				if (directive.equals(line)) {
					state.emit(logical.physical(), true);
				} else {
					state.emit(directive, true);
				}

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

		// A rewritten conditional the file never closes would leave a directive this reader put in
		// the text with nothing to close it, so it is closed where the file ends.
		//
		// Only those are closed. A group the PACK left open is one the compiler may never have
		// opened: this reader matches directives line by line and a compiler strips comments
		// first, so a conditional written inside a block comment is open here and absent there.
		// Sildur's writes four such files, and an #endif added at the end of them is refused as
		// a mismatched statement where the file compiles untouched.
		for (int open = conditions.unclosedRewritten(); open > 0; open--) {
			state.emit("#endif", true);
			state.openInOutput--;
		}

		state.onPath.remove(relative);
	}

	/**
	 * One line as the compiler reads it, where in the file it starts, and the lines it was written
	 * over.
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
	 * with the line of code below. Sixteen units lost a declaration that way. The lines written out
	 * joined are the ones this reader had to rewrite, a {@code #define} a setting changed and a
	 * conditional the pack wrote loosely, and no pack of the corpus continues either of those.
	 */
	private record Logical(String text, int number, List<String> physical) {
	}

	private static List<Logical> logicalLines(List<String> lines) {
		List<Logical> logical = new ArrayList<>(lines.size());
		StringBuilder joined = new StringBuilder();
		List<String> physical = new ArrayList<>();
		int number = 1;
		for (String line : lines) {
			physical.add(line);
			if (line.endsWith("\\")) {
				joined.append(line, 0, line.length() - 1);
				continue;
			}

			joined.append(line);
			logical.add(new Logical(joined.toString(), number, List.copyOf(physical)));
			number += physical.size();
			joined.setLength(0);
			physical.clear();
		}

		if (!physical.isEmpty()) {
			logical.add(new Logical(joined.toString(), number, List.copyOf(physical)));
		}

		return logical;
	}

	/**
	 * Accounts for a conditional directive and says what to write in its place.
	 *
	 * @return null when the line is not a conditional directive, and otherwise the text to write,
	 *         which is the line itself unless the pack wrote the directive loosely
	 */
	private String handleCondition(Logical logical, String relative, ConditionStack conditions,
			State state) {
		String line = logical.text();

		Matcher ifDefined = IF_DEFINED.matcher(line);
		if (ifDefined.matches()) {
			state.conditionals++;
			state.openInOutput++;

			return defined(logical, relative, ifDefined, conditions, state);
		}

		Matcher iff = IF.matcher(line);
		if (iff.matches()) {
			state.conditionals++;
			state.openInOutput++;
			if (!beyondComments(iff.group(2)).isEmpty()) {
				conditions.ifDirective(decide(iff.group(2), state));
				return line;
			}

			report(conditions, state, relative, logical,
					"no expression follows it, so the group it opens is not taken");
			conditions.rewrittenIfDirective(false);

			return iff.group(1) + "#if 0";
		}

		Matcher elif = ELIF.matcher(line);
		if (elif.matches()) {
			state.conditionals++;
			if (state.openInOutput == 0) {
				return unopened(conditions, state, relative, logical);
			}

			if (!beyondComments(elif.group(2)).isEmpty()) {
				conditions.elifDirective(() -> decide(elif.group(2), state));
				return line;
			}

			report(conditions, state, relative, logical,
					"no expression follows it, so the branch it opens is not taken");
			conditions.elifDirective(() -> false);

			return elif.group(1) + "#elif 0";
		}

		if (ELSE.matcher(line).matches()) {
			if (state.openInOutput == 0) {
				return unopened(conditions, state, relative, logical);
			}

			conditions.elseDirective();

			return line;
		}

		if (ENDIF.matcher(line).matches()) {
			if (state.openInOutput == 0) {
				return unopened(conditions, state, relative, logical);
			}

			state.openInOutput--;
			conditions.endifDirective();

			return line;
		}

		return null;
	}

	/**
	 * A directive that continues or closes a group where the text written out has none open. jcpp
	 * reports it and carries on with the text it had; the compiler here would be handed the
	 * directive and refuse the program for it, so it becomes a comment. Photon writes one, and it
	 * is what costs its three {@code composite3} programs.
	 * <p>
	 * The count that decides this is of what has been WRITTEN, never the depth of the file being
	 * read, and it errs the one way it can afford to. A conditional inside a block comment is a
	 * directive to this reader and not to the compiler, so the count can only be too high, and too
	 * high leaves the directive alone. Too low would delete the one {@code #endif} a group needed.
	 */
	private static String unopened(ConditionStack conditions, State state, String relative,
			Logical logical) {
		report(conditions, state, relative, logical,
				"nothing is open for it to close, so it is not written out");

		return "// no conditional is open here: " + logical.text().trim();
	}

	/**
	 * Says what was read of a directive the pack wrote loosely.
	 * <p>
	 * Not said from a branch that is off: the reference's preprocessor does not read the inside of
	 * one, so there is nothing it decided differently there to report.
	 */
	private static void report(ConditionStack conditions, State state, String relative,
			Logical logical, String decided) {
		if (conditions.active()) {
			state.loose(relative, logical, decided);
		}
	}

	/**
	 * Reads one {@code #ifdef} or {@code #ifndef}, and rewrites it when what the pack wrote is not
	 * a directive the compiler will take.
	 * <p>
	 * Two shapes are written loosely and the reference lets both through, because it hands the
	 * compiler the text its preprocessor produced rather than the text the pack wrote, so no
	 * conditional directive survives as far as a compiler at all: {@code JcppProcessor.java:55-64}
	 * of the reference walks the tokens jcpp yields and {@code ShaderPack.java:317} makes that the
	 * program source. Here the directives are left in the text for the compiler to read, which is
	 * what turns a shape jcpp merely complains about into a program that will not build.
	 * <p>
	 * A name followed by anything else: jcpp reads the first token, decides on it, and reports each
	 * further token as an unexpected one, so the extra tokens are dropped and the decision is
	 * unchanged. Anything else than a name after the keyword, a number, a bracket, or the end of
	 * the line: jcpp reports what it read instead of a name and leaves the group it opened taken,
	 * so it becomes a group that is always taken. Both are reported to the listener rather than
	 * thrown, and the reference's listener only prints, so neither refuses the pack there.
	 * <p>
	 * One thing jcpp does that is not reproduced: when the keyword is followed by nothing at all,
	 * recovering eats the line under it, the newline having already been read as the name that was
	 * missing. That is a slip of its recovery rather than a decision, it does not happen when a
	 * token other than a name is there to be read, and reproducing it would silently drop a line of
	 * a pack's code.
	 */
	private String defined(Logical logical, String relative, Matcher ifDefined,
			ConditionStack conditions, State state) {
		String indent = ifDefined.group(1);
		String keyword = ifDefined.group(2);
		Matcher name = DEFINED_NAME.matcher(ifDefined.group(3));

		if (!name.matches()) {
			report(conditions, state, relative, logical,
					"nothing that can name a setting follows it, so the group it opens is taken");
			conditions.rewrittenIfDirective(true);

			return indent + "#if 1";
		}

		boolean defined = state.defines.containsKey(name.group(1));
		conditions.ifDirective(keyword.equals("ifdef") == defined);

		if (beyondComments(name.group(2)).isEmpty()) {
			return logical.text();
		}

		report(conditions, state, relative, logical, "only " + name.group(1) + " is read");

		return indent + "#" + keyword + " " + name.group(1);
	}

	/** What the compiler still has to read once the comments are taken out of a directive's tail. */
	private static String beyondComments(String tail) {
		String code = tail.replaceAll("/\\*.*?\\*/", " ");
		int line = code.indexOf("//");
		int unterminated = code.indexOf("/*");
		if (line >= 0) {
			code = code.substring(0, line);
		}

		if (unterminated >= 0 && (line < 0 || unterminated < line)) {
			code = code.substring(0, unterminated);
		}

		return code.trim();
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

		/**
		 * Copied in and copied out, a {@link BitSet} being mutable. In on its own was enough while
		 * a unit was built for one reader and dropped; now that an opening hands the same unit back
		 * to every walk of a load that asks for it, a reader that set a bit would set it for all
		 * the others too, and the key a translation is found on disk by is taken over this set,
		 * so a stray bit moves which cached program a unit lands on.
		 */
		public ExpandedUnit {
			live = (BitSet) live.clone();
		}

		@Override
		public BitSet live() {
			return (BitSet) this.live.clone();
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
		private final Set<String> loose;
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

		/**
		 * How many conditional groups the text written out so far leaves open, which is not the
		 * depth of any one file: a group opened in an include is closed by whatever file writes the
		 * {@code #endif}, and the compiler reads the unit as one text.
		 */
		private int openInOutput;

		private State(Map<String, String> defines, Set<String> loose) {
			this.defines = new LinkedHashMap<>(defines);
			this.loose = loose;
		}

		private void loose(String relative, Logical logical, String decided) {
			this.loose.add(relative + ":" + logical.number() + " writes " + logical.text().trim()
					+ ", and " + decided);
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
