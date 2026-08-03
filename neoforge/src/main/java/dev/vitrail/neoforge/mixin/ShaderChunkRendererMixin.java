package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.sodium.SodiumPasses;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.render.TerrainDraw;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands Sodium the pack's own programs in place of its chunk shader, one per pass.
 * <p>
 * The head of {@code compileProgram} is the whole hook: it is one point, it has no state of its own,
 * and short circuiting it there leaves Sodium's memo untouched, so nothing of ours is ever handed
 * back once this is turned off.
 * <p>
 * The three passes are told apart by identity against {@code DefaultTerrainRenderPasses}, and a pass
 * that is none of the three is left to Sodium. That is not defensive: {@code TerrainRenderPass} is a
 * plain class and not an enum, so a mod adding one is a thing the type allows, and drawing it with a
 * program written for another pass would be silently wrong.
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
		TerrainDraw.sampler(terrainSampler);
	}

	@Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
	private void vitrail$terrain(TerrainRenderPass pass,
			CallbackInfoReturnable<RenderPipeline> callback) {
		// The region offset arrives through push constants, which only the Vulkan backend pushes at
		// all: under OpenGL Sodium sets it as an ordinary uniform and our shader would read nothing.
		if (DrawBackend.BACKEND == DrawBackend.OPENGL) {
			return;
		}

		TerrainPass ours = SodiumPasses.of(pass);
		if (ours == null) {
			return;
		}

		RenderPipeline pipeline = TerrainDraw.pipeline(ours, this.vertexFormat, pass.getAtlas());
		if (pipeline != null) {
			callback.setReturnValue(pipeline);
		}
	}
}
