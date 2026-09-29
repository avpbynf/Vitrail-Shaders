package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds how one entry file is flattened into the single unit that reaches the compiler: includes
 * spliced in, dead branches kept as they stand, loose conditionals rewritten into ones the compiler
 * takes, and every loop bounded on total work.
 * <p>
 * The unit is compared line for line. Which line a message lands on is part of the contract, since an
 * error the compiler prints is numbered against this text and nothing else: an include directive is
 * replaced by the lines of its file with no line marker, and a rewritten directive is still exactly one
 * line.
 */
class IncludeExpanderTest {

	@TempDir
	Path temp;

	private Path pack(Shape shape, Map<String, String> files) throws IOException {
		Path packs = Files.createTempDirectory(this.temp, "packs");

		return shape.build(packs, "pack", files);
	}

	private static Map<String, String> files(String... pairs) {
		Map<String, String> files = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			files.put("shaders/" + pairs[i], pairs[i + 1]);
		}

		return files;
	}

	private ExpandedUnit expand(Shape shape, Map<String, String> files, String entry, SettingSet settings)
			throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(pack(shape, files))) {
			return new IncludeExpander(source, settings).expand(source.file(entry).orElseThrow());
		}
	}

	private ExpandedUnit expand(Map<String, String> files, String entry) throws IOException {
		return expand(Shape.DIRECTORY, files, entry, SettingSet.defaults());
	}

	private static SettingSet chosen(String name, OptionValue value) {
		return SettingSet.resolve(Map.of(), Map.of(name, value), "test");
	}

	// --- includes -------------------------------------------------------------------------------

	@ParameterizedTest
	@EnumSource(Shape.class)
	void splicesAnIncludeInPlaceOfItsDirectiveWithNoLineMarker(Shape shape) throws IOException {
		Map<String, String> files = files(
				"composite.fsh", "#version 330\n#include \"lib/a.glsl\"\nvoid main() {}\n",
				"lib/a.glsl", "#include \"b.glsl\"\n// a\n",
				"lib/b.glsl", "// b\n");

		ExpandedUnit unit = expand(shape, files, "composite.fsh", SettingSet.defaults());

		// The directive line is gone; every file contributes its own final empty line, so a file that
		// ends in a newline leaves a blank behind it. b.glsl resolved against lib/, the directory of the
		// file that asked for it.
		assertEquals(List.of("#version 330", "// b", "", "// a", "", "void main() {}", ""), unit.lines());
		assertEquals("composite.fsh", unit.entry());
		assertEquals("330", unit.version());
		assertEquals("#version 330\n// b\n\n// a\n\nvoid main() {}\n", unit.text());
		assertEquals(new ExpansionStats(2, 2, 0, 0, 0, 0, 2, 0, 0, 0, 0), unit.stats());
		assertTrue(unit.stats().clean());
	}

	@Test
	void resolvesARelativeIncludeAgainstTheIncludingFileAndASlashAgainstShaders() throws IOException {
		Map<String, String> files = files(
				"world0/composite.fsh", "#include \"inc/one.glsl\"\n#include \"/shared/two.glsl\"\n#include \"../shared/two.glsl\"\n",
				"world0/inc/one.glsl", "#include \"../local.glsl\"\n",
				"world0/local.glsl", "// local of world0\n",
				"shared/two.glsl", "// two\n",
				"local.glsl", "// local of the root\n");

		ExpandedUnit unit = expand(files, "world0/composite.fsh");

		assertEquals(List.of("// local of world0", "", "", "// two", "", "// two", "", ""), unit.lines());
		assertTrue(unit.stats().clean());
	}

	@Test
	void takesAngleBracketsAndQuotesAsInterchangeableAndIgnoresWhatFollowsTheClosingOne() throws IOException {
		Map<String, String> files = files(
				"x.fsh", """
						#include "a.glsl"
						#include <a.glsl>
						#include "a.glsl>
						#include <a.glsl"
						#include "a.glsl" // trailing words
						  #  include "a.glsl"
						#include a.glsl
						# include
						""",
				"a.glsl", "A");

		ExpandedUnit unit = expand(files, "x.fsh");

		// Six directives are followed; "#include a.glsl" has no delimiter and "# include" has no name,
		// so both stay ordinary lines that reach the compiler as they were written.
		assertEquals(List.of("A", "A", "A", "A", "A", "A", "#include a.glsl", "# include", ""), unit.lines());
		assertEquals(6, unit.stats().followed());
	}

	@Test
	void triesOneSpellingOnlyWithNoSearchPathAndNoImplicitExtension() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#include \"lib/a\"\n#include \"a.glsl\"\n#include \"lib/a.glsl\"\n",
				"lib/a.glsl", "// found\n");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals(List.of("#error include not found: lib/a", "#error include not found: a.glsl", "// found", "",
				""), unit.lines());
		assertEquals(2, unit.stats().missing());
		assertFalse(unit.stats().clean());
	}

	@Test
	void writesAnErrorDirectiveForAMissingIncludeAndCarriesOn() throws IOException {
		Map<String, String> files = files("x.fsh", "before\n#include \"nope.glsl\"\nafter\n");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals(List.of("before", "#error include not found: nope.glsl", "after", ""), unit.lines());
		assertEquals(new ExpansionStats(1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0), unit.stats());
		assertTrue(unit.isLive(1));
	}

	@Test
	void cutsACycleAtTheFileAlreadyOnTheInclusionStack() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#include \"a.glsl\"\n",
				"a.glsl", "// a\n#include \"b.glsl\"\n",
				"b.glsl", "// b\n#include \"a.glsl\"\n#include \"b.glsl\"\n");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals(List.of("// a", "// b", "#error include cycle at a.glsl", "#error include cycle at b.glsl", "",
				"", ""), unit.lines());
		assertEquals(2, unit.stats().cycles());
		assertFalse(unit.stats().clean());
	}

	@Test
	void anEntryThatIncludesItselfIsACycle() throws IOException {
		ExpandedUnit unit = expand(files("x.fsh", "#include \"x.fsh\"\n"), "x.fsh");

		assertEquals(List.of("#error include cycle at x.fsh", ""), unit.lines());
		assertEquals(1, unit.stats().cycles());
	}

	@Test
	void readsAFileIncludedTwiceInFullBothTimesBecauseThereIsNoIncludeOnce() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#include \"g.glsl\"\n#include \"g.glsl\"\n",
				"g.glsl", "#ifndef GUARD\n#define GUARD\nint once;\n#endif\n");

		ExpandedUnit unit = expand(files, "x.fsh");

		// The pack's own sentinel is what keeps the body out the second time, and it is text: the
		// #ifndef is decided again on the table as the first copy left it.
		assertEquals(List.of("#ifndef GUARD", "#define GUARD", "int once;", "#endif", "",
				"#ifndef GUARD", "#define GUARD", "int once;", "#endif", "", ""), unit.lines());
		assertEquals(List.of(true, true, true, true, true, true, false, false, true, true, true),
				liveList(unit));
		assertEquals(1, unit.stats().duplicates());
		assertEquals(2, unit.stats().followed());
	}

	@Test
	void stopsAtThirtyTwoLevelsOfNestingAndSaysWhere() throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/x.fsh", "#include \"f1.glsl\"\n");
		for (int i = 1; i <= 40; i++) {
			files.put("shaders/f" + i + ".glsl", "#include \"f" + (i + 1) + ".glsl\"\n");
		}

		ExpandedUnit unit = expand(Shape.DIRECTORY, files, "x.fsh", SettingSet.defaults());

		// x.fsh is depth zero, f32 is depth thirty two and the last one read, and f33 is the first
		// refused: its own text is never read.
		assertTrue(unit.lines().contains("#error include nesting too deep at f33.glsl"), unit.text());
		assertEquals(1, unit.stats().tooDeep());
		assertEquals(33, unit.stats().maxDepth());
		// The refused one is counted as followed, being followed up to the moment it is refused.
		assertEquals(33, unit.stats().followed());
		assertFalse(unit.stats().clean());
	}

	@Test
	void boundsTheTotalNumberOfFilesAnExponentialIncludeGraphMayOpen() throws IOException {
		// Each file includes the next twice, so the unit is two to the fifteenth files with no cycle and
		// well inside the nesting limit: only the budget of twenty thousand files ends it.
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/x.fsh", "#include \"n1.glsl\"\n");
		for (int i = 1; i < 16; i++) {
			files.put("shaders/n" + i + ".glsl", "#include \"n" + (i + 1) + ".glsl\"\n#include \"n" + (i + 1) + ".glsl\"\n");
		}

		files.put("shaders/n16.glsl", "leaf\n");

		ExpandedUnit unit = assertTimeoutPreemptively(Duration.ofSeconds(30),
				() -> expand(Shape.DIRECTORY, files, "x.fsh", SettingSet.defaults()));

		assertEquals(1, unit.lines().stream().filter(line -> line.startsWith("#error include budget exhausted")).count());
		assertTrue(unit.stats().exhausted() > 1, "the budget was hit " + unit.stats().exhausted() + " times");
		assertFalse(unit.stats().clean());
		assertTrue(unit.lines().size() < 400_000);
	}

	@Test
	void boundsTheLinesOfASingleFileThatNeverIncludesAnything() throws IOException {
		String big = "x\n".repeat(450_000);

		ExpandedUnit unit = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> expand(files("x.fsh", big), "x.fsh"));

		assertEquals(400_002, unit.lines().size());
		assertEquals("#error include budget exhausted at x.fsh", unit.lines().get(400_001));
		assertEquals(1, unit.stats().exhausted());
	}

	@Test
	void boundsTheCharactersOfAUnit() throws IOException {
		String wide = ("y".repeat(199) + "\n").repeat(21_000);

		ExpandedUnit unit = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> expand(files("x.fsh", wide), "x.fsh"));

		// Four million characters at two hundred a line is twenty thousand lines: the twenty thousand
		// and first is the one that takes the count past it.
		assertEquals(20_002, unit.lines().size());
		assertEquals("#error include budget exhausted at x.fsh", unit.lines().get(20_001));
	}

	// --- branches -------------------------------------------------------------------------------

	@Test
	void turnsAnIncludeOnADeadBranchIntoACommentAndKeepsTheRestOfTheBranchAsItStands() throws IOException {
		Map<String, String> files = files(
				"x.fsh", """
						#ifdef NOPE
						#include "lib/a.glsl"
						int dead = 1;
						#else
						#include "lib/a.glsl"
						#endif
						""",
				"lib/a.glsl", "// a");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals(List.of("#ifdef NOPE", "// include not taken: lib/a.glsl", "int dead = 1;", "#else", "// a",
				"#endif", ""), unit.lines());
		// The dead lines are kept and marked dead; directives themselves count as taken.
		assertEquals(List.of(true, false, false, true, true, true, true), liveList(unit));
		assertEquals(new ExpansionStats(2, 1, 1, 0, 0, 0, 1, 0, 1, 0, 0), unit.stats());
	}

	@Test
	void decidesAConditionOnTheDefinesAsTheyStandWhereTheLineIs() throws IOException {
		Map<String, String> files = files(
				"x.fsh", """
						#ifdef LATER
						#include "one.glsl"
						#endif
						#define LATER
						#ifdef LATER
						#include "two.glsl"
						#endif
						#undef LATER
						#ifdef LATER
						#include "three.glsl"
						#endif
						""",
				"one.glsl", "1", "two.glsl", "2", "three.glsl", "3");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals(List.of("#ifdef LATER", "// include not taken: one.glsl", "#endif", "#define LATER", "#ifdef LATER",
				"2", "#endif", "#undef LATER", "#ifdef LATER", "// include not taken: three.glsl", "#endif", ""),
				unit.lines());
		assertFalse(unit.defines().containsKey("LATER"));
	}

	@Test
	void decidesAConditionOnTheSymbolsTheCompilerAndTheEngineDefineForTheStageOfTheEntry() throws IOException {
		String body = "#ifdef GL_FRAGMENT_SHADER\n#include \"f.glsl\"\n#endif\n"
				+ "#ifdef GL_VERTEX_SHADER\n#include \"v.glsl\"\n#endif\n"
				+ "#if VULKAN == 100\n#include \"vk.glsl\"\n#endif\n"
				+ "#ifdef IS_IRIS\n#include \"iris.glsl\"\n#endif\n";
		Map<String, String> files = files("x.fsh", body, "y.vsh", body, "z.glsl", body,
				"f.glsl", "F", "v.glsl", "V", "vk.glsl", "K", "iris.glsl", "I");

		assertEquals(List.of("F", "K", "I"), spliced(expand(files, "x.fsh")));
		assertEquals(List.of("V", "K", "I"), spliced(expand(files, "y.vsh")));
		// A file whose extension names no stage starts with the engine's symbols alone.
		assertEquals(List.of("I"), spliced(expand(files, "z.glsl")));
	}

	@Test
	void appliesAChosenSettingWhereItsDeclarationStandsAndDecidesLaterConditionsOnIt() throws IOException {
		Map<String, String> files = files(
				"x.fsh", """
						#ifdef FOG
						#include "early.glsl"
						#endif
						#define QUALITY 1 //[1 2 3]
						//#define FOG
						#if QUALITY == 2
						#include "two.glsl"
						#endif
						#ifdef FOG
						#include "fog.glsl"
						#endif
						""",
				"early.glsl", "E", "two.glsl", "TWO", "fog.glsl", "FOG");
		SettingSet settings = SettingSet.resolve(Map.of(),
				Map.of("QUALITY", OptionValue.of("2"), "FOG", OptionValue.on()), "test");

		ExpandedUnit unit = expand(Shape.DIRECTORY, files, "x.fsh", settings);

		// Rewritten in place: FOG is uncommented where it is declared, so the test above it still reads
		// it undefined, which is what the compiler will read too.
		assertEquals(List.of("#ifdef FOG", "// include not taken: early.glsl", "#endif", "#define QUALITY 2 //[1 2 3]",
				"#define FOG", "#if QUALITY == 2", "TWO", "#endif", "#ifdef FOG", "FOG", "#endif", ""), unit.lines());
		assertEquals("2", unit.defines().get("QUALITY"));
		assertTrue(unit.defines().containsKey("FOG"));

		// The same file with nothing chosen keeps the pack's defaults.
		ExpandedUnit plain = expand(files, "x.fsh");
		assertEquals("1", plain.defines().get("QUALITY"));
		assertFalse(plain.defines().containsKey("FOG"));
		assertTrue(plain.lines().contains("//#define FOG"));
	}

	@Test
	void turningAToggleOffCommentsItsDeclarationOutAndTheNameIsUndefinedAfterIt() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#define FOG\n#ifdef FOG\n#include \"fog.glsl\"\n#endif\n",
				"fog.glsl", "FOG");

		ExpandedUnit off = expand(Shape.DIRECTORY, files, "x.fsh", chosen("FOG", OptionValue.off()));

		assertEquals(List.of("//#define FOG", "#ifdef FOG", "// include not taken: fog.glsl", "#endif", ""), off.lines());
		assertFalse(off.defines().containsKey("FOG"));
	}

	@Test
	void writesAConditionOnAFractionalNumberAsTheDecisionTheDriverWouldReach() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#define MOTION_BLUR 0.5\n#if MOTION_BLUR > 0.0\nblur\n#endif\n#if MOTION_BLUR < 0.25\nno\n#endif\n");

		ExpandedUnit unit = expand(files, "x.fsh");

		// One line for one line: the directive is answered in place, and what the compiler would refuse
		// is not handed to it.
		assertEquals(List.of("#define MOTION_BLUR 0.5", "#if 1", "blur", "#endif", "#if 0", "no", "#endif", ""),
				unit.lines());
		assertEquals(0, unit.stats().undecidable());
	}

	@Test
	void takesAConditionItCannotDecideAsTrueAndCountsIt() throws IOException {
		ExpandedUnit unit = expand(files("x.fsh", "#if ((\nkept\n#endif\n#if 1 / 0\nalso\n#endif\n"), "x.fsh");

		assertEquals(List.of("#if ((", "kept", "#endif", "#if 1 / 0", "also", "#endif", ""), unit.lines());
		assertEquals(2, unit.stats().undecidable());
		assertTrue(unit.stats().clean());
	}

	@Test
	void decidesAConditionContinuedOverSeveralLinesOnTheJoinedTextAndWritesItAsWritten() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#define A\n#if defined(A) && \\\n   !defined(B) \\\n   && defined(A)\n#include \"a.glsl\"\n#endif\n",
				"a.glsl", "A");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals(List.of("#define A", "#if defined(A) && \\", "   !defined(B) \\", "   && defined(A)", "A", "#endif",
				""), unit.lines());
		assertEquals(1, unit.stats().followed());
	}

	@Test
	void countsTheConditionalsItMeetsAndReadsNoneInsideABlockComment() throws IOException {
		Map<String, String> files = files(
				"x.fsh", """
						/* licence
						#define FXAA_HLSL_5 1
						#ifdef ANYTHING
						*/
						#ifdef FXAA_HLSL_5
						#include "hlsl.glsl"
						#endif
						#ifdef NOTHING
						#endif
						""",
				"hlsl.glsl", "HLSL");

		ExpandedUnit unit = expand(files, "x.fsh");

		// The define in the comment is not a define, so the branch it would have opened is dead. The
		// #ifdef inside the comment is not counted as a conditional either: two are met, not three.
		assertEquals(List.of("/* licence", "#define FXAA_HLSL_5 1", "#ifdef ANYTHING", "*/", "#ifdef FXAA_HLSL_5",
				"// include not taken: hlsl.glsl", "#endif", "#ifdef NOTHING", "#endif", ""), unit.lines());
		assertFalse(unit.defines().containsKey("FXAA_HLSL_5"));
		assertEquals(2, unit.stats().conditionals());
	}

	@Test
	void stillFollowsAnIncludeWrittenInsideABlockCommentAsIrisDoes() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "/*\n#include \"c.glsl\"\n*/\nend\n",
				"c.glsl", "C");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals(List.of("/*", "C", "*/", "end", ""), unit.lines());
	}

	@Test
	void appliesAChosenSettingToADeclarationInsideABlockCommentToo() throws IOException {
		Map<String, String> files = files("x.fsh", "/*\n#define SIZE 4 //[4 8]\n*/\n");

		ExpandedUnit unit = expand(Shape.DIRECTORY, files, "x.fsh", chosen("SIZE", OptionValue.of("8")));

		assertEquals(List.of("/*", "#define SIZE 8 //[4 8]", "*/", ""), unit.lines());
		// Applied, and still not a define: the compiler reads no directive from a comment.
		assertFalse(unit.defines().containsKey("SIZE"));
	}

	// --- one line for one line, and the loose directives ----------------------------------------

	@Test
	void rewritesAConditionalThePackWroteLooselyInPlaceAndClosesTheGroupsItLeftOpen() throws IOException {
		Map<String, String> files = files(
				"x.fsh", """
						#if
						a
						#elif
						b
						#endif
						#ifdef
						c
						#endif
						#ifdef 3
						d
						#endif
						#ifdef KEEP extra words
						e
						#endif
						#endif
						#else
						#elif X
						#if
						tail
						""");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals(List.of("#if 0", "a", "#elif 0", "b", "#endif", "#if 1", "c", "#endif", "#if 1", "d", "#endif",
				"#ifdef KEEP", "e", "#endif",
				"// no conditional is open here: #endif", "// no conditional is open here: #else",
				"// no conditional is open here: #elif X", "#if 0", "tail", "", "#endif"), unit.lines());
		// The added closer is a line of its own and the only one; everything else is one for one.
		assertEquals(21, unit.lines().size());
		// Lines 3 and 12 are rewritten and not reported: see the test below for when a loose line is said.
		assertEquals(List.of(
				"x.fsh:1 writes #if, and no expression follows it, so the group it opens is not taken",
				"x.fsh:6 writes #ifdef, and nothing that can name a setting follows it, so the group it opens is taken",
				"x.fsh:9 writes #ifdef 3, and nothing that can name a setting follows it, so the group it opens is taken",
				"x.fsh:15 writes #endif, and nothing is open for it to close, so it is not written out",
				"x.fsh:16 writes #else, and nothing is open for it to close, so it is not written out",
				"x.fsh:17 writes #elif X, and nothing is open for it to close, so it is not written out",
				"x.fsh:18 writes #if, and no expression follows it, so the group it opens is not taken"),
				looseOf(files, "x.fsh"));
	}

	/**
	 * DIAGNOSTIC GAP, pinned as it stands: whether a loosely written directive is reported depends on the
	 * state of the stack at the moment of the report, and that moment is not the same for every shape.
	 * An {@code #ifdef NAME extra} is reported after its own group has been pushed, so it is said when the
	 * name is defined and silent when it is not; an {@code #elif} with no expression is reported before
	 * its branch is decided, so it is said when the branch above it was taken, where it can decide nothing,
	 * and silent when it was not, where it decides everything. The rewrite of the text is the same either
	 * way and only the log line comes and goes.
	 */
	@Test
	void knownBug_aLooseDirectiveIsReportedOnlyWhenTheStackHappensToBeLive() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#define KEEP\n#ifdef KEEP extra\n#endif\n#ifdef NOPE extra\n#endif\n"
						+ "#if 1\n#elif\n#endif\n#if 0\n#elif\n#endif\n");

		assertEquals(List.of(
				"x.fsh:2 writes #ifdef KEEP extra, and only KEEP is read",
				"x.fsh:7 writes #elif, and no expression follows it, so the branch it opens is not taken"),
				looseOf(files, "x.fsh"));
	}

	@Test
	void keepsALiveBitPerLineOfTheFlattenedUnitAndHandsOutCopies() throws IOException {
		ExpandedUnit unit = expand(files("x.fsh", "#ifdef NOPE\ndead\n#endif\nlive\n"), "x.fsh");

		assertEquals(List.of(true, false, true, true, true), liveList(unit));
		BitSet copy = unit.live();
		copy.set(1);
		copy.clear(0);
		assertFalse(unit.isLive(1));
		assertTrue(unit.isLive(0));
		assertNotSame(unit.live(), unit.live());
	}

	@Test
	void keepsTheVersionOfTheEntryAndDropsEveryLaterOneWithNothingInItsPlace() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#version 330 core // the entry's\nA\n#include \"i.glsl\"\nB\n",
				"i.glsl", "#version 120\nfrom include\n");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals("330 core", unit.version());
		// The later directive leaves no blank: it goes, and the line count after it moves with it.
		assertEquals(List.of("#version 330 core // the entry's", "A", "from include", "", "B", ""), unit.lines());
	}

	@Test
	void aVersionOnADeadBranchIsWrittenAsItIsAndNeverBecomesTheVersion() throws IOException {
		ExpandedUnit unit = expand(files("x.fsh", "#ifdef NOPE\n#version 100\n#endif\n#version 450\n"), "x.fsh");

		assertEquals("450", unit.version());
		assertEquals(List.of("#ifdef NOPE", "#version 100", "#endif", "#version 450", ""), unit.lines());
		assertEquals(List.of(true, false, true, true, true), liveList(unit));
	}

	@Test
	void aUnitWithNoVersionDirectiveHasNoVersion() throws IOException {
		assertNull(expand(files("x.fsh", "void main() {}\n"), "x.fsh").version());
	}

	@Test
	void writesAPacksErrorDirectiveAsACommentInABranchTakenOrNot() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#error live problem\n#ifdef NOPE\n#error dead problem\n#endif\n#error /* opens\nstill comment\n*/\nafter\n");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals(List.of("// error not written out: #error live problem", "#ifdef NOPE",
				"// error not written out: #error dead problem", "#endif", "/* error not written out */ /*",
				"still comment", "*/", "after", ""), unit.lines());
		assertEquals(List.of(true, true, false, true, true, true, true, true, true), liveList(unit));
		// The ones this reader writes itself stay errors, and only those are counted against the unit.
		assertTrue(unit.stats().clean());
	}

	@Test
	void aGroupOpenedInAnIncludeMayBeClosedByTheFileThatIncludedIt() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#include \"open.glsl\"\ninside\n#endif\n",
				"open.glsl", "#ifdef NOPE\n");

		ExpandedUnit unit = expand(files, "x.fsh");

		// Counted on the text written and not on the file being read, so this #endif has something to
		// close and is not commented out.
		assertEquals(List.of("#ifdef NOPE", "", "inside", "#endif", ""), unit.lines());
	}

	@Test
	void readsAnIncludeThatMakesTheDefinesOfTheFileVisibleToTheFileAfterIt() throws IOException {
		Map<String, String> files = files(
				"x.fsh", "#include \"defs.glsl\"\n#ifdef FROM_INCLUDE\n#include \"yes.glsl\"\n#endif\n",
				"defs.glsl", "#define FROM_INCLUDE 7\n",
				"yes.glsl", "YES");

		ExpandedUnit unit = expand(files, "x.fsh");

		assertEquals(List.of("#define FROM_INCLUDE 7", "", "#ifdef FROM_INCLUDE", "YES", "#endif", ""), unit.lines());
		assertEquals("7", unit.defines().get("FROM_INCLUDE"));
	}

	// --- memo and the report's expander ---------------------------------------------------------

	@Test
	void flattensAnEntryOncePerOpeningAndSettingsAndServesTheSameUnitAfter() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, files("x.fsh", "A\n", "y.fsh", "B\n"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			SettingSet settings = SettingSet.defaults();
			IncludeExpander expander = new IncludeExpander(source, settings);
			Path x = source.file("x.fsh").orElseThrow();

			ExpandedUnit first = expander.expand(x);
			assertSame(first, expander.expand(x));
			// A second expander over the same opening and the same settings is served from the memo.
			assertSame(first, new IncludeExpander(source, settings).expand(x));
			assertEquals(1, source.unitsFlattened());

			// Settings are compared by identity: an equal table built again expands again.
			ExpandedUnit other = new IncludeExpander(source, SettingSet.defaults()).expand(x);
			assertNotSame(first, other);
			assertEquals(first.lines(), other.lines());
			assertEquals(2, source.unitsFlattened());
		}
	}

	@Test
	void theReportsExpanderNeitherReadsNorFillsTheMemo() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, files("x.fsh", "A\n"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			SettingSet settings = SettingSet.defaults();
			Path x = source.file("x.fsh").orElseThrow();
			IncludeExpander report = IncludeExpander.forTheReport(source, settings);

			ExpandedUnit once = report.expand(x);
			ExpandedUnit twice = report.expand(x);

			assertNotSame(once, twice);
			assertEquals(once.lines(), twice.lines());
			assertEquals(0, source.unitsFlattened());

			ExpandedUnit kept = new IncludeExpander(source, settings).expand(x);
			assertNotSame(once, kept);
			assertEquals(1, source.unitsFlattened());
			// And it does not read what a load filed there either.
			assertNotSame(kept, report.expand(x));
		}
	}

	@Test
	void doesNotRememberAUnitThatThrew() throws IOException {
		Path archive = SyntheticPacks.zip(Files.createTempDirectory(this.temp, "z"), "gone", files("x.fsh", "A\n"));
		ShaderPackSource source = ShaderPackSource.open(archive);
		Path x = source.file("x.fsh").orElseThrow();
		IncludeExpander expander = new IncludeExpander(source, SettingSet.defaults());
		source.close();

		assertThrows(RuntimeException.class, () -> expander.expand(x));
		assertEquals(0, source.unitsFlattened());
	}

	private static List<Boolean> liveList(ExpandedUnit unit) {
		List<Boolean> live = new ArrayList<>();
		for (int line = 0; line < unit.lines().size(); line++) {
			live.add(unit.isLive(line));
		}

		return live;
	}

	/** What a unit holds of its own includes, with the entry's own one-letter marks removed. */
	private static List<String> spliced(ExpandedUnit unit) {
		return unit.lines().stream().filter(line -> line.length() == 1).toList();
	}

	private List<String> looseOf(Map<String, String> files, String entry) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(pack(Shape.DIRECTORY, files))) {
			IncludeExpander expander = new IncludeExpander(source, SettingSet.defaults());
			expander.expand(source.file(entry).orElseThrow());

			return expander.looseDirectives();
		}
	}
}
