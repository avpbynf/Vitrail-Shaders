package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link GlslLexer} to what the GLSL specification says a token is, at the edges where a pack
 * writes something the translator would misread if the lexer did.
 * <p>
 * The expected values are worked out from the language rules and written out by hand: the class
 * contract is that the tokens join back into the exact text, and everything else is where a token
 * ends and which preprocessor line it belongs to. The two places the lexer is known to differ from
 * the specification are named as such in their tests, so that a change to either is a decision and
 * not an accident.
 */
class GlslLexerTest {

	/** One token as {@code KIND[text]}, which is short enough to read a whole stream at a glance. */
	private static String show(Token token) {
		return token.kind().name().substring(0, 2) + "[" + token.text() + "]";
	}

	private static List<String> lex(String source) {
		List<String> shown = new ArrayList<>();
		for (Token token : GlslLexer.lex(source)) {
			shown.add(show(token));
		}

		return shown;
	}

	/** The directive of every token, {@code -} where there is none. */
	private static List<String> directives(String source) {
		List<String> found = new ArrayList<>();
		for (Token token : GlslLexer.lex(source)) {
			found.add(token.directive() == null ? "-" : token.directive());
		}

		return found;
	}

	@Test
	void anEmptySourceHasNoTokens() {
		assertEquals(List.of(), GlslLexer.lex(""));
		assertEquals("", GlslLexer.join(List.of()));
	}

	@Test
	void splitsAFunctionHeaderIntoWordsAndSingleCharacterOperators() {
		assertEquals(List.of("ID[void]", "SP[ ]", "ID[main]", "OP[(]", "OP[)]", "SP[ ]", "OP[{]", "OP[}]"),
				lex("void main() {}"));
	}

	@Test
	void everyOperatorIsOneCharacterEvenWhereTheLanguageMakesItTwo() {
		assertEquals(List.of("ID[a]", "OP[+]", "OP[=]", "ID[b]", "OP[<]", "OP[<]", "OP[=]", "OP[=]",
				"OP[=]", "OP[-]", "OP[>]"), lex("a+=b<<===->"));
	}

	@Test
	void identifiersAreAsciiOnlyAndTakeDigitsAndUnderscores() {
		assertEquals(List.of("ID[_a1]", "SP[ ]", "ID[gl_FragData]", "OP[[]", "NU[0]", "OP[]]"),
				lex("_a1 gl_FragData[0]"));
		// A letter outside ASCII is an operator of its own and the identifier ends before it.
		assertEquals(List.of("ID[a]", "OP[\u00e9]", "ID[b]"), lex("a\u00e9b"));
	}

	@Test
	void aLetterOnlyEndsAnIdentifierNeverStartsANumberFromIt() {
		// x1e is one name, so the minus after it is a minus and not the sign of an exponent.
		assertEquals(List.of("ID[x1e]", "OP[-]", "NU[3]"), lex("x1e-3"));
	}

	@Test
	void numbersTakeTheirExponentSignAndSuffixes() {
		assertEquals(List.of("NU[1e-3]"), lex("1e-3"));
		assertEquals(List.of("NU[1E+3]"), lex("1E+3"));
		assertEquals(List.of("NU[.5]"), lex(".5"));
		assertEquals(List.of("NU[0x1F]"), lex("0x1F"));
		assertEquals(List.of("NU[1.0f]"), lex("1.0f"));
		assertEquals(List.of("NU[1.]"), lex("1."));
		assertEquals(List.of("NU[1.e-3]"), lex("1.e-3"));
		assertEquals(List.of("NU[0xFFu]"), lex("0xFFu"));
		assertEquals(List.of("NU[2.0lf]"), lex("2.0lf"));
	}

	@Test
	void aSignAfterADigitIsAnOperator() {
		assertEquals(List.of("NU[3]", "OP[-]", "NU[2]"), lex("3-2"));
		assertEquals(List.of("NU[1e-3]", "OP[-]", "NU[2]"), lex("1e-3-2"));
		assertEquals(List.of("NU[1.0]", "OP[+]", "NU[.5]"), lex("1.0+.5"));
	}

	@Test
	void aDotBetweenNamesIsASwizzleAndNotANumber() {
		assertEquals(List.of("ID[v]", "OP[.]", "ID[xyz]", "OP[.]", "ID[x]"), lex("v.xyz.x"));
		assertEquals(List.of("OP[.]", "OP[.]"), lex(".."));
		assertEquals(List.of("ID[a]", "OP[.]", "SP[ ]", "ID[b]"), lex("a. b"));
	}

	/**
	 * KNOWN DEVIATION from the specification, pinned as it is. {@code E} is a hexadecimal digit, so
	 * GLSL ends {@code 0xE+1} after the {@code E} and reads a sum; the lexer takes the {@code +} for
	 * the sign of an exponent, as it does after a decimal {@code e}. No effect is known on a pack:
	 * nothing the translator does asks what a NUMBER token holds.
	 */
	@Test
	void aHexLiteralEndingInEAbsorbsTheSignAfterItWhereTheLanguageWouldNot() {
		assertEquals(List.of("NU[0xE+1]"), lex("0xE+1"));
		assertEquals(List.of("NU[0xF]", "OP[+]", "NU[1]"), lex("0xF+1"));
	}

	@Test
	void aLineCommentStopsBeforeItsBreak() {
		assertEquals(List.of("ID[a]", "SP[ ]", "CO[// b /* c]", "NE[\n]", "ID[d]"), lex("a // b /* c\nd"));
	}

	@Test
	void aBlockCommentIsOneTokenAcrossLines() {
		assertEquals(List.of("ID[a]", "SP[ ]", "CO[/* x\ny // z\n*/]", "SP[ ]", "ID[b]"),
				lex("a /* x\ny // z\n*/ b"));
	}

	@Test
	void anUnterminatedBlockCommentRunsToTheEndOfTheText() {
		assertEquals(List.of("ID[a]", "SP[ ]", "CO[/* x\ny]"), lex("a /* x\ny"));
		assertEquals(List.of("CO[/*]"), lex("/*"));
		assertEquals(List.of("CO[/*/]"), lex("/*/"));
	}

	@Test
	void theSlashOfTheOpeningDoesNotCloseTheComment() {
		assertEquals(List.of("CO[/*/ x */]", "SP[ ]", "ID[y]"), lex("/*/ x */ y"));
	}

	@Test
	void aLoneCloserIsTwoOperatorsAndADivisionIsNotAComment() {
		assertEquals(List.of("OP[*]", "OP[/]"), lex("*/"));
		assertEquals(List.of("ID[a]", "OP[/]", "ID[b]"), lex("a/b"));
		assertEquals(List.of("ID[a]", "OP[/]", "SP[ ]", "OP[*]", "ID[b]"), lex("a/ *b"));
		assertEquals(List.of("OP[/]"), lex("/"));
	}

	@Test
	void aBlockCommentOpenedInsideALineCommentDoesNotOutliveIt() {
		assertEquals(List.of("CO[// /*]", "NE[\n]", "ID[x]", "SP[ ]", "CO[/* y */]"), lex("// /*\nx /* y */"));
	}

	@Test
	void anyBreakEndsALineAndCrLfIsOne() {
		assertEquals(List.of("ID[a]", "NE[\r\n]", "ID[b]"), lex("a\r\nb"));
		assertEquals(List.of("ID[a]", "NE[\r]", "ID[b]"), lex("a\rb"));
		assertEquals(List.of("ID[a]", "NE[\n]", "NE[\r]", "ID[b]"), lex("a\n\rb"));
		assertEquals(List.of("NE[\r]", "NE[\r\n]"), lex("\r\r\n"));
		assertEquals(List.of("NE[\n]", "NE[\n]"), lex("\n\n"));
	}

	@Test
	void aLineCommentEndsAtACarriageReturnToo() {
		assertEquals(List.of("CO[// a]", "NE[\r]", "ID[b]"), lex("// a\rb"));
		assertEquals(List.of("CO[// a]", "NE[\r\n]", "ID[b]"), lex("// a\r\nb"));
	}

	@Test
	void blankRunsMergeIntoOneTokenOfAllFourKinds() {
		assertEquals(List.of("ID[a]", "SP[ \t\f\u000b ]", "ID[b]"), lex("a \t\f\u000b b"));
	}

	@Test
	void spacesOutsideTheFourBlankCharactersAreOperators() {
		assertEquals(List.of("ID[a]", "OP[\u00a0]", "ID[b]"), lex("a\u00a0b"));
		assertEquals(List.of("OP[\0]"), lex("\0"));
	}

	@Test
	void aCharacterOutsideTheBasicPlaneIsTwoOperatorsOneToAUnitOfUtf16() {
		String face = "\uD83D\uDE00";
		assertEquals(List.of("OP[\uD83D]", "OP[\uDE00]"), lex(face));
		assertEquals(face, GlslLexer.join(GlslLexer.lex(face)));
	}

	@Test
	void aBackslashBeforeABreakJoinsTheLinesAndIsOneBlankToken() {
		assertEquals(List.of("ID[a]", "SP[ ]", "SP[\\\n]", "ID[b]"), lex("a \\\nb"));
		assertEquals(List.of("ID[a]", "SP[\\\r\n]", "ID[b]"), lex("a\\\r\nb"));
		assertEquals(List.of("ID[a]", "SP[\\\r]", "ID[b]"), lex("a\\\rb"));
	}

	@Test
	void aBackslashThatIsNotRightBeforeABreakIsAnOperator() {
		assertEquals(List.of("OP[\\]"), lex("\\"));
		assertEquals(List.of("ID[a]", "OP[\\]", "SP[ ]", "NE[\n]", "ID[b]"), lex("a\\ \nb"));
		assertEquals(List.of("OP[\\]", "ID[n]"), lex("\\n"));
	}

	/**
	 * KNOWN DEVIATION from the specification, pinned as it is. A backslash before the break splices
	 * the next line into a {@code //} comment in GLSL, and the lexer ends the comment at the break
	 * whatever came before it, so the line after is read as code. Only a pack with a trailing
	 * backslash in a comment meets it, and what is read as code there is text the compiler drops.
	 */
	@Test
	void aLineCommentEndedByABackslashDoesNotTakeTheNextLineWhereTheLanguageWould() {
		assertEquals(List.of("CO[// a \\]", "NE[\n]", "ID[b]"), lex("// a \\\nb"));
	}

	@Test
	void aDirectiveKeepsItsNameOnEveryTokenOfTheLine() {
		assertEquals(
				List.of("HA[#]", "ID[define]", "SP[ ]", "ID[X]", "SP[ ]", "NU[1]", "NE[\n]", "ID[float]", "SP[ ]",
						"ID[y]", "OP[;]"),
				lex("#define X 1\nfloat y;"));
		assertEquals(List.of("define", "define", "define", "define", "define", "define", "-", "-", "-", "-",
				"-"), directives("#define X 1\nfloat y;"));
	}

	@Test
	void aHashAfterBlanksOrCommentsStillOpensADirective() {
		assertEquals(List.of("-", "version", "version", "version", "version", "version"),
				directives("  #  version 450"));
		assertEquals(List.of("-", "-", "define", "define", "define", "define"),
				directives("/* c */ #define A"));
		assertEquals(List.of("-", "-", "define", "define", "define", "define"),
				directives("/* a\nb */ #define A"));
		assertEquals("if", GlslLexer.lex("# /* c */ if X").getFirst().directive());
	}

	@Test
	void aHashAfterCodeIsAnOperator() {
		assertEquals(List.of("ID[a]", "SP[ ]", "OP[#]", "SP[ ]", "ID[b]"), lex("a # b"));
		assertEquals(List.of("-", "-", "-", "-", "-"), directives("a # b"));
		assertEquals(List.of("ID[x]", "SP[ ]", "CO[/* c */]", "SP[ ]", "OP[#]", "ID[define]"),
				lex("x /* c */ #define"));
	}

	@Test
	void aHashWithNoWordAfterItGivesAnEmptyDirective() {
		assertEquals(List.of("HA[#]", "NE[\n]"), lex("#\n"));
		assertEquals("", GlslLexer.lex("#\n").getFirst().directive());
		assertEquals("", GlslLexer.lex("#").getFirst().directive());
		assertEquals("", GlslLexer.lex("#123 x").getFirst().directive());
		assertEquals("", GlslLexer.lex("# (x)").getFirst().directive());
	}

	/** The tokens that are neither blank nor a break, as {@code text@directive}. */
	private static List<String> significant(String source) {
		List<String> found = new ArrayList<>();
		for (Token token : GlslLexer.lex(source)) {
			if (token.kind() != Kind.SPACE && token.kind() != Kind.NEWLINE) {
				found.add(token.text() + "@" + (token.directive() == null ? "-" : token.directive()));
			}
		}

		return found;
	}

	@Test
	void theBreakEndingADirectiveBelongsToNoDirective() {
		List<Token> tokens = GlslLexer.lex("#if 1\n");
		assertEquals(Kind.NEWLINE, tokens.getLast().kind());
		assertNull(tokens.getLast().directive());
		assertEquals(List.of("if", "if", "if", "if", "-", "else", "else", "-", "endif", "endif", "-"),
				directives("#if 1\n#else\n#endif\n"));
	}

	@Test
	void aBackslashBreakKeepsTheDirectiveGoingOnTheNextLine() {
		assertEquals(List.of("#@define", "define@define", "A@define", "1@define", "+@define", "2@define", "B@-"),
				significant("#define A 1 \\\n + 2\nB"));
		assertEquals(List.of("#@define", "define@define", "A@define", "B@-"),
				significant("#define A \\\r\n\nB"));
		// The hash and its keyword may be split by a splice: the two lines are one logical line.
		assertEquals("define", GlslLexer.lex("#\\\ndefine X 1").getFirst().directive());
	}

	@Test
	void aBackslashWithBlanksBeforeTheBreakDoesNotContinueTheDirective() {
		assertEquals(List.of("#@define", "define@define", "X@define", "\\@define", "Y@-"),
				significant("#define X \\ \nY"));
	}

	@Test
	void aBlockCommentWithABreakInsideDoesNotEndTheDirective() {
		// The comment is one blank to the preprocessor, so 1 is still on the define line.
		List<Token> tokens = GlslLexer.lex("#define A /* a\nb */ 1\nB");
		Token one = tokens.stream().filter(t -> t.text().equals("1")).findFirst().orElseThrow();
		Token b = tokens.getLast();
		assertEquals("define", one.directive());
		assertNull(b.directive());
		assertEquals("B", b.text());
	}

	@Test
	void aTrailingLineCommentIsPartOfTheDirectiveLine() {
		List<Token> tokens = GlslLexer.lex("#define A // note\nB");
		Token comment = tokens.stream().filter(t -> t.kind() == Kind.COMMENT).findFirst().orElseThrow();
		assertEquals("define", comment.directive());
	}

	@Test
	void aPastedHashInAMacroBodyIsNotADirectiveOpener() {
		assertEquals(List.of("HA[#]", "ID[define]", "SP[ ]", "ID[CAT]", "OP[(]", "ID[a]", "OP[,]", "ID[b]",
				"OP[)]", "SP[ ]", "ID[a]", "OP[#]", "OP[#]", "ID[b]"), lex("#define CAT(a,b) a##b"));
	}

	@Test
	void aLineOfNothingButTwoHashesGivesAnEmptyDirectiveAndAnOperator() {
		assertEquals(List.of("HA[#]", "OP[#]"), lex("##"));
		assertEquals(List.of("", ""), directives("##"));
	}

	@Test
	void tokensOfPlainCodeAreNeverMarkedAsNamingAMacro() {
		for (Token token : GlslLexer.lex("#define A B\n#ifdef A\nA;\n#endif")) {
			assertFalse(token.macroName(), token.text());
		}
	}

	@Test
	void theTokenHelpersAnswerForTheirKindOnly() {
		Token word = new Token(Kind.IDENTIFIER, "texture", null);
		Token symbol = new Token(Kind.OPERATOR, "(", null);
		assertTrue(word.identifier("texture"));
		assertFalse(word.identifier("Texture"));
		assertFalse(word.operator("texture"));
		assertTrue(symbol.operator("("));
		assertFalse(symbol.identifier("("));
		assertTrue(new Token(Kind.SPACE, " ", null).trivia());
		assertTrue(new Token(Kind.COMMENT, "//", null).trivia());
		assertFalse(new Token(Kind.NEWLINE, "\n", null).trivia());
		assertFalse(new Token(Kind.RAW, "x", null).trivia());
		assertFalse(new Token(Kind.RAW, "texture", null).identifier("texture"));
	}

	@Test
	void asKeepsEverythingButTheTextAndNamingKeepsEverythingButTheFlag() {
		Token name = new Token(Kind.IDENTIFIER, "A", "define");
		Token renamed = name.as("B");
		assertEquals(new Token(Kind.IDENTIFIER, "B", "define", false, false), renamed);
		Token naming = name.naming();
		assertTrue(naming.macroName());
		assertEquals("A", naming.text());
		assertEquals("define", naming.directive());
		assertTrue(naming.as("C").macroName());
		Token parameter = name.parameter();
		assertTrue(parameter.macroParameter());
		assertTrue(parameter.as("C").macroParameter());
		assertTrue(parameter.naming().macroParameter());
		assertEquals("", Token.BLANK.text());
		assertSame(Kind.SPACE, Token.BLANK.kind());
	}

	@Test
	void joinsAnyStreamBackToTheExactTextItCameFrom() {
		String[] sources = {
			"",
			"#version 450 core\r\n#define X 1 \\\r\n  + 2\r\nvoid main() { gl_FragData[0] = vec4(1.0e-3, .5, 0x1F); }\r\n",
			"/* unterminated",
			"// only a comment",
			"a\\",
			"\u00e9\u00e8\uD83D\uDE00 \0 \\ \f\u000b",
			"#\n##\n# #\n#123\n",
		};
		for (String source : sources) {
			assertEquals(source, GlslLexer.join(GlslLexer.lex(source)));
		}
	}

	/** A characters-and-directives reading of the same text, that shares no code with the lexer. */
	private static List<String> expectedDirectives(String text) {
		// One entry per character: the directive the character sits on, or null. Worked out by a
		// state machine over characters, where the lexer works over tokens.
		String[] onLine = new String[text.length()];
		boolean lineStart = true;
		int at = 0;
		while (at < text.length()) {
			char c = text.charAt(at);
			if (c == '\n' || c == '\r') {
				at++;
				if (c == '\r' && at < text.length() && text.charAt(at) == '\n') {
					at++;
				}

				lineStart = true;
				continue;
			}

			if (c == '\\' && at + 1 < text.length() && (text.charAt(at + 1) == '\n' || text.charAt(at + 1) == '\r')) {
				at += 2;
				if (text.charAt(at - 1) == '\r' && at < text.length() && text.charAt(at) == '\n') {
					at++;
				}

				continue;
			}

			if (c == ' ' || c == '\t' || c == '\f' || c == 0x0B) {
				at++;
				continue;
			}

			if (text.startsWith("//", at)) {
				while (at < text.length() && text.charAt(at) != '\n' && text.charAt(at) != '\r') {
					at++;
				}

				continue;
			}

			if (text.startsWith("/*", at)) {
				int close = text.indexOf("*/", at + 2);
				at = close < 0 ? text.length() : close + 2;
				continue;
			}

			if (c != '#' || !lineStart) {
				lineStart = false;
				at++;
				continue;
			}

			// A directive: name it, then mark every character up to the end of the logical line.
			int name = at + 1;
			while (name < text.length()) {
				char n = text.charAt(name);
				if (n == ' ' || n == '\t' || n == '\f' || n == 0x0B) {
					name++;
				} else if (n == '\\' && name + 1 < text.length()
						&& (text.charAt(name + 1) == '\n' || text.charAt(name + 1) == '\r')) {
					name += (text.charAt(name + 1) == '\r' && name + 2 < text.length() && text.charAt(name + 2) == '\n')
							? 3 : 2;
				} else if (text.startsWith("//", name)) {
					while (name < text.length() && text.charAt(name) != '\n' && text.charAt(name) != '\r') {
						name++;
					}
				} else if (text.startsWith("/*", name)) {
					int close = text.indexOf("*/", name + 2);
					name = close < 0 ? text.length() : close + 2;
				} else {
					break;
				}
			}

			int word = name;
			while (word < text.length()) {
				char w = text.charAt(word);
				boolean ascii = w < 128 && Character.isLetterOrDigit(w);
				if (!ascii && w != '_') {
					break;
				}

				word++;
			}

			boolean identifier = word > name && !(text.charAt(name) >= '0' && text.charAt(name) <= '9');
			String directive = identifier ? text.substring(name, word) : "";

			int end = at;
			while (end < text.length()) {
				char e = text.charAt(end);
				if (e == '\n' || e == '\r') {
					break;
				}

				if (e == '\\' && end + 1 < text.length() && (text.charAt(end + 1) == '\n' || text.charAt(end + 1) == '\r')) {
					end += (text.charAt(end + 1) == '\r' && end + 2 < text.length() && text.charAt(end + 2) == '\n')
							? 3 : 2;
				} else if (text.startsWith("/*", end)) {
					int close = text.indexOf("*/", end + 2);
					end = close < 0 ? text.length() : close + 2;
				} else if (text.startsWith("//", end)) {
					while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
						end++;
					}
				} else {
					end++;
				}
			}

			for (int mark = at; mark < end; mark++) {
				onLine[mark] = directive;
			}

			at = end;
			lineStart = false;
		}

		return java.util.Arrays.asList(onLine);
	}

	@Test
	void agreesWithACharacterByCharacterReadingOnRandomText() {
		// Pieces chosen for the seams: every place where two of them meet is a place a token could end
		// in the wrong spot.
		String[] pieces = {"#", "##", "define", "if", "1", "0x1E", "e", "+", "-", ".", "a", "_", " ", "\t", "\n",
			"\r\n", "\r", "\\\n", "\\\r\n", "\\", "//", "/*", "*/", "/", "*", "(", ")", "{", ";", "\u00e9", "\uD83D",
			"\0", "\f"};
		Random random = new Random(0x5EED);

		for (int round = 0; round < 20_000; round++) {
			StringBuilder text = new StringBuilder();
			for (int part = random.nextInt(24); part >= 0; part--) {
				text.append(pieces[random.nextInt(pieces.length)]);
			}

			String source = text.toString();
			List<Token> tokens = GlslLexer.lex(source);

			assertEquals(source, GlslLexer.join(tokens), "join of " + escape(source));

			List<String> expected = expectedDirectives(source);
			int offset = 0;
			for (Token token : tokens) {
				assertFalse(token.text().isEmpty(), "an empty token in " + escape(source));
				for (int at = offset; at < offset + token.text().length(); at++) {
					// Every character of a token sits on the token's line, so the first says it all,
					// except that the break ending a directive is itself no part of the directive.
					if (token.kind() != Kind.NEWLINE) {
						assertEquals(expected.get(at), token.directive(),
								"character " + at + " of " + escape(source) + " in " + show(token));
					}
				}

				offset += token.text().length();
			}
		}
	}

	private static String escape(String text) {
		return text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f")
				.replace("\0", "\\0");
	}

	@Test
	void takesAWholeUnitOfAMillionCharactersInOnePass() {
		StringBuilder unit = new StringBuilder();
		String line = "vec3 c = mix(texture2D(colortex0, uv).rgb, vec3(1.0e-3, .5, 0x1F), 0.25); // tail\r\n";
		while (unit.length() < 1_000_000) {
			unit.append(line).append("#define M(x) ((x) * 2.0) \\\n\t+ 1.0\n");
		}

		String source = unit.toString();
		List<Token> tokens = GlslLexer.lex(source);

		assertEquals(source, GlslLexer.join(tokens));
		long directives = tokens.stream().filter(t -> t.kind() == Kind.HASH).count();
		assertEquals(source.split("#define", -1).length - 1, directives);
	}

	@Test
	void takesManyHashesAndManyUnterminatedOpenersWithoutTrouble() {
		String hashes = "#\n".repeat(50_000);
		assertEquals(50_000, GlslLexer.lex(hashes).stream().filter(t -> t.kind() == Kind.HASH).count());

		// Nothing in it closes: "/*/*" would, the star of the second opener meeting the slash after it.
		String openers = "/*a".repeat(100_000);
		List<Token> tokens = GlslLexer.lex(openers);
		assertEquals(1, tokens.size());
		assertEquals(Kind.COMMENT, tokens.getFirst().kind());
	}
}
