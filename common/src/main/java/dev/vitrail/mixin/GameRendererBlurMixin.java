package dev.vitrail.mixin;

import dev.vitrail.screen.SettingsScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lets the settings screen fade the menu blur out instead of switching it off late.
 * <p>
 * The blur has two halves in this game and a screen only owns one of them. Asking for it is
 * {@code Screen.extractBlurredBackground}, which is a yes or a no; how wide it is comes from the
 * option, carried to the shader in the sixth argument of {@code GlobalSettingsUniform.update},
 * {@code menuBlurRadius}. A screen that fades the first half alone therefore fades nothing: the
 * widgets go in one frame, the blur stays at its full width for as long as the fade takes to fall
 * under one, and then goes out in a single frame. That is what the eye looked like before this.
 * <p>
 * Iris does both halves and this is its second one, {@code MixinGameRenderer.iris$modifyBlur} at
 * line 67 of its own tree, on the same call and the same argument.
 * <p>
 * Held DOWN rather than replaced, so that a value the option or another mod has already lowered is
 * never raised back up, and only while this screen is the one open: every other frame in the game
 * passes through untouched.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererBlurMixin {

	// require = 1 because the failure is otherwise invisible: an argument nobody modifies is an
	// argument the game passes through, so a signature that moves would give back exactly the late
	// switch this exists to remove, with nothing said anywhere.
	@ModifyArg(method = "render",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;"
							+ "update(IIDJLnet/minecraft/client/DeltaTracker;I"
							+ "Lnet/minecraft/world/phys/Vec3;Z)V"),
			index = 5, require = 1)
	private int vitrail$fadeMenuBlur(int radius) {
		return Minecraft.getInstance().gui.screen() instanceof SettingsScreen settings
				? Math.min(radius, settings.blurRadius())
				: radius;
	}
}
