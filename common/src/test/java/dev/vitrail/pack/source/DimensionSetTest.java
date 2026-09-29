package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds which directories of a pack are dimension directories and which folder draws which world: the
 * conventional {@code world0} names when the pack says nothing, and only what {@code dimension.properties}
 * says when it says anything.
 */
class DimensionSetTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final String END = "minecraft:the_end";

	@TempDir
	Path temp;

	private DimensionSet discover(Shape shape, Map<String, String> files) throws IOException {
		Path packs = Files.createTempDirectory(this.temp, "packs");
		Path packPath = shape.build(packs, "pack", files);
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			return DimensionSet.discover(source);
		}
	}

	private DimensionSet discover(String properties, String... folders) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/composite.fsh", "x");
		for (String folder : folders) {
			files.put("shaders/" + folder + "/", "");
		}

		if (properties != null) {
			files.put("shaders/dimension.properties", properties);
		}

		return discover(Shape.DIRECTORY, files);
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void withNoFileTheConventionalFoldersAnswerForTheirOwnWorldsAndTheOverworldForEveryOther(Shape shape)
			throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/world0/composite.fsh", "x");
		files.put("shaders/world-1/composite.fsh", "x");
		files.put("shaders/world1/composite.fsh", "x");
		files.put("shaders/world7/composite.fsh", "x");
		files.put("shaders/lib/a.glsl", "x");
		files.put("shaders/worldly/a.fsh", "x");
		DimensionSet set = discover(shape, files);

		assertEquals(List.of("world-1", "world0", "world1", "world7"), set.names());
		assertFalse(set.hasDimensionProperties());
		assertEquals("world0", set.place(OVERWORLD));
		assertEquals("world-1", set.place(NETHER));
		assertEquals("world1", set.place(END));
		assertEquals("world0", set.place("mod:the_aether"));
		assertTrue(set.declares(NETHER));
		assertFalse(set.declares("mod:the_aether"));
		assertTrue(set.isDimensionDirectory("world7"));
		assertFalse(set.isDimensionDirectory("lib"));
		assertFalse(set.isDimensionDirectory(""));
	}

	@Test
	void anEmptyConventionalFolderStillCountsBecauseItsExistenceIsTheQuestion() throws IOException {
		DimensionSet set = discover(null, "world0", "world-1");

		assertEquals(List.of("world-1", "world0"), set.names());
		assertEquals("world-1", set.place(NETHER));
	}

	@Test
	void withoutAnOverworldFolderAnUnnamedWorldIsDrawnFromTheRoot() throws IOException {
		DimensionSet set = discover(null, "world-1");

		assertEquals("world-1", set.place(NETHER));
		assertEquals("", set.place(OVERWORLD));
		assertEquals("", set.place("mod:other"));
		assertFalse(set.declares(OVERWORLD));
	}

	@Test
	void aPackWithNoDimensionFolderAtAllHasNoDimension() throws IOException {
		DimensionSet set = discover(null);

		assertEquals(List.of(), set.names());
		assertEquals("", set.place(OVERWORLD));
	}

	@Test
	void readsTheFoldersAWorldIsDrawnFromOutOfDimensionPropertiesAndOnlyThose() throws IOException {
		DimensionSet set = discover("""
				dimension.overworld = minecraft:overworld
				dimension.hell=the_nether  mod:extra
				dimension.sky-lands = the_end
				""", "world0", "hell");

		assertTrue(set.hasDimensionProperties());
		// Declared folders and conventional ones on disk are both dimension directories, sorted.
		assertEquals(List.of("hell", "overworld", "sky-lands", "world0"), set.names());
		assertEquals("overworld", set.place(OVERWORLD));
		// The short spelling and the qualified one are one world, as under Iris.
		assertEquals("hell", set.place(NETHER));
		assertEquals("hell", set.place("mod:extra"));
		assertEquals("sky-lands", set.place(END));
		// Not layered over the convention: world0 exists and is not mapped to anything.
		assertEquals("", set.place("mod:never_named"));
	}

	@Test
	void aCatchAllFolderTakesEveryWorldNoOneNamed() throws IOException {
		DimensionSet set = discover("dimension.fallback = *\ndimension.nether = the_nether\n");

		assertEquals("nether", set.place(NETHER));
		assertEquals("fallback", set.place(OVERWORLD));
		assertEquals("fallback", set.place("mod:anything"));
		assertFalse(set.declares(OVERWORLD));
		assertTrue(set.declares(NETHER));
	}

	@Test
	void aFolderAssignedTwiceKeepsItsPlaceAndTakesOnlyTheLastWorlds() throws IOException {
		DimensionSet set = discover("""
				dimension.a = the_nether
				dimension.b = the_end
				dimension.a = minecraft:overworld
				""");

		assertEquals(List.of("a", "b"), set.names());
		assertEquals("a", set.place(OVERWORLD));
		assertEquals("", set.place(NETHER));
		assertEquals("b", set.place(END));
	}

	@Test
	void whenTwoFoldersClaimOneWorldTheLaterEntryDecides() throws IOException {
		DimensionSet set = discover("dimension.first = the_end\ndimension.second = the_end\n");

		assertEquals("second", set.place(END));
	}

	@Test
	void joinsAContinuedListOfWorldsAndReadsOnlyTheEnginesOwnConditionals() throws IOException {
		DimensionSet set = discover("""
				dimension.hell = the_nether \\
				    mod:one \\
				    mod:two
				#ifdef MC_VERSION
				dimension.live = the_end
				#endif
				#ifdef ANY_SETTING_OF_THE_PACK
				dimension.dead = minecraft:overworld
				#endif
				""");

		assertEquals("hell", set.place("mod:two"));
		assertEquals("hell", set.place("mod:one"));
		assertEquals("live", set.place(END));
		assertEquals(List.of("hell", "live"), set.names());
	}

	@Test
	void anyLiveKeyAtAllSuppressesTheConventionAndAFileOfCommentsDoesNot() throws IOException {
		assertFalse(discover("# only a comment\n\n! and another\n", "world0").hasDimensionProperties());
		assertEquals("world0", discover("# only a comment\n", "world0").place(OVERWORLD));

		DimensionSet unrelated = discover("something.else = 1\n", "world0");
		assertTrue(unrelated.hasDimensionProperties());
		assertEquals(List.of("world0"), unrelated.names());
		assertEquals("", unrelated.place(OVERWORLD));
		assertFalse(unrelated.declares(OVERWORLD));
	}

	@Test
	void aWorldSpellingIsNotChangedWhenItAlreadyHasANamespaceOrIsTheCatchAll() throws IOException {
		DimensionSet set = discover("dimension.x = mod:zone  plain\n");

		assertEquals("x", set.place("mod:zone"));
		assertEquals("x", set.place("minecraft:plain"));
	}
}
