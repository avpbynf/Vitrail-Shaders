package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.source.ShaderProperties.ScreenToken;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds the one flat pass over {@code shaders.properties} that runs before any setting exists: the
 * profiles, the screens with their pages and column counts, the sliders, the two feature lines, and
 * the census of everything else. Every conditional reader lives in {@link ShaderPropertiesReadersTest}.
 * <p>
 * The values pinned here are the parsed model and not merely that the parse finished, and the
 * malformed lines are there to say what a pack that writes one gets.
 */
class ShaderPropertiesParseTest {

	@TempDir
	Path temp;

	private ShaderProperties parse(String text) throws IOException {
		Path packPath = Files.createTempDirectory(this.temp, "pack");
		Files.createDirectories(packPath.resolve("shaders"));
		Files.writeString(packPath.resolve("shaders/shaders.properties"), text);
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			return ShaderProperties.parse(source);
		}
	}

	@Test
	void aPackWithoutTheFileHasAnEmptyModel() throws IOException {
		Path packPath = Files.createTempDirectory(this.temp, "bare");
		Files.createDirectories(packPath.resolve("shaders"));
		Files.writeString(packPath.resolve("shaders/a.fsh"), "x");

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			ShaderProperties properties = ShaderProperties.parse(source);

			assertFalse(properties.present());
			assertEquals(Map.of(), properties.profiles());
			assertEquals(Map.of(), properties.customUniformTypes());
			assertEquals(java.util.Set.of(), properties.screenTokens());
			assertEquals(Map.of(), properties.screenLayout());
			assertEquals(List.of(), properties.sliders());
			assertEquals(List.of(), properties.requiredFeatures());
			assertEquals(Map.of(), properties.ignoredPrefixes());
			assertEquals(0, properties.directiveCount());
			assertEquals(0, properties.continuationCount());
			assertEquals(0, properties.blendCount());
			assertEquals(OptionalInt.empty(), properties.columns(""));
			// A pack with no file still answers every reader, with its defaults.
			assertEquals(Map.of(), properties.programConditions(Map.of()));
			assertTrue(properties.oldHandLight(Map.of()));
		}
	}

	@Test
	void anEmptyFileIsPresentAndSaysNothing() throws IOException {
		ShaderProperties properties = parse("");

		assertTrue(properties.present());
		assertEquals(Map.of(), properties.profiles());
		assertEquals(Map.of(), properties.ignoredPrefixes());
		assertEquals(0, properties.continuationCount());
	}

	@Test
	void readsTheSameModelFromADirectoryAndFromAZip() throws IOException {
		String text = "profile.LOW=A=1\nscreen=A B\nsliders=A\nuniform.float.u=1\nblend.x=off\nfoo=1\n";
		Path directory = Files.createTempDirectory(this.temp, "same");
		Files.createDirectories(directory.resolve("shaders"));
		Files.writeString(directory.resolve("shaders/shaders.properties"), text);
		Path zip = SyntheticPacks.zip(this.temp, "same", Map.of("shaders/shaders.properties", text));

		try (ShaderPackSource dir = ShaderPackSource.open(directory); ShaderPackSource archive = ShaderPackSource.open(zip)) {
			ShaderProperties a = ShaderProperties.parse(dir);
			ShaderProperties b = ShaderProperties.parse(archive);

			assertEquals(a.profiles(), b.profiles());
			assertEquals(a.screenLayout(), b.screenLayout());
			assertEquals(a.sliders(), b.sliders());
			assertEquals(a.customUniformTypes(), b.customUniformTypes());
			assertEquals(a.ignoredPrefixes(), b.ignoredPrefixes());
			assertEquals(a.blendCount(), b.blendCount());
			assertEquals(Map.of("foo", 1), b.ignoredPrefixes());
		}
	}

	@Test
	void countsTheDirectivesOnTheLinesAsWrittenIncludingOnesAContinuationSwallows() throws IOException {
		ShaderProperties properties = parse("""
				#ifdef A
				a=1
				#elif B
				b=1
				#else
				c=1
				#endif
				  # if C
				#define X 1
				#undef X
				# comment
				#iffy
				v=1 \\
				#endif
				""");

		// #ifdef, #elif, #else, #endif, "# if", and the #endif a continued value swallows when folded.
		// #define, #undef, a comment and "#iffy" (no word boundary after "if") are not conditionals.
		assertEquals(6, properties.directiveCount());
	}

	@Test
	void countsEveryBackslashThatEndsALineAsAContinuationAndOnlyThose() throws IOException {
		ShaderProperties properties = parse("a=1 \\\n  2 \\\n  3\n# comment \\\nb=1\nc=1\\ \nd=\\\n\ne=1\n");

		// Three real ones (two on a, one on the comment) plus "d=\" ending on a blank line. "c=1\ " has a
		// space after the backslash and is not one.
		assertEquals(4, properties.continuationCount());
	}

	@Test
	void readsWindowsLineEndingsWithoutALeftoverCarriageReturn() throws IOException {
		ShaderProperties properties = parse("profile.HIGH=A B\r\nscreen=A \\\r\n   B\r\nsliders=A\r\n");

		assertEquals(Map.of("HIGH", "A B"), properties.profiles());
		assertEquals(List.of(new ScreenToken.Name("A"), new ScreenToken.Name("B")), properties.screenLayout().get(""));
		assertEquals(List.of("A"), properties.sliders());
		assertEquals(1, properties.continuationCount());
	}

	@Test
	void readsProfilesInTheOrderWrittenWithTheirBodyTrimmedAndTheLastOfANameWinning() throws IOException {
		ShaderProperties properties = parse("""
				profile.MINIMUM = QUALITY=1 !BLOOM
				profile.LOW=QUALITY=2
				# profile.COMMENTED=A
				profile.MINIMUM=QUALITY=9 SHADOWS
				#ifdef X
				profile.CONDITIONAL=A
				#endif
				   profile.INDENTED  =  A B   \s
				profile.high-quality=A
				""");

		// The flat road reads dead branches too: the profiles decide the settings a conditional is
		// evaluated against, so no condition can be asked yet. A repeated name keeps its first place.
		assertEquals(List.of("MINIMUM", "LOW", "CONDITIONAL", "INDENTED"), List.copyOf(properties.profiles().keySet()));
		assertEquals("QUALITY=9 SHADOWS", properties.profiles().get("MINIMUM"));
		assertEquals("A B", properties.profiles().get("INDENTED"));
		// A hyphen is not a word character, so that line is not a profile and shows up among the ignored keys.
		assertEquals(Map.of("profile", 1), properties.ignoredPrefixes());
	}

	@Test
	void readsCustomUniformAndVariableDeclarationsByNameAndType() throws IOException {
		ShaderProperties properties = parse("""
				uniform.float.sunAngle = frameTimeCounter * 0.5
				variable.float.helper=sin(frameTimeCounter)
				uniform.vec3.tint = vec3(1, 0.5, 0.25)
				variable.int.count = 3
				uniform.float.sunAngle = 2.0
				  uniform.bool.indented=true
				uniform.float=nope
				uniform.float.a.b=oops
				""");

		assertEquals(Map.of("sunAngle", "float", "helper", "float", "tint", "vec3", "count", "int",
				"indented", "bool"), properties.customUniformTypes());
		// "uniform.float" has no name and "uniform.float.a.b" has a dotted one, and neither is a declaration.
		assertEquals(Map.of("uniform", 2), properties.ignoredPrefixes());
	}

	@Test
	void readsTheMainScreenAndItsPagesWithEverySlotKept() throws IOException {
		ShaderProperties properties = parse("""
				screen = [LIGHTING] <empty> BLOOM <profile> *
				screen.LIGHTING = TORCH   <empty>  [SUB] <empty>
				screen.SUB = A_1 b2 3x
				""");

		assertEquals(List.of("", "LIGHTING", "SUB"), List.copyOf(properties.screenLayout().keySet()));
		assertEquals(List.of(new ScreenToken.Link("LIGHTING"), new ScreenToken.Blank(), new ScreenToken.Name("BLOOM"),
				new ScreenToken.Profiles(), new ScreenToken.Rest()), properties.screenLayout().get(""));
		// A run of spaces is one separator here.
		assertEquals(List.of(new ScreenToken.Name("TORCH"), new ScreenToken.Blank(), new ScreenToken.Link("SUB"),
				new ScreenToken.Blank()), properties.screenLayout().get("LIGHTING"));
		// "3x" starts with a digit: kept as a slot, and kept out of the census of option names.
		assertEquals(List.of(new ScreenToken.Name("A_1"), new ScreenToken.Name("b2"), new ScreenToken.Name("3x")),
				properties.screenLayout().get("SUB"));
		assertEquals(java.util.Set.of("BLOOM", "TORCH", "A_1", "b2"), properties.screenTokens());
	}

	@Test
	void aTabDoesNotSeparateTwoScreenTokens() throws IOException {
		ShaderProperties properties = parse("screen=A\tB C\n");

		assertEquals(List.of(new ScreenToken.Name("A\tB"), new ScreenToken.Name("C")), properties.screenLayout().get(""));
		assertEquals(java.util.Set.of("C"), properties.screenTokens());
	}

	@Test
	void aSecondLineForAPageReplacesTheFirstButTheCensusKeepsCounting() throws IOException {
		ShaderProperties properties = parse("""
				screen.P = A B
				screen.Q = C
				screen.P = D
				screen = P Q
				""");

		// The page keeps the position of its first line, and the layout of its last.
		assertEquals(List.of("P", "Q", ""), List.copyOf(properties.screenLayout().keySet()));
		assertEquals(List.of(new ScreenToken.Name("D")), properties.screenLayout().get("P"));
		assertEquals(java.util.Set.of("A", "B", "C", "D", "P", "Q"), properties.screenTokens());
	}

	@Test
	void aScreenLineWithNothingAfterTheEqualsSignIsAnEmptyPage() throws IOException {
		ShaderProperties properties = parse("screen=\nscreen.EMPTY   =    \n");

		assertEquals(List.of(), properties.screenLayout().get(""));
		assertEquals(List.of(), properties.screenLayout().get("EMPTY"));
	}

	@Test
	void readsColumnCountsForTheMainScreenAndForPagesAndNeverMakesAPageOfThem() throws IOException {
		ShaderProperties properties = parse("""
				screen.columns = 2
				screen.LIGHTING.columns=3
				screen.HUGE.columns=99999999999999999999
				screen.ZERO.columns=0
				  screen.INDENTED.columns  =  4  \s
				screen.LIGHTING = A
				""");

		assertEquals(OptionalInt.of(2), properties.columns(""));
		assertEquals(OptionalInt.of(3), properties.columns("LIGHTING"));
		assertEquals(OptionalInt.of(4), properties.columns("INDENTED"));
		// A count no screen could be laid out in is dropped and the line is still consumed.
		assertEquals(OptionalInt.empty(), properties.columns("HUGE"));
		assertEquals(OptionalInt.empty(), properties.columns("ZERO"));
		assertEquals(List.of("LIGHTING"), List.copyOf(properties.screenLayout().keySet()));
		assertEquals(Map.of(), properties.ignoredPrefixes());
	}

	@Test
	void aColumnsLineWithMoreThanANumberIsAPageNamedColumns() throws IOException {
		ShaderProperties properties = parse("screen.columns = 2 3\nscreen.P.columns = x\n");

		// Quirk pinned as it stands: only a lone number is a column count, anything else after the sign
		// is read as the tokens of a page, here one nobody can reach. The second line has no page name
		// pattern that matches "P.columns" as a page, so it is counted among the ignored keys.
		assertEquals(List.of(new ScreenToken.Name("2"), new ScreenToken.Name("3")),
				properties.screenLayout().get("columns"));
		assertEquals(OptionalInt.empty(), properties.columns(""));
		assertEquals(Map.of("screen", 1), properties.ignoredPrefixes());
	}

	@Test
	void aPageNameThatIsNotAWordIsNotAPage() throws IOException {
		ShaderProperties properties = parse("screen.my-page = A\nscreen.a.b = B\n");

		assertEquals(Map.of(), properties.screenLayout());
		assertEquals(Map.of("screen", 2), properties.ignoredPrefixes());
	}

	@Test
	void collectsSlidersAcrossLinesAndKeepsOnlyNames() throws IOException {
		ShaderProperties properties = parse("sliders = A B  C\n  sliders=D <empty> [P] 9x E\nsliders=\n");

		assertEquals(List.of("A", "B", "C", "D", "E"), properties.sliders());
	}

	@Test
	void takesTheLastIrisFeatureLineOfEachKindAndAnswersCaseInsensitively() throws IOException {
		ShaderProperties properties = parse("""
				iris.features.required = CUSTOM_IMAGES SSBO
				iris.features.optional = ENTITY_TRANSLUCENT
				iris.features.required=COMPUTE_SHADERS   BLOCK_EMISSION_ATTRIBUTE
				""");

		assertEquals(List.of("COMPUTE_SHADERS", "BLOCK_EMISSION_ATTRIBUTE"), properties.requiredFeatures());
		assertTrue(properties.declares("compute_shaders"));
		assertTrue(properties.declares("Entity_Translucent"));
		assertFalse(properties.declares("CUSTOM_IMAGES"));
		assertFalse(properties.declares(""));
	}

	@Test
	void countsAKeyNothingReadsByItsFirstSegmentAndSaysNothingOfAnUnkeyedLine() throws IOException {
		ShaderProperties properties = parse("""
				progam.world0/composite.enabled = false
				progam.world0/final.enabled = false
				program.composite = false
				mystery=1
				mystery.sub = 2
				  spaced.key=1
				# comment.key=1
				plain text without a separator
				key:with:colons = 1
				9lives=1
				_under.score=1
				trailing.
				keyed =2
				=novalue
				""");

		assertEquals(Map.of("_under", 1, "mystery", 2, "progam", 2, "program", 1, "spaced", 1, "trailing", 1),
				properties.ignoredPrefixes());
		// Read in name order whatever the file's order was.
		assertEquals(List.of("_under", "mystery", "progam", "program", "spaced", "trailing"),
				List.copyOf(properties.ignoredPrefixes().keySet()));
	}

	@Test
	void aValueIsEverythingAfterTheFirstEqualsSignAndAnEndOfLineCommentIsPartOfIt() throws IOException {
		ShaderProperties properties = parse("""
				sun=true # not a comment
				moon=false
				stars=1//x
				profile.P=A=B C=D
				""");

		// "true # not a comment" is not one of the four words, so the line is not understood and is
		// counted, which is how a pack's author finds it.
		assertEquals(Map.of("sun", 1, "stars", 1), properties.ignoredPrefixes());
		assertEquals("A=B C=D", properties.profiles().get("P"));
	}

	@Test
	void keepsALineNothingRecognisesAmongTheIgnoredKeysAndConsumesWhatItReads() throws IOException {
		ShaderProperties properties = parse("""
				program.a.enabled = X
				blend.gbuffers_water = SRC_ALPHA ONE ZERO ONE
				blend.gbuffers_water.colortex1 = off
				alphaTest.gbuffers_terrain = GREATER 0.1
				endFlashShadows = true
				rain.depth = true
				shadowTerrain = false
				shadowEntities = 1
				dhShadow.enabled = false
				shadow.culling = reversed
				shadow.enabled = 0
				separateAo = whatever
				breaksAnisotropy = yes
				oldHandLight = maybe
				oldLighting = true
				size.buffer.colortex1 = 0.5 0.5
				sun = false
				clouds = fast
				weather = true false
				particles.ordering = mixed
				particles.before.deferred = true
				flip.composite1.colortex0 = true
				texture.noise = tex/noise.png
				texture.composite.colortex4 = tex/lut.png
				customTexture.mine = tex/x.png
				image.img1 = none RGBA RGBA8 UNSIGNED_BYTE false false 16 16
				bufferObject.0 = 1024
				""");

		assertEquals(Map.of(), properties.ignoredPrefixes());
		assertEquals(2, properties.blendCount());
	}

	@Test
	void countsALineWhoseValueThisEngineCannotReadAmongTheKeysNothingReads() throws IOException {
		ShaderProperties properties = parse("""
				rain.depth=maybe
				shadowTerrain=maybe
				dhShadow.enabled=maybe
				sun=off
				moon=off
				clouds=puffy
				weather=maybe maybe
				particles.ordering=
				particles.before.deferred=false
				flip.composite1.colortex0=maybe
				shadow.enabled=maybe
				""");

		// Each of these is a line a pack wrote and nothing here acts on: not honoured and not silent.
		assertEquals(Map.of("rain", 1, "shadowTerrain", 1, "dhShadow", 1, "sun", 1, "moon", 1, "clouds", 1,
				"weather", 1, "particles", 2, "flip", 1, "shadow", 1), properties.ignoredPrefixes());
	}

	/**
	 * DIAGNOSTIC GAP, pinned as it stands: the census of keys nothing reads recognises a key only when
	 * the equals sign, or a dot, follows its first word directly. A pack that writes {@code sun = off}
	 * with spaces, which is a great many of them, gets the same silence from a refused value that the
	 * census exists to break: {@code sun=off} is counted and {@code sun = off} is not. What the engine
	 * does with the line is unchanged; only the report of it is missing.
	 */
	@Test
	void knownBug_aRefusedLineWithSpacesBeforeTheEqualsSignIsNotCounted() throws IOException {
		ShaderProperties spaced = parse("""
				sun = off
				moon = off
				clouds = puffy
				weather = maybe maybe
				shadowTerrain = maybe
				mystery = 1
				""");
		ShaderProperties tight = parse("sun=off\nmoon=off\nclouds=puffy\nweather=maybe maybe\nshadowTerrain=maybe\nmystery=1\n");

		assertEquals(Map.of(), spaced.ignoredPrefixes());
		assertEquals(Map.of("sun", 1, "moon", 1, "clouds", 1, "weather", 1, "shadowTerrain", 1, "mystery", 1),
				tight.ignoredPrefixes());
	}

	@Test
	void readsARepresentativePackFileEndToEnd() throws IOException {
		ShaderProperties properties = parse("""
				# Realistic slice of a pack's shaders.properties
				version.1.21=1
				profile.LOW = SHADOWS=off !BLOOM QUALITY=1
				profile.HIGH = profile.LOW SHADOWS BLOOM QUALITY=3
				screen = <profile> [POST] <empty> SHADOWS *
				screen.POST = BLOOM QUALITY \\
				    EXPOSURE
				screen.POST.columns = 2
				sliders = QUALITY EXPOSURE
				iris.features.required = CUSTOM_IMAGES
				uniform.float.wetness = smooth(rainStrength, 5, 5)
				variable.float.day = clamp(sunAngle * 2, 0, 1)
				program.composite1.enabled = BLOOM
				blend.gbuffers_water = SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO
				#ifdef MC_VERSION
				sun = false
				#endif
				""");

		assertEquals(List.of("LOW", "HIGH"), List.copyOf(properties.profiles().keySet()));
		assertEquals(List.of(new ScreenToken.Profiles(), new ScreenToken.Link("POST"), new ScreenToken.Blank(),
				new ScreenToken.Name("SHADOWS"), new ScreenToken.Rest()), properties.screenLayout().get(""));
		assertEquals(List.of(new ScreenToken.Name("BLOOM"), new ScreenToken.Name("QUALITY"),
				new ScreenToken.Name("EXPOSURE")), properties.screenLayout().get("POST"));
		assertEquals(OptionalInt.of(2), properties.columns("POST"));
		assertEquals(List.of("QUALITY", "EXPOSURE"), properties.sliders());
		assertEquals(List.of("CUSTOM_IMAGES"), properties.requiredFeatures());
		assertEquals(Map.of("wetness", "float", "day", "float"), properties.customUniformTypes());
		assertEquals(Map.of("version", 1), properties.ignoredPrefixes());
		assertEquals(2, properties.directiveCount());
		assertEquals(1, properties.continuationCount());
		assertEquals(1, properties.blendCount());
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void aPackWhosePropertiesFileHasADifferentCaseIsStillRead(Shape shape) throws IOException {
		Path packPath = shape.build(this.temp, "cased", Map.of("shaders/Shaders.Properties", "profile.A=X\n"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			ShaderProperties properties = ShaderProperties.parse(source);

			// The lookup falls back to matching the name ignoring case, in either shape.
			assertTrue(properties.present());
			assertEquals(Map.of("A", "X"), properties.profiles());
		}
	}
}
