package dev.vitrail.pack.source;

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

		return new PropertiesFile(name, List.copyOf(source.readLines(file.get())));
	}

	public String name() {
		return this.name;
	}

	public boolean present() {
		return !this.lines.isEmpty();
	}

	/**
	 * Every line the conditionals leave standing, in order, directives themselves excluded and
	 * continuations joined.
	 * <p>
	 * <strong>The conditionals are read first and the continuations joined after, and the order is
	 * not a detail.</strong> Folding first was what this did, and a pack that continues a value past
	 * an {@code #endif} then had its {@code #endif} swallowed into the middle of that value, where
	 * nothing recognises it: the {@code #ifdef} above was never closed and the rest of the file went
	 * dark. Bliss writes exactly that, {@code block.properties:94-99}, and it cost 291 of its 305
	 * block declarations. Iris runs its preprocessor before it joins anything, so this is also what
	 * being read the same way as Iris means.
	 * <p>
	 * A continuation therefore joins across a directive line, which is the same thing that happens
	 * once the directive has been removed. Only the indentation of the joined line is swallowed,
	 * never a blank line.
	 */
	public void walk(Map<String, String> defines, Consumer<String> line) {
		walk(this.lines, defines, line);
	}

	/** The same walk over lines held elsewhere, so that {@link ShaderProperties} reads its own. */
	static void walk(List<String> lines, Map<String, String> defines, Consumer<String> line) {
		ConditionStack conditions = new ConditionStack();
		StringBuilder joined = null;

		for (String text : lines) {
			Matcher directive = DIRECTIVE.matcher(text);
			if (directive.matches()) {
				apply(directive.group(1), text, conditions, defines);
				continue;
			}

			if (!conditions.active()) {
				continue;
			}

			boolean continues = text.endsWith("\\");
			String body = continues ? text.substring(0, text.length() - 1) : text;
			if (joined == null) {
				joined = new StringBuilder(body);
			} else {
				joined.append(' ').append(body.stripLeading());
			}

			if (!continues) {
				line.accept(joined.toString());
				joined = null;
			}
		}

		// A file whose last line asks to be continued. The pack is wrong and the value is still
		// worth handing over, since everything before the backslash is a complete declaration.
		if (joined != null) {
			line.accept(joined.toString());
		}
	}

	/** An expression that cannot be decided leaves the branch on, as it does elsewhere here. */
	private static void apply(String keyword, String line, ConditionStack conditions,
			Map<String, String> defines) {
		switch (keyword) {
			case "ifdef", "ifndef" -> {
				Matcher name = IncludeExpander.DEFINED_NAME
						.matcher(line.replaceFirst("^\\s*#\\s*\\w+", ""));
				// Nothing that can name a setting after the keyword leaves the group taken, and
				// anything written after the name is dropped. These files carry the conditionals
				// a pack's shaders carry, so they get the same reading: one stack, one decision.
				conditions.ifDirective(!name.matches()
						|| keyword.equals("ifdef") == defines.containsKey(name.group(1)));
			}
			case "if" -> conditions.ifDirective(decide(after(line), defines));
			case "elif" -> conditions.elifDirective(() -> decide(after(line), defines));
			case "else" -> conditions.elseDirective();
			default -> conditions.endifDirective();
		}
	}

	/**
	 * Nothing to evaluate is not the same as an expression that could not be worked out. The
	 * reference reads the end of the line where it wanted a token and leaves the group off, where
	 * one it cannot decide is taken so that no code goes missing.
	 */
	private static boolean decide(String expression, Map<String, String> defines) {
		return !expression.isBlank()
				&& PreprocessorExpression.evaluate(expression, defines).orElse(true);
	}

	private static String after(String line) {
		return line.replaceAll("^\\s*#\\s*\\w+\\s*", "");
	}
}
