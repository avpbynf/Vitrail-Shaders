package dev.vitrail.render;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;

import java.util.concurrent.CompletionException;

/**
 * What the pack-load worker compiles a pipeline with off the render thread, so the first draw finds
 * the half second of shaderc already paid. One per worker task, closed when the task ends.
 * <p>
 * The 26.3 half, and the road the game itself now takes: {@code compilePipeline} is built to run
 * on any executor, pooling shaderc compilers under a lock and creating the device objects the
 * way Vulkan lets any thread create them. So the worker compiles through the device like the
 * render thread would, on its own thread, and {@link Ahead#adopt} files the result where
 * {@link GraphicsApi#compile} will find it. Nothing here needs the 26.2 road's own compiler.
 */
public final class AheadCompiler implements AutoCloseable {

	private final GpuDevice device;

	private AheadCompiler(GpuDevice device) {
		this.device = device;
	}

	/** Whether this device can compile off the render thread, which this game's device always can. */
	public static boolean available(GpuDevice device) {
		return true;
	}

	/** A compiler for one worker task. */
	public static AheadCompiler open(GpuDevice device) {
		return new AheadCompiler(device);
	}

	/**
	 * Builds one pipeline on the calling thread.
	 *
	 * @throws Refused where the pack's GLSL is refused; the device has logged the compiler's reason
	 *                 itself by then
	 */
	public Ahead build(RenderPipeline pipeline, ShaderSource source) throws Refused {
		CompiledRenderPipeline compiled;
		try {
			compiled = this.device.compilePipeline(pipeline, source, Runnable::run).join()
					.finishCompile();
		} catch (CompletionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw new Refused(String.valueOf(cause.getMessage()));
		}

		if (compiled == null) {
			throw new Refused("the device refused it, as the line above says");
		}

		return new Ahead(compiled);
	}

	@Override
	public void close() {
		// Holds nothing of its own: the device pools the compilers.
	}

	/**
	 * A pipeline the worker built, waiting for the render thread to file it where
	 * {@link GraphicsApi#compile} looks.
	 */
	public static final class Ahead {

		private final CompiledRenderPipeline built;

		private Ahead(CompiledRenderPipeline built) {
			this.built = built;
		}

		/**
		 * Files the pipeline under {@code pipeline}.
		 *
		 * @return false where one was already filed under that key, in which case the caller still
		 *         owns this one and has to {@link #destroy} it
		 */
		public boolean adopt(GpuDevice device, RenderPipeline pipeline) {
			return GraphicsApi.adopt(pipeline, this.built);
		}

		/** Frees a pipeline nothing ever bound. */
		public void destroy() {
			this.built.close();
		}
	}

	/** The pack's GLSL was refused on the worker's road. */
	public static final class Refused extends Exception {

		Refused(String message) {
			super(message);
		}
	}
}
