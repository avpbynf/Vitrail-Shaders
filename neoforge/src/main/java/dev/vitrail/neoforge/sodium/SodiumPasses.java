package dev.vitrail.neoforge.sodium;

import dev.vitrail.pack.program.TerrainPass;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

/**
 * This engine's name for one of the chunk renderer's three passes.
 * <p>
 * Told apart by identity and by nothing else. {@code TerrainRenderPass} is a plain class rather than
 * an enum, so a fourth one is a thing the type allows, and drawing it with a program written for
 * another pass would be silently wrong: a cutout discard on geometry that has none, or a blend where
 * the renderer wanted none. Anything that is not one of the three is left to Sodium.
 */
public final class SodiumPasses {

	private SodiumPasses() {
	}

	/** Null for a pass this engine has no program for, which is every pass but the three. */
	@SuppressWarnings("ReferenceEquality")
	public static TerrainPass of(TerrainRenderPass pass) {
		if (pass == DefaultTerrainRenderPasses.SOLID) {
			return TerrainPass.SOLID;
		}

		if (pass == DefaultTerrainRenderPasses.CUTOUT) {
			return TerrainPass.CUTOUT;
		}

		return pass == DefaultTerrainRenderPasses.TRANSLUCENT ? TerrainPass.TRANSLUCENT : null;
	}
}
