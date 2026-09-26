package dev.vitrail.mixin.access;

import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The backend behind the encoder the game hands out.
 * <p>
 * The 26.3 half: the encoder every caller holds is the {@code CommandEncoder} interface and the
 * object behind it is {@code FrontendCommandEncoder}, whose {@code backend()} answers the same
 * backend the 26.2 encoder kept.
 */
@Mixin(FrontendCommandEncoder.class)
public interface CommandEncoderAccessor {

	@Invoker("backend")
	CommandEncoderBackend vitrail$backend();
}
