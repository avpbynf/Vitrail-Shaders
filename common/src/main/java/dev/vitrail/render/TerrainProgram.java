package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.SodiumVertex;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.OptionValue;
import dev.vitrail.pack.ProgramStage;
import dev.vitrail.pack.SamplerPlan;
import dev.vitrail.uniform.TextSink;
import dev.vitrail.uniform.WorldState;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;

import org.joml.Vector4f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The pack's {@code gbuffers_terrain}, drawn over Sodium's chunk mesh in place of Sodium's own
 * shader.
 * <p>
 * The first step of milestone six, and it is deliberately the smallest one that can be judged. Only
 * the opaque pass is taken over; cutout and translucent keep Sodium's shader, so the difference
 * between the two is on screen at the same time. Nothing of the mesh is changed: the four attributes
 * it carries are decoded and the four names it does not carry are given constants, which
 * {@link SodiumVertex} spells out. The normals are therefore wrong and the albedo is right, and the
 * albedo is what the test looks at.
 * <p>
 * <strong>The pipeline is named in a namespace containing {@code sodium}, and that is not a
 * cosmetic.</strong> blaze3d never declares a push constant range; Sodium adds one by a mixin on
 * {@code VulkanRenderPipeline}, and only when
 * {@code pipeline.getLocation().getNamespace().contains("sodium")}. Named anything else, this
 * pipeline is pushed twenty bytes into a layout with no room for them: the region offset never
 * arrives and the whole world draws itself on top of the camera. It is a {@code contains} and not an
 * {@code equals}, so a namespace of our own with the word in it is enough and no mixin is needed.
 * <p>
 * The block is called {@code OfGlobals} like every other program of this engine, and it has to stay
 * that way: Sodium binds its own {@code u_Globals} into the same pass, unconditionally, and the two
 * would be one name. The bindings Sodium emits for names this pipeline does not declare are
 * harmless, because the descriptor flush walks the layout of the pipeline that is bound and not the
 * list of what was offered; the converse is not, and everything declared here has to be bound or the
 * draw throws.
 */
public final class TerrainProgram {

	/** The block name the translator writes into every program. Never {@code u_Globals}. */
	private static final String UNIFORM_BLOCK = "OfGlobals";

	/** The one name that decides everything. See the class comment before shortening it. */
	private static final String NAMESPACE = Vitrail.MOD_ID + "_sodium";

	/** The program of the milestone. The fallback tree serves it where a pack ships no file. */
	public static final String PROGRAM = "gbuffers_terrain";

	/**
	 * What a pack calls the block atlas. {@code texture} arrives as {@code ofTexture} because the
	 * word is reserved in modern GLSL and the translator renames it; all eight packs of the corpus
	 * use that spelling and no other.
	 */
	private static final Set<String> ATLAS = Set.of("gtexture", "tex", "texture", "ofTexture");

	private static final String LIGHTMAP = "lightmap";

	/** One pixel each, for a name this step has no answer for. */
	private static final GpuFormat CONSTANT_FORMAT = GpuFormat.RGBA8_UNORM;
	private static final Vector4f OPAQUE_BLACK = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
	private static final Vector4f OPAQUE_WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector4f MID_GREY = new Vector4f(0.5F, 0.5F, 0.5F, 1.0F);

	private static final Supplier<String> BLOCK_LABEL = () -> "Vitrail terrain OfGlobals";

	private final String path;
	private final PackProgram.Loaded loaded;
	private final PackValues values;
	private final PackUniforms uniforms;
	private final List<String> samplers;
	private final RenderPipeline pipeline;
	private final ShaderSource source;

	private MappableRingBuffer block;
	private TextureTarget black;
	private TextureTarget white;
	private TextureTarget grey;
	private GpuTextureView atlas;
	private GpuSampler atlasSampler;
	private boolean cleared;
	private boolean announced;
	private boolean broken;

	private TerrainProgram(String path, PackProgram.Loaded loaded, PackValues values, int load,
			VertexFormat format) {
		this.path = path;
		this.loaded = loaded;
		this.values = values;
		this.uniforms = new PackUniforms(loaded.program().uniforms(), values.geometryCatalog());
		this.samplers = loaded.program().samplers().stream().map(TranslatedUnit.Uniform::name).toList();

		String vertex = loaded.program().stages().get(ProgramStage.VERTEX).text();
		String fragment = loaded.program().stages().get(ProgramStage.FRAGMENT).text();
		String stem = "pack/" + load + "/" + path;
		Identifier vertexId = Identifier.fromNamespaceAndPath(NAMESPACE, stem + "/vertex");
		Identifier fragmentId = Identifier.fromNamespaceAndPath(NAMESPACE, stem + "/fragment");

		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				return fragmentId.equals(id) ? fragment : null;
			}

			return vertexId.equals(id) ? vertex : null;
		};

		BindGroupLayout.Builder bindings = BindGroupLayout.builder()
				.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER);
		this.samplers.forEach(bindings::withSampler);

		// Everything but the shaders and the bind group is Sodium's own, taken from
		// ShaderChunkRenderer.createShader: the pass this is bound into was opened for that pipeline
		// and a difference of topology or of depth state would be a difference nobody declared.
		// One colour target state, because the pass carries one attachment and dynamic rendering
		// wants the two counts equal. A fragment stage declaring more outputs than that writes the
		// extra ones nowhere, which is said in the log rather than left to be noticed.
		this.pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/" + stem))
				.withVertexShader(vertexId)
				.withFragmentShader(fragmentId)
				.withBindGroupLayout(bindings.build())
				.withVertexBinding(0, format)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withDepthStencilState(DepthStencilState.DEFAULT)
				.withColorTargetState(ColorTargetState.DEFAULT)
				.withCull(true)
				.build();
	}

	/**
	 * Reads and translates the terrain program of one place, or answers empty when the pack serves
	 * none there.
	 * <p>
	 * A second reading of the pack, which costs one plan build. The chain's own reading translates
	 * what the chain runs, and a gbuffers program is not in it: folding this into that walk would
	 * make every place pay for a program only this step uses.
	 *
	 * @param format the chunk mesh format, handed in rather than looked up, because nothing in this
	 *               module is allowed to name Sodium
	 */
	static Optional<TerrainProgram> read(Path packPath, String place, String program,
			Map<String, OptionValue> chosen, String profile, PackValues values, int load,
			VertexFormat format) {
		String path = place.isEmpty() ? program : place + "/" + program;
		try {
			Optional<PackProgram.Loaded> loaded =
					PackProgram.load(packPath, path, VertexInputs.TERRAIN, chosen, profile);
			if (loaded.isEmpty()) {
				Vitrail.logger().warn("{} serves no {} with both stages, so the terrain keeps the "
						+ "game's own shader", packPath.getFileName(), path);

				return Optional.empty();
			}

			return Optional.of(new TerrainProgram(path, loaded.get(), values, load, format));
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare " + path + ", so the terrain keeps the game's "
					+ "own shader", e);

			return Optional.empty();
		}
	}

	/** Checks that the mesh is still the one the prologue decodes, and says so once when it is not. */
	static boolean carries(VertexFormat format) {
		List<String> elements = format.getElements().stream()
				.map(VertexFormatElement::name)
				.toList();
		if (elements.equals(SodiumVertex.ATTRIBUTES)) {
			return true;
		}

		// A silent failure otherwise, and the worst kind. An element the shader does not declare
		// moves the location of every element after it without a word, so the picture stays a
		// picture and the texture coordinates come out of the light map.
		Vitrail.logger().error("The chunk mesh carries {} and this engine decodes {}, so no terrain "
				+ "program will be drawn", elements, SodiumVertex.ATTRIBUTES);

		return false;
	}

	/**
	 * Everything that has to happen outside a render pass: the pipeline compiled, the buffers made,
	 * the constants cleared, and this frame's block written.
	 * <p>
	 * Called where Sodium asks for its shader, which is before it opens its pass. Creating a texture
	 * or a buffer records a barrier into the very command buffer a pass would be recording into, and
	 * a clear refuses outright while one is open.
	 *
	 * @param atlas the block atlas of the pass being drawn, kept for the bind
	 * @return the pipeline to draw with, or null to leave the game's own shader alone
	 */
	RenderPipeline prepare(GpuDevice device, GpuTextureView atlas) {
		if (this.broken) {
			return null;
		}

		CompiledRenderPipeline compiled = device.precompilePipeline(this.pipeline, this.source);
		if (!compiled.isValid()) {
			// Handing back an invalid pipeline throws inside setPipeline, in the middle of Sodium's
			// own pass, which reads as a Sodium failure. Refused here instead, once.
			this.broken = true;
			Vitrail.logger().error("{} did not compile, so the terrain keeps the game's own shader",
					this.path);

			return null;
		}

		this.atlas = atlas;
		ensureConstants(device);
		if (this.block == null) {
			this.block = new MappableRingBuffer(BLOCK_LABEL,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, blockBytes());
		}

		announce();
		writeBlock();

		return this.pipeline;
	}

	/**
	 * Binds this program's block and every sampler it declares, inside the pass Sodium opened.
	 * <p>
	 * Every name the layout carries has to be bound or the draw throws on the first one missing, so
	 * a name this step has no answer for gets one pixel rather than being left out. Only two names
	 * are answered with anything real: the block atlas, and the light map. Everything else is a
	 * constant, which is why the criterion for this step is the albedo and nothing to do with light.
	 */
	void bind(RenderPass pass) {
		pass.setUniform(UNIFORM_BLOCK, this.block.currentBuffer().slice(0, blockBytes()));

		for (String sampler : this.samplers) {
			pass.bindTexture(sampler, view(sampler), sampler(sampler));
		}
	}

	private GpuSampler sampler(String name) {
		if (ATLAS.contains(name) && this.atlasSampler != null) {
			return this.atlasSampler;
		}

		return RenderSystem.getSamplerCache().getClampToEdge(filter(name));
	}

	/**
	 * The sampler the game configured for the block atlas, which is mipmapped and filtered as the
	 * user's own settings say. Worth taking rather than making one: a block atlas read without
	 * mipmaps shimmers at distance, and the sprites bleed into each other at their edges.
	 */
	void sampler(GpuSampler sampler) {
		this.atlasSampler = sampler;
	}

	/** Whether the pipeline a pass has bound is this program's. */
	boolean owns(RenderPipeline bound) {
		return this.pipeline == bound;
	}

	/** Rotates the ring buffer. Called once the frame's terrain draw has been recorded. */
	void rotate() {
		if (this.block != null) {
			this.block.rotate();
		}
	}

	/** This program's block as {@code name = value} text, for the decoded dump. */
	String decoded(WorldState world) {
		TextSink sink = new TextSink();
		this.uniforms.write(sink, world);

		return sink.text();
	}

	String path() {
		return this.path;
	}

	void release() {
		if (this.block != null) {
			this.block.close();
			this.block = null;
		}

		this.black = release(this.black);
		this.white = release(this.white);
		this.grey = release(this.grey);
		this.cleared = false;
	}

	private int blockBytes() {
		return Math.max(16, this.uniforms.size());
	}

	private void writeBlock() {
		try (GpuBufferSlice.MappedView view = this.block.currentBuffer().map(false, true)) {
			ByteBuffer data = view.data();
			data.position(0);
			this.uniforms.write(Std140Builder.intoBuffer(data), this.values.world());
		}
	}

	private void ensureConstants(GpuDevice device) {
		if (this.black == null) {
			this.black = new TextureTarget("Vitrail terrain black", 1, 1, false, CONSTANT_FORMAT);
			this.white = new TextureTarget("Vitrail terrain white", 1, 1, false, CONSTANT_FORMAT);
			this.grey = new TextureTarget("Vitrail terrain grey", 1, 1, false, CONSTANT_FORMAT);
			this.cleared = false;
		}

		if (!this.cleared) {
			this.cleared = true;
			CommandEncoder encoder = device.createCommandEncoder();
			encoder.clearColorTexture(this.black.getColorTexture(), OPAQUE_BLACK);
			encoder.clearColorTexture(this.white.getColorTexture(), OPAQUE_WHITE);
			encoder.clearColorTexture(this.grey.getColorTexture(), MID_GREY);
		}
	}

	private GpuTextureView view(String sampler) {
		if (ATLAS.contains(sampler)) {
			return this.atlas;
		}

		if (LIGHTMAP.equals(sampler)) {
			Minecraft minecraft = Minecraft.getInstance();
			GpuTextureView lightmap = minecraft == null ? null : minecraft.gameRenderer.lightmap();

			return lightmap == null ? this.white.getColorTextureView() : lightmap;
		}

		// Black and not white for a depth, and the reason is the convention rather than a taste:
		// every depth lookup is wrapped in of_DepthConv.zw, which under the reversed Z the game
		// rasterises in reads nought back as the far plane. White would put the whole world against
		// the camera. PackPass answers the same way, and the day a target of ours is drawn into,
		// both have to follow.
		return switch (SamplerPlan.classify(sampler)) {
			case SHADOW_DEPTH, SHADOW_COLOUR -> this.white.getColorTextureView();
			case NOISE -> this.grey.getColorTextureView();
			default -> this.black.getColorTextureView();
		};
	}

	/** The light map is the one thing here that is interpolated, as it is in the game's own shader. */
	private static FilterMode filter(String sampler) {
		return LIGHTMAP.equals(sampler) ? FilterMode.LINEAR : FilterMode.NEAREST;
	}

	/**
	 * Said once, and grouped by what it costs the picture. Four names are constants, every sampler
	 * but two reads one pixel, and a fragment stage may declare more outputs than the one attachment
	 * Sodium's pass carries. None of the three shows as an error and all three change the image.
	 */
	private void announce() {
		if (this.announced) {
			return;
		}

		this.announced = true;
		int outputs = this.loaded.program().stages().get(ProgramStage.FRAGMENT).notes().fragmentOutputs();
		Vitrail.logger().info("Drawing the opaque terrain with {} of {}, {} uniforms and {} samplers,"
				+ " cutout and translucent left to the game", this.path, this.loaded.packName(),
				this.loaded.program().uniforms().size(), this.samplers.size());

		Map<String, String> synthesized = this.loaded.program().synthesized();
		if (!synthesized.isEmpty()) {
			Vitrail.logger().warn("The chunk mesh carries neither a normal nor a block id, so {} are "
					+ "answered with a constant and the lighting this program computes is wrong: {}",
					synthesized.size(), synthesized.keySet());
		}

		List<String> real = this.samplers.stream()
				.filter(name -> ATLAS.contains(name) || LIGHTMAP.equals(name))
				.toList();
		List<String> flat = this.samplers.stream()
				.filter(name -> !ATLAS.contains(name) && !LIGHTMAP.equals(name))
				.toList();
		Vitrail.logger().info("{} samplers of this program read a real texture: {}", real.size(), real);
		if (!flat.isEmpty()) {
			Vitrail.logger().warn("{} read one pixel, because this step draws before any target of "
					+ "the pack exists: {}", flat.size(), flat);
		}

		if (outputs > 1) {
			Vitrail.logger().warn("{} declares {} fragment outputs and the pass it is drawn in carries"
					+ " one attachment, so all but the first are written nowhere", this.path, outputs);
		}

		PackValues.Gaps gaps = this.values.classify(this.uniforms.unsupplied());
		if (!gaps.engine().isEmpty()) {
			Vitrail.logger().warn("{} reads {} values written as zeroes: {}", this.path,
					gaps.engine().size(), gaps.engine());
		}
	}

	private static TextureTarget release(TextureTarget target) {
		if (target != null) {
			target.destroyBuffers();
		}

		return null;
	}
}
