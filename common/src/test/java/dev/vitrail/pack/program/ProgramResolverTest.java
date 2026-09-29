package dev.vitrail.pack.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.ProgramFallbacks;
import dev.vitrail.pack.program.ProgramResolver.Resolution;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds which file serves each program of each dimension: the pack's own where it ships one, the parent
 * in the fallback tree where it does not, nothing where the chain ends. A dimension directory replaces the
 * root and is never layered over it, and a program the pack switched off counts as not shipped.
 */
class ProgramResolverTest {

	private static final int TOTAL = ProgramFallbacks.names().size();

	@TempDir
	Path temp;

	private ProgramResolver resolve(Map<String, String> files, Set<String> switchedOff) throws IOException {
		Map<String, String> all = new LinkedHashMap<>();
		files.forEach((name, text) -> all.put("shaders/" + name, text));
		Path packPath = Shape.DIRECTORY.build(Files.createTempDirectory(this.temp, "packs"), "pack", all);
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			DimensionSet dimensions = DimensionSet.discover(source);

			return ProgramResolver.resolve(ProgramSet.enumerate(source, dimensions), dimensions, switchedOff);
		}
	}

	private static Map<String, String> ships(String... names) {
		Map<String, String> files = new LinkedHashMap<>();
		for (String name : names) {
			files.put(name, "x");
		}

		return files;
	}

	@Test
	void servesAProgramWithTheFileOfItsOwnNameAndOthersThroughTheFallbackTree() throws IOException {
		ProgramResolver resolver = resolve(ships("gbuffers_textured_lit.fsh", "gbuffers_basic.fsh"), Set.of());

		assertEquals(Optional.of(new Resolution("gbuffers_textured_lit", "gbuffers_textured_lit",
				"gbuffers_textured_lit.fsh", 0)), resolver.lookup("", "gbuffers_textured_lit"));
		// Terrain is not shipped, so the walk goes up to the lit textured program, one step away.
		assertEquals(Optional.of(new Resolution("gbuffers_terrain", "gbuffers_textured_lit",
				"gbuffers_textured_lit.fsh", 1)), resolver.lookup("", "gbuffers_terrain"));
		// Three levels up for the water program: water, terrain, lit textured.
		assertEquals(2, resolver.lookup("", "gbuffers_water").orElseThrow().depth());
		assertEquals(Optional.of(new Resolution("gbuffers_line", "gbuffers_basic", "gbuffers_basic.fsh", 1)),
				resolver.lookup("", "gbuffers_line"));
		assertTrue(resolver.lookup("", "gbuffers_textured_lit").orElseThrow().direct());
		assertFalse(resolver.lookup("", "gbuffers_terrain").orElseThrow().direct());
	}

	@Test
	void countsWhatIsServedDirectlyThroughTheTreeAndNotAtAll() throws IOException {
		ProgramResolver resolver = resolve(ships("gbuffers_basic.fsh", "gbuffers_terrain.fsh"), Set.of());

		int direct = resolver.directCount("");
		int inherited = resolver.inheritedCount("");
		int unserved = resolver.unservedCount("");

		assertEquals(2, direct);
		assertEquals(TOTAL, direct + inherited + unserved);
		assertEquals(resolver.resolutions("").size(), direct + inherited);
		assertTrue(unserved > 0, "the shadow family and the DH programs have no root here");
		assertEquals(Optional.empty(), resolver.lookup("", "shadow"));
		assertEquals(Optional.empty(), resolver.lookup("", "dh_terrain"));
	}

	@Test
	void aVertexFileAloneServesNothing() throws IOException {
		ProgramResolver resolver = resolve(ships("gbuffers_basic.vsh", "gbuffers_basic.gsh", "gbuffers_basic.csh"),
				Set.of());

		assertEquals(0, resolver.resolutions("").size());
		assertEquals(TOTAL, resolver.unservedCount(""));
	}

	@Test
	void aDimensionDirectoryReplacesTheRootAndIsNotLayeredOverIt() throws IOException {
		ProgramResolver resolver = resolve(
				ships("gbuffers_basic.fsh", "gbuffers_terrain.fsh", "world0/gbuffers_water.fsh"), Set.of());

		// The root ships two programs and world0 ships one, and world0 answers for itself alone.
		assertEquals(2, resolver.directCount(""));
		assertEquals(1, resolver.directCount("world0"));
		assertEquals(Optional.of(new Resolution("gbuffers_water", "gbuffers_water", "world0/gbuffers_water.fsh", 0)),
				resolver.lookup("world0", "gbuffers_water"));
		assertEquals(Optional.empty(), resolver.lookup("world0", "gbuffers_terrain"));
		assertEquals(Optional.empty(), resolver.lookup("world0", "gbuffers_basic"));
		assertEquals(TOTAL - 1, resolver.unservedCount("world0"));
	}

	@Test
	void anEmptyDimensionDirectoryIsAnEmptySetAndDoesNotFallBackToTheRoot() throws IOException {
		Map<String, String> files = ships("gbuffers_basic.fsh");
		files.put("world1/", "");
		ProgramResolver resolver = resolve(files, Set.of());

		assertEquals(1, resolver.directCount(""));
		assertEquals(0, resolver.resolutions("world1").size());
		assertEquals(TOTAL, resolver.unservedCount("world1"));
	}

	@Test
	void aPlaceNobodyAskedAboutHasNoResolutionsAtAll() throws IOException {
		ProgramResolver resolver = resolve(ships("gbuffers_basic.fsh"), Set.of());

		assertEquals(0, resolver.resolutions("world-9").size());
		assertEquals(Optional.empty(), resolver.lookup("world-9", "gbuffers_basic"));
		assertEquals(TOTAL, resolver.unservedCount("world-9"));
	}

	@Test
	void aProgramSwitchedOffCountsAsNotShippedSoTheWalkCarriesOnToItsParent() throws IOException {
		Map<String, String> files = ships("gbuffers_terrain.fsh", "gbuffers_water.fsh", "world0/gbuffers_water.fsh",
				"world0/gbuffers_terrain.fsh");

		ProgramResolver resolver = resolve(files, Set.of("gbuffers_water", "world0/gbuffers_terrain"));

		assertEquals(Optional.of(new Resolution("gbuffers_water", "gbuffers_terrain", "gbuffers_terrain.fsh", 1)),
				resolver.lookup("", "gbuffers_water"));
		// The toggle is keyed by the path as written and never the bare name: the root's terrain is
		// still on, and so is world0's water, whose own toggle is a different key.
		assertEquals(Optional.of(new Resolution("gbuffers_terrain", "gbuffers_terrain", "gbuffers_terrain.fsh", 0)),
				resolver.lookup("", "gbuffers_terrain"));
		assertEquals(Optional.of(new Resolution("gbuffers_water", "gbuffers_water", "world0/gbuffers_water.fsh", 0)),
				resolver.lookup("world0", "gbuffers_water"));
		assertEquals(Optional.empty(), resolver.lookup("world0", "gbuffers_terrain"));
	}

	@Test
	void aWholeChainSwitchedOffIsUnservedAndTheShadowFamilyClosesTheStage() throws IOException {
		ProgramResolver resolver = resolve(ships("shadow.fsh", "shadow_solid.fsh", "gbuffers_basic.fsh"),
				Set.of("shadow", "shadow_solid", "gbuffers_basic"));

		assertEquals(0, resolver.resolutions("").size());
		assertEquals(Optional.empty(), resolver.lookup("", "shadow_solid"));
		assertEquals(Optional.empty(), resolver.lookup("", "shadow"));
		assertEquals(Optional.empty(), resolver.lookup("", "gbuffers_basic"));
	}

	@Test
	void resolvesEachDimensionOnItsOwnInTheOrderRootThenSortedFolders() throws IOException {
		ProgramResolver resolver = resolve(ships("gbuffers_basic.fsh", "world1/gbuffers_basic.fsh",
				"world-1/gbuffers_basic.fsh", "world0/gbuffers_line.fsh"), Set.of());

		assertEquals("gbuffers_basic.fsh", resolver.lookup("world1", "gbuffers_basic").orElseThrow().file()
				.replace("world1/", ""));
		assertEquals("world-1/gbuffers_basic.fsh", resolver.lookup("world-1", "gbuffers_basic").orElseThrow().file());
		// world0 holds a line program and nothing it can fall back to for basic: the basic program is
		// the parent of line and not the other way round.
		assertEquals(Optional.empty(), resolver.lookup("world0", "gbuffers_basic"));
		assertEquals(Optional.of("gbuffers_line"),
				resolver.lookup("world0", "gbuffers_line").map(Resolution::servedBy));
	}
}
