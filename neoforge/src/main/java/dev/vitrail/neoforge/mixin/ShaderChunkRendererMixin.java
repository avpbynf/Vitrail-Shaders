package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.PackChain;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands Sodium the pack's own terrain program in place of its chunk shader, for the opaque pass and
 * that pass alone.
 * <p>
 * The head of {@code compileProgram} is the whole hook: it is one point, it has no state of its own,
 * and short circuiting it there leaves Sodium's memo untouched, so nothing of ours is ever handed
 * back once this is turned off. Cutout and translucent are deliberately left alone, which puts the
 * two shaders on screen at the same time and makes the difference between them the thing being read.
 * <p>
 * It is also the last point before Sodium opens its render pass, which is why the pipeline is
 * compiled, the buffers made and this frame's uniform block written from here rather than from the
 * bind: creating a texture or a buffer records a barrier into the very command buffer a pass would
 * be recording into, and a clear refuses outright while one is open.
 * <p>
 * The vertex format is shadowed rather than looked up. It is the one the renderer will really bind,
 * whereas {@code ChunkMeshFormats.getCurrent()} is what it would have chosen, and the two parting
 * company would be a mismatch nothing reports: an element the shader does not declare shifts the
 * location of every element after it in silence.
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ShaderChunkRendererMixin {

	@Shadow
	protected VertexFormat vertexFormat;

	/**
	 * Takes the sampler the game configured for the block atlas, which {@code begin} is handed and
	 * {@code compileProgram} is not.
	 * <p>
	 * Worth the second hook rather than settling for a sampler of our own: the game's is mipmapped
	 * and ours was not, and a block atlas sampled without mipmaps shimmers at distance and bleeds
	 * between sprites at their edges. {@code begin} calls {@code compileProgram}, so this always
	 * lands first.
	 */
	@Inject(method = "begin", at = @At("HEAD"))
	private void vitrail$sampler(TerrainRenderPass pass, FogParameters parameters,
			GpuSampler terrainSampler, CallbackInfo callback) {
		PackChain.terrainSampler(terrainSampler);
	}

	@Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
	private void vitrail$terrain(TerrainRenderPass pass,
			CallbackInfoReturnable<RenderPipeline> callback) {
		// The region offset arrives through push constants, which only the Vulkan backend pushes at
		// all: under OpenGL Sodium sets it as an ordinary uniform and our shader would read nothing.
		if (DrawBackend.BACKEND == DrawBackend.OPENGL || pass != DefaultTerrainRenderPasses.SOLID) {
			return;
		}

		RenderPipeline ours = PackChain.terrainPipeline(this.vertexFormat, pass.getAtlas());
		if (ours != null) {
			callback.setReturnValue(ours);
		}
	}
}
