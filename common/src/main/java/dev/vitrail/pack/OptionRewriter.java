package dev.vitrail.pack;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a chosen setting by rewriting the line that declares it, where it stands.
 * <p>
 * The obvious alternative, gathering every setting into a block of {@code #define} at the top
 * of the unit, is wrong. A declaration's position is part of its meaning: packs test a setting
 * with {@code #ifdef} above the line that declares it, relying on it being undefined at that
 * point, and hoisting the declaration silently flips those tests.
 */
public final class OptionRewriter {

	private static final Pattern DEFINE =
			Pattern.compile("^(\\s*)(//\\s*)?#\\s*define\\s+([A-Za-z_]\\w*)\\b(.*)$");
	private static final Pattern CONSTANT =
			Pattern.compile("^(\\s*)const\\s+(int|float|bool|uint)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);(.*)$");

	/** The list of allowed values a pack writes after a declaration, kept when rewriting. */
	private static final Pattern VALUE_LIST_COMMENT = Pattern.compile("(//\\s*\\[.*)$");

	private OptionRewriter() {
	}

	/** Returns the line unchanged when it declares nothing, or nothing was chosen for it. */
	public static String apply(String line, Map<String, OptionValue> chosen) {
		Matcher define = DEFINE.matcher(line);
		if (define.matches()) {
			OptionValue value = chosen.get(define.group(3));
			if (value == null) {
				return line;
			}

			String indent = define.group(1);
			String name = define.group(3);
			Matcher list = VALUE_LIST_COMMENT.matcher(define.group(4));
			String tail = list.find() ? " " + list.group(1) : "";

			if (!value.isBoolean()) {
				return indent + "#define " + name + " " + value.text() + tail;
			}

			// Turning a switch off comments the line out rather than defining it to false: the
			// pack tests it with #ifdef, which any definition at all would satisfy.
			return indent + (value.asBoolean() ? "#define " : "//#define ") + name + tail;
		}

		Matcher constant = CONSTANT.matcher(line);
		if (constant.matches()) {
			OptionValue value = chosen.get(constant.group(3));
			// A boolean says nothing about a constant that holds a number, so leave it alone.
			if (value == null || value.isBoolean()) {
				return line;
			}

			return constant.group(1) + "const " + constant.group(2) + " " + constant.group(3)
					+ " = " + value.text() + ";" + constant.group(5);
		}

		return line;
	}
}
