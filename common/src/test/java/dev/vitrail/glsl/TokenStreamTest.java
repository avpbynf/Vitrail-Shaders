package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;
import dev.vitrail.glsl.TokenStream.Closing;
import dev.vitrail.glsl.TokenStream.Insertion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link TokenStream}, the substrate every translator pass reads and edits by index, to what
 * its javadoc promises: where the searches start and stop, which edits keep the two cached answers
 * and which drop them, and where a closing or an inserted token lands.
 * <p>
 * The insertion methods are also held against the one-shift-per-token version they replaced, kept
 * here as the reference, on seeded random input.
 */
class TokenStreamTest {

	private static TokenStream stream(String source) {
		return new TokenStream(GlslLexer.lex(source));
	}

	/** The index of the {@code nth} token whose text is this, counting from zero. */
	private static int at(TokenStream stream, String text, int nth) {
		int seen = 0;
		for (int index = 0; index < stream.size(); index++) {
			if (stream.get(index).text().equals(text) && seen++ == nth) {
				return index;
			}
		}

		throw new AssertionError("no token " + nth + " of " + text);
	}

	private static int at(TokenStream stream, String text) {
		return at(stream, text, 0);
	}

	@Test
	void keepsACopyOfTheListItWasGivenAndJoinsBackToTheText() {
		List<Token> tokens = new ArrayList<>(GlslLexer.lex("a + b;"));
		TokenStream stream = new TokenStream(tokens);
		tokens.clear();

		assertEquals(6, stream.size());
		assertEquals("a + b;", stream.join());
	}

	@Test
	void walksTheListButCannotTakeATokenOutOfItOnTheWay() {
		TokenStream stream = stream("a b");
		Iterator<Token> walk = stream.iterator();
		walk.next();
		assertThrows(UnsupportedOperationException.class, walk::remove);
		assertEquals(3, stream.size());
	}

	@Test
	void significantAfterStepsOverBlanksCommentsAndBreaksOutsideADirective() {
		TokenStream stream = stream("a /* c */ \n // d\n b");
		assertEquals(at(stream, "b"), stream.significantAfter(0));
		assertEquals(-1, stream.significantAfter(stream.size() - 1));
		assertEquals(-1, stream.significantAfter(-1));
	}

	@Test
	void significantAfterStopsAtTheEndOfADirectiveLine() {
		TokenStream stream = stream("#define A\nB");
		assertEquals(-1, stream.significantAfter(at(stream, "A")));
		// From a token outside a directive the break is stepped over.
		TokenStream code = stream("A\n#define B");
		assertEquals(at(code, "#"), code.significantAfter(0));

		TokenStream same = stream("#define A 1");
		assertEquals(at(same, "1"), same.significantAfter(at(same, "A")));
	}

	@Test
	void significantBeforeStepsOverBreaksAndRefusesToLandInADirective() {
		TokenStream stream = stream("a\n /* c */ b");
		assertEquals(0, stream.significantBefore(at(stream, "b")));
		assertEquals(-1, stream.significantBefore(0));

		TokenStream directive = stream("#define A\nB");
		assertEquals(-1, directive.significantBefore(at(directive, "B")));
	}

	@Test
	void macroNameAfterIsTheSecondWordOfTheLine() {
		TokenStream define = stream("#define FOO 1");
		assertEquals(at(define, "FOO"), define.macroNameAfter(0));

		TokenStream commented = stream("#ifdef  /* c */ BAR");
		assertEquals(at(commented, "BAR"), commented.macroNameAfter(0));

		TokenStream spliced = stream("#ifdef \\\n BAR");
		assertEquals(at(spliced, "BAR"), spliced.macroNameAfter(0));
	}

	@Test
	void macroNameAfterIsMinusOneWhereTheLineNamesNothing() {
		assertEquals(-1, stream("#endif").macroNameAfter(0));
		assertEquals(-1, stream("#").macroNameAfter(0));
		assertEquals(-1, stream("#define 123").macroNameAfter(0));
		assertEquals(-1, stream("#if (FOO)").macroNameAfter(0));
		assertEquals(-1, stream("#define\nFOO").macroNameAfter(0));
	}

	@Test
	void callOpenerSeesThroughBlanksAndBreaksButNotOutOfADirective() {
		TokenStream call = stream("texture (uv)");
		assertEquals(at(call, "("), call.callOpener(0));
		TokenStream broken = stream("texture\n(uv)");
		assertEquals(at(broken, "("), broken.callOpener(0));
		assertEquals(-1, stream("texture;").callOpener(0));
		assertEquals(-1, stream("texture").callOpener(0));

		TokenStream macro = stream("#define T texture\n(x)");
		assertEquals(-1, macro.callOpener(at(macro, "texture")));
	}

	@Test
	void matchingBracketPairsEachKindByDepth() {
		TokenStream round = stream("(a(b)c)");
		assertEquals(round.size() - 1, round.matchingBracket(0));
		assertEquals(at(round, ")"), round.matchingBracket(at(round, "(", 1)));

		TokenStream braces = stream("{ { } { } }");
		assertEquals(braces.size() - 1, braces.matchingBracket(0));

		TokenStream square = stream("a[b[1]]");
		assertEquals(square.size() - 1, square.matchingBracket(at(square, "[")));
	}

	@Test
	void matchingBracketIsMinusOneWhereNothingCloses() {
		assertEquals(-1, stream("(a(b)").matchingBracket(0));
		assertEquals(-1, stream("a").matchingBracket(-1));
		assertEquals(-1, stream("(").matchingBracket(0));
	}

	@Test
	void matchingBracketIgnoresBracketsInsideComments() {
		TokenStream stream = stream("(a /* ) */ b) // )\n");
		assertEquals(at(stream, ")"), stream.matchingBracket(0));
		assertEquals(6, at(stream, ")"));
	}

	@Test
	void functionEndIsTheBraceClosingTheBodyOrTheParenthesisOfAPrototype() {
		TokenStream definition = stream("void f(int a) { if (a) { g(); } }");
		assertEquals(definition.size() - 1, definition.functionEnd(at(definition, "(")));

		TokenStream prototype = stream("void f(int a); int x;");
		assertEquals(at(prototype, ")"), prototype.functionEnd(at(prototype, "(")));
	}

	@Test
	void functionEndIsTheLastTokenWhereABracketNeverCloses() {
		TokenStream openParenthesis = stream("void f(int a");
		assertEquals(openParenthesis.size() - 1, openParenthesis.functionEnd(at(openParenthesis, "(")));

		TokenStream openBrace = stream("void f() { g();");
		assertEquals(openBrace.size() - 1, openBrace.functionEnd(at(openBrace, "(")));
	}

	@Test
	void functionEndIsForgottenAsSoonAsATokenIsEdited() {
		TokenStream stream = stream("void f() { { } }");
		int parameters = at(stream, "(");
		assertEquals(stream.size() - 1, stream.functionEnd(parameters));

		// With the inner opening brace gone the body closes at the inner closing brace instead.
		stream.blank(at(stream, "{", 1));
		assertEquals(at(stream, "}", 0), stream.functionEnd(parameters));
	}

	@Test
	void lineNumbersCountTheBreaksInsideOneToken() {
		TokenStream stream = stream("a\nb /* x\ny */ c\\\nd\r\ne");
		int[] lines = stream.lineNumbers();

		assertEquals(0, lines[at(stream, "a")]);
		assertEquals(1, lines[at(stream, "b")]);
		// The comment starts on the second line; what follows it is on the third.
		assertEquals(1, lines[at(stream, "/* x\ny */")]);
		assertEquals(2, lines[at(stream, "c")]);
		assertEquals(2, lines[at(stream, "\\\n")]);
		assertEquals(3, lines[at(stream, "d")]);
		assertEquals(4, lines[at(stream, "e")]);
		assertEquals(stream.size(), lines.length);
	}

	/**
	 * The table counts LF and nothing else: a CRLF is one line and a lone CR is none, though the lexer
	 * ends a line at either. Harmless where every unit is joined with LF before it is read, which is
	 * how the translator gets its text; it would be one line off for text that was not.
	 */
	@Test
	void lineNumbersCountLineFeedsOnlyWhereALoneCarriageReturnEndsTheLexersLine() {
		TokenStream crlf = stream("a\r\nb");
		assertEquals(1, crlf.lineNumbers()[at(crlf, "b")]);

		TokenStream lone = stream("a\rb");
		assertEquals(0, lone.lineNumbers()[at(lone, "b")]);
	}

	@Test
	void lineNumbersAreKeptAcrossAnEditThatLeavesTheBreaksWhereTheyWere() {
		TokenStream stream = stream("a\nb /* x\ny */ c");
		int[] before = stream.lineNumbers();
		assertSame(before, stream.lineNumbers());

		stream.replace(at(stream, "a"), "renamed");
		stream.inject(at(stream, "b"), "injected");
		stream.blank(at(stream, "c"));
		stream.blankRange(at(stream, "/* x\ny */"), at(stream, "/* x\ny */"));

		assertSame(before, stream.lineNumbers());
	}

	@Test
	void lineNumbersAreRebuiltWhenATokenLosesItsBreak() {
		TokenStream stream = stream("a\nb");
		int[] before = stream.lineNumbers();
		assertEquals(1, before[2]);

		stream.replace(1, "");
		int[] after = stream.lineNumbers();

		assertNotSame(before, after);
		assertEquals(0, after[2]);
	}

	@Test
	void lineNumbersAreRebuiltWhenATokenIsInserted() {
		TokenStream stream = stream("a\nb");
		int[] before = stream.lineNumbers();
		stream.insertClosings(new ArrayList<>(List.of(new Closing(1, ")", null))));

		int[] after = stream.lineNumbers();
		assertNotSame(before, after);
		assertEquals(4, after.length);

		int[] second = stream.lineNumbers();
		stream.insertTokens(List.of(new Insertion(0, new Token(Kind.IDENTIFIER, "x", null))));
		assertNotSame(second, stream.lineNumbers());
		assertEquals(5, stream.lineNumbers().length);
	}

	@Test
	void anEmptyInsertionChangesNothingAndKeepsTheTable() {
		TokenStream stream = stream("a\nb");
		int[] before = stream.lineNumbers();
		stream.insertClosings(new ArrayList<>());
		stream.insertTokens(List.of());

		assertSame(before, stream.lineNumbers());
		assertEquals("a\nb", stream.join());
	}

	@Test
	void replaceKeepsTheKindAndTheDirectiveWhereInjectMakesTheTokenRaw() {
		TokenStream stream = stream("#define A B\nC");
		int b = at(stream, "B");
		stream.replace(b, "Z");
		assertEquals(new Token(Kind.IDENTIFIER, "Z", "define", false, false), stream.get(b));

		stream.inject(b, "raw(");
		assertEquals(new Token(Kind.RAW, "raw(", "define", false, false), stream.get(b));
		assertFalse(stream.get(b).identifier("raw("));
	}

	@Test
	void namingRidesOnTheTokenAcrossAnInsertionBeforeIt() {
		TokenStream stream = stream("#define A 1\n#define B 2");
		int b = at(stream, "B");
		stream.naming(b);

		stream.insertTokens(List.of(new Insertion(0, new Token(Kind.IDENTIFIER, "x", null))));
		stream.insertClosings(new ArrayList<>(List.of(new Closing(3, ")", null))));

		assertTrue(stream.get(at(stream, "B")).macroName());
		assertFalse(stream.get(at(stream, "A")).macroName());
	}

	@Test
	void blankLeavesNothingAndIgnoresAMissingIndex() {
		TokenStream stream = stream("a b");
		stream.blank(-1);
		assertEquals("a b", stream.join());
		stream.blank(0);
		assertEquals(" b", stream.join());
		assertSame(Token.BLANK, stream.get(0));
	}

	@Test
	void blankRangeEmptiesEveryTokenButKeepsEveryLineBreakItPassesOver() {
		TokenStream stream = stream("x a /* p\nq */ b\nc d");
		stream.blankRange(at(stream, "a"), at(stream, "d"));

		// The blank before a stays; the comment's one break and the line's own remain, and nothing else.
		assertEquals("x \n\n", stream.join());
	}

	@Test
	void blankDirectiveStopsAtTheBreakEndingTheLineAndKeepsSplicedBreaks() {
		TokenStream spliced = stream("#define A 1 \\\n + 2\nB");
		spliced.blankDirective(0);
		assertEquals("\n\nB", spliced.join());

		TokenStream commented = stream("#define A /* a\nb */ 1\nB");
		commented.blankDirective(0);
		assertEquals("\n\nB", commented.join());

		TokenStream open = stream("x\n#define A 1");
		open.blankDirective(at(open, "#"));
		assertEquals("x\n", open.join());
	}

	@Test
	void blankingDoesNotMoveALaterNameOffItsLine() {
		TokenStream stream = stream("#define A /* a\nb */ 1\nB");
		int[] before = stream.lineNumbers().clone();
		stream.blankDirective(0);

		assertArrayEquals(before, stream.lineNumbers());
		assertEquals(2, stream.lineNumbers()[at(stream, "B")]);
	}

	@Test
	void aClosingLandsInFrontOfTheTokenAtItsIndexAndOneAtTheEndAppends() {
		TokenStream stream = stream("a b");
		stream.insertClosings(new ArrayList<>(List.of(new Closing(2, ")", null), new Closing(3, "}", null))));

		assertEquals("a )b}", stream.join());
		assertEquals(new Token(Kind.RAW, ")", null), stream.get(2));
	}

	@Test
	void closingsSharingAPositionLandLatestFirstWhereInsertedTokensKeepTheirOrder() {
		TokenStream closed = stream("a b");
		closed.insertClosings(new ArrayList<>(List.of(new Closing(2, "1", null), new Closing(2, "2", null),
				new Closing(2, "3", null))));
		assertEquals("a 321b", closed.join());

		TokenStream inserted = stream("a b");
		inserted.insertTokens(List.of(new Insertion(2, new Token(Kind.IDENTIFIER, "x", null)),
				new Insertion(2, new Token(Kind.IDENTIFIER, "y", null)),
				new Insertion(0, new Token(Kind.IDENTIFIER, "z", null))));
		assertEquals("za xyb", inserted.join());
	}

	@Test
	void aClosingCarriesTheDirectiveItWasGivenAndIsRawText() {
		TokenStream stream = stream("#define A (x\nB");
		int newline = at(stream, "\n");
		stream.insertClosings(new ArrayList<>(List.of(new Closing(newline, ")", "define"))));

		Token closing = stream.get(newline);
		assertEquals(Kind.RAW, closing.kind());
		assertEquals("define", closing.directive());
		assertFalse(closing.operator(")"));
		assertEquals("#define A (x)\nB", stream.join());
	}

	@Test
	void insertTokensKeepsTheKindsItIsGivenAndDoesNotReorderTheCallersList() {
		TokenStream stream = stream("a b");
		List<Insertion> given = new ArrayList<>(List.of(new Insertion(2, new Token(Kind.IDENTIFIER, "late", null)),
				new Insertion(0, new Token(Kind.IDENTIFIER, "early", null))));
		List<Insertion> copy = List.copyOf(given);
		stream.insertTokens(given);

		assertEquals(copy, given);
		assertEquals(Kind.IDENTIFIER, stream.get(0).kind());
		assertTrue(stream.get(0).identifier("early"));
	}

	/** The way both insertions were made before the one walk: each into the list at its index, last first. */
	private static List<Token> oneShiftPerInsertion(List<Token> tokens, List<Insertion> insertions) {
		List<Token> list = new ArrayList<>(tokens);
		List<Insertion> ordered = new ArrayList<>(insertions);
		ordered.sort(Comparator.comparingInt(Insertion::at).reversed());
		// A stable descending sort keeps the given order among equals, and each of those is put in
		// front of the one before it: the reference for closings, which land latest first.
		for (Insertion insertion : ordered) {
			list.add(insertion.at(), insertion.token());
		}

		return list;
	}

	@Test
	void insertsClosingsAsOneShiftPerClosingWouldOnRandomLists() {
		Random random = new Random(0xC105);
		String[] words = {"a", " ", "(", ")", "\n", "b", "#define", "{"};

		for (int round = 0; round < 2_000; round++) {
			StringBuilder text = new StringBuilder();
			for (int part = random.nextInt(30); part >= 0; part--) {
				text.append(words[random.nextInt(words.length)]);
			}

			List<Token> tokens = GlslLexer.lex(text.toString());
			List<Closing> closings = new ArrayList<>();
			List<Insertion> reference = new ArrayList<>();
			for (int count = random.nextInt(12); count > 0; count--) {
				int at = random.nextInt(tokens.size() + 1);
				String closer = new String[] {")", "}", "]"}[random.nextInt(3)];
				String directive = random.nextBoolean() ? null : "define";
				closings.add(new Closing(at, closer, directive));
				reference.add(new Insertion(at, new Token(Kind.RAW, closer, directive)));
			}

			TokenStream stream = new TokenStream(tokens);
			stream.insertClosings(new ArrayList<>(closings));

			List<Token> expected = oneShiftPerInsertion(tokens, reference);
			List<Token> actual = new ArrayList<>();
			stream.forEach(actual::add);
			assertEquals(expected, actual, "round " + round + " over " + text);
		}
	}

	@Test
	void insertsTokensInTheGivenOrderAsOneShiftPerTokenWouldOnRandomLists() {
		Random random = new Random(0x1257);
		String[] words = {"a", " ", "(", ")", "\n", "b", "#if", "}"};

		for (int round = 0; round < 2_000; round++) {
			StringBuilder text = new StringBuilder();
			for (int part = random.nextInt(30); part >= 0; part--) {
				text.append(words[random.nextInt(words.length)]);
			}

			List<Token> tokens = GlslLexer.lex(text.toString());
			List<Insertion> insertions = new ArrayList<>();
			for (int count = random.nextInt(12); count > 0; count--) {
				insertions.add(new Insertion(random.nextInt(tokens.size() + 1),
						new Token(Kind.IDENTIFIER, "n" + count, null)));
			}

			TokenStream stream = new TokenStream(tokens);
			stream.insertTokens(insertions);

			// The one-shift reference puts the later of two equal positions in front of the earlier,
			// so its answer is read with the equal positions handed over in the opposite order.
			List<Insertion> flipped = new ArrayList<>(insertions);
			Collections.reverse(flipped);
			List<Token> expected = oneShiftPerInsertion(tokens, flipped);
			List<Token> actual = new ArrayList<>();
			stream.forEach(actual::add);
			assertEquals(expected, actual, "round " + round + " over " + text);
		}
	}

	@Test
	void statementStartIsJustPastTheLastBoundaryAndSkipsTheBlanksAfterIt() {
		TokenStream stream = stream("int a = 1; float b = f(x, y);");
		assertEquals(at(stream, "float"), stream.statementStart(at(stream, "b")));

		TokenStream braces = stream("void f() {\n\tint a;\n}\n int b;");
		assertEquals(at(braces, "int", 1), braces.statementStart(at(braces, "b")));

		TokenStream first = stream("  a b;");
		assertEquals(at(first, "a"), first.statementStart(at(first, "b")));
	}

	@Test
	void statementStartTreatsAnyTokenOfADirectiveAsABoundary() {
		TokenStream stream = stream("#define X 1\nfloat b;");
		assertEquals(at(stream, "float"), stream.statementStart(at(stream, "b")));
	}

	@Test
	void statementStartGivesUpFourThousandNinetySixTokensBackWithoutABoundary() {
		TokenStream stream = stream("a ".repeat(3000));

		// Reaching the first token is an answer; running out of reach first is not.
		assertEquals(0, stream.statementStart(4096));
		assertEquals(-1, stream.statementStart(4097));
		assertEquals(-1, stream.statementStart(5000));
	}

	@Test
	void statementEndIsTheSemicolonAtDepthZero() {
		TokenStream nested = stream("f(a, (b; c)); x");
		assertEquals(at(nested, ";", 1), nested.statementEnd(0));

		TokenStream loop = stream("for (int i = 0; i < 3; i++) x;");
		assertEquals(at(loop, ";", 2), loop.statementEnd(0));

		TokenStream index = stream("a[b; c] d;");
		assertEquals(at(index, ";", 1), index.statementEnd(0));
	}

	@Test
	void statementEndIsMinusOneWhereABraceOpensFirstOrNothingEnds() {
		TokenStream block = stream("if (a) { b; }");
		assertEquals(-1, block.statementEnd(0));
		assertEquals(-1, stream("int a = 1").statementEnd(0));
		assertEquals(-1, stream("x").statementEnd(1));
	}

	@Test
	void statementEndWalksPastTheSemicolonsOfDirectiveLines() {
		TokenStream stream = stream("#define A x;\n y;");
		assertEquals(at(stream, ";", 1), stream.statementEnd(at(stream, "x")));
	}

	@Test
	void statementEndReachesFourThousandNinetySixTokensAheadAndNoFurther() {
		TokenStream near = stream("x ".repeat(2047) + "x;");
		assertEquals(4095, near.statementEnd(0));
		assertEquals(4095, near.size() - 1);

		TokenStream far = stream("x ".repeat(2048) + ";");
		assertEquals(4096, far.size() - 1);
		assertEquals(-1, far.statementEnd(0));
	}

	/**
	 * A closing bracket the walk never opened ends it with no end, which is what a name inside a
	 * parameter list or an argument meets. Past that bracket no semicolon stands at depth nought
	 * until an opening one brings the depth back, and the first semicolon after that belongs to
	 * another statement: in a function, the header of a loop in its body.
	 */
	@Test
	void statementEndStopsAtAClosingBracketItNeverOpened() {
		assertEquals(-1, stream("a ) ; b ;").statementEnd(0));
		assertEquals(-1, stream("a ) " + "x ; ".repeat(5000)).statementEnd(0));
		assertEquals(-1, stream("a ] ( ; b ;").statementEnd(0));

		TokenStream parameter = stream("void f(vec3 x) { for (int i = 0; i < 4; i++) {} }");
		assertEquals(-1, parameter.statementEnd(at(parameter, "x")));
	}

	@Test
	void significantRangeIsInclusiveAndLeavesOutBlanksCommentsAndBreaks() {
		TokenStream stream = stream("a /*c*/ b\n c");
		assertEquals(List.of(0, at(stream, "b"), at(stream, "c")), stream.significantRange(0, stream.size() - 1));
		assertEquals(List.of(at(stream, "b")), stream.significantRange(at(stream, "b"), at(stream, "b")));
		assertEquals(List.of(), stream.significantRange(1, 1));
	}
}
