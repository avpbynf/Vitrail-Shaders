package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * The tokens one unit is rewritten in, and every way there is of reading them or changing them.
 * <p>
 * {@link GlslTranslator} is a pipeline of passes over this list, and every one of them works by
 * index: the next token that is not space, the bracket that closes this one, the statement a name
 * sits in, the line the pack wrote it on. That is the substrate the whole rewrite stands on rather
 * than a pass of its own, so it lives here, and the list is this class's alone. A pass reaches it
 * through the methods below or not at all, and cannot leave it in a shape they would misread.
 * <p>
 * Two answers are kept between calls instead of being worked out again, where a function ends and
 * which line each token is on, and they belong here for the same reason: what makes either stale is
 * this class's own {@link #insertClosings}, and nothing else in the rewrite moves a token at all.
 */
final class TokenStream implements Iterable<Token> {

	/**
	 * How far a declaration may reach for its semicolon. A pack that leaves one out would
	 * otherwise have the rest of the file scanned once per {@code uniform} it declares, which is
	 * quadratic: forty thousand such lines took twenty-seven seconds.
	 */
	private static final int MAX_STATEMENT_TOKENS = 4096;

	private final List<Token> tokens;

	/** The parameter list {@link #functionEnd} last answered for, and its answer. */
	private int functionEndFor = -1;
	private int functionEndAt;

	/** What {@link #lineNumbers} last built, or null where a token has moved since. */
	private int[] lineTable;

	TokenStream(List<Token> tokens) {
		this.tokens = new ArrayList<>(tokens);
	}

	int size() {
		return this.tokens.size();
	}

	Token get(int index) {
		return this.tokens.get(index);
	}

	/** Read only, so that a walk over the list cannot take a token out of it on the way. */
	@Override
	public Iterator<Token> iterator() {
		return Collections.unmodifiableList(this.tokens).iterator();
	}

	/** The text of every token, which is the body a header is written in front of. */
	String join() {
		return GlslLexer.join(this.tokens);
	}

	/**
	 * Marks a token as the name a naming directive gives, which is the one thing a pass may put on
	 * a token from outside. Everything else the passes leave behind is a rewrite of its text.
	 */
	void naming(int index) {
		this.tokens.set(index, this.tokens.get(index).naming());
	}

	/** One closing the wrap owes, put in once the scan that found it has finished reading. */
	record Closing(int at, String text, String directive) {
	}

	/**
	 * Inserting shifts every index after it, so the last insertion is made first.
	 * <p>
	 * This is the one place that moves a token, both passes that close a wrap coming through it,
	 * and so the one place that can make a position stale. What a later pass has to know about a
	 * token is carried on the token for that reason, as {@link Token#macroName()} is: a position
	 * kept across here would be read against somebody else's token, and nothing downstream can
	 * tell. The two answers this class keeps about the list, the function end and the line table,
	 * are thrown away here for the same reason.
	 */
	void insertClosings(List<Closing> closings) {
		if (closings.isEmpty()) {
			return;
		}

		this.functionEndFor = -1;
		this.lineTable = null;

		// One walk that copies the list with the closings dropped in, rather than one shift of
		// every later token per closing: a stage carries hundreds of thousands of tokens and a
		// few hundred closings, and the shifts were the whole cost of the pass. The order is the
		// one the shifts produced: closings sharing a position land latest first, because each
		// shift pushed the one inserted before it along.
		closings.sort(Comparator.comparingInt(Closing::at));
		List<Token> rebuilt = new ArrayList<>(this.tokens.size() + closings.size());
		int next = 0;
		for (int at = 0; at <= this.tokens.size(); at++) {
			int first = next;
			while (next < closings.size() && closings.get(next).at() == at) {
				next++;
			}

			for (int closing = next - 1; closing >= first; closing--) {
				Closing one = closings.get(closing);
				rebuilt.add(new Token(Kind.RAW, one.text(), one.directive()));
			}

			if (at < this.tokens.size()) {
				rebuilt.add(this.tokens.get(at));
			}
		}

		this.tokens.clear();
		this.tokens.addAll(rebuilt);
	}

	void replace(int index, String text) {
		this.functionEndFor = -1;
		this.tokens.set(index, this.tokens.get(index).as(text));
	}

	/** Replaces a token with text of our own, which no later pass will match again. */
	void inject(int index, String text) {
		this.functionEndFor = -1;
		this.tokens.set(index, new Token(Kind.RAW, text, this.tokens.get(index).directive()));
	}

	void blank(int index) {
		this.functionEndFor = -1;
		if (index >= 0) {
			this.tokens.set(index, Token.BLANK);
		}
	}

	/** Empties a range but keeps its line breaks, so error messages still point at the right line. */
	void blankRange(int start, int end) {
		this.functionEndFor = -1;
		for (int index = start; index <= end; index++) {
			Token token = this.tokens.get(index);
			if (token.kind() == Kind.NEWLINE) {
				continue;
			}

			// A comment or a spliced line carries its breaks inside one token, and they are kept
			// as well: the passes that ask which line a name is on take their numbers once and
			// read them after blanking, so a break lost here moves every name after it.
			long breaks = token.text().chars().filter(c -> c == '\n').count();
			this.tokens.set(index, breaks == 0
					? Token.BLANK
					: new Token(Kind.SPACE, "\n".repeat((int) breaks), token.directive()));
		}
	}

	void blankDirective(int hash) {
		this.functionEndFor = -1;
		for (int index = hash; index < this.tokens.size(); index++) {
			if (this.tokens.get(index).kind() == Kind.NEWLINE) {
				return;
			}

			this.tokens.set(index, Token.BLANK);
		}
	}

	/**
	 * The next token that is neither space nor comment. A line break ends the search inside a
	 * directive, since a directive is one line, and is stepped over everywhere else.
	 */
	int significantAfter(int index) {
		if (index < 0) {
			return -1;
		}

		boolean directive = this.tokens.get(index).directive() != null;
		for (int scan = index + 1; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.trivia()) {
				continue;
			}

			if (token.kind() == Kind.NEWLINE) {
				if (directive) {
					return -1;
				}

				continue;
			}

			return scan;
		}

		return -1;
	}

	/**
	 * The identifier a directive declares or tests, which is the second one on its line. The first
	 * is the directive keyword itself.
	 */
	int macroNameAfter(int hash) {
		boolean keywordSeen = false;

		for (int scan = hash + 1; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() == Kind.NEWLINE) {
				return -1;
			}

			if (token.trivia()) {
				continue;
			}

			if (!keywordSeen) {
				keywordSeen = true;
				continue;
			}

			return token.kind() == Kind.IDENTIFIER ? scan : -1;
		}

		return -1;
	}

	/** The previous token that is neither space, comment nor line break, staying out of directives. */
	int significantBefore(int index) {
		for (int scan = index - 1; scan >= 0; scan--) {
			Token token = this.tokens.get(scan);
			if (token.trivia() || token.kind() == Kind.NEWLINE) {
				continue;
			}

			return token.directive() == null ? scan : -1;
		}

		return -1;
	}

	/**
	 * Which line of the expanded unit each token sits on.
	 * <p>
	 * Kept between calls, which a stage makes twenty three times, and thrown away by
	 * {@link #insertClosings}, the one pass that moves a token. Nothing else can make it stale:
	 * every other method here leaves the line breaks where they were, which {@link #blankRange}
	 * goes out of its way to do. That is not this table's rule either, it is the unit's: a break
	 * lost anywhere would move every line after it away from the one the expander is asked about,
	 * and no pass would have a way to notice.
	 */
	int[] lineNumbers() {
		if (this.lineTable != null) {
			return this.lineTable;
		}

		int[] lines = new int[this.tokens.size()];
		int line = 0;

		for (int index = 0; index < this.tokens.size(); index++) {
			lines[index] = line;
			String text = this.tokens.get(index).text();
			for (int at = 0; at < text.length(); at++) {
				if (text.charAt(at) == '\n') {
					line++;
				}
			}
		}

		this.lineTable = lines;

		return lines;
	}

	/** The opening parenthesis of a call on the identifier at this index, or -1 if it is not one. */
	int callOpener(int index) {
		int next = significantAfter(index);

		return next >= 0 && this.tokens.get(next).operator("(") ? next : -1;
	}

	int matchingBracket(int open) {
		if (open < 0) {
			return -1;
		}

		String opening = this.tokens.get(open).text();
		String closing = switch (opening) {
			case "(" -> ")";
			case "{" -> "}";
			default -> "]";
		};
		int depth = 0;

		for (int scan = open; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() != Kind.OPERATOR) {
				continue;
			}

			if (token.operator(opening)) {
				depth++;
			} else if (token.operator(closing)) {
				depth--;
				if (depth == 0) {
					return scan;
				}
			}
		}

		return -1;
	}

	/**
	 * The last token of the function a parameter list opens, which is the brace that closes the
	 * body, or the parenthesis itself when the function is only declared.
	 */
	int functionEnd(int parameters) {
		// Asked once per parameter of a list and answered once per list: the walk to the
		// function's closing brace is the same for every parameter, and every helper that
		// touches a token forgets this first.
		if (parameters == this.functionEndFor) {
			return this.functionEndAt;
		}

		int end = walkFunctionEnd(parameters);
		this.functionEndFor = parameters;
		this.functionEndAt = end;

		return end;
	}

	private int walkFunctionEnd(int parameters) {
		int close = matchingBracket(parameters);
		int brace = close < 0 ? -1 : significantAfter(close);
		if (brace < 0 || !this.tokens.get(brace).operator("{")) {
			return close < 0 ? this.tokens.size() - 1 : close;
		}

		int end = matchingBracket(brace);

		return end < 0 ? this.tokens.size() - 1 : end;
	}

	/**
	 * Where the statement containing this token starts: just past whatever ended the last one.
	 * Returns -1 when no end to the previous statement is within reach, since blanking a range
	 * whose beginning was guessed would erase code that is none of our business.
	 */
	int statementStart(int index) {
		int limit = Math.max(0, index - MAX_STATEMENT_TOKENS);
		int start = -1;

		for (int scan = index - 1; scan >= limit; scan--) {
			Token token = this.tokens.get(scan);
			boolean boundary = token.kind() == Kind.HASH || token.directive() != null
					|| token.operator(";") || token.operator("{") || token.operator("}");
			if (boundary) {
				start = scan + 1;
				break;
			}
		}

		if (start < 0) {
			// Reaching the first token is a real answer; running out of budget is not.
			if (limit > 0) {
				return -1;
			}

			start = 0;
		}

		while (start < index) {
			Token token = this.tokens.get(start);
			if (!token.trivia() && token.kind() != Kind.NEWLINE) {
				break;
			}

			start++;
		}

		return start;
	}

	/** The semicolon closing this statement, or -1 if a brace opens first or none is found. */
	int statementEnd(int index) {
		int depth = 0;
		int last = Math.min(this.tokens.size(), index + MAX_STATEMENT_TOKENS);

		for (int scan = index; scan < last; scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() != Kind.OPERATOR || token.directive() != null) {
				continue;
			}

			String text = token.text();
			if (text.equals("(") || text.equals("[")) {
				depth++;
			} else if (text.equals(")") || text.equals("]")) {
				depth--;
			} else if (depth == 0 && text.equals("{")) {
				return -1;
			} else if (depth == 0 && text.equals(";")) {
				return scan;
			}
		}

		return -1;
	}

	List<Integer> significantRange(int start, int end) {
		List<Integer> found = new ArrayList<>();
		for (int scan = start; scan <= end; scan++) {
			Token token = this.tokens.get(scan);
			if (!token.trivia() && token.kind() != Kind.NEWLINE) {
				found.add(scan);
			}
		}

		return found;
	}
}
