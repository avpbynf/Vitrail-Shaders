package dev.vitrail.mixin;

import dev.vitrail.render.RenderScale;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the render scale's swap inside one frame, on the two clears of {@code render} that
 * already separate the world from the interface.
 * <p>
 * The first clear is the world's: the whole main target, colour and depth, cleared before anything
 * of the level is drawn. Wrapping it is what lets the swap happen after the game's own resize
 * check, which runs at the head of the same method and compares the window against fields the swap
 * moves, and before the first write of the frame; the wrapper then hands the clear the textures
 * the target holds now, since the ones on the stack were read off it before the swap ran.
 * <p>
 * The second clear is the interface's, depth alone, once the level and everything drawn after it
 * are done. The upscale runs there and the restore with it, for the same reason in the other
 * direction: the clear must land on the window-sized depth the interface will really test
 * against. {@link RenderScale#endWorld} does nothing on the frames the first wrapper declined, so
 * neither wrapper asks the other what happened.
 * <p>
 * Both anchors are single calls of {@code render} and the panorama capture goes through neither:
 * it renders the level directly, so its 4096-square frames never meet the swap.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererScaleMixin {

	@Shadow
	public abstract RenderTarget mainRenderTarget();

	/**
	 * Two things, and both have to happen at the head. The recovery first: a frame that died
	 * between the two clears left the scaled set installed, and the game's resize check, which
	 * runs before its first clear, would compare the window against those scaled numbers and
	 * destroy the stand-in textures as its own. And the frame's intent, read here because the
	 * first wrapper fires before the game computes the same answer for itself: the world only
	 * renders when resources are loaded, the frame advances the game and a level exists, and a
	 * swap on any other frame would scale a loading screen.
	 */
	@Inject(method = "render", at = @At("HEAD"))
	private void vitrail$frameIntent(DeltaTracker deltaTracker, boolean advanceGameTime,
			CallbackInfo ci) {
		RenderScale.recover(mainRenderTarget());

		Minecraft minecraft = Minecraft.getInstance();
		RenderScale.frameIntent(minecraft.isGameLoadFinished() && advanceGameTime
				&& minecraft.level != null);
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures("
					+ "Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;"
					+ "Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
	private void vitrail$scaleWorld(CommandEncoder encoder, GpuTexture colour, Vector4fc clearColour,
			GpuTexture depth, double clearDepth, Operation<Void> original) {
		RenderTarget main = mainRenderTarget();
		if (RenderScale.beginWorld(main)) {
			original.call(encoder, main.getColorTexture(), clearColour, main.getDepthTexture(),
					clearDepth);
		} else {
			original.call(encoder, colour, clearColour, depth, clearDepth);
		}
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture("
					+ "Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
	private void vitrail$unscaleWorld(CommandEncoder encoder, GpuTexture depth, double clearDepth,
			Operation<Void> original) {
		RenderTarget main = mainRenderTarget();
		RenderScale.endWorld(main, encoder);
		original.call(encoder, main == null ? depth : main.getDepthTexture(), clearDepth);
	}
}
