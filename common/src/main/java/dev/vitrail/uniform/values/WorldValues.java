package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

/**
 * The dimension and the biome: what the world is made of where the camera stands.
 * <p>
 * The category is a {@link dev.vitrail.uniform.BiomeCategory} ordinal and the classification that
 * produces it lives against the game, because it is a cascade of tag tests all the way down. The
 * two meet at one accessor, and there is one table, not two: the defines a pack branches on are
 * named after the same enum.
 */
public final class WorldValues {

	private WorldValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("bedrockLevel", UniformShape.INT, (world, out) -> out.set(world.bedrockLevel()));
		builder.add("heightLimit", UniformShape.INT, (world, out) -> out.set(world.heightLimit()));
		builder.add("logicalHeightLimit", UniformShape.INT,
				(world, out) -> out.set(world.logicalHeightLimit()));
		builder.add("hasCeiling", UniformShape.INT, (world, out) -> out.set(world.hasCeiling()));
		builder.add("hasSkylight", UniformShape.INT, (world, out) -> out.set(world.hasSkylight()));
		builder.add("ambientLight", UniformShape.FLOAT, (world, out) -> out.set(world.ambientLight()));
		builder.add("cloudHeight", UniformShape.FLOAT, (world, out) -> out.set(world.cloudHeight()));
		builder.add("seaLevel", UniformShape.INT, (world, out) -> out.set(world.seaLevel()));

		builder.add("biome", UniformShape.INT, (world, out) -> out.set(world.biomeId()));
		builder.add("biome_category", UniformShape.INT,
				(world, out) -> out.set(world.biomeCategory()));
		builder.add("biome_precipitation", UniformShape.INT,
				(world, out) -> out.set(world.biomePrecipitation()));
		builder.add("rainfall", UniformShape.FLOAT, (world, out) -> out.set(world.rainfall()));
		builder.add("temperature", UniformShape.FLOAT, (world, out) -> out.set(world.temperature()));
	}
}
