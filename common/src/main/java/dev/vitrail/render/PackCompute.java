package dev.vitrail.render;

import dev.vitrail.glsl.LoadClock;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.mixin.access.CommandEncoderAccessor;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.mixin.access.VulkanCommandEncoderAccessor;
import dev.vitrail.pack.program.ProgramNames;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.pack.texture.TextureStage;
import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pack's compute passes: the shadow computes, dispatched at the head of the frame, and the
 * computes hanging off a full screen pass, dispatched right before that pass.
 * <p>
 * The shadow computes run in the parity of the gbuffers that read what they propagate; the
 * volumes they read are the previous frame's shadow-geometry writes, one frame late like the
 * shadow map itself. Complementary's floodfill lives in {@code shadowcomp.csh}. Iris runs it
 * inside its shadow render ({@code ShadowRenderer.java:631-632}, the debug group and the
 * {@code compositeRenderer.renderAll()} under it), before its gbuffers in the SAME frame. Under
 * this engine's deferred shadow stage, the head of the frame is that moment's translation.
 * <p>
 * The chained computes, {@code deferred4_a.csh} for {@code deferred4}, run where Iris runs them:
 * in a loop right before their pass, with a memory barrier after ({@code CompositeRenderer.java:287-297}),
 * reading and storing the colour targets on the halves that pass reads. Photon builds its sky
 * lighting in one, and without it everything in shadow was black.
 * <p>
 * The Java facade has no compute, so the pipeline is built the way the storage probe was: shaderc
 * kind 2, push descriptors, and a storage image that is either the pack's own through VMA or a
 * colour target created with the usage for it.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris ComputeProgram, LGPL-3.0</a>
 */
final class PackCompute implements AutoCloseable {

	private static final int SHADERC_VULKAN_1_2 = 4202496;
	private static final int SHADERC_COMPUTE = 2;

	/**
	 * Stage token hashed into {@link ModuleCache}'s key for this road, and nowhere else. The
	 * game's compiler never sees a compute: shaderc kind, optimisation and target live only in
	 * {@link Pass#compileSpirv}. Naming those here keeps a later {@code ShaderType.COMPUTE}
	 * through {@code GlslCompiler} from serving this blob, or the other way around.
	 */
	private static final String MODULE_CACHE_STAGE = "COMPUTE/shaderc-opt2-vulkan1.2";

	/**
	 * A common ceiling on a pushed descriptor set, the same one the graphics side carries in
	 * {@link PackPass}. Nothing in the game asks the device for its own, so a dispatch past this
	 * is named and still made: the failure, if it comes, is a driver error that the line makes
	 * readable.
	 */
	private static final int PUSH_DESCRIPTORS = 32;

	/** The pattern of a colour target written as an image, {@code colorimg4} for {@code colortex4}. */
	private static final Pattern COLOUR_IMAGE = Pattern.compile("\\bcolorimg(\\d+)\\b");

	/** The shadow computes, dispatched at the head of the frame. */
	private final List<Pass> passes;

	/**
	 * The computes hanging off a full screen pass, by that pass, each list in letter order. Iris
	 * dispatches them right before the pass ({@code CompositeRenderer.java:287-297}, the loop over
	 * {@code compositePass.computes} with a memory barrier after), and so does the chain here.
	 */
	private final Map<String, List<Pass>> chained;

	/** The targets some compute writes as {@code colorimgN}, which are created writable for it. */
	private final Set<Integer> storageTargets;

	private boolean announced;

	/** The passes whose computes have been announced once, which is once per pass and not per frame. */
	private final Set<String> announcedChains = new LinkedHashSet<>();

	private PackCompute(List<Pass> passes, Map<String, List<Pass>> chained,
			Set<Integer> storageTargets) {
		this.passes = List.copyOf(passes);
		this.chained = Map.copyOf(chained);
		this.storageTargets = Set.copyOf(storageTargets);
	}

	static PackCompute none() {
		return new PackCompute(List.of(), Map.of(), Set.of());
	}

	/** The targets to create writable from a compute, read before the first allocation. */
	Set<Integer> storageTargets() {
		return this.storageTargets;
	}

	/** Whether any compute hangs off that full screen pass. */
	boolean hangsOff(String program) {
		return this.chained.containsKey(program);
	}

	/**
	 * Whether a compute hanging off a full screen pass samples {@code centerDepthSmooth}, keyed
	 * like the passes on the declaration surviving the translation. Such a compute is handed the
	 * texel the same way its pass is, so a chain whose only reader is one has to fold it the same
	 * way: Iris arms the fold from its compute builder as it does from a composite
	 * ({@code pipeline/CompositeRenderer.java:466}).
	 * <p>
	 * The shadow computes do not count. They run at the head of the frame, before the opaque
	 * world whose depth the fold takes exists, and Iris hands them no such sampler at all: its
	 * shadow builder ({@code pipeline/IrisRenderingPipeline.java:544},
	 * {@code createShadowComputes}) gives them the targets, the custom textures and images, the
	 * level samplers, the noise and the shadow set, and the centre depth is bound nowhere but in
	 * the composite and final builders. A shadow compute declaring the name reads here what the
	 * fold last left, or white where nothing arms it, which is nearer what it reads under Iris
	 * than a fold of its own would be.
	 */
	boolean readsCenterDepth() {
		for (List<Pass> passes : this.chained.values()) {
			for (Pass pass : passes) {
				for (TranslatedUnit.Uniform sampler : pass.compute.loaded().program().samplers()) {
					if (sampler.name().equals(SamplerPlan.centerDepth())) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/**
	 * Reads every compute the pack ships and the plan kept, out of the opening the load already
	 * holds: the shadow computes for the head of the frame, and the computes hanging off a pass of
	 * the chain for the moment before that pass.
	 * <p>
	 * The opening is handed in and not taken here, and this loop is why it matters: read a program
	 * at a time from a pack path, each turn mounted the archive again and walked every source file
	 * of it to rebuild the same index of the same settings, so a pack with four computes paid for
	 * four whole readings of itself to translate four files.
	 *
	 * @param running the programs the chain draws, which is where a chained compute can hang. A
	 *                compute whose pass is not among them is read by Iris and dispatched with no
	 *                fragment stage at all ({@code CompositeRenderer.java:135-141}); here it is
	 *                named and left, since the chain has no step to hang it off yet
	 */
	static PackCompute load(OpenedPack pack, String place, List<String> computes, int load,
			UniformCatalog shadowCatalog, UniformCatalog chainCatalog, Set<String> running) {
		List<Pass> passes = new ArrayList<>();
		Map<String, List<Pass>> chained = new LinkedHashMap<>();
		Set<Integer> storageTargets = new LinkedHashSet<>();
		for (String name : computes) {
			boolean shadow = ProgramNames.shadowComposite(ProgramNames.familyOf(name));
			Optional<String> base = ProgramNames.computeBase(name);
			// Setup has no moment in this frame yet. The plan already names it.
			if (!shadow && base.isEmpty()) {
				continue;
			}

			if (!shadow && !running.contains(base.get())) {
				Vitrail.logger().warn("compute {} hangs off {}, which this chain does not draw, so "
						+ "it is not dispatched: the reference runs it as a pass of its own",
						name, base.get());
				continue;
			}

			String path = place.isEmpty() ? name : place + "/" + name;
			try {
				Optional<PackProgram.Compute> compute = PackProgram.loadCompute(pack, path);
				if (compute.isEmpty()) {
					continue;
				}

				// Left undispatched rather than dispatched at a guessed size. A program on one of
				// the screen roads is sized by dividing the screen by its own local size, and a
				// local size this engine cannot read as a number would have to be invented: read
				// as one where the shader means sixteen, the guess asks for a group per pixel and
				// stalls the frame instead of drawing it wrong.
				if (!compute.get().sized()) {
					Vitrail.logger().warn("compute {} is not dispatched: it leaves its work "
							+ "group count to the engine and writes its local size as something "
							+ "other than a number, so how much work it asks for cannot be read",
							path);
					continue;
				}

				Pass pass = new Pass(compute.get(), shadow ? shadowCatalog : chainCatalog, load,
						path, shadow ? null : base.get());
				if (shadow) {
					passes.add(pass);
					Vitrail.logger().info("Loaded shadow compute {} ({})", path, sizing(compute.get()));
				} else {
					chained.computeIfAbsent(base.get(), _ -> new ArrayList<>()).add(pass);
					storageTargets.addAll(colourImagesOf(compute.get()));
					Vitrail.logger().info("Loaded compute {} before {} ({})", path, base.get(),
							sizing(compute.get()));
				}
			} catch (IOException | RuntimeException e) {
				Vitrail.logger().warn("compute {} could not be translated: {}", path, e.toString());
			}
		}

		chained.values().forEach(list -> list.sort(
				Comparator.comparing(pass -> ProgramNames.computeLetter(pass.name))));

		return new PackCompute(passes, chained, storageTargets);
	}

	/** The colour targets a compute names as an image, read off its translated text. */
	private static Set<Integer> colourImagesOf(PackProgram.Compute compute) {
		Set<Integer> indices = new LinkedHashSet<>();
		TranslatedUnit unit = compute.loaded().program().stages().get(ProgramStage.COMPUTE);
		if (unit == null) {
			return indices;
		}

		Matcher matcher = COLOUR_IMAGE.matcher(unit.text());
		while (matcher.find()) {
			indices.add(Integer.parseInt(matcher.group(1)));
		}

		return indices;
	}

	/**
	 * Dispatches the computes hanging off that full screen pass, right before it, as Iris does.
	 * Nothing happens for a pass with none, which is every pass of most packs.
	 *
	 * @param step    the halves that pass reads and writes, which is where its computes read a
	 *                colour target from and write one to: Iris binds both the sampler and the
	 *                image on the flipped state of that moment
	 *                ({@code IrisImages.addRenderTargetImages})
	 * @param depth   what that pass reads as {@code depthtex0}, already in the pack's own window,
	 *                and so what its computes read under the same name: Iris hands a compute the
	 *                three depth names of the pass it hangs off through the same
	 *                {@code IrisSamplers.addCompositeSamplers} call
	 *                ({@code pipeline/CompositeRenderer.java:458} against {@code :402}), and the
	 *                far terrain's through the same {@code addRenderTargetSamplers}
	 *                ({@code :450} against {@code :394})
	 * @param distant what that pass reads as {@code dhDepthTex0}, on the same split, or null for
	 *                the far plane on the frames the pack drew no far terrain
	 */
	void dispatchBefore(String program, CommandEncoder encoder, GpuDevice device, PackValues values,
			ColorTargets targets, TargetSchedule.Bound step, GpuTextureView depth,
			GpuTextureView distant, int width, int height) {
		List<Pass> attached = this.chained.get(program);
		if (attached == null || attached.isEmpty()) {
			return;
		}

		// The hold first, as the head-of-frame dispatch does: the first pass of a chain half can
		// still have the geometry's render pass object open over it, and ending the pass
		// underneath would leave that object to close a pass the encoder no longer has.
		GeometryHold.flush(() -> "the compute dispatch before " + program);
		GpuRecording.endPass(encoder);
		VkCommandBuffer commands = commands(encoder);
		VulkanDevice vulkan = vulkan(device);
		if (commands == null || vulkan == null) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			beforeChainCompute(commands, stack);
		}

		for (Pass pass : attached) {
			try {
				pass.dispatch(vulkan, commands, values, targets, width, height, step, depth, distant);
			} catch (RuntimeException e) {
				if (pass.failed.add(e.toString())) {
					Vitrail.logger().warn("compute {} failed: {}", pass.path, e.toString());
				}
			}
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			afterChainCompute(commands, stack);
		}

		if (this.announcedChains.add(program)) {
			Vitrail.logger().info("Dispatched {} compute pass(es) before {}", attached.size(), program);
		}
	}

	void dispatch(PackValues values, ColorTargets targets) {
		if (this.passes.isEmpty()) {
			return;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}

		CommandEncoder encoder = device.createCommandEncoder();
		// The hold first, and through its own door: it keeps a RenderPass OBJECT open across
		// draws, so ending the pass underneath it would leave that object to close a pass the
		// encoder no longer has, which is a crash at the next flush and not here.
		GeometryHold.flush(() -> "the shadow compute dispatch");
		GpuRecording.endPass(encoder);
		VkCommandBuffer commands = commands(encoder);
		VulkanDevice vulkan = vulkan(device);
		if (commands == null || vulkan == null) {
			return;
		}

		values.convention(ClipSpace.FORWARD);
		values.modelView(null, null);
		values.projection(null);
		values.passColour(null);
		values.renderStage(RenderStage.NONE);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			afterShadowGeometry(commands, stack);
		}

		// Asked of the game and not of ColorTargets, which is the same number one frame late or
		// nought on the first. ColorTargets.screenWidth is only written by its ensure(), which
		// runs inside the world render, while this dispatch is placed at the head of the frame
		// before any of it: on the first frame after a pack loads the fields are still nought,
		// which is a dispatch of no groups at all, and every resize afterwards would size a frame
		// by the window it had before. Iris asks the same object at the same moment,
		// ShadowCompositeRenderer.java:211-212.
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();
		int width = main == null ? 0 : main.width;
		int height = main == null ? 0 : main.height;
		for (Pass pass : this.passes) {
			try {
				pass.dispatch(vulkan, commands, values, targets, width, height, null, null, null);
			} catch (RuntimeException e) {
				if (pass.failed.add(e.toString())) {
					Vitrail.logger().warn("shadow compute {} failed: {}", pass.path, e.toString());
				}
			}
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			afterCompute(commands, stack);
		}

		if (!this.announced) {
			this.announced = true;
			Vitrail.logger().info("Dispatched {} shadow compute pass(es) at the head of the frame",
					this.passes.size());
		}
	}

	@Override
	public void close() {
		this.passes.forEach(Pass::close);
	}

	private static VkCommandBuffer commands(CommandEncoder encoder) {
		return ((CommandEncoderAccessor) encoder).vitrail$backend() instanceof VulkanCommandEncoder vulkan
				? ((VulkanCommandEncoderAccessor) vulkan).vitrail$commandBuffer()
				: null;
	}

	private static VulkanDevice vulkan(GpuDevice device) {
		GpuDeviceBackend backend = ((GpuDeviceAccessor) device).vitrail$backend();
		return backend instanceof VulkanDevice found ? found : null;
	}

	/**
	 * What sizes this program's dispatch, in the pack's own terms. Only the count a pack writes
	 * out for itself is a number before the frame runs: the two roads that go by the screen are
	 * settled at the size the frame is drawn at, so what is named here is the road and the tile of
	 * pixels one group of it covers.
	 */
	private static String sizing(PackProgram.Compute compute) {
		if (compute.fixed()) {
			return compute.groupsX() + "x" + compute.groupsY() + "x" + compute.groupsZ() + " groups";
		}

		String covered = compute.relative()
				? compute.renderX() + " by " + compute.renderY() + " of the screen"
				: "the whole screen";
		return covered + ", " + compute.localX() + "x" + compute.localY() + " pixels to a group";
	}

	/**
	 * The views the engine serves a pack program under these names, for a compute that samples
	 * them. White where a map is not there, for the same reason the passes answer white: it is
	 * the far plane in the pack's window, so a lookup that finds nothing reads "nothing between
	 * here and the light" rather than a world in its own shadow.
	 */
	private static GpuTextureView engineView(ColorTargets targets, String name) {
		ShadowTargets shadow = targets.shadow();
		return switch (name) {
			case "noisetex" -> targets.noise();
			// The HW spelling reads the same image as its plain twin, which is the whole of what
			// SEPARATE_HARDWARE_SAMPLERS asks for. A compute makes every comparison in arithmetic
			// whatever the pack declared, so nothing but the name reaches this line.
			case "shadowtex0", "shadowtex0HW" ->
					orWhite(targets, shadow == null ? null : shadow.depth());
			case "shadowtex1", "shadowtex1HW" ->
					orWhite(targets, shadow == null ? null : shadow.depthWithoutTranslucents());
			// Every name SamplerPlan reads as a shadow colour, the bare shadowcolor with them, and
			// the white stand-in for one no program of the place named. That is the rule a full
			// screen pass follows for the same names (PackPass.java:585), and the ceiling is not
			// consulted here because it does not have to be: a buffer the pack may not reach was
			// never allocated, so colour() answers null for it and white is what the compute reads,
			// exactly as it does for a buffer nothing has written.
			//
			// White covers one more case than it should, and that is a divergence rather than a
			// rule: what gets allocated is read off the FRAGMENT stages of the place alone
			// (TargetPlan.build walks fragmentsOf), so a shadowcolor that only a COMPUTE of the
			// place names is never opened, and this hands the compute white for a buffer the pack
			// does mean to read. Iris opens it from the compute itself, addShadowSamplers calling
			// createIfEmpty for each shadowcolor the program declares, on the compute path as on
			// the composite one (CompositeRenderer.java:461, samplers/IrisSamplers.java:156-163).
			// Closing it means expanding every compute of the pack while the plan is built, which
			// is a second reading of the archive, and nothing of the corpus asks for it: every
			// shadowcolor a compute of it names is named by a fragment stage of the same place too.
			default -> SamplerPlan.isShadowColour(name)
					? orWhite(targets, shadow == null ? null : shadow.colour(SamplerPlan.shadowColour(name)))
					: null;
		};
	}

	private static GpuTextureView orWhite(ColorTargets targets, GpuTextureView view) {
		return view == null ? targets.white() : view;
	}

	/**
	 * The same sampler state the graphics passes bind for the name. The noise field repeats and
	 * is filtered, Iris's choice: a pack indexes it in texels well past one, and clamped it reads
	 * the same edge row for the whole volume. The shadow set is filtered the way Iris filters its
	 * shadow samplers. A volume the pack declared follows its format, through the one place that
	 * answers that for every road, so a compute reads it exactly as the composite after it will.
	 */
	private static long samplerFor(String name) {
		boolean noise = "noisetex".equals(name);
		boolean shadow = name.startsWith("shadowtex") || name.startsWith("shadowcolor");
		// Every other name keeps the answer this line gave before there was one place to ask:
		// customImageFilter is NEAREST for a name no image directive declared.
		FilterMode filter = noise || shadow
				? FilterMode.LINEAR
				: PackPass.customImageFilter(name);

		return ((VulkanGpuSampler) PackPass.sampler(noise, filter, false)).vkSampler();
	}

	/**
	 * Makes the shadow fragment {@code imageStore} into voxel volumes visible to
	 * {@code shadowcomp}'s {@code texelFetch} of the same images. The game's barrier is
	 * compute-to-compute storage only, which does not cover a sampled read of a volume the
	 * geometry just wrote.
	 */
	private static void afterShadowGeometry(VkCommandBuffer commands, MemoryStack stack) {
		shaderBarrier(commands, stack,
				VK13.VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT
						| VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
				VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
						| VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT,
				VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
				VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
						| VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
						| VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
	}

	/**
	 * Makes the floodfill {@code imageStore} visible to the next frame's gbuffers, which sample
	 * those volumes as {@code sampler3D}.
	 */
	private static void afterCompute(VkCommandBuffer commands, MemoryStack stack) {
		shaderBarrier(commands, stack, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
				VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
				VK13.VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT
						| VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
				VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
						| VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
						| VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
	}

	/**
	 * Makes what the passes before this point drew, colour writes and storage writes alike, visible
	 * to a compute that samples or stores it. The render pass has already closed on the game's own
	 * barrier, which orders graphics against graphics; this names the compute stage as the reader.
	 */
	private static void beforeChainCompute(VkCommandBuffer commands, MemoryStack stack) {
		shaderBarrier(commands, stack, VK13.VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT,
				VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
						| VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
				VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
				VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
						| VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
						| VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
	}

	/**
	 * Makes a compute's image stores visible to the pass that follows, which samples them, and to
	 * the attachment writes a later pass makes to the same target. Iris's memory barrier after its
	 * loop, {@code CompositeRenderer.java:297}: image access, texture fetch and shader storage.
	 */
	private static void afterChainCompute(VkCommandBuffer commands, MemoryStack stack) {
		shaderBarrier(commands, stack, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
				VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
				VK13.VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT
						| VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
				VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
						| VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT
						| VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
						| VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
	}

	private static void shaderBarrier(VkCommandBuffer commands, MemoryStack stack, long srcStage,
			long srcAccess, long dstStage, long dstAccess) {
		VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default();
		barrier.srcStageMask(srcStage);
		barrier.srcAccessMask(srcAccess);
		barrier.dstStageMask(dstStage);
		barrier.dstAccessMask(dstAccess);
		VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default();
		dependency.pMemoryBarriers(barrier);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(commands, dependency);
	}

	private static final class Pass {

		private final PackProgram.Compute compute;
		private final PackUniforms uniforms;
		private final String path;
		private final String name;

		/** The full screen pass this compute hangs off, or null for a shadow compute. */
		private final String program;

		/**
		 * Which stage a {@code texture.<stage>.<sampler>} line has to name for this compute to read
		 * the file behind it. A shadow compute is the {@code shadowcomp} stage by construction: it
		 * hangs off no pass, so there is nothing else it could be.
		 */
		private final TextureStage textureStage;

		private final String label;
		private final Set<String> failed = new LinkedHashSet<>();
		private MappableRingBuffer block;
		private long shaderModule;
		private long setLayout;
		private long pipelineLayout;
		private long pipeline;
		private List<VulkanBindGroupLayout.Entry> entries = List.of();
		private boolean compiled;

		private Pass(PackProgram.Compute compute, UniformCatalog catalog, int load, String path,
				String program) {
			this.compute = compute;
			this.path = path;
			this.name = path.substring(path.lastIndexOf('/') + 1);
			this.program = program;
			this.textureStage = program == null
					? TextureStage.SHADOWCOMP
					: TextureStage.of(program).orElse(null);
			this.label = "pack/" + load + "/" + path + "/compute";
			this.uniforms = new PackUniforms(compute.loaded().program().uniforms(), catalog);
		}

		/**
		 * @param step    the halves of the pass this compute hangs off, or null for a shadow
		 *                compute, which reads no colour target
		 * @param depth   the depth the pass this compute hangs off reads, or null for a shadow
		 *                compute, which is dispatched before the frame has one: {@code depthtex0}
		 *                then reads the far plane, and the two copies read what they last held,
		 *                as they do for a pass drawn before the copy is taken
		 * @param distant the far terrain's depth on the same terms
		 */
		private void dispatch(VulkanDevice vulkan, VkCommandBuffer commands, PackValues values,
				ColorTargets targets, int width, int height, TargetSchedule.Bound step,
				GpuTextureView depth, GpuTextureView distant) {
			if (!this.compiled) {
				compile(vulkan);
			}

			if (this.pipeline == 0L) {
				return;
			}

			writeBlock(values);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VulkanCommandEncoder.memoryBarrier(commands, stack);
				VK12.vkCmdBindPipeline(commands, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
				pushDescriptors(commands, stack, targets, step, depth, distant);
				// The screen and not the shadow map: Iris sizes a shadow composite's compute off
				// the main render target, ShadowCompositeRenderer.java:212, and the resolution of
				// the shadow map is what it sizes the shadow GEOMETRY computes off instead,
				// IrisRenderingPipeline.java:916.
				int[] groups = this.compute.groupsAt(width, height);
				VK12.vkCmdDispatch(commands, groups[0], groups[1], groups[2]);
			}

			if (this.block != null) {
				this.block.rotate();
			}
		}

		private void compile(VulkanDevice vulkan) {
			this.compiled = true;
			TranslatedUnit unit = this.compute.loaded().program().stages().get(ProgramStage.COMPUTE);
			if (unit == null) {
				return;
			}

			// Clocked as module work like everything the game's compiler makes: shaderc first,
			// then the reflection inside createFromSpirv. Neither goes through the game's
			// compiler, so the funnel clock cannot see them and this road counts itself. A cache
			// hit skips both and still clocks the file read, the same way GlslCompilerMixin
			// clocks a served graphics unit. The layout and the pipeline below stay outside the
			// figure: they are Vulkan object creation, not module work. One outer finally so that
			// every exit, the refusal and the throw included, is counted exactly once.
			//
			// Iris has no disk store for this. ProgramBuilder.beginCompute
			// (ProgramBuilder.java:71-84) then GlShader (GlShader.java:24-49) calls
			// glCompileShader on the render thread, and the binary cache is the OpenGL driver's
			// (Iris.java:133-136 asks that driver for ten parallel compile threads). This engine's
			// compute never entered GlslCompiler.createIntermediary, so it never entered
			// ModuleCache either; the same store now holds it, same file layout, a stage token
			// that names our shaderc options so a graphics COMPUTE through the game's compiler
			// cannot serve this blob.
			long began = System.nanoTime();
			IntermediaryShaderModule module = null;
			try {
				String source = unit.text();
				String key = ModuleCache.keyOf(source, MODULE_CACHE_STAGE);
				module = ModuleCache.lookup(key, this.label);
				ByteBuffer spirv = null;
				if (module == null) {
					spirv = compileSpirv(source);
					if (spirv == null) {
						ModuleCache.building();
						return;
					}

					ModuleCache.building();
				}

				try {
					if (module == null) {
						module = IntermediaryShaderModule.createFromSpirv(this.label, spirv);
						ModuleCache.store(key, module);
					}

					ComputeShader.Compiled compiled = ComputeShader.compile(vulkan, module);
					this.shaderModule = compiled.module();
					this.entries = compiled.entries();
					// Named and still dispatched, which is what the graphics path does with the
					// same ceiling: a device is only obliged to allow this many descriptors in one
					// pushed set, and going past it is undefined rather than slow. Said here, once,
					// so that a driver error later has a line in the log that predicted it.
					if (this.entries.size() > PUSH_DESCRIPTORS) {
						Vitrail.logger().warn("compute {} pushes {} descriptors in one set, "
								+ "past the {} a device commonly allows at once", this.path,
								this.entries.size(), PUSH_DESCRIPTORS);
					}
				} catch (Exception e) {
					Vitrail.logger().warn("compute {} SPIR-V failed: {}", this.path,
							e.toString());
					return;
				}
			} finally {
				if (module != null) {
					module.close();
				}

				LoadClock.module(System.nanoTime() - began);
			}

			try (MemoryStack stack = MemoryStack.stackPush()) {
				createLayout(vulkan, stack);
				createPipeline(vulkan, stack);
			} catch (RuntimeException e) {
				destroy(vulkan);
				Vitrail.logger().warn("compute {} pipeline failed: {}", this.path, e.toString());
			}

			if (this.pipeline != 0L) {
				Vitrail.logger().info("Compiled compute {} ({})", this.path,
						sizing(this.compute));
			}
		}

		private static ByteBuffer compileSpirv(String source) {
			long compiler = Shaderc.shaderc_compiler_initialize();
			long options = Shaderc.shaderc_compile_options_initialize();
			ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, false);
			ByteBuffer filename = MemoryUtil.memUTF8("shadowcomp.csh");
			ByteBuffer entry = MemoryUtil.memUTF8("main");
			long result = 0L;
			try {
				Shaderc.shaderc_compile_options_set_target_env(options, 0, SHADERC_VULKAN_1_2);
				Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
				Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
				Shaderc.shaderc_compile_options_set_generate_debug_info(options);
				// Performance, and for the LAYOUT before speed: the pack's common include
				// declares samplers a compute never reads, an unoptimised module keeps them,
				// and reflection then demands a binding for every one. Optimised, the dead
				// declarations fall out and the entries are the names the shader touches.
				Shaderc.shaderc_compile_options_set_optimization_level(options, 2);
				result = Shaderc.shaderc_compile_into_spv(compiler, sourceBuffer, SHADERC_COMPUTE,
						filename, entry, options);
				int status = Shaderc.shaderc_result_get_compilation_status(result);
				if (status != 0) {
					Vitrail.logger().warn("compute shaderc: {}",
							Shaderc.shaderc_result_get_error_message(result));
					return null;
				}

				ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memCalloc(spirv.remaining());
				MemoryUtil.memCopy(spirv, copy);
				return copy;
			} finally {
				if (result != 0L) {
					Shaderc.shaderc_result_release(result);
				}

				MemoryUtil.memFree(entry);
				MemoryUtil.memFree(filename);
				MemoryUtil.memFree(sourceBuffer);
				Shaderc.shaderc_compile_options_release(options);
				Shaderc.shaderc_compiler_release(compiler);
			}
		}

		private void createLayout(VulkanDevice vulkan, MemoryStack stack) {
			int count = Math.max(1, this.entries.size());
			VkDescriptorSetLayoutBinding.Buffer bindings =
					VkDescriptorSetLayoutBinding.calloc(this.entries.isEmpty() ? 0 : count, stack);
			for (int i = 0; i < this.entries.size(); i++) {
				VulkanBindGroupLayout.Entry entry = this.entries.get(i);
				boolean storage = CustomImages.storage(entry.name())
						|| StorageImages.storageBinding(entry.name())
						|| COLOUR_IMAGE.matcher(entry.name()).matches();
				int type;
				if (entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER) {
					type = StorageBuffers.named(entry.name())
							? VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
							: VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
				} else {
					type = storage
							? VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
							: VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
				}
				bindings.get(i).binding(i).descriptorType(type).descriptorCount(1)
						.stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
			}

			VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType$Default()
					.flags(1)
					.pBindings(bindings);
			LongBuffer layoutPtr = stack.callocLong(1);
			VulkanUtils.crashIfFailure(vulkan,
					VK12.vkCreateDescriptorSetLayout(vulkan.vkDevice(), layoutInfo, null, layoutPtr),
					"compute set layout");
			this.setLayout = layoutPtr.get(0);
			LongBuffer setLayouts = stack.callocLong(1).put(0, this.setLayout);
			VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
					.sType$Default()
					.pSetLayouts(setLayouts);
			LongBuffer pipelineLayoutPtr = stack.callocLong(1);
			VulkanUtils.crashIfFailure(vulkan,
					VK12.vkCreatePipelineLayout(vulkan.vkDevice(), pipelineLayoutInfo, null,
							pipelineLayoutPtr),
					"compute pipeline layout");
			this.pipelineLayout = pipelineLayoutPtr.get(0);
		}

		private void createPipeline(VulkanDevice vulkan, MemoryStack stack) {
			VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
					.sType$Default();
			stage.stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
			stage.module(this.shaderModule);
			stage.pName(stack.UTF8("main"));
			VkComputePipelineCreateInfo pipelineInfo = VkComputePipelineCreateInfo.calloc(stack)
					.sType$Default();
			pipelineInfo.stage(stage);
			pipelineInfo.layout(this.pipelineLayout);
			VkComputePipelineCreateInfo.Buffer infos = VkComputePipelineCreateInfo.calloc(1, stack);
			infos.put(0, pipelineInfo);
			LongBuffer pipelinePtr = stack.callocLong(1);
			VulkanUtils.crashIfFailure(vulkan,
					VK12.vkCreateComputePipelines(vulkan.vkDevice(), 0L, infos, null, pipelinePtr),
					"compute pipeline");
			this.pipeline = pipelinePtr.get(0);
		}

		private void writeBlock(PackValues values) {
			int bytes = Math.max(16, this.uniforms.size());
			if (this.block == null) {
				this.block = new MappableRingBuffer(() -> "vitrail shadowcomp " + this.path,
						GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, bytes);
			}

			try (GpuBufferSlice.MappedView view = this.block.currentBuffer().map(false, true)) {
				ByteBuffer data = view.data();
				data.position(0);
				this.uniforms.write(Std140Builder.intoBuffer(data), values.world());
			}
		}

		private void pushDescriptors(VkCommandBuffer commands, MemoryStack stack,
				ColorTargets targets, TargetSchedule.Bound step, GpuTextureView depth,
				GpuTextureView distant) {
			if (this.entries.isEmpty()) {
				return;
			}

			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(this.entries.size(), stack);
			for (int i = 0; i < this.entries.size(); i++) {
				VulkanBindGroupLayout.Entry entry = this.entries.get(i);
				VkWriteDescriptorSet write = writes.get(i).sType$Default();
				write.dstBinding(i);
				write.dstArrayElement(0);
				write.descriptorCount(1);
				if (entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER) {
					if (StorageBuffers.named(entry.name())) {
						StorageBuffers.Bound bound = StorageBuffers.bound(entry.name());
						if (bound == null) {
							throw new IllegalStateException("Missing storage buffer " + entry.name());
						}

						VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
						bufferInfo.buffer(bound.buffer());
						bufferInfo.offset(0L);
						bufferInfo.range(bound.range());
						write.descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
						write.pBufferInfo(bufferInfo);
						continue;
					}

					int bytes = Math.max(16, this.uniforms.size());
					GpuBufferSlice slice = this.block.currentBuffer().slice(0, bytes);
					VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
					bufferInfo.buffer(((VulkanGpuBuffer) slice.buffer()).vkBuffer());
					bufferInfo.offset(slice.offset());
					bufferInfo.range(slice.length());
					write.descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
					write.pBufferInfo(bufferInfo);
					continue;
				}

				StorageImages.Bound bound = StorageImages.bound(entry.name());
				VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);

				// A texture the pack ships answers here exactly as it answers a full screen pass:
				// which image, how it is filtered and how it is addressed outside zero to one are
				// all the pack's to say, in one directive and the .mcmeta beside the file. Asked
				// BEFORE the colour targets for the reason PackPass asks it first as well: a pack
				// may lay a lookup table over the name of a real target for one stage, and reading
				// the name first would hand the compute a copy of the scene as a lookup table.
				ColorTargets.PackBinding supplied = bound == null
						? targets.packTexture(this.textureStage, entry.name())
						: null;
				if (supplied != null && supplied.view() instanceof VulkanGpuTextureView served) {
					imageInfo.sampler(((VulkanGpuSampler) PackPass.sampler(supplied.repeat(),
							supplied.filter(), false)).vkSampler());
					imageInfo.imageView(served.vkImageView());
					imageInfo.imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
					write.descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
					write.pImageInfo(imageInfo);
					continue;
				}

				if (bound == null && step != null && colourTarget(write, imageInfo, entry.name(),
						targets, step, this.program)) {
					continue;
				}

				if (bound == null) {
					// The depth of the pass this compute hangs off, under the three names, the far
					// terrain's and the smoothed centre depth, answered by the methods that answer
					// the pass itself: Iris gives a compute the depth samplers of its pass
					// (CompositeRenderer.java:458 and :461), and Reverie's cloud compute reads
					// depthtex1 to know where the sky ends. Ahead of the engine set below because
					// that set carries no depth, and a name it did not carry threw on every
					// dispatch, so the compute never ran and said so once.
					GpuTextureView read = switch (SamplerPlan.classify(entry.name())) {
						case DEPTH -> PackPass.depth(entry.name(), targets, depth);
						case DISTANT_DEPTH -> PackPass.distant(entry.name(), targets, distant);
						case CENTER_DEPTH -> PackPass.centerDepth(targets);
						default -> null;
					};
					if (read instanceof VulkanGpuTextureView served) {
						imageInfo.sampler(samplerFor(entry.name()));
						imageInfo.imageView(served.vkImageView());
						imageInfo.imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
						write.descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
						write.pImageInfo(imageInfo);
						continue;
					}

					// The samplers the engine serves every pack program, noisetex and the shadow
					// set among them: shadowcomp reads them the way a composite does, and only a
					// name neither the pack's images nor this set carries is a real miss.
					if (engineView(targets, entry.name()) instanceof VulkanGpuTextureView served) {
						imageInfo.sampler(samplerFor(entry.name()));
						imageInfo.imageView(served.vkImageView());
						imageInfo.imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
						write.descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
						write.pImageInfo(imageInfo);
						continue;
					}

					throw new IllegalStateException("Missing storage image " + entry.name());
				}

				boolean storage = bound.storage();
				imageInfo.sampler(storage ? 0L : samplerFor(entry.name()));
				imageInfo.imageView(bound.view());
				imageInfo.imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
				write.descriptorType(storage
						? VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
						: VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
				write.pImageInfo(imageInfo);
			}

			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commands,
					VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, writes);
		}

		/**
		 * A colour target, read as {@code colortexN} or written as {@code colorimgN}, on the half
		 * the pass this compute hangs off reads: Iris binds both off the same flipped state
		 * ({@code IrisSamplers.addRenderTargetSamplers}, {@code IrisImages.addRenderTargetImages}),
		 * so a compute stores into the very half the pass after it will sample.
		 *
		 * @param program the pass this compute hangs off, whose lod reads say whether the target
		 *                is sampled through its chain
		 * @return whether the name was one, and the write filled
		 */
		private static boolean colourTarget(VkWriteDescriptorSet write,
				VkDescriptorImageInfo.Buffer imageInfo, String name, ColorTargets targets,
				TargetSchedule.Bound step, String program) {
			Matcher image = COLOUR_IMAGE.matcher(name);
			if (image.matches()) {
				int index = Integer.parseInt(image.group(1));
				TargetSurface surface = targets.surface(index, step.read(index));
				if (surface == null || !surface.storage()
						|| !(surface.storageView() instanceof VulkanGpuTextureView view)) {
					throw new IllegalStateException(name + " is not a target created writable from "
							+ "a compute");
				}

				imageInfo.sampler(0L);
				imageInfo.imageView(view.vkImageView());
				imageInfo.imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
				write.descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
				write.pImageInfo(imageInfo);
				return true;
			}

			OptionalInt index = TargetName.index(name);
			if (index.isEmpty()) {
				return false;
			}

			TargetSurface surface = targets.surface(index.getAsInt(), step.read(index.getAsInt()));
			if (surface == null || !(surface.view() instanceof VulkanGpuTextureView view)) {
				throw new IllegalStateException(name + " is not an allocated colour target");
			}

			// Clamped, filtered and mipmapped the way the pass this hangs off binds the same
			// target: the pack's own indexing stays inside the image, an integer format takes no
			// filtering, and the chain is only in reach once something has written it and the
			// pass reads the target at a lod. Iris has one texture with one set of parameters
			// for both, so a compute and its pass sample it the same way.
			boolean mipmaps = surface.chainWritten()
					&& targets.lodReads(program).contains(index.getAsInt());
			imageInfo.sampler(((VulkanGpuSampler) PackPass.sampler(false,
					targets.filter(index.getAsInt()), mipmaps)).vkSampler());
			imageInfo.imageView(view.vkImageView());
			imageInfo.imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
			write.descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
			write.pImageInfo(imageInfo);
			return true;
		}

		private void close() {
			GpuDevice device = RenderSystem.tryGetDevice();
			VulkanDevice vulkan = device == null ? null : vulkan(device);
			if (vulkan != null) {
				destroy(vulkan);
			}

			if (this.block != null) {
				this.block.close();
				this.block = null;
			}
		}

		/** Deferred like every destruction of this engine's: two frames are still in flight. */
		private void destroy(VulkanDevice vulkan) {
			long pipeline = this.pipeline;
			long pipelineLayout = this.pipelineLayout;
			long setLayout = this.setLayout;
			long shaderModule = this.shaderModule;
			this.pipeline = 0L;
			this.pipelineLayout = 0L;
			this.setLayout = 0L;
			this.shaderModule = 0L;
			GpuRecording.destroyLater(() -> {
				if (pipeline != 0L) {
					VK12.vkDestroyPipeline(vulkan.vkDevice(), pipeline, null);
				}

				if (pipelineLayout != 0L) {
					VK12.vkDestroyPipelineLayout(vulkan.vkDevice(), pipelineLayout, null);
				}

				if (setLayout != 0L) {
					VK12.vkDestroyDescriptorSetLayout(vulkan.vkDevice(), setLayout, null);
				}

				if (shaderModule != 0L) {
					VK12.vkDestroyShaderModule(vulkan.vkDevice(), shaderModule, null);
				}
			});
		}
	}
}
