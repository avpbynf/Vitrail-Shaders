package dev.vitrail.pack.option;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every setting a pack declares, found by reading its sources line by line.
 * <p>
 * The scan is deliberately naive, and has to stay that way. It does not skip comment blocks
 * and it does not skip branches that the settings turn off, so a declaration written inside a
 * documentation block counts, and so does a macro that takes parameters. Both look like
 * mistakes and both are what the measurements were taken with; teaching the scan to be clever
 * changes the totals and there is then nothing left to compare against.
 * <p>
 * The first declaration of a name wins, which makes the order files are read in part of the
 * answer rather than an implementation detail. That order is the source's,
 * {@link dev.vitrail.pack.source.ShaderPackSource#sourceFiles()}, which feeds a
 * {@link Reader} one file after another and keeps the index it answers for the opening.
 */
public final class OptionIndex {

	private static final Pattern DEFINE =
			Pattern.compile("^\\s*(//\\s*)?#\\s*define\\s+([A-Za-z_]\\w*)\\s*(.*)$");
	private static final Pattern CONSTANT =
			Pattern.compile("^\\s*const\\s+(int|float|bool|uint)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);(.*)$");
	// The list is the first bracket anywhere in the trailing comment, not one that has to
	// follow the slashes. Packs routinely describe the setting before offering its values,
	// as in "// render resolution multiplier [0.10 0.25]", and requiring the bracket first
	// silently leaves those options with nothing to cycle through.
	private static final Pattern VALUE_LIST = Pattern.compile("//[^\\[]*\\[(.*?)]");
	private static final Pattern TRAILING_COMMENT = Pattern.compile("//.*");
	// The reference's own strictness, detail for detail: no space between # and the keyword,
	// the name alone, and nothing after it, so a trailing comment on the #ifdef line keeps it
	// from counting as a reference there too. The name class is the one this index can hold;
	// the reference would also take a name opening on a digit, which no declaration here can.
	private static final Pattern CONDITIONAL =
			Pattern.compile("^\\s*#(?:ifdef|ifndef)\\s+([A-Za-z_]\\w*)\\s*$");

	private final Map<String, PackOption> byName;
	private final Set<String> conditionalReferences;

	private OptionIndex(Map<String, PackOption> byName, Set<String> conditionalReferences) {
		this.byName = Map.copyOf(byName);
		this.conditionalReferences = Set.copyOf(conditionalReferences);
	}

	/**
	 * Gathers the declarations of one file after another, in the order they are handed in, and
	 * answers the index once every file has been read. It reads lines and never a file, so that
	 * this package knows nothing of where a pack is kept.
	 */
	public static final class Reader {

		private final Map<String, PackOption> found = new LinkedHashMap<>();
		private final Set<String> referenced = new HashSet<>();

		/** Reads one file's lines, {@code where} being the name a declaration is reported under. */
		public void read(String where, List<String> lines) {
			for (int i = 0; i < lines.size(); i++) {
				PackOption option = parse(lines.get(i), where, i + 1);
				if (option != null) {
					this.found.putIfAbsent(option.name(), option);
				}

				Matcher conditional = CONDITIONAL.matcher(lines.get(i));
				if (conditional.matches()) {
					this.referenced.add(conditional.group(1));
				}
			}
		}

		public OptionIndex index() {
			return new OptionIndex(this.found, this.referenced);
		}
	}

	private static PackOption parse(String line, String where, int lineNumber) {
		Matcher define = DEFINE.matcher(line);
		if (define.matches()) {
			String rest = define.group(3);
			String defaultText = TRAILING_COMMENT.matcher(rest).replaceAll("").trim();

			// Empty means a bare switch. Anything else is a value, even without a list of
			// allowed ones next to it, and even when it is a macro's parameter list.
			return new PackOption(define.group(2),
					defaultText.isEmpty() ? PackOption.Kind.TOGGLE : PackOption.Kind.VALUE,
					defaultText, valueList(rest), define.group(1) != null, null, where, lineNumber);
		}

		Matcher constant = CONSTANT.matcher(line);
		if (constant.matches()) {
			return new PackOption(constant.group(2), PackOption.Kind.CONST, constant.group(3).trim(),
					valueList(constant.group(4)), false, constant.group(1), where, lineNumber);
		}

		return null;
	}

	/** The allowed values a pack offers in a trailing {@code //[a b c]} comment. */
	private static List<String> valueList(String text) {
		Matcher list = VALUE_LIST.matcher(text);
		if (!list.find()) {
			return List.of();
		}

		return List.of(list.group(1).trim().split("\\s+", -1)).stream().filter(token -> !token.isEmpty()).toList();
	}

	public Optional<PackOption> get(String name) {
		return Optional.ofNullable(this.byName.get(name));
	}

	/**
	 * Whether any {@code #ifdef} or {@code #ifndef} in the pack tests this name. The reference
	 * only offers a toggle whose name something tests; a bare define nothing reads is scenery,
	 * not a setting. Its scope there is the include component of the declaring file, and the
	 * whole pack here: this index deliberately has no include graph, and a name tested in a
	 * file its declaration never reaches is a difference no measured pack exhibits.
	 */
	public boolean referenced(String name) {
		return this.conditionalReferences.contains(name);
	}

	/**
	 * Whether a declaration is a setting at all, and it is the reference's own sieve
	 * ({@code OptionAnnotatedSource.getOptionSet}): a toggle something tests, a value with a
	 * list to cycle through, or a constant that clears three bars at once. Its name has to be
	 * on the closed list of {@link ConstOptions}; a {@code const bool} then needs something
	 * testing it, a constant holding a number needs a list of values; and {@code uint} never
	 * qualifies, the reference reading only {@code int}, {@code float} and {@code bool} as
	 * configurable. The index still holds what fails here, because it deliberately holds every
	 * declaration: this answers what a screen may offer and a settings file may change.
	 */
	public boolean offers(PackOption option) {
		return switch (option.kind()) {
			case TOGGLE -> referenced(option.name());
			case VALUE -> option.hasValueList();
			case CONST -> ConstOptions.isOption(option.name())
					&& !"uint".equals(option.constType())
					&& ("bool".equals(option.constType()) ? referenced(option.name())
							: option.hasValueList());
		};
	}

	public Collection<PackOption> all() {
		return this.byName.values();
	}

	/** Every name the pack declares, for a caller that only has to tell declared from not. */
	public Set<String> names() {
		return this.byName.keySet();
	}

	public int count() {
		return this.byName.size();
	}

	public int countByKind(PackOption.Kind kind) {
		return (int) this.byName.values().stream().filter(option -> option.kind() == kind).count();
	}

	/** Settings the pack ships commented out, which are still settings. */
	public int disabledCount() {
		return (int) this.byName.values().stream().filter(PackOption::defaultOff).count();
	}

	public int withValueListCount() {
		return (int) this.byName.values().stream().filter(PackOption::hasValueList).count();
	}

	/**
	 * Names that differ only by case. Harmless on a case-sensitive reading, but they are the
	 * kind of thing that makes a pack behave differently once loaded from a zip.
	 */
	public Collection<String> caseCollisions() {
		Map<String, String> seen = new LinkedHashMap<>();
		Collection<String> collisions = new TreeSet<>();

		for (String name : this.byName.keySet()) {
			String previous = seen.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
			if (previous != null) {
				collisions.add(previous);
				collisions.add(name);
			}
		}

		return collisions;
	}
}
