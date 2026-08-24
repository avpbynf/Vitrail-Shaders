package dev.vitrail.mixin;

import dev.vitrail.render.StartupGuard;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The one read the whole "last startup ended badly" rescue hangs off.
 * <p>
 * The constructor reads {@code startedCleanly} ONCE into a local, then tests that local twice: once
 * to drop fullscreen, once to walk the preferred graphics API down to Default and then to OpenGL.
 * Answering that single read is therefore the whole intervention, and it is why this is one hook
 * rather than three: no message is suppressed by hand and no branch is duplicated here, so a change
 * in what the game rescues follows automatically.
 * <p>
 * The write just after it is untouched. The game still sets the flag false and saves it, so the
 * marker keeps working for everything else that reads it, and the next clean startup still clears
 * it the way it always did.
 *
 * @see StartupGuard
 */
@Mixin(Minecraft.class)
public abstract class MinecraftStartupGuardMixin {

	@WrapOperation(method = "<init>", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/Options;startedCleanly:Z", opcode = Opcodes.GETFIELD),
			require = 1)
	private boolean vitrail$keepTheChosenBackend(Options options, Operation<Boolean> original) {
		return StartupGuard.startedCleanly(options, original.call(options));
	}
}
