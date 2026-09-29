package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link BiomeCategory} to its order, which is the specification: the number a pack reads is
 * the ordinal and the {@code CAT_*} symbols it branches on are numbered by it, so a tidied or
 * extended list compiles and puts every pack in the wrong biome.
 * <p>
 * The list is the one this engine ships today, pinned in full; it is a copy of a reference's order
 * and the tests can hold it to itself, not to that reference.
 */
class BiomeCategoryTest {

	private static final List<String> IN_ORDER = List.of("NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA",
			"PLAINS", "SAVANNA", "ICY", "THE_END", "BEACH", "FOREST", "OCEAN", "DESERT", "RIVER", "SWAMP",
			"MUSHROOM", "NETHER", "MOUNTAIN", "UNDERGROUND");

	@Test
	void keepsTheCategoriesInTheOrderPacksAreNumberedBy() {
		assertEquals(IN_ORDER, Arrays.stream(BiomeCategory.values()).map(BiomeCategory::name).toList());
	}

	@Test
	void nothingIsNumberedByAnythingButItsPlaceInTheList() {
		for (int i = 0; i < IN_ORDER.size(); i++) {
			assertEquals(i, BiomeCategory.valueOf(IN_ORDER.get(i)).ordinal(), IN_ORDER.get(i));
		}
	}

	@Test
	void noneIsZeroBecauseAPackTestsForNoCategoryWithIt() {
		assertEquals(0, BiomeCategory.NONE.ordinal());
	}

	@Test
	void undergroundIsLastSoThatTheOrdinalsBeforeItDoNotMove() {
		assertEquals(BiomeCategory.values().length - 1, BiomeCategory.UNDERGROUND.ordinal());
	}

	@Test
	void everyNameIsAValidSymbolOnceTheDefineIsBuiltFromIt() {
		// PackDefines writes CAT_ plus the name in upper case, so a lower case letter or a space in
		// one would be a symbol no preprocessor line spells the same way.
		for (BiomeCategory category : BiomeCategory.values()) {
			assertTrue(category.name().matches("[A-Z][A-Z_]*"), category.name());
		}
	}
}
