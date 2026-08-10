package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.WeatherDraw;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ParticleStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Stops the ground throwing up splashes under the rain where the pack asked for it, which is the
 * second word of {@code weather=}.
 * <p>
 * <strong>Not the same thing as the curtain and not in the same place.</strong> The first word is
 * about geometry the weather renderer draws in the frame; this is about ordinary particles the level
 * spawns on a tick, so no shader of anybody's is involved and there is nothing here to draw with a
 * program of the pack's. What a pack writing it means is that it draws its own, and leaving the
 * game's would put two sets of splashes on the ground.
 * <p>
 * <strong>It is handed the frugal setting rather than skipped</strong>, which is what Iris does with
 * the same word ({@code mixin/MixinWeatherRenderer.java:30-37}). That is not the same as skipping the
 * method: the game still walks its columns until one qualifies, still keeps that position and still
 * plays the rain's sound over it, and only the particle is left out. It stops at the FIRST qualifying
 * column rather than the last, {@code ClientLevel.tickWeatherEffects} breaking out of the loop there,
 * so the sound is placed at a different column and still placed. Skipping the method would take the
 * sound with it, which no word of the format asks for.
 * <p>
 * The method Iris hangs that on is gone in 26.2, the spawning having moved out of the weather
 * renderer and into the level's own tick, and the setting it substitutes is still read at the top of
 * it, into the local this modifies.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

	/**
	 * The one {@code ParticleStatus} local of the method, which is what makes the type enough to name
	 * it: the line immediately below reads another setting through the same call, so an injection
	 * matching on the call rather than on the variable would need an ordinal and would follow the
	 * wrong one the day a third setting is read.
	 */
	@ModifyVariable(method = "tickWeatherEffects", at = @At("STORE"), require = 1)
	private ParticleStatus vitrail$splashes(ParticleStatus status) {
		return WeatherDraw.splashes() ? status : ParticleStatus.MINIMAL;
	}
}
