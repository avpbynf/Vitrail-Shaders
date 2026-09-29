package dev.vitrail.pack.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.OptionPackFixture;
import dev.vitrail.pack.source.ShaderPackSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds what the option scan makes of a pack's lines: which lines declare a setting, of which
 * kind, with which default and which list of values, and which of them the index keeps.
 * <p>
 * The scan is deliberately naive (no comment blocks, no branches), so several of these cases pin
 * shapes that look wrong and are what the measurements were taken with.
 */
class OptionIndexTest {

	private static OptionIndex index(String... lines) {
		OptionIndex.Reader reader = new OptionIndex.Reader();
		reader.read("f.glsl", List.of(lines));

		return reader.index();
	}

	private static PackOption only(String line) {
		OptionIndex index = index(line);
		assertEquals(1, index.count(), "declarations found in: " + line);

		return index.all().iterator().next();
	}

	@Test
	void aBareDefineIsAToggleOnAtItsLine() {
		PackOption option = only("#define FOO");

		assertEquals("FOO", option.name());
		assertEquals(PackOption.Kind.TOGGLE, option.kind());
		assertEquals("", option.defaultText());
		assertEquals(List.of(), option.values());
		assertFalse(option.defaultOff());
		assertNull(option.constType());
		assertEquals("f.glsl", option.declaredIn());
		assertEquals(1, option.line());
	}

	@Test
	void aCommentedOutDefineIsStillDeclaredAndShipsOff() {
		for (String line : List.of("//#define FOO", "// #define FOO", "  //   #  define FOO",
				"\t//\t#\tdefine\tFOO")) {
			PackOption option = only(line);

			assertEquals("FOO", option.name(), line);
			assertEquals(PackOption.Kind.TOGGLE, option.kind(), line);
			assertTrue(option.defaultOff(), line);
		}
	}

	@Test
	void aDefineWithAValueIsAValueEvenWithoutAList() {
		PackOption option = only("#define STRENGTH 1.5");

		assertEquals(PackOption.Kind.VALUE, option.kind());
		assertEquals("1.5", option.defaultText());
		assertFalse(option.hasValueList());
	}

	@Test
	void aTrailingCommentAloneMakesNoValue() {
		PackOption option = only("#define FOO // just a note");

		assertEquals(PackOption.Kind.TOGGLE, option.kind());
		assertEquals("", option.defaultText());
		assertEquals(List.of(), option.values());
	}

	@Test
	void aToggleMayCarryAListAndStaysAToggle() {
		PackOption option = only("#define FOO // [a b]");

		assertEquals(PackOption.Kind.TOGGLE, option.kind());
		assertEquals(List.of("a", "b"), option.values());
	}

	/** Each row: the line, the default text and the list of values the scan reads off it. */
	@Test
	void readsTheValueFormsPacksWrite() {
		String[][] rows = {
			{"#define A 1 // [0 1 2]", "1", "0 1 2"},
			{"#define A 1 //[0 1 2]", "1", "0 1 2"},
			{"#define A 1//[0 1 2]", "1", "0 1 2"},
			{"#define A 1 //   [  0   1   2  ]", "1", "0 1 2"},
			{"#define A 1 // strength of the effect [0 1 2]", "1", "0 1 2"},
			{"#define A -1 // [-1 0 1]", "-1", "-1 0 1"},
			{"#define A 0xFF // [0x00 0x7F 0xFF]", "0xFF", "0x00 0x7F 0xFF"},
			{"#define A 0.5 // [0.25 0.5 .75 1e-2]", "0.5", "0.25 0.5 .75 1e-2"},
			{"#define A 1.0 // [0.0:1.0]", "1.0", "0.0:1.0"},
			{"#define A Medium // [Low Medium High]", "Medium", "Low Medium High"},
			{"#define A \"x\" // [\"x\" \"y\"]", "\"x\"", "\"x\" \"y\""},
			{"#define\tA\t1\t//\t[0\t1]", "1", "0 1"},
			// The first bracket anywhere in the comment is the list, not the one after the slashes.
			{"#define A 1 // see [docs] [1 2]", "1", "docs"},
			// The list ends at the first closing bracket, so a nested one splits it.
			{"#define A 1 // [a [b] c]", "1", "a [b"},
			{"#define A 1 // [1 2] [3 4]", "1", "1 2"},
			// A second pair of slashes before the list does not hide it.
			{"#define A 1 // note // [1 2]", "1", "1 2"},
		};

		for (String[] row : rows) {
			PackOption option = only(row[0]);
			List<String> expected = row[2].isEmpty() ? List.of() : List.of(row[2].split(" ", -1));

			assertEquals(PackOption.Kind.VALUE, option.kind(), row[0]);
			assertEquals(row[1], option.defaultText(), row[0]);
			assertEquals(expected, option.values(), row[0]);
		}
	}

	@Test
	void aQuotedValueWithASpaceSplitsIntoTwoTokens() {
		PackOption option = only("#define A \"a b\" // [\"a b\" \"c\"]");

		assertEquals("\"a b\"", option.defaultText());
		assertEquals(List.of("\"a", "b\"", "\"c\""), option.values());
	}

	@Test
	void whatIsNotACommentedListIsNoList() {
		// No closing bracket.
		assertEquals(List.of(), only("#define A 1 // [0 1 2").values());
		// Brackets without slashes stay in the value and are no list.
		PackOption bare = only("#define A 1 [0 1 2]");
		assertEquals(List.of(), bare.values());
		assertEquals("1 [0 1 2]", bare.defaultText());
		// A block comment is not a line comment.
		PackOption block = only("#define A 1 /* [0 1] */");
		assertEquals(List.of(), block.values());
		assertEquals("1 /* [0 1] */", block.defaultText());
		// An empty list is no list.
		assertFalse(only("#define A 1 // []").hasValueList());
		assertFalse(only("#define A 1 // [   ]").hasValueList());
	}

	@Test
	void aMacroWithParametersIsAValueNamedByItsHead() {
		PackOption option = only("#define MAD(a, b) a * b + 1");

		assertEquals("MAD", option.name());
		assertEquals(PackOption.Kind.VALUE, option.kind());
		assertEquals("(a, b) a * b + 1", option.defaultText());
	}

	@Test
	void aNameEndsAtTheFirstCharacterAnIdentifierCannotHold() {
		PackOption dashed = only("#define FOO-BAR 1");
		assertEquals("FOO", dashed.name());
		assertEquals("-BAR 1", dashed.defaultText());

		PackOption accented = only("#define CAF\u00C9 1");
		assertEquals("CAF", accented.name());
		assertEquals("\u00C9 1", accented.defaultText());
	}

	@Test
	void linesThatAreNotDefinesDeclareNothing() {
		OptionIndex index = index("#defineFOO", "#define 1FOO 2", "#define", "#undef FOO", "#pragma FOO",
				"#if FOO", "///#define FOO", "/ /#define FOO", "  /* #define FOO */", "x #define FOO",
				"vec3 c = vec3(1.0);", "");

		assertEquals(0, index.count());
	}

	@Test
	void readsTheConstantsThePackExpectsToBeEditedInPlace() {
		PackOption resolution = only("const int shadowMapResolution = 2048; // [1024 2048 4096]");

		assertEquals(PackOption.Kind.CONST, resolution.kind());
		assertEquals("int", resolution.constType());
		assertEquals("2048", resolution.defaultText());
		assertEquals(List.of("1024", "2048", "4096"), resolution.values());
		assertFalse(resolution.defaultOff());

		PackOption rotation = only("\tconst float sunPathRotation=-40.0;//[-40.0 0.0 40.0]");
		assertEquals("float", rotation.constType());
		assertEquals("-40.0", rotation.defaultText());
		assertEquals(List.of("-40.0", "0.0", "40.0"), rotation.values());

		PackOption flag = only("const bool shadowHardwareFiltering = true;");
		assertEquals("bool", flag.constType());
		assertEquals("true", flag.defaultText());
		assertFalse(flag.hasValueList());

		assertEquals("uint", only("const uint N = 3u;").constType());
	}

	@Test
	void aConstantHoldsEverythingUpToTheFirstSemicolon() {
		assertEquals("3 * 2", only("const int N = 3 * 2; // [6 12]").defaultText());
		// Only the first of two declarations on a line is seen.
		OptionIndex two = index("const int A = 1; const int B = 2;");
		assertEquals(Set.of("A"), two.names());
		// A list of declarators is one value.
		assertEquals("1, b = 2", only("const int a = 1, b = 2;").defaultText());
	}

	@Test
	void whatIsNotAScalarConstantWithASemicolonIsNotOne() {
		OptionIndex index = index("const vec3 v = vec3(1.0);", "const int X = 1 // no semicolon",
				"constint Y = 1;", "const  highp float Z = 1.0;", "int W = 1;", "// const int V = 1;");

		assertEquals(0, index.count());
	}

	@Test
	void aDefineIsReadBeforeAConstantOnTheSameLine() {
		OptionIndex index = index("#define X 1 // const int Y = 2;");

		assertEquals(Set.of("X"), index.names());
	}

	@Test
	void theFirstDeclarationOfANameWinsWithinAFileAndAcrossFiles() {
		OptionIndex.Reader reader = new OptionIndex.Reader();
		reader.read("a.glsl", List.of("//#define X", "#define X 1 // [1 2]", "#define Y 5"));
		reader.read("b.glsl", List.of("#define Y 7 // [7 8]", "#define Z 1"));
		OptionIndex index = reader.index();

		PackOption x = index.get("X").orElseThrow();
		assertEquals(PackOption.Kind.TOGGLE, x.kind());
		assertTrue(x.defaultOff());
		assertEquals(1, x.line());

		PackOption y = index.get("Y").orElseThrow();
		assertEquals("5", y.defaultText());
		assertEquals("a.glsl", y.declaredIn());
		assertEquals(3, y.line());
		assertEquals("b.glsl", index.get("Z").orElseThrow().declaredIn());
		assertEquals(3, index.count());
	}

	@Test
	void theScanReadsInsideBranchesThatAreOffAndInsideBlockComments() {
		OptionIndex index = index("#if 0", "#define DEAD_BRANCH 1 // [1 2]", "#endif", "#ifdef NEVER_DEFINED",
				"//#define IN_THE_IF_ARM", "#else", "#define IN_THE_ELSE_ARM", "#endif", "/*", "#define IN_A_COMMENT",
				"const int shadowDistance = 64; // [64 128]", "*/", "#ifdef X", "#define X", "#endif");

		assertEquals(Set.of("DEAD_BRANCH", "IN_THE_IF_ARM", "IN_THE_ELSE_ARM", "IN_A_COMMENT", "shadowDistance", "X"),
				index.names());
		assertTrue(index.referenced("NEVER_DEFINED"));
		assertTrue(index.referenced("X"));
		assertEquals(List.of("64", "128"), index.get("shadowDistance").orElseThrow().values());
	}

	@Test
	void aNameDeclaredInBothArmsOfAConditionalIsOneOptionAndTheFirstArmWins() {
		OptionIndex index = index("#ifdef HIGH", "#define QUALITY 3 // [1 2 3]", "#else", "#define QUALITY 1 // [1 2 3]",
				"#endif");

		assertEquals(1, index.count());
		assertEquals("3", index.get("QUALITY").orElseThrow().defaultText());
		assertEquals(2, index.get("QUALITY").orElseThrow().line());
	}

	@Test
	void linesAreNumberedFromOne() {
		OptionIndex index = index("", "// nothing", "#define A", "", "#define B 1");

		assertEquals(3, index.get("A").orElseThrow().line());
		assertEquals(5, index.get("B").orElseThrow().line());
	}

	@Test
	void aLineThatKeepsItsCarriageReturnDeclaresNothingButStillCountsAsATest() {
		// The source splits CRLF before the reader sees a line, so this never happens on a load; the
		// reader is public and the two patterns treat a stray carriage return differently: the
		// declaration ends in a dot that cannot take it, the conditional ends in whitespace that can.
		OptionIndex index = index("#define FOO 1\r", "#ifdef BAR\r");

		assertEquals(0, index.count());
		assertTrue(index.referenced("BAR"));
	}

	@Test
	void anUnknownNameIsAbsent() {
		OptionIndex index = index("#define A");

		assertTrue(index.get("B").isEmpty());
		assertFalse(index.get("A").isEmpty());
	}

	@Test
	void recordsWhichNamesAConditionalTests() {
		OptionIndex index = index("#ifdef A", "#ifndef B", "  #ifdef C", "#ifdef  D  ", "\t#ifndef\tE",
				"#ifdef F // trailing", "# ifdef G", "#if defined(H)", "#ifdef 1I", "#ifdef J K",
				"#ifdef L_2", "#ifdef", "#elif defined(M)");

		Set<String> tested = Set.of("A", "B", "C", "D", "E", "L_2");
		for (String name : List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L_2", "M")) {
			assertEquals(tested.contains(name), index.referenced(name), name);
		}
	}

	@Test
	void aConditionalCountsWhereverItSitsAndWhateverItGuards() {
		OptionIndex.Reader reader = new OptionIndex.Reader();
		reader.read("a.glsl", List.of("#define X"));
		reader.read("lib/b.inc", List.of("/*", "#ifdef X", "*/"));
		OptionIndex index = reader.index();

		// No include graph and no comment removal: any file, and a block comment, count.
		assertTrue(index.referenced("X"));
		assertFalse(index.referenced("Y"));
	}

	@Test
	void offersDecidesWhatScreenAndSettingsFileMayTouch() {
		OptionIndex index = index(
				"#define TOGGLE_TESTED", "#define TOGGLE_UNTESTED",
				"#define VALUE_LISTED 1 // [1 2]", "#define VALUE_BARE 1",
				"const int shadowMapResolution = 2048; // [1024 2048]",
				"const int shadowDistance = 128;",
				"const int notOnTheList = 1; // [1 2]",
				"const uint sunPathRotation = 1u; // [1u 2u]",
				"const bool shadowHardwareFiltering = true;",
				"const bool shadowtex0Nearest = false;",
				"const bool aBoolOffTheList = true;",
				"#ifdef TOGGLE_TESTED", "#ifndef shadowtex0Nearest", "#ifdef aBoolOffTheList");

		assertTrue(offers(index, "TOGGLE_TESTED"));
		assertFalse(offers(index, "TOGGLE_UNTESTED"));
		assertTrue(offers(index, "VALUE_LISTED"));
		assertFalse(offers(index, "VALUE_BARE"));
		assertTrue(offers(index, "shadowMapResolution"));
		assertFalse(offers(index, "shadowDistance"), "on the list but no values to cycle");
		assertFalse(offers(index, "notOnTheList"), "a list is not enough off the closed list");
		assertFalse(offers(index, "sunPathRotation"), "a uint never qualifies");
		assertFalse(offers(index, "shadowHardwareFiltering"), "a bool on the list needs a test");
		assertTrue(offers(index, "shadowtex0Nearest"));
		assertFalse(offers(index, "aBoolOffTheList"), "tested but off the closed list");
	}

	private static boolean offers(OptionIndex index, String name) {
		return index.offers(index.get(name).orElseThrow());
	}

	@Test
	void countsTheKindsTheCommentedAndTheListed() {
		OptionIndex index = index("#define T1", "//#define T2", "#define V1 1", "//#define V2 2 // [2 3]",
				"#define V3 1 // [1 2]", "const int C1 = 1; // [1 2]", "const int C2 = 2;",
				"#define T3 // [a b]");

		assertEquals(8, index.count());
		assertEquals(3, index.countByKind(PackOption.Kind.TOGGLE));
		assertEquals(3, index.countByKind(PackOption.Kind.VALUE));
		assertEquals(2, index.countByKind(PackOption.Kind.CONST));
		assertEquals(2, index.disabledCount());
		assertEquals(4, index.withValueListCount());
	}

	@Test
	void namesThatDifferOnlyByCaseAreCollisionsInSortedOrder() {
		OptionIndex index = index("#define foo", "#define FOO", "#define Foo", "#define bar", "#define BAZ");

		assertEquals(List.of("FOO", "Foo", "foo"), List.copyOf(index.caseCollisions()));
		assertEquals(List.of(), List.copyOf(index("#define A", "#define B").caseCollisions()));
	}

	@Test
	void everyDeclaredNameIsHeldOnceAsASet() {
		OptionIndex index = index("#define A", "#define B 1", "const int C = 1;");

		assertEquals(Set.of("A", "B", "C"), index.names());
		assertEquals(Set.of("A", "B", "C"), index.all().stream().map(PackOption::name).collect(Collectors.toSet()));
		assertEquals(3, index.all().size());
	}

	/**
	 * Forty names, because an order salted per process lands on the declared one now and then for
	 * three or four, and a test that passes by luck proves nothing about the next start.
	 */
	@Test
	void allIsInDeclarationOrder() {
		Random random = new Random(7);
		List<String> declared = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			String name = "OPT_" + Integer.toString(random.nextInt(Integer.MAX_VALUE), 36);
			declared.add(name);
			lines.add("#define " + name + " 1 // [1 2]");
		}

		OptionIndex index = index(lines.toArray(String[]::new));

		assertEquals(declared, index.all().stream().map(PackOption::name).toList());
		assertEquals(declared, List.copyOf(index.names()));
	}

	@Test
	void aNameDeclaredAgainInALaterFileKeepsThePlaceOfItsFirstDeclaration() {
		OptionIndex.Reader reader = new OptionIndex.Reader();
		reader.read("a.glsl", List.of("#define ZETA", "#define SHARED 1 // [1 2]", "#define ALPHA 2"));
		reader.read("b.glsl", List.of("#define BETA", "#define SHARED 9 // [8 9]", "#define ALPHA"));

		assertEquals(List.of("ZETA", "SHARED", "ALPHA", "BETA"),
				reader.index().all().stream().map(PackOption::name).toList());
	}

	@Test
	void theSourceFeedsFilesInSortedPathOrderSoTheFirstDeclarationIsTheSortedFirst(@TempDir Path root)
			throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("zeta.glsl", "#define SHARED 1 // [1 2]\n");
		files.put("lib/alpha.inc", "#define SHARED 9 // [8 9]\n#define ONLY_INC\n");
		files.put("composite.fsh", "#define SHARED 5\n#ifdef ONLY_INC\n#endif\n");
		files.put("notes.txt", "#define FROM_TEXT\n");
		OptionPackFixture.write(root, files);

		try (ShaderPackSource source = OptionPackFixture.open(root)) {
			OptionIndex index = source.options();

			// "composite.fsh" sorts before "lib/alpha.inc" and "zeta.glsl".
			PackOption shared = index.get("SHARED").orElseThrow();
			assertEquals("5", shared.defaultText());
			assertEquals("composite.fsh", shared.declaredIn());
			assertEquals("lib/alpha.inc", index.get("ONLY_INC").orElseThrow().declaredIn());
			assertTrue(index.get("FROM_TEXT").isEmpty(), "a .txt is not a source");
			assertTrue(index.referenced("ONLY_INC"));
		}
	}

	@Test
	void aCrLfFileIsReadLikeAnyOther(@TempDir Path root) throws IOException {
		OptionPackFixture.write(root, Map.of("a.glsl",
				"#define A 1 // [1 2]\r\n#ifdef A\r\n#endif\r\nconst int shadowMapResolution = 2048; // [1024 2048]\r\n"));

		try (ShaderPackSource source = OptionPackFixture.open(root)) {
			OptionIndex index = source.options();

			assertEquals("1", index.get("A").orElseThrow().defaultText());
			assertEquals(List.of("1", "2"), index.get("A").orElseThrow().values());
			assertEquals(List.of("1024", "2048"), index.get("shadowMapResolution").orElseThrow().values());
			assertTrue(index.referenced("A"));
		}
	}
}
