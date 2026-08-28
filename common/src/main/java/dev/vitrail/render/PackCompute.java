package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.mixin.CommandEncoderAccessor;
import dev.vitrail.mixin.GpuDeviceAccessor;
import dev.vitrail.mixin.VulkanCommandEncoderAccessor;
import dev.vitrail.pack.program.ProgramNames;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The pack's shadow compute passes, dispatched at the head of the frame, in the parity of the
 * gbuffers that read what they propagate; the volumes they read are the previous frame's
 * shadow-geometry writes, one frame late like the shadow map itself.
 * <p>
 * Complementary's floodfill lives in {@code shadowcomp.csh}. The Java facade has no compute, so
 * the pipeline is built the way the storage probe was: shaderc kind 2, a VMA storage image,
 * push descriptors. Iris runs it inside its shadow render ({@code ShadowRenderer.java:631-632},
 * the debug group and the {@code compositeRenderer.renderAll()} under it), before its gbuffers in
 * the SAME frame. Under this engine's deferred shadow stage, the head of the frame is that
 * moment's translation.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris ComputeProgram, LGPL-3.0</a>
 */
final class PackCompute implements AutoCloseable {

	private static final int SHADERC_VULKAN_1_2 = 4202496;
	private static final int SHADERC_COMPUTE = 2;

	/**
	 * A common ceiling on a pushed descriptor set, the same one the graphics side carries in
	 * {@link PackPass}. Nothing in the game asks the device for its own, so a dispatch past this
	 * is named and still made: the failure, if it comes, is a driver error that the line makes
	 * readable.
	 */
	private static final int PUSH_DESCRIPTORS = 32;

	private final List<Pass> passes;
	private boolean announced;

	private PackCompute(List<Pass> passes) {
		this.passes = List.copyOf(passes);
	}

	static PackCompute none() {
		return new PackCompute(List.of());
	}

	/**
	 * Reads every shadow compute the pack ships, out of the opening the load already holds.
	 * <p>
	 * The opening is handed in and not taken here, and this loop is why it matters: read a program
	 * at a time from a pack path, each turn mounted the archive again and walked every source file
	 * of it to rebuild the same index of the same settings, so a pack with four shadow computes
	 * paid for four whole readings of itself to translate four files.
	 */
	static PackCompute load(OpenedPack pack, String place, List<String> computes, int load,
			UniformCatalog catalog) {
		List<Pass> passes = new ArrayList<>();
		for (String name : computes) {
			if (!ProgramNames.shadowComposite(ProgramNames.familyOf(name))) {
				continue;
			}

			String path = place.isEmpty() ? name : place + "/" + name;
			try {
				Optional<PackProgram.Compute> compute = PackProgram.loadCompute(pack, path);
				if (compute.isEmpty()) {
					continue;
				}

				passes.add(new Pass(compute.get(), catalog, load, path));
				Vitrail.logger().info("Loaded shadow compute {} ({}x{}x{} groups)", path,
						compute.get().groupsX(), compute.get().groupsY(), compute.get().groupsZ());
			} catch (IOException | RuntimeException e) {
				Vitrail.logger().warn("shadow compute {} could not be translated: {}", path,
						e.toString());
			}
		}

		return new PackCompute(passes);
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

		for (Pass pass : this.passes) {
			try {
				pass.dispatch(vulkan, commands, values, targets);
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
	 * The views the engine serves a pack program under these names, for a compute that samples
	 * them. White where a map is not there, for the same reason the passes answer white: it is
	 * the far plane in the pack's window, so a lookup that finds nothing reads "nothing between
	 * here and the light" rather than a world in its own shadow.
	 */
	private static GpuTextureView engineView(ColorTargets targets, String name) {
		ShadowTargets shadow = targets.shadow();
		return switch (name) {
			case "noisetex" -> targets.noise();
			case "shadowtex0" -> orWhite(targets, shadow == null ? null : shadow.depth());
			case "shadowtex1" ->
					orWhite(targets, shadow == null ? null : shadow.depthWithoutTranslucents());
			case "shadowcolor0" -> orWhite(targets, shadow == null ? null : shadow.colour(0));
			case "shadowcolor1" -> orWhite(targets, shadow == null ? null : shadow.colour(1));
			default -> null;
		};
	}

	private static GpuTextureView orWhite(ColorTargets targets, GpuTextureView view) {
		return view == null ? targets.white() : view;
	}

	/**
	 * The same sampler state the graphics passes bind for the name. The noise field repeats and
	 * is filtered, Iris's choice: a pack indexes it in texels well past one, and clamped it reads
	 * the same edge row for the whole volume. The shadow set is filtered the way Iris filters its
	 * shadow samplers. A pack's own image stays NEAREST and clamped, on the pack's own indexing.
	 */
	private static long samplerFor(String name) {
		boolean noise = "noisetex".equals(name);
		boolean shadow = name.startsWith("shadowtex") || name.startsWith("shadowcolor");
		return ((VulkanGpuSampler) PackPass.sampler(noise,
				noise || shadow ? FilterMode.LINEAR : FilterMode.NEAREST, false)).vkSampler();
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
		private final String label;
		private final Set<String> failed = new LinkedHashSet<>();
		private MappableRingBuffer block;
		private long shaderModule;
		private long setLayout;
		private long pipelineLayout;
		private long pipeline;
		private List<VulkanBindGroupLayout.Entry> entries = List.of();
		private boolean compiled;

		private Pass(PackProgram.Compute compute, UniformCatalog catalog, int load, String path) {
			this.compute = compute;
			this.path = path;
			this.label = "pack/" + load + "/" + path + "/compute";
			this.uniforms = new PackUniforms(compute.loaded().program().uniforms(), catalog);
		}

		private void dispatch(VulkanDevice vulkan, VkCommandBuffer commands, PackValues values,
				ColorTargets targets) {
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
				pushDescriptors(commands, stack, targets);
				VK12.vkCmdDispatch(commands, this.compute.groupsX(), this.compute.groupsY(),
						this.compute.groupsZ());
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

			ByteBuffer spirv = compileSpirv(unit.text());
			if (spirv == null) {
				return;
			}

			try {
				ComputeShader.Compiled compiled = ComputeShader.compile(vulkan, this.label, spirv);
				this.shaderModule = compiled.module();
				this.entries = compiled.entries();
				// Named and still dispatched, which is what the graphics path does with the same
				// ceiling: a device is only obliged to allow this many descriptors in one pushed
				// set, and going past it is undefined rather than slow. Said here, once, so that a
				// driver error later has a line in the log that predicted it.
				if (this.entries.size() > PUSH_DESCRIPTORS) {
					Vitrail.logger().warn("shadow compute {} pushes {} descriptors in one set, past "
							+ "the {} a device commonly allows at once", this.path,
							this.entries.size(), PUSH_DESCRIPTORS);
				}
			} catch (Exception e) {
				Vitrail.logger().warn("shadow compute {} SPIR-V failed: {}", this.path, e.toString());
				return;
			}

			try (MemoryStack stack = MemoryStack.stackPush()) {
				createLayout(vulkan, stack);
				createPipeline(vulkan, stack);
			} catch (RuntimeException e) {
				destroy(vulkan);
				Vitrail.logger().warn("shadow compute {} pipeline failed: {}", this.path, e.toString());
			}

			if (this.pipeline != 0L) {
				Vitrail.logger().info("Compiled shadow compute {} ({}x{}x{} groups)", this.path,
						this.compute.groupsX(), this.compute.groupsY(), this.compute.groupsZ());
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
					Vitrail.logger().warn("shadow compute shaderc: {}",
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
						|| StorageImages.storageBinding(entry.name());
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
					"shadow compute set layout");
			this.setLayout = layoutPtr.get(0);
			LongBuffer setLayouts = stack.callocLong(1).put(0, this.setLayout);
			VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
					.sType$Default()
					.pSetLayouts(setLayouts);
			LongBuffer pipelineLayoutPtr = stack.callocLong(1);
			VulkanUtils.crashIfFailure(vulkan,
					VK12.vkCreatePipelineLayout(vulkan.vkDevice(), pipelineLayoutInfo, null,
							pipelineLayoutPtr),
					"shadow compute pipeline layout");
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
					"shadow compute pipeline");
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
				ColorTargets targets) {
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
				if (bound == null) {
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
