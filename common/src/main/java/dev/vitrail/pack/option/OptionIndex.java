package dev.vitrail.pack.option;

import dev.vitrail.pack.source.ShaderPackSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * answer rather than an implementation detail. That order is fixed by
 * {@link ShaderPackSource#sourceFiles()}.
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

	private final Map<String, PackOption> byName;

	private OptionIndex(Map<String, PackOption> byName) {
		this.byName = Map.copyOf(byName);
	}

	public static OptionIndex build(ShaderPackSource source) throws IOException {
		Map<String, PackOption> found = new LinkedHashMap<>();

		for (Path file : source.sourceFiles()) {
			String where = source.rel(file);
			List<String> lines = source.readLines(file);

			for (int i = 0; i < lines.size(); i++) {
				PackOption option = parse(lines.get(i), where, i + 1);
				if (option != null) {
					found.putIfAbsent(option.name(), option);
				}
			}
		}

		return new OptionIndex(found);
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

		return List.of(list.group(1).trim().split("\\s+")).stream().filter(token -> !token.isEmpty()).toList();
	}

	public boolean contains(String name) {
		return this.byName.containsKey(name);
	}

	public Optional<PackOption> get(String name) {
		return Optional.ofNullable(this.byName.get(name));
	}

	public Collection<PackOption> all() {
		return this.byName.values();
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
