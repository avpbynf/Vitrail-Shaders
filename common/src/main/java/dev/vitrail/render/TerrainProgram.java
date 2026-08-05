package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.SodiumVertex;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSize;
import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.TextSink;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
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
import com.mojang.blaze3d.systems.RenderPassDescriptor;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One of the three programs the pack draws a chunk pass with, in place of Sodium's own shader.
 * <p>
 * Nothing of the mesh is changed: the attributes it carries are decoded and the names it does not
 * carry are given constants, which {@link SodiumVertex} spells out. What separates the three is not
 * the geometry but the pass, and {@link TerrainPass} holds all of it: which program the pack serves
 * it with, what alpha the fragment stage discards at, and whether the result is blended. The first
 * two are settled at translation and reach here already written into the text; only the blend is a
 * property of the pipeline, and it is the one line below that differs between the three.
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
	private static final Supplier<String> PASS_LABEL = () -> "Vitrail terrain";
	private static final Supplier<String> SHADOW_LABEL = () -> "Vitrail shadow";

	/** Where one colour attachment of a world pass takes its image from. */
	private enum Bound {

		/** The target the chunk renderer was going to draw into, which carries its own format. */
		GAME,

		/** A colour target of the pack, named by the attachment and on the half it names. */
		PACK,

		/**
		 * No image at all, and the slot is there to be skipped.
		 * <p>
		 * The game writes each fragment output's RANK over the location it declared, so a stage
		 * declaring more outputs than the pack gave it draw buffers still spends those ranks. They
		 * are stood for here rather than closed up, or the mask below would land on the rank an
		 * output of the pack's already holds and read whatever that output happens to carry.
		 */
		UNUSED,

		/** The mask saying where this pass drew, which is ours and not in the pack's draw buffers. */
		COVERAGE
	}

	/**
	 * One colour attachment, in the order the pipeline's states and the descriptor's views are both
	 * walked. {@code target} is the pack's answer and is null for anything else.
	 */
	private record Slot(Bound bound, ChainPlan.Attachment target, GpuFormat format) {
	}

	private final TerrainPass pass;
	private final String path;

	/**
	 * The attachments this pass adds to or takes instead of the game's target: every draw buffer
	 * when {@link #ownsFirst}, every one but nought otherwise. Empty when there is nothing to gain.
	 */
	private final List<ChainPlan.Attachment> extra;

	/**
	 * Every colour attachment of a world pass, in order, settled once at load. Empty for a shadow
	 * pass, which draws into the map and nothing else.
	 * <p>
	 * One list rather than two readings of one rule. The pipeline carries a state per element and
	 * the descriptor names a view per element, and {@code RenderPass.setPipeline} refuses outright,
	 * in the middle of the world, the moment the two counts or the two formats stop agreeing.
	 */
	private final List<Slot> slots;

	/** Whether draw buffer nought goes to the pack rather than to the game. The constructor says why. */
	private final boolean ownsFirst;

	/** Whether this pass writes the mask the scene seed is cut with. Opaque halves only. */
	private final boolean covers;
	private final ColorTargets targets;
	private final ShadowTargets shadow;
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
	private boolean drew;
	private boolean broken;

	/** Targets already reported as read on the half this pass writes. Said once each, not per frame. */
	private final Set<Integer> collisions = new HashSet<>();

	private TerrainProgram(TerrainPass pass, PackProgram.Loaded loaded, PackValues values, int load,
			VertexFormat format, List<ChainPlan.Attachment> writes, ColorTargets targets,
			boolean chainRuns) {
		this.pass = pass;
		this.path = loaded.path();
		TranslatedUnit.Notes notes = loaded.program().stages().get(ProgramStage.FRAGMENT).notes();
		int outputs = notes.fragmentOutputs();

		// Draw buffer nought goes to the pack, on all three halves of the world, and the whole cost
		// of that is that somebody else has to stop painting over it.
		//
		// The translucent half has needed it from the start. It is drawn AFTER the seed has run, so
		// the pack's own colour target already holds the opaque world, and that is exactly what a
		// gbuffers_water expects to blend onto. Sent to the game's target instead, the water is
		// drawn and then thrown away: the final overwrites that target with the image the chain
		// composed out of a colortex the water never reached.
		//
		// The two opaque halves used to keep it on the game's target and reach the pack's colortex
		// through the seed, which was one conversion too many. What a gbuffers_terrain puts in draw
		// buffer nought is not a colour but whatever the pack packed there, and the game's target is
		// eight bits a channel: Bliss packs two values into each channel of a sixteen bit colortex1,
		// and the trip through the game's target quantised its albedo away entirely, leaving the
		// encoded normal to be read back as the albedo. So the opaque halves write their target
		// outright, and the coverage mask below is what keeps the seed off the pixels they wrote.
		//
		// Three demotions, all back to the game's target. When the chain is not running there is no
		// final to bring a colortex to the screen, so anything sent there would simply vanish; when
		// the plan had no answer there is nowhere else to send it; and when an opaque half could not
		// be given a mask, the seed would repaint the whole target and take the terrain with it.
		// Either way the pass draws where Sodium would have, which is also what keeps the pipeline's
		// one state the pass's.
		//
		// Whether the mask was really written is the translation's answer and not a second reading
		// of the same rule: the stage that could not be given one says so, and an engine that
		// decided for itself would be attaching an image nothing fills.
		boolean owns = chainRuns && !writes.isEmpty();
		this.covers = owns && pass.covers() && notes.coverage() == 1 && writes.size() <= outputs
				&& outputs < ColorTargetState.MAX_COLOR_TARGETS;
		this.ownsFirst = owns && (pass.blended() || this.covers);
		this.extra = this.ownsFirst
				? List.copyOf(writes)
				: writes.size() < 2 ? List.of() : List.copyOf(writes.subList(1, writes.size()));
		this.slots = pass.shadow() ? List.of() : attachments(targets, outputs);

		// Said here rather than in announce(), because it is a property of the text and not of a
		// frame, and because what it costs is invisible: the pass then draws exactly as it did
		// before the mask existed, and it is Bliss's albedo that pays for it.
		if (owns && pass.covers() && !this.covers) {
			Vitrail.logger().warn("{} declares {} fragment outputs against {} draw buffers, so there "
					+ "is no rank left for a coverage mask: draw buffer nought stays on the game's "
					+ "target and the scene seed keeps painting the whole of it", this.path, outputs,
					writes.size());
		}

		this.targets = targets;
		this.shadow = targets.shadow();
		this.loaded = loaded;
		this.values = values;
		// A shadow pass is drawn from the light, so the six fixed function names answer the shadow
		// pair. Everything else in the table is the frame's and is shared with the world.
		this.uniforms = new PackUniforms(loaded.program().uniforms(),
				pass.shadow() ? values.shadowGeometryCatalog() : values.geometryCatalog());
		this.samplers = loaded.program().samplers().stream().map(TranslatedUnit.Uniform::name).toList();

		String vertex = loaded.program().stages().get(ProgramStage.VERTEX).text();
		String fragment = loaded.program().stages().get(ProgramStage.FRAGMENT).text();
		// The pass is in the name and not only the path, because two passes are usually served by
		// the same file and their text still differs: the cutout half carries a discard the solid
		// half does not. The device caches a shader module under its identifier, so one name for two
		// texts would hand the second whatever the first compiled to, and the picture would be a
		// picture with the discard silently gone.
		String stem = "pack/" + load + "/" + pass.name().toLowerCase(Locale.ROOT) + "/" + this.path;
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

		// Everything but the shaders, the bind group, the attachments and the two lines below is
		// Sodium's own, taken from ShaderChunkRenderer.createShader: the pass this is bound into was
		// opened for that pipeline and a difference of topology would be a difference nobody declared.
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/" + stem))
				.withVertexShader(vertexId)
				.withFragmentShader(fragmentId)
				.withBindGroupLayout(bindings.build())
				.withVertexBinding(0, format)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withDepthStencilState(depthState())
				// Nothing is culled in the shadow map. What matters there is which surface is nearest
				// the light and not which way it faces, and a wall drawn on one side only leaks light
				// through its back. Iris cuts it for the same reason.
				.withCull(!pass.shadow());

		// One state per attachment, and dynamic rendering wants the two counts equal.
		// By slot and never by append: the builder holds the states in an array and the argumentless
		// form writes slot nought every time, so three calls would leave one state and a pipeline
		// the pass refuses to bind, by name and in the middle of the world.
		if (pass.shadow()) {
			// One attachment, shadowcolor0, whatever the pack's draw buffers say. A shadow program
			// writing more than that has its later outputs written nowhere, which announce() says.
			// The format is the map's own and not a constant: Mellow asks for R8 there, and a state
			// naming four channels against a one channel attachment is the pipeline refusing to bind.
			builder.withColorTargetState(0, state(targets.shadowFormat()));
		} else {
			for (int slot = 0; slot < this.slots.size(); slot++) {
				Slot one = this.slots.get(slot);
				switch (one.bound()) {
					case UNUSED -> builder.withUnusedColorTargetState(slot);
					// The mask is written outright and never blended, whatever the pack asked for its
					// own targets: a fragment either covered this pixel or it did not.
					case COVERAGE -> builder.withColorTargetState(slot, new ColorTargetState(
							Optional.empty(), one.format(), ColorTargetState.WRITE_ALL));
					default -> builder.withColorTargetState(slot, state(one.format()));
				}
			}
		}

		this.pipeline = builder.build();
	}

	/**
	 * The colour attachments of a world pass, in the order both the pipeline and the descriptor walk
	 * them.
	 * <p>
	 * Nought is the game's own target and carries its format; the rest carry the format their colour
	 * target was really allocated as, which is not always the one the pack asked for.
	 *
	 * @param outputs how many outputs the fragment stage declares, which is where the mask goes and
	 *                not where the pack's draw buffers end. See {@link Bound#UNUSED}
	 */
	private List<Slot> attachments(ColorTargets targets, int outputs) {
		List<Slot> built = new ArrayList<>();
		if (!this.ownsFirst) {
			built.add(new Slot(Bound.GAME, null, GpuFormat.RGBA8_UNORM));
		}

		for (ChainPlan.Attachment attachment : this.extra) {
			built.add(new Slot(Bound.PACK, attachment, targets.format(attachment.target())));
		}

		if (this.covers) {
			while (built.size() < outputs) {
				built.add(new Slot(Bound.UNUSED, null, null));
			}

			built.add(new Slot(Bound.COVERAGE, null, targets.coverageFormat()));
		}

		return List.copyOf(built);
	}

	/**
	 * Which way the depth test runs, which follows the window the target stores and nothing else.
	 * <p>
	 * The game rasterises the scene under a reversed Z and clears its depth to nought, so its own
	 * targets keep the default and its greater-or-equal. The shadow map is ours and stores the
	 * forward window, cleared to one, so its test is the other way round. Getting this pair out of
	 * step does not fail: it fills the map with the geometry furthest from the light, which is a
	 * shadow map of the far side of the world and reads as shadows in all the wrong places.
	 */
	private DepthStencilState depthState() {
		return this.pass.shadow()
				? new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true)
				: DepthStencilState.DEFAULT;
	}

	/**
	 * What the pack asked to blend with, falling back to what the pass wants when it asked nothing,
	 * on every attachment alike. The per buffer form, {@code blend.<program>.<buffer>}, is still
	 * not read: one pipeline carries one blend function for every target it writes.
	 * <p>
	 * Four packs of the corpus name the translucent chunk pass here. Reverie asks for no blending
	 * at all on its water, which is the opposite of what the pass would have chosen, and Bliss and
	 * the two Complementary give a function whose alpha half differs from the one assumed.
	 */
	private ColorTargetState state(GpuFormat format) {
		Optional<BlendFunction> wanted = this.pass.blended()
				? Optional.of(BlendFunction.TRANSLUCENT)
				: Optional.empty();

		return new ColorTargetState(
				BlendFunctions.of(this.targets.blend(this.loaded.path()), wanted), format,
				ColorTargetState.WRITE_ALL);
	}

	/**
	 * Reads and translates the three programs the chunk renderer draws with, keyed by the pass each
	 * one serves. A pass the pack ships nothing for is absent, and keeps the game's own shader.
	 * <p>
	 * A second reading of the pack, which costs one plan build for all three. The chain's own reading
	 * translates what the chain runs, and a gbuffers program is not in it: folding this into that
	 * walk would make every place pay for programs only this step uses.
	 *
	 * @param format the chunk mesh format, handed in rather than looked up, because nothing in this
	 *               module is allowed to name Sodium
	 */
	static Map<TerrainPass, TerrainProgram> read(Path packPath, String place,
			Map<String, OptionValue> chosen, String profile, PackValues values, int load,
			VertexFormat format, ChainPlan plan, TargetPlan chainTargets, boolean chainRuns,
			ColorTargets targets) {
		try {
			Map<TerrainPass, PackProgram.Loaded> loaded =
					PackProgram.loadTerrain(packPath, place, chosen, profile);
			if (loaded.isEmpty()) {
				Vitrail.logger().warn("{} serves no terrain program with both stages in {}, so the "
						+ "world keeps the game's own shader", packPath.getFileName(),
						place.isEmpty() ? "its root" : place);

				return Map.of();
			}

			Map<TerrainPass, TerrainProgram> programs = new EnumMap<>(TerrainPass.class);
			loaded.forEach((pass, one) -> {
				// The samplers are bound again, against the chain's own plan and on the step of the
				// PASS. What loadTerrain bound them against is a plan without the user's pass
				// filter and a step looked up by file, and both are the wrong parity in their own
				// way: the first the moment passes= trims the chain, the second for the translucent
				// pass, whose reads land on the halves the deferred stage leaves behind. BSL's
				// gbuffers_water reading gaux1, which its own deferred writes, is the second case.
				String servedBy = one.path().substring(one.path().lastIndexOf('/') + 1);
				PackProgram.Loaded bound = one.rebind(chainTargets, pass.afterDeferred()
						? chainTargets.schedule().stepAfterDeferred(servedBy)
						: chainTargets.schedule().step(servedBy));

				// Attachment nought is the game's own target and it is the size of the screen, so
				// every other attachment of that pass has to be too: one render pass has one render
				// area. A pack scaling its targets with size.buffer therefore keeps the single
				// attachment pass, and the log says so rather than the encoder throwing mid frame.
				List<ChainPlan.Attachment> writes = plan.geometry(pass)
						.filter(geometry -> {
							if (geometry.size().equals(TargetSize.ofScreen())) {
								return true;
							}

							Vitrail.logger().warn("{} writes targets the pack asked to be scaled, so "
									+ "they cannot share a pass with the game's own target and its "
									+ "other draw buffers are written nowhere", servedBy);

							return false;
						})
						.map(ChainPlan.Pass::attachments)
						.orElse(List.of());
				programs.put(pass, new TerrainProgram(pass, bound, values, load, format, writes,
						targets, chainRuns));
			});

			return programs;
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the terrain programs of "
					+ packPath.getFileName() + ", so the world keeps the game's own shader", e);

			return Map.of();
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
				+ "program will be drawn. The mesh is decided once when the game starts, so this is "
				+ "what turning the terrain on after that looks like: restart the game", elements,
				SodiumVertex.ATTRIBUTES);

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

		// Refused rather than drawn somewhere else. A shadow program handed back with no map to
		// draw into would be bound into the pass the renderer opens for itself, which is the game's
		// own target, and the pack's shadow output would land on the screen.
		if (this.pass.shadow() && this.shadow.depth() == null) {
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
		// Once, and it is the one thing that tells a pass that draws from a pass that only compiled:
		// announce() says a program was prepared, which happens whether or not the renderer goes on
		// to record a single command against it.
		if (!this.drew) {
			this.drew = true;
			Vitrail.logger().info("The {} pass records its first draw with {}",
					this.pass.name().toLowerCase(Locale.ROOT), this.path);
		}

		pass.setUniform(UNIFORM_BLOCK, this.block.currentBuffer().slice(0, blockBytes()));

		for (String sampler : this.samplers) {
			pass.bindTexture(sampler, view(sampler), sampler(sampler));
		}
	}

	private GpuSampler sampler(String name) {
		if (ATLAS.contains(name) && this.atlasSampler != null) {
			return this.atlasSampler;
		}

		if (LIGHTMAP.equals(name)) {
			return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}

		// The noise image tiles and everything else clamps, the same rule the chain follows.
		//
		// Never past level nought, even on a target that carries a chain, and this is not a gap left
		// open. Nothing fills a chain for a geometry program, because the reduction opens render
		// passes and a terrain pass draws inside the one Sodium opened, where no other can start; a
		// sampler that let these reads climb would hand them levels nothing has written, which is
		// undefined memory rather than a coarser image.
		//
		// Nothing asks for it either, and that was measured rather than assumed: across the eight
		// packs there are fifty reads at a lod other than nought and not one of them is in a
		// geometry file. Four of them do declare colortexNMipmapEnabled on a gbuffers program,
		// Bliss on gaux1 the loudest, and none of those programs ever reads the target at a lod. So
		// the directive is theirs to declare and dead on their side, and the cost of honouring it
		// here would be a risk taken for nobody.
		return PackPass.sampler(this.loaded.samplers().binding(name).kind(), filter(name), false);
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

	/** Whether this program can still be served, which everything built on it has to agree with. */
	boolean servable() {
		return !this.broken;
	}

	/**
	 * The render pass this program wants opened, or null to leave the chunk renderer's own alone.
	 * <p>
	 * Null is the ordinary answer and not a failure: a pass that gained nothing over the one Sodium
	 * would have opened wants exactly that one, and building an identical one of our own would only
	 * be a way of getting it wrong later. It is also the answer while the targets are still being
	 * allocated, which is the first frame or two and the frames after a resize.
	 *
	 * @param colour the colour view the renderer was going to draw into, which is attachment nought
	 *               only where the pack's own targets do not take it
	 * @param depth  the depth view it was going to use, kept as it is: the terrain has to test
	 *               against the sky the game already drew, and everything the game draws afterwards
	 *               has to test against the terrain
	 */
	RenderPassDescriptor descriptor(GpuTextureView colour, GpuTextureView depth) {
		// The same refusal prepare makes, or the two part company: with the pipeline refused,
		// Sodium keeps its own shader, which declares one target state, and steering its pass onto
		// a many target descriptor is refused at setPipeline in the middle of Sodium's own draw.
		if (this.broken) {
			return null;
		}

		if (this.pass.shadow()) {
			return shadowDescriptor();
		}

		// Nothing gained over the pass the renderer was going to open: one attachment, its own
		// target, at its own format.
		if (this.slots.size() == 1 && this.slots.get(0).bound() == Bound.GAME) {
			return null;
		}

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(PASS_LABEL);
		for (Slot slot : this.slots) {
			if (slot.bound() == Bound.UNUSED) {
				descriptor.withUnusedColorAttachment();
				continue;
			}

			GpuTextureView view = view(slot, colour);
			if (view == null) {
				// The targets are not there yet, or not there any more. Sodium's own pass draws this
				// frame rather than nothing at all, and the next frame tries again.
				return null;
			}

			descriptor.withColorAttachment(view);
		}

		// Never left out: the encoder refuses a descriptor without one outright, and it refuses it
		// at the first draw rather than at load time. The size is the screen's, and stays the
		// screen's now that attachment nought may be a target of the pack's: a scaled colour target
		// is dropped before it gets here, and the game's depth is attached whatever else is.
		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0,
				this.targets.screenWidth(), this.targets.screenHeight()));

		return depth == null ? descriptor : descriptor.withDepthAttachment(depth);
	}

	/**
	 * The image one attachment is really drawn into, or null when it is not there yet.
	 *
	 * @param colour the colour view the renderer was going to draw into, which is the only image of
	 *               this pass that is not ours to look up
	 */
	private GpuTextureView view(Slot slot, GpuTextureView colour) {
		return switch (slot.bound()) {
			case GAME -> colour;
			case PACK -> this.targets.view(slot.target().target(), slot.target().side());
			case COVERAGE -> this.targets.coverage();
			case UNUSED -> null;
		};
	}

	/**
	 * The pass the shadow map is drawn into, which shares nothing with the one the renderer opened:
	 * neither its attachments, which are ours, nor its area, which is the map's own square and not
	 * the screen's. Null while the map is not there, and then nothing is drawn at all rather than
	 * the shadow programs writing over the world.
	 */
	private RenderPassDescriptor shadowDescriptor() {
		GpuTextureView colour = this.shadow.colour(0);
		GpuTextureView depth = this.shadow.depth();
		if (colour == null || depth == null) {
			return null;
		}

		return RenderPassDescriptor.create(SHADOW_LABEL)
				.withColorAttachment(colour)
				.withDepthAttachment(depth)
				.withRenderArea(new RenderPass.RenderArea(0, 0, this.shadow.resolution(),
						this.shadow.resolution()));
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

	/**
	 * How the dump names this program, which has to tell two passes of one file apart. The pass is
	 * last and bare so that the line the dump is pointed at can be the pass rather than the file.
	 */
	String label() {
		return this.path + " " + this.pass.name().toLowerCase(Locale.ROOT);
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
		// Before the block and never once for the run: the two conventions alternate inside one
		// frame now that the shadow map is ours and the game's targets are not, and what a vertex
		// stage does with its clip depth on the way out comes from this pair.
		this.values.convention(this.pass.shadow() ? ClipSpace.FORWARD : ClipSpace.REVERSED);

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

		SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);

		// White and not black for a depth that stays a constant, and the reason is the image rather
		// than a taste: what a depth lookup reads is already in the pack's own window, where one is
		// the far plane. Black would put the whole world against the camera. PackPass answers the
		// same way, and the two have to move together.
		return switch (binding.kind()) {
			case COLORTEX -> colortex(binding);
			case DEPTH -> depth();
			case SHADOW_DEPTH -> shadowDepth(binding.sampler());
			case SHADOW_COLOUR -> shadowColour(binding.index());
			case NOISE -> this.targets.noise();
			default -> this.black.getColorTextureView();
		};
	}

	/**
	 * The shadow map, or white for the far plane.
	 * <p>
	 * White, and the same white {@link #depth()} falls back to, for the same reason: a
	 * {@code shadowtex} lookup is never rewritten, so what is stored is what the pack reads, and the
	 * map stores the forward window where one is the far plane. A shadow lookup that finds nothing
	 * has to say "nothing between here and the light".
	 * <p>
	 * A shadow pass reads white whatever the map holds: the image it would read is an attachment of
	 * the very pass it is drawn in, and sampling an attachment is a thing Vulkan gives no meaning to.
	 */
	private GpuTextureView shadowDepth(String sampler) {
		if (this.pass.shadow()) {
			return this.white.getColorTextureView();
		}

		// shadowtex1 is the map without the translucents and shadowtex0 the map with them. Serving
		// one image to both is what makes a pack's coloured shadow branch dead code: it asks whether
		// a point is occluded in nought and clear in one, and one image can never answer yes.
		GpuTextureView map = SamplerPlan.withoutTranslucents(sampler)
				? this.shadow.depthWithoutTranslucents()
				: this.shadow.depth();

		return map == null ? this.white.getColorTextureView() : map;
	}

	/** A shadow colour target, on the same two rules as the depth above. */
	private GpuTextureView shadowColour(int index) {
		if (this.pass.shadow()) {
			return this.white.getColorTextureView();
		}

		GpuTextureView view = this.shadow.colour(index);

		return view == null ? this.white.getColorTextureView() : view;
	}

	/**
	 * What a depth sampler reads, which depends on which side of the frame this pass stands.
	 * <p>
	 * The translucent pass gets the opaque world's image, whatever the sampler is called. At that
	 * point of the frame depthtex0, depthtex1 and depthtex2 are one depth, the opaque world's, and
	 * that image is exactly it; the live depth cannot be the answer for any of them, being an
	 * attachment of this very pass, and sampling an attachment is a thing Vulkan gives no meaning
	 * to. This is what BSL's water fog and refraction read.
	 * <p>
	 * The solid and cutout passes stay on the constant. They draw before the image of THIS frame is
	 * taken, so the only one in existence at that moment holds the previous frame's, and handing
	 * them that would be the exact shape of picture this project refuses: plausible, and wrong by
	 * one frame of camera movement.
	 */
	private GpuTextureView depth() {
		if (this.pass.afterDeferred()) {
			GpuTextureView opaque = this.targets.depth().opaque();
			if (opaque != null) {
				return opaque;
			}
		}

		return this.white.getColorTextureView();
	}

	/**
	 * A colour target of the pack, on the half the plan reads it from, or black.
	 * <p>
	 * Black covers two cases and only one of them is temporary. The targets may not be allocated
	 * yet, which is the first frame or two. And the half being read may be a half this very pass is
	 * writing, which happens when the pack asks for a target it does not double: one image cannot be
	 * an attachment and a sampled texture of the same pass, so the read is refused rather than left
	 * to mean whatever the driver decides that frame.
	 */
	private GpuTextureView colortex(SamplerPlan.Binding binding) {
		for (ChainPlan.Attachment attachment : this.extra) {
			if (attachment.target() == binding.index() && attachment.side() == binding.side()) {
				if (this.collisions.add(binding.index())) {
					Vitrail.logger().warn("{} reads {} on the half it writes, so it is answered with "
							+ "one pixel: the pack does not double that target and one image cannot be "
							+ "both an attachment and a texture of one pass", this.path,
							TargetName.canonical(binding.index()));
				}

				return this.black.getColorTextureView();
			}
		}

		GpuTextureView view = this.targets.view(binding.index(), binding.side());

		return view == null ? this.black.getColorTextureView() : view;
	}

	/**
	 * A sampler's name with what it is really bound to, the half included. The half is the thing to
	 * read: a colour target read on the wrong one holds a clear colour, which is a picture that
	 * looks like a feature nobody turned on.
	 */
	private String describe(String sampler) {
		SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);
		if (binding.kind() != SamplerPlan.Kind.COLORTEX) {
			return sampler;
		}

		return sampler + "=" + TargetName.canonical(binding.index()) + " " + binding.side();
	}

	/**
	 * Whether this name is answered with something the frame really drew, rather than one pixel. A
	 * colour target counts even when it is empty at this point of the frame: it is the pack's own
	 * image and what it holds is a question about the order of the frame, not about the binding. A
	 * depth sampler counts only on the translucent pass, where the copy answers it.
	 */
	private boolean readsATexture(String sampler) {
		SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);
		SamplerPlan.Kind kind = binding.kind();

		return ATLAS.contains(sampler) || LIGHTMAP.equals(sampler)
				|| kind == SamplerPlan.Kind.COLORTEX
				|| kind == SamplerPlan.Kind.NOISE
				|| (kind == SamplerPlan.Kind.DEPTH && this.pass.afterDeferred())
				// The map exists from the first frame, but a pass that draws it reads its own
				// attachment and is answered with a constant like everything else that collides.
				|| (!this.pass.shadow() && kind == SamplerPlan.Kind.SHADOW_DEPTH
						&& this.shadow.depth() != null)
				|| (!this.pass.shadow() && kind == SamplerPlan.Kind.SHADOW_COLOUR
						&& this.shadow.colour(binding.index()) != null);
	}

	private FilterMode filter(String sampler) {
		if (LIGHTMAP.equals(sampler)) {
			return FilterMode.LINEAR;
		}

		// A colour target is filtered as the chain filters it, LINEAR wherever the format allows it,
		// so that a name reads the same here and one pass later.
		SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);
		if (binding.kind() == SamplerPlan.Kind.COLORTEX) {
			return this.targets.filter(binding.index());
		}

		// The noise image is a continuous field, not a lookup table: a pack derives water normals
		// and cloud shapes from it and counts on the interpolation. Iris binds it LINEAR_REPEAT,
		// and reading it NEAREST shatters every one of those surfaces into facets. A shadow colour
		// is continuous in the same way, carrying the light that came through glass and water
		// across a penumbra, and both OptiFine and Iris filter it linearly. The shadow DEPTH beside
		// it stays NEAREST because it is compared rather than interpolated.
		return switch (binding.kind()) {
			case NOISE, SHADOW_COLOUR -> FilterMode.LINEAR;
			default -> FilterMode.NEAREST;
		};
	}

	/**
	 * Said once, and grouped by what it costs the picture. Some names are constants, every sampler
	 * but two reads one pixel, a pass that wanted an alpha test may not have got one, and a fragment
	 * stage may declare more outputs than the one attachment Sodium's pass carries. None of them
	 * shows as an error and all of them change the image.
	 */
	private void announce() {
		if (this.announced) {
			return;
		}

		this.announced = true;
		TranslatedUnit fragment = this.loaded.program().stages().get(ProgramStage.FRAGMENT);
		int outputs = fragment.notes().fragmentOutputs();
		Vitrail.logger().info("Drawing the {} chunk pass with {} of {}, {} uniforms and {} samplers",
				this.pass.name().toLowerCase(Locale.ROOT), this.path, this.loaded.packName(),
				this.loaded.program().uniforms().size(), this.samplers.size());

		// A cutout stage without its discard draws a leaf as a cube, which reads as the pack being
		// wrong rather than as a translation that could not place a statement.
		AlphaTest alphaTest = this.loaded.alphaTest();
		if (alphaTest.tests() && fragment.notes().alphaEpilogue() == 0) {
			Vitrail.logger().warn("This pass discards at {} {} and the program could not be given the "
					+ "test, so nothing is discarded at all", alphaTest.function(),
					alphaTest.reference());
		}

		// Split by what the mesh really answers: mc_Entity comes out of the fifth element and is not
		// a gap, where a tangent and a mid texture coordinate still are and change what is drawn.
		List<String> constants = this.loaded.program().synthesized().keySet().stream()
				.filter(name -> !SodiumVertex.ANSWERED.contains(name))
				.toList();
		if (!constants.isEmpty()) {
			Vitrail.logger().warn("The chunk mesh carries none of these, so they are answered with a "
					+ "constant and what this program computes from them is wrong: {}", constants);
		}

		List<String> real = this.samplers.stream()
				.filter(this::readsATexture)
				.map(this::describe)
				.toList();
		List<String> flat = this.samplers.stream().filter(name -> !readsATexture(name)).toList();
		Vitrail.logger().info("{} samplers of this program read a real texture: {}", real.size(), real);
		if (!flat.isEmpty()) {
			// What is left is what nothing draws yet: the shadow map, and the depth on the passes
			// that draw before the copy of this frame is taken.
			Vitrail.logger().warn("{} read one pixel, because nothing fills them yet: {}",
					flat.size(), flat);
		}

		if (this.pass.shadow()) {
			Vitrail.logger().info("It draws into the shadow map, {}x{}, storing the forward depth "
					+ "window, and into shadowcolor0", this.shadow.resolution(),
					this.shadow.resolution());
			if (outputs > 1) {
				Vitrail.logger().warn("{} declares {} fragment outputs and this engine gives the "
						+ "shadow pass one, so all but the first are written nowhere", this.path,
						outputs);
			}
		} else if (this.ownsFirst) {
			// Nought included, and the log says the sides because they are the whole fix: a write on
			// the half the composites do not read is geometry that vanishes without a word from
			// anyone.
			Vitrail.logger().info("Its draw buffers all reach the pack's own targets, nought "
					+ "included: {}",
					this.extra.stream()
							.map(one -> TargetName.canonical(one.target()) + " " + one.side())
							.toList());
			if (this.covers) {
				// The pair to read this against is the seed's own line: this one says the mask is
				// written, that one says it is honoured.
				Vitrail.logger().info("It also writes the coverage mask, so nothing paints the game's "
						+ "own picture back over what this pass wrote");
			}
		} else if (this.extra.isEmpty()) {
			// Draw buffer nought is not named here on purpose: it goes to the game's own target,
			// which is where it has always gone and where the seed reads it back from.
			if (outputs > 1) {
				Vitrail.logger().warn("{} declares {} fragment outputs and writes one draw buffer, so "
						+ "all but the first are written nowhere", this.path, outputs);
			}
		} else {
			Vitrail.logger().info("Its other draw buffers reach the pack's own targets: {}",
					this.extra.stream()
							.map(one -> TargetName.canonical(one.target()) + " " + one.side())
							.toList());
		}

		// Said because nothing on screen would. A pack declaring sampler2DShadow asks the hardware
		// to compare and hand back a filtered fraction; blaze3d's GpuSampler carries no comparison
		// at all, so the translation makes it instead, one tap and a step. What is lost is the
		// softness of a compare filtered LINEAR, not the shadow: an edge one texel harder than the
		// pack drew against.
		//
		// Asked of the notes and not of the samplers: by the time a sampler is one of those, its
		// type has been rewritten to the ordinary one and there is nothing left to recognise.
		List<String> compared = this.loaded.program().stages().values().stream()
				.flatMap(unit -> unit.notes().comparedSamplers().stream())
				.distinct()
				.toList();
		if (!compared.isEmpty()) {
			Vitrail.logger().info("{} asked the hardware to compare {}, which this backend cannot "
					+ "bind, so the comparison is made in the shader with a single tap", this.path,
					compared);
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
