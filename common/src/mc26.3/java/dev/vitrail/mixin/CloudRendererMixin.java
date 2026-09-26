package dev.vitrail.mixin;

import dev.vitrail.render.CloudDraw;
import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.GraphicsApi;
import dev.vitrail.render.LevelPass;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jspecify.annotations.Nullable;

/**
 * Draws the clouds with the program the pack ships for them, instead of the game's own shader.
 * <p>
 * The shape is that of 26.2's {@code SkyRendererMixin}, and shorter, because the cloud renderer is
 * simpler than the sky renderer: one method, one pass, one draw. The pipeline is swapped where it is
 * set and the pass is replaced where the renderer starts drawing into it, and the two answers are
 * taken together so that they cannot part company - a pipeline carries one colour state per
 * attachment the descriptor names, and setting one against a pass built for the other throws by
 * name in the middle of the frame.
 * <p>
 * <strong>What is not swapped is the geometry, because there is none to swap.</strong> This renderer
 * binds no vertex buffer at all: it fills a texel buffer with three bytes a face and draws six
 * indices a face out of it, working the corners out in the vertex stage. Everything the game sets
 * afterwards - the index buffer, the cloud block, the face buffer - is left exactly as it was and
 * lands on the pack's own pipeline instead, which is why that pipeline has to declare the game's own
 * two names. {@code CloudDraw} says what that costs and {@code glsl/CloudVertex} says what the pack
 * then reads.
 * <p>
 * <strong>Which of the two cloud pipelines is coming has to be known before the pass exists</strong>,
 * because it decides the culling and therefore which of the two programs is prepared. It is taken off
 * the argument the renderer was called with rather than read back from the user's settings: a pack
 * is allowed to overrule those, and it does so through the same accessor this renderer was handed
 * its answer from.
 * <p>
 * <strong>The 26.3 half.</strong> The renderer no longer opens a pass: the level hands it the one
 * pass it draws the whole main pass through, {@link LevelPass}, and the draw itself moved into a
 * private {@code render} that the order independent {@code renderOit} calls as well. So where
 * 26.2 replaced the pass the renderer opened, this replaces the pass it is handed, at the first
 * line that uses it. That line is past the renderer's own test for a texture and a face to draw,
 * which is where 26.2's renderer opened its pass, so a frame with no cloud in it prepares nothing
 * and opens nothing, as it did there.
 * <p>
 * The pack's pass is opened as 26.2 opened it, through {@link GeometryHold}, on the images the
 * level's pass draws into; opening it steps the level's pass aside, and the level's next draw opens
 * that one again. It is closed where the renderer returns, which is where 26.2's renderer closed
 * its own. The pipeline is set compiled on this game, so the swap hands over the compiled form of
 * the pack's. The two wraps in the private method answer only while the classic draw has armed
 * them: the order independent one, which runs only while no pack draws, reaches them with nothing
 * armed and keeps everything of its own. A pass the level did not hand over is left alone: its
 * images are not known here, and a pass of ours cannot be opened while it stays open.
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {

	/**
	 * The pipeline this draw is recorded with, or null for the game's own. A field of the mixin and
	 * not a static: the renderer is one object and it draws one pass at a time.
	 */
	@Unique
	private @Nullable RenderPipeline vitrail$pipeline;

	/** The pass opened for the draw, or null where the renderer kept the one it was handed. */
	@Unique
	private @Nullable RenderPass vitrail$opened;

	/**
	 * Prepares the pack's program and hands the renderer the pass it wants instead of the level's.
	 * <p>
	 * The cloud setting is the renderer's own argument, read here rather than kept from the head of
	 * the method as on 26.2: the renderer chose its pipeline from it one line above, so this is
	 * already the moment that decides.
	 */
	@ModifyVariable(method = "render(Lnet/minecraft/client/CloudStatus;"
			+ "Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
			require = 1, argsOnly = true, at = @At(value = "LOAD", ordinal = 0))
	private RenderPass vitrail$open(RenderPass pass, CloudStatus cloudStatus) {
		this.vitrail$pipeline = null;
		this.vitrail$opened = null;
		if (!(pass instanceof LevelPass level)) {
			return pass;
		}

		this.vitrail$pipeline = CloudDraw.pipeline(cloudStatus == CloudStatus.FANCY);
		RenderPassDescriptor descriptor = this.vitrail$pipeline == null
				? null
				: CloudDraw.descriptor(level.colour(), level.depth());
		if (descriptor == null) {
			return pass;
		}

		this.vitrail$opened = GeometryHold.open(RenderSystem.getDevice().createCommandEncoder(),
				descriptor);

		return this.vitrail$opened;
	}

	/**
	 * Closes the pass opened above where the renderer returns, as 26.2's renderer closed the one it
	 * opened. {@link GeometryHold} keeps it open for whatever writes the same images next.
	 */
	@Inject(method = "render(Lnet/minecraft/client/CloudStatus;"
			+ "Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
			require = 1, at = @At("RETURN"))
	private void vitrail$close(CallbackInfo callback) {
		RenderPass opened = this.vitrail$opened;
		this.vitrail$opened = null;
		this.vitrail$pipeline = null;
		if (opened != null) {
			opened.close();
		}
	}

	@WrapOperation(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;"
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
	 * The last moment before the draw, and the first at which everything the bind needs is set.
	 * <p>
	 * After the game's own uniforms and not before them, which is what makes the two halves fit: the
	 * game fills the cloud block and the face buffer by name, and the block and samplers bound here
	 * are the pack's own. Neither knows about the other, and the draw wants both.
	 */
	@WrapOperation(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;"
			+ "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
			require = 1, at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/commands/RenderPass;drawIndexed(IIIII)V"))
	private void vitrail$draw(RenderPass pass, int indexCount, int instanceCount, int firstIndex,
			int vertexOffset, int firstInstance, Operation<Void> original) {
		RenderPipeline ours = this.vitrail$pipeline;
		if (ours != null) {
			CloudDraw.bind(pass, ours);
		}

		original.call(pass, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}
}
