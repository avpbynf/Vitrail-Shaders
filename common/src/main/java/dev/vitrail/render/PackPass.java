package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.ChainPlan;
import dev.vitrail.pack.ProgramStage;
import dev.vitrail.pack.SamplerPlan;
import dev.vitrail.pack.TargetName;

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
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
	private final PackUniforms uniforms;
	private final List<String> samplers;
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
	 * @param load    the load counter, so that no two loads name their shaders alike
	 * @param offset  where this program's block sits in the chain's one ring buffer, already
	 *                rounded to the device's minimum uniform offset alignment
	 */
	PackPass(String place, String program, PackProgram.Loaded loaded, ChainPlan.Pass pass,
			ColorTargets targets, int load, int offset) {
		this.path = place.isEmpty() ? program : place + "/" + program;
		this.loaded = loaded;
		this.pass = pass;
		this.attachments = List.copyOf(pass.attachments());
		this.offset = offset;
		this.last = this.attachments.isEmpty();
		this.label = () -> "Vitrail " + this.path;
		this.uniforms = new PackUniforms(loaded.program().uniforms());
		this.samplers = loaded.program().samplers().stream().map(TranslatedUnit.Uniform::name).toList();

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

	void write(Std140Builder into, PackUniforms.Frame frame) {
		this.uniforms.write(into, frame);
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
				// White is not black on purpose: a depth of one is the far plane, so a lookup that
				// finds nothing reads open sky. Black would put the whole world in shadow.
				case DEPTH -> depthView == null ? targets.white() : depthView;
				case SHADOW_DEPTH, SHADOW_COLOUR -> targets.white();
				case NOISE -> targets.grey();
				case UNSERVED -> targets.black();
			};

			FilterMode filter = binding.kind() == SamplerPlan.Kind.COLORTEX
					? targets.filter(binding.index())
					: FilterMode.NEAREST;

			pass.bindTexture(sampler, bound == null ? targets.black() : bound,
					RenderSystem.getSamplerCache().getClampToEdge(filter));
		}
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

		List<String> unsupplied = this.uniforms.unsupplied();
		if (!unsupplied.isEmpty()) {
			this.notes.add(this.path + " reads " + unsupplied.size() + " values written as zeroes "
					+ "because nothing supplies them yet: " + unsupplied);
		}

		List<String> unserved = this.loaded.samplers().unserved();
		if (!unserved.isEmpty()) {
			this.notes.add(this.path + " declares " + unserved.size() + " samplers that read one "
					+ "black pixel because nothing serves them: " + unserved);
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
