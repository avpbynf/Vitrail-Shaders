package dev.vitrail.mixin;

import dev.vitrail.render.GamePipelines;
import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.GraphicsApi;
import dev.vitrail.render.LevelPass;
import dev.vitrail.render.WeatherDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jspecify.annotations.Nullable;

/**
 * Draws the game's rain and snow with the program the pack ships for them, instead of the game's own
 * shader.
 * <p>
 * <strong>This is the sky's shape of door and not the entities'.</strong> The renderer draws into one
 * render pass, sets the game's weather pipeline and draws a vertex buffer it built beforehand, so the
 * pipeline is swapped where it is set and the pass is replaced where the renderer starts drawing into
 * it. The two answers are taken together and cannot part company: a pipeline carries one colour
 * state per attachment the descriptor names, and setting one against a pass built for the other
 * throws by name in the middle of a rainstorm.
 * <p>
 * <strong>The image belongs to the draw and not to the pass.</strong> One pass draws the rain and
 * then the snow out of one buffer with one pipeline, and the only thing that changes between the two
 * is the texture, so it is handed over as the game binds it and the block is bound again before each
 * of the two draws.
 * <p>
 * <strong>Every handler is required</strong>, and says so rather than lean on the configuration's
 * default, which is one whatever the injector really binds. The pass and the pipeline are a pair,
 * and half of them applying binds a pipeline carrying eight colour states into a pass carrying one.
 * The refusal and the image each turn into a picture with nothing in the log when they are lost: two
 * curtains in the air, or the curtain drawn with the wrong image.
 * <p>
 * <strong>The 26.3 half.</strong> The renderer no longer opens a pass or picks between two
 * pipelines. It builds its buffer in {@code prepare}, ahead of the level's pass, and draws in a
 * private {@code render} that both the classic {@code render}, handed the level's one pass
 * ({@link LevelPass}) and the game's {@code WEATHER} pipeline, and the order independent
 * {@code renderOit} call. So the hooks sit on that private method and on {@code renderWeather} under
 * it: the pass it is handed is replaced at its first use, which is past its own test for a buffer and a column to draw, the point
 * 26.2's renderer opened its pass at, and the pipeline it chose is its third argument where 26.2 read
 * it off a local. The pack's pass is opened as 26.2 opened it, through {@link GeometryHold}, on the
 * images the level's pass draws into, which steps the level's pass aside, and it is closed where the
 * method returns, as 26.2's renderer closed its own. A pipeline is set compiled here and an image is
 * bound with {@code setUniform}, so the two swaps speak those. The order independent draw reaches
 * all of it with the order independent pass and pipeline, which serve nothing, and keeps its own.
 * <p>
 * <strong>Two things of 26.2's are done otherwise.</strong> NeoForge no longer widens the method
 * into a second one on this game, so there is one descriptor to name and not two. And
 * {@code rain.depth} cannot move the game's choice: 26.2 added it to the game's own question before
 * the game picked between the weather pipeline that writes depth and the one that does not, and
 * 26.3 keeps the second alone, the first having gone with the transparency chain that asked for it.
 * So the pack's program is made from the one that writes depth, which {@link GamePipelines} builds
 * again out of the same snippet, and the game's own weather, where the pack serves none, is left as
 * the game draws it.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {


	/**
	 * The pipeline the curtain is drawn with, or null for the game's own. A field of the mixin and
	 * not a static: the renderer is one object and its draw does not overlap with itself.
	 */
	@Unique
	private @Nullable RenderPipeline vitrail$pipeline;

	/** The pass opened for the curtain, or null where the renderer kept the one it was handed. */
	@Unique
	private @Nullable RenderPass vitrail$opened;

	/**
	 * Takes the whole curtain out of the frame where the pack asked for it in
	 * {@code shaders.properties}.
	 * <p>
	 * At the head of the method, which is what makes it a removal rather than a choice of shader: the
	 * rain is not drawn by anybody, because a pack writing {@code weather=false} draws its own and
	 * being handed the game's on top puts two curtains in the air. Iris cancels the method that draws
	 * it for the same word, and on this game the drawing is all in this one.
	 */
	@Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;"
			+ "Lcom/mojang/renderpearl/api/commands/RenderPass;"
			+ "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
			require = 1, at = @At("HEAD"), cancellable = true)
	private void vitrail$refuse(CallbackInfo callback) {
		if (!WeatherDraw.draws()) {
			callback.cancel();
		}
	}

	/**
	 * Prepares the pack's program and hands the renderer the pass it wants instead of the level's.
	 *
	 * @param game the pipeline the renderer was handed, out of which the blend, the depth window, the
	 *             culling and the topology of ours are read
	 */
	@ModifyVariable(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;"
			+ "Lcom/mojang/renderpearl/api/commands/RenderPass;"
			+ "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
			require = 1, argsOnly = true, at = @At(value = "LOAD", ordinal = 0))
	private RenderPass vitrail$open(RenderPass pass, WeatherRenderState state, RenderPass handed,
			RenderPipeline game) {
		this.vitrail$pipeline = null;
		this.vitrail$opened = null;

		if (!(pass instanceof LevelPass level)) {
			return pass;
		}

		// The pipeline the pack's program is made from: the one that writes depth where the pack
		// asks for it, which the game would have picked on 26.2 and no longer keeps.
		RenderPipeline asked = WeatherDraw.depth() ? GamePipelines.weatherDepthWrite() : game;
		this.vitrail$pipeline = WeatherDraw.element(asked, level.colour(), level.depth());
		RenderPassDescriptor descriptor =
				this.vitrail$pipeline == null ? null : WeatherDraw.descriptor();
		if (descriptor == null) {
			return pass;
		}

		this.vitrail$opened = GeometryHold.open(RenderSystem.getDevice().createCommandEncoder(),
				descriptor);

		return this.vitrail$opened;
	}

	/**
	 * Closes the pass opened above where the method returns, as 26.2's renderer closed the one it
	 * opened. {@link GeometryHold} keeps it open for whatever writes the same images next.
	 */
	@Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;"
			+ "Lcom/mojang/renderpearl/api/commands/RenderPass;"
			+ "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
			require = 1, at = @At("RETURN"))
	private void vitrail$close(CallbackInfo callback) {
		RenderPass opened = this.vitrail$opened;
		this.vitrail$opened = null;
		this.vitrail$pipeline = null;
		if (opened != null) {
			opened.close();
		}
	}

	@WrapOperation(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;"
			+ "Lcom/mojang/renderpearl/api/commands/RenderPass;"
			+ "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
			require = 1, at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline("
							+ "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
	private void vitrail$pipeline(RenderPass pass, CompiledRenderPipeline compiled,
			Operation<Void> original) {
		RenderPipeline ours = this.vitrail$pipeline;
		CompiledRenderPipeline chosen = ours == null ? null : GraphicsApi.compiledFor(ours);
		original.call(pass, chosen == null ? compiled : chosen);
	}

	/**
	 * Lets the game bind the rain's or the snow's own image and keeps what it bound, then binds the
	 * pack's block and samplers over it, one line before the draw that reads them.
	 * <p>
	 * In {@code renderWeather} and not in {@code render}, which is what makes it the DRAW's image
	 * rather than the pass's: the private method is called once for the rain and once for the snow,
	 * and its first line is the bind. The game's own binding costs nothing and its name is not the one
	 * that is read, the descriptor flush walking the layout of the pipeline that is really bound.
	 */
	@WrapOperation(method = "renderWeather", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform("
							+ "Ljava/lang/String;"
							+ "Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
							+ "Lcom/mojang/renderpearl/api/textures/GpuSampler;)V"))
	private void vitrail$texture(RenderPass pass, String name, GpuTextureView view,
			GpuSampler sampler, Operation<Void> original) {
		original.call(pass, name, view, sampler);
		RenderPipeline ours = this.vitrail$pipeline;
		if (ours != null) {
			WeatherDraw.texture(view, sampler);
			WeatherDraw.bind(pass, ours);
		}
	}
}
