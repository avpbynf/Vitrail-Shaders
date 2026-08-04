package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetSchedule;
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
 * and samplers, and nothing says so. A chain is ten programs where a final alone used to be one,
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
	private final PackProgram.Loaded loaded;
	private final ChainPlan.Pass pass;
	private final List<ChainPlan.Attachment> attachments;
	private final PackValues values;
	private final PackUniforms uniforms;
	private final List<String> samplers;
	private final List<LodRead> lodReads;

	/** The targets of {@link #lodReads}, for the binding to answer one name at a time. */
	private final Set<Integer> lodTargets;

	private final RenderPipeline pipeline;
	private final ShaderSource source;
	private final Supplier<String> label;
	private final List<String> notes = new ArrayList<>();
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
		this.loaded = loaded;
		this.pass = pass;
		this.attachments = List.copyOf(pass.attachments());
		this.offset = offset;
		this.last = this.attachments.isEmpty();
		this.label = () -> "Vitrail " + this.path;
		this.values = values;
		this.uniforms = new PackUniforms(loaded.program().uniforms(), values.catalog());
		this.samplers = loaded.program().samplers().stream().map(TranslatedUnit.Uniform::name).toList();
		this.lodReads = lodReadsOf(program, loaded, targets);
		this.lodTargets = this.lodReads.stream().map(LodRead::target).collect(Collectors.toSet());

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

				builder.withColorTargetState(slot, new ColorTargetState(Optional.empty(), format,
						ColorTargetState.WRITE_ALL));
			}

			for (int slot = this.attachments.size(); slot < this.outputs; slot++) {
				builder.withUnusedColorTargetState(slot);
			}
		}

		this.pipeline = builder.build();
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
	 * allocated here, and the attachments are loaded rather than cleared, since the clears for the
	 * whole frame have already run and an earlier pass may have written these very pixels.
	 */
	void draw(CommandEncoder encoder, ColorTargets targets, GpuTextureView depthView, GpuBuffer quad,
			GpuBufferSlice uniforms, int screenWidth, int screenHeight) {
		// The two draws are not interchangeable and neither mistake shows as an error: a final sent
		// here has one null attachment and no target at all, and a composite sent the other way
		// paints the screen instead of the buffer the rest of the chain reads.
		if (this.last) {
			throw new IllegalStateException(this.path + " writes the game's target and has to be "
					+ "drawn through drawFinal");
		}

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(this.label);
		for (ChainPlan.Attachment attachment : this.attachments) {
			descriptor.withColorAttachment(view(targets, attachment));
		}

		// Always at the tail. The encoder asserts that attachment zero is there, and a pipeline
		// whose state count differs from the attachment count is refused by setPipeline.
		for (int slot = this.attachments.size(); slot < this.outputs; slot++) {
			descriptor.withUnusedColorAttachment();
		}

		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0,
				this.pass.size().width(screenWidth), this.pass.size().height(screenHeight)));

		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			record(pass, targets, depthView, quad, uniforms);
		}
	}

	/**
	 * The {@code final}, which writes the game's own target rather than a colour target of the
	 * pack. Loaded and not cleared, as Iris has it: a final that does not cover every pixel leaves
	 * the world showing underneath.
	 */
	void drawFinal(CommandEncoder encoder, GpuTextureView into, ColorTargets targets,
			GpuTextureView depthView, GpuBuffer quad, GpuBufferSlice uniforms) {
		if (!this.last) {
			throw new IllegalStateException(this.path + " writes " + this.attachments.size()
					+ " colour targets of the pack and cannot be drawn onto the game's own");
		}

		try (RenderPass pass = encoder.createRenderPass(this.label, into, Optional.empty())) {
			record(pass, targets, depthView, quad, uniforms);
		}
	}

	private void record(RenderPass pass, ColorTargets targets, GpuTextureView depthView,
			GpuBuffer quad, GpuBufferSlice uniforms) {
		pass.setPipeline(this.pipeline);
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform(UNIFORM_BLOCK, uniforms);
		pass.setVertexBuffer(0, quad.slice());
		bindSamplers(pass, targets, depthView);
		pass.draw(VERTICES, 1, 0, 0);
	}

	/**
	 * Every name the layout carries has to be bound or the draw throws on the first one missing,
	 * so the plan answers for all of them and a name nothing serves gets one black pixel rather
	 * than being left out. The half a colour target is read from is the plan's answer for this
	 * program, taken when the pack was read.
	 */
	private void bindSamplers(RenderPass pass, ColorTargets targets, GpuTextureView depthView) {
		for (String sampler : this.samplers) {
			SamplerPlan.Binding binding = this.loaded.samplers().binding(sampler);
			GpuTextureView bound = switch (binding.kind()) {
				case COLORTEX -> targets.view(binding.index(), binding.side());
				// Black is not white on purpose, and it used to be the other way round. The pack
				// still has to read the far plane from a lookup that finds nothing, or it puts the
				// whole world against the camera, but it no longer reads what is stored: the
				// translation wraps every depth lookup in of_DepthConv.zw, and under the reversed
				// convention that is 1 - d. So the far plane is now stored as nought.
				// Tied to the convention being REVERSED, which it is for every pass while nothing
				// draws into a target of our own. The day one does, this has to follow.
				case DEPTH -> depth(binding.sampler(), targets, depthView);
				// White where the map is not there, and white is the far plane rather than a
				// placeholder: a shadowtex lookup is the one depth read the translation never wraps,
				// so the map stores the forward window and a lookup that finds nothing has to say
				// "nothing between here and the light". Black would put the world in its own shadow.
				case SHADOW_DEPTH -> or(SamplerPlan.withoutTranslucents(binding.sampler())
						? targets.shadow().depthWithoutTranslucents()
						: targets.shadow().depth(), targets.white());
				case SHADOW_COLOUR -> or(targets.shadow().colour(binding.index()), targets.white());
				case NOISE -> targets.noise();
				// A name this backend cannot bind should have taken its program out of the chain
				// before a frame was drawn. It is still answered rather than left out, because the
				// layout carries it either way and the draw throws on the first name it misses.
				case UNSERVED, UNBINDABLE -> targets.black();
			};

			// The noise image is LINEAR for the same reason the terrain reads it LINEAR: it is a
			// continuous field the pack interpolates surfaces out of, and Iris binds it that way.
			FilterMode filter = switch (binding.kind()) {
				case COLORTEX -> targets.filter(binding.index());
				case NOISE -> FilterMode.LINEAR;
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
			// to an earlier moment of the frame, or that nothing has written at all.
			boolean mipmaps = binding.kind() == SamplerPlan.Kind.COLORTEX
					&& this.lodTargets.contains(binding.index());

			// The noise image repeats and everything else clamps, which is Iris's choice and not a
			// taste: a pack indexes noisetex with coordinates of its own, in texels and well past
			// one, and clamped it reads the same edge row for the whole screen. That does not fail,
			// it produces a field of stripes that reads as an effect nobody asked for, and it takes
			// whatever the pack built out of it down with it.
			pass.bindTexture(sampler, bound == null ? targets.black() : bound,
					sampler(binding.kind(), filter, mipmaps));
		}
	}

	/**
	 * Which depth a name reads. {@code depthtex0} and {@code gdepthtex} are the depth as it stands
	 * when this pass draws: the live view, which for a deferred is the opaque world and for a
	 * composite the whole of it. {@code depthtex1} and {@code depthtex2} are the copy taken before
	 * the world's translucents, and they fall back to the live view rather than to black when no
	 * copy has been taken yet: the wrong moment of the right image, over a constant.
	 */
	private static GpuTextureView depth(String sampler, ColorTargets targets,
			GpuTextureView depthView) {
		if (SamplerPlan.depthCopy(sampler)) {
			GpuTextureView copy = targets.depthCopy();
			if (copy != null) {
				return copy;
			}
		}

		return depthView == null ? targets.black() : depthView;
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
		return kind == SamplerPlan.Kind.NOISE
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
		// as one number. A name the pack declares for itself is ours to resolve and the reason is
		// a line above this one; a name waiting on machinery nobody runs is not a hole in the
		// catalogue; and only what is left is a value this engine owes.
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

		if (!gaps.awaited().isEmpty()) {
			this.notes.add(this.path + " reads " + gaps.awaited().size() + " values that wait on a "
					+ "pass that does not run yet: " + gaps.awaited());
		}

		// Underneath the three, the members that count as supplied and are not: a zero that
		// arrived through a registered source is the one failure a screenshot can never show.
		List<String> standIns = PackValues.standIns(this.loaded.program().uniforms().stream()
				.map(TranslatedUnit.Uniform::name)
				.toList());
		if (!standIns.isEmpty()) {
			this.notes.add(this.path + " reads " + standIns.size() + " values answered with a "
					+ "stand-in rather than with a value, which count as supplied everywhere else: "
					+ standIns);
		}

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
