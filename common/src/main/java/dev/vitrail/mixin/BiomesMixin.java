package dev.vitrail.mixin;

import dev.vitrail.render.BiomeClassifier;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands {@link BiomeClassifier} the vanilla biomes in the order the game's own class declares
 * them, which is the order Iris numbers them in.
 * <p>
 * <strong>The order is the contract, and it is nobody's registry.</strong> Iris counts a static
 * field upward across {@code Biomes.register} ({@code mixin/MixinBiomes.java:14-22}), so the
 * numbers a pack compares {@code biome} against are the declaration order of the {@code Biomes}
 * class: {@code the_void} nought, {@code plains} one, {@code swamp} six. Packs write those numbers
 * as literals, {@code in(biome, 6, 52, 7)} in Bliss's swamp fog, so any other numbering sends the
 * fog to whatever biomes happen to sit on those numbers instead. This engine first walked the
 * level's biome registry, whose iteration order is alphabetical, and paid exactly that: six was
 * an ocean, and the swamp fog stood over shorelines.
 * <p>
 * Same injection point as Iris, and the counter lives in the classifier rather than here so that
 * the table and the number it hands out cannot drift apart.
 */
@Mixin(Biomes.class)
public abstract class BiomesMixin {

	// require = 1 against this config's defaultRequire of nought: a silently missed injection
	// here would number every biome nought and write no BIOME_* define at all, which a pack
	// swallows without a word.
	@Inject(method = "register", at = @At("TAIL"), require = 1)
	private static void vitrail$record(String name, CallbackInfoReturnable<ResourceKey<Biome>> cir) {
		BiomeClassifier.record(cir.getReturnValue());
	}
}
