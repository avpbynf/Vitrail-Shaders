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
 * The tail of the same constructor is the moment that matches: the device is up, the options are
 * read, the level renderer exists, and no frame has been drawn.
 */
@Mixin(Minecraft.class)
public abstract class ClientSetupMixin {

	@Inject(method = "<init>", at = @At("TAIL"), require = 1)
	private void vitrail$clientSetup(CallbackInfo ci) {
		EngineStages.clientSetup();
	}
}
