package dev.vitrail.pack.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Evaluates the expression of an {@code #if} or {@code #elif} well enough to decide which
 * branch of a pack is live.
 * <p>
 * Arithmetic is in integers, as the preprocessor defines it, not in floating point: a pack
 * writing {@code #if QUALITY / 2} expects the C answer, and the compiler that sees the same
 * line later will give the C answer whatever is decided here.
 * <p>
 * A name stands for a value, and that value may itself be an expression naming other settings,
 * so evaluating one expression can start another. The budget for that is shared across the
 * whole nest rather than restarting at each hop, because a pack is downloaded content and
 * {@code #define A (B)} beside {@code #define B (A)} would otherwise exhaust the stack. That
 * failure is not catchable where this is called from, so it has to be impossible here.
 * <p>
 * An expression this cannot parse yields no answer rather than false, and the caller is
 * expected to treat that as true. Including code that should have been skipped leaves a
 * compiler error to find; skipping code that should have been included removes a function
 * whose absence surfaces somewhere else entirely.
 */
public final class PreprocessorExpression {

	/** How far one name may lead to another before the nest is called unresolvable. */
	private static final int MAX_RESOLUTION_DEPTH = 8;

	/**
	 * No real expression nests this deep, and a crafted one must not reach the stack limit. A
	 * bracket and a prefix operator nest the same way and share the budget, because a bracket
	 * limit alone bounds nothing: ten thousand exclamation marks in a row cost ten thousand frames
	 * and no brackets at all.
	 */
	private static final int MAX_NESTING_DEPTH = 64;

	private final List<String> tokens;
	private final Map<String, String> defines;
	private final int depth;

	private int position;
	private int nesting;
	private boolean failed;

	private PreprocessorExpression(List<String> tokens, Map<String, String> defines, int depth) {
		this.tokens = tokens;
		this.defines = defines;
		this.depth = depth;
	}

	public static Optional<Boolean> evaluate(String expression, Map<String, String> defines) {
		OptionalLong value = value(expression, defines, 0);

		return value.isPresent() ? Optional.of(value.getAsLong() != 0) : Optional.empty();
	}

	/** The numeric answer, which is what a name resolving to an expression needs. */
	private static OptionalLong value(String expression, Map<String, String> defines, int depth) {
		if (depth > MAX_RESOLUTION_DEPTH) {
			return OptionalLong.empty();
		}

		List<String> tokens = tokenise(stripComments(expression));
		if (tokens.isEmpty()) {
			return OptionalLong.empty();
		}

		PreprocessorExpression parser = new PreprocessorExpression(tokens, defines, depth);
		long result = parser.logicalOr();
		if (parser.failed || parser.position < tokens.size()) {
			return OptionalLong.empty();
		}

		return OptionalLong.of(result);
	}

	private static String stripComments(String expression) {
		return expression.replaceAll("/\\*.*?\\*/", " ").replaceAll("//.*", "");
	}

	private static List<String> tokenise(String text) {
		List<String> tokens = new ArrayList<>();
		int i = 0;

		while (i < text.length()) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
			} else if (Character.isLetter(c) || c == '_') {
				int start = i;
				while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
					i++;
				}

				tokens.add(text.substring(start, i));
			} else if (Character.isDigit(c)) {
				int start = i;
				while (i < text.length() && isNumberPart(text.charAt(i))) {
					i++;
				}

				tokens.add(text.substring(start, i));
			} else {
				// Two-character operators first, so that "<=" is never read as "<" then "=".
				if (i + 1 < text.length() && isTwoCharacterOperator(text.substring(i, i + 2))) {
					tokens.add(text.substring(i, i + 2));
					i += 2;
				} else {
					tokens.add(String.valueOf(c));
					i++;
				}
			}
		}

		return tokens;
	}

	private static boolean isNumberPart(char c) {
		return Character.isLetterOrDigit(c) || c == '.';
	}

	private static boolean isTwoCharacterOperator(String pair) {
		return switch (pair) {
			case "&&", "||", "==", "!=", "<=", ">=", "<<", ">>" -> true;
			default -> false;
		};
	}

	private String peek() {
		return this.position < this.tokens.size() ? this.tokens.get(this.position) : null;
	}

	private boolean accept(String token) {
		if (token.equals(peek())) {
			this.position++;
			return true;
		}

		return false;
	}

	private long logicalOr() {
		long left = logicalAnd();
		while (accept("||")) {
			long right = logicalAnd();
			left = left != 0 || right != 0 ? 1 : 0;
		}

		return left;
	}

	private long logicalAnd() {
		long left = bitwiseOr();
		while (accept("&&")) {
			long right = bitwiseOr();
			left = left != 0 && right != 0 ? 1 : 0;
		}

		return left;
	}

	private long bitwiseOr() {
		long left = bitwiseXor();
		while ("|".equals(peek())) {
			this.position++;
			left |= bitwiseXor();
		}

		return left;
	}

	private long bitwiseXor() {
		long left = bitwiseAnd();
		while (accept("^")) {
			left ^= bitwiseAnd();
		}

		return left;
	}

	private long bitwiseAnd() {
		long left = equality();
		while ("&".equals(peek())) {
			this.position++;
			left &= equality();
		}

		return left;
	}

	private long equality() {
		long left = relational();
		while (true) {
			if (accept("==")) {
				left = left == relational() ? 1 : 0;
			} else if (accept("!=")) {
				left = left != relational() ? 1 : 0;
			} else {
				return left;
			}
		}
	}

	private long relational() {
		long left = shift();
		while (true) {
			if (accept("<=")) {
				left = left <= shift() ? 1 : 0;
			} else if (accept(">=")) {
				left = left >= shift() ? 1 : 0;
			} else if (accept("<")) {
				left = left < shift() ? 1 : 0;
			} else if (accept(">")) {
				left = left > shift() ? 1 : 0;
			} else {
				return left;
			}
		}
	}

	private long shift() {
		long left = additive();
		while (true) {
			if (accept("<<")) {
				left = shiftBy(left, additive(), true);
			} else if (accept(">>")) {
				left = shiftBy(left, additive(), false);
			} else {
				return left;
			}
		}
	}

	/** A shift by a negative or absurd amount is undefined in C, so it is not an answer here. */
	private long shiftBy(long left, long places, boolean up) {
		if (places < 0 || places > 63) {
			this.failed = true;
			return 0;
		}

		return up ? left << places : left >> places;
	}

	private long additive() {
		long left = multiplicative();
		while (true) {
			if (accept("+")) {
				left += multiplicative();
			} else if (accept("-")) {
				left -= multiplicative();
			} else {
				return left;
			}
		}
	}

	private long multiplicative() {
		long left = unary();
		while (true) {
			if (accept("*")) {
				left *= unary();
			} else if (accept("/")) {
				left = divide(left, unary(), false);
			} else if (accept("%")) {
				left = divide(left, unary(), true);
			} else {
				return left;
			}
		}
	}

	/**
	 * Zero and the one overflowing case are treated as no answer rather than as an exception:
	 * a pack whose condition cannot be worked out is not a reason to abandon the load.
	 */
	private long divide(long left, long right, boolean remainder) {
		if (right == 0 || (left == Long.MIN_VALUE && right == -1)) {
			this.failed = true;
			return 0;
		}

		return remainder ? left % right : left / right;
	}

	private long unary() {
		String token = peek();
		if (token == null || !isPrefix(token)) {
			return primary();
		}

		this.position++;
		if (++this.nesting > MAX_NESTING_DEPTH) {
			this.failed = true;
			return 0;
		}

		long value = unary();
		this.nesting--;

		return switch (token) {
			case "!" -> value == 0 ? 1 : 0;
			case "-" -> -value;
			case "~" -> ~value;
			default -> value;
		};
	}

	private static boolean isPrefix(String token) {
		return switch (token) {
			case "!", "-", "+", "~" -> true;
			default -> false;
		};
	}

	private long primary() {
		String token = peek();
		if (token == null) {
			this.failed = true;
			return 0;
		}

		if (accept("(")) {
			if (++this.nesting > MAX_NESTING_DEPTH) {
				this.failed = true;
				return 0;
			}

			long value = logicalOr();
			this.nesting--;
			if (!accept(")")) {
				this.failed = true;
			}

			return value;
		}

		this.position++;

		if (token.equals("defined")) {
			return definedOperator();
		}

		if (isIdentifier(token)) {
			return resolve(token, this.defines, this.depth + 1);
		}

		OptionalLong number = parseNumber(token);
		if (number.isEmpty()) {
			this.failed = true;
			return 0;
		}

		return number.getAsLong();
	}

	private long definedOperator() {
		boolean parenthesised = accept("(");
		String name = peek();
		if (name == null || !isIdentifier(name)) {
			this.failed = true;
			return 0;
		}

		this.position++;
		if (parenthesised && !accept(")")) {
			this.failed = true;
			return 0;
		}

		return this.defines.containsKey(name) ? 1 : 0;
	}

	/**
	 * An identifier stands for whatever it was defined as, and that in turn may be another
	 * identifier or a whole expression. A name nothing defines is zero, which is what the
	 * preprocessor does and what lets a pack test a setting it never declared.
	 */
	public static long resolve(String name, Map<String, String> defines, int depth) {
		if (depth > MAX_RESOLUTION_DEPTH) {
			return 0;
		}

		String value = defines.get(name);
		if (value == null || value.isBlank()) {
			// Defined but empty is the usual shape of a switch that is on.
			return defines.containsKey(name) ? 1 : 0;
		}

		String trimmed = value.trim();
		OptionalLong number = parseNumber(trimmed);
		if (number.isPresent()) {
			return number.getAsLong();
		}

		if (isIdentifier(trimmed)) {
			return resolve(trimmed, defines, depth + 1);
		}

		// An expression keeps its value rather than collapsing to one or zero. A pack that
		// writes SHADOW_RES as QUALITY * 512 and then compares it against 256 is comparing
		// sizes, and reducing that to a truth value quietly gives the wrong branch.
		return value(trimmed, defines, depth + 1).orElse(1L);
	}

	private static boolean isIdentifier(String token) {
		if (token.isEmpty() || !(Character.isLetter(token.charAt(0)) || token.charAt(0) == '_')) {
			return false;
		}

		return token.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
	}

	private static OptionalLong parseNumber(String token) {
		boolean hexadecimal = token.length() > 2 && (token.startsWith("0x") || token.startsWith("0X"));

		// The base has to be known before a suffix can be stripped: in hexadecimal, f and F are
		// digits, and taking them for a float suffix turns 0x1F into 0x1 without a word.
		String suffixes = hexadecimal ? "uUlL" : "uUlLfF";
		String text = token;
		while (!text.isEmpty() && suffixes.indexOf(text.charAt(text.length() - 1)) >= 0) {
			text = text.substring(0, text.length() - 1);
		}

		if (text.isEmpty()) {
			return OptionalLong.empty();
		}

		try {
			if (hexadecimal) {
				return OptionalLong.of(Long.parseLong(text.substring(2), 16));
			}

			// A pack may well write 1.0 in a condition. Truncating matches what the compiler
			// does with the same line, which is the only thing that has to agree.
			if (text.indexOf('.') >= 0) {
				return OptionalLong.of((long) Double.parseDouble(text));
			}

			return OptionalLong.of(Long.parseLong(text));
		} catch (NumberFormatException e) {
			return OptionalLong.empty();
		}
	}
}
