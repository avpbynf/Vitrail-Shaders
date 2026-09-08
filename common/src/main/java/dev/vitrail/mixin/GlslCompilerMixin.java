package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import dev.vitrail.cache.ModuleCache;
import dev.vitrail.glsl.LoadClock;
import dev.vitrail.render.GeometryStage;
import dev.vitrail.render.RawLocals;
import dev.vitrail.render.ShaderDebugInfo;
import dev.vitrail.render.storage.StorageImages;
import org.lwjgl.util.shaderc.Shaderc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Lets a sampled or stored 3D image through the bind-group walk, gives the compiler's output its
 * zeroes before the reflection reads it, and puts {@link ModuleCache} around the one call that
 * turns a pack's GLSL into a module, which is also where {@link LoadClock} counts what that costs.
 * <p>
 * It also binds the geometry stage a pack ships: shaderc is asked for kind 3, the unit joins the
 * bind group the two other stages share, and the rebind chain runs through it rather than past it.
 * {@link GeometryStage} carries the road and the reason each piece sits where it does.
 * <p>
 * It also decides, at the compiler's own constructor, whether shaderc writes debug information
 * into every module of the session: {@link ShaderDebugInfo} says what that costs and why the
 * constructor is the only place the question can be answered.
 * <p>
 * {@code addToBindGroup} refuses anything whose SPIR-V dimension is not 2D or Cube. SpvDim3D is 2.
 * Pretending it is 2D is enough for the check; the view that is actually bound is the 3D one
 * {@code StorageImages} allocated.
 * <p>
 * The cache is around the whole method rather than inside it, and that is the difference between
 * this and caching the SPIR-V alone. {@code createIntermediary} is two costs in a row, shaderc and
 * then the SPIRV-Cross reflection reading what shaderc emitted, and the reflection is the half a
 * SPIR-V cache leaves standing. Wrapping the method skips both: the module that comes back is
 * built from the file, and nothing native runs. Nothing happens after the reflection inside the
 * method, so a module taken here is a module taken the instant it was finished, before any caller
 * has had it and before {@code rebind} has bent its bytes to one pipeline's bindings.
 * <p>
 * The clock stays on this method rather than on this engine's own call sites, because the game's
 * compiler is the funnel and the call sites are not: the background warmup goes through
 * {@code GeometryProgram}, but the terrain, every composite pass and anything a first draw or a
 * resource reload still owes goes through {@code precompilePipeline}, which lands here without a
 * line of this engine on the way. Clocking the funnel counts every road once; clocking a call site
 * counted one road and read as all of them. The span ends in a finally, so a refusal thrown by a
 * compile still costs what it cost, and a served unit is clocked like any other: the figure is what
 * getting a module took, whichever way it came.
 */
@Mixin(GlslCompiler.class)
public abstract class GlslCompilerMixin {

	/**
	 * Stage token hashed into {@link ModuleCache}'s key for a geometry unit, which travels this
	 * road under {@code ShaderType.VERTEX}. {@code shaderc_glsl_geometry_shader} is 3.
	 */
	@Unique
	private static final String GEOMETRY_STAGE = "GEOMETRY/shaderc-kind3";

	/** {@code shaderc_glsl_geometry_shader}. */
	@Unique
	private static final int GEOMETRY_KIND = Shaderc.shaderc_glsl_geometry_shader;

	@Shadow
	private static void addToBindGroup(List<VulkanBindGroupLayout.Entry> entries,
			IntermediaryShaderModule shader, RenderPipeline pipeline) throws ShaderCompileException {
		throw new AssertionError();
	}

	/**
	 * Asks shaderc for a geometry unit where {@link GeometryStage} is compiling one. The game maps
	 * its two-armed {@code ShaderType} to kinds 0 and 1 and has no third arm to add: the enum is
	 * Minecraft's, a pack's geometry stage is not the game's business, and a constant read off a
	 * flag raised for the length of one call cannot be reached by anything else compiling on
	 * another thread.
	 */
	@ModifyArg(method = "createIntermediary", require = 1, index = 2,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/util/shaderc/Shaderc;shaderc_compile_into_spv("
							+ "JLjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;J)J"))
	private int vitrail$geometryKind(int kind) {
		return GeometryStage.compiling() ? GEOMETRY_KIND : kind;
	}

	/**
	 * Compiles the pack's geometry stage, where the pipeline being built ships one, and puts what
	 * it declares into the bind group the two other stages are sharing. Hung off the second walk
	 * rather than a head injection so it runs after both of them and before the first rebind: a
	 * name only this stage declares has to be an entry before {@code rebind} looks for it, which
	 * throws over anything the list never named, and before the layout is created off that same
	 * list at the end of the method. The entries the two other stages added keep their index, an
	 * arrival at the end of the list moving none of them.
	 */
	@WrapOperation(method = "compile", require = 1,
			at = @At(value = "INVOKE", ordinal = 1,
					target = "Lcom/mojang/blaze3d/vulkan/glsl/GlslCompiler;addToBindGroup("
							+ "Ljava/util/List;Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;"
							+ "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void vitrail$geometryResources(List<VulkanBindGroupLayout.Entry> entries,
			IntermediaryShaderModule fragment, RenderPipeline pipeline, Operation<Void> original)
			throws ShaderCompileException {
		original.call(entries, fragment, pipeline);
		IntermediaryShaderModule geometry =
				GeometryStage.begin((GlslCompiler) (Object) this, pipeline);
		if (geometry != null) {
			addToBindGroup(entries, geometry, pipeline);
		}
	}

	/**
	 * Rebinds the geometry stage between the two, which is the whole point of the road: OpenGL
	 * links the stages by name and Vulkan by location, so the fragment stage has to be numbered
	 * over what the stage BEFORE it writes. That stage is the geometry one wherever a pack ships
	 * it, and the vertex stage's outputs, which the game hands in here, name the geometry stage's
	 * inputs instead.
	 */
	@WrapOperation(method = "compile", require = 1,
			at = @At(value = "INVOKE", ordinal = 1,
					target = "Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;rebind("
							+ "Ljava/util/List;Ljava/util/List;)V"))
	private void vitrail$rebindBetween(IntermediaryShaderModule fragment, List<String> written,
			List<VulkanBindGroupLayout.Entry> entries, Operation<Void> original)
			throws ShaderCompileException {
		IntermediaryShaderModule geometry = GeometryStage.building();
		if (geometry == null) {
			original.call(fragment, written, entries);

			return;
		}

		geometry.rebind(written, entries);
		original.call(fragment, GeometryStage.outputs(geometry), entries);
	}

	/**
	 * Creates the device module for the geometry stage, on the one call that has a device in hand.
	 * The handle waits on the thread for {@code VulkanRenderPipelineMixin}, which is the next call
	 * on whichever thread is building.
	 */
	@WrapOperation(method = "compile", require = 1,
			at = @At(value = "INVOKE", ordinal = 1,
					target = "Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;"
							+ "createVulkanShaderModule(Lcom/mojang/blaze3d/vulkan/VulkanDevice;)J"))
	private long vitrail$geometryModule(IntermediaryShaderModule fragment, VulkanDevice device,
			Operation<Long> original) {
		long id = original.call(fragment, device);
		GeometryStage.built(device);

		return id;
	}

	/**
	 * Skips the one call that asks shaderc for debug information, unless somebody asked for it
	 * back. {@link ShaderDebugInfo} carries the switch, what it costs and why the decision can only
	 * be taken here: shaderc turns the option on and has no call that turns it off, so the
	 * constructor is the only place, and the compiler it builds is the one every unit of the
	 * session goes through.
	 */
	@WrapOperation(method = "<init>", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/util/shaderc/Shaderc;"
							+ "shaderc_compile_options_set_generate_debug_info(J)V"))
	private void vitrail$skipDebugInfo(long options, Operation<Void> original) {
		ShaderDebugInfo.announce();
		if (ShaderDebugInfo.asked()) {
			original.call(options);
		}
	}

	@WrapOperation(method = "addToBindGroup", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vulkan/glsl/SpvSampler;dimensions()I"))
	private static int vitrail$allow3d(@Coerce Object sampler, Operation<Integer> original) {
		int dimension = original.call(sampler);
		return dimension == 2 ? 1 : dimension;
	}

	/**
	 * Between shaderc and SPIRV-Cross, on the copy the game made of the compiler's output: every
	 * variable the pack can read before writing gets the zero it reads under Iris.
	 * {@link RawLocals} carries the switch and the why. Inside the wrapped method below, so a
	 * module served from the cache was zeroed the day it was built and is not walked again, and
	 * under the state that method took at its head, so the key and the bytes agree.
	 */
	@WrapOperation(method = "createIntermediary", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;createFromSpirv("
							+ "Ljava/lang/String;Ljava/nio/ByteBuffer;)"
							+ "Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;"))
	private IntermediaryShaderModule vitrail$zeroLocals(String filename, ByteBuffer spirv,
			Operation<IntermediaryShaderModule> original) {
		return original.call(filename, RawLocals.patch(filename, spirv));
	}

	/**
	 * The text keyed on is the one this method is handed, which is two lines short of the one
	 * shaderc sees: the method splices the compiler's own two global defines in behind the
	 * version directive. They are built once in its constructor out of literals and nothing can
	 * move them, so they say the same thing about every unit and cannot tell two of them apart.
	 * The debug name is handed to {@link ModuleCache#lookup} so the rebuilt module carries this
	 * chain's identifier, and it is not hashed: that name carries the load number the disk key
	 * must not see.
	 */
	@WrapMethod(method = "createIntermediary", require = 1)
	private IntermediaryShaderModule vitrail$module(String filename, String source, ShaderType type,
			Operation<IntermediaryShaderModule> original) {
		long began = System.nanoTime();
		// The state the key is hashed under is the state the bytes are patched under, taken once
		// here: a load flipping the switch while this thread is between the two would otherwise
		// store one state's module under the other's key.
		RawLocals.begin();
		try {
			// A geometry stage comes past under VERTEX, the enum having no arm for it, and is
			// keyed under its own name all the same: the two roads compile the same text to
			// different bytes, and a blob stored under the wrong one would be served to the wrong
			// stage.
			String key = ModuleCache.keyOf(source,
					GeometryStage.compiling() ? GEOMETRY_STAGE : type.name());
			IntermediaryShaderModule served = ModuleCache.lookup(key, filename);
			if (served != null) {
				return served;
			}

			// Counted before the call and not after it: a unit a pack broke throws out of the
			// compile, and counting on the way back would leave that load short by exactly the
			// units somebody is reading the log to find.
			ModuleCache.building(filename);
			IntermediaryShaderModule built = original.call(filename, source, type);
			ModuleCache.store(key, built);

			return built;
		} finally {
			RawLocals.end();
			LoadClock.module(System.nanoTime() - began);
		}
	}
}
