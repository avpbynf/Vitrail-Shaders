package dev.vitrail.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lexical measurements of a pack: how many files, includes, conditionals and settings it
 * contains, and how many of those settings actually gate code.
 * <p>
 * This is a counting pass and nothing more. It resolves no include, evaluates no condition and
 * expands nothing, which is exactly why it can be compared: the same numbers come out whatever
 * the settings are. They exist to be checked against the measurements taken from the corpus
 * before any of this was written, so the definitions here have to match those to the letter,
 * including the parts that look arbitrary. {@code #else} counts as neither a conditional nor a
 * depth change, and the nesting depth restarts at every file.
 */
public final class PackStats {

	private static final Pattern IF_DEFINED = Pattern.compile("^#\\s*(ifdef|ifndef)\\s+([A-Za-z_]\\w*)");
	private static final Pattern ELIF = Pattern.compile("^#\\s*elif\\s+(.*)$");
	private static final Pattern IF = Pattern.compile("^#\\s*if\\s+(.*)$");
	private static final Pattern ENDIF = Pattern.compile("^#\\s*endif.*");
	private static final Pattern INCLUDE = Pattern.compile("^#\\s*include\\s+[<\"](.+?)[>\"].*");
	private static final Pattern VERSION = Pattern.compile("^#\\s*version\\s+(.*)$");

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
	private static final Pattern TRAILING_COMMENT = Pattern.compile("//.*");

	private final int files;
	private final int includes;
	private final int conditionalIncludes;
	private final int absoluteIncludes;
	private final int relativeIncludes;
	private final int conditionals;
	private final Set<String> symbols;
	private final int options;
	private final int binaryGates;
	private final int multiValueGates;
	private final int maxValues;
	private final Map<String, Integer> filesByExtension;
	private final Map<String, Integer> versions;
	private final int lines;

	private PackStats(Builder builder, OptionIndex index) {
		this.files = builder.files;
		this.includes = builder.includes;
		this.conditionalIncludes = builder.conditionalIncludes;
		this.absoluteIncludes = builder.absoluteIncludes;
		this.relativeIncludes = builder.relativeIncludes;
		this.conditionals = builder.conditionals;
		this.symbols = Set.copyOf(builder.symbols);
		this.filesByExtension = Map.copyOf(builder.filesByExtension);
		this.versions = Map.copyOf(builder.versions);
		this.lines = builder.lines;
		this.options = index.count();

		// A setting only counts as gating code if some conditional actually tests it. Packs
		// declare a good many that nothing reads.
		int binary = 0;
		int multi = 0;
		int max = 0;
		for (String symbol : builder.symbols) {
			PackOption option = index.get(symbol).orElse(null);
			if (option == null) {
				continue;
			}

			int values = option.values().size();
			if (values > 1) {
				multi++;
				max = Math.max(max, values);
			} else {
				binary++;
			}
		}

		this.binaryGates = binary;
		this.multiValueGates = multi;
		this.maxValues = max;
	}

	public static PackStats measure(ShaderPackSource source, OptionIndex index) throws IOException {
		Builder builder = new Builder();

		for (Path file : source.sourceFiles()) {
			builder.files++;
			builder.filesByExtension.merge(extensionOf(source.rel(file)), 1, Integer::sum);

			// Nesting is counted from the top of each file. A file that opens a conditional and
			// leaves it open does not leak that state into the next one.
			int depth = 0;
			for (String raw : source.readLines(file)) {
				builder.lines++;

				String line = raw.stripLeading();
				Matcher ifDefined = IF_DEFINED.matcher(line);
				if (ifDefined.lookingAt()) {
					depth++;
					builder.conditionals++;
					builder.symbols.add(ifDefined.group(2));
					continue;
				}

				Matcher elif = ELIF.matcher(line);
				if (elif.matches()) {
					builder.conditionals++;
					collectSymbols(elif.group(1), builder);
					continue;
				}

				Matcher iff = IF.matcher(line);
				if (iff.matches()) {
					depth++;
					builder.conditionals++;
					collectSymbols(iff.group(1), builder);
					continue;
				}

				if (ENDIF.matcher(line).matches()) {
					depth = Math.max(0, depth - 1);
					continue;
				}

				Matcher include = INCLUDE.matcher(line);
				if (include.matches()) {
					builder.includes++;
					if (depth > 0) {
						builder.conditionalIncludes++;
					}

					if (include.group(1).startsWith("/")) {
						builder.absoluteIncludes++;
					} else {
						builder.relativeIncludes++;
					}

					continue;
				}

				Matcher version = VERSION.matcher(line);
				if (version.matches()) {
					builder.versions.merge(stripComment(version.group(1)), 1, Integer::sum);
				}
			}
		}

		return new PackStats(builder, index);
	}

	private static void collectSymbols(String expression, Builder builder) {
		Matcher identifier = IDENTIFIER.matcher(stripComment(expression));
		while (identifier.find()) {
			String name = identifier.group();
			// "defined" is the operator, not something the pack declares.
			if (!name.equals("defined")) {
				builder.symbols.add(name);
			}
		}
	}

	private static String stripComment(String text) {
		return TRAILING_COMMENT.matcher(text).replaceAll("").trim();
	}

	private static String extensionOf(String relative) {
		int dot = relative.lastIndexOf('.');

		return dot < 0 ? "" : relative.substring(dot + 1);
	}

	public int files() {
		return this.files;
	}

	public int includes() {
		return this.includes;
	}

	public int conditionalIncludes() {
		return this.conditionalIncludes;
	}

	public int absoluteIncludes() {
		return this.absoluteIncludes;
	}

	public int relativeIncludes() {
		return this.relativeIncludes;
	}

	public int conditionals() {
		return this.conditionals;
	}

	public int symbolCount() {
		return this.symbols.size();
	}

	public int options() {
		return this.options;
	}

	public int gatingOptions() {
		return this.binaryGates + this.multiValueGates;
	}

	public int binaryGates() {
		return this.binaryGates;
	}

	public int multiValueGates() {
		return this.multiValueGates;
	}

	public int maxValues() {
		return this.maxValues;
	}

	public int lines() {
		return this.lines;
	}

	public Map<String, Integer> filesByExtension() {
		return new TreeMap<>(this.filesByExtension);
	}

	/**
	 * Files carrying a stage extension, wherever they sit and whatever they are called. This is
	 * a file count and not a program count: it includes the ones filed under a directory the
	 * engine never walks into, and the ones whose name it does not recognise. It exists because
	 * that is what the reference measurements counted, and the two numbers are worth seeing
	 * next to each other.
	 */
	public int stageFiles() {
		return this.filesByExtension.entrySet().stream()
				.filter(entry -> ProgramStage.fromExtension(entry.getKey()).isPresent())
				.mapToInt(Map.Entry::getValue)
				.sum();
	}

	/** Files that are shared bodies rather than entry points, which is the other reference column. */
	public int includeFiles() {
		return this.filesByExtension.getOrDefault("glsl", 0);
	}

	/** The GLSL version most of the pack asks for, with how many files ask for it. */
	public String majorityVersion() {
		return this.versions.entrySet().stream()
				.max(Map.Entry.comparingByValue())
				.map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
				.orElse("none");
	}

	/**
	 * The same columns as the reference measurements, in the same order, so that checking the
	 * result is reading two lines side by side rather than counting by hand.
	 */
	public String tsvLine(String packName) {
		return String.join("\t", List.of(packName,
				Integer.toString(this.files),
				Integer.toString(this.includes),
				Integer.toString(this.conditionalIncludes),
				Integer.toString(this.absoluteIncludes),
				Integer.toString(this.relativeIncludes),
				Integer.toString(this.conditionals),
				Integer.toString(this.symbols.size()),
				Integer.toString(this.options),
				Integer.toString(gatingOptions()),
				Integer.toString(this.binaryGates),
				Integer.toString(this.multiValueGates),
				Integer.toString(this.maxValues),
				this.majorityVersion()));
	}

	public static String tsvHeader() {
		return String.join("\t", "pack", "fichiers", "includes", "incl_cond", "incl_abs", "incl_rel",
				"conditionnelles", "symboles", "options", "opt_gating", "bin", "multi", "max_valeurs",
				"version");
	}

	private static final class Builder {

		private int files;
		private int includes;
		private int conditionalIncludes;
		private int absoluteIncludes;
		private int relativeIncludes;
		private int conditionals;
		private int lines;
		private final Set<String> symbols = new LinkedHashSet<>();
		private final Map<String, Integer> filesByExtension = new LinkedHashMap<>();
		private final Map<String, Integer> versions = new LinkedHashMap<>();
	}
}
