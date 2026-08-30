package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanCommandPool;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;

import java.nio.LongBuffer;

/**
 * Submits the pack's shadow compute on the game's dedicated compute queue, when there is one.
 * <p>
 * <strong>What Iris does.</strong> It runs {@code shadowcomp} on the GL context inside the shadow
 * stage ({@code ShadowRenderer.java:631-632}, the debug group and the
 * {@code compositeRenderer.renderAll()} under it), before the gbuffers of the same frame.
 * <p>
 * <strong>What prevents that here.</strong> This backend records at the head of the next frame,
 * which is that moment's translation under a deferred shadow stage, and the 26.2 device already
 * creates a compute-capable graphics queue plus a spare compute queue when the hardware has
 * another family ({@code VulkanDevice.java:116-125}). The Java facade has no compute, so the
 * dispatch cannot follow Iris onto a shared GL context; the spare {@code VkQueue} is the overlap
 * the backend actually offers.
 * <p>
 * <strong>What it costs the image.</strong> None, when the graphics submission waits on the
 * compute semaphore before the gbuffers sample those volumes as {@code sampler3D}. The wait is
 * the public {@code VulkanCommandEncoder.waitSemaphore} split, not a CPU spin.
 * <p>
 * One extra {@code vkQueueSubmit2KHR} per frame on the compute queue, counted by the same census
 * that issue 161 reads. None when this object is never built.
 */
final class ShadowComputeQueue implements AutoCloseable {

	private static final long GRAPHICS_AND_COMPUTE = VK13.VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT
			| VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

	private final VulkanDevice vulkan;
	private final VulkanQueue compute;
	private final VulkanCommandPool[] pools;
	private final long graphicsToCompute;
	private final long computeToGraphics;
	private long timeline;
	private int slot;

	private ShadowComputeQueue(VulkanDevice vulkan, VulkanQueue compute, VulkanCommandPool[] pools,
			long graphicsToCompute, long computeToGraphics) {
		this.vulkan = vulkan;
		this.compute = compute;
		this.pools = pools;
		this.graphicsToCompute = graphicsToCompute;
		this.computeToGraphics = computeToGraphics;
	}

	/**
	 * True when {@link VulkanDevice#computeQueue()} is a different {@code VkQueue} than the
	 * graphics one, so a submit there can overlap. The device never returns null: without a spare
	 * family it hands the graphics queue back, and that is a fall-back, not an overlap.
	 */
	static boolean dedicated(VulkanDevice vulkan) {
		VkQueue graphics = vulkan.graphicsQueue().vkQueue();
		VkQueue compute = vulkan.computeQueue().vkQueue();
		return graphics.address() != compute.address();
	}

	static ShadowComputeQueue create(VulkanDevice vulkan) {
		VulkanQueue compute = vulkan.computeQueue();
		VulkanCommandPool[] pools = new VulkanCommandPool[2];
		long graphicsToCompute = 0L;
		long computeToGraphics = 0L;
		try {
			pools[0] = new VulkanCommandPool(vulkan, compute);
			pools[1] = new VulkanCommandPool(vulkan, compute);
			graphicsToCompute = timelineSemaphore(vulkan);
			computeToGraphics = timelineSemaphore(vulkan);
		} catch (RuntimeException e) {
			destroyPools(pools);
			destroySemaphore(vulkan, graphicsToCompute);
			destroySemaphore(vulkan, computeToGraphics);
			throw e;
		}

		Vitrail.logger().info("Shadow compute overlaps the rest of the frame on the dedicated "
				+ "compute queue (family {}, extra vkQueueSubmit per frame); graphics waits before "
				+ "gbuffers sample the voxel volumes", compute.queueFamilyIndex());
		return new ShadowComputeQueue(vulkan, compute, pools, graphicsToCompute, computeToGraphics);
	}

	/**
	 * A command buffer from the pool two frames ago, begun for one submit. The game keeps two
	 * graphics submits in flight and waits for the older one before recycling that pool; this
	 * pair follows that depth, reset here at the head of the frame that reuses it.
	 */
	VkCommandBuffer begin() {
		VulkanCommandPool pool = this.pools[this.slot];
		pool.reset();
		VkCommandBuffer commands = pool.allocateBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
			begin.flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			VulkanUtils.crashIfFailure(this.vulkan,
					VK12.vkBeginCommandBuffer(commands, begin),
					"shadow compute command buffer begin");
		}

		return commands;
	}

	/**
	 * Ends the compute buffer, splits the graphics submission around it, and submits the compute
	 * queue. The graphics commands already recorded (the reanchor and the barrier that waits for
	 * the previous frame's shadow geometry) signal first; gbuffers recorded after this wait on
	 * compute. The compute submit itself is the extra {@code vkQueueSubmit2KHR}.
	 */
	void submit(VulkanCommandEncoder encoder, VkCommandBuffer compute) {
		VulkanUtils.crashIfFailure(this.vulkan, VK12.vkEndCommandBuffer(compute),
				"shadow compute command buffer end");
		this.timeline++;
		long value = this.timeline;
		encoder.signalSemaphore(this.graphicsToCompute, value, GRAPHICS_AND_COMPUTE);
		try (VulkanQueue.Submission submission = this.compute.beginSubmit()) {
			submission.waitSemaphore(this.graphicsToCompute, value,
					VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
			submission.executeCommands(compute);
			submission.signalSemaphore(this.computeToGraphics, value,
					VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
		}

		encoder.waitSemaphore(this.computeToGraphics, value, GRAPHICS_AND_COMPUTE);
		this.slot ^= 1;
	}

	@Override
	public void close() {
		VulkanDevice vulkan = this.vulkan;
		VulkanCommandPool[] pools = this.pools;
		long graphicsToCompute = this.graphicsToCompute;
		long computeToGraphics = this.computeToGraphics;
		GpuRecording.destroyLater(() -> {
			destroyPools(pools);
			destroySemaphore(vulkan, graphicsToCompute);
			destroySemaphore(vulkan, computeToGraphics);
		});
	}

	private static long timelineSemaphore(VulkanDevice vulkan) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default();
			type.semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE);
			type.initialValue(0L);
			VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
			info.pNext(type);
			LongBuffer handle = stack.callocLong(1);
			VulkanUtils.crashIfFailure(vulkan,
					VK12.vkCreateSemaphore(vulkan.vkDevice(), info, null, handle),
					"shadow compute timeline semaphore");
			return handle.get(0);
		}
	}

	private static void destroyPools(VulkanCommandPool[] pools) {
		for (VulkanCommandPool pool : pools) {
			if (pool != null) {
				pool.destroy();
			}
		}
	}

	private static void destroySemaphore(VulkanDevice vulkan, long semaphore) {
		if (semaphore != 0L) {
			VK12.vkDestroySemaphore(vulkan.vkDevice(), semaphore, null);
		}
	}
}
