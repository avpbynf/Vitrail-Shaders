package dev.vitrail.mixin;

import dev.vitrail.sodium.SodiumPasses;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.render.LegacyTerrainFilter;
import dev.vitrail.render.TerrainDraw;
import dev.vitrail.render.TerrainSampler;

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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Hands Sodium the pack's own programs in place of its chunk shader, one per pass.
 * <p>
 * The head of {@code compileProgram} is the whole hook: it is one point, it has no state of its own,
 * and short circuiting it there leaves Sodium's memo untouched, so nothing of ours is ever handed
 * back once this is turned off. What that memo answers when the pass is left to Sodium is a question
 * of its own, and {@link #vitrail$ofThisFormat} is where it is asked.
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
public abstract class MixinShaderChunkRenderer {

	@Shadow
	protected VertexFormat vertexFormat;

	/**
	 * Settles which sampler a pack's terrain reads the block atlas through, at the one point that is
	 * handed the game's: {@code begin} has it and {@code compileProgram} does not. {@code begin}
	 * calls {@code compileProgram}, so this always lands first.
	 * <p>
	 * <strong>What the pack binds is ours and not the game's, and that is a correction rather than a
	 * preference.</strong> The game filters the atlas LINEAR and Iris binds NEAREST for every terrain
	 * draw, so a pack, which is written against Iris, expects NEAREST. What that filter decides on
	 * cutout foliage is the silhouette rather than the colour, the fragment being discarded on the
	 * atlas's alpha, and {@link TerrainSampler} carries the whole of it with the references on both
	 * sides. {@link LegacyTerrainFilter} puts the game's back, terrain and shadow alike, for a
	 * comparison. The choice is made where the pack is known to be drawing, so that a pass with no
	 * pack behind it neither asks nor announces anything.
	 * <p>
	 * Every pass comes through here, the shadow map's included: the light's draw hands the game's
	 * sampler down the same road, and the pack's shadow programs bind what is settled here.
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

	/**
	 * Hands the renderer its own memoised pipeline back only when that pipeline was built for the
	 * format this renderer binds, and reports a miss otherwise so that it is built again.
	 * <p>
	 * <strong>The memo is static and keyed by the render pass alone.</strong> The three passes are
	 * immortal instances of {@code DefaultTerrainRenderPasses}, the map is a static field, and what
	 * it holds declares the vertex format of whichever renderer built it,
	 * {@code withVertexBinding(0, this.vertexFormat)}. Nothing ever empties it, and the renderer's own
	 * {@code delete} is not an oversight either: what it frees is the index buffer and the draw context
	 * it owns, and the half it inherits is a plain return.
	 * <p>
	 * Everything else that holds a stride is rebuilt when the format moves - the section manager, the
	 * chunk renderer it constructs from the format in force, the mesh of every section - so this memo
	 * is the one thing that survives the rebuild carrying the old one. And it is handed to the game's
	 * own chunk shader, which draws the terrain wherever this engine gives a pass back, warm up
	 * included. A pipeline declaring 44 bytes a vertex over a mesh written at 20 reads every position
	 * out of the middle of some other vertex: the terrain comes out as stretched coloured spikes while
	 * the sky, the entities and the interface stay right. That is what dropping a pack to {@code none}
	 * in a running game did, and the same mechanism says the first frames after picking one are drawn
	 * from a pipeline left over at Sodium's own stride.
	 * <p>
	 * <strong>The Sodium this could not happen on says what the key is missing.</strong> Its memo was
	 * one per renderer and keyed by {@code ChunkShaderOptions}, which carries the
	 * {@code ChunkVertexType} itself, so an entry built for one format could not answer for another.
	 * This asks the same question where the answer is used rather than rekeying a map that is not
	 * ours, and it costs one reference comparison per pass and frame.
	 * <p>
	 * <strong>What building one again costs is real and bounded.</strong> The device keeps the
	 * compiled pipeline in an identity map from the description it was compiled from, and only a
	 * resource reload empties it, so the one left behind by a switch lives until the next reload:
	 * three at most for each time the format moves. The alternative is the wrong picture.
	 * <p>
	 * <strong>{@code require = 1}, and what it guards is the lookup being there at all.</strong> A
	 * Sodium that put the format back in its key would go on asking the same map the same way, so
	 * this would go on applying and its comparison would simply never refuse anything. What fails the
	 * injection is the memo being dropped or reached by some other road, and refusing to load then
	 * names the one thing that moved. Failing quietly is the other way round: a world destroyed a
	 * pack switch later, and nothing on screen pointing anywhere near here.
	 */
	@Redirect(method = "compileProgram",
			at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),
			require = 1)
	private Object vitrail$ofThisFormat(Map<?, ?> memo, Object pass) {
		Object found = memo.get(pass);
		// A pipeline binding nothing at nought is no more usable than one binding another format, and
		// the renderer builds none: every road into this map goes through its own vertex binding.
		if (found instanceof RenderPipeline pipeline
				&& !this.vertexFormat.equals(pipeline.getVertexFormatBinding(0))) {
			return null;
		}

		return found;
	}
}
