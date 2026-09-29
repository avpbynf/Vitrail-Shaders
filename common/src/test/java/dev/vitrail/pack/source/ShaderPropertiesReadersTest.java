package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.BufferObject;
import dev.vitrail.pack.model.ImageInformation;
import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.source.ShaderProperties.BlendDirective;
import dev.vitrail.pack.source.ShaderProperties.CloudSetting;
import dev.vitrail.pack.source.ShaderProperties.CustomUniform;
import dev.vitrail.pack.source.ShaderProperties.FlipDirective;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds every reader of {@code shaders.properties} that runs against the pack's settings: each one
 * walks the file with the conditionals decided and answers one family of directives.
 * <p>
 * The shape of the tests follows the shape of the rule. A directive is read on the LIVE lines, so each
 * family is asked once with its line in a branch the settings take and once in a branch they do not;
 * a default is what a pack that says nothing gets; and a word the family cannot read is what a pack
 * that mistypes one gets, which differs between families and is the part most likely to be
 * regularised by accident.
 */
class ShaderPropertiesReadersTest {

	private static final Map<String, String> NONE = Map.of();

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

	private static OptionIndex index(String... lines) {
		OptionIndex.Reader reader = new OptionIndex.Reader();
		reader.read("settings.glsl", List.of(lines));

		return reader.index();
	}

	private static Map<String, String> defines(String... pairs) {
		Map<String, String> table = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			table.put(pairs[i], pairs[i + 1]);
		}

		return table;
	}

	/** A file that carries {@code line} live only where {@code SWITCH} is defined, and dead otherwise. */
	private static String gated(String line) {
		return "#ifdef SWITCH\n" + line + "\n#endif\n";
	}

	// --- program.NAME.enabled -------------------------------------------------------------------

	@Test
	void readsWhichProgramsAreSwitchedOffByAnExpressionOverThePacksOwnSwitches() throws IOException {
		ShaderProperties properties = parse("""
				program.composite.enabled = BLOOM
				program.world0/composite1.enabled = !BLOOM && FOG
				program.world-1/composite1.enabled = true
				program.final.enabled =
				program.deferred.enabled = LUT
				program.deferred1.enabled = NEVER_DECLARED
				program.deferred2.enabled = (BLOOM || FOG)
				program.deferred3.enabled = ((
				program.deferred4.enabled = false
				program.deferred5.enabled = !!!true
				program.deferred6.enabled = BLOOM & FOG | LUT
				program.deferred7.enabled = shadowHardwareFiltering
				program.deferred8.enabled = 0
				program.deferred9.enabled =    BLOOM   &&   !  FOG
				program.deferred10.enabled = BLOOM FOG
				""");
		OptionIndex options = index(
				"#define BLOOM",
				"//#define FOG",
				"#define LUT 0 //[0 1]",
				"const bool shadowHardwareFiltering = true;",
				"#ifdef BLOOM",
				"#ifdef shadowHardwareFiltering");
		Map<String, String> table = defines("BLOOM", "");

		Map<String, Boolean> toggles = properties.programToggles(table, options);

		// A switch the pack declares answers with its state and everything else answers true, a valued
		// setting and a name nobody declared among them. An empty value and a value that cannot be read
		// leave the program on: this file is read fail open.
		Map<String, Boolean> expected = new LinkedHashMap<>();
		expected.put("composite", true);
		expected.put("world0/composite1", false);
		expected.put("world-1/composite1", true);
		expected.put("final", true);
		expected.put("deferred", true);
		expected.put("deferred1", true);
		expected.put("deferred2", true);
		expected.put("deferred3", true);
		expected.put("deferred4", false);
		expected.put("deferred5", false);
		expected.put("deferred6", true);
		// A constant is a switch only where the index offers it, and its state is the value in the table,
		// which here has no entry for it: false.
		expected.put("deferred7", false);
		expected.put("deferred8", false);
		expected.put("deferred9", true);
		// Two names with no operator between them do not parse.
		expected.put("deferred10", true);
		assertEquals(expected, toggles);
		assertEquals(List.copyOf(expected.keySet()), List.copyOf(toggles.keySet()));
		assertEquals(Set.of("world0/composite1", "deferred4", "deferred5", "deferred7", "deferred8"),
				properties.switchedOff(table, options));

		// With the constant set, and the toggles flipped.
		Map<String, Boolean> other = properties.programToggles(defines("FOG", "", "shadowHardwareFiltering", "true"), options);
		assertEquals(false, other.get("composite"));
		assertEquals(true, other.get("world0/composite1"));
		assertEquals(true, other.get("deferred7"));
		assertEquals(false, other.get("deferred9"));
	}

	@Test
	void aProgramToggleIsKeyedByTheFolderPathAsWrittenAndNeverByTheBareName() throws IOException {
		ShaderProperties properties = parse("""
				program.world0/composite1.enabled = false
				program.composite2.enabled = false
				""");

		assertEquals(Set.of("world0/composite1", "composite2"), properties.switchedOff(NONE, index()));
		assertFalse(properties.switchedOff(NONE, index()).contains("composite1"));
		assertEquals(Map.of("world0/composite1", "false", "composite2", "false"), properties.programConditions(NONE));
	}

	@Test
	void keepsTheExpressionAsWrittenTrimmedAndTheLastLiveLineOfAKeyInTheFirstPlace() throws IOException {
		ShaderProperties properties = parse("""
				program.a.enabled =   X && Y  \s
				program.b.enabled = 1
				program.a.enabled = Z
				""");

		assertEquals(List.of("a", "b"), List.copyOf(properties.programConditions(NONE).keySet()));
		assertEquals("Z", properties.programConditions(NONE).get("a"));
	}

	@Test
	void readsAProgramLineOnlyWhereItsConditionalIsLive() throws IOException {
		ShaderProperties properties = parse(gated("program.composite.enabled = false"));

		assertEquals(Set.of(), properties.switchedOff(NONE, index()));
		assertEquals(Set.of("composite"), properties.switchedOff(defines("SWITCH", ""), index()));
	}

	@Test
	void readsAProgramLineUnderADefineTheFileWritesForItself() throws IOException {
		ShaderProperties properties = parse("""
				#define OFF_PASS
				#ifdef OFF_PASS
				program.composite.enabled = false
				#endif
				""");

		assertEquals(Set.of("composite"), properties.switchedOff(NONE, index()));
	}

	@Test
	void anExpressionNestedPastTheBudgetIsUnreadableAndLeavesTheProgramOn() throws IOException {
		String justInside = "!".repeat(63) + "true";
		String justPast = "!".repeat(65) + "true";
		ShaderProperties properties = parse("program.inside.enabled = " + justInside + "\n"
				+ "program.past.enabled = " + justPast + "\n");

		// Sixty three prefix operators is an odd count of negations: false. Sixty five is past the budget,
		// so it is not read at all, and a program is never dropped for want of being understood.
		assertEquals(Map.of("inside", false, "past", true), properties.programToggles(NONE, index()));
	}

	// --- uniforms -------------------------------------------------------------------------------

	@Test
	void readsCustomUniformsInFileOrderWithTheSymbolsSubstituted() throws IOException {
		ShaderProperties properties = parse("""
				uniform.float.a = 1 + BASE
				variable.int.b = MODE * 2
				#define K 4
				uniform.float.c = K * sunAngle
				uniform.float.d = sunPosition.x + x
				uniform.float.e = BLANK + SELF + UNKNOWN
				uniform.float.a = 9
				#ifdef SWITCH
				uniform.float.gated = 1
				#endif
				""");

		List<CustomUniform> read = properties.customUniforms(defines("BASE", "2", "MODE", "3", "x", "1",
				"BLANK", "", "SELF", "SELF"));

		// The list keeps a name written twice, both times, and a define the file makes reaches only the
		// lines after it. A component after a dot is not a name, and a symbol defined with no value,
		// with itself, or not at all is left as it was so that the reader can say which one it did not know.
		assertEquals(List.of(
				new CustomUniform(true, "float", "a", "1 + 2"),
				new CustomUniform(false, "int", "b", "3 * 2"),
				new CustomUniform(true, "float", "c", "4 * sunAngle"),
				new CustomUniform(true, "float", "d", "sunPosition.x + 1"),
				new CustomUniform(true, "float", "e", "BLANK + SELF + UNKNOWN"),
				new CustomUniform(true, "float", "a", "9")), read);
		assertEquals(7, properties.customUniforms(defines("SWITCH", "1")).size());
	}

	// --- textures -------------------------------------------------------------------------------

	@Test
	void readsCustomTexturesByKeyOnLiveLinesWithTheLastOfAKeyWinning() throws IOException {
		ShaderProperties properties = parse("""
				texture.noise = tex/noise.png
				texture.composite.colortex4 = tex/lut.png
				customTexture.mine =   tex/one.png  \s
				texture.composite.colortex4 = tex/lut2.png
				texture.deferred.colortex5 = tex/vol.dat TEXTURE_3D RGBA8 16 16 16 RGBA UNSIGNED_BYTE
				texturefoo = x
				""" + gated("customTexture.gated = tex/gated.png"));

		Map<String, String> off = properties.customTextures(NONE);
		assertEquals(List.of("texture.composite.colortex4", "customTexture.mine", "texture.deferred.colortex5"),
				List.copyOf(off.keySet()));
		assertEquals("tex/lut2.png", off.get("texture.composite.colortex4"));
		assertEquals("tex/one.png", off.get("customTexture.mine"));
		// The value is left exactly as written, whatever it means.
		assertEquals("tex/vol.dat TEXTURE_3D RGBA8 16 16 16 RGBA UNSIGNED_BYTE", off.get("texture.deferred.colortex5"));

		assertTrue(properties.customTextures(defines("SWITCH", "1")).containsKey("customTexture.gated"));
	}

	@Test
	void readsTheNoiseTexturePathAsTheLastLiveLine() throws IOException {
		ShaderProperties properties = parse("""
				texture.noise = tex/first.png
				texture.noise =    tex/second.png   \s
				""" + gated("texture.noise = tex/gated.png"));

		assertEquals(Optional.of("tex/second.png"), properties.noiseTexturePath(NONE));
		assertEquals(Optional.of("tex/gated.png"), properties.noiseTexturePath(defines("SWITCH", "1")));
		assertEquals(Optional.empty(), parse("sun=false\n").noiseTexturePath(NONE));
	}

	// --- the booleans of the sky, the weather, the particles ------------------------------------

	@Test
	void readsWhichPiecesOfTheSkyAreStillDrawn() throws IOException {
		ShaderProperties none = parse("");
		assertEquals(new ShaderProperties.SkyElements(true, true, true, true, CloudSetting.DEFAULT),
				none.skyElements(NONE));

		ShaderProperties properties = parse("""
				sun = false
				moon = 0
				stars = maybe
				sky = true
				clouds = Fast
				""");
		ShaderProperties.SkyElements read = properties.skyElements(NONE);

		// A word outside true, false, 1, 0 leaves the piece as it was, so the moon's 0 is a no and the
		// stars' "maybe" is nothing. The cloud words are the game's three, in any case.
		assertEquals(new ShaderProperties.SkyElements(false, false, true, true, CloudSetting.FAST), read);
		assertTrue(read.allows("stars"));
		assertFalse(read.allows("sun"));
		assertTrue(read.allows("anythingElse"));
	}

	@Test
	void readsTheSkyOnLiveLinesOnly() throws IOException {
		ShaderProperties properties = parse("""
				#ifdef SWITCH
				sun = false
				clouds = off
				#else
				moon = false
				clouds = fancy
				#endif
				""");

		assertEquals(new ShaderProperties.SkyElements(true, false, true, true, CloudSetting.FANCY),
				properties.skyElements(NONE));
		assertEquals(new ShaderProperties.SkyElements(false, true, true, true, CloudSetting.OFF),
				properties.skyElements(defines("SWITCH", "1")));
	}

	@Test
	void readsTheWeatherAsTwoWordsSplitOnASingleSpace() throws IOException {
		assertEquals(new ShaderProperties.Weather(true, true), parse("").weather(NONE));
		assertEquals(new ShaderProperties.Weather(false, true), parse("weather = false\n").weather(NONE));
		assertEquals(new ShaderProperties.Weather(true, false), parse("weather = true false\n").weather(NONE));
		assertEquals(new ShaderProperties.Weather(true, false), parse("weather = maybe 0\n").weather(NONE));
		// A double space leaves an empty word in the middle, which reads as nothing about the splashes.
		assertEquals(new ShaderProperties.Weather(false, true), parse("weather = false  false\n").weather(NONE));
		assertEquals(new ShaderProperties.Weather(false, false),
				parse("weather = true true\nweather = 0 0\n").weather(NONE));
	}

	@Test
	void readsWhereTheParticlesAreDrawnAndFoldsTheOlderSpellingIn() throws IOException {
		assertEquals(Optional.empty(), parse("").particleOrdering(NONE));
		assertEquals(Optional.of("mixed"), parse("particles.ordering = MIXED\n").particleOrdering(NONE));
		assertEquals(Optional.of("before"), parse("particles.before.deferred = true\n").particleOrdering(NONE));
		assertEquals(Optional.empty(), parse("particles.before.deferred = false\n").particleOrdering(NONE));
		// The explicit ordering beats the older spelling wherever the pack writes both, in either order.
		assertEquals(Optional.of("after"),
				parse("particles.before.deferred = true\nparticles.ordering = after\n").particleOrdering(NONE));
		assertEquals(Optional.of("after"),
				parse("particles.ordering = after\nparticles.before.deferred = true\n").particleOrdering(NONE));
		assertEquals(Optional.empty(), parse("particles.ordering =\n").particleOrdering(NONE));
		assertEquals(Optional.of("mixed"),
				parse("particles.ordering = mixed\nparticles.ordering =\n").particleOrdering(NONE));
	}

	// --- the shadow map and the flags of one word -----------------------------------------------

	@Test
	void readsTheShadowCastersWithIrisDefaultsAndOnlyReadableWords() throws IOException {
		assertEquals(ShadowCasters.DEFAULT, parse("").shadowCasters(NONE));
		assertEquals(new ShadowCasters(true, true, true, false, true, false), ShadowCasters.DEFAULT);

		ShaderProperties properties = parse("""
				shadowTerrain = false
				shadowTranslucent = 0
				shadowEntities = false
				shadowPlayer = true
				shadowBlockEntities = maybe
				shadowLightBlockEntities = 1
				""");
		ShadowCasters casters = properties.shadowCasters(NONE);

		assertEquals(new ShadowCasters(false, false, false, true, true, true), casters);
		assertTrue(casters.anyFeature());
		assertTrue(casters.anyBlockEntity());
		assertFalse(casters.emittersOnly());
	}

	@Test
	void readsTheShadowCastersOnLiveLinesWhereAPackWritesTheWordInBothArms() throws IOException {
		ShaderProperties properties = parse("""
				#ifdef SWITCH
				shadowPlayer = true
				#else
				shadowPlayer = false
				#endif
				""");

		assertFalse(properties.shadowCasters(NONE).player());
		assertTrue(properties.shadowCasters(defines("SWITCH", "1")).player());
	}

	@Test
	void readsTheShadowCullingByTheFourStatesAndFallsBackToTheDefaultOnAnUnreadableWord() throws IOException {
		assertEquals(ShadowCullState.DEFAULT, parse("").shadowCull(NONE));
		assertEquals(ShadowCullState.ADVANCED, parse("shadow.culling = true\n").shadowCull(NONE));
		assertEquals(ShadowCullState.DISTANCE, parse("shadow.culling = false\n").shadowCull(NONE));
		assertEquals(ShadowCullState.SAFE_ZONE, parse("shadow.culling = reversed\n").shadowCull(NONE));
		assertEquals(ShadowCullState.SAFE_ZONE, parse("shadow.culling = safe_zone\n").shadowCull(NONE));
		// An unreadable word puts the default back and does not leave the line above standing, and the
		// words are case sensitive.
		assertEquals(ShadowCullState.DEFAULT, parse("shadow.culling = false\nshadow.culling = TRUE\n").shadowCull(NONE));
		assertEquals(ShadowCullState.DISTANCE, parse("shadow.culling = true\nshadow.culling = false\n").shadowCull(NONE));
	}

	@Test
	void readsTheFlagsWhoseUnreadableWordSitsAmongTheLinesAboveIt() throws IOException {
		// endFlashShadows, rainDepth and shadowEnabled: an unreadable word steps over, the line above stands.
		assertFalse(parse("").endFlashShadows(NONE));
		assertTrue(parse("endFlashShadows = true\nendFlashShadows = yes\n").endFlashShadows(NONE));
		assertFalse(parse("").rainDepth(NONE));
		assertTrue(parse("rain.depth = 1\nrain.depth = later\n").rainDepth(NONE));
		assertEquals(Optional.empty(), parse("").shadowEnabled(NONE));
		assertEquals(Optional.of(false), parse("shadow.enabled = false\nshadow.enabled = maybe\n").shadowEnabled(NONE));
		assertEquals(Optional.of(true), parse("shadow.enabled = 0\nshadow.enabled = 1\n").shadowEnabled(NONE));
		// And the default of dhShadow is the other way round: on unless the pack says otherwise.
		assertTrue(parse("").dhShadow(NONE));
		assertFalse(parse("dhShadow.enabled = false\n").dhShadow(NONE));
		assertFalse(parse("dhShadow.enabled = false\ndhShadow.enabled = maybe\n").dhShadow(NONE));
		assertTrue(parse("dhShadow.enabled = false\ndhShadow.enabled = true\n").dhShadow(NONE));
	}

	@Test
	void readsTheFlagsWhoseLastLiveLineDecidesEvenWhenItCannotBeRead() throws IOException {
		// separateAo and its three siblings: the last line stands for the key, as Iris loads the file into
		// a map, so a word this cannot read puts the answer back to the default and does not keep the line above.
		assertFalse(parse("").separateAo(NONE));
		assertTrue(parse("separateAo = true\n").separateAo(NONE));
		assertFalse(parse("separateAo = true\nseparateAo = yes\n").separateAo(NONE));

		assertFalse(parse("").breaksAnisotropy(NONE));
		assertTrue(parse("breaksAnisotropy = 1\n").breaksAnisotropy(NONE));
		assertFalse(parse("breaksAnisotropy = 1\nbreaksAnisotropy = ?\n").breaksAnisotropy(NONE));

		assertFalse(parse("").oldLighting(NONE));
		assertTrue(parse("oldLighting = true\n").oldLighting(NONE));
		assertFalse(parse("oldLighting = true\noldLighting = nope\n").oldLighting(NONE));

		// oldHandLight defaults ON, so its unreadable last word puts it back on.
		assertTrue(parse("").oldHandLight(NONE));
		assertFalse(parse("oldHandLight = false\n").oldHandLight(NONE));
		assertTrue(parse("oldHandLight = false\noldHandLight = maybe\n").oldHandLight(NONE));
	}

	@Test
	void readsEveryBooleanOfTheFamilyOnLiveLinesOnly() throws IOException {
		ShaderProperties properties = parse(gated("""
				separateAo = true
				breaksAnisotropy = true
				oldLighting = true
				oldHandLight = false
				rain.depth = true
				endFlashShadows = true
				dhShadow.enabled = false
				shadow.enabled = false
				shadowTerrain = false
				shadow.culling = false"""));
		Map<String, String> on = defines("SWITCH", "1");

		assertFalse(properties.separateAo(NONE));
		assertTrue(properties.separateAo(on));
		assertTrue(properties.breaksAnisotropy(on));
		assertTrue(properties.oldLighting(on));
		assertFalse(properties.oldHandLight(on));
		assertTrue(properties.rainDepth(on));
		assertTrue(properties.endFlashShadows(on));
		assertFalse(properties.dhShadow(on));
		assertEquals(Optional.of(false), properties.shadowEnabled(on));
		assertFalse(properties.shadowCasters(on).terrain());
		assertEquals(ShadowCullState.DISTANCE, properties.shadowCull(on));

		assertTrue(properties.oldHandLight(NONE));
		assertTrue(properties.dhShadow(NONE));
		assertEquals(Optional.empty(), properties.shadowEnabled(NONE));
		assertEquals(ShadowCullState.DEFAULT, properties.shadowCull(NONE));
	}

	// --- blend, alpha, buffers, flips -----------------------------------------------------------

	@Test
	void readsTheBlendOverridesInFileOrderOnLiveLines() throws IOException {
		ShaderProperties properties = parse("""
				blend.gbuffers_water = SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO
				blend.composite.colortex1 = OFF
				blend.a.b.c = x
				blend. = y
				""" + gated("blend.gbuffers_hand = off"));

		List<BlendDirective> off = properties.blend(NONE);

		assertEquals(List.of(new BlendDirective("gbuffers_water", null, "SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO"),
				new BlendDirective("composite", "colortex1", "OFF")), off);
		assertFalse(off.get(0).off());
		assertTrue(off.get(1).off());
		assertEquals(3, properties.blend(defines("SWITCH", "1")).size());
		// The census counts them wherever they stand, gated or not.
		assertEquals(3, properties.blendCount());
	}

	@Test
	void readsTheSizeOfEachTargetAsTheLastLiveLine() throws IOException {
		ShaderProperties properties = parse("""
				size.buffer.colortex1 = 0.5 0.5
				size.buffer.colortex2 = WIDTH   HEIGHT\s
				size.buffer.colortex1 = 1.0 1.0
				""" + gated("size.buffer.colortex1 = 2.0 2.0"));

		Map<String, String> sizes = properties.sizeBuffers(NONE);

		assertEquals(Map.of("colortex1", "1.0 1.0", "colortex2", "WIDTH   HEIGHT"), sizes);
		assertEquals(List.of("colortex1", "colortex2"), List.copyOf(sizes.keySet()));
		assertEquals("2.0 2.0", properties.sizeBuffers(defines("SWITCH", "1")).get("colortex1"));
	}

	@Test
	void readsTheFlipDirectivesThatCarryABoolean() throws IOException {
		ShaderProperties properties = parse("""
				flip.composite1.colortex0 = true
				flip.composite2.colortex1 = 0
				flip.composite3.colortex2 = maybe
				flip.final.colortex0.extra = true
				""");

		assertEquals(List.of(new FlipDirective("composite1", "colortex0", true),
				new FlipDirective("composite2", "colortex1", false)), properties.flips(NONE));
	}

	@Test
	void readsTheAlphaTestOverridesByProgramAndDropsWhatItCannotRead() throws IOException {
		ShaderProperties properties = parse("""
				alphaTest.gbuffers_terrain = GREATER 0.1
				alphaTest.gbuffers_water = off
				alphaTest.gbuffers_hand = GL_LESS 0.75
				alphaTest.gbuffers_block = SIDEWAYS 0.5
				alphaTest.gbuffers_item = GREATER
				alphaTest.gbuffers_entities = GREATER 0.5 trailing words
				alphaTest.gbuffers_terrain = GEQUAL 0.2
				""" + gated("alphaTest.gbuffers_gated = NEVER 0"));

		Map<String, AlphaTest> read = properties.alphaTests(NONE);

		assertEquals(List.of("gbuffers_terrain", "gbuffers_water", "gbuffers_hand", "gbuffers_entities"),
				List.copyOf(read.keySet()));
		assertEquals(new AlphaTest(AlphaTest.Function.GEQUAL, 0.2F), read.get("gbuffers_terrain"));
		assertEquals(AlphaTest.OFF, read.get("gbuffers_water"));
		assertEquals(new AlphaTest(AlphaTest.Function.LESS, 0.75F), read.get("gbuffers_hand"));
		assertEquals(new AlphaTest(AlphaTest.Function.GREATER, 0.5F), read.get("gbuffers_entities"));
		assertTrue(properties.alphaTests(defines("SWITCH", "1")).containsKey("gbuffers_gated"));
	}

	// --- images and buffer objects --------------------------------------------------------------

	@Test
	void readsTheStorageImagesInFileOrderWithASettingNameAsASizeAndNamesTheOnesItDrops() throws IOException {
		ShaderProperties properties = parse("""
				image.img1 = imgSampler RGBA RGBA8 UNSIGNED_BYTE false false 16 16
				image.vol = none RGBA RGBA16F HALF_FLOAT true false VOX VOX 32
				image.bad = none RGBA NOTAFORMAT UNSIGNED_BYTE false false 16 16
				image.short = none RGBA RGBA8
				#define LOCAL 8
				image.local = none RGBA RGBA8 UNSIGNED_BYTE false false LOCAL LOCAL
				image.rel = none RGBA RGBA8 UNSIGNED_BYTE false true 0.5 0.5
				""");

		ImageInformation.Reading reading = properties.imageDirectives(defines("VOX", "24"));

		assertEquals(List.of("img1", "vol", "local", "rel"),
				reading.images().stream().map(ImageInformation::name).toList());
		assertEquals(Optional.of("imgSampler"), reading.images().get(0).sampler());
		assertEquals(Optional.empty(), reading.images().get(1).sampler());
		assertEquals(24, reading.images().get(1).width());
		assertEquals(24, reading.images().get(1).height());
		assertEquals(32, reading.images().get(1).depth());
		assertEquals(8, reading.images().get(2).width());
		assertTrue(reading.images().get(3).relative());
		assertEquals(2, reading.dropped().size());
		assertTrue(reading.dropped().get(0).startsWith("image.bad = none RGBA NOTAFORMAT"), reading.dropped().get(0));
		assertEquals("image.short = none RGBA RGBA8: expected at least six words", reading.dropped().get(1));
		assertEquals(Set.of("imgSampler"), properties.imageSamplers(defines("VOX", "24")));
	}

	@Test
	void dropsASeventeenthStorageImageAndSaysWhy() throws IOException {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 17; i++) {
			text.append("image.i").append(i).append(" = none RGBA RGBA8 UNSIGNED_BYTE false false 4 4\n");
		}

		ImageInformation.Reading reading = parse(text.toString()).imageDirectives(NONE);

		assertEquals(16, reading.images().size());
		assertEquals(List.of("image.i16: only 16 storage images are allowed"), reading.dropped());
	}

	@Test
	void readsTheStorageImagesOnLiveLinesOnly() throws IOException {
		ShaderProperties properties = parse(gated("image.img = none RGBA RGBA8 UNSIGNED_BYTE false false 4 4"));

		assertEquals(List.of(), properties.imageDirectives(NONE).images());
		assertEquals(1, properties.imageDirectives(defines("SWITCH", "1")).images().size());
	}

	@Test
	void readsTheStorageBuffersAndNamesTheOnesItDrops() throws IOException {
		ShaderProperties properties = parse("""
				bufferObject.0 = 1024
				bufferObject.1 = 2048 lights
				bufferObject.2 = 64 true 0.5 0.5
				bufferObject.3 = 0
				bufferObject.13 = 16
				bufferObject.4 = abc
				bufferObject.12 = 8
				bufferObject.1 = 4096 lights2
				""" + gated("bufferObject.5 = 32"));

		BufferObject.Reading reading = properties.bufferObjects(NONE);

		assertEquals(List.of(0, 1, 2, 12), reading.buffers().stream().map(BufferObject::index).toList());
		assertEquals(4096L, reading.buffers().get(1).size());
		assertEquals(Optional.of("lights2"), reading.buffers().get(1).name());
		assertTrue(reading.buffers().get(2).relative());
		assertEquals(List.of(
				"bufferObject.3 = 0: size below one disables the buffer",
				"bufferObject.13 = 16: only indices 0 to 12 are allowed",
				"bufferObject.4 = abc: size is not a number"), reading.dropped());
		assertTrue(reading.hasIndex(12));
		assertTrue(reading.hasName("lights2"));
		assertFalse(reading.hasName("lights"));
		assertEquals(5, properties.bufferObjects(defines("SWITCH", "1")).buffers().size());
	}

	// --- profiles -------------------------------------------------------------------------------

	@Test
	void expandsAProfileInReadingOrderWithTheLaterChoiceWinning() throws IOException {
		ShaderProperties properties = parse("""
				profile.LOW = SHADOWS=off !BLOOM QUALITY=1 FOG
				profile.HIGH = profile.LOW SHADOWS BLOOM QUALITY=3 profile.MISSING
				profile.ODD =    A   B=  !
				""");

		Map<String, OptionValue> low = properties.expandProfile("LOW");
		assertEquals(List.of("SHADOWS", "BLOOM", "QUALITY", "FOG"), List.copyOf(low.keySet()));
		assertEquals("off", low.get("SHADOWS").text());
		assertFalse(low.get("BLOOM").asBoolean());
		assertTrue(low.get("BLOOM").isBoolean());
		assertEquals("1", low.get("QUALITY").text());
		assertSame(OptionValue.on(), low.get("FOG"));

		// HIGH pulls LOW in first and then overrides it: the last choice for a name wins, and a switch
		// written bare turns a value written "off" into a boolean.
		Map<String, OptionValue> high = properties.expandProfile("HIGH");
		assertSame(OptionValue.on(), high.get("SHADOWS"));
		assertSame(OptionValue.on(), high.get("BLOOM"));
		assertEquals("3", high.get("QUALITY").text());
		assertSame(OptionValue.on(), high.get("FOG"));

		assertEquals(Map.of(), properties.expandProfile("NOPE"));
		Map<String, OptionValue> odd = properties.expandProfile("ODD");
		assertSame(OptionValue.on(), odd.get("A"));
		// "B=" carries the empty value, and a lone "!" turns off a setting called the empty string.
		assertEquals("", odd.get("B").text());
		assertSame(OptionValue.off(), odd.get(""));
	}

	@Test
	void aBareProfileTokenIsOnlyKeptWhereTheCallerSaysThePackDeclaresIt() throws IOException {
		ShaderProperties properties = parse("profile.P = GTAO KNOWN !GONE V=2 profile.Q\nprofile.Q = FROMQ\n");

		Map<String, OptionValue> strict = properties.expandProfile("P", "KNOWN"::equals);

		assertEquals(List.of("KNOWN", "GONE", "V"), List.copyOf(strict.keySet()));
		assertFalse(strict.containsKey("GTAO"));
		assertFalse(strict.containsKey("FROMQ"));
		assertTrue(properties.expandProfile("P").containsKey("GTAO"));
	}

	@Test
	void stopsAProfileThatNamesItselfAndOneNestedPastTheDepthLimit() throws IOException {
		ShaderProperties cycle = parse("profile.A = X profile.A\n");
		AtomicInteger asked = new AtomicInteger();
		Map<String, OptionValue> cycled = cycle.expandProfile("A", _ -> {
			asked.incrementAndGet();
			return true;
		});
		// Depth zero through eight are read, so the profile is expanded nine times before it stops.
		assertEquals(9, asked.get());
		assertSame(OptionValue.on(), cycled.get("X"));

		StringBuilder deep = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			deep.append("profile.P").append(i).append(" = profile.P").append(i + 1).append(" MARK").append(i).append('\n');
		}

		deep.append("profile.P10 = LEAF\n");
		Map<String, OptionValue> chain = parse(deep.toString()).expandProfile("P0");

		// P0 is depth zero and P8 is depth eight, the last one read: what P9 and P10 say is never reached.
		assertTrue(chain.containsKey("MARK8"));
		assertFalse(chain.containsKey("MARK9"));
		assertFalse(chain.containsKey("LEAF"));
	}

	@Test
	void boundsTheTotalWorkOfProfilesThatNameTheNextOneManyTimes() throws IOException {
		// Eight levels of ten references each is ten to the eighth expansions, which never ends; the shared
		// budget of ten thousand is what makes it a bounded read.
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 8; i++) {
			text.append("profile.P").append(i).append(" =");
			text.append((" profile.P" + (i + 1)).repeat(10)).append('\n');
		}

		text.append("profile.P8 = LEAF\n");
		AtomicInteger leaves = new AtomicInteger();
		ShaderProperties properties = parse(text.toString());

		assertTimeoutPreemptively(Duration.ofSeconds(30), () -> properties.expandProfile("P0", _ -> {
			leaves.incrementAndGet();
			return true;
		}));

		assertTrue(leaves.get() > 0);
		assertTrue(leaves.get() <= 10_000, "leaf reads: " + leaves.get());
	}
}
