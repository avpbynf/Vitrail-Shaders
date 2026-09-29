package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.option.OptionRewriter;
import dev.vitrail.pack.option.OptionValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Holds the two premises the expander's shortcut for lines that are only text stands on, against
 * straightforward copies of the patterns it shortcuts.
 * <p>
 * A line is let past every reader when it is text, and a line on a branch that is off is let past when
 * it is text or a directive nothing reads there. The corpus test proves that the units come out the same;
 * this proves WHY they can, line by line, so that a pattern that grows a new shape fails here first and
 * says which premise it broke.
 */
class IncludeExpanderFastPathTest {

	// Copies of the directive patterns the expander holds, as they are written there.
	private static final List<Pattern> READ_WHERE_A_BRANCH_IS_OFF = List.of(
			Pattern.compile("^(\\s*)#\\s*(ifdef|ifndef)\\b(.*)$"),
			Pattern.compile("^(\\s*)#\\s*if\\b(.*)$"),
			Pattern.compile("^(\\s*)#\\s*elif\\b(.*)$"),
			Pattern.compile("^\\s*#\\s*else\\b.*$"),
			Pattern.compile("^\\s*#\\s*endif\\b.*$"),
			Pattern.compile("^\\s*#\\s*include\\s+[<\"](.+?)[>\"].*$"),
			Pattern.compile("^\\s*#\\s*error\\b.*$"));

	private static final List<Pattern> READ_ONLY_WHERE_A_BRANCH_IS_LIVE = List.of(
			Pattern.compile("^\\s*#\\s*version\\s+(.*)$"),
			Pattern.compile("^\\s*#\\s*undef\\s+([A-Za-z_]\\w*).*$"),
			Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s*(.*)$"));

	private static final String[] PIECES = {"#", "#", " ", "\t", "\f", "\u000B", "\r", "\n", "\u00a0", "\u0085",
			"\u2028", "\u001c", "if", "ifdef", "ifndef", "elif", "else", "endif", "error", "include", "define",
			"undef", "version", "pragma", "e", "\u00e9", "_", "1", "9", "<", ">", "\"", "(", ")", "//", "/*", "*/",
			"const", "int", "float", "shadowMapResolution", " = ", ";", "A", "x", "\\"};

	private static String random(Random random, int most) {
		StringBuilder text = new StringBuilder();
		int count = random.nextInt(most + 1);
		for (int i = 0; i < count; i++) {
			text.append(PIECES[random.nextInt(PIECES.length)]);
		}

		return text.toString();
	}

	private static final String[] WORDS = {"if", "ifdef", "ifndef", "elif", "else", "endif", "error", "include",
			"define", "undef", "version", "elsewhere", "iffy", "ifdefx", "else_", "el", "e", "", "endif2", "if1",
			"\u00e9lse", "else\u00e9"};
	private static final String[] BLANKS = {"", "", " ", "\t", "  ", "\f", "\u000B", "\u00a0", "\u001c"};

	/** A line that is more often than not a directive, or something that only looks like one. */
	private static String directiveLike(Random random) {
		String tail = random(random, 4);

		return BLANKS[random.nextInt(BLANKS.length)] + "#" + BLANKS[random.nextInt(BLANKS.length)]
				+ WORDS[random.nextInt(WORDS.length)] + BLANKS[random.nextInt(BLANKS.length)] + tail;
	}

	private static final String[] TYPES = {"int", "float", "bool", "uint", "vec3", "in"};
	private static final String[] CONSTANT_NAMES = {"shadowMapResolution", "sunPathRotation", "A", "x", "other",
			"shadowHardwareFiltering"};

	/** A line that is more often than not a constant declaration, the one thing besides a directive that is rewritten. */
	private static String constantLike(Random random) {
		return BLANKS[random.nextInt(BLANKS.length)] + "const" + BLANKS[random.nextInt(BLANKS.length)] + " "
				+ TYPES[random.nextInt(TYPES.length)] + " " + CONSTANT_NAMES[random.nextInt(CONSTANT_NAMES.length)]
				+ BLANKS[random.nextInt(BLANKS.length)] + "=" + BLANKS[random.nextInt(BLANKS.length)]
				+ random.nextInt(4) + (random.nextInt(8) == 0 ? "" : ";") + random(random, 3);
	}

	@Test
	void everyLineAReaderOrTheRewriterTouchesIsNotPlain() {
		Random random = new Random(0xF00DL);
		Map<String, OptionValue> chosen = new LinkedHashMap<>();
		chosen.put("A", OptionValue.on());
		chosen.put("x", OptionValue.of("3"));
		chosen.put("shadowMapResolution", OptionValue.of("512"));
		chosen.put("shadowHardwareFiltering", OptionValue.off());
		int plain = 0;
		int rewrittenWithoutAHash = 0;
		for (int i = 0; i < 100_000; i++) {
			String line = switch (i % 3) {
				case 0 -> random(random, 9);
				case 1 -> directiveLike(random);
				default -> constantLike(random);
			};

			boolean rewrites = !line.equals(OptionRewriter.apply(line, chosen, 100))
					|| !line.equals(OptionRewriter.apply(line, chosen, 50));
			boolean read = false;
			for (Pattern pattern : READ_WHERE_A_BRANCH_IS_OFF) {
				read |= pattern.matcher(line).matches();
			}

			for (Pattern pattern : READ_ONLY_WHERE_A_BRANCH_IS_LIVE) {
				read |= pattern.matcher(line).matches();
			}

			if (IncludeExpander.plain(line)) {
				plain++;
				assertFalse(read, "a reader takes " + line);
				assertFalse(rewrites, "the rewriter takes " + line);
			} else {
				assertTrue(line.indexOf('#') >= 0 || line.contains("const"), line);
			}

			if (rewrites && line.indexOf('#') < 0) {
				rewrittenWithoutAHash++;
			}
		}

		assertTrue(plain > 10_000, "the draw held " + plain + " plain lines");
		assertTrue(rewrittenWithoutAHash > 1_000,
				"the draw held " + rewrittenWithoutAHash + " constants that are rewritten and have no hash");
	}

	@Test
	void aLineWithAHashOrTheWordConstIsNeverPlain() {
		assertFalse(IncludeExpander.plain("#define A 1"));
		assertFalse(IncludeExpander.plain("x # y"));
		assertFalse(IncludeExpander.plain("const int a = 1;"));
		assertFalse(IncludeExpander.plain("constant"));
		assertTrue(IncludeExpander.plain(""));
		assertTrue(IncludeExpander.plain("cons t"));
		assertTrue(IncludeExpander.plain("vec3 v = texture2D(t, uv).rgb; // no directive here"));
	}

	@Test
	void aLineOnAnOffBranchIsOnlyTextWhenNoneOfTheEightDirectivesReadThereMatchesIt() {
		Random random = new Random(0xBEEFL);
		int text = 0;
		int directives = 0;
		for (int i = 0; i < 100_000; i++) {
			String line = i % 2 == 0 ? random(random, 8) : directiveLike(random);
			if (IncludeExpander.deadText(line)) {
				text++;
				for (Pattern pattern : READ_WHERE_A_BRANCH_IS_OFF) {
					assertFalse(pattern.matcher(line).matches(), pattern + " reads " + line);
				}
			} else {
				directives++;
			}
		}

		assertTrue(text > 20_000, "the draw held " + text + " text lines");
		assertTrue(directives > 10_000, "the draw held " + directives + " lines that go the long way");
	}

	@Test
	void everyDirectiveReadOnAnOffBranchGoesTheLongWayInEverySpacing() {
		for (String keyword : List.of("if", "ifdef", "ifndef", "elif", "else", "endif", "error", "include")) {
			for (String gap : List.of("", " ", "\t", "  \t ", "\f", "\u000B")) {
				for (String lead : List.of("", " ", "\t\t", "\f ")) {
					String line = lead + "#" + gap + keyword + " X // tail";
					assertFalse(IncludeExpander.deadText(line), line);
					assertFalse(IncludeExpander.deadText(lead + "#" + gap + keyword), line);
					assertFalse(IncludeExpander.deadText(lead + "#" + gap + keyword + "("), line);
				}
			}
		}
	}

	@Test
	void everyDirectiveNothingReadsOnAnOffBranchIsLetPast() {
		for (String line : List.of("#define A 1", "#undef A", "#version 450", "#pragma once", "#extension X : enable",
				"#line 4", "#elsewhere", "#iffy", "#include_next <x>", "#ifdefined", "#endif2", "#else_", "#errors",
				"#includes <x>", "#defined", "##", "#", "  # ", "text", "", "   ", "// #if X", "x #if X",
				"#\u00a0if X", "\u00a0#if X", "#1if")) {
			assertTrue(IncludeExpander.deadText(line), line);
		}
	}

	@Test
	void anUnplaceableWordGoesTheLongWayAndIsAnswerEitherWay() {
		// A letter beyond ASCII after a keyword is where the word boundary of the patterns is the JDK's to
		// say, and the shortcut does not guess: it takes the long way, which asks the patterns.
		for (String line : List.of("#else\u00e9", "#endif\u00e9", "#if\u00e9", "#include\u00e9 <a>")) {
			assertFalse(IncludeExpander.deadText(line), line);
		}
	}
}
