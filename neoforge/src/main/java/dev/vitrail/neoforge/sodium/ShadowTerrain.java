package dev.vitrail.neoforge.sodium;

import dev.vitrail.render.TerrainDraw;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4fc;

/**
 * Draws the chunk renderer a second time in the frame, for the shadow map.
 * <p>
 * Nothing of the draw is ours. {@code drawChunkLayer} is Sodium's own public entry and it walks the
 * solid pass and then the cutout one over the render lists as they stand; what changes is that our
 * two mixins answer differently while {@link TerrainDraw#shadowPass} holds its flag, so the
 * pipeline is the pack's {@code shadow} program and the render pass is opened on the shadow map. The
 * geometry, the regions, the push constants and the culling stay exactly where they were, which is
 * the only way to touch the most internal code Sodium has under a licence this project may not copy
 * from.
 * <p>
 * <strong>The matrices handed over are the camera's, deliberately, and they are not what the shadow
 * is drawn with.</strong> They go into Sodium's own {@code u_Globals}, which is written once a frame
 * by whichever draw comes first and which our programs never read: they take their matrices from
 * their own block, where the shadow pair is. Handing the shadow pair here instead would leave that
 * uniform holding the light's view for the whole frame, and any chunk pass the pack does not serve,
 * a place shipping no {@code gbuffers_water} for one, would then be drawn by the game's own shader
 * from the sun.
 * <p>
 * <strong>What this does not do is cull for the light.</strong> The render lists are the camera's,
 * so what the camera cannot see casts no shadow. That is a real hole and a visible one, shadows
 * arriving and leaving as the player turns, and it is named in the log rather than left to be read
 * off the picture.
 */
public final class ShadowTerrain {

	private ShadowTerrain() {
	}

	/**
	 * @param modelView the camera's own view rotation, as the frame graph was handed it
	 * @param camera    where the camera is, which is also where the shadow view is centred, so the
	 *                  region offsets the push constants carry mean the same thing in both
	 */
	public static void draw(Matrix4fc modelView, Vec3 camera) {
		SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
		Minecraft minecraft = Minecraft.getInstance();
		if (renderer == null || minecraft == null) {
			return;
		}

		// Sodium's own source for it, so that what reaches u_Globals is what would have reached it
		// anyway: this one carries the walk bob and the camera state's does not.
		Matrix4fc projection = ((GameRendererStorage) minecraft.gameRenderer).sodium$getProjectionMatrix();
		ChunkRenderMatrices matrices = new ChunkRenderMatrices(projection, modelView);

		// Mipmapped and clamped, which is the game's own chunk sampler short of its anisotropy. It
		// matters here rather than being tidiness: the cutout half of the shadow discards on the
		// atlas's alpha, and a leaf sampled without mipmaps casts a shadow that crawls at distance.
		GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true);

		TerrainDraw.shadowPass(() -> renderer.drawChunkLayer(ChunkSectionLayerGroup.OPAQUE, matrices,
				camera.x, camera.y, camera.z, sampler));

		// Between the two groups and nowhere else: this is the one moment shadowtex0 and shadowtex1
		// hold different things, and what separates them is exactly the draw that comes next. The
		// renderer closes its own render pass before returning, so a copy here is outside one.
		TerrainDraw.copyShadowDepth();

		TerrainDraw.shadowPass(() -> renderer.drawChunkLayer(ChunkSectionLayerGroup.TRANSLUCENT,
				matrices, camera.x, camera.y, camera.z, sampler));
	}
}
