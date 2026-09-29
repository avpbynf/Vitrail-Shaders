package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds where a block comment stands from one line to the next, read as a compiler reads it: a line
 * comment ends the walk of its line, and inside a block only the closing pair means anything.
 */
class BlockCommentsTest {

	@Test
	void opensOnASlashStarAndClosesOnAStarSlash() {
		assertTrue(BlockComments.openAfter("/* open", false));
		assertFalse(BlockComments.openAfter("/* one line */", false));
		assertTrue(BlockComments.openAfter("code /* a */ more /* b", false));
		assertFalse(BlockComments.openAfter("plain code;", false));
		assertFalse(BlockComments.openAfter("", false));
	}

	@Test
	void aLineCommentHidesAnOpeningOnTheSameLine() {
		assertFalse(BlockComments.openAfter("// /* not a block", false));
		assertFalse(BlockComments.openAfter("x = 1; // /*", false));
		assertTrue(BlockComments.openAfter("/* a // b", false));
	}

	@Test
	void insideABlockOnlyTheClosingPairIsLookedFor() {
		assertTrue(BlockComments.openAfter("still inside", true));
		assertTrue(BlockComments.openAfter("// not a line comment here", true));
		assertTrue(BlockComments.openAfter("/* nor a nested open", true));
		assertFalse(BlockComments.openAfter("done */", true));
		assertTrue(BlockComments.openAfter("done */ and /* reopened", true));
		assertFalse(BlockComments.openAfter("*/", true));
	}

	/** The reading as it was written before a line without a slash was let go early, kept as the reference. */
	private static boolean straightforward(String line, boolean commented) {
		for (int at = 0; at < line.length() - 1; at++) {
			if (commented) {
				if (line.charAt(at) == '*' && line.charAt(at + 1) == '/') {
					commented = false;
					at++;
				}
			} else if (line.charAt(at) == '/' && line.charAt(at + 1) == '/') {
				return false;
			} else if (line.charAt(at) == '/' && line.charAt(at + 1) == '*') {
				commented = true;
				at++;
			}
		}

		return commented;
	}

	@Test
	void agreesWithTheStraightforwardReadingOnEveryLineOfUpToEightCharactersOverTheAlphabetThatMatters() {
		char[] alphabet = {'/', '*', 'a'};
		int checked = 0;
		for (int length = 0; length <= 8; length++) {
			int combinations = (int) Math.pow(alphabet.length, length);
			for (int code = 0; code < combinations; code++) {
				char[] text = new char[length];
				int rest = code;
				for (int at = 0; at < length; at++) {
					text[at] = alphabet[rest % alphabet.length];
					rest /= alphabet.length;
				}

				String line = new String(text);
				assertEquals(straightforward(line, false), BlockComments.openAfter(line, false), line);
				assertEquals(straightforward(line, true), BlockComments.openAfter(line, true), line);
				checked++;
			}
		}

		// Three to the power of each length, nought to eight.
		assertEquals(9841, checked);
	}

	@Test
	void agreesWithTheStraightforwardReadingOnSeededLongerLinesWithBlanksAndBackslashes() {
		java.util.Random random = new java.util.Random(0x8A1CL);
		String alphabet = "//**  \\a\t";
		for (int i = 0; i < 20_000; i++) {
			StringBuilder text = new StringBuilder();
			int length = random.nextInt(40);
			for (int at = 0; at < length; at++) {
				text.append(alphabet.charAt(random.nextInt(alphabet.length())));
			}

			String line = text.toString();
			assertEquals(straightforward(line, false), BlockComments.openAfter(line, false), line);
			assertEquals(straightforward(line, true), BlockComments.openAfter(line, true), line);
		}
	}

	@Test
	void aBackslashAtTheEndOfALineChangesNothingBecauseNoCommentPairHasOne() {
		java.util.Random random = new java.util.Random(0x5EEDL);
		String alphabet = "//**  a";
		for (int i = 0; i < 20_000; i++) {
			StringBuilder text = new StringBuilder();
			int length = random.nextInt(30);
			for (int at = 0; at < length; at++) {
				text.append(alphabet.charAt(random.nextInt(alphabet.length())));
			}

			String line = text.toString();
			assertEquals(BlockComments.openAfter(line, false), BlockComments.openAfter(line + "\\", false), line);
			assertEquals(BlockComments.openAfter(line, true), BlockComments.openAfter(line + "\\", true), line);
		}
	}

	@Test
	void slashStarSlashOpensAndDoesNotCloseItself() {
		assertTrue(BlockComments.openAfter("/*/", false));
		assertFalse(BlockComments.openAfter("/**/", false));
		assertTrue(BlockComments.openAfter("*", true));
	}
}
