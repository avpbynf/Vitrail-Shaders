package dev.vitrail.mixin.game;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import dev.vitrail.Vitrail;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.lwjgl.util.spvc.Spvc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the pipeline builder of 26.3 accept the two things a pack declares that the game's own
 * shaders never do: storage resources and three dimensional images.
 * <p>
 * The builder checks every descriptor a stage reflects against the bind group the pipeline
 * declares, by name and by kind, and it knows three kinds, a uniform buffer, a sampled image and a
 * texel buffer. A pack's storage buffer is declared in this engine's layouts as a uniform buffer
 * and its storage image as a sampled image, exactly as on 26.2, and it is the layout of the
 * pipeline and the descriptor pushed at the draw where each becomes what it is
 * ({@code VulkanRenderPipelineMixin}, {@code VulkanRenderPassMixin}). So here each is read as the
 * kind it is declared as, which is all the check asks. A three dimensional image is read as two
 * dimensional for the same reason 26.2 did it: the refusal is the facade's, the view bound is the
 * three dimensional one the engine allocated, and Vulkan draws with it.
 */
@Mixin(PipelineBuilder.class)
public abstract class PipelineBuilderMixin {

	@WrapOperation(method = "generateBackendCreateInfo", require = 2,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/backend/api/SpvModule$Reflection$Descriptor;"
							+ "resourceType()I"))
	private int vitrail$declaredKind(SpvModule.Reflection.Descriptor descriptor,
			Operation<Integer> original) {
		int kind = original.call(descriptor);
		if (kind == Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER) {
			return Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER;
		}

		if (kind == Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE) {
			return Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE;
		}

		return kind;
	}

	/** SpvDim3D is 2, and SpvDim2D, which the check accepts for a sampled image, is 1. */
	@WrapOperation(method = "generateBackendCreateInfo", require = 2,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/backend/api/SpvModule$Reflection$Type;"
							+ "dimensions()I"))
	private int vitrail$allow3d(SpvModule.Reflection.Type type, Operation<Integer> original) {
		int dimensions = original.call(type);
		return dimensions == 2 ? 1 : dimensions;
	}

	/**
	 * The outputs of the vertex stage of this engine's pipeline being built, by name, between the
	 * builder's reading of them and its reading of the fragment stage's inputs, on this thread.
	 */
	@Unique
	private static final ThreadLocal<Map<String, Integer>> VITRAIL$WRITTEN = new ThreadLocal<>();

	/**
	 * Links the two stages of this engine's pipelines by name, as 26.2's {@code rebind} did.
	 * <p>
	 * The builder matches a fragment input to the vertex output at the same location, and a pack's
	 * units carry no locations: the compiler assigns each stage's in the order that stage declares
	 * them, which a pack is free to make different in the two. So before the builder reads the
	 * fragment stage, every input of it is moved to the location of the vertex output of the same
	 * name, which the module writes into its own SPIR-V. The engine's translation has already made
	 * the two lists agree name for name. Only this engine's pipelines, whose shaders are named under
	 * its namespace: the game's own declare their locations and are matched as the game means them.
	 */
	@WrapOperation(method = "generateBackendCreateInfo", require = 2,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/frontend/shaders/PipelineBuilder;generateSlotMap("
							+ "Ljava/util/List;Ljava/lang/String;"
							+ "Lcom/mojang/renderpearl/api/pipeline/ShaderType;)"
							+ "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;"))
	private Int2ObjectMap<?> vitrail$linkByName(List<SpvModule.Reflection.InterfaceVariable> variables,
			String stage, ShaderType type, Operation<Int2ObjectMap<?>> original) {
		if (!stage.startsWith(Vitrail.MOD_ID)) {
			return original.call(variables, stage, type);
		}

		if (type == ShaderType.VERTEX) {
			Map<String, Integer> written = new HashMap<>();
			for (SpvModule.Reflection.InterfaceVariable output : variables) {
				written.put(output.name(), output.location());
			}

			VITRAIL$WRITTEN.set(written);
		} else {
			Map<String, Integer> written = VITRAIL$WRITTEN.get();
			VITRAIL$WRITTEN.remove();
			if (written != null) {
				for (SpvModule.Reflection.InterfaceVariable input : variables) {
					Integer location = written.get(input.name());
					if (location != null) {
						input.location(location);
					}
				}
			}
		}

		return original.call(variables, stage, type);
	}
}
