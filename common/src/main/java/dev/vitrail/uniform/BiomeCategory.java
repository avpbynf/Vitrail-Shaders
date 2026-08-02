package dev.vitrail.uniform;

/**
 * The biome categories a pack compares against, by ordinal.
 * <p>
 * Adapted in August 2026 from {@code net.irisshaders.iris.parsing.BiomeCategories}, Iris commit
 * b0ae41c. The order is the specification: the value a pack reads is the ordinal, and the defines
 * it branches on are named after these constants, so this list is copied rather than tidied.
 * Reordering it would compile and would put every pack in the wrong biome.
 * <p>
 * {@code UNDERGROUND} is here because the ordinals after it would move otherwise, and it is never
 * answered: Iris leaves its classification unwritten, and parity is that it never comes out.
 */
public enum BiomeCategory {

	NONE,
	TAIGA,
	EXTREME_HILLS,
	JUNGLE,
	MESA,
	PLAINS,
	SAVANNA,
	ICY,
	THE_END,
	BEACH,
	FOREST,
	OCEAN,
	DESERT,
	RIVER,
	SWAMP,
	MUSHROOM,
	NETHER,
	MOUNTAIN,
	UNDERGROUND
}
