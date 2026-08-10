package dev.vitrail.neoforge.mixin;

import dev.vitrail.pack.source.ShaderProperties;
import dev.vitrail.render.CloudDraw;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the loaded pack overrule the user's cloud setting, which is the one directive of the sky
 * family that is not a yes or a no.
 * <p>
 * <strong>Here and not at the renderer</strong>, and that is the whole of why this is a second mixin
 * rather than two lines inside {@link CloudRendererMixin}. This accessor is read twice a frame and
 * for two different things: {@code GameRenderer} copies it into the render state, and
 * {@code LevelRenderer} decides from that state whether to add a cloud pass to the frame graph at
 * all. Answered at the renderer instead, a pack writing {@code clouds=off} would still have its pass
 * opened and its buffers filled every frame, for a draw that had to be thrown away.
 * <p>
 * <strong>Only where this engine really draws the clouds</strong>, which {@code CloudDraw.setting}
 * holds and this file deliberately does not repeat: the word means "draw them this way, I have
 * written for it", and with the game's own shader behind it {@code off} would take the clouds away
 * and put nothing in their place.
 * <p>
 * The priority is Iris's, at the same call of the same method and for the reason its own comment
 * gives: Sodium replaces this accessor outright, so a mixin applied before it is overwritten.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris, LGPL-3.0</a>
 */
@Mixin(value = Options.class, priority = 1010)
public abstract class OptionsCloudsMixin {

	@Inject(method = "getCloudStatus", at = @At("HEAD"), cancellable = true)
	private void vitrail$clouds(CallbackInfoReturnable<CloudStatus> callback) {
		CloudDraw.setting().map(OptionsCloudsMixin::vitrail$status).ifPresent(callback::setReturnValue);
	}

	/**
	 * The game's own word for what the pack asked for. {@code DEFAULT} never reaches here, being
	 * exactly the answer {@code CloudDraw.setting} hands back as empty.
	 */
	private static CloudStatus vitrail$status(ShaderProperties.CloudSetting asked) {
		return switch (asked) {
			case OFF -> CloudStatus.OFF;
			case FAST -> CloudStatus.FAST;
			default -> CloudStatus.FANCY;
		};
	}
}
