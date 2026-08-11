package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits GLSL into tokens and keeps every space, comment and newline, so that a stream nothing
 * has touched joins back into the exact text it came from.
 * <p>
 * Nothing here parses the language. What the translator needs is to tell the identifier
 * {@code texture} apart from the same seven letters inside a comment, and to know whether a token
 * sits on a preprocessor line. Both are lexical questions, and stopping there keeps this small
 * enough to trust against eight packs of code written for something else.
 */
public final class GlslLexer {

	private GlslLexer() {
	}

	public enum Kind {

		IDENTIFIER,
		NUMBER,
		OPERATOR,
		COMMENT,
		SPACE,
		NEWLINE,

		/** A {@code #} that opens a preprocessor line, as opposed to one pasting macro arguments. */
		HASH,

		/** Text the translator injected. It is emitted as it is and no later pass matches it. */
		RAW
	}

	/**
	 * One token of the source, with the preprocessor line it sits on.
	 *
	 * @param directive the preprocessor keyword of the line this token sits on, {@code null} when
	 *                  the token is ordinary code. A {@code #} on its own gives an empty string.
	 */
	public record Token(Kind kind, String text, String directive) {

		public static final Token BLANK = new Token(Kind.SPACE, "", null);

		public boolean trivia() {
			return this.kind == Kind.SPACE || this.kind == Kind.COMMENT;
		}

		public boolean identifier(String name) {
			return this.kind == Kind.IDENTIFIER && this.text.equals(name);
		}

		public boolean operator(String symbol) {
			return this.kind == Kind.OPERATOR && this.text.equals(symbol);
		}

		public Token as(String replacement) {
			return new Token(this.kind, replacement, this.directive);
		}
	}

	public static List<Token> lex(String source) {
		List<Token> tokens = new ArrayList<>();
		int index = 0;
		int length = source.length();

		while (index < length) {
			char c = source.charAt(index);

			if (c == '\n' || c == '\r') {
				int end = index + 1;
				if (c == '\r' && end < length && source.charAt(end) == '\n') {
					end++;
				}

				tokens.add(new Token(Kind.NEWLINE, source.substring(index, end), null));
				index = end;
				continue;
			}

			// A backslash before a line break splices the two lines together before anything else
			// reads them. That is how a macro body runs past its own line, so the break has to be
			// swallowed here rather than end the directive.
			if (c == '\\' && index + 1 < length && (source.charAt(index + 1) == '\n' || source.charAt(index + 1) == '\r')) {
				int end = index + 2;
				if (source.charAt(index + 1) == '\r' && end < length && source.charAt(end) == '\n') {
					end++;
				}

				tokens.add(new Token(Kind.SPACE, source.substring(index, end), null));
				index = end;
				continue;
			}

			if (c == ' ' || c == '\t' || c == '\f' || c == 0x0B) {
				int end = index;
				while (end < length && isBlank(source.charAt(end))) {
					end++;
				}

				tokens.add(new Token(Kind.SPACE, source.substring(index, end), null));
				index = end;
				continue;
			}

			if (c == '/' && index + 1 < length && source.charAt(index + 1) == '/') {
				int end = index;
				while (end < length && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
					end++;
				}

				tokens.add(new Token(Kind.COMMENT, source.substring(index, end), null));
				index = end;
				continue;
			}

			if (c == '/' && index + 1 < length && source.charAt(index + 1) == '*') {
				int close = source.indexOf("*/", index + 2);
				int end = close < 0 ? length : close + 2;
				tokens.add(new Token(Kind.COMMENT, source.substring(index, end), null));
				index = end;
				continue;
			}

			if (isDigit(c) || (c == '.' && index + 1 < length && isDigit(source.charAt(index + 1)))) {
				int end = numberEnd(source, index);
				tokens.add(new Token(Kind.NUMBER, source.substring(index, end), null));
				index = end;
				continue;
			}

			if (isIdentifierStart(c)) {
				int end = index;
				while (end < length && isIdentifierPart(source.charAt(end))) {
					end++;
				}

				tokens.add(new Token(Kind.IDENTIFIER, source.substring(index, end), null));
				index = end;
				continue;
			}

			// One character per operator token. The translator only ever asks about brackets,
			// commas and semicolons, and single characters make joining the stream back exact.
			tokens.add(new Token(Kind.OPERATOR, String.valueOf(c), null));
			index++;
		}

		return markDirectives(tokens);
	}

	public static String join(List<Token> tokens) {
		StringBuilder text = new StringBuilder();
		for (Token token : tokens) {
			text.append(token.text());
		}

		return text.toString();
	}

	/**
	 * Tags each token with the directive it belongs to. A block comment carrying a line break does
	 * not end a directive, which falls out of the comment being one token with no newline of its
	 * own.
	 */
	private static List<Token> markDirectives(List<Token> tokens) {
		List<Token> marked = new ArrayList<>(tokens.size());
		boolean lineStart = true;
		int index = 0;

		while (index < tokens.size()) {
			Token token = tokens.get(index);

			if (token.kind() == Kind.NEWLINE) {
				marked.add(token);
				lineStart = true;
				index++;
				continue;
			}

			if (token.trivia()) {
				marked.add(token);
				index++;
				continue;
			}

			if (!lineStart || !token.operator("#")) {
				marked.add(token);
				lineStart = false;
				index++;
				continue;
			}

			int end = index;
			while (end < tokens.size() && tokens.get(end).kind() != Kind.NEWLINE) {
				end++;
			}

			String name = "";
			for (int scan = index + 1; scan < end; scan++) {
				Token candidate = tokens.get(scan);
				if (candidate.trivia()) {
					continue;
				}

				name = candidate.kind() == Kind.IDENTIFIER ? candidate.text() : "";
				break;
			}

			marked.add(new Token(Kind.HASH, token.text(), name));
			for (int scan = index + 1; scan < end; scan++) {
				Token inside = tokens.get(scan);
				marked.add(new Token(inside.kind(), inside.text(), name));
			}

			index = end;
			lineStart = false;
		}

		return marked;
	}

	private static int numberEnd(String source, int start) {
		int end = start;

		while (end < source.length()) {
			char c = source.charAt(end);
			if (isIdentifierPart(c) || c == '.') {
				end++;
				continue;
			}

			// The sign of an exponent, the only place an operator belongs inside a number.
			if ((c == '+' || c == '-') && end > start) {
				char previous = source.charAt(end - 1);
				if (previous == 'e' || previous == 'E') {
					end++;
					continue;
				}
			}

			break;
		}

		return end;
	}

	private static boolean isBlank(char c) {
		return c == ' ' || c == '\t' || c == '\f' || c == 0x0B;
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	/** Deliberately ASCII: GLSL identifiers are, and a pack file may hold any bytes at all. */
	private static boolean isIdentifierStart(char c) {
		return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	private static boolean isIdentifierPart(char c) {
		return isIdentifierStart(c) || isDigit(c);
	}
}
