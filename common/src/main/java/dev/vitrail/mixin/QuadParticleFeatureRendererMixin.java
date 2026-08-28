package dev.vitrail.mixin;

import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.ParticleDraw;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Draws the game's quad particles with the programs the pack ships for them.
 * <p>
 * <strong>The entities' mixin cannot reach this class and that is the reason it exists.</strong>
 * Twelve feature renderers draw through {@code RenderTypeFeatureRenderer.executeGroup} and inherit it
 * unchanged; this one implements the interface directly and has an {@code executeGroup} of its own,
 * which opens a render pass, walks the layers of the group and sets a pipeline and an atlas for each.
 * So the shape here is the sky's and the weather's rather than the entities': the pass is replaced
 * where it is opened, and this class only opens and closes the group.
 * <p>
 * <strong>The pipeline and the atlas are swapped on the pass and not here</strong>, by
 * {@link RenderPassMixin}, keyed to the pass this class hands the renderer. Wrapping the calls of
 * {@code drawLayers} was tried first and it misses every draw a mod records into the group's pass
 * from a handler of its own: AsyncParticles' GPU particles set pipelines carrying one colour state
 * after {@code drawLayers} returns, which a pass carrying the pack's colour targets refuses by name.
 * <p>
 * <strong>Which half is being drawn is read off the submits and not worked out here.</strong> The
 * game reads the same field to decide which layers go into the group and which target they go to, so
 * taking it from anywhere else would be a second answer to a question already asked.
 * <p>
 * <strong>Both handlers are required</strong>, and say so rather than lean on the configuration's
 * default. Without the open, the pack's particle programs are never asked for and every group keeps
 * the game's own shader, with nothing in the log to say why. Without the close, what the open armed
 * outlives the group, and the hooks on the pass would answer for draws that are not particles at
 * all.
 */
@Mixin(QuadParticleFeatureRenderer.class)
public abstract class QuadParticleFeatureRendererMixin {

	/**
	 * Prepares the pack's program for the half about to be drawn, hands back the pass it wants
	 * opened, and tells {@link ParticleDraw} which pass the group's draws will land in.
	 * <p>
	 * The submits cannot be empty here: the renderer only records a group when they are not, and it
	 * is indexing that record one line above. Read defensively all the same, an empty list being a
	 * frame with no particles rather than anything to report.
	 */
	@WrapOperation(method = "executeGroup", require = 1,
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
			OptionalDouble clearDepth, Operation<RenderPass> original,
			@Local(argsOnly = true) List<QuadParticleFeatureRenderer.Submit> submits) {
		RenderPipeline pipeline = submits.isEmpty()
				? null
				: ParticleDraw.group(submits.getFirst().translucent(), colour, depth);
		RenderPassDescriptor descriptor = pipeline == null ? null : ParticleDraw.descriptor();

		RenderPass pass = descriptor == null
				? original.call(encoder, label, colour, clearColour, depth, clearDepth)
				: GeometryHold.open(encoder, descriptor);
		ParticleDraw.opened(pass);

		return pass;
	}

	/**
	 * Forgets the group at the way out, whichever way out it is. The two hooks on the pass stay
	 * armed for as long as the group stands, so an exception thrown inside the renderer must
	 * disarm them too: the group's pass may be one {@code GeometryHold} keeps open, and later
	 * families join that same object.
	 */
	@WrapMethod(method = "executeGroup", require = 1)
	private void vitrail$group(FeatureFrameContext context, int groupIndex,
			List<QuadParticleFeatureRenderer.Submit> submits, boolean strictlyOrdered,
			Operation<Void> original) {
		try {
			original.call(context, groupIndex, submits, strictlyOrdered);
		} finally {
			ParticleDraw.endGroup();
		}
	}
}
