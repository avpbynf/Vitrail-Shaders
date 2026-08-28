package dev.vitrail.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The downfall of a biome's climate record, without naming the record type.
 * <p>
 * The type cannot be named: 26.2 compiles {@code Biome.ClimateSettings} as a package-private
 * member of {@code Biome}, so javac refuses it from any other package even though its own {@code downfall()}
 * is public. Naming it in a target string sidesteps that, the string never being a type to javac,
 * and the field the accessor reads is a plain float that both sides can write down.
 * <p>
 * Iris does not need this hop, its {@code mixin/MixinBiome.java:11-30} shadowing the record type
 * directly, which is what 26.1 still allowed. The value read is the same field either way.
 */
@Mixin(targets = "net.minecraft.world.level.biome.Biome$ClimateSettings")
public interface BiomeClimateAccessor {

	@Accessor("downfall")
	float vitrail$downfall();
}
