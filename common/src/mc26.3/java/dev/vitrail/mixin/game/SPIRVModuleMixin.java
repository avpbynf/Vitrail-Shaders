package dev.vitrail.mixin.game;

import dev.vitrail.render.SamplerReach;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.IntBuffer;
import java.util.List;
import java.util.Set;

/**
 * Leaves out of a module's reflection the sampled images {@link SamplerReach} found it never
 * reaches, which is what narrows the layout 26.3 builds from that reflection.
 * <p>
 * The names are set on the module as it is made, before anything has asked for the reflection,
 * which the module only works out when first asked; a module this engine did not compile is never
 * handed any and keeps every descriptor.
 */
@Mixin(SPIRVModule.class)
public abstract class SPIRVModuleMixin implements SamplerReach.Narrowable {

	@Unique
	private Set<String> vitrail$unreached = Set.of();

	@Override
	public void vitrail$unreached(Set<String> unreached) {
		this.vitrail$unreached = unreached;
	}

	@WrapOperation(method = "doReflection", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/frontend/shaders/SPIRVModule;"
							+ "generateDescriptorList(JJLjava/nio/IntBuffer;I)Ljava/util/List;"))
	private List<SpvModule.Reflection.Descriptor> vitrail$reached(long compiler, long resources,
			IntBuffer spirv, int type, Operation<List<SpvModule.Reflection.Descriptor>> original) {
		return SamplerReach.keep(original.call(compiler, resources, spirv, type), type,
				this.vitrail$unreached);
	}
}
