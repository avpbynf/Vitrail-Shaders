package dev.vitrail.render;

import dev.vitrail.mixin.CommandEncoderAccessor;
import dev.vitrail.mixin.GpuDeviceAccessor;
import dev.vitrail.mixin.VulkanCommandEncoderAccessor;
import dev.vitrail.pack.texture.BufferObject;
import dev.vitrail.pack.texture.CustomStorage;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The shader storage buffers a pack declared with {@code bufferObject.N}, allocated on the Vulkan
 * device.
 * <p>
 * The Java buffer facade has no storage bit, so these go through VMA the way the custom images do:
 * a dummy {@code USAGE_UNIFORM} slice satisfies {@code setUniform}, and mixins swap the real
 * {@code VkBuffer} and {@code VK_DESCRIPTOR_TYPE_STORAGE_BUFFER} while pushing descriptors.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris ShaderStorageBuffer, LGPL-3.0</a>
 */
public final class StorageBuffers implements AutoCloseable {

	private static volatile StorageBuffers current = none();

	private final BufferObject.Reading declared;
	private final List<Allocated> allocated = new ArrayList<>();
	private GpuBuffer dummy;
	private int lastWidth;
	private int lastHeight;

	StorageBuffers(BufferObject.Reading declared) {
		this.declared = declared;
	}

	static StorageBuffers none() {
		return new StorageBuffers(BufferObject.Reading.empty());
	}

	/** Whether this name is a storage buffer this engine serves, allocated or about to be. */
	public static boolean named(String name) {
		return CustomStorage.named(name);
	}

	/**
	 * The VMA buffer currently allocated for this GLSL name. Mixins look it up while pushing
	 * descriptors.
	 */
	public static Bound bound(String name) {
		return current.lookup(name);
	}

	private Bound lookup(String name) {
		int index = CustomStorage.indexOf(name);
		if (index < 0) {
			return null;
		}

		for (Allocated buffer : this.allocated) {
			if (buffer.declared.index() == index) {
				return new Bound(buffer.buffer, buffer.bytes);
			}
		}

		return null;
	}

	/**
	 * One bound buffer. {@code range} is the whole allocation: Complementary indexes
	 * {@code blockDataSSBO.data[face]} across hundreds of megabytes, and a 16-byte dummy would OOB.
	 */
	public record Bound(long buffer, long range) {
	}

	void install() {
		current = this;
	}

	/**
	 * Allocates every absolute buffer once, and rebuilds the relative ones when the screen moves.
	 * A failure is thrown rather than skipped: a tiny stand-in would let the pack compile and then
	 * hang the GPU on the first out-of-range write.
	 */
	void ensure(int screenWidth, int screenHeight) {
		install();
		if (this.declared.buffers().isEmpty()) {
			return;
		}

		boolean first = this.allocated.isEmpty();
		boolean resized = screenWidth != this.lastWidth || screenHeight != this.lastHeight;
		if (!first && !resized) {
			return;
		}

		VulkanDevice vulkan = vulkan();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (vulkan == null || device == null) {
			return;
		}

		ensurePlaceholder(device);
		if (first) {
			for (BufferObject buffer : this.declared.buffers()) {
				if (buffer.relative()) {
					continue;
				}

				this.allocated.add(Allocated.create(vulkan, buffer, aligned(buffer.size())));
				Vitrail.logger().info("storage buffer {}", buffer.describe());
			}
		}

		if (first || resized) {
			List<Allocated> kept = new ArrayList<>();
			for (Allocated buffer : this.allocated) {
				if (buffer.relative) {
					buffer.destroy(vulkan);
				} else {
					kept.add(buffer);
				}
			}

			this.allocated.clear();
			this.allocated.addAll(kept);
			for (BufferObject buffer : this.declared.buffers()) {
				if (!buffer.relative()) {
					continue;
				}

				long bytes = aligned((long) (screenWidth * buffer.scaleX())
						* (long) (screenHeight * buffer.scaleY())
						* buffer.size());
				this.allocated.add(Allocated.create(vulkan, buffer, Math.max(bytes, 4L)));
				Vitrail.logger().info("storage buffer {} at {} bytes", buffer.describe(), bytes);
			}
		}

		this.lastWidth = screenWidth;
		this.lastHeight = screenHeight;
		zero(device);
	}

	private void ensurePlaceholder(GpuDevice device) {
		if (this.dummy == null) {
			this.dummy = device.createBuffer(() -> "vitrail ssbo placeholder",
					GpuBuffer.USAGE_UNIFORM, 16);
		}
	}

	/**
	 * Binds a dummy uniform slice for every storage-buffer name this program's layout carries.
	 * Mixins replace the {@code VkBuffer} and the range while the descriptors are pushed.
	 */
	public static void bind(RenderPass pass, List<String> names) {
		GpuBufferSlice slice = current.placeholder();
		if (slice == null) {
			return;
		}

		for (String name : names) {
			pass.setUniform(name, slice);
		}
	}

	private GpuBufferSlice placeholder() {
		return this.dummy == null ? null : this.dummy.slice(0, 16);
	}

	/**
	 * Zeros storage buffers small enough to fill on this command buffer. Complementary Ultra's
	 * {@code bufferObject.0} is hundreds of megabytes; {@code vkCmdFillBuffer} of that size on
	 * the frame that also draws the world trips Windows TDR ({@code VK_ERROR_DEVICE_LOST} two
	 * seconds later). The pack writes the cells it uses.
	 */
	private void zero(GpuDevice device) {
		CommandEncoder encoder = device.createCommandEncoder();
		GpuRecording.endPass(encoder);
		VkCommandBuffer commands = commands(encoder);
		if (commands == null) {
			return;
		}

		boolean filled = false;
		for (Allocated buffer : this.allocated) {
			if (buffer.zeroed) {
				continue;
			}

			if (buffer.bytes > 4L * 1024 * 1024) {
				buffer.zeroed = true;
				continue;
			}

			VK12.vkCmdFillBuffer(commands, buffer.buffer, 0L, buffer.bytes, 0);
			buffer.zeroed = true;
			filled = true;
		}

		if (filled) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				GpuRecording.afterTransfer(commands, stack);
			}
		}
	}

	private static long aligned(long size) {
		return Math.max(4L, (size + 3L) & ~3L);
	}

	private static VkCommandBuffer commands(CommandEncoder encoder) {
		return ((CommandEncoderAccessor) encoder).vitrail$backend() instanceof VulkanCommandEncoder vulkan
				? ((VulkanCommandEncoderAccessor) vulkan).vitrail$commandBuffer()
				: null;
	}

	@Override
	public void close() {
		if (current == this) {
			current = none();
		}

		VulkanDevice vulkan = vulkan();
		if (vulkan != null) {
			this.allocated.forEach(buffer -> buffer.destroy(vulkan));
		}

		this.allocated.clear();
		if (this.dummy != null) {
			this.dummy.close();
			this.dummy = null;
		}

		this.lastWidth = 0;
		this.lastHeight = 0;
	}

	private static VulkanDevice vulkan() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return null;
		}

		GpuDeviceBackend backend = ((GpuDeviceAccessor) device).vitrail$backend();
		return backend instanceof VulkanDevice vulkan ? vulkan : null;
	}

	private static final class Allocated {

		private final BufferObject declared;
		private final boolean relative;
		private final long bytes;
		private long buffer;
		private long allocation;
		private boolean zeroed;

		private Allocated(BufferObject declared, boolean relative, long bytes, long buffer,
				long allocation) {
			this.declared = declared;
			this.relative = relative;
			this.bytes = bytes;
			this.buffer = buffer;
			this.allocation = allocation;
		}

		private static Allocated create(VulkanDevice vulkan, BufferObject declared, long bytes) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default();
				bufferInfo.size(bytes);
				bufferInfo.usage(VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
						| VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
				bufferInfo.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
				VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack);
				allocationInfo.usage(8);
				LongBuffer bufferPtr = stack.callocLong(1);
				PointerBuffer allocationPtr = stack.callocPointer(1);
				VulkanUtils.crashIfFailure(vulkan,
						Vma.vmaCreateBuffer(vulkan.vma(), bufferInfo, allocationInfo, bufferPtr,
								allocationPtr, null),
						"storage buffer " + declared.index());
				return new Allocated(declared, declared.relative(), bytes, bufferPtr.get(0),
						allocationPtr.get(0));
			}
		}

		/**
		 * Deferred like the images: two frames may still hold descriptors naming this buffer, and
		 * freeing under them is the settings-change device loss.
		 */
		private void destroy(VulkanDevice vulkan) {
			long buffer = this.buffer;
			long allocation = this.allocation;
			this.buffer = 0L;
			this.allocation = 0L;
			if (buffer != 0L) {
				GpuRecording.destroyLater(() -> Vma.vmaDestroyBuffer(vulkan.vma(), buffer, allocation));
			}
		}
	}
}
