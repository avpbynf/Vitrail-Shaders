package dev.vitrail.fabric.mixin;

import dev.vitrail.platform.EngineStages;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the pack is read, which on Fabric is not where the mod is handed the game.
 * <p>
 * Fabric calls a client entry point from inside the {@code Minecraft} constructor, and near the top
 * of it: measured on the first Fabric run of this mod, where the entry point ran nine seconds before
 * the line that names the graphics backend and with {@code Minecraft.options} still null, which the
 * pack's own reading needs for the language. That is a different moment from NeoForge's client
 * setup, and reading a pack there gives a backend called {@code unknown} and a chain that throws.
 * <p>
 * The tail of the same constructor is not the moment either, and it was until it cost every resource
 * pack of an install. The constructor starts the initial resource reload before it returns, so a
 * pack read at its tail blocks the render thread AFTER that reload's background preparation has
 * begun. When the read outlasts the preparation, the first {@code runAllTasks} of
 * the first tick uploads the stitched atlases before the first {@code GameRenderer.render}
 * ({@code Minecraft.runTick} queues tasks above the frame), and
 * {@code TextureAtlas.uploadInitialContents} binds default uniforms that only
 * {@code GameRenderer.render} writes: "Missing uniform Globals", and the game throws away every
 * selected resource pack. Measured on 0.4.1-beta.1 with Distant Horizons installed, whose
 * {@code dh_} programs lengthen the read past the preparation; NeoForge never sees it because its
 * client setup runs on a mod loading worker, off the render thread and before the reload exists.
 * <p>
 * {@code onGameLoadFinished} is the moment that matches: reached at most once, on the render thread
 * with no pass open ({@code LoadingOverlay.tick} calls it, not the overlay's render), after the
 * game has drawn frames for the whole of the initial reload, and before {@code buildInitialScreens}
 * hands the player a screen or a quick play world. At most and not exactly: a boot whose reload
 * fails twice over gives up through {@code abortResourcePackRecovery} and never comes this way,
 * but that boot never draws a level either, so a pack unread is nothing it would have shown.
 */
@Mixin(Minecraft.class)
public abstract class ClientSetupMixin {

	@Inject(method = "onGameLoadFinished", at = @At("HEAD"), require = 1)
	private void vitrail$clientSetup(CallbackInfo ci) {
		EngineStages.clientSetup();
	}
}
