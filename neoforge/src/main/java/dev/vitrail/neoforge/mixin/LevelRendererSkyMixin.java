package dev.vitrail.neoforge.mixin;

import dev.vitrail.render.SkyDraw;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the sky out of a frame drawn from inside a fluid, where the game's own is what draws it.
 * <p>
 * Sodium takes the whole sky pass out under water, so that the sky cannot be seen through the chunks
 * its fog occlusion culled, and it does that by answering yes to the question
 * {@code Camera.extractRenderState} asks about blindness, which is the one {@code addSkyPass} reads
 * before it builds anything. With a pack drawing the sky that costs everything and buys nothing:
 * none of the six elements is drawn, so none of the pack's sky programs runs, nothing opens its
 * colour targets and a head under water is a frame with no sky in it at all.
 * <p>
 * <strong>So that mixin is switched off in {@code neoforge.mods.toml} and this is the copy that
 * answers in its place</strong>, conditioned on which engine draws the sky. A copy and not a wrap of
 * Sodium's own: the {@code sodium:options} block is a whole feature package at a time, which is the
 * only granularity offered, and leaving it on would leave nothing here to condition. Iris takes the
 * same two steps for the same reason.
 * <p>
 * The condition is asked of this engine and not of the pack, unlike Iris, which has no such switch
 * to ask about: with the sky left to the game in {@code options.txt} there is no pack sky for the
 * suppression to be taking away, and the frame should look as Sodium alone draws it.
 * <p>
 * <strong>It is not asked of the programs either, and that is the answer rather than a gap.</strong>
 * A pack that serves no program for a piece leaves that piece to the game, and the game needs the
 * pass this would have cancelled to draw it: cancelling there would take the sky away from a pack
 * that never offered to replace it. What comes back in that case is Sodium's own reason for the
 * suppression, a sky drawn through culled chunks while the camera is submerged, and it is the lesser
 * of the two. No pack of the corpus reaches it: of the twenty five pack and place pairs, twenty two
 * ship {@code gbuffers_skybasic} outright and the other three reach it through the fallback tree.
 * {@code SkyDraw.serves} carries the same reasoning from the other side.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSkyMixin {

	/**
	 * The fluid alone, because the method this is at the head of already leaves out the two cases the
	 * game itself refuses a sky for, powder snow and lava, and the mob effect that blinds the camera.
	 * Water is the whole of what Sodium's own answer adds to those.
	 */
	@Inject(method = "addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;"
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
			+ "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
			+ "Lorg/joml/Matrix4fc;)V",
			at = @At("HEAD"), cancellable = true)
	private void vitrail$sky(FrameGraphBuilder frame, CameraRenderState cameraState,
			GpuBufferSlice skyFog, Matrix4fc modelView, CallbackInfo callback) {
		if (cameraState.fogType != FogType.NONE && !SkyDraw.serves()) {
			callback.cancel();
		}
	}
}
