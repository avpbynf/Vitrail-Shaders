package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds the walk every properties file of a pack is read through: conditionals decided first, then
 * the backslash continuations joined, with the file's own defines feeding the conditions below them.
 * <p>
 * Each case is the shape one pack of the corpus writes, named in the test where it matters. What is
 * pinned is the text handed to the reader, because a reader's regular expressions run over exactly
 * that and nothing else.
 */
class PropertiesFileTest {

	@TempDir
	Path temp;

	private static List<String> walk(Map<String, String> defines, String... lines) {
		List<String> handed = new ArrayList<>();
		PropertiesFile.walk(List.of(lines), defines, handed::add);

		return handed;
	}

	private static List<String> walk(String... lines) {
		return walk(Map.of(), lines);
	}

	@Test
	void handsOverEveryLineOfAFlatFileAsWrittenBlanksAndCommentsIncluded() {
		assertEquals(List.of("a=1", "", "# a comment", "b = 2", "!bang", "   indented=3"),
				walk("a=1", "", "# a comment", "b = 2", "!bang", "   indented=3"));
	}

	@Test
	void joinsAContinuationAndSwallowsTheIndentationOfTheNextLineOnly() {
		// The separator is one space; a space before the backslash stays, so it shows as two.
		assertEquals(List.of("v=1 2", "w=1  2"), walk("v=1\\", "      2", "w=1 \\", "   2"));
		assertEquals(List.of("v=a b c"), walk("v=a\\", "\tb\\", "  c"));
	}

	@Test
	void aBlankLineEndsAContinuationAndIsNotHandedOverAsALineOfItsOwn() {
		// Widening the swallowed run to any whitespace would let the key eat what follows it, which is
		// what cost one pack its main screen.
		assertEquals(List.of("screen=A B  ", "# next block", "other=1"),
				walk("screen=A B \\", "", "# next block", "other=1"));
	}

	@Test
	void aLastLineThatAsksToBeContinuedIsStillHandedOver() {
		assertEquals(List.of("v=1 2"), walk("v=1\\", "2\\"));
		assertEquals(List.of("v=1"), walk("v=1\\"));
	}

	@Test
	void aTrailingBackslashOnACommentLineJoinsItToTheNextLineToo() {
		// As in a Java properties file: the comment is not stripped before the join.
		assertEquals(List.of("# comment  key=value"), walk("# comment \\", "key=value"));
	}

	@Test
	void joinsAcrossADirectiveLineBecauseTheConditionalsAreReadFirst() {
		// Bliss, block.properties:94-99: the value continues past the line that closes its group. Folded
		// first, the #endif is swallowed into the value and the group never closes.
		assertEquals(List.of("v=1  2", "after=1"),
				walk(Map.of("A", "1"), "#ifdef A", "v=1 \\", "#endif", "  2", "after=1"));
	}

	@Test
	void aBranchThatEndsOnAContinuationRunsIntoTheNextLiveLineAndNotIntoItsElseArm() {
		// Photon's moon brightness. With A defined the first arm is live and its last line continues; the
		// dead #else arm is stepped over and the value joins the next LIVE line, which is what a text
		// with the dead arm cut out would do and so what the reference reads. The #else arm never leaks in.
		String[] photon = {"#ifdef A", "v=1 \\", "  2 \\", "#else", "v=9 \\", "  8", "#endif", "tail=1"};

		assertEquals(List.of("v=1  2  tail=1"), walk(Map.of("A", "1"), photon));
		assertEquals(List.of("v=9  8", "tail=1"), walk(Map.of(), photon));
	}

	@Test
	void picksTheFirstTrueBranchOfAnElifChainAndNoOtherEvenWhenTheyAreTrue() {
		String[] chain = {"#if MODE == 1", "one", "#elif MODE == 2", "two", "#elif MODE >= 2", "many", "#else",
				"none", "#endif"};

		assertEquals(List.of("one"), walk(Map.of("MODE", "1"), chain));
		assertEquals(List.of("two"), walk(Map.of("MODE", "2"), chain));
		assertEquals(List.of("many"), walk(Map.of("MODE", "3"), chain));
		assertEquals(List.of("none"), walk(Map.of("MODE", "0"), chain));
		assertEquals(List.of("none"), walk(Map.of(), chain));
	}

	@Test
	void readsIfdefAndIfndefAndNestsGroups() {
		String[] file = {"#ifdef A", "a", "#ifndef B", "ab", "#else", "a-nob", "#endif", "#else", "noa", "#endif",
				"end"};

		assertEquals(List.of("a", "ab", "end"), walk(Map.of("A", "1"), file));
		assertEquals(List.of("a", "a-nob", "end"), walk(Map.of("A", "1", "B", "1"), file));
		assertEquals(List.of("noa", "end"), walk(Map.of("B", "1"), file));
	}

	@Test
	void anInnerConditionIsNotEvaluatedInsideAGroupThatIsOff() {
		// Nothing the inner expression says can matter there, and it must not be counted or crash.
		assertEquals(List.of("end"), walk(Map.of(), "#ifdef OFF", "#if @@@ garbage", "x", "#endif", "#endif", "end"));
	}

	@Test
	void takesADirectiveWrittenLooselyTheWayTheReferenceDoes() {
		// An #if with nothing after it is off, and an #ifdef with no name is taken: the two differ.
		assertEquals(List.of("end"), walk("#if", "x", "#endif", "end"));
		assertEquals(List.of("x", "end"), walk("#ifdef", "x", "#endif", "end"));
		assertEquals(List.of("x", "end"), walk("#ifdef 3", "x", "#endif", "end"));
		// Anything after the name is dropped.
		assertEquals(List.of("x"), walk(Map.of("A", "1"), "#ifdef A trailing words", "x", "#endif"));
		assertEquals(List.of(), walk(Map.of(), "#ifdef A trailing words", "x", "#endif"));
		// An expression that cannot be decided is taken, so that no line goes missing.
		assertEquals(List.of("x"), walk("#if ((", "x", "#endif"));
		// A space between the hash and the keyword is still a directive.
		assertEquals(List.of("x"), walk(Map.of("A", "1"), "  #  ifdef A", "x", "  # endif"));
	}

	@Test
	void ignoresAnEndifNothingIsOpenFor() {
		assertEquals(List.of("a", "b"), walk("a", "#endif", "b"));
		assertEquals(List.of("a", "b"), walk("a", "#else", "b"));
		assertEquals(List.of("a", "b"), walk("a", "#elif X", "b"));
	}

	@Test
	void aDefineOnALiveLineChangesEveryLaterConditionAndIsNotHandedOver() {
		List<String> seen = walk("#if MODE == 2", "early", "#endif", "#define MODE 2 // comment", "#if MODE == 2",
				"late", "#endif", "#undef MODE", "#ifdef MODE", "gone", "#endif", "#define BARE", "#ifdef BARE",
				"bare", "#endif");

		assertEquals(List.of("late", "bare"), seen);
	}

	@Test
	void aDefineInADeadBranchDefinesNothing() {
		assertEquals(List.of("no", "tail"), walk("#ifdef NEVER", "#define X 1", "#endif", "#ifdef X", "yes", "#else",
				"no", "#endif", "tail"));
	}

	@Test
	void onlyAHashDirectlyFollowedByDefineOrUndefCountsAsOne() {
		// A space between the hash and the word turns the line into a comment: it is handed over as
		// text and defines nothing. Indentation before the hash does not.
		assertEquals(List.of("# define A 1", "no"),
				walk("# define A 1", "#ifdef A", "yes", "#else", "no", "#endif"));
		assertEquals(List.of("yes"), walk("   #define A 1", "#ifdef A", "yes", "#else", "no", "#endif"));
	}

	@Test
	void handsTheTableInForceToTheReaderWithEachLine() {
		Map<String, String> defines = new LinkedHashMap<>();
		defines.put("BASE", "1");
		List<String> seen = new ArrayList<>();
		PropertiesFile.walkDefining(List.of("a", "#define SIZE 64 // px", "b", "#undef BASE", "c"), defines,
				(line, table) -> seen.add(line + ":" + table));

		assertEquals(List.of("a:{BASE=1}", "b:{BASE=1, SIZE=64}", "c:{SIZE=64}"), seen);
		// The caller's table is copied, never written to.
		assertEquals(Map.of("BASE", "1"), defines);
	}

	@Test
	void aDefineWithoutAValueHoldsAnEmptyString() {
		List<String> seen = new ArrayList<>();
		PropertiesFile.walkDefining(List.of("#define FLAG", "x"), Map.of(),
				(line, table) -> seen.add(table.toString()));

		assertEquals(List.of("{FLAG=}"), seen);
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void readsAFileOfThePackAndReportsWhetherItIsThere(Shape shape) throws IOException {
		Files.createDirectories(this.temp);
		Path packPath = shape.build(this.temp, "pack", Map.of(
				"shaders/block.properties", "#ifdef A\nblock.1 = stone\n#endif\nblock.2 = dirt \\\n grass\n",
				"shaders/empty.properties", ""));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			PropertiesFile block = PropertiesFile.read(source, "block.properties");
			assertTrue(block.present());
			assertEquals("block.properties", block.name());

			List<String> off = new ArrayList<>();
			block.walk(Map.of(), off::add);
			assertEquals(List.of("block.2 = dirt  grass", ""), off);

			List<String> on = new ArrayList<>();
			block.walk(Map.of("A", "1"), on::add);
			assertEquals(List.of("block.1 = stone", "block.2 = dirt  grass", ""), on);

			// An empty file is one empty line to the reader, so the file counts as present and says nothing.
			PropertiesFile empty = PropertiesFile.read(source, "empty.properties");
			assertTrue(empty.present());
			List<String> nothing = new ArrayList<>();
			empty.walk(Map.of(), nothing::add);
			assertEquals(List.of(""), nothing);

			PropertiesFile missing = PropertiesFile.read(source, "item.properties");
			assertFalse(missing.present());
			assertEquals("item.properties", missing.name());
			List<String> none = new ArrayList<>();
			missing.walk(Map.of(), none::add);
			assertEquals(List.of(), none);

			// Found ignoring case, which is what an archive needs.
			assertTrue(PropertiesFile.read(source, "Block.Properties").present() || shape == Shape.DIRECTORY);
		}
	}
}
