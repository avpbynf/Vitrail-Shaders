package dev.vitrail.pack.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.RenderStage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the symbols the engine defines before a pack sees its own code, beyond the system symbol
 * {@link EngineDefinesTest} covers: the classification of the vendor and the renderer, the
 * conditional symbols, the numbering a pack compares against, and the order of the table.
 * <p>
 * A symbol that goes missing does not fail loudly, it sends a pack down a fallback path meant for
 * a renderer of ten years ago, so the whole table is held.
 */
class EngineDefinesSymbolsTest {

	private final EngineDefines.Environment saved = EngineDefines.machine();

	@AfterEach
	void restore() {
		EngineDefines.machine(this.saved);
	}

	private static Map<String, String> table(String vendor, String renderer) {
		return EngineDefines.table(new EngineDefines.Environment(EngineDefines.DEFAULT_MC_VERSION,
				EngineDefines.Os.LINUX, vendor, renderer, 4, false, Map.of(), List.of()));
	}

	private static String symbolWith(Map<String, String> table, String prefix) {
		List<String> found = table.keySet().stream().filter(name -> name.startsWith(prefix)).toList();
		assertEquals(1, found.size(), prefix + " symbols posed: " + found);

		return found.get(0);
	}

	// ---- the machine -------------------------------------------------------------------------

	@Test
	void anEnvironmentOfAVersionIsTheEmptyOneOnWindows() {
		EngineDefines.Environment environment = EngineDefines.Environment.of(EngineDefines.DEFAULT_MC_VERSION);

		assertEquals(260200, EngineDefines.DEFAULT_MC_VERSION);
		assertEquals(260200, environment.mcVersion());
		assertEquals(EngineDefines.Os.WINDOWS, environment.os());
		assertEquals("", environment.vendorName());
		assertEquals("", environment.rendererName());
		assertEquals(4, environment.mipmapLevel());
		assertTrue(environment.biomes().isEmpty());
		assertTrue(environment.biomeCategories().isEmpty());
		assertFalse(environment.distantHorizons());
		assertFalse(environment.bufferBlending());
		assertNull(environment.textureFormat());
	}

	@Test
	void theMachineIsOneValueForTheWholeProcess() {
		EngineDefines.Environment other = EngineDefines.Environment.of(1);

		EngineDefines.machine(other);

		assertSame(other, EngineDefines.machine());
	}

	@Test
	void theShorterConstructorPosesNoTextureFormatAndNoBufferBlending() {
		EngineDefines.Environment environment = new EngineDefines.Environment(5, EngineDefines.Os.MAC, "v", "r", 2,
				true, Map.of("a", 1), List.of("b"));

		assertNull(environment.textureFormat());
		assertFalse(environment.bufferBlending());
		assertEquals(2, environment.mipmapLevel());
	}

	// ---- vendor and renderer -----------------------------------------------------------------

	@Test
	void classifiesTheVendorByPrefixCaseInsensitively() {
		String[][] rows = {
			{"ATI Technologies Inc.", "MC_GL_VENDOR_ATI"},
			{"ati", "MC_GL_VENDOR_ATI"},
			{"Intel Inc.", "MC_GL_VENDOR_INTEL"},
			{"Intel(R) Corporation", "MC_GL_VENDOR_INTEL"},
			{"NVIDIA Corporation", "MC_GL_VENDOR_NVIDIA"},
			{"nvidia", "MC_GL_VENDOR_NVIDIA"},
			{"AMD", "MC_GL_VENDOR_AMD"},
			{"Advanced Micro Devices", "MC_GL_VENDOR_OTHER"},
			{"X.Org", "MC_GL_VENDOR_XORG"},
			{"x.org foundation", "MC_GL_VENDOR_XORG"},
			{"The X.Org Foundation", "MC_GL_VENDOR_OTHER"},
			{"Apple", "MC_GL_VENDOR_OTHER"},
			{"Mesa", "MC_GL_VENDOR_OTHER"},
			{"", "MC_GL_VENDOR_OTHER"},
			{" NVIDIA", "MC_GL_VENDOR_OTHER"},
		};

		for (String[] row : rows) {
			assertEquals(row[1], symbolWith(table(row[0], ""), "MC_GL_VENDOR_"), "vendor '" + row[0] + "'");
		}
	}

	@Test
	void classifiesTheRendererByPrefixInOptiFinesOrder() {
		String[][] rows = {
			{"AMD Radeon RX 7900", "MC_GL_RENDERER_RADEON"},
			{"ATI Radeon HD", "MC_GL_RENDERER_RADEON"},
			{"Radeon Pro", "MC_GL_RENDERER_RADEON"},
			{"Gallium 0.4 on AMD", "MC_GL_RENDERER_GALLIUM"},
			{"Intel(R) UHD Graphics", "MC_GL_RENDERER_INTEL"},
			{"GeForce RTX 4090", "MC_GL_RENDERER_GEFORCE"},
			{"NVIDIA GeForce RTX", "MC_GL_RENDERER_GEFORCE"},
			{"Quadro P2000", "MC_GL_RENDERER_QUADRO"},
			{"NVS 315", "MC_GL_RENDERER_QUADRO"},
			// nvidia is tested before quadro, so a board named by its maker first is a GeForce.
			{"NVIDIA Quadro RTX 4000", "MC_GL_RENDERER_GEFORCE"},
			{"Mesa Intel(R) Xe", "MC_GL_RENDERER_MESA"},
			{"Apple M2 Pro", "MC_GL_RENDERER_APPLE"},
			{"llvmpipe (LLVM 15)", "MC_GL_RENDERER_OTHER"},
			{"", "MC_GL_RENDERER_OTHER"},
		};

		for (String[] row : rows) {
			assertEquals(row[1], symbolWith(table("", row[0]), "MC_GL_RENDERER_"), "renderer '" + row[0] + "'");
		}
	}

	// ---- the conditional symbols -------------------------------------------------------------

	@Test
	void distantHorizonsAndPerBufferBlendingAreOnlyPosedWhenTheyAreTrue() {
		EngineDefines.Environment off = EngineDefines.Environment.of(1);
		EngineDefines.Environment on = new EngineDefines.Environment(1, EngineDefines.Os.LINUX, "", "", 4, true,
				Map.of(), List.of(), null, true);

		assertFalse(EngineDefines.table(off).containsKey("DISTANT_HORIZONS"));
		assertFalse(EngineDefines.table(off).containsKey("IRIS_FEATURE_PER_BUFFER_BLENDING"));
		assertEquals("", EngineDefines.table(on).get("DISTANT_HORIZONS"));
		assertEquals("", EngineDefines.table(on).get("IRIS_FEATURE_PER_BUFFER_BLENDING"));
	}

	@Test
	void theTextureFormatPosesItsNameAndItsVersionWhenThereIsOne() {
		EngineDefines.Environment plain = withFormat(new EngineDefines.TextureFormat("lab-pbr", null));
		EngineDefines.Environment versioned = withFormat(new EngineDefines.TextureFormat("lab-pbr", "1.3"));
		EngineDefines.Environment odd = withFormat(new EngineDefines.TextureFormat("old-pbr", "1.3-beta"));

		Map<String, String> none = EngineDefines.table(EngineDefines.Environment.of(1));
		assertTrue(none.keySet().stream().noneMatch(name -> name.startsWith("MC_TEXTURE_FORMAT")));

		assertEquals(List.of("MC_TEXTURE_FORMAT_LAB_PBR"), formatSymbols(plain));
		assertEquals(List.of("MC_TEXTURE_FORMAT_LAB_PBR", "MC_TEXTURE_FORMAT_LAB_PBR_1_3"), formatSymbols(versioned));
		// The version is not upper-cased, only its dots and dashes become underscores.
		assertEquals(List.of("MC_TEXTURE_FORMAT_OLD_PBR", "MC_TEXTURE_FORMAT_OLD_PBR_1_3_beta"), formatSymbols(odd));
	}

	private static EngineDefines.Environment withFormat(EngineDefines.TextureFormat format) {
		return new EngineDefines.Environment(1, EngineDefines.Os.LINUX, "", "", 4, false, Map.of(), List.of(),
				format, false);
	}

	private static List<String> formatSymbols(EngineDefines.Environment environment) {
		return EngineDefines.table(environment).keySet().stream()
				.filter(name -> name.startsWith("MC_TEXTURE_FORMAT")).toList();
	}

	@Test
	void biomesAndCategoriesAreNumberedAndPosedInTheOrderHandedOver() {
		Map<String, Integer> biomes = new LinkedHashMap<>();
		biomes.put("the_void", 0);
		biomes.put("plains", 1);
		biomes.put("Deep_Dark", 42);
		EngineDefines.Environment environment = new EngineDefines.Environment(1, EngineDefines.Os.LINUX, "", "", 4,
				false, biomes, List.of("none", "taiga", "the_end"));

		List<String> keys = new ArrayList<>(EngineDefines.table(environment).keySet());
		Map<String, String> table = EngineDefines.table(environment);

		assertEquals("0", table.get("BIOME_THE_VOID"));
		assertEquals("1", table.get("BIOME_PLAINS"));
		assertEquals("42", table.get("BIOME_DEEP_DARK"));
		assertEquals("0", table.get("CAT_NONE"));
		assertEquals("1", table.get("CAT_TAIGA"));
		assertEquals("2", table.get("CAT_THE_END"));

		int tail = keys.indexOf("PPT_SNOW");
		assertEquals(List.of("BIOME_THE_VOID", "BIOME_PLAINS", "BIOME_DEEP_DARK", "CAT_NONE", "CAT_TAIGA",
				"CAT_THE_END"), keys.subList(tail + 1, keys.size()));
	}

	@Test
	void anEmptyMachineHasNoBiomeSymbolAtAll() {
		assertTrue(EngineDefines.table(EngineDefines.Environment.of(1)).keySet().stream()
				.noneMatch(name -> name.startsWith("BIOME_") || name.startsWith("CAT_")));
	}

	// ---- the values --------------------------------------------------------------------------

	@Test
	void carriesTheVersionsTheQualitiesAndTheCeilings() {
		Map<String, String> table = EngineDefines.table(new EngineDefines.Environment(260300,
				EngineDefines.Os.LINUX, "", "", 7, false, Map.of(), List.of()));

		assertEquals("260300", table.get("MC_VERSION"));
		assertEquals("11102", table.get("IRIS_VERSION"));
		assertEquals("460", table.get("MC_GL_VERSION"));
		assertEquals("460", table.get("MC_GLSL_VERSION"));
		assertEquals("1.0", table.get("MC_RENDER_QUALITY"));
		assertEquals("1.0", table.get("MC_SHADOW_QUALITY"));
		assertEquals("0.125", table.get("MC_HAND_DEPTH"));
		assertEquals("7", table.get("MC_MIPMAP_LEVEL"));
		assertEquals("32", table.get("MAX_COLOR_BUFFERS"));
		assertEquals("", table.get("MC_NORMAL_MAP"));
		assertEquals("", table.get("MC_SPECULAR_MAP"));
		assertEquals("", table.get("IS_IRIS"));
	}

	@Test
	void theVersionShortcutIsTheEnvironmentOfThatVersion() {
		assertEquals(EngineDefines.table(EngineDefines.Environment.of(260200)), EngineDefines.table(260200));
	}

	@Test
	void posesTheFeaturesTheEngineServes() {
		Map<String, String> table = EngineDefines.table(EngineDefines.Environment.of(1));

		for (String feature : List.of("IRIS_FEATURE_CUSTOM_IMAGES", "IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE",
				"IRIS_FEATURE_ENTITY_TRANSLUCENT", "IRIS_FEATURE_SEPARATE_HARDWARE_SAMPLERS",
				"IRIS_FEATURE_HIGHER_SHADOWCOLOR", "IRIS_FEATURE_SSBO", "IRIS_FEATURE_COMPUTE_SHADERS")) {
			assertEquals("", table.get(feature), feature);
		}
	}

	@Test
	void numbersTheRenderStagesByTheirPositionInTheEnum() {
		Map<String, String> table = EngineDefines.table(EngineDefines.Environment.of(1));
		String[] expected = {"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID",
			"TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG",
			"HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER",
			"HAND_TRANSLUCENT"};

		assertEquals(expected.length, RenderStage.values().length);
		for (int number = 0; number < expected.length; number++) {
			assertEquals(Integer.toString(number), table.get("MC_RENDER_STAGE_" + expected[number]), expected[number]);
		}
	}

	@Test
	void numbersTheDistantHorizonsBlockKindsAndThePrecipitationTypes() {
		Map<String, String> table = EngineDefines.table(EngineDefines.Environment.of(1));
		String[] kinds = {"UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA", "DEEPSLATE", "SNOW", "SAND",
			"TERRACOTTA", "NETHER_STONE", "WATER", "GRASS", "AIR", "ILLUMINATED"};

		for (int number = 0; number < kinds.length; number++) {
			assertEquals(Integer.toString(number), table.get("DH_BLOCK_" + kinds[number]), kinds[number]);
		}

		assertEquals("0", table.get("PPT_NONE"));
		assertEquals("1", table.get("PPT_RAIN"));
		assertEquals("2", table.get("PPT_SNOW"));
	}

	// ---- the order ---------------------------------------------------------------------------

	@Test
	void theTableIsEmittedInAFixedOrder() {
		List<String> expected = new ArrayList<>(List.of("MC_VERSION", "IRIS_VERSION", "MC_GL_VERSION",
				"MC_GLSL_VERSION", "MC_OS_WINDOWS", "MC_GL_VENDOR_OTHER", "MC_GL_RENDERER_OTHER", "MC_RENDER_QUALITY",
				"MC_SHADOW_QUALITY", "MC_HAND_DEPTH", "MC_MIPMAP_LEVEL", "MC_NORMAL_MAP", "MC_SPECULAR_MAP",
				"MAX_COLOR_BUFFERS", "IS_IRIS", "IRIS_FEATURE_CUSTOM_IMAGES",
				"IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE", "IRIS_FEATURE_ENTITY_TRANSLUCENT",
				"IRIS_FEATURE_SEPARATE_HARDWARE_SAMPLERS", "IRIS_FEATURE_HIGHER_SHADOWCOLOR", "IRIS_FEATURE_SSBO",
				"IRIS_FEATURE_COMPUTE_SHADERS"));
		for (RenderStage stage : RenderStage.values()) {
			expected.add("MC_RENDER_STAGE_" + stage.name());
		}
		for (String kind : List.of("UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA", "DEEPSLATE",
				"SNOW", "SAND", "TERRACOTTA", "NETHER_STONE", "WATER", "GRASS", "AIR", "ILLUMINATED")) {
			expected.add("DH_BLOCK_" + kind);
		}
		expected.addAll(List.of("PPT_NONE", "PPT_RAIN", "PPT_SNOW"));

		assertEquals(expected, new ArrayList<>(EngineDefines.table(EngineDefines.Environment.of(1)).keySet()));
	}

	@Test
	void everyCallBuildsATableOfItsOwn() {
		Map<String, String> first = EngineDefines.table(1);
		first.put("SCRIBBLED", "1");
		first.remove("MC_VERSION");

		Map<String, String> second = EngineDefines.table(1);

		assertFalse(second.containsKey("SCRIBBLED"));
		assertNotNull(second.get("MC_VERSION"));
	}
}
