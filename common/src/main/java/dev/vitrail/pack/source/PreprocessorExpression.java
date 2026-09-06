package dev.vitrail.pack.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Evaluates the expression of an {@code #if} or {@code #elif} well enough to decide which
 * branch of a pack is live.
 * <p>
 * <strong>Arithmetic follows C's rules and not the preprocessor's, which is a deliberate
 * divergence from the language and the only one here.</strong> The preprocessor of GLSL works in
 * integers and refuses a fractional number outright; a GL driver takes one and reduces it, so packs
 * ship conditions the language forbids. Clarity writes {@code #if MOTION_BLUR > 0.0} with that
 * setting at 0.5 and Pegasus writes {@code #if SKY_LIGHT_FALLOFF == 1} with the macro at 1.0.
 * Answering those in integers alone truncates 0.5 to nought and switches an effect off in the
 * picture, silently, which is worse than refusing the line.
 * <p>
 * So a value carries whether it is whole, and the promotion is C's: {@code /} and {@code %} divide
 * in integers when both sides are whole and in floating point otherwise, so {@code 7 / 2 == 3}
 * still holds; the shifts and the bitwise operators take whole numbers only and yield no answer on
 * anything else, as C forbids them there; everything else widens as soon as one side is
 * fractional. What a comparison or a logical operator hands back is whole, being nought or one.
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

	/** Compiled once: an expression is evaluated for every conditional of every unit of a load. */
	private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/");
	private static final Pattern LINE_COMMENT = Pattern.compile("//.*");

	private final List<String> tokens;
	private final Map<String, String> defines;
	private final int depth;

	private int position;
	private int nesting;
	/**
	 * Whether a value could not be worked out: a division by nought, a shift by an absurd amount.
	 * Forgiven where it happened in an operand the preprocessor never evaluates.
	 */
	private boolean failed;

	/** Whether the text is not an expression at all, which no operator forgives. */
	private boolean malformed;

	/**
	 * Whether anything the expression read was fractional, which is what the compiler refuses the
	 * whole line for. Set wherever a value that is not whole is produced or resolved, including in
	 * an operand a short circuit spares, because the compiler reads the text either way.
	 */
	private boolean fractional;

	/**
	 * One value of an expression: what it is worth, and whether it is a whole number.
	 * <p>
	 * The flag is not the same question as {@code number == Math.rint(number)}. What decides
	 * whether a division is C's integer division is how the two operands were WRITTEN, so
	 * {@code 4.0 / 2} divides in floating point and answers 2.0 while {@code 4 / 2} answers the
	 * same 2 as a whole number, and only the second may then be shifted.
	 * <p>
	 * <strong>A whole number is carried as a {@code long} beside its double and read from there
	 * whenever both sides are whole</strong>, which is the arithmetic this class did before it
	 * learned about fractions and which a double cannot do: past two to the fifty-third a double
	 * has no room for consecutive integers, so {@code 9007199254740993 == 9007199254740992} would
	 * answer true and {@code 9007199254740993 & 1} would answer nought. Neither reaches a pack, and
	 * both would be a right answer replaced by a wrong one, which is the shape this repository pays
	 * for twice over.
	 */
	private record Scalar(double number, long whole, boolean integral) {

		static Scalar of(long value) {
			return new Scalar(value, value, true);
		}

		static Scalar of(boolean truth) {
			return of(truth ? 1 : 0);
		}

		static Scalar fraction(double value) {
			return new Scalar(value, (long) value, false);
		}

		boolean truth() {
			return this.integral ? this.whole != 0 : this.number != 0;
		}
	}

	private PreprocessorExpression(List<String> tokens, Map<String, String> defines, int depth) {
		this.tokens = tokens;
		this.defines = defines;
		this.depth = depth;
	}

	public static Optional<Boolean> evaluate(String expression, Map<String, String> defines) {
		return decide(expression, defines).taken();
	}

	/**
	 * What one condition decides, and whether the compiler will take the line at all.
	 *
	 * @param taken      the branch this expression opens, or empty where nothing could be worked out
	 * @param fractional whether the expression read a value that is not a whole number. The
	 *                   preprocessor of the language refuses such a line outright, so a caller that
	 *                   writes the text back out has to answer it here instead of passing it on
	 */
	public record Verdict(Optional<Boolean> taken, boolean fractional) {
	}

	/** The same as {@link #evaluate}, with what the caller needs to know about the text itself. */
	public static Verdict decide(String expression, Map<String, String> defines) {
		Reading read = read(expression, defines, 0);

		return new Verdict(read.value().map(Scalar::truth), read.fractional());
	}

	/** One evaluation: what it came to, and whether anything fractional was read on the way. */
	private record Reading(Optional<Scalar> value, boolean fractional) {
	}

	/** The numeric answer, which is what a name resolving to an expression needs. */
	private static Reading read(String expression, Map<String, String> defines, int depth) {
		if (depth > MAX_RESOLUTION_DEPTH) {
			return new Reading(Optional.empty(), false);
		}

		List<String> tokens = tokenise(stripComments(expression));
		if (tokens.isEmpty()) {
			return new Reading(Optional.empty(), false);
		}

		PreprocessorExpression parser = new PreprocessorExpression(tokens, defines, depth);
		Scalar result = parser.logicalOr();
		if (parser.failed || parser.malformed || parser.position < tokens.size()) {
			return new Reading(Optional.empty(), parser.fractional);
		}

		return new Reading(Optional.of(result), parser.fractional);
	}

	private static String stripComments(String expression) {
		return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(expression).replaceAll(" ")).replaceAll("");
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

	/**
	 * The two short-circuit operators. The right operand is always walked, so that its tokens are
	 * consumed and its syntax still has to hold, but where the left side already decides, what
	 * the right side failed to work out is forgotten: the preprocessor never evaluates that
	 * operand, so {@code X != 0 && 100 / X > 5} is false at zero rather than undecidable, and an
	 * undecidable answer would have put the whole branch back in.
	 */
	private Scalar logicalOr() {
		Scalar left = logicalAnd();
		while (accept("||")) {
			boolean decided = this.failed;
			Scalar right = logicalAnd();
			if (left.truth()) {
				this.failed = decided;
			}

			left = Scalar.of(left.truth() || right.truth());
		}

		return left;
	}

	private Scalar logicalAnd() {
		Scalar left = bitwiseOr();
		while (accept("&&")) {
			boolean decided = this.failed;
			Scalar right = bitwiseOr();
			if (!left.truth()) {
				this.failed = decided;
			}

			left = Scalar.of(left.truth() && right.truth());
		}

		return left;
	}

	private Scalar bitwiseOr() {
		Scalar left = bitwiseXor();
		while ("|".equals(peek())) {
			this.position++;
			left = bits(left, bitwiseXor(), "|");
		}

		return left;
	}

	private Scalar bitwiseXor() {
		Scalar left = bitwiseAnd();
		while (accept("^")) {
			left = bits(left, bitwiseAnd(), "^");
		}

		return left;
	}

	private Scalar bitwiseAnd() {
		Scalar left = equality();
		while ("&".equals(peek())) {
			this.position++;
			left = bits(left, equality(), "&");
		}

		return left;
	}

	/**
	 * The bitwise operators, which C gives whole numbers only. A fractional operand is not rounded
	 * into one: that would answer a question the language refuses to ask, so it yields no answer.
	 */
	private Scalar bits(Scalar left, Scalar right, String operator) {
		if (!left.integral() || !right.integral()) {
			this.failed = true;

			return Scalar.of(0);
		}

		long a = left.whole();
		long b = right.whole();

		return Scalar.of(switch (operator) {
			case "|" -> a | b;
			case "^" -> a ^ b;
			default -> a & b;
		});
	}

	private Scalar equality() {
		Scalar left = relational();
		while (true) {
			if (accept("==")) {
				left = Scalar.of(compare(left, relational()) == 0);
			} else if (accept("!=")) {
				left = Scalar.of(compare(left, relational()) != 0);
			} else {
				return left;
			}
		}
	}

	private Scalar relational() {
		Scalar left = shift();
		while (true) {
			if (accept("<=")) {
				left = Scalar.of(compare(left, shift()) <= 0);
			} else if (accept(">=")) {
				left = Scalar.of(compare(left, shift()) >= 0);
			} else if (accept("<")) {
				left = Scalar.of(compare(left, shift()) < 0);
			} else if (accept(">")) {
				left = Scalar.of(compare(left, shift()) > 0);
			} else {
				return left;
			}
		}
	}

	/**
	 * Two values ordered, in whole numbers where both are whole. Comparing them as doubles instead
	 * would call two consecutive integers equal above two to the fifty-third, where C does not.
	 */
	private static int compare(Scalar left, Scalar right) {
		return left.integral() && right.integral()
				? Long.compare(left.whole(), right.whole())
				: Double.compare(left.number(), right.number());
	}

	private Scalar shift() {
		Scalar left = additive();
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

	/**
	 * A shift by a negative or absurd amount is undefined in C, so it is not an answer here, and
	 * neither is a shift of or by anything that is not a whole number.
	 */
	private Scalar shiftBy(Scalar left, Scalar places, boolean up) {
		if (!left.integral() || !places.integral()
				|| places.whole() < 0 || places.whole() > 63) {
			this.failed = true;

			return Scalar.of(0);
		}

		return Scalar.of(up ? left.whole() << places.whole() : left.whole() >> places.whole());
	}

	private Scalar additive() {
		Scalar left = multiplicative();
		while (true) {
			if (accept("+")) {
				Scalar right = multiplicative();
				left = left.integral() && right.integral()
						? Scalar.of(left.whole() + right.whole())
						: Scalar.fraction(left.number() + right.number());
			} else if (accept("-")) {
				Scalar right = multiplicative();
				left = left.integral() && right.integral()
						? Scalar.of(left.whole() - right.whole())
						: Scalar.fraction(left.number() - right.number());
			} else {
				return left;
			}
		}
	}

	private Scalar multiplicative() {
		Scalar left = unary();
		while (true) {
			if (accept("*")) {
				Scalar right = unary();
				left = left.integral() && right.integral()
						? Scalar.of(left.whole() * right.whole())
						: Scalar.fraction(left.number() * right.number());
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
	 * <p>
	 * <strong>Whole against whole divides in whole numbers</strong>, which is C's rule and what
	 * keeps {@code 7 / 2 == 3} true. As soon as one side is fractional the division is a floating
	 * one, and the remainder is not defined on those at all.
	 */
	private Scalar divide(Scalar left, Scalar right, boolean remainder) {
		if (!right.truth()) {
			this.failed = true;

			return Scalar.of(0);
		}

		if (!left.integral() || !right.integral()) {
			if (remainder) {
				this.failed = true;

				return Scalar.of(0);
			}

			return Scalar.fraction(left.number() / right.number());
		}

		long a = left.whole();
		long b = right.whole();
		if (a == Long.MIN_VALUE && b == -1) {
			this.failed = true;

			return Scalar.of(0);
		}

		return Scalar.of(remainder ? a % b : a / b);
	}

	private Scalar unary() {
		String token = peek();
		if (token == null || !isPrefix(token)) {
			return primary();
		}

		this.position++;
		if (++this.nesting > MAX_NESTING_DEPTH) {
			this.malformed = true;
			return Scalar.of(0);
		}

		Scalar value = unary();
		this.nesting--;

		switch (token) {
			case "!":
				return Scalar.of(!value.truth());
			case "-":
				return value.integral() ? Scalar.of(-value.whole())
						: Scalar.fraction(-value.number());
			case "~":
				if (!value.integral()) {
					this.failed = true;

					return Scalar.of(0);
				}

				return Scalar.of(~value.whole());
			default:
				return value;
		}
	}

	private static boolean isPrefix(String token) {
		return switch (token) {
			case "!", "-", "+", "~" -> true;
			default -> false;
		};
	}

	private Scalar primary() {
		String token = peek();
		if (token == null) {
			this.malformed = true;
			return Scalar.of(0);
		}

		if (accept("(")) {
			if (++this.nesting > MAX_NESTING_DEPTH) {
				this.malformed = true;
				return Scalar.of(0);
			}

			Scalar value = logicalOr();
			this.nesting--;
			if (!accept(")")) {
				this.malformed = true;
			}

			return value;
		}

		this.position++;

		if (token.equals("defined")) {
			return definedOperator();
		}

		if (isIdentifier(token)) {
			Reading read = resolve(token, this.defines, this.depth + 1);
			this.fractional |= read.fractional();

			return read.value().orElse(Scalar.of(0));
		}

		Optional<Scalar> number = parseNumber(token);
		if (number.isEmpty()) {
			this.malformed = true;
			return Scalar.of(0);
		}

		this.fractional |= !number.get().integral();

		return number.get();
	}

	private Scalar definedOperator() {
		boolean parenthesised = accept("(");
		String name = peek();
		if (name == null || !isIdentifier(name)) {
			this.malformed = true;
			return Scalar.of(0);
		}

		this.position++;
		if (parenthesised && !accept(")")) {
			this.malformed = true;
			return Scalar.of(0);
		}

		return Scalar.of(this.defines.containsKey(name));
	}

	/**
	 * An identifier stands for whatever it was defined as, and that in turn may be another
	 * identifier or a whole expression. A name nothing defines is zero, which is what the
	 * preprocessor does and what lets a pack test a setting it never declared.
	 */
	private static Reading resolve(String name, Map<String, String> defines, int depth) {
		if (depth > MAX_RESOLUTION_DEPTH) {
			return new Reading(Optional.of(Scalar.of(0)), false);
		}

		String value = defines.get(name);
		if (value == null || value.isBlank()) {
			// Defined but empty is the usual shape of a switch that is on.
			return new Reading(Optional.of(Scalar.of(defines.containsKey(name))), false);
		}

		String trimmed = value.trim();
		Optional<Scalar> number = parseNumber(trimmed);
		if (number.isPresent()) {
			return new Reading(number, !number.get().integral());
		}

		if (isIdentifier(trimmed)) {
			return resolve(trimmed, defines, depth + 1);
		}

		// An expression keeps its value rather than collapsing to one or zero. A pack that
		// writes SHADOW_RES as QUALITY * 512 and then compares it against 256 is comparing
		// sizes, and reducing that to a truth value quietly gives the wrong branch.
		Reading read = read(trimmed, defines, depth + 1);

		return new Reading(Optional.of(read.value().orElse(Scalar.of(1))), read.fractional());
	}

	private static boolean isIdentifier(String token) {
		if (token.isEmpty() || !(Character.isLetter(token.charAt(0)) || token.charAt(0) == '_')) {
			return false;
		}

		return token.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
	}

	private static Optional<Scalar> parseNumber(String token) {
		boolean hexadecimal = token.length() > 2 && (token.startsWith("0x") || token.startsWith("0X"));

		// The base has to be known before a suffix can be stripped: in hexadecimal, f and F are
		// digits, and taking them for a float suffix turns 0x1F into 0x1 without a word.
		String suffixes = hexadecimal ? "uUlL" : "uUlLfF";
		String text = token;
		while (!text.isEmpty() && suffixes.indexOf(text.charAt(text.length() - 1)) >= 0) {
			text = text.substring(0, text.length() - 1);
		}

		if (text.isEmpty()) {
			return Optional.empty();
		}

		try {
			if (hexadecimal) {
				return Optional.of(Scalar.of(Long.parseLong(text.substring(2), 16)));
			}

			// Kept as it was written and not rounded here, which is the whole of the divergence
			// this class carries: 0.5 is a half all the way to the comparison that reads it, and
			// the caller is told the line held one so that it can answer for a compiler that will
			// not. An exponent makes a number fractional as surely as a point does, 1e3 being a
			// float in every language that has both.
			if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
				return Optional.of(Scalar.fraction(Double.parseDouble(text)));
			}

			return Optional.of(Scalar.of(Long.parseLong(text)));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}
}
