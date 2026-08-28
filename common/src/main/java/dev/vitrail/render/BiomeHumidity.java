package dev.vitrail.render;

/**
 * A biome's downfall, which is what a pack reads as {@code rainfall}: the constant a swamp and a
 * desert differ by, and not whether it is raining.
 * <p>
 * <strong>It exists because 26.2 hands the number out through nothing at all.</strong> The biome
 * keeps its climate in a record whose {@code downfall} component is exactly this value, and it
 * publishes {@code getBaseTemperature} for the component beside it and no getter whatever for this
 * one. Every public path that touches it comes back out as a grass or foliage colour, which is a
 * lookup and not an inverse.
 * <p>
 * Outside the mixin package on purpose, for the reason {@link BlockEntityOrigin} already gives: a
 * class in there is read as a mixin, and this is a plain interface the mixin implements and its
 * caller casts to.
 */
public interface BiomeHumidity {

	float vitrail$downfall();
}
