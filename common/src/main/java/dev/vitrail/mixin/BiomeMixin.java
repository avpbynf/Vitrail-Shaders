package dev.vitrail.mixin;

import dev.vitrail.render.BiomeHumidity;

import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the biome's downfall out to {@link BiomeHumidity}, which is what a pack reads as
 * {@code rainfall}.
 * <p>
 * <strong>Taken in the constructor rather than read on demand, because the record type cannot be
 * named here.</strong> An accessor answers with the field's own type, and 26.2 compiles
 * {@code Biome.ClimateSettings} as a package-private member, so no method in this package can be
 * declared to return it. What CAN cross the boundary is a parameter widened to {@code Object}, which is
 * what {@code @Coerce} is for, and the constructor is the one place the record arrives as a
 * parameter. It runs once per biome at registry load and the field is final, so nothing is being
 * cached that could go stale.
 * <p>
 * Iris reaches the same field with a plain shadow ({@code mixin/MixinBiome.java:11-30}), which is
 * what 26.1 allowed and 26.2 does not. The number handed to a pack is the same either way, with one
 * exception worth writing down: NeoForge redirects every read of that field to a copy its biome
 * modifiers may have rewritten, and a constructor parameter is the original. So a pack running
 * under a mod that edits biome humidity would read Iris' rewritten number and our unrewritten one.
 * Reaching the rewritten copy means naming a method that exists on one loader only, which this
 * module cannot do; what it costs is one uniform, on one loader, with such a mod installed.
 * <p>
 * The other four parameters are named because an injector's signature has to match its target's,
 * and they are all public types. {@code require = 1}, written out where this config already
 * defaults to it, for the reason {@code BiomesMixin} gives: a silently missed injection here would
 * leave every biome answering nought, which is exactly the desert this exists to stop being
 * published.
 */
@Mixin(Biome.class)
public abstract class BiomeMixin implements BiomeHumidity {

	@Unique
	private float vitrail$downfall;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$readDownfall(@Coerce Object climate, EnvironmentAttributeMap attributes,
			BiomeSpecialEffects effects, BiomeGenerationSettings generation,
			MobSpawnSettings spawns, CallbackInfo callback) {
		this.vitrail$downfall = ((BiomeClimateAccessor) climate).vitrail$downfall();
	}

	@Override
	public float vitrail$downfall() {
		return this.vitrail$downfall;
	}
}
