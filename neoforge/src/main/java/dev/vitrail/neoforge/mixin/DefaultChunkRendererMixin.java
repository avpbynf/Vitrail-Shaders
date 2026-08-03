package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.sodium.SodiumPasses;
import dev.vitrail.pack.TerrainPass;
import dev.vitrail.render.TerrainDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Opens the chunk renderer's pass with the colour targets the pack's program writes, instead of the
 * single one Sodium was going to give it.
 * <p>
 * The renderer opens its own pass and it is a one attachment pass:
 * {@code createRenderPass(label, target.getColorTextureView(), empty, target.getDepthTextureView(),
 * empty)}. A {@code gbuffers_terrain} declaring {@code DRAWBUFFERS:0,6,4} therefore has nowhere to
 * put its second and third outputs, and they are written nowhere at all, which is what the log has
 * been saying since the first step of milestone six.
 * <p>
 * Wrapping the call is the whole hook. Everything else stays Sodium's: the draw commands, the
 * regions, the culling, the push constants. Rewriting any of that is out of the question, it is the
 * most internal code Sodium has and it is under a licence this project cannot copy from.
 * <p>
 * <strong>Draw buffer nought is deliberately left where it was.</strong> It keeps going to the
 * game's own target, so the picture on screen is unchanged and the scene seed still finds the world
 * where it looks for it. What is gained is everything above nought, which costs nothing because it
 * is written nowhere today. Taking nought as well is the next step and it is not this one: the sky
 * and the entities are still drawn by the game, so a colour target holding only the terrain would
 * make the chain composite a world with no sky in it.
 * <p>
 * The depth view is passed through untouched. The terrain has to depth test against the sky the game
 * has already drawn, and the entities, the particles and the hand have to test against the terrain.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class DefaultChunkRendererMixin {

	@WrapOperation(
			method = "render",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
							+ "Ljava/util/function/Supplier;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Ljava/util/Optional;"
							+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
							+ "Ljava/util/OptionalDouble;"
							+ ")Lcom/mojang/blaze3d/systems/RenderPass;"))
	private RenderPass vitrail$pass(CommandEncoder encoder, Supplier<String> label,
			GpuTextureView colour, Optional<?> clearColour, GpuTextureView depth,
			OptionalDouble clearDepth, Operation<RenderPass> original,
			@Local(argsOnly = true) TerrainRenderPass pass) {
		TerrainPass ours = SodiumPasses.of(pass);
		RenderPassDescriptor descriptor = ours == null
				? null
				: TerrainDraw.descriptor(ours, colour, depth);

		return descriptor == null
				? original.call(encoder, label, colour, clearColour, depth, clearDepth)
				: encoder.createRenderPass(descriptor);
	}
}
