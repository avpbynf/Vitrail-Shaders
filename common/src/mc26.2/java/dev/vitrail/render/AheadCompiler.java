package dev.vitrail.render;

import dev.vitrail.mixin.access.GpuDeviceAccessor;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.minecraft.resources.Identifier;

/**
 * What the pack-load worker compiles a pipeline with off the render thread, so the first draw finds
 * the half second of shaderc already paid. One per worker task, closed when the task ends.
 * <p>
 * The 26.2 half, and deliberately not {@code precompilePipeline}: the device keeps its results in
 * plain maps only the render thread may touch, and its compiler is one shared instance on the
 * same rule. Everything used here instead is safe off the thread. This worker's own
 * {@code GlslCompiler} carries shaderc, the SPIRV-Cross reflection opens a context per call, and
 * the three calls underneath ({@code vkCreateShaderModule}, the set layout, the pipelines) create
 * device-level objects Vulkan lets any thread create. What the worker may not do is write the
 * cache, and {@link Ahead#adopt} does that half on the render thread.
 */
public final class AheadCompiler implements AutoCloseable {

	private final VulkanDevice device;
	private final GlslCompiler compiler;

	private AheadCompiler(VulkanDevice device) {
		this.device = device;
		this.compiler = new GlslCompiler();
	}

	/** Whether this device can compile off the render thread, which takes the Vulkan backend. */
	public static boolean available(GpuDevice device) {
		return ((GpuDeviceAccessor) device).vitrail$backend() instanceof VulkanDevice;
	}

	/** A compiler for one worker task. Only to be asked where {@link #available} said yes. */
	public static AheadCompiler open(GpuDevice device) {
		return new AheadCompiler((VulkanDevice) ((GpuDeviceAccessor) device).vitrail$backend());
	}

	/**
	 * Builds one pipeline through the same public steps the device takes.
	 *
	 * @throws Refused where the pack's GLSL is refused, with the compiler's reason
	 */
	public Ahead build(RenderPipeline pipeline, ShaderSource source) throws Refused {
		try {
			IntermediaryShaderModule vertex = intermediary(pipeline, source,
					GraphicsApi.vertexShader(pipeline), ShaderType.VERTEX);
			try {
				IntermediaryShaderModule fragment = intermediary(pipeline, source,
						GraphicsApi.fragmentShader(pipeline), ShaderType.FRAGMENT);
				try {
					GlslCompiler.CompiledModules modules =
							this.compiler.compile(this.device, pipeline, vertex, fragment);
					return new Ahead(VulkanRenderPipeline.compile(this.device, modules.layout(),
							pipeline, modules.vertex(), modules.fragment()));
				} finally {
					// vkCreateShaderModule consumes pCode at the call, by spec, so the buffers
					// behind the intermediaries are done once compile returns. The device keeps
					// its own in a cache instead, which is why its path has no close: here nothing
					// keeps them.
					fragment.close();
				}
			} finally {
				vertex.close();
			}
		} catch (ShaderCompileException e) {
			throw new Refused(e.getMessage());
		}
	}

	/** One stage the way the device reads it: the pipeline's defines injected, then shaderc. */
	private IntermediaryShaderModule intermediary(RenderPipeline pipeline, ShaderSource source,
			Identifier id, ShaderType type) throws ShaderCompileException {
		String text = GraphicsApi.shaderText(source, id, type);
		if (text == null) {
			throw new ShaderCompileException("no source for " + id);
		}

		return this.compiler.createIntermediary(id.toDebugFileName(),
				GlslPreprocessor.injectDefines(text, pipeline.getShaderDefines()), type);
	}

	@Override
	public void close() {
		this.compiler.close();
	}

	/**
	 * A pipeline the worker built, waiting for the render thread to hand it to the device's cache.
	 */
	public static final class Ahead {

		private final VulkanRenderPipeline built;

		private Ahead(VulkanRenderPipeline built) {
			this.built = built;
		}

		/**
		 * Hands the pipeline to the device's cache under {@code pipeline}. Render thread only.
		 *
		 * @return false where the cache already held one for that key, in which case the caller
		 *         still owns this one and has to {@link #destroy} it
		 */
		public boolean adopt(GpuDevice device, RenderPipeline pipeline) {
			return ((GpuDeviceAccessor) device).vitrail$backend() instanceof StalePipelines cache
					&& cache.vitrail$adopt(pipeline, this.built);
		}

		/** Frees a pipeline nothing ever bound. */
		public void destroy() {
			this.built.destroy();
		}
	}

	/** The pack's GLSL was refused on the worker's road. */
	public static final class Refused extends Exception {

		Refused(String message) {
			super(message);
		}
	}
}
