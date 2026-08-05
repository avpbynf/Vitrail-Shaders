package dev.vitrail.render;

import dev.vitrail.uniform.BiomeCategory;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
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
 * Minecraft from end to end: a cascade of tag tests and a registry walk. The value package only
 * ever sees the two integers that come out.
 * <p>
 * <b>One table, two readers.</b> The number this hands out is the same one the {@code BIOME_*}
 * defines have to carry, and two tables that agree today and drift tomorrow is the silent failure
 * this class exists to prevent, so {@link #names()} is here for whoever writes those defines.
 * Nobody builds a second one.
 * <p>
 * The cascade is Iris {@code uniforms/BiomeUniforms.java:56-95} at b0ae41c, order included, since
 * that order is what decides a biome that carries two tags. Two things about it are deliberate
 * and not oversights: {@code UNDERGROUND} is never returned, because Iris has no way to detect it
 * on this version and leaves it commented out, and there is no test for {@code IS_SAVANNA}, so a
 * savanna comes out as plains. Packs are written against that behaviour, not against OptiFine's
 * documentation of it.
 */
public final class BiomeClassifier {

	private final Map<Identifier, Integer> ids = new HashMap<>();
	private final List<String> names = new ArrayList<>();

	private Object registrySeen;

	/**
	 * The dense number for a biome, in the registry's own order.
	 * <p>
	 * Iris numbers biomes by intercepting the static registration of the vanilla ones, which
	 * needs a mixin and misses everything a data pack adds. Walking the level's registry costs
	 * nothing, covers the modded ones, and is stable for as long as the level is loaded, which is
	 * as long as the number has to mean anything.
	 */
	public int identify(Level level, Holder<Biome> biome) {
		refresh(level);

		Identifier key = biome.unwrapKey().map(k -> k.identifier()).orElse(null);
		Integer id = key == null ? null : this.ids.get(key);

		return id == null ? 0 : id;
	}

	/**
	 * Builds the table against this level's registry, if that registry has not been walked yet.
	 * Called on its own by whoever writes the {@code BIOME_*} defines, which happens once when a
	 * pack is read and therefore before anything has asked for a number.
	 */
	public void refresh(Level level) {
		Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
		if (this.registrySeen != registry) {
			rebuild(registry);
		}
	}

	/** The biome names, in the order their numbers were handed out. Empty until a level loads. */
	public List<String> names() {
		return List.copyOf(this.names);
	}

	/**
	 * Drops the table and lets go of the registry it was walked from. For a client leaving a world:
	 * the registry is held by identity, so keeping it is keeping everything the world hung off it.
	 */
	public void forget() {
		this.ids.clear();
		this.names.clear();
		this.registrySeen = null;
	}

	private void rebuild(Registry<Biome> registry) {
		this.ids.clear();
		this.names.clear();
		registry.listElements().forEach(element -> {
			Identifier key = element.key().identifier();
			this.ids.put(key, this.names.size());
			this.names.add(key.getPath().toUpperCase(Locale.ROOT));
		});

		this.registrySeen = registry;
	}

	/** The ordinal of {@link BiomeCategory}, which is the number the pack actually compares. */
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
