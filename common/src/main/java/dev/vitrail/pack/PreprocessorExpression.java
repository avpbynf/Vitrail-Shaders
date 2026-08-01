package dev.vitrail.pack;

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
 * An expression this cannot parse yields no answer rather than false, and the caller is
 * expected to treat that as true. Including code that should have been skipped leaves a
 * compiler error to find; skipping code that should have been included removes a function
 * whose absence surfaces somewhere else entirely.
 */
public final class PreprocessorExpression {

	/** How far a macro may be resolved through other macros before giving up. */
	private static final int MAX_RESOLUTION_DEPTH = 8;

	private final List<String> tokens;
	private final Map<String, String> defines;

	private int position;
	private boolean failed;

	private PreprocessorExpression(List<String> tokens, Map<String, String> defines) {
		this.tokens = tokens;
		this.defines = defines;
	}

	public static Optional<Boolean> evaluate(String expression, Map<String, String> defines) {
		List<String> tokens = tokenise(stripComments(expression));
		if (tokens.isEmpty()) {
			return Optional.empty();
		}

		PreprocessorExpression parser = new PreprocessorExpression(tokens, defines);
		long value = parser.logicalOr();
		if (parser.failed || parser.position < tokens.size()) {
			return Optional.empty();
		}

		return Optional.of(value != 0);
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
				left <<= additive();
			} else if (accept(">>")) {
				left >>= additive();
			} else {
				return left;
			}
		}
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
				long divisor = unary();
				// A pack dividing by zero is a pack whose condition cannot be decided, not a
				// reason to bring the load down.
				if (divisor == 0) {
					this.failed = true;
					return 0;
				}

				left /= divisor;
			} else if (accept("%")) {
				long divisor = unary();
				if (divisor == 0) {
					this.failed = true;
					return 0;
				}

				left %= divisor;
			} else {
				return left;
			}
		}
	}

	private long unary() {
		if (accept("!")) {
			return unary() == 0 ? 1 : 0;
		}

		if (accept("-")) {
			return -unary();
		}

		if (accept("+")) {
			return unary();
		}

		if (accept("~")) {
			return ~unary();
		}

		return primary();
	}

	private long primary() {
		String token = peek();
		if (token == null) {
			this.failed = true;
			return 0;
		}

		if (accept("(")) {
			long value = logicalOr();
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
			return resolve(token, this.defines, 0);
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
	 * identifier. A name nothing defines is zero, which is what the preprocessor does and what
	 * lets a pack test a setting it never declared.
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

		// Anything else is an expression: evaluate it in the same table rather than guessing.
		return evaluate(trimmed, defines).map(value2 -> value2 ? 1L : 0L).orElse(1L);
	}

	private static boolean isIdentifier(String token) {
		if (token.isEmpty() || !(Character.isLetter(token.charAt(0)) || token.charAt(0) == '_')) {
			return false;
		}

		return token.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
	}

	private static OptionalLong parseNumber(String token) {
		String text = token;
		while (!text.isEmpty() && "uUlLfF".indexOf(text.charAt(text.length() - 1)) >= 0) {
			text = text.substring(0, text.length() - 1);
		}

		if (text.isEmpty()) {
			return OptionalLong.empty();
		}

		try {
			if (text.length() > 2 && (text.startsWith("0x") || text.startsWith("0X"))) {
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
