package dev.vitrail.fabric.mixin;

import dev.vitrail.platform.EngineStages;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two moments everything a pack costs may be handed back: leaving a world, and leaving the game.
 * <p>
 * Both are at the head of their method, and both for the same reason: what is released here is
 * owned by the graphics device, and the device is still alive at the head and not at the return.
 * {@code close} hands the renderer, the shader manager and finally the whole render system back a
 * few lines down; {@code disconnect} drops the level a dozen lines down.
 */
@Mixin(Minecraft.class)
public abstract class ClientLifecycleMixin {

	/**
	 * The three argument overload, which is the one the other two and every screen that leaves a
	 * world end up in. Guarded on there being a level, which is what makes this the counterpart of
	 * NeoForge's logging out event rather than of every call: {@code disconnect} is also how the
	 * game leaves a connection it never got a world out of.
	 */
	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V",
			at = @At("HEAD"), require = 1)
	private void vitrail$leaveWorld(Screen screen, boolean keepResourcePacks, boolean stopSound,
			CallbackInfo ci) {
		if (((Minecraft) (Object) this).level == null) {
			return;
		}

		EngineStages.leaveWorld();
	}

	@Inject(method = "close", at = @At("HEAD"), require = 1)
	private void vitrail$closeClient(CallbackInfo ci) {
		EngineStages.closeClient();
	}
}
