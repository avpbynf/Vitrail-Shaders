package dev.vitrail.render;

import dev.vitrail.cache.ModuleCache;
import dev.vitrail.glsl.LoadClock;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Compiles a compute SPIR-V module and numbers its bindings the way the engine lays them out: one
 * set, bindings counted from nought in the order this class lists them.
 * <p>
 * The 26.3 half. On 26.2 this went through the game's {@code IntermediaryShaderModule}, whose
 * reflection named uniform buffers and sampled images only, so the storage resources were read off
 * the module by hand and appended to it before the game renumbered the lot. 26.3's module reflects
 * every descriptor itself, storage buffers and storage images included, and each descriptor it lists
 * can be renumbered in place, which is what this does: uniform and storage buffers first, then
 * storage and sampled images, in the order the reflection lists each kind.
 */
public final class ComputeShader {

	private ComputeShader() {
	}

	/**
	 * One descriptor a compute declares, in the order its binding was remapped to: a buffer,
	 * uniform or storage, or an image, sampled or storage. Which of the two storage kinds a name is
	 * is the engine's to answer by name, as it was before this record existed.
	 */
	public record Binding(boolean buffer, String name) {
	}

	/** The device module a compute was built into, and what it binds, binding by binding. */
	public record Compiled(long module, List<Binding> entries) {
	}

	/**
	 * Builds one compute unit into a device module, with the zeroes the game's compiler road gets
	 * and clocked as module work. A compute unit is not kept on disk on this game: {@link ModuleCache}
	 * keeps what goes through the game's compiler, and a compute does not, a pack shipping a
	 * handful.
	 *
	 * @param source  the unit's text as shaderc is to read it
	 * @param stage   the stage token a store would key this road under
	 * @param compile shaderc for this road, answering null where it refused the unit
	 * @return the module, or null where shaderc refused the unit
	 * @throws Exception whatever the reflection or the module creation threw
	 */
	public static @Nullable Compiled build(VulkanDevice vulkan, String label, String source,
			String stage, Function<String, @Nullable ByteBuffer> compile) throws Exception {
		long began = System.nanoTime();
		RawLocals.begin();
		try {
			ByteBuffer spirv = compile.apply(source);
			ModuleCache.building(label);
			if (spirv == null) {
				return null;
			}

			// The same zeroes the game's compiler road gets in GlslCompilerMixin, and before the
			// reflection, which has to read the module the driver will read.
			try (SPIRVModule module = new SPIRVModule(RawLocals.patch(label, spirv),
					ShaderType.VERTEX)) {
				return compile(vulkan, module);
			}
		} finally {
			RawLocals.end();
			LoadClock.module(System.nanoTime() - began);
		}
	}

	/**
	 * Renumbers the module's descriptors and creates the device module. The type handed to the
	 * module is only what it files the reflection under; a compute has no stage inputs or outputs
	 * for it to read either way.
	 */
	private static Compiled compile(VulkanDevice vulkan, SpvModule module) throws Exception {
		SpvModule.Reflection reflection = module.reflect();
		List<Binding> bindings = new ArrayList<>();
		int binding = 0;
		for (int type : new int[] {Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
				Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE,
				Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE}) {
			boolean buffer = type == Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER
					|| type == Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER;
			for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors(type)) {
				descriptor.descriptorSetIndex(0);
				descriptor.binding(binding++);
				bindings.add(new Binding(buffer, descriptor.name()));
			}
		}

		for (int type : new int[] {Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
				Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS}) {
			if (!reflection.descriptors(type).isEmpty()) {
				throw new IllegalStateException("a separate image or sampler, which the engine "
						+ "binds on no road, is declared by " + reflection.descriptors(type)
						.getFirst().name());
			}
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default()
					.pCode(module.spv());
			LongBuffer handle = stack.callocLong(1);
			int result = VK12.vkCreateShaderModule(vulkan.vkDevice(), info, null, handle);
			if (result != VK12.VK_SUCCESS) {
				throw new IllegalStateException("vkCreateShaderModule answered " + result);
			}

			return new Compiled(handle.get(0), List.copyOf(bindings));
		}
	}
}
