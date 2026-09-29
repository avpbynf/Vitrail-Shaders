package dev.vitrail.pack.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.program.ProgramSet.ProgramKey;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.ShaderPackSource;
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
 * Holds which files of a pack are entry points, in which directory, under which name and stage: the
 * extension decides the stage, the directory has to be a dimension directory, and the base name has to be
 * a program name. What is rejected is counted and never dropped in silence.
 */
class ProgramSetTest {

	@TempDir
	Path temp;

	private Map<String, String> files(String... names) {
		Map<String, String> files = new LinkedHashMap<>();
		for (String name : names) {
			files.put("shaders/" + name, "// " + name + "\n");
		}

		return files;
	}

	private ProgramSet enumerate(Shape shape, Map<String, String> files) throws IOException {
		Path packPath = shape.build(Files.createTempDirectory(this.temp, "packs"), "pack", files);
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			return ProgramSet.enumerate(source, DimensionSet.discover(source));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void listsEveryEntryPointOfARootAndADimensionInFileOrder(Shape shape) throws IOException {
		ProgramSet set = enumerate(shape, files(
				"gbuffers_terrain.vsh", "gbuffers_terrain.fsh", "composite.fsh", "composite1.fsh", "final.fsh",
				"world0/composite.fsh", "world-1/gbuffers_basic.fsh", "shadow.vsh", "deferred_a.csh"));

		assertEquals(9, set.count());
		assertEquals(List.of("composite.fsh", "composite1.fsh", "deferred_a.csh", "final.fsh",
				"gbuffers_terrain.fsh", "gbuffers_terrain.vsh", "shadow.vsh", "world-1/gbuffers_basic.fsh",
				"world0/composite.fsh"), set.keys().stream().map(ProgramKey::file).toList());

		ProgramKey terrain = set.keys().get(4);
		assertEquals(new ProgramKey("", terrain.name(), ProgramStage.FRAGMENT, "gbuffers_terrain.fsh"), terrain);
		assertEquals("gbuffers_terrain", terrain.name().baseName());

		ProgramKey compute = set.keys().get(2);
		assertEquals(ProgramStage.COMPUTE, compute.stage());
		assertEquals("deferred", compute.name().baseName());
		assertEquals("a", compute.name().computeSuffix());
	}

	@Test
	void countsByDimensionByStageAndByDistinctName() throws IOException {
		ProgramSet set = enumerate(Shape.DIRECTORY, files(
				"composite.fsh", "composite.vsh", "final.fsh", "world0/composite.fsh", "world0/composite.vsh",
				"world0/composite1.fsh", "world1/final.fsh", "shadow.gsh"));

		assertEquals(Map.of("(root)", 4, "world0", 3, "world1", 1), set.countByDimension());
		assertEquals(Map.of("fsh", 5, "vsh", 2, "gsh", 1), set.countByStage());
		assertEquals(4, set.distinctNames());
	}

	@Test
	void anExtensionAloneDecidesWhetherAFileCanBeAnEntryPoint() throws IOException {
		ProgramSet set = enumerate(Shape.DIRECTORY, files(
				"composite.glsl", "composite1.inc", "final.settings", "lib.glsl", "composite.txt", "composite",
				"composite.fsh"));

		assertEquals(List.of("composite.fsh"), set.keys().stream().map(ProgramKey::file).toList());
		// Not counted anywhere, being shared bodies and not misfiled programs.
		assertEquals(Map.of(), set.skippedNames());
		assertEquals(Map.of(), set.skippedDirectories());
	}

	@Test
	void rejectsADirectoryThatIsNotADimensionAndCountsIt() throws IOException {
		ProgramSet set = enumerate(Shape.DIRECTORY, files(
				"lib/composite.fsh", "lib/final.fsh", "lib/deep/composite.fsh", "world0/composite.fsh",
				"other/gbuffers_basic.vsh", "world0/lib/composite.fsh"));

		assertEquals(List.of("world0/composite.fsh"), set.keys().stream().map(ProgramKey::file).toList());
		assertEquals(Map.of("lib", 2, "lib/deep", 1, "other", 1, "world0/lib", 1), set.skippedDirectories());
	}

	@Test
	void rejectsAFileWhoseNameIsNoProgramAndCountsTheBaseName() throws IOException {
		ProgramSet set = enumerate(Shape.DIRECTORY, files(
				"gbuffers_nothing.fsh", "gbuffers_nothing.vsh", "mycomposite.fsh", "composite100.fsh",
				"final1.fsh", "gbuffers_terrain_a.csh", "world0/helper.fsh", "composite99.fsh", "setup1.csh",
				"final_a.csh", "shadowcomp2_b.csh", "gbuffers_terrain.fsh"));

		assertEquals(List.of("composite99.fsh", "final_a.csh", "gbuffers_terrain.fsh", "setup1.csh",
				"shadowcomp2_b.csh"), set.keys().stream().map(ProgramKey::file).toList());
		assertEquals(Map.of("gbuffers_nothing", 2, "mycomposite", 1, "composite100", 1, "final1", 1,
				"gbuffers_terrain_a", 1, "helper", 1), set.skippedNames());
	}

	@Test
	void takesAnUnnumberedFamilyAsSlotNoughtAndKeepsSparseSlots() throws IOException {
		ProgramSet set = enumerate(Shape.DIRECTORY, files("composite.fsh", "composite7.fsh", "composite42.fsh"));

		// In the order of the files, which is text: composite42 sorts before composite7.
		assertEquals(List.of(0, 42, 7), set.keys().stream().map(key -> key.name().slot()).toList());
	}

	@Test
	void countsADimensionDeclaredInDimensionPropertiesAsADimensionDirectory() throws IOException {
		Map<String, String> files = files("composite.fsh", "custom/composite.fsh", "unlisted/composite.fsh");
		files.put("shaders/dimension.properties", "dimension.custom = the_nether\n");

		ProgramSet set = enumerate(Shape.ZIP, files);

		assertEquals(List.of("composite.fsh", "custom/composite.fsh"),
				set.keys().stream().map(ProgramKey::file).toList());
		assertEquals(Map.of("unlisted", 1), set.skippedDirectories());
	}

	@Test
	void selectsTheFragmentAndComputeEntriesOfOneDimensionOnly() throws IOException {
		ProgramSet set = enumerate(Shape.DIRECTORY, files(
				"composite.fsh", "composite.vsh", "composite_a.csh", "final.fsh", "world0/composite.fsh",
				"world0/composite_a.csh"));

		assertEquals(List.of("composite.fsh", "final.fsh"), set.fragmentsOf("").stream().map(ProgramKey::file).toList());
		assertEquals(List.of("composite_a.csh"), set.computesOf("").stream().map(ProgramKey::file).toList());
		assertEquals(List.of("world0/composite.fsh"),
				set.fragmentsOf("world0").stream().map(ProgramKey::file).toList());
		assertEquals(List.of(), set.fragmentsOf("world1"));
	}

	@Test
	void sortsByTheCallersRankThenSlotThenFile() throws IOException {
		ProgramSet set = enumerate(Shape.DIRECTORY, files(
				"composite2.fsh", "composite.fsh", "final.fsh", "deferred.fsh", "composite10.fsh", "prepare.fsh"));

		List<ProgramKey> sorted = ProgramSet.sorted(set.keys(), family -> switch (family) {
			case "prepare" -> 0;
			case "deferred" -> 1;
			case "composite" -> 2;
			default -> 3;
		});

		assertEquals(List.of("prepare.fsh", "deferred.fsh", "composite.fsh", "composite2.fsh", "composite10.fsh",
				"final.fsh"), sorted.stream().map(ProgramKey::file).toList());
	}

	@Test
	void handsBackListsNobodyCanChange() throws IOException {
		ProgramSet set = enumerate(Shape.DIRECTORY, files("composite.fsh", "lib/x.fsh", "bogus.fsh"));

		assertThrows(UnsupportedOperationException.class, () -> set.keys().clear());
		// The counts come back as fresh maps, and the caller may do what it likes with them.
		set.skippedNames().clear();
		assertEquals(Map.of("bogus", 1), set.skippedNames());
		assertEquals(Map.of("lib", 1), set.skippedDirectories());
	}

	@Test
	void aPackWithNoEntryPointHasAnEmptySet() throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/lib/a.glsl", "x");
		ProgramSet set = enumerate(Shape.DIRECTORY, files);

		assertEquals(0, set.count());
		assertEquals(0, set.distinctNames());
		assertEquals(Map.of(), set.countByDimension());
	}
}
