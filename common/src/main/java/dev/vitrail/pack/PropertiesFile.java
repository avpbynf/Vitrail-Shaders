package dev.vitrail.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines of one of a pack's four properties files, joined and read the way a preprocessor
 * would.
 * <p>
 * {@code shaders.properties}, {@code block.properties}, {@code item.properties} and
 * {@code entity.properties} share a grammar with none of Java's: only {@code =} separates a key
 * from a value, a line continued with a backslash swallows the indentation of the next line, and
 * a conditional may switch whole blocks of the file off. Reading any of them flat gets the packs
 * that use conditionals wrong, and six of the eight do.
 */
public final class PropertiesFile {

	// Only the indentation of the joined line is swallowed, never a blank line. Kept the same as
	// the reader in ShaderProperties, which found that the hard way on Bliss.
	private static final Pattern CONTINUATION = Pattern.compile("\\\\\\r?\\n[ \\t]*");
	private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(if|ifdef|ifndef|else|elif|endif)\\b.*$");

	private final String name;
	private final List<String> lines;

	private PropertiesFile(String name, List<String> lines) {
		this.name = name;
		this.lines = lines;
	}

	public static PropertiesFile read(ShaderPackSource source, String name) throws IOException {
		Optional<Path> file = source.file(name);
		if (file.isEmpty()) {
			return new PropertiesFile(name, List.of());
		}

		String text = String.join("\n", source.readLines(file.get()));

		return new PropertiesFile(name, List.of(CONTINUATION.matcher(text).replaceAll(" ").split("\n", -1)));
	}

	public String name() {
		return this.name;
	}

	public boolean present() {
		return !this.lines.isEmpty();
	}

	/** Every line the conditionals leave standing, in order, directives themselves excluded. */
	public void walk(Map<String, String> defines, Consumer<String> line) {
		ConditionStack conditions = new ConditionStack();

		for (String text : this.lines) {
			Matcher directive = DIRECTIVE.matcher(text);
			if (directive.matches()) {
				apply(directive.group(1), text, conditions, defines);
				continue;
			}

			if (conditions.active()) {
				line.accept(text);
			}
		}
	}

	/** An expression that cannot be decided leaves the branch on, as it does elsewhere here. */
	private static void apply(String keyword, String line, ConditionStack conditions,
			Map<String, String> defines) {
		switch (keyword) {
			case "ifdef", "ifndef" -> {
				String name = line.replaceAll("^\\s*#\\s*\\w+\\s+", "").trim().split("\\s+")[0];
				conditions.ifDirective(keyword.equals("ifdef") == defines.containsKey(name));
			}
			case "if" -> conditions.ifDirective(
					PreprocessorExpression.evaluate(after(line), defines).orElse(true));
			case "elif" -> conditions.elifDirective(
					() -> PreprocessorExpression.evaluate(after(line), defines).orElse(true));
			case "else" -> conditions.elseDirective();
			default -> conditions.endifDirective();
		}
	}

	private static String after(String line) {
		return line.replaceAll("^\\s*#\\s*\\w+\\s*", "");
	}
}
