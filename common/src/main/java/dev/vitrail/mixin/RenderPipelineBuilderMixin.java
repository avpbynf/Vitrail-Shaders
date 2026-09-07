package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.vitrail.render.BufferBlending;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Lets one pipeline carry a different blend function on each colour target it writes, where the
 * device can really keep them apart.
 * <p>
 * The builder walks the active states and throws on the first two that name different functions
 * ({@code RenderPipeline:457-468}, the throw at {@code :464}). It is right to, on both of the
 * game's roads. OpenGL turns blending on and off per buffer but sets the function once for the
 * whole draw, {@code _enableBlend(i)} beside an unindexed {@code _blendFuncSeparate}
 * ({@code GlCommandEncoder:842-858}), so the last target's function would quietly stand on all of
 * them. Vulkan fills one {@code VkPipelineColorBlendAttachmentState} per slot from that slot's own
 * state ({@code VulkanRenderPipeline:178-191}), which is the shape wanted, but every element of
 * that array has to be identical unless {@code independentBlend} is enabled, and the game asks for
 * that feature nowhere.
 * <p>
 * So this is the second half of the permission {@link BufferBlending} describes, the device
 * feature being the first: {@code VulkanBackendMixin} asks for the feature and answers there
 * whether it was given, and this lifts the refusal standing in front of it.
 * <p>
 * <strong>The builder below is the one every pipeline of the process is built through</strong>, the
 * game's own and every other mod's, none of which asked for anything. So the device's answer is not
 * by itself a narrow enough condition to lift on, and {@code parting()} is the two together: a
 * Vulkan device has to have granted {@code independentBlend}, and the build has to be one of this
 * engine's own, which {@code GeometryProgram.part} marks on its thread for the length of the call.
 * Everything else keeps the game's own word, the OpenGL road and a device that refused among them,
 * and so does every builder in the process this engine is not standing in. What our own passes get
 * where the device refused is the whole program function on every attachment
 * ({@code GeometryProgram.state}), which no two states of one pipeline can disagree over.
 */
@Mixin(RenderPipeline.Builder.class)
public abstract class RenderPipelineBuilderMixin {

	/**
	 * Two functions read as one where this engine is building and the device parts its attachments,
	 * which is the comparison the refusal hangs off and the only thing here that moves.
	 */
	@WrapOperation(method = "build()Lcom/mojang/blaze3d/pipeline/RenderPipeline;", require = 1,
			at = @At(value = "INVOKE", target = "Ljava/util/Optional;equals(Ljava/lang/Object;)Z"))
	private boolean vitrail$blendApart(Optional<BlendFunction> current, Object last,
			Operation<Boolean> original) {
		return original.call(current, last) || BufferBlending.parting();
	}
}
