package dev.vitrail.pack.source;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes the engine's and the pack's symbols into a line of a properties file.
 * <p>
 * A source file never needs this: the GLSL compiler is a preprocessor and does it downstream. A
 * properties file has no compiler behind it, and the values it carries are read directly, so the
 * substitution has to happen here or not at all. BSL writes
 * {@code in(biome, BIOME_GROVE, BIOME_FROZEN_OCEAN, ...)} and Reverie writes the same list as
 * numbers; without this they are not the same file.
 * <p>
 * A symbol defined with no value is left alone rather than erased. A real preprocessor would
 * delete it and leave a hole that fails to parse a line or two later; leaving the name in place
 * makes the reader say which name it did not know, which is the difference between a report and a
 * puzzle.
 */
public final class Macros {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");

	/** A symbol may be defined in terms of another. Four levels is more than the corpus uses. */
	private static final int MAX_ROUNDS = 4;

	/**
	 * How long a line may grow while its symbols are substituted, where it did not come in longer.
	 * <p>
	 * The rounds bound how deep a symbol may lead and not how wide: a symbol written as a thousand
	 * others, each of those as a thousand more, turns a line of one name into two gigabytes by the
	 * third round, which is an {@code OutOfMemoryError} while the client is still starting. The
	 * longest line of the three packs measured is thirty kilobytes, in Eclipse's
	 * {@code block.properties}, and substituting does not lengthen it; this is eight times that.
	 */
	private static final int MAX_CHARACTERS = 256 * 1024;

	private Macros() {
	}

	/**
	 * The text with the symbols substituted, as far as the rounds go. A round that would grow the
	 * line past {@link #MAX_CHARACTERS} is abandoned and the line is handed back as the round
	 * before left it, names and all, for the reason a symbol with no value is left alone: the reader
	 * then says which name it could not read.
	 */
	public static String expand(String text, Map<String, String> defines) {
		int most = Math.max(MAX_CHARACTERS, text.length());
		String current = text;

		for (int round = 0; round < MAX_ROUNDS; round++) {
			String next = expandOnce(current, defines, most);
			if (next == null || next.equals(current)) {
				return current;
			}

			current = next;
		}

		return current;
	}

	/** One round, or null as soon as the line it builds is longer than {@code most}. */
	private static String expandOnce(String text, Map<String, String> defines, int most) {
		Matcher identifier = IDENTIFIER.matcher(text);
		StringBuilder out = new StringBuilder();

		while (identifier.find()) {
			String name = identifier.group();
			String value = defines.get(name);
			boolean substitute = value != null && !value.isBlank() && !value.equals(name)
					&& !component(text, identifier.start());

			identifier.appendReplacement(out, Matcher.quoteReplacement(substitute ? value : name));
			if (out.length() > most) {
				return null;
			}
		}

		identifier.appendTail(out);

		return out.length() > most ? null : out.toString();
	}

	/**
	 * Whether the identifier is the part after a dot, which makes it a component and not a name.
	 * Every one of them is a single letter, {@code x} through {@code q}, and a pack that happens
	 * to define one of those as a symbol would otherwise turn {@code sunPosition.x} into
	 * {@code sunPosition.1}, which parses and reads the wrong axis.
	 */
	private static boolean component(String text, int start) {
		for (int i = start - 1; i >= 0; i--) {
			char c = text.charAt(i);
			if (c == '.') {
				return true;
			}
			if (!Character.isWhitespace(c)) {
				return false;
			}
		}

		return false;
	}
}
