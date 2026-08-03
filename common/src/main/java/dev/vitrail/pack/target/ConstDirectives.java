package dev.vitrail.pack.target;

import dev.vitrail.pack.source.IncludeExpander;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the {@code const <type> <name> = <value>;} declarations a unit carries.
 * <p>
 * The grammar is Iris's, transcribed rather than reasoned out, because it is what the packs are
 * written against and it is arbitrary in places: the type is matched by prefix against a closed
 * list of six, the key must be a word, and the value is whatever sits between the equals sign
 * and the first semicolon, unvalidated. A pack writes
 * {@code const int colortex0Format = R11F_G11F_B10F;}, which no compiler would take, and it
 * only stands because the line sits inside a block comment.
 * <p>
 * Which is the trap, and it is the quiet one. Nothing here skips comments and nothing here ever
 * should: every format directive in the corpus, all hundred and five of them, is written inside
 * a comment, because it has to be. A reader that strips comments first finds no formats at all,
 * allocates every target as RGBA8, and still produces a plausible image.
 * <p>
 * Dead lines are the other half of the rule and they are dropped. The expander leaves a branch
 * nobody took standing so that the compiler sees what it saw, so a pack that declares one format
 * per setting has several declarations of the same name in its text and only one of them means
 * anything.
 */
public final class ConstDirectives {

	/** Tested by prefix, in this order, exactly as Iris tests them. */
	private static final String[] TYPES = { "int", "float", "vec2", "ivec3", "vec4", "bool" };

	private ConstDirectives() {
	}

	/** @param line index into {@link IncludeExpander.ExpandedUnit#lines()}, or -1 for a loose line */
	public record Directive(String type, String name, String value, int line) {
	}

	/** Live lines only, in file order. */
	public static List<Directive> read(IncludeExpander.ExpandedUnit unit) {
		List<Directive> found = new ArrayList<>();
		List<String> lines = unit.lines();

		for (int index = 0; index < lines.size(); index++) {
			if (unit.isLive(index)) {
				parse(lines.get(index), index).ifPresent(found::add);
			}
		}

		return List.copyOf(found);
	}

	/** Exposed on its own so the rule can be measured against one line of text. */
	public static Optional<Directive> readLine(String line) {
		return parse(line, -1);
	}

	private static Optional<Directive> parse(String line, int number) {
		// Three substring searches before anything else. The corpus expands to millions of lines
		// and almost none of them are declarations, so the cheap refusal is what makes the whole
		// plan affordable.
		if (!line.contains("const") || !line.contains("=") || !line.contains(";")) {
			return Optional.empty();
		}

		String rest = line.trim();
		if (!rest.startsWith("const")) {
			return Optional.empty();
		}

		rest = rest.substring("const".length());
		if (!startsWithSpace(rest)) {
			return Optional.empty();
		}

		rest = rest.trim();
		String type = null;
		for (String candidate : TYPES) {
			if (rest.startsWith(candidate)) {
				type = candidate;
				rest = rest.substring(candidate.length());
				break;
			}
		}

		// The space is what tells a type from the start of a longer name, so a declaration of
		// something called intensity is not read as an int.
		if (type == null || !startsWithSpace(rest)) {
			return Optional.empty();
		}

		int equals = rest.indexOf('=');
		if (equals < 0) {
			return Optional.empty();
		}

		String name = rest.substring(0, equals).trim();
		if (!isWord(name)) {
			return Optional.empty();
		}

		String tail = rest.substring(equals + 1);
		int semicolon = tail.indexOf(';');
		if (semicolon < 0) {
			return Optional.empty();
		}

		return Optional.of(new Directive(type, name, tail.substring(0, semicolon).trim(), number));
	}

	private static boolean startsWithSpace(String text) {
		return !text.isEmpty() && Character.isWhitespace(text.charAt(0));
	}

	private static boolean isWord(String text) {
		if (text.isEmpty()) {
			return false;
		}

		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			if (!Character.isDigit(character) && !Character.isAlphabetic(character) && character != '_') {
				return false;
			}
		}

		return true;
	}
}
