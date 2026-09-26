package dev.vitrail.render;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.vitrail.Vitrail;
import dev.vitrail.mixin.access.RenderPipelineAccessor;
import dev.vitrail.mixin.game.PipelineCacheAccessor;
import dev.vitrail.mixin.game.RenderSystemAccessor;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * The calls into the game's graphics API that Minecraft 26.2 and 26.3 spell differently, one method
 * each, so the code making them is shared and the difference lives in one file per game.
 * <p>
 * <strong>This is the 26.3 half.</strong> Most methods are a rename: a sampled image is bound with
 * {@code setUniform}, a sampler is a uniform of type {@code COMBINED_IMAGE_SAMPLER}, a pipeline
 * names its stages in one map. One is not, and it is the reason this class holds state at all.
 * <p>
 * <strong>26.3 has no pipeline cache on the device.</strong> On 26.2 {@code precompilePipeline}
 * compiled into a map the device owned and {@code setPipeline} took the pipeline and looked the
 * compiled one up there, so every call site in this engine could ask for a pipeline by its
 * description and let the device remember. 26.3 compiles through {@code compilePipeline}, which
 * hands back a future and keeps nothing, and {@code setPipeline} takes the compiled object. The
 * game keeps its own pipelines in a {@code PipelineCache} it replaces whole at every resource
 * reload. So the engine keeps its own here, {@link #COMPILED}, with the semantics the device map
 * had on 26.2, and the call sites keep asking by description:
 * <ul>
 * <li>{@link #compile} is a computeIfAbsent, failures included, so a pipeline the pack broke is not
 * compiled again every frame;</li>
 * <li>{@link #setPipeline} finds what {@link #compile} kept, and falls back on the game's own cache
 * for the game's own pipelines, which is what the 26.2 device did for a key it did not hold;</li>
 * <li>{@link #purge} empties it where the game empties its own, at a resource reload, carrying the
 * live pack's pipelines over exactly as the 26.2 device mixin did.</li>
 * </ul>
 * Closing is safe at any instant on this game, which it was not on 26.2: a compiled pipeline's
 * close queues its destruction behind the frames still recording it.
 */
public final class GraphicsApi {

	/**
	 * Whether the live pack's pipelines cross a resource reload rather than being compiled again
	 * behind a held-back world. The same switch, and the same default, as on 26.2.
	 */
	private static final boolean KEEP_ACROSS_RELOAD = Boolean.parseBoolean(
			System.getProperty("vitrail.keepPackAcrossReload", "true"));

	/**
	 * Every pipeline this engine compiled, by the description it was compiled from. Concurrent
	 * because the pack-load worker compiles into it off the render thread; the value is a holder
	 * because a refusal is kept as well, and the map takes no null.
	 */
	private static final Map<RenderPipeline, Held> COMPILED = new ConcurrentHashMap<>();

	/**
	 * The description each backend pipeline in {@link #COMPILED} was compiled from, by the backend
	 * object. The backend's own pass only holds that object when it pushes a draw's descriptors,
	 * and the questions asked there, which shadow samplers compare, are keyed on the description,
	 * as they were on 26.2 where the backend pipeline carried it. Identity is what the key needs
	 * and what it gets: backend pipelines override neither equals nor hashCode.
	 */
	private static final Map<Object, RenderPipeline> DESCRIBED = new ConcurrentHashMap<>();

	/**
	 * The description each compiled pipeline was compiled from, by the compiled object, for the
	 * game's pipelines as well as this engine's. 26.2 set a pipeline on a pass by its description;
	 * 26.3 sets the compiled object, so the hooks on a pass that asked which pipeline it was, the
	 * particle swap among them, ask this instead. The compiled objects are records, whose equality
	 * would compare every component, so they are keyed by identity through {@link Same}. The game's
	 * entries are dropped where the game drops its cache, in {@link #purge}.
	 */
	private static final Map<Same, RenderPipeline> SOURCES = new ConcurrentHashMap<>();

	/**
	 * The bytes Sodium pushes at every draw of a chunk layer, its region offset, which its own chunk
	 * pipelines declare with {@code withPushConstantSize(20)} in {@code ShaderChunkRenderer}.
	 */
	private static final int SODIUM_PUSH_CONSTANTS = 20;

	private GraphicsApi() {
	}

	/** A compiled pipeline, or the refusal a compile ended on. */
	private record Held(@Nullable CompiledRenderPipeline compiled) {
	}

	/** A key that compares the object it holds by identity. */
	private record Same(Object held) {

		@Override
		public boolean equals(Object other) {
			return other instanceof Same same && same.held == this.held;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this.held);
		}
	}

	/** Binds one sampled image by the name a shader declares it under. */
	public static void bindTexture(RenderPass pass, String name, GpuTextureView view,
			GpuSampler sampler) {
		pass.setUniform(name, view, sampler);
	}

	/**
	 * Sets the pipeline a pass draws with: what {@link #compile} kept for it, or the game's own
	 * compiled pipeline where this engine compiled none.
	 *
	 * @throws IllegalStateException where neither holds one, which is where 26.2's device threw too
	 */
	public static void setPipeline(RenderPass pass, RenderPipeline pipeline) {
		Held held = COMPILED.get(pipeline);
		CompiledRenderPipeline compiled = held != null ? held.compiled()
				: RenderSystem.getCompiledPipelineNullable(pipeline);
		if (compiled == null || compiled.isClosed()) {
			throw new IllegalStateException("There is no compiled pipeline for "
					+ pipeline.getLocation());
		}

		pass.setPipeline(compiled);
	}

	/**
	 * Compiles a pipeline, or answers what an earlier call compiled for it. Synchronous, on the
	 * calling thread, which is what every caller of the 26.2 {@code precompilePipeline} expected;
	 * the pack-load worker calls it off the render thread, which this game allows.
	 * <p>
	 * A compile that throws stores nothing and rethrows what it threw, unwrapped, so a caller
	 * catching the driver's refusal catches it here as it did on 26.2.
	 *
	 * @param source where the shader text comes from, or null for the game's own sources, in which
	 *               case the game's own cache compiles and keeps it
	 * @return the compiled pipeline, or null where the compile refused it
	 */
	public static @Nullable CompiledRenderPipeline compile(GpuDevice device, RenderPipeline pipeline,
			@Nullable ShaderSource source) {
		if (source == null) {
			return RenderSystem.getCompiledPipelineNullable(pipeline);
		}

		Held held = COMPILED.get(pipeline);
		if (held != null) {
			return held.compiled();
		}

		CompiledRenderPipeline built;
		try {
			built = device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
		} catch (CompletionException e) {
			if (e.getCause() instanceof RuntimeException cause) {
				throw cause;
			}

			if (e.getCause() instanceof Error cause) {
				throw cause;
			}

			throw e;
		}

		Held raced = COMPILED.putIfAbsent(pipeline, new Held(built));
		if (raced != null) {
			// Another thread compiled the same description meanwhile. Its copy is the one callers
			// may already be holding, so ours is the one that goes.
			if (built != null) {
				built.close();
			}

			return raced.compiled();
		}

		describe(pipeline, built);
		return built;
	}

	/** Whether a compiled pipeline can be drawn with. */
	public static boolean valid(@Nullable CompiledRenderPipeline compiled) {
		return compiled != null && !compiled.isClosed();
	}

	/**
	 * Files a pipeline compiled elsewhere under the description it was compiled from, so the next
	 * {@link #compile} of that description is a lookup.
	 *
	 * @return false where one was already filed, in which case nothing moved and the caller still
	 *         owns what it offered
	 */
	static boolean adopt(RenderPipeline pipeline, CompiledRenderPipeline compiled) {
		if (COMPILED.putIfAbsent(pipeline, new Held(compiled)) != null) {
			return false;
		}

		describe(pipeline, compiled);
		return true;
	}

	/**
	 * What this engine compiled for a description, or null where it compiled nothing or the compile
	 * refused it. {@code RenderSystemMixin} answers the game's own lookup with it, so a pipeline
	 * of this engine that the game or Sodium sets by description is found here rather than
	 * compiled again from the game's sources, which hold none of it.
	 */
	public static @Nullable CompiledRenderPipeline held(RenderPipeline pipeline) {
		Held held = COMPILED.get(pipeline);
		return held == null ? null : held.compiled();
	}

	/** The description a backend pipeline of this engine was compiled from, or null. */
	public static @Nullable RenderPipeline describing(@Nullable Object backend) {
		return backend == null ? null : DESCRIBED.get(backend);
	}

	private static void describe(RenderPipeline pipeline, @Nullable CompiledRenderPipeline compiled) {
		if (compiled instanceof FrontendRenderPipeline front) {
			DESCRIBED.put(front.backendRenderPipeline(), pipeline);
		}

		if (compiled != null) {
			SOURCES.put(new Same(compiled), pipeline);
		}
	}

	/**
	 * Notes the description the game compiled a pipeline of its own from, as the game hands it
	 * out, so a hook on the pass it is set on can ask {@link #descriptionOf}.
	 */
	public static void noteGameCompiled(RenderPipeline pipeline,
			@Nullable CompiledRenderPipeline compiled) {
		if (compiled != null) {
			SOURCES.putIfAbsent(new Same(compiled), pipeline);
		}
	}

	/** The description a compiled pipeline was compiled from, or null where nothing noted it. */
	public static @Nullable RenderPipeline descriptionOf(@Nullable CompiledRenderPipeline compiled) {
		return compiled == null ? null : SOURCES.get(new Same(compiled));
	}

	/**
	 * The compiled pipeline to set for a description: this engine's where it compiled one, the
	 * game's otherwise.
	 */
	public static @Nullable CompiledRenderPipeline compiledFor(RenderPipeline pipeline) {
		CompiledRenderPipeline ours = held(pipeline);
		return ours != null ? ours : RenderSystem.getCompiledPipelineNullable(pipeline);
	}

	private static void undescribe(@Nullable CompiledRenderPipeline compiled) {
		if (compiled instanceof FrontendRenderPipeline front) {
			DESCRIBED.remove(front.backendRenderPipeline());
		}

		if (compiled != null) {
			SOURCES.remove(new Same(compiled));
		}
	}

	/**
	 * Forgets and closes what {@link #compile} kept for one description, so the next compile builds
	 * it again. Safe at any instant, the destruction queuing behind whatever still records it.
	 */
	public static void forget(RenderPipeline pipeline) {
		Held held = COMPILED.remove(pipeline);
		if (held != null && held.compiled() != null) {
			undescribe(held.compiled());
			held.compiled().close();
		}
	}

	/**
	 * Empties the engine's pipelines where the game empties its own, at the instant a resource
	 * reload replaces the game's pipeline cache. The live pack's pipelines are carried over unless
	 * {@code -Dvitrail.keepPackAcrossReload=false} says otherwise; the engine's own, which carry no
	 * load, go every time and are compiled again by the next frame that wants them, as on 26.2.
	 */
	public static void purge() {
		int load = PackChain.liveLoad();
		String live = load == 0 ? null : "pipeline/pack/" + load + "/";
		int ofThePack = 0;
		int carried = 0;

		Iterator<Map.Entry<RenderPipeline, Held>> held = COMPILED.entrySet().iterator();
		while (held.hasNext()) {
			Map.Entry<RenderPipeline, Held> entry = held.next();
			boolean pack = ofAPack(entry.getKey());
			if (pack) {
				ofThePack++;
				if (KEEP_ACROSS_RELOAD && live != null
						&& entry.getKey().getLocation().getPath().startsWith(live)) {
					carried++;
					continue;
				}
			}

			held.remove();
			CompiledRenderPipeline compiled = entry.getValue().compiled();
			if (compiled != null) {
				undescribe(compiled);
				compiled.close();
			}
		}

		// The game closes every pipeline of the cache it is replacing, so what it compiled is gone;
		// what survives in the map is what this engine still holds.
		SOURCES.keySet().removeIf(key -> COMPILED.values().stream()
				.noneMatch(kept -> kept.compiled() == key.held()));

		Vitrail.logger().info("Pipeline purge: {} pipelines of the pack held, {} carried over it, "
				+ "property=vitrail.keepPackAcrossReload", ofThePack, carried);
	}

	/**
	 * Whether this pipeline was built for a pack, whichever load it came from, read off the name it
	 * was built under, as the 26.2 device mixin reads it.
	 */
	private static boolean ofAPack(RenderPipeline pipeline) {
		Identifier location = pipeline.getLocation();
		String namespace = location.getNamespace();

		return (namespace.equals(Vitrail.MOD_ID) || namespace.startsWith(Vitrail.MOD_ID + "_"))
				&& location.getPath().startsWith("pipeline/pack/");
	}

	/**
	 * A shader source answering by id and stage out of a function. This game's source also serves
	 * the includes shaderc asks for, and none is ever asked of this one: a pack's units reach the
	 * compiler with every include already expanded, so an include here is refused as missing.
	 */
	public static ShaderSource source(BiFunction<Identifier, ShaderType, @Nullable String> shaders) {
		return new ShaderSource() {
			@Override
			public @Nullable String getShader(Identifier id, ShaderType type) {
				return shaders.apply(id, type);
			}

			@Override
			public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier id) {
				return null;
			}

			@Override
			public void close() {
				// Holds nothing native.
			}
		};
	}

	/** The text a source holds for one stage, or null where it holds none. */
	public static @Nullable String shaderText(ShaderSource source, Identifier id, ShaderType type) {
		return source.getShader(id, type);
	}

	/** A bind group of sampled images and nothing else, in the order named. */
	public static BindGroupLayout samplers(String... names) {
		BindGroupLayout.Builder builder = BindGroupLayout.builder();
		for (String name : names) {
			builder.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
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
		return builder.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
	}

	/** The id a pipeline names its vertex stage by. */
	public static Identifier vertexShader(RenderPipeline pipeline) {
		return pipeline.getShaders().get(ShaderType.VERTEX);
	}

	/** The id a pipeline names its fragment stage by. */
	public static Identifier fragmentShader(RenderPipeline pipeline) {
		return pipeline.getShaders().get(ShaderType.FRAGMENT);
	}

	/** The state of a pipeline's first colour target, or null where it writes none. */
	public static @Nullable ColorTargetState colorTarget(RenderPipeline pipeline) {
		List<@Nullable ColorTargetState> states = pipeline.getColorTargetStates();
		return states.isEmpty() ? null : states.getFirst();
	}

	/** The state of every colour target a pipeline writes, one per attachment, in order. */
	public static List<@Nullable ColorTargetState> colorTargets(RenderPipeline pipeline) {
		return pipeline.getColorTargetStates();
	}

	/**
	 * A texture target of one colour format, with a depth image where {@code depth} asks for one.
	 * 26.3 takes the depth format rather than a flag; the one given is what 26.2 allocated for the
	 * flag, so the image is the same on both.
	 */
	public static TextureTarget textureTarget(@Nullable String label, int width, int height,
			boolean depth, GpuFormat colour) {
		return new TextureTarget(label, width, height, colour, depth ? GpuFormat.D32_FLOAT : null);
	}

	/**
	 * Whether a render target carries a depth image beside its colour. 26.3 keeps the depth format
	 * rather than a flag, and a target has depth exactly where it names one.
	 */
	public static boolean hasDepth(RenderTarget target) {
		return target.hasDepth();
	}

	/**
	 * The area a pass descriptor restricts drawing to. The 26.3 record fills in the extent of its
	 * first attachment where none was set, so a built descriptor always answers one here.
	 */
	public static RenderPass.@Nullable RenderArea renderArea(RenderPassDescriptor descriptor) {
		return descriptor.renderArea();
	}

	/**
	 * A built descriptor with its depth attachment replaced. The 26.3 descriptor is a record, so
	 * this is a copy with every other part kept, the area included.
	 */
	public static RenderPassDescriptor withDepthAttachment(RenderPassDescriptor descriptor,
			GpuTextureView view, OptionalDouble clear) {
		return new RenderPassDescriptor(descriptor.label(), descriptor.colorAttachments(),
				new RenderPassDescriptor.Attachment<>(view, clear), descriptor.renderArea());
	}

	/**
	 * Takes every compiled pipeline that declares the game's entity format out of the game's
	 * pipeline caches, so the next bind compiles it against the mesh now in force, and answers
	 * with their keys.
	 * <p>
	 * On 26.2 those pipelines sat in the device's cache and could only be set aside until the next
	 * safe purge. Here they sit in the game's own caches, the one the current resource load built
	 * and the one it falls back on, and closing one is safe at any instant, so they are closed as
	 * they leave.
	 *
	 * @return the keys taken out, never null on this game
	 */
	public static @Nullable List<RenderPipeline> dropEntityPipelines(GpuDevice device) {
		List<RenderPipeline> dropped = new ArrayList<>();
		for (PipelineCache cache : new PipelineCache[] {RenderSystemAccessor.vitrail$current(),
				RenderSystemAccessor.vitrail$fallback()}) {
			if (cache == null) {
				continue;
			}

			Iterator<Map.Entry<RenderPipeline, CompiledRenderPipeline>> held =
					((PipelineCacheAccessor) cache).vitrail$cache().entrySet().iterator();
			while (held.hasNext()) {
				Map.Entry<RenderPipeline, CompiledRenderPipeline> entry = held.next();
				RenderPipeline pipeline = entry.getKey();
				if (!declaresGameEntity(pipeline)) {
					continue;
				}

				// Read before the removal: the map's entries are views of its slots, and a removed
				// slot answers nothing.
				CompiledRenderPipeline compiled = entry.getValue();
				held.remove();
				compiled.close();
				dropped.add(pipeline);
			}
		}

		return dropped;
	}

	/**
	 * Whether a pipeline declares the game's entity format, read off the field rather than the
	 * getter, which {@code RenderPipelineMixin} rewrites while the entity mesh carries. The three
	 * pipelines a moving block is drawn with count as well.
	 */
	private static boolean declaresGameEntity(RenderPipeline pipeline) {
		if (EntityMesh.movingBlock(pipeline)) {
			return true;
		}

		for (VertexFormat format : ((RenderPipelineAccessor) pipeline).vitrail$declaredFormats()) {
			@SuppressWarnings("ReferenceEquality")
			boolean entity = format == DefaultVertexFormat.ENTITY;
			if (entity) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Ends the pass the level is being drawn through, so the engine can record what a pass may not
	 * hold; the level's next draw opens it again. {@link LevelPass} says why.
	 */
	public static void suspendLevelPass() {
		LevelPass.suspendCurrent();
	}

	/**
	 * Gives a pipeline the room Sodium's region offset takes. This game has each pipeline declare
	 * its push constants, and refuses a stage pushing more than its pipeline declared.
	 */
	public static void withSodiumPushConstants(RenderPipeline.Builder builder) {
		builder.withPushConstantSize(SODIUM_PUSH_CONSTANTS);
	}

	/** Carries a pipeline's push constants over to a builder rebuilding it. */
	public static void copyPushConstants(RenderPipeline.Builder builder, RenderPipeline from) {
		builder.withPushConstantSize(from.pushConstantSize());
	}
}
