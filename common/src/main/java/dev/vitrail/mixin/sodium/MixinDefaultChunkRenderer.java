package dev.vitrail.mixin.sodium;

import dev.vitrail.sodium.SodiumPasses;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.RingTimings;
import dev.vitrail.render.TerrainDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * <strong>Draw buffer nought comes here too, and the sky and the entities still do not.</strong>
 * What a {@code gbuffers_terrain} puts there is not a colour but whatever the pack packed there, and
 * the game's own target is eight bits a channel: a pack encoding two values per channel loses one of
 * them on the way through. So the pass writes the pack's target outright and marks the pixels it
 * covered, and the scene seed brings the rest of the game's picture in around them.
 * <p>
 * The depth view is passed through untouched. The terrain has to depth test against the sky the game
 * has already drawn, and the entities, the particles and the hand have to test against the terrain.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer {

	// require, because a silently unapplied probe prints the same zeros as an empty rotate, and
	// the whole point of the clock is telling those two apart.
	@Inject(method = "rotate", at = @At("HEAD"), require = 1)
	private void vitrail$rotateBegin(CallbackInfo ci) {
		RingTimings.beginRotate();
	}

	@Inject(method = "rotate", at = @At("RETURN"), require = 1)
	private void vitrail$rotateEnd(CallbackInfo ci) {
		RingTimings.endRotate();
	}

	/**
	 * Answers the target question with the game's own while the shadow map is being drawn.
	 * <p>
	 * The translucent pass answers it with {@code LevelRenderer.translucentTarget()}, which is a
	 * frame graph resource that exists only while the pass that declared it is executing. The shadow
	 * map is drawn before every one of those, so asking there throws "Resource is not currently
	 * available" from inside Sodium, before our own wrap below is ever reached: the arguments of a
	 * call are evaluated before the call.
	 * <p>
	 * What comes back is thrown away. The descriptor below replaces the whole pass, so this only has
	 * to be something that exists.
	 * <p>
	 * {@code require = 2} because the method asks twice, and Mixin counts an injector's matches as
	 * one total: at one, either call could stop matching on its own and be dropped without a word,
	 * and the one left unwrapped throws from inside Sodium the first time a shadow map is drawn.
	 */
	@WrapOperation(
			method = "render",
			require = 2,
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
							+ "getTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget vitrail$target(TerrainRenderPass pass, Operation<RenderTarget> original) {
		if (!TerrainDraw.drawingShadow()) {
			return original.call(pass);
		}

		Minecraft minecraft = Minecraft.getInstance();

		return minecraft == null ? original.call(pass) : minecraft.gameRenderer.mainRenderTarget();
	}

	/**
	 * Serves every face of a section while the shadow map is drawn.
	 * <p>
	 * The batches Sodium builds leave out the faces that point away from the camera, and the light
	 * is not the camera: a face the player cannot see still stands between the sun and the ground.
	 * The pipeline of the shadow passes already draws both sides; this reaches the culling that
	 * happens before any pipeline, when the draw commands are picked. Iris turns the same option
	 * off at the same point for the same reason.
	 */
	@WrapOperation(
			method = "render",
			at = @At(value = "FIELD",
					target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;"
							+ "useBlockFaceCulling:Z"))
	private boolean vitrail$shadowFaces(SodiumOptions.PerformanceSettings settings,
			Operation<Boolean> original) {
		return !TerrainDraw.drawingShadow() && original.call(settings);
	}

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
				: GeometryHold.open(encoder, descriptor);
	}
}
