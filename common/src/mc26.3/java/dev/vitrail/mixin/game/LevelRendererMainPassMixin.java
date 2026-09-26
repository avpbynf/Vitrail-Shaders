package dev.vitrail.mixin.game;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.vitrail.render.LevelPass;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Hands the level the pass {@link LevelPass} makes out of the one it opens for the whole main pass.
 * <p>
 * The opening is the one call in the main pass's runnable that opens a pass on the main target, and
 * it is wrapped whole: the first real pass is the game's own, opened as the game opens it, and every
 * later one is opened by the same call with the same arguments, which empty nothing. Both loaders
 * build the same runnable under the same name, NeoForge's stage events being posted inside the
 * methods it calls rather than inside it.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMainPassMixin {

	@WrapOperation(method = "lambda$addMainPass$0", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass("
							+ "Ljava/util/function/Supplier;"
							+ "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;"
							+ "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)"
							+ "Lcom/mojang/renderpearl/api/commands/RenderPass;"))
	private RenderPass vitrail$levelPass(CommandEncoder encoder, Supplier<String> label,
			GpuTextureView colour, Optional<?> clearColour, GpuTextureView depth,
			OptionalDouble clearDepth, Operation<RenderPass> original) {
		RenderPass first = original.call(encoder, label, colour, clearColour, depth, clearDepth);
		return LevelPass.open(first, colour, depth, () -> original.call(encoder, label, colour,
				Optional.empty(), depth, OptionalDouble.empty()));
	}
}
