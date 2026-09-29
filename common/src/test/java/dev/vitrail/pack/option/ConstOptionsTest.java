package dev.vitrail.pack.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds the closed list of constants a pack may configure: twenty five names and seven spellings
 * for each of the eight shadow colour buffers, and nothing else.
 */
class ConstOptionsTest {

	private static final List<String> FIXED = List.of("shadowMapResolution", "shadowDistance", "voxelDistance",
			"shadowDistanceRenderMul", "entityShadowDistanceMul", "shadowIntervalSize", "generateShadowMipmap",
			"generateShadowColorMipmap", "shadowHardwareFiltering", "shadowtex0Mipmap", "shadowtexMipmap",
			"shadowtex1Mipmap", "shadowtex0Nearest", "shadowtexNearest", "shadow0MinMagNearest",
			"shadowtex1Nearest", "shadow1MinMagNearest", "wetnessHalflife", "drynessHalflife",
			"eyeBrightnessHalflife", "centerDepthHalflife", "sunPathRotation", "ambientOcclusionLevel",
			"superSamplingLevel", "noiseTextureResolution");

	private static List<String> perBuffer(int buffer) {
		return List.of("shadowcolor" + buffer + "Mipmap", "shadowColor" + buffer + "Mipmap",
				"shadowcolor" + buffer + "Nearest", "shadowColor" + buffer + "Nearest",
				"shadowcolor" + buffer + "MinMagNearest", "shadowColor" + buffer + "MinMagNearest",
				"shadowHardwareFiltering" + buffer);
	}

	@Test
	void everyFixedNameIsAnOption() {
		assertEquals(25, new HashSet<>(FIXED).size());
		for (String name : FIXED) {
			assertTrue(ConstOptions.isOption(name), name);
		}
	}

	@Test
	void everyPerBufferSpellingOfTheEightBuffersIsAnOption() {
		for (int buffer = 0; buffer < 8; buffer++) {
			for (String name : perBuffer(buffer)) {
				assertTrue(ConstOptions.isOption(name), name);
			}
		}
	}

	@Test
	void aNinthBufferIsNotOne() {
		for (String name : perBuffer(8)) {
			assertFalse(ConstOptions.isOption(name), name);
		}
		assertFalse(ConstOptions.isOption("shadowcolor10Mipmap"));
		assertFalse(ConstOptions.isOption("shadowcolorMipmap"));
	}

	@Test
	void namesAreMatchedExactlyAndCaseSensitively() {
		Set<String> refused = new HashSet<>(List.of("", "shadowMapBias", "shadowmapresolution", "SHADOWDISTANCE",
				"shadowMapResolution ", " shadowMapResolution", "shadowMapResolution2", "shadowtex2Nearest",
				"Shadowcolor0Mipmap", "shadowcolor0mipmap", "shadowHardwareFiltering8", "sunPathRotation_"));

		for (String name : refused) {
			assertFalse(ConstOptions.isOption(name), "'" + name + "'");
		}
	}

	@Test
	void theListHasEightyOneNames() {
		// 25 fixed and 7 spellings for each of 8 buffers; counted by probing every candidate once.
		List<String> candidates = new ArrayList<>(FIXED);
		for (int buffer = 0; buffer < 8; buffer++) {
			candidates.addAll(perBuffer(buffer));
		}

		assertEquals(81, new HashSet<>(candidates).size());
		assertEquals(81, candidates.stream().filter(ConstOptions::isOption).count());
	}
}
