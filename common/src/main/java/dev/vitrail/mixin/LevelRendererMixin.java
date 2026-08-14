package dev.vitrail.mixin;

import dev.vitrail.render.PackChain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.framegraph.FramePass;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the world's depth before the game throws it away for the gizmos it draws over everything.
 * <p>
 * {@code addAlwaysOnTopPass} builds the last pass of the level's frame graph, and the first thing
 * that pass does is {@code clearDepthTexture(mainRenderTarget.getDepthTexture(), 0.0)}, so that a
 * chunk border or a pathfinding node is visible through the terrain in front of it. That clear
 * lands on the main target the whole engine reads, and it lands before {@code AfterLevel}, where
 * the chain runs: every frame the game has one gizmo to draw, the depth this engine hands the pack
 * as {@code depthtex0} is the far plane over the whole screen. Nothing about that shows as itself.
 * The fog, the depth of field and the ambient occlusion of the pack simply behave as though the
 * player were looking at an empty sky, which is a plausible picture and a false one.
 * <p>
 * <strong>The wrap is on the pass body and not on the clear</strong>, and that is the only place it
 * can be: the clear is what the game means to do, so what has to move is this engine's copy, to the
 * head of the same runnable. Suppressing the clear instead would put every debug renderer of the
 * game back behind the terrain.
 * <p>
 * The pass exists only on the frames where something is drawn always on top, so on most frames this
 * never runs and the chain keeps the depth at its own point, which is then still whole.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	/**
	 * The only wrap in this package to carry {@code require}, because it is the only one whose
	 * failure to bind gives back exactly the picture it was written against: under the
	 * {@code defaultRequire: 0} the config carries, a descriptor that stopped matching would leave
	 * {@code depthtex0} reading the far plane again, in silence and with nothing on screen to tell
	 * the two apart.
	 */
	@WrapOperation(method = "addAlwaysOnTopPass",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/framegraph/FramePass;executes(Ljava/lang/Runnable;)V"),
			require = 1)
	private void vitrail$scene(FramePass pass, Runnable body, Operation<Void> original) {
		Runnable kept = () -> {
			PackChain.markSceneDepth();
			body.run();
		};

		original.call(pass, kept);
	}
}
