package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.EarlyWindow;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the early loading window stops being FML's, which is the last thing mod loading does.
 * <p>
 * The loading bar is drawn into that window right up to {@code ClientModLoader.finish()}, so taking
 * it before is a black rectangle where the progress was. What happens here is the handover and
 * nothing else; the window is closed a frame later, from {@code FlipFrameEvent}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftEarlyWindowMixin {

	@Inject(method = "<init>", at = @At(value = "INVOKE",
			target = "Lnet/neoforged/neoforge/client/loading/ClientModLoader;finish()V"),
			require = 1)
	private void vitrail$takeTheEarlyWindowOver(CallbackInfo ci) {
		EarlyWindow.takeOver();
	}
}
