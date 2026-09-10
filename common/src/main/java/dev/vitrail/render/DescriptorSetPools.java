package dev.vitrail.render;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The descriptor pools {@link WideSamplerSets} allocates from, one list of them per submit slot of
 * the game's command encoder.
 * <p>
 * <strong>Why the slot.</strong> A set named by a recorded command buffer has to stay valid until
 * the GPU is done with that buffer, and the game keeps two submissions in flight
 * ({@code VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT}). It already answers that for the command
 * buffers themselves: two command pools, the one recording picked by the submit index modulo two,
 * each reset in {@code submit} right after waiting on the submission that last recorded into it
 * ({@code VulkanCommandEncoder:205-210}). These pools follow the same slot and are reset at that
 * same call, so a set is never handed back while a buffer binding it can still run, and never kept
 * past the buffer either. The encoder's destruction queue turns on that beat too, which is what
 * frees a texel buffer view a write names at the moment the set naming it goes.
 * <p>
 * <strong>Why a list.</strong> How many sets a frame binds is not known up front: one per draw
 * whose descriptors changed, on every pipeline of such a layout. A pool that runs out is left for
 * the rest of its slot's turn and the next one is used, created when there is none, sized off the
 * set that did not fit. A reset keeps every pool, so once a pack has drawn a frame or two each slot
 * owns what a frame takes and creates nothing more.
 * <p>
 * Render thread only, which is where every draw and dispatch is recorded.
 */
public final class DescriptorSetPools {

	/** Sets one pool holds; each descriptor type gets this many times what the set that opened it needs. */
	private static final int SETS_PER_POOL = 64;

	/** Every core descriptor type is below this, the input attachment being the last at ten. */
	private static final int TYPES = VK10.VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT + 1;

	private final VulkanDevice device;

	private final List<List<Long>> slots = new ArrayList<>();

	/** Per slot, the pool the next allocation tries first; the ones before it ran out this turn. */
	private final int[] cursors;

	/**
	 * @param slots how many submit slots the encoder turns over, one list of pools each
	 */
	public DescriptorSetPools(VulkanDevice device, int slots) {
		this.device = device;
		this.cursors = new int[slots];
		for (int i = 0; i < slots; i++) {
			this.slots.add(new ArrayList<>());
		}
	}

	/**
	 * A set of that layout from the pools of the slot, unwritten.
	 *
	 * @param writes the writes the set is about to be given, which size a pool created for it
	 */
	public long allocate(int slot, long setLayout, VkWriteDescriptorSet.Buffer writes) {
		List<Long> pools = this.slots.get(slot);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer set = stack.callocLong(1);
			VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
					.sType$Default()
					.pSetLayouts(stack.longs(setLayout));
			while (true) {
				boolean fresh = this.cursors[slot] == pools.size();
				if (fresh) {
					pools.add(create(writes));
				}

				info.descriptorPool(pools.get(this.cursors[slot]));
				int result = VK10.vkAllocateDescriptorSets(this.device.vkDevice(), info, set);
				if (result == VK10.VK_SUCCESS) {
					return set.get(0);
				}

				// Only a full pool is moved past, and never one just sized off this very set: a pool
				// that refuses the set it was made for would otherwise be followed by another, forever.
				boolean full = result == VK11.VK_ERROR_OUT_OF_POOL_MEMORY
						|| result == VK10.VK_ERROR_FRAGMENTED_POOL;
				if (!full || fresh) {
					String message = "Can't allocate a descriptor set";
					VulkanUtils.crashIfFailure(this.device, result, message);
					throw new IllegalStateException(VulkanUtils.resultToString(result) + ": " + message);
				}

				this.cursors[slot]++;
			}
		}
	}

	/** Hands every set of the slot back, once the submission that last recorded in it is done. */
	public void reset(int slot) {
		for (long pool : this.slots.get(slot)) {
			VK10.vkResetDescriptorPool(this.device.vkDevice(), pool, 0);
		}

		this.cursors[slot] = 0;
	}

	/** Destroys every pool, once the device has gone idle and before it is destroyed. */
	public void destroy() {
		for (int slot = 0; slot < this.slots.size(); slot++) {
			for (long pool : this.slots.get(slot)) {
				VK10.vkDestroyDescriptorPool(this.device.vkDevice(), pool, null);
			}

			this.slots.get(slot).clear();
			this.cursors[slot] = 0;
		}
	}

	/** A pool for {@link #SETS_PER_POOL} sets carrying the descriptors those writes carry. */
	private long create(VkWriteDescriptorSet.Buffer writes) {
		int[] counts = new int[TYPES];
		int kinds = 0;
		for (int i = writes.position(); i < writes.limit(); i++) {
			VkWriteDescriptorSet write = writes.get(i);
			int type = write.descriptorType();
			if (type < 0 || type >= TYPES) {
				throw new IllegalStateException("No pool size for descriptor type " + type);
			}

			if (counts[type] == 0) {
				kinds++;
			}

			counts[type] += write.descriptorCount();
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(kinds, stack);
			int at = 0;
			for (int type = 0; type < TYPES; type++) {
				if (counts[type] > 0) {
					sizes.get(at).type(type).descriptorCount(counts[type] * SETS_PER_POOL);
					at++;
				}
			}

			VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
					.sType$Default()
					.maxSets(SETS_PER_POOL)
					.pPoolSizes(sizes);
			LongBuffer pool = stack.callocLong(1);
			VulkanUtils.crashIfFailure(this.device,
					VK10.vkCreateDescriptorPool(this.device.vkDevice(), info, null, pool),
					"Can't create a descriptor pool");

			return pool.get(0);
		}
	}
}
