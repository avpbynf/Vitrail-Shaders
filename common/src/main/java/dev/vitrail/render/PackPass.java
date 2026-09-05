package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.pack.texture.TextureStage;
import dev.vitrail.uniform.TextSink;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
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
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * One program of a pack's chain, from its two translated stages to the draw that runs it.
 * <p>
 * Everything a frame needs is settled here when the pack is read: the pipeline, the attachments
 * in draw buffer order and on the half the schedule gave them, what every sampler is bound to,
 * and where this program's block sits in the chain's one uniform buffer. A frame replays that and
 * works nothing out again, which is what keeps one answer to the question of which half a pass
 * writes.
 * <p>
 * The two shader identifiers carry the load counter, the place and the program, and no two of
 * them may repeat. The device caches a compiled module under the identifier, the stage and the
 * defines, and never under the source, so two programs given one identifier are one program: the
 * second is not compiled at all, the first one's SPIR-V is drawn with the second one's targets
 * and samplers, and nothing says so. A chain is ten programs where a final alone is one,
 * and it is reloaded every time a setting is forced, so the constructor refuses a name it has
 * already handed out rather than trusting the rule to hold.
 * <p>
 * A render pass carries exactly as many colour attachments as the pipeline declares colour target
 * states, or the draw throws. A program whose fragment stage declares more outputs than it has
 * draw buffers is padded at the tail, on both sides at once; the padding is never at index 0,
 * which the encoder asserts against.
 */
final class PackPass {

	/** The block name the translator writes into every program. */
	private static final String UNIFORM_BLOCK = "OfGlobals";

	/** What a pipeline of this game can carry, and what the builder sizes its array for. */
	private static final int MAX_ATTACHMENTS = 8;

	/**
	 * A common ceiling on a pushed descriptor set. Nothing in the game asks the device for its
	 * own, so a program past this is named and still drawn: the failure, if it comes, is a driver
	 * error that this line makes readable.
	 */
	private static final int PUSH_DESCRIPTORS = 32;

	/** The format of the game's own target, which is what a {@code final} writes onto. */
	private static final GpuFormat SCREEN_FORMAT = GpuFormat.RGBA8_UNORM;

	/** The two triangles the caller hands in, in POSITION_TEX. */
	private static final int VERTICES = 6;

	/**
	 * The shader names one load has already given out. Only within a load can two programs
	 * collide, since the counter is part of every name, so the set is emptied when a new load
	 * starts rather than growing for as long as the game runs.
	 */
	private static final Set<Identifier> ISSUED = new LinkedHashSet<>();
	private static int issuedFor = -1;

	private final String path;

	/**
	 * Which of the seven moments of a frame this program is drawn in, which is what narrows a
	 * {@code texture.STAGE.NAME} override to the half of the frame the pack meant it for. Empty for
	 * a name no family claims, which is a name nothing runs.
	 */
	private final TextureStage textureStage;

	private final PackProgram.Loaded loaded;
	private final ChainPlan.Pass pass;
	private final List<ChainPlan.Attachment> attachments;
	private final PackValues values;
	private final PackUniforms uniforms;
	private final List<String> samplers;

	/**
	 * The plan's answer for each of {@link #samplers}, in that order.
	 * <p>
	 * Taken here because the plan is settled when the pack is read and the names never move: asked at
	 * the binding it was a lookup by name per sampler, per pass and per frame, and a name nothing
	 * serves built its answer afresh on every one of them.
	 */
	private final List<SamplerPlan.Binding> samplerBindings;

	/**
	 * For each of {@link #samplers}, what the pack supplies under that name, or null for every
	 * other kind of binding and for a name the pack took over with nothing behind it. Settled
	 * with the plan for the same reason as {@link #samplerBindings}: the resolution walked the
	 * pack's texture directives by name per sampler, per pass and per frame, and only the view
	 * behind the image can move.
	 */
	private final List<ColorTargets.PackSource> packSources;

	private final List<String> storage;
	private final List<LodRead> lodReads;

	/** The targets of {@link #lodReads}, for the binding to answer one name at a time. */
	private final Set<Integer> lodTargets;

	/**
	 * The schedule's step for this pass, or null where the schedule names none, for the computes
	 * hanging off it. Settled here because the schedule answers by walking its steps and
	 * comparing names, and the answer cannot change once the pack is read.
	 */
	private final TargetSchedule.Bound step;

	private final RenderPipeline pipeline;
	private final ShaderSource source;
	private final Supplier<String> label;
	private final List<String> notes = new ArrayList<>();
	private final List<GpuTextureView> attachedViews = new ArrayList<>();

	/**
	 * The area of the last descriptor built and the screen it was built for. This program's own size
	 * follows the screen and nothing else, so the value moves on a resize and never between two.
	 */
	private RenderPass.RenderArea area;
	private int areaWidth;
	private int areaHeight;

	private final int outputs;
	private final int offset;
	private final boolean last;

	/**
	 * Touches neither the device nor the render thread: it builds text, a layout and a pipeline
	 * description, all of which are read the first time a frame asks for them.
	 *
	 * @param place   where the program was read from, {@code world0} or the empty root
	 * @param program the bare name, {@code composite4}
	 * @param pass    what it writes, in draw buffer order and on the half the schedule chose. Empty
	 *                attachments mean the {@code final}, which writes the game's own target
	 * @param targets consulted for the format of each attachment, which the pipeline has to agree
	 *                with exactly, and never held
	 * @param values  what the pack is answered with: its catalogue fills this program's block, and
	 *                its reading of the pack tells a name the pack owes itself from one this engine
	 *                owes
	 * @param load    the load counter, so that no two loads name their shaders alike
	 * @param offset  where this program's block sits in the chain's one ring buffer, already
	 *                rounded to the device's minimum uniform offset alignment
	 */
	PackPass(String place, String program, PackProgram.Loaded loaded, ChainPlan.Pass pass,
			ColorTargets targets, PackValues values, int load, int offset) {
		this.path = place.isEmpty() ? program : place + "/" + program;
		this.textureStage = TextureStage.of(program).orElse(null);
		this.loaded = loaded;
		this.pass = pass;
		this.attachments = List.copyOf(pass.attachments());
		this.offset = offset;
		this.last = this.attachments.isEmpty();
		// Finished once: the encoder asks every pass of the game for its label to tell ours
		// apart, so a supplier that concatenated on every ask paid a string per pass per frame.
		String label = "Vitrail " + this.path;
		this.label = () -> label;
		this.values = values;
		this.uniforms = new PackUniforms(loaded.program().uniforms(), values.catalog());
		this.samplers = loaded.program().samplers().stream().map(TranslatedUnit.Uniform::name).toList();
		this.samplerBindings = this.samplers.stream().map(loaded.samplers()::binding).toList();
		List<ColorTargets.PackSource> sources = new ArrayList<>();
		for (int at = 0; at < this.samplers.size(); at++) {
			sources.add(this.samplerBindings.get(at).kind() == SamplerPlan.Kind.PACK_TEXTURE
					? targets.packSource(this.textureStage, this.samplers.get(at))
					: null);
		}
		this.packSources = Collections.unmodifiableList(sources);
		this.storage = loaded.storageBlocks().stream()
				.distinct()
				.filter(StorageBuffers::named)
				.toList();
		this.lodReads = lodReadsOf(program, loaded, targets);
		this.lodTargets = this.lodReads.stream().map(LodRead::target).collect(Collectors.toSet());
		this.step = targets.schedule().step(program).orElse(null);

		if (this.attachments.size() > MAX_ATTACHMENTS) {
			throw new IllegalStateException(this.path + " writes " + this.attachments.size()
					+ " targets and a pipeline carries " + MAX_ATTACHMENTS);
		}

		int declared = loaded.program().stages().get(ProgramStage.FRAGMENT).notes().fragmentOutputs();
		this.outputs = this.last
				? 1
				: Math.min(Math.max(this.attachments.size(), declared), MAX_ATTACHMENTS);

		String vertex = loaded.program().stages().get(ProgramStage.VERTEX).text();
		String fragment = loaded.program().stages().get(ProgramStage.FRAGMENT).text();
		String stem = "pack/" + load + "/" + (place.isEmpty() ? "root" : place) + "/" + program;
		Identifier vertexId = Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, stem + "/vertex");
		Identifier fragmentId = Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, stem + "/fragment");
		claim(load, this.path, vertexId, fragmentId);

		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				return fragmentId.equals(id) ? fragment : null;
			}

			return vertexId.equals(id) ? vertex : null;
		};

		BindGroupLayout.Builder bindings = BindGroupLayout.builder()
				.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER);
		this.samplers.forEach(bindings::withSampler);
		this.storage.forEach(name -> bindings.withUniform(name, UniformType.UNIFORM_BUFFER));

		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/" + stem))
				.withVertexShader(vertexId)
				.withFragmentShader(fragmentId)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(bindings.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false);

		if (this.last) {
			// The alpha of the game's target is left alone: the interface is drawn over it
			// afterwards and reads it.
			builder.withColorTargetState(0, new ColorTargetState(Optional.empty(), SCREEN_FORMAT,
					ColorTargetState.WRITE_COLOR));
		} else {
			for (int slot = 0; slot < this.attachments.size(); slot++) {
				int index = this.attachments.get(slot).target();
				GpuFormat format = targets.format(index);
				if (format == null) {
					// The plan allocates everything a program of the place writes, so this is the
					// plan disagreeing with itself. Refused here rather than at the first draw,
					// where the message would name a format and not a program.
					throw new IllegalStateException(this.path + " writes "
							+ TargetName.canonical(index) + " and the plan carries no format for it");
				}

				// A full screen program replaces what it writes unless the pack says otherwise, and
				// four packs of the corpus do say otherwise: Mellow and Reverie ask six of their
				// composites to blend ONE ONE, which is an accumulation. Replacing where a pack
				// asked to add is not a subtle difference, and nothing about it shows as an error.
				builder.withColorTargetState(slot, new ColorTargetState(
						BlendFunctions.of(targets.blend(program), Optional.empty()), format,
						ColorTargetState.WRITE_ALL));
			}

			for (int slot = this.attachments.size(); slot < this.outputs; slot++) {
				builder.withUnusedColorTargetState(slot);
			}
		}

		this.pipeline = builder.build();

		// Filed against the pipeline and not the program, because the pipeline is what the
		// descriptor walk can see when it has to answer for a name.
		ShadowCompare.note(this.pipeline, this.path, loaded);

		noteGaps(declared);
	}

	/**
	 * Compiles this program's pipeline if the device has not got it, and hands back what the cache
	 * now holds for it.
	 * <p>
	 * The compiled form is returned rather than a yes or no because the caller has no other way to
	 * find out that the cache was emptied under it. A resource reload empties it, F3+T included,
	 * and the next call here hands back a different instance; without that the chain would either
	 * recompile every program of every frame or draw one whose pipeline the device has quietly
	 * rebuilt from the game's own shader sources, which do not contain a line of this pack.
	 */
	CompiledRenderPipeline compile(GpuDevice device) {
		return device.precompilePipeline(this.pipeline, this.source);
	}

	/** {@code world0/composite4}, which is what a log line and a failure name it by. */
	String path() {
		return this.path;
	}

	/** The program name alone, {@code composite3}, which is what the schedule and the computes key on. */
	TargetSchedule.Bound step() {
		return this.step;
	}

	String program() {
		return this.pass.program();
	}

	int uniformOffset() {
		return this.offset;
	}

	/**
	 * The targets this program reads at a lod, each with the half it reads. Empty for all but a
	 * handful of programs: on BSL it is composite3, composite4, composite6 and composite7.
	 * <p>
	 * The caller fills these chains before drawing this program, and it has to be the caller: a
	 * chain is filled by its own render passes, and a pass cannot be opened inside another one.
	 */
	List<LodRead> lodReads() {
		return this.lodReads;
	}

	/**
	 * The colour targets this program draws into, with the half of each, which is what lets the
	 * caller know a chain it filled is no longer true of its base once this program has run.
	 */
	List<ChainPlan.Attachment> attachments() {
		return this.attachments;
	}

	/**
	 * Whether this program declares the sampler the smoothed centre depth was moved onto, which is
	 * what decides that the pass drawing that texel is worth running at all.
	 * <p>
	 * Iris asks for the same reason and asks a NARROWER question, and the difference costs a draw
	 * rather than a pixel. What sets its flag is the return of {@code addDynamicSampler}, which is
	 * true only where {@code glGetUniformLocation} finds the name ACTIVE in the linked program
	 * ({@code gl/program/ProgramSamplers.java:189-194}), so a sampler the pack declares and never
	 * reads is optimised out by the driver and answers no there. This side has no linked program to
	 * ask when the chain is built, so what it keys on is the declaration surviving the translation.
	 * A pack that declares the name in a program whose reader is switched off therefore has the one
	 * texel folded every frame here where Iris folds it once. Two packs of the corpus never write
	 * the name at all.
	 */
	boolean readsCenterDepth() {
		return this.samplers.contains(SamplerPlan.centerDepth());
	}

	/**
	 * One target this program reads at a lod, on the half the schedule gives this program.
	 *
	 * @param target the colour target index
	 * @param side   the half this program reads, which is where the chain has to be filled. Filling
	 *               the other one would leave this read on levels nothing wrote
	 */
	record LodRead(int target, TargetSchedule.Side side) {
	}

	/**
	 * Which targets this program reads at a lod, taken from its own bindings rather than from the
	 * directive alone.
	 * <p>
	 * The directive names a target and the binding names the half, and only the pair is actionable:
	 * a chain filled on the half this program does not read is work whose result it never sees. A
	 * target named by the directive that this program binds no sampler for is dropped here, which is
	 * what a pack that turns the directive on inside a branch it does not take looks like.
	 */
	private static List<LodRead> lodReadsOf(String program, PackProgram.Loaded loaded,
			ColorTargets targets) {
		Set<Integer> asked = targets.lodReads(program);
		if (asked.isEmpty()) {
			return List.of();
		}

		Map<Integer, TargetSchedule.Side> reads = new LinkedHashMap<>();
		for (TranslatedUnit.Uniform uniform : loaded.program().samplers()) {
			SamplerPlan.Binding binding = loaded.samplers().binding(uniform.name());
			if (binding.kind() == SamplerPlan.Kind.COLORTEX && asked.contains(binding.index())) {
				reads.putIfAbsent(binding.index(), binding.side());
			}
		}

		return reads.entrySet().stream()
				.map(entry -> new LodRead(entry.getKey(), entry.getValue()))
				.toList();
	}

	/**
	 * Never zero. A program can declare an empty block, and a zero length slice is not something
	 * to hand a descriptor; the block is bound whatever the program reads from it.
	 */
	int uniformSize() {
		return Math.max(16, this.uniforms.size());
	}

	/**
	 * What one program's block takes, answerable before anywhere has been found for it. The chain
	 * lays every block out before it builds a single pass, and this is what it measures with.
	 */
	static int uniformSizeOf(PackProgram.Loaded loaded) {
		return Math.max(16, new PackUniforms(loaded.program().uniforms()).size());
	}

	void write(Std140Builder into, WorldState world) {
		this.uniforms.write(into, world);
	}

	/** This program's block as {@code name = value} text, for the decoded dump. */
	String decoded(WorldState world) {
		TextSink sink = new TextSink();
		this.uniforms.write(sink, world);

		return sink.text();
	}

	/** Two for the block and the globals, then one per sampler. Logged for every program. */
	int descriptors() {
		return this.samplers.size() + 2;
	}

	/**
	 * One line for the log, and the one line that says what this program does to the picture:
	 * which targets it writes and, for each, which half of the ping pong. A wrong image is read
	 * back against these, so the halves are spelled out rather than implied.
	 */
	String describe() {
		StringBuilder line = new StringBuilder(this.path).append(" writes ").append(writes());
		if (!this.last && !this.pass.size().full()) {
			line.append(", sized ").append(this.pass.size().relative()
					? String.format(Locale.ROOT, "%.3f by %.3f of the screen",
							this.pass.size().width(), this.pass.size().height())
					: (int) this.pass.size().width() + " by " + (int) this.pass.size().height()
							+ " pixels");
		}

		return line.append(", ")
				.append(this.loaded.program().uniforms().size()).append(" uniforms and ")
				.append(this.samplers.size()).append(" samplers, ")
				.append(descriptors()).append(" descriptors")
				.toString();
	}

	private String writes() {
		if (this.last) {
			return "the game's own target";
		}

		return this.attachments.stream()
				.map(attachment -> TargetName.canonical(attachment.target()) + " "
						+ attachment.side().name().toLowerCase(Locale.ROOT))
				.collect(Collectors.joining(", "));
	}

	/** Whole sentences, already naming the program, for the log this pack writes once. */
	List<String> notes() {
		return List.copyOf(this.notes);
	}

	/**
	 * {@code colortex6 as sampler3D}. The type is always printed beside the name, because the name
	 * on its own is what made this look like a colour target in the first place.
	 */
	static String describe(List<TranslatedUnit.Uniform> samplers) {
		return samplers.stream()
				.map(sampler -> sampler.name() + " as " + sampler.type())
				.collect(Collectors.joining(", "));
	}

	/**
	 * Opens and closes its own render pass, which is what makes the next one able to read what
	 * this one wrote: the Vulkan backend ends a pass with a full memory barrier. Nothing is
	 * allocated here. The first write of a target this frame loads as a clear, which is
	 * {@code glClear} as the FBO is bound; later writes load what the last pass left.
	 */
	void draw(CommandEncoder encoder, ColorTargets targets, GpuTextureView depthView,
			GpuTextureView distantView, GpuBuffer quad, GpuBufferSlice uniforms, int screenWidth,
			int screenHeight) {
		// The two draws are not interchangeable and neither mistake shows as an error: a final sent
		// here has one null attachment and no target at all, and a composite sent the other way
		// paints the screen instead of the buffer the rest of the chain reads.
		if (this.last) {
			throw new IllegalStateException(this.path + " writes the game's target and has to be "
					+ "drawn through drawFinal");
		}

		this.attachedViews.clear();
		for (ChainPlan.Attachment attachment : this.attachments) {
			this.attachedViews.add(view(targets, attachment));
		}

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(this.label);
		for (GpuTextureView view : this.attachedViews) {
			descriptor.withColorAttachment(view, targets.takeClear(view));
		}

		// Always at the tail. The encoder asserts that attachment zero is there, and a pipeline
		// whose state count differs from the attachment count is refused by setPipeline.
		for (int slot = this.attachments.size(); slot < this.outputs; slot++) {
			descriptor.withUnusedColorAttachment();
		}

		descriptor.withRenderArea(area(screenWidth, screenHeight));

		if (targets.hasPendingClears()) {
			targets.flushPending(encoder);
		}

		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			record(pass, targets, depthView, distantView, quad, uniforms);
		}
	}

	/**
	 * The area this program is drawn over, built again only where the screen has moved since the last
	 * one. The record is read by the descriptor and kept by nobody else.
	 */
	private RenderPass.RenderArea area(int screenWidth, int screenHeight) {
		if (this.area == null || screenWidth != this.areaWidth || screenHeight != this.areaHeight) {
			this.areaWidth = screenWidth;
			this.areaHeight = screenHeight;
			this.area = new RenderPass.RenderArea(0, 0, this.pass.size().width(screenWidth),
					this.pass.size().height(screenHeight));
		}

		return this.area;
	}

	/**
	 * The {@code final}, which writes the game's own target rather than a colour target of the
	 * pack. Loaded and not cleared, as Iris has it: a final that does not cover every pixel leaves
	 * the world showing underneath.
	 */
	void drawFinal(CommandEncoder encoder, GpuTextureView into, ColorTargets targets,
			GpuTextureView depthView, GpuTextureView distantView, GpuBuffer quad,
			GpuBufferSlice uniforms) {
		if (!this.last) {
			throw new IllegalStateException(this.path + " writes " + this.attachments.size()
					+ " colour targets of the pack and cannot be drawn onto the game's own");
		}

		try (RenderPass pass = encoder.createRenderPass(this.label, into, Optional.empty())) {
			record(pass, targets, depthView, distantView, quad, uniforms);
		}
	}

	private void record(RenderPass pass, ColorTargets targets, GpuTextureView depthView,
			GpuTextureView distantView, GpuBuffer quad, GpuBufferSlice uniforms) {
		pass.setPipeline(this.pipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform(UNIFORM_BLOCK, uniforms);
		StorageBuffers.bind(pass, this.storage);
		pass.setVertexBuffer(0, quad.slice());
		bindSamplers(pass, targets, depthView, distantView);
		pass.draw(VERTICES, 1, 0, 0);
	}

	/**
	 * Every name the layout carries has to be bound or the draw throws on the first one missing,
	 * so the plan answers for all of them and a name nothing serves gets one black pixel rather
	 * than being left out. The half a colour target is read from is the plan's answer for this
	 * program, taken when the pack was read.
	 *
	 * @param depthView   the depth of the half this pass stands in, already converted into the
	 *                    pack's own window. Never the game's live view: a pack reads what it is
	 *                    handed and has nothing left in it that would turn a reversed depth round
	 * @param distantView the far terrain's depth on the same split, already converted as well, or
	 *                    null for the far plane on the frames the pack drew no far terrain
	 */
	private void bindSamplers(RenderPass pass, ColorTargets targets, GpuTextureView depthView,
			GpuTextureView distantView) {
		for (int at = 0; at < this.samplers.size(); at++) {
			String sampler = this.samplers.get(at);
			SamplerPlan.Binding binding = this.samplerBindings.get(at);

			// A texture the pack ships answers all three questions at once, and they are one
			// answer: which image, how it is filtered, and how it is addressed outside zero to one
			// are all the pack's to say, in the same directive and the same .mcmeta beside it.
			ColorTargets.PackSource source = this.packSources.get(at);
			GpuTextureView supplied = source == null ? null : targets.packView(source.image());

			// Resolved once for the view and the chain below, which used to be two walks of the
			// same map for one name.
			TargetSurface surface = binding.kind() == SamplerPlan.Kind.COLORTEX
					? targets.surface(binding.index(), binding.side())
					: null;

			GpuTextureView bound = supplied != null ? supplied : switch (binding.kind()) {
				case COLORTEX -> surface == null ? null : surface.view();
				// White where no image is there, and white is the far plane rather than a
				// placeholder: what a depth lookup reads is now an image already in the pack's own
				// window, where one is the far plane, and the whole world would otherwise be drawn
				// against the camera. It follows the image rather than the convention of the
				// target, which is what makes it the same answer as the shadow map's.
				case DEPTH -> depth(binding.sampler(), targets, depthView);
				// The far plane where the map is not there, rather than a placeholder: a shadowtex
				// lookup is the one depth read the translation never wraps, so the map stores the
				// forward window and a lookup that finds nothing has to say "nothing between here
				// and the light". Black would put the world in its own shadow. In a depth format
				// first, because a comparison sampler is only defined against one; the RGBA white
				// stays as the answer of last resort for the frames before the constants exist.
				case SHADOW_DEPTH -> or(this.loaded.samplers().withoutTranslucents(binding.sampler())
						? targets.shadow().depthWithoutTranslucents()
						: targets.shadow().depth(),
						or(ConstantTextures.farPlaneIfReady(), targets.white()));
				case SHADOW_COLOUR -> or(targets.shadow().colour(binding.index()), targets.white());
				case NOISE -> targets.noise();
				// White until the pass behind it has drawn once, which is the far plane in the pack's
				// own window and so a focus point at the horizon. Black would be a focus point at the
				// camera, which is the defect this whole path exists to close.
				case CENTER_DEPTH -> centerDepth(targets);
				// The far terrain's own depth, kept beside the world's as Iris keeps it, and white
				// for the far plane on the frames the pack drew no far terrain: the pack's Distant
				// Horizons branches then stay shut, exactly as without the mod. dhDepthTex1 is the
				// image without the water whichever half asks, which is Iris's copy before the
				// translucent LODs; the other two names follow the half, like depthtex0.
				case DISTANT_DEPTH -> distant(binding.sampler(), targets, distantView);
				// A name this backend cannot bind should have taken its program out of the chain
				// before a frame was drawn. It is still answered rather than left out, because the
				// layout carries it either way and the draw throws on the first name it misses.
				//
				// A pack texture reaches this line only when the pack took the name over and
				// nothing could be read for it, which does not cover a file the pack simply does
				// not ship: that case hands the name back and the colour target keeps its ordinary
				// binding, as it does under Iris. What is left here is a declaration that named
				// something real and could not be turned into an image, and black is the honest
				// answer for it: falling back to the colour target of the same name would have the
				// pass read the scene as whatever the pack meant to sample and look convincing.
				case UNSERVED, UNBINDABLE, PACK_TEXTURE, CUSTOM_IMAGE -> targets.black();
			};

			// The noise image is LINEAR for the same reason the terrain reads it LINEAR: it is a
			// continuous field the pack interpolates surfaces out of, and Iris binds it that way.
			//
			// A shadow COLOUR is LINEAR as well, and that is both authorities rather than a taste:
			// OptiFine filters shadowcolor linearly unless the pack writes shadowcolorNNearest, and
			// Iris binds it LINEAR outright (IrisSamplers.addShadowSamplers). It carries the light
			// that came through stained glass and water, which a pack blurs across a penumbra; read
			// NEAREST it steps in blocks the size of a shadow texel.
			//
			// The shadow DEPTH is LINEAR for a reason of its own, and the reason a reader reaches
			// for to keep it NEAREST does not hold. An averaged depth would indeed be a comparison
			// against a surface standing nowhere, but that is true of an ordinary sampler and not
			// of a comparison one, where the hardware compares first and averages the RESULTS; and
			// the comparison this engine makes for itself takes its four texels with textureGather,
			// which no bound filter reaches either way. What the filter really decides is the other
			// read, the one every pack of the corpus makes: a PCF loop that samples shadowtex as a
			// plain sampler2D and expects each of its taps to be smoothed over a texel. Iris
			// filters both depth images LINEAR unless the pack asks otherwise, since
			// SamplingSettings.nearest starts false (ShadowRenderer.configureDepthSampler), so read
			// NEAREST every edge of such a loop walks in texels of the map however many taps it
			// pays for.
			//
			// The three names that would ask for NEAREST back - shadowtexNearest, shadowtexNNearest
			// and shadowNMinMagNearest - are not read: no pack of the corpus writes one.
			//
			// A custom image is the image's own answer and not this pass's, which is why it is
			// asked of one place rather than decided here: see customImageFilter.
			FilterMode filter = supplied != null ? source.filter() : switch (binding.kind()) {
				case COLORTEX -> targets.filter(binding.index());
				case NOISE, SHADOW_COLOUR, SHADOW_DEPTH -> FilterMode.LINEAR;
				case CUSTOM_IMAGE -> customImageFilter(sampler);
				default -> FilterMode.NEAREST;
			};

			// A lod read needs a sampler that may climb past level nought, and that is not the
			// default: the cache's ordinary samplers stop there, so a lod read would come back with
			// the base image and a filled chain would look like it had never been written.
			//
			// Narrowed to the targets THIS program reads at a lod, which are exactly the chains the
			// caller filled before this draw, and not to every target that carries one. The two are
			// not the same set, and the difference is not a detail: a chain is only filled before
			// its reader, so letting an unrelated read climb one would hand it levels that belong
			// to an earlier moment of the frame.
			//
			// And asked of the surface as well, because the reduction is allowed to fail: its
			// pipeline may refuse to compile for a format, and then the levels hold whatever the
			// driver left there. Deciding this at the binding rather than when the pass was built
			// is what makes the fall back real instead of announced: a chain nothing has written
			// is read at level nought, which is the image the pack had before there were chains.
			boolean mipmaps = surface != null && surface.chainWritten()
					&& this.lodTargets.contains(binding.index());

			// The noise image repeats and everything else clamps, which is Iris's choice and not a
			// taste: a pack indexes noisetex with coordinates of its own, in texels and well past
			// one, and clamped it reads the same edge row for the whole screen. That does not fail,
			// it produces a field of stripes that reads as an effect nobody asked for, and it takes
			// whatever the pack built out of it down with it. A texture of the pack's own says for
			// itself, in the .mcmeta beside the file.
			pass.bindTexture(sampler, bound == null ? targets.black() : bound,
					supplied != null
							? sampler(source.repeat(), filter, false)
							: sampler(binding.kind(), filter, mipmaps));
		}
	}

	/**
	 * Which depth a name reads. {@code depthtex0} and {@code gdepthtex} are the depth of the half
	 * this pass stands in, which for a deferred is the opaque world and for a composite the whole
	 * scene. {@code depthtex1} and {@code depthtex2} are the opaque world whichever half asks, and
	 * they fall back to the half's own image rather than to a constant while nothing has filled
	 * them: the wrong moment of the right image, over the far plane everywhere.
	 * <p>
	 * The two part company over the hand, and only over the hand: {@code depthtex2} is taken one step
	 * earlier, before the hand's solid pass, so that a pack can read what the hand stands in front
	 * of. On every frame no hand of this engine's was drawn - the game keeping its own, which is what
	 * the {@code hand} line of {@code vitrail/options.txt} still leaves it doing by default, or
	 * nothing being on screen to draw - there is no such image and none is needed, the two moments
	 * holding the same depth, and the fall through below is that answer rather than a gap. It is
	 * also reached when an image was wanted and could not be had, which is not the same thing:
	 * {@link PackDepth#preHand} carries the cases apart and says which of them reach the log.
	 */
	static GpuTextureView depth(String sampler, ColorTargets targets, GpuTextureView depthView) {
		if (SamplerPlan.depthCopy(sampler)) {
			if (SamplerPlan.preHandCopy(sampler)) {
				GpuTextureView preHand = targets.depth().preHand();
				if (preHand != null) {
					return preHand;
				}
			}

			GpuTextureView opaque = targets.depth().opaque();
			if (opaque != null) {
				return opaque;
			}
		}

		return depthView == null ? targets.white() : depthView;
	}

	/**
	 * Which far terrain depth a name reads, and white for the far plane on the frames the pack
	 * drew no far terrain, so that the pack's Distant Horizons branches stay shut exactly as
	 * without the mod. {@code dhDepthTex1} is the image without the water whichever half asks,
	 * which is Iris's copy before the translucent LODs; the other two names follow the half, like
	 * {@code depthtex0}. Package private for the compute road, so that a compute reads the far
	 * terrain exactly as the pass it hangs off does.
	 */
	static GpuTextureView distant(String sampler, ColorTargets targets, GpuTextureView distantView) {
		return SamplerPlan.distantWithoutWater(sampler)
				? or(targets.depth().distantOpaque(), targets.white())
				: or(distantView, targets.white());
	}

	/**
	 * The one texel {@code centerDepthSmooth} is read out of, and white until the pass behind it
	 * has drawn once: the far plane in the pack's own window, so a focus point at the horizon,
	 * where black would be one at the camera. Package private for the compute road, which Iris
	 * hands the same texel ({@code pipeline/CompositeRenderer.java:461}).
	 */
	static GpuTextureView centerDepth(ColorTargets targets) {
		return or(targets.centerDepth().view(), targets.white());
	}

	/** The first of the two that exists, for a name whose image may not be allocated yet. */
	private static GpuTextureView or(GpuTextureView view, GpuTextureView fallback) {
		return view == null ? fallback : view;
	}

	/**
	 * How a name is addressed outside zero to one, and how far up its chain it may be read. Only the
	 * noise image tiles, and only a target something reads at a lod is given a sampler that goes
	 * past level nought.
	 */
	static GpuSampler sampler(SamplerPlan.Kind kind, FilterMode filter, boolean mipmaps) {
		return sampler(kind == SamplerPlan.Kind.NOISE, filter, mipmaps);
	}

	/**
	 * How a custom image is filtered, which its FORMAT decides and not the pass reading it.
	 * <p>
	 * Iris settles this once and on the image itself, when it allocates it
	 * ({@code gl/image/GlImage.java:51-53}): LINEAR unless the format is integer, and every
	 * consumer then reads it through that one setting. There is no such place here, an image being
	 * bound with a sampler of the caller's choosing, so this method is that place instead: the
	 * geometry programs, the full screen passes and the compute passes all ask it, and a pack
	 * therefore reads one volume the same way wherever it reads it from.
	 * <p>
	 * The floodfill volumes are {@code rgba16f} and the light they carry is continuous, so NEAREST
	 * turns every voxel into a lit brick with a hard edge for the packs that interpolate them.
	 * Most read them with {@code texelFetch}, which no filter reaches, which is why only some show
	 * it. The format's half of the answer is {@link GpuFormats#filterFor}, which the colour targets
	 * already go through; what is this method's own is the default for a name no {@code image.}
	 * directive declared, and that is NEAREST.
	 */
	static FilterMode customImageFilter(String sampler) {
		return CustomImages.image(sampler)
				.map(image -> GpuFormats.filterFor(image.internalFormat().used()))
				.orElse(FilterMode.NEAREST);
	}

	/** The same, where the addressing is the pack's own answer rather than the name's. */
	static GpuSampler sampler(boolean repeat, FilterMode filter, boolean mipmaps) {
		return repeat
				? RenderSystem.getSamplerCache().getRepeat(filter, mipmaps)
				: RenderSystem.getSamplerCache().getClampToEdge(filter, mipmaps);
	}

	private GpuTextureView view(ColorTargets targets, ChainPlan.Attachment attachment) {
		GpuTextureView view = targets.view(attachment.target(), attachment.side());
		if (view == null) {
			// Named rather than let through: a null attachment reaches the encoder as an assertion
			// or a null pointer, neither of which says which program wrote which target.
			throw new IllegalStateException(this.path + " writes "
					+ TargetName.canonical(attachment.target()) + " on its " + attachment.side()
					+ " half and nothing was allocated for it");
		}

		return view;
	}

	private void noteGaps(int declared) {
		if (declared > this.attachments.size() && !this.last) {
			this.notes.add(this.path + " declares " + declared + " fragment outputs for "
					+ this.attachments.size() + " draw buffers, so the ones past the last draw "
					+ "buffer are written nowhere");
		}

		if (this.last && declared > 1) {
			this.notes.add(this.path + " declares " + declared + " fragment outputs and a final "
					+ "writes the game's one target, so all but the first are written nowhere");
		}

		if (descriptors() > PUSH_DESCRIPTORS) {
			this.notes.add(this.path + " binds " + descriptors() + " descriptors in one set, past "
					+ "the " + PUSH_DESCRIPTORS + " a device commonly allows pushed at once");
		}

		// What the block could not be given, said as the three different things it is rather than
		// as one number. A name the pack declares for itself is ours to resolve and the reason is a
		// line above this one; a name no engine answers is nobody's; what is left is a value this
		// engine owes.
		PackValues.Gaps gaps = this.values.classify(this.uniforms.unsupplied());
		if (!gaps.engine().isEmpty()) {
			this.notes.add(this.path + " reads " + gaps.engine().size() + " values written as "
					+ "zeroes because this engine does not supply them yet: " + gaps.engine());
		}

		if (!gaps.pack().isEmpty()) {
			this.notes.add(this.path + " reads " + gaps.pack().size() + " values "
					+ this.loaded.packName() + " declares for itself, and none of those "
					+ "declarations survived: " + gaps.pack());
		}

		// And beside those, the names that are nobody's debt: the pack reads them under Iris as
		// well and gets the same nought, so saying this engine owes them would send a reader
		// looking through Iris for a source that was never written.
		if (!gaps.nobody().isEmpty()) {
			this.notes.add(this.path + " reads " + gaps.nobody().size() + " values no engine "
					+ "answers, Iris included, so they are zeroes there too: " + gaps.nobody());
		}

		// Underneath the three, the members that count as supplied and are not: a zero that
		// arrived through a registered source is the one failure a screenshot can never show.
		PackValues.standIns(this.loaded.program().uniforms().stream()
						.map(TranslatedUnit.Uniform::name)
						.toList())
				.forEach((reason, names) -> this.notes.add(this.path + " reads " + names
						+ " answered with a stand-in rather than with a value, which count as "
						+ "supplied everywhere else, because " + reason));

		List<String> unserved = this.loaded.samplers().unserved();
		if (!unserved.isEmpty()) {
			this.notes.add(this.path + " declares " + unserved.size() + " samplers that read one "
					+ "black pixel because nothing serves them: " + unserved);
		}

		// This one should be unreachable: a program declaring such a sampler is taken out of the
		// chain before anything is built. It stays because the failure it would otherwise produce
		// is the raw one this whole path exists to replace, and it names the type as well.
		List<TranslatedUnit.Uniform> unbindable = this.loaded.unbindable();
		if (!unbindable.isEmpty()) {
			this.notes.add(this.path + " is being drawn although it declares "
					+ describe(unbindable) + ", which this backend cannot bind, so its pipeline "
					+ "will not build");
		}
	}

	private static synchronized void claim(int load, String path, Identifier vertex,
			Identifier fragment) {
		if (issuedFor != load) {
			issuedFor = load;
			ISSUED.clear();
		}

		if (!ISSUED.add(vertex) || !ISSUED.add(fragment)) {
			throw new IllegalStateException("Two programs of one load would be compiled under the "
					+ "same names, " + vertex + " and " + fragment + ", the second being " + path);
		}
	}
}
