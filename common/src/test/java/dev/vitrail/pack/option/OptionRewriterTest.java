package dev.vitrail.pack.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Holds how a chosen setting is written back into the line that declares it.
 * <p>
 * The rewrite happens where the declaration stands, and only there: a line that declares nothing,
 * declares a setting nobody chose, or declares a constant off the closed list comes back as it was.
 */
class OptionRewriterTest {

	private static Map<String, OptionValue> chosen(String name, OptionValue value) {
		return Map.of(name, value);
	}

	private static String apply(String line, String name, OptionValue value) {
		return OptionRewriter.apply(line, chosen(name, value), 100);
	}

	private static String scaled(String line, int scale) {
		return OptionRewriter.apply(line, Map.of(), scale);
	}

	// ---- defines carrying a value ------------------------------------------------------------

	@Test
	void rewritesTheValueOfADefineKeepingIndentAndTheListComment() {
		OptionValue two = OptionValue.of("2");

		assertEquals("#define FOO 2 // [0 1 2]", apply("#define FOO 1 // [0 1 2]", "FOO", two));
		assertEquals("\t#define FOO 2 //[0 1 2]", apply("\t#define FOO 1 //[0 1 2]", "FOO", two));
		assertEquals("  #define FOO 2 // [0 1 2]", apply("  #define FOO 1 // [0 1 2]", "FOO", two));
		assertEquals("#define FOO 2", apply("#define FOO 1", "FOO", two));
		assertEquals("#define FOO 2 // [0 1 2] trailing", apply("#define FOO 1 // [0 1 2] trailing", "FOO", two));
	}

	@Test
	void normalisesTheSpacingBetweenTheKeywordTheNameAndTheValue() {
		assertEquals("#define FOO 2 //   [0 1 2]",
				apply("#define   FOO    1   //   [0 1 2]", "FOO", OptionValue.of("2")));
	}

	@Test
	void aCommentThatIsNotTheListIsDroppedOnTheFirstWrite() {
		OptionValue two = OptionValue.of("2");

		// The list has to follow the slashes directly; a sentence before it takes both away.
		assertEquals("#define FOO 2", apply("#define FOO 1 // strength [0 1 2]", "FOO", two));
		assertEquals("#define FOO 2", apply("#define FOO 1 // just a note", "FOO", two));
	}

	@Test
	void aChosenValueUncommentsACommentedOutDefine() {
		OptionValue two = OptionValue.of("2");

		assertEquals("#define FOO 2 // [1 2]", apply("//#define FOO 1 // [1 2]", "FOO", two));
		assertEquals("#define FOO 2", apply("// #define FOO 1", "FOO", two));
		assertEquals("\t#define FOO a b", apply("\t//  #  define FOO 1", "FOO", OptionValue.of("a b")));
	}

	@Test
	void aDefineWithParametersIsRewrittenLikeAnyOtherAndLosesItsBody() {
		assertEquals("#define FOO 1", apply("#define FOO(x) x", "FOO", OptionValue.of("1")));
	}

	@Test
	void aNameIsMatchedWholeNotByPrefix() {
		OptionValue one = OptionValue.of("1");

		assertEquals("#define FOOBAR 9", apply("#define FOOBAR 9", "FOO", one));
		assertEquals("#define FOO_2 9", apply("#define FOO_2 9", "FOO", one));
		assertEquals("#define BAR 9", apply("#define BAR 9", "FOO", one));
	}

	// ---- toggles -----------------------------------------------------------------------------

	@Test
	void turningAToggleOnUncommentsItAndOffCommentsItOut() {
		OptionValue on = OptionValue.on();
		OptionValue off = OptionValue.off();

		assertEquals("#define FOO", apply("#define FOO", "FOO", on));
		assertEquals("//#define FOO", apply("#define FOO", "FOO", off));
		assertEquals("#define FOO", apply("//#define FOO", "FOO", on));
		assertEquals("//#define FOO", apply("//#define FOO", "FOO", off));
		assertEquals("  #define FOO", apply("  // #define FOO", "FOO", on));
		assertEquals("  //#define FOO", apply("  // #define FOO", "FOO", off));
		assertEquals("\t//#define FOO // [a b]", apply("\t#define FOO // [a b]", "FOO", off));
	}

	@Test
	void aCommentOnAToggleThatIsNotAListIsDropped() {
		assertEquals("//#define FOO", apply("#define FOO // enables the thing", "FOO", OptionValue.off()));
	}

	@Test
	void aValueGivenToABareToggleIsWrittenAfterIt() {
		assertEquals("#define FOO 3", apply("#define FOO", "FOO", OptionValue.of("3")));
		assertEquals("#define FOO 3", apply("//#define FOO", "FOO", OptionValue.of("3")));
	}

	/**
	 * {@code SettingSet.globalDefines} reads a switch chosen on for a define that carries a value as
	 * that define at its default, and the rewrite leaves it defined with nothing.
	 */
	@Test
	void knownBug_aSwitchOnAValuedDefineDropsItsValue() {
		assertEquals("#define FOO // [1 2]", apply("#define FOO 1 // [1 2]", "FOO", OptionValue.on()));
		assertEquals("//#define FOO // [1 2]", apply("#define FOO 1 // [1 2]", "FOO", OptionValue.off()));
	}

	// ---- untouched lines ---------------------------------------------------------------------

	@Test
	void aLineThatDeclaresNothingComesBackAsTheSameString() {
		Map<String, OptionValue> everything = new HashMap<>();
		for (String name : List.of("FOO", "BAR", "x", "shadowMapResolution")) {
			everything.put(name, OptionValue.of("7"));
		}

		for (String line : List.of("", "vec3 x = vec3(FOO);", "#ifdef FOO", "#undef FOO", "#include \"FOO.glsl\"",
				"// FOO BAR", "#define", "#define 1FOO 2", "float FOO = 1.0; // #define FOO 2", "\t#pragma FOO",
				"const int shadowMapResolution 2048;", "int shadowMapResolution = 2048;")) {
			assertSame(line, OptionRewriter.apply(line, everything, 100), line);
		}
	}

	@Test
	void aDeclarationNobodyChoseComesBackAsTheSameString() {
		Map<String, OptionValue> other = chosen("ELSE", OptionValue.of("1"));

		for (String line : List.of("#define FOO 1 // [0 1]", "//#define FOO", "const  int shadowDistance=128 ;",
				"const int shadowMapResolution = 2048; // [1024 2048]")) {
			assertSame(line, OptionRewriter.apply(line, other, 100), line);
		}
	}

	// ---- constants ---------------------------------------------------------------------------

	@Test
	void rewritesOnlyTheRightHandSideOfAConstantOnTheClosedList() {
		assertEquals("const int shadowMapResolution = 4096; // [1024 2048 4096]",
				apply("const int shadowMapResolution = 2048; // [1024 2048 4096]", "shadowMapResolution",
						OptionValue.of("4096")));
		assertEquals("\tconst float sunPathRotation = -30.0;//[-40.0 0.0]",
				apply("\tconst float sunPathRotation=-40.0;//[-40.0 0.0]", "sunPathRotation",
						OptionValue.of("-30.0")));
		assertEquals("const int shadowDistance = 160;   // far",
				apply("const  int   shadowDistance  =  128 ;   // far", "shadowDistance", OptionValue.of("160")));
	}

	@Test
	void aSwitchIsWrittenOutOnAConstBoolAndIgnoredOnANumber() {
		assertEquals("const bool shadowHardwareFiltering = true; // note",
				apply("const bool shadowHardwareFiltering = false; // note", "shadowHardwareFiltering",
						OptionValue.on()));
		assertEquals("const bool shadowHardwareFiltering = false;",
				apply("const bool shadowHardwareFiltering = true;", "shadowHardwareFiltering",
						OptionValue.off()));

		String number = "const int shadowMapResolution = 2048; // [1024 2048]";
		assertSame(number, OptionRewriter.apply(number, chosen("shadowMapResolution", OptionValue.on()), 100));
		assertSame(number, OptionRewriter.apply(number, chosen("shadowMapResolution", OptionValue.off()), 100));
	}

	@Test
	void aConstantOffTheClosedListIsNeverRewritten() {
		String line = "const int notASetting = 1; // [1 2]";

		assertSame(line, OptionRewriter.apply(line, chosen("notASetting", OptionValue.of("2")), 100));
		assertSame(line, OptionRewriter.apply(line, chosen("notASetting", OptionValue.on()), 100));
	}

	@Test
	void theRewriterGatesOnTheClosedListAloneWhateverTheType() {
		// A uint never reaches a screen, but a hand-written line of the settings file still lands.
		assertEquals("const uint shadowDistance = 8u;",
				apply("const uint shadowDistance = 4u;", "shadowDistance", OptionValue.of("8u")));
	}

	@Test
	void aCommentedOutConstantIsNotADeclaration() {
		String line = "//const int shadowMapResolution = 2048;";

		assertSame(line, apply(line, "shadowMapResolution", OptionValue.of("512")));
		assertSame(line, scaled(line, 50));
	}

	// ---- the shadow map scale ----------------------------------------------------------------

	@Test
	void aScaleOfAHundredTouchesNothing() {
		String line = "const  int shadowMapResolution=2048 ;//c";

		assertSame(line, scaled(line, 100));
	}

	@Test
	void scalesTheShadowMapResolutionAsAPercentageOnEachAxis() {
		String line = "const int shadowMapResolution = 2048; // [1024 2048 4096]";

		assertEquals("const int shadowMapResolution = 1024; // [1024 2048 4096]", scaled(line, 50));
		assertEquals("const int shadowMapResolution = 512; // [1024 2048 4096]", scaled(line, 25));
		assertEquals("const int shadowMapResolution = 4096; // [1024 2048 4096]", scaled(line, 200));
		assertEquals("const int shadowMapResolution = 1; // [1024 2048 4096]", scaled(line, 0));
	}

	@Test
	void roundsHalvesUpAndNeverGoesBelowOneTexel() {
		assertEquals("const int shadowMapResolution = 2;", scaled("const int shadowMapResolution = 3;", 50));
		assertEquals("const int shadowMapResolution = 1;", scaled("const int shadowMapResolution = 3;", 10));
		assertEquals("const int shadowMapResolution = 330;", scaled("const int shadowMapResolution = 1000;", 33));
		assertEquals("const int shadowMapResolution = 1;", scaled("const int shadowMapResolution = -5;", 50));
	}

	@Test
	void theScaleRewritesTheSpacingAndKeepsTheTail() {
		assertEquals("const int shadowMapResolution = 1024;//c",
				scaled("const  int  shadowMapResolution=2048 ;//c", 50));
		assertEquals("\tconst int shadowMapResolution = 1024;", scaled("\tconst int shadowMapResolution = +2048;", 50));
	}

	@Test
	void aScaleComposesWithTheChosenValue() {
		String line = "const int shadowMapResolution = 2048; // [1024 2048 4096]";

		assertEquals("const int shadowMapResolution = 2048; // [1024 2048 4096]",
				OptionRewriter.apply(line, chosen("shadowMapResolution", OptionValue.of("4096")), 50));
		// A choice the scale cannot read is still written.
		assertEquals("const int shadowMapResolution = auto; // [1024 2048 4096]",
				OptionRewriter.apply(line, chosen("shadowMapResolution", OptionValue.of("auto")), 50));
		// A switch says nothing about a number, scaled or not.
		assertSame(line, OptionRewriter.apply(line, chosen("shadowMapResolution", OptionValue.on()), 50));
	}

	@Test
	void aScaleLeavesADeclarationItCannotReadAsAWholeNumberAlone() {
		for (String line : List.of("const int shadowMapResolution = 1024 * 2;", "const int shadowMapResolution=SM_RES;",
				"const int shadowMapResolution = 2048.0;", "const int shadowMapResolution = 0x800;")) {
			assertSame(line, scaled(line, 50), line);
		}
	}

	@Test
	void aScaleTouchesNoOtherName() {
		for (String line : List.of("const int shadowDistance = 128;", "const int shadowMapResolutionX = 2048;",
				"#define shadowMapResolution 2048", "const float shadowMapResolution = 2048.5;")) {
			// The last one is a float typed constant of the right name: it is read as a whole number
			// or left alone, and 2048.5 is not one.
			assertSame(line, scaled(line, 50), line);
		}
	}

	/**
	 * The scale multiplies in {@code int}, so an absurd declared size wraps before it is divided.
	 * A hundred million texels a side is not a size any pack writes.
	 */
	@Test
	void knownBug_aScaledResolutionOverflowsInt() {
		assertEquals("const int shadowMapResolution = 7050327;",
				scaled("const int shadowMapResolution = 100000000;", 50));
	}

	// ---- properties over generated declarations ----------------------------------------------

	private static final List<String> LIST_TOKENS = List.of("0", "1", "2", "-1", "0.5", "0.25", "1e-2", ".75", "0xFF",
			"Low", "High", "16", "4096");

	private static final List<String> CONST_NAMES = List.of("shadowMapResolution", "shadowDistance",
			"voxelDistance", "shadowDistanceRenderMul", "entityShadowDistanceMul", "shadowIntervalSize",
			"wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife", "centerDepthHalflife",
			"sunPathRotation", "ambientOcclusionLevel", "superSamplingLevel", "noiseTextureResolution");

	private static final List<String> COMMENT_STYLES = List.of("// [%s]", "//[%s]", "// [ %s ]", "//   [%s]");

	/** A declaration the generator wrote, and what the rewriter has to make of a choice for it. */
	private record Generated(String line, String name, boolean toggle, List<String> values, String expectedPrefix,
			String expectedTail) {

		String expected(OptionValue value) {
			if (this.toggle) {
				return this.expectedPrefix + (value.asBoolean() ? "" : "//") + this.expectedTail;
			}

			return this.expectedPrefix + value.text() + this.expectedTail;
		}
	}

	private static Generated generate(Random random, int number) {
		String indent = List.of("", "\t", "  ", "    ").get(random.nextInt(4));
		int kind = random.nextInt(3);
		int size = 2 + random.nextInt(3);
		List<String> pool = new ArrayList<>(LIST_TOKENS);
		List<String> values = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			values.add(pool.remove(random.nextInt(pool.size())));
		}

		String comment = COMMENT_STYLES.get(random.nextInt(COMMENT_STYLES.size())).formatted(String.join(" ", values));

		if (kind == 0) {
			String name = "OPT_" + number;
			String hidden = random.nextBoolean() ? "//" + (random.nextBoolean() ? " " : "") : "";
			String tail = random.nextBoolean() ? " " + comment : "";
			// The prefix carries "#define NAME" and the toggle's own "//" goes between indent and hash.
			return new Generated(indent + hidden + "#define " + name + tail, name, true,
					tail.isEmpty() ? List.of() : values, indent, "#define " + name + tail);
		}

		if (kind == 1) {
			String name = "OPT_" + number;
			String hidden = random.nextBoolean() ? "//" + (random.nextBoolean() ? " " : "") : "";
			String dflt = values.get(random.nextInt(values.size()));
			return new Generated(indent + hidden + "#define " + name + " " + dflt + " " + comment, name, false,
					values, indent + "#define " + name + " ", " " + comment);
		}

		String name = CONST_NAMES.get(number % CONST_NAMES.size());
		String type = random.nextBoolean() ? "int" : "float";
		String dflt = values.get(random.nextInt(values.size()));
		String spacing = random.nextBoolean() ? " " : "";
		return new Generated(indent + "const " + type + " " + name + spacing + "=" + spacing + dflt + ";" + comment,
				name, false, values, indent + "const " + type + " " + name + " = ", ";" + comment);
	}

	private static OptionValue choose(Random random, Generated declaration) {
		if (declaration.toggle()) {
			return random.nextBoolean() ? OptionValue.on() : OptionValue.off();
		}

		return OptionValue.of(declaration.values().get(random.nextInt(declaration.values().size())));
	}

	@Test
	void aChosenValueRoundTripsThroughTheIndexOnGeneratedDeclarations() {
		for (long seed : new long[] {1L, 2L, 3L, 0xC0FFEEL}) {
			Random random = new Random(seed);
			for (int i = 0; i < 300; i++) {
				// Distinct constant names within one line, so each line is indexed on its own.
				Generated declaration = generate(random, i);
				OptionValue value = choose(random, declaration);

				String written = apply(declaration.line(), declaration.name(), value);

				assertEquals(declaration.expected(value), written, declaration.line());

				OptionIndex.Reader reader = new OptionIndex.Reader();
				reader.read("f.glsl", List.of(written));
				PackOption read = reader.index().get(declaration.name()).orElseThrow();

				assertEquals(declaration.values(), read.values(), written);
				if (declaration.toggle()) {
					assertEquals(PackOption.Kind.TOGGLE, read.kind(), written);
					assertEquals(!value.asBoolean(), read.defaultOff(), written);
				} else {
					assertEquals(value.text(), read.defaultText(), written);
					assertFalse(read.defaultOff(), written);
				}
			}
		}
	}

	@Test
	void rewritingIsIdempotent() {
		Random random = new Random(11L);
		for (int i = 0; i < 400; i++) {
			Generated declaration = generate(random, i);
			OptionValue value = choose(random, declaration);

			String once = apply(declaration.line(), declaration.name(), value);

			assertEquals(once, apply(once, declaration.name(), value), declaration.line());
		}
	}

	@Test
	void aWholeSourceIsRewrittenOnTheChosenLinesAndNowhereElse() {
		Random random = new Random(99L);
		List<String> noise = List.of("", "// a comment", "vec3 color = vec3(1.0); // #define OPT_1 5",
				"#include \"lib/common.glsl\"", "#endif", "float a = OPT_2 * 2.0;", "#undef OPT_3", "#ifdef OPT_4",
				"#define UNCHOSEN 4 // [4 5]", "const int notASetting = 3; // [3 4]", "#define OTHER OPT_2",
				"\t\tgl_FragData[0] = vec4(color, 1.0);", "varying vec2 texcoord;", "/* #define OPT_5 1 */");

		// A line is either noise (null) or a generated declaration; what each one has to come out as is
		// settled after the choices are all made, since one settings file speaks for every declaration
		// of a name, a constant drawn twice included.
		List<String> source = new ArrayList<>();
		List<Generated> declared = new ArrayList<>();
		Map<String, OptionValue> chosen = new LinkedHashMap<>();

		for (int i = 0; i < 250; i++) {
			if (random.nextInt(3) == 0) {
				source.add(noise.get(random.nextInt(noise.size())));
				declared.add(null);
				continue;
			}

			Generated declaration = generate(random, 1000 + i);
			source.add(declaration.line());
			declared.add(declaration);
			if (random.nextBoolean() && !chosen.containsKey(declaration.name())) {
				chosen.put(declaration.name(), choose(random, declaration));
			}
		}

		int changed = 0;
		for (int i = 0; i < source.size(); i++) {
			Generated declaration = declared.get(i);
			OptionValue value = declaration == null ? null : chosen.get(declaration.name());
			String expected = value == null ? source.get(i) : declaration.expected(value);
			String rewritten = OptionRewriter.apply(source.get(i), chosen, 100);

			assertEquals(expected, rewritten, "line " + (i + 1) + ": " + source.get(i));
			if (!source.get(i).equals(rewritten)) {
				changed++;
			}
		}

		assertTrue(changed > 20, "the generator has to exercise the rewrite, changed " + changed);
	}
}
