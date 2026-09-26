package dev.vitrail.render;

import dev.vitrail.mixin.access.GpuDeviceAccessor;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BiFunction;

/**
 * The calls into the game's graphics API that Minecraft 26.2 and 26.3 spell differently, one method
 * each, so the code making them is shared and the difference lives in one file per game.
 * <p>
 * <strong>This is the 26.2 half, and every method is the call the engine made before there were
 * two games,</strong> unchanged: a 26.2 jar runs exactly what it ran before this class existed. The
 * 26.3 half beside it under {@code src/mc26.3/} is where a method has to do more than rename, and
 * its javadoc says what and why.
 */
public final class GraphicsApi {

	private GraphicsApi() {
	}

	/** Binds one sampled image by the name a shader declares it under. */
	public static void bindTexture(RenderPass pass, String name, GpuTextureView view,
			GpuSampler sampler) {
		pass.bindTexture(name, view, sampler);
	}

	/**
	 * Sets the pipeline a pass draws with. The device looks up what {@link #compile} built for it,
	 * and compiles it from the game's own sources where nothing was.
	 */
	public static void setPipeline(RenderPass pass, RenderPipeline pipeline) {
		pass.setPipeline(pipeline);
	}

	/**
	 * Compiles a pipeline into the device's cache, or looks it up there. A resource reload empties
	 * that cache, which is why the engine asks again rather than keeping the answer.
	 *
	 * @param source where the shader text comes from, or null for the game's own sources
	 * @return what the device holds for the pipeline, which {@link #valid} says the use of
	 */
	public static CompiledRenderPipeline compile(GpuDevice device, RenderPipeline pipeline,
			@Nullable ShaderSource source) {
		return device.precompilePipeline(pipeline, source);
	}

	/** Whether a compiled pipeline can be drawn with. */
	public static boolean valid(@Nullable CompiledRenderPipeline compiled) {
		return compiled != null && compiled.isValid();
	}

	/**
	 * A shader source answering by id and stage out of a function, which on this game is all a
	 * source is.
	 */
	public static ShaderSource source(BiFunction<Identifier, ShaderType, @Nullable String> shaders) {
		return shaders::apply;
	}

	/** The text a source holds for one stage, or null where it holds none. */
	public static @Nullable String shaderText(ShaderSource source, Identifier id, ShaderType type) {
		return source.get(id, type);
	}

	/** A bind group of sampled images and nothing else, in the order named. */
	public static BindGroupLayout samplers(String... names) {
		BindGroupLayout.Builder builder = BindGroupLayout.builder();
		for (String name : names) {
			builder.withSampler(name);
		}

		return builder.build();
	}

	/**
	 * A bind group of one uniform block followed by sampled images, in the order named, which is
	 * the shape every full screen pass of the engine's own declares.
	 */
	public static BindGroupLayout blockAndSamplers(String block, String... samplers) {
		BindGroupLayout.Builder builder = BindGroupLayout.builder()
				.withUniform(block, UniformType.UNIFORM_BUFFER);
		for (String name : samplers) {
			withSampler(builder, name);
		}

		return builder.build();
	}

	/** Declares one sampled image on a bind group being built. */
	public static BindGroupLayout.Builder withSampler(BindGroupLayout.Builder builder, String name) {
		return builder.withSampler(name);
	}

	/** The id a pipeline names its vertex stage by. */
	public static Identifier vertexShader(RenderPipeline pipeline) {
		return pipeline.getVertexShader();
	}

	/** The id a pipeline names its fragment stage by. */
	public static Identifier fragmentShader(RenderPipeline pipeline) {
		return pipeline.getFragmentShader();
	}

	/** The state of a pipeline's first colour target, or null where it writes none. */
	public static @Nullable ColorTargetState colorTarget(RenderPipeline pipeline) {
		return pipeline.getColorTargetState();
	}

	/** The state of every colour target a pipeline writes, one per attachment, in order. */
	public static List<@Nullable ColorTargetState> colorTargets(RenderPipeline pipeline) {
		@Nullable ColorTargetState[] states = pipeline.getColorTargetStates();
		return states == null ? List.of() : Arrays.asList(states);
	}

	/**
	 * A texture target of one colour format, with a depth image of the game's default format where
	 * {@code depth} asks for one.
	 */
	public static TextureTarget textureTarget(@Nullable String label, int width, int height,
			boolean depth, GpuFormat colour) {
		return new TextureTarget(label, width, height, depth, colour);
	}

	/** Whether a render target carries a depth image beside its colour. */
	public static boolean hasDepth(RenderTarget target) {
		return target.useDepth;
	}

	/** The area a pass descriptor restricts drawing to, or null where it draws everywhere. */
	public static RenderPass.@Nullable RenderArea renderArea(RenderPassDescriptor descriptor) {
		return descriptor.renderArea;
	}

	/**
	 * A built descriptor with its depth attachment replaced, which on this game is the same object
	 * filled in place.
	 */
	public static RenderPassDescriptor withDepthAttachment(RenderPassDescriptor descriptor,
			GpuTextureView view, OptionalDouble clear) {
		return descriptor.withDepthAttachment(view, clear);
	}

	/**
	 * Takes every compiled pipeline that declares the game's entity format out of the device's
	 * cache, so the next bind compiles it against the mesh now in force, and answers with their
	 * keys. The compiled objects are kept aside and freed at the next safe purge, which is
	 * {@link StalePipelines}'s whole reason to exist on this game.
	 *
	 * @return the keys taken out, or null where the device is not the one this engine keeps a
	 *         cache hook on
	 */
	public static @Nullable List<RenderPipeline> dropEntityPipelines(GpuDevice device) {
		return ((GpuDeviceAccessor) device).vitrail$backend() instanceof StalePipelines stale
				? stale.vitrail$dropEntityPipelines()
				: null;
	}

	/**
	 * Ends the pass the level is being drawn through, where the game draws the whole level through
	 * one. This game opens a pass per phase, so there is none to end.
	 */
	public static void suspendLevelPass() {
		// Nothing: every phase of the level closes its own pass on this game.
	}

	/**
	 * Gives a pipeline the room Sodium's region offset takes. This game pushes it to every pipeline
	 * whose name says sodium, whatever the pipeline declares, so there is nothing to declare.
	 */
	public static void withSodiumPushConstants(RenderPipeline.Builder builder) {
		// Nothing: the backend decides by the pipeline's name on this game.
	}

	/**
	 * Carries a pipeline's push constants over to a builder rebuilding it. Nothing to carry on this
	 * game, where the name alone decides them.
	 */
	public static void copyPushConstants(RenderPipeline.Builder builder, RenderPipeline from) {
		// Nothing: the rebuilt pipeline keeps the name that decides them.
	}
}
