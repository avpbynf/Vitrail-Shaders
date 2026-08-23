package dev.vitrail.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The backend behind an encoder wrapper, which is where a blit can be recorded. The wrapper is a
 * new object every call and forwards; the backend is the one that holds the command buffer.
 */
@Mixin(CommandEncoder.class)
public interface CommandEncoderAccessor {

	@Invoker("backend")
	CommandEncoderBackend vitrail$backend();
}
