package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.SkyDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import org.joml.Matrix4f;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Draws the sky with the programs the pack ships for it, instead of the game's own shaders.
 * <p>
 * The sky is the one piece of the world that opens its own render passes: {@code SkyRenderer} makes
 * one per element, sets a pipeline of the game's and draws a buffer built once at startup. So the
 * hook is not the one the entities will use, and it is smaller: the pipeline is swapped where it is
 * set, and everything else about the pass stays the game's, its attachment included.
 * <p>
 * <strong>An element is recognised by the label the game gives its own pass</strong>, which is the
 * first argument of the call wrapped below. That is what lets one wrap serve six methods without a
 * table of method names to keep in step with the game: a method whose label this engine has no
 * element for prepares nothing and draws exactly as it did.
 * <p>
 * <strong>The order of the four wraps is the whole design.</strong> The pass is opened after the
 * game has pushed the model view for this element, so the matrix is final by then and the sun is
 * where the game put it; compiling a pipeline or clearing a target has to happen before the pass
 * exists, which is why the preparation hangs off the opening and not off the head of the method.
 * The texture goes past next, and the block and the samplers are bound last, once everything the
 * bind needs is known.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

	/**
	 * The pipeline the element being recorded is drawn with, or null for the game's own. A field of
	 * the mixin and not a static: the renderer is one object and its passes do not overlap.
	 */
	private RenderPipeline vitrail$pipeline;

	@WrapOperation(
			method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset", "renderSun",
					"renderMoon"},
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
			OptionalDouble clearDepth, Operation<RenderPass> original) {
		// The model view as the game has just left it, rotation of the day included. Copied, because
		// the stack is about to be popped and the block is written from it.
		this.vitrail$pipeline = SkyDraw.element(label.get(),
				new Matrix4f(RenderSystem.getModelViewStack()));

		return original.call(encoder, label, colour, clearColour, depth, clearDepth);
	}

	@WrapOperation(
			method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset", "renderSun",
					"renderMoon"},
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline("
							+ "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void vitrail$pipeline(RenderPass pass, RenderPipeline pipeline,
			Operation<Void> original) {
		original.call(pass, this.vitrail$pipeline == null ? pipeline : this.vitrail$pipeline);
	}

	/**
	 * Lets the game bind its own texture and keeps what it bound. The pack's program declares its
	 * own name for the same image, and the descriptor flush walks the layout of the pipeline that is
	 * bound, so the game's binding costs nothing and the name it used is not the one that is read.
	 */
	@WrapOperation(
			method = {"renderSun", "renderMoon"},
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture("
							+ "Ljava/lang/String;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Lcom/mojang/blaze3d/textures/GpuSampler;)V"))
	private void vitrail$texture(RenderPass pass, String name, GpuTextureView view,
			GpuSampler sampler, Operation<Void> original) {
		original.call(pass, name, view, sampler);
		SkyDraw.texture(view, sampler);
	}

	/**
	 * The last moment before the draw, and the first at which everything the bind needs is known.
	 * Binding earlier would leave the celestial atlas out, since the game binds it after the
	 * pipeline is set.
	 */
	@WrapOperation(
			method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset", "renderSun",
					"renderMoon"},
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;setVertexBuffer("
							+ "ILcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
	private void vitrail$bind(RenderPass pass, int slot, GpuBufferSlice buffer,
			Operation<Void> original) {
		original.call(pass, slot, buffer);
		if (this.vitrail$pipeline != null) {
			SkyDraw.bind(pass, this.vitrail$pipeline);
		}
	}
}
