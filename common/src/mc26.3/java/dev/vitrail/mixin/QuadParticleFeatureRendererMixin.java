package dev.vitrail.mixin;

import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.LevelPass;
import dev.vitrail.render.ParticleDraw;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws the game's quad particles with the programs the pack ships for them.
 * <p>
 * <strong>The entities' mixin cannot reach this class and that is the reason it exists.</strong>
 * Twelve feature renderers draw through {@code RenderTypeFeatureRenderer.executeGroup} and inherit it
 * unchanged; this one implements the interface directly and has an {@code executeGroup} of its own,
 * which walks the layers of the group and sets a pipeline and an atlas for each. So the shape here
 * is the sky's and the weather's rather than the entities': the group's pass is replaced, and this
 * class only opens and closes the group.
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
 * <strong>The 26.3 half.</strong> The renderer no longer opens a pass: {@code executeGroup} takes
 * the pass it draws into, the level's own one ({@link LevelPass}) on the classic path, and an order
 * independent stage beside it, which is null on that path. So the whole group is wrapped, and the
 * pass it is handed is replaced there, where 26.2 replaced the pass it opened: what 26.2 did in two
 * handlers, opening the pass and forgetting the group, is one handler here, with the close its
 * renderer made between the two.
 * <p>
 * The pass is opened as 26.2 opened it: through {@link GeometryHold} where the pack's program writes
 * targets of its own, and as a plain pass on the level's images where it writes the game's alone,
 * which is the renderer's own pass of 26.2. That second case is not the level's pass handed on,
 * although it lands on the same images: the hooks on the pass are keyed to the pass object the draws
 * are recorded into, and the level's pass records into a pass under it that it opens again after
 * every gap. Opening either steps the level's pass aside, and it is closed where the group ends, as
 * 26.2's renderer closed its own. A group with an order independent stage, which only runs while no
 * pack draws, and a pass the level did not hand over, whose images are not known here, are left
 * exactly as they come.
 * <p>
 * The one handler is required, as both of 26.2's were: without it no group is ever served, and
 * nothing in the log says why.
 */
@Mixin(QuadParticleFeatureRenderer.class)
public abstract class QuadParticleFeatureRendererMixin {

	/**
	 * Prepares the pack's program for the half about to be drawn, draws the group into the pass it
	 * wants, and forgets the group at the way out, whichever way out it is.
	 * <p>
	 * The two hooks on the pass stay armed for as long as the group stands, so an exception thrown
	 * inside the renderer must disarm them too: the group's pass may be one {@code GeometryHold}
	 * keeps open, and later families join that same object. The pass is closed before the group is
	 * forgotten, which is the order 26.2 left them in, its renderer closing the pass inside the
	 * method this wrapped.
	 */
	@WrapMethod(method = "executeGroup", require = 1)
	private void vitrail$group(FeatureFrameContext context, @Nullable OitStage stage,
			RenderPass pass, int groupIndex, List<QuadParticleFeatureRenderer.Submit> submits,
			boolean strictlyOrdered, Operation<Void> original) {
		RenderPass drawn = pass;
		try {
			if (stage == null) {
				drawn = vitrail$open(pass, submits);
			}

			original.call(context, stage, drawn, groupIndex, submits, strictlyOrdered);
		} finally {
			try {
				if (drawn != pass) {
					drawn.close();
				}
			} finally {
				ParticleDraw.endGroup();
			}
		}
	}

	/**
	 * The pass the group is drawn into: the one handed in where the pack serves nothing for this
	 * half, and one opened for it otherwise, which {@link ParticleDraw} is told about.
	 * <p>
	 * The submits cannot be empty here: the renderer only records a group when they are not. Read
	 * defensively all the same, an empty list being a frame with no particles rather than anything to
	 * report.
	 */
	@Unique
	private static RenderPass vitrail$open(RenderPass pass,
			List<QuadParticleFeatureRenderer.Submit> submits) {
		if (!(pass instanceof LevelPass level) || submits.isEmpty()) {
			return pass;
		}

		boolean translucent = submits.getFirst().translucent();
		RenderPipeline pipeline = ParticleDraw.group(translucent, level.colour(), level.depth());
		if (pipeline == null) {
			return pass;
		}

		RenderPassDescriptor descriptor = ParticleDraw.descriptor();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		String half = translucent ? "Translucent" : "Solid";
		RenderPass drawn = descriptor == null
				? encoder.createRenderPass(() -> "Particles - " + half, level.colour(),
						Optional.empty(), level.depth(), OptionalDouble.empty())
				: GeometryHold.open(encoder, descriptor);
		ParticleDraw.opened(drawn);

		return drawn;
	}
}
