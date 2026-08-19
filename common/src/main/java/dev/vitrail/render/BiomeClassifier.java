package dev.vitrail.render;

import dev.vitrail.uniform.BiomeCategory;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Numbers the biomes and sorts them into the categories a pack compares against.
 * <p>
 * This lives on the game's side of the line rather than in the value package, because it is
 * Minecraft from end to end: an interception of the game's own registration and a cascade of tag
 * tests. The value package only ever sees the two integers that come out.
 * <p>
 * <b>One table, two readers.</b> The number this hands out is the same one the {@code BIOME_*}
 * defines have to carry, and two tables that agree today and drift tomorrow is the silent failure
 * this class exists to prevent, so {@link #names()} is here for whoever writes those defines.
 * Nobody builds a second one.
 * <p>
 * <b>The numbering is Iris's, the declaration order of the {@code Biomes} class, and nothing
 * about it is free to vary.</b> Iris counts upward across {@code Biomes.register}
 * ({@code mixin/MixinBiomes.java:14-22}), and packs write the numbers that come out as literals:
 * Bliss's swamp fog is {@code in(biome, 6, 52, 7)} in its {@code shaders.properties}, six being
 * {@code swamp} and seven {@code mangrove_swamp} in that order and nowhere else.
 * {@link dev.vitrail.mixin.BiomesMixin} is the same interception, and {@link #record} is its
 * counter. This class first walked the level's biome registry instead, on the argument that it
 * also covered what a data pack adds; that registry iterates alphabetically, six landed on
 * {@code cold_ocean}, and Bliss stood its swamp fog over shorelines. A biome the vanilla class
 * never registers answers nought, which is what Iris's map answers for one it has never seen.
 * <p>
 * The category cascade is Iris {@code uniforms/BiomeUniforms.java:56-95} at b0ae41c, order
 * included, since that order is what decides a biome that carries two tags. Two things about it
 * are deliberate and not oversights: {@code UNDERGROUND} is never returned, because Iris has no
 * way to detect it on this version and leaves it commented out, and there is no test for
 * {@code IS_SAVANNA}, so a savanna comes out as plains. Packs are written against that behaviour,
 * not against OptiFine's documentation of it.
 */
public final class BiomeClassifier {

	private static final Map<Identifier, Integer> IDS = new HashMap<>();
	private static final List<String> NAMES = new ArrayList<>();

	private BiomeClassifier() {
	}

	/**
	 * Takes the next vanilla biome as the game's own class declares it. Called by
	 * {@link dev.vitrail.mixin.BiomesMixin} from {@code Biomes.register}, which runs once per
	 * biome when that class initialises, before any pack is read and before any world exists.
	 */
	public static void record(ResourceKey<Biome> key) {
		Identifier identifier = key.identifier();
		IDS.put(identifier, NAMES.size());
		NAMES.add(identifier.getPath().toUpperCase(Locale.ROOT));
	}

	/** The dense number for a biome: its place in the declaration order, or nought off the table. */
	public static int identify(Holder<Biome> biome) {
		Identifier key = biome.unwrapKey().map(k -> k.identifier()).orElse(null);
		Integer id = key == null ? null : IDS.get(key);

		return id == null ? 0 : id;
	}

	/** The biome names, in the order their numbers were handed out. */
	public static List<String> names() {
		return List.copyOf(NAMES);
	}

	/** The ordinal of {@link BiomeCategory}, which is the number the pack actually compares. */
	@SuppressWarnings("EnumOrdinal")
	public static int categoryOf(Holder<Biome> holder) {
		return category(holder).ordinal();
	}

	private static BiomeCategory category(Holder<Biome> holder) {
		if (holder.is(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS)) {
			// Literally only the void carries this one.
			return BiomeCategory.NONE;
		} else if (holder.is(BiomeTags.HAS_VILLAGE_SNOWY)) {
			return BiomeCategory.ICY;
		} else if (holder.is(BiomeTags.IS_HILL)) {
			return BiomeCategory.EXTREME_HILLS;
		} else if (holder.is(BiomeTags.IS_TAIGA)) {
			return BiomeCategory.TAIGA;
		} else if (holder.is(BiomeTags.IS_OCEAN)) {
			return BiomeCategory.OCEAN;
		} else if (holder.is(BiomeTags.IS_JUNGLE)) {
			return BiomeCategory.JUNGLE;
		} else if (holder.is(BiomeTags.IS_FOREST)) {
			return BiomeCategory.FOREST;
		} else if (holder.is(BiomeTags.IS_BADLANDS)) {
			return BiomeCategory.MESA;
		} else if (holder.is(BiomeTags.IS_NETHER)) {
			return BiomeCategory.NETHER;
		} else if (holder.is(BiomeTags.IS_END)) {
			return BiomeCategory.THE_END;
		} else if (holder.is(BiomeTags.IS_BEACH)) {
			return BiomeCategory.BEACH;
		} else if (holder.is(BiomeTags.HAS_DESERT_PYRAMID)) {
			return BiomeCategory.DESERT;
		} else if (holder.is(BiomeTags.IS_RIVER)) {
			return BiomeCategory.RIVER;
		} else if (holder.is(BiomeTags.ALLOWS_SURFACE_SLIME_SPAWNS)) {
			return BiomeCategory.SWAMP;
		} else if (holder.is(BiomeTags.WITHOUT_ZOMBIE_SIEGES)) {
			return BiomeCategory.MUSHROOM;
		} else if (holder.is(BiomeTags.IS_MOUNTAIN)) {
			return BiomeCategory.MOUNTAIN;
		} else {
			return BiomeCategory.PLAINS;
		}
	}
}
