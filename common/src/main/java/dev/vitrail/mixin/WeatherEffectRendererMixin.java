package dev.vitrail.mixin;

import dev.vitrail.platform.PatchedMethods;
import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.WeatherDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Draws the game's rain and snow with the program the pack ships for them, instead of the game's own
 * shader.
 * <p>
 * <strong>This is the sky's shape of door and not the entities'.</strong> The renderer opens one
 * render pass of its own, sets one of the game's two weather pipelines and draws a vertex buffer it
 * built a few lines earlier, so the pipeline is swapped where it is set and the pass is replaced
 * where it is opened. The two answers are taken on one call of one wrap and cannot part company: a
 * pipeline carries one colour state per attachment the descriptor names, and setting one against a
 * pass built for the other throws by name in the middle of a rainstorm.
 * <p>
 * <strong>The game picks its pipeline before it opens its pass</strong>, which is what lets the
 * preparation read every state it does not decide off the game's own: the choice is a local of the
 * same method, made before the pass and read where the pass opens, with the whole vertex buffer
 * build and the dynamic transform write in between. That is also the whole of what
 * {@code rain.depth} costs, the directive moving that choice rather than describing a depth state
 * of ours.
 * <p>
 * <strong>The image belongs to the draw and not to the pass.</strong> One pass draws the rain and
 * then the snow out of one buffer with one pipeline, and the only thing that changes between the two
 * is the texture, so it is handed over as the game binds it and the block is bound again before each
 * of the two draws.
 * <p>
 * <strong>All five handlers are required</strong>, and say so rather than lean on the
 * configuration's default, which is one whatever the injector really binds: writing the count out
 * is what keeps it right when a handler moves or a target gains a call.
 * The pass and the pipeline are a pair, and half of them
 * applying binds a pipeline carrying eight colour states into a pass carrying one, which throws by
 * name in the middle of a rainstorm. The other three each turn into a picture with nothing in the
 * log: the curtain drawn twice, the curtain drawn with the wrong image, or a pack's
 * {@code rain.depth} quietly unread.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

	/**
	 * The pipeline the curtain is drawn with, or null for the game's own. A field of the mixin and
	 * not a static: the renderer is one object and its pass does not overlap with itself.
	 */
	@Unique
	private RenderPipeline vitrail$pipeline;

	/**
	 * Takes the whole curtain out of the frame where the pack asked for it in
	 * {@code shaders.properties}.
	 * <p>
	 * At the head of the method, which is what makes it a removal rather than a choice of shader: the
	 * rain is not drawn by anybody, because a pack writing {@code weather=false} draws its own and
	 * being handed the game's on top puts two curtains in the air. Iris cancels the same method for
	 * the same word.
	 */
	@Inject(method = { PatchedMethods.WEATHER_RENDER, PatchedMethods.WEATHER_RENDER_WIDENED },
			require = 1, at = @At("HEAD"), cancellable = true)
	private void vitrail$refuse(CallbackInfo callback) {
		if (!WeatherDraw.draws()) {
			callback.cancel();
		}
	}

	/**
	 * Serves {@code rain.depth} where Iris serves it: by moving the answer the game asks for before
	 * it picks between its two weather pipelines.
	 * <p>
	 * <strong>The game's own question and not a second one.</strong> The renderer writes the depth
	 * only where the game's transparency chain is running, and a pack asking for {@code rain.depth}
	 * is asking for it whether that chain is running or not, so the yes has to be added to the game's
	 * answer rather than substituted for it. Doing it here rather than in the pipeline this engine
	 * builds is what keeps the two in step on the frames the pack's own program does not serve: the
	 * game's shader then draws, and it draws with the depth the pack asked for.
	 * <p>
	 * The call moved between the two versions of the game the two engines target, from
	 * {@code Minecraft.useShaderTransparency} to the frame's own render state, so the target here is
	 * not the one Iris names; what it decides is the same line of the same method.
	 */
	@WrapOperation(method = { PatchedMethods.WEATHER_RENDER, PatchedMethods.WEATHER_RENDER_WIDENED },
			require = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/state/GameRenderState;"
							+ "useShaderTransparency()Z"))
	private boolean vitrail$rainDepth(GameRenderState state, Operation<Boolean> original) {
		return original.call(state) || WeatherDraw.depth();
	}

	/**
	 * Prepares the pack's program and hands back the pass it wants opened.
	 *
	 * @param game the pipeline the renderer picked earlier in this same method, out of which the
	 *             blend, the depth window, the culling and the topology of ours are read
	 */
	@WrapOperation(method = { PatchedMethods.WEATHER_RENDER, PatchedMethods.WEATHER_RENDER_WIDENED },
			require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
							+ "Ljava/util/function/Supplier;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Ljava/util/Optional;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Ljava/util/OptionalDouble;"
							+ ")Lcom/mojang/blaze3d/systems/RenderPass;"))
	private RenderPass vitrail$open(CommandEncoder encoder, Supplier<String> label,
			GpuTextureView colour, Optional<?> clearColour, GpuTextureView depth,
			OptionalDouble clearDepth, Operation<RenderPass> original, @Local RenderPipeline game) {
		this.vitrail$pipeline = WeatherDraw.element(game, colour, depth);
		RenderPassDescriptor descriptor =
				this.vitrail$pipeline == null ? null : WeatherDraw.descriptor();

		return descriptor == null
				? original.call(encoder, label, colour, clearColour, depth, clearDepth)
				: GeometryHold.open(encoder, descriptor);
	}

	@WrapOperation(method = { PatchedMethods.WEATHER_RENDER, PatchedMethods.WEATHER_RENDER_WIDENED },
			require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline("
							+ "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void vitrail$pipeline(RenderPass pass, RenderPipeline pipeline,
			Operation<Void> original) {
		original.call(pass, this.vitrail$pipeline == null ? pipeline : this.vitrail$pipeline);
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
					target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture("
							+ "Ljava/lang/String;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Lcom/mojang/blaze3d/textures/GpuSampler;)V"))
	private void vitrail$texture(RenderPass pass, String name, GpuTextureView view,
			GpuSampler sampler, Operation<Void> original) {
		original.call(pass, name, view, sampler);
		if (this.vitrail$pipeline != null) {
			WeatherDraw.texture(view, sampler);
			WeatherDraw.bind(pass, this.vitrail$pipeline);
		}
	}
}
