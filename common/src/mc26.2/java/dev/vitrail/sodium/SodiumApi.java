package dev.vitrail.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.world.phys.Vec3;

/**
 * The calls into Sodium that its builds for Minecraft 26.2 and 26.3 spell differently, one method
 * each, so the code making them is shared and the difference lives in one file per game.
 * <p>
 * <strong>This is the 26.2 half, and every method is the call the engine made before there were
 * two games,</strong> unchanged. The 26.3 half beside it under {@code src/mc26.3/} says what that
 * build of Sodium changed and what the method does about it.
 */
public final class SodiumApi {

	private SodiumApi() {
	}

	/**
	 * Draws one group of chunk layers for the light's walk, inside {@code TerrainDraw.shadowPass}.
	 * On this game Sodium opens the render pass itself, one per terrain pass of the group, and the
	 * chunk renderer mixin steers each onto the shadow map while that flag stands.
	 */
	public static void drawShadowLayer(SodiumWorldRenderer renderer, ChunkSectionLayerGroup group,
			ChunkRenderMatrices matrices, Vec3 camera, GpuSampler sampler) {
		renderer.drawChunkLayer(group, matrices, camera.x, camera.y, camera.z, sampler);
	}
}
