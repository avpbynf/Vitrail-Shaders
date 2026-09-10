package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.IntBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allocates and binds the descriptor set of a layout MoltenVK could not push, rather than pushing
 * it.
 * <p>
 * <strong>What goes wrong without it.</strong> The game creates every set layout with the push
 * descriptor flag ({@code VulkanBindGroupLayout:34}) and hands every draw its descriptors through
 * {@code vkCmdPushDescriptorSetKHR} ({@code VulkanRenderPass:412}). MoltenVK never gives a push
 * layout a Metal argument buffer, so each combined image sampler takes a {@code [[sampler(n)]]}
 * slot of its own, and Metal has sixteen. A stage reading more is refused as Metal builds it,
 * {@code 'sampler' attribute parameter is out of bounds: must be between 0 and 15}, which on
 * Reverie is {@code deferred2} and took the whole pack down on a Mac. MoltenVK goes on reporting
 * sixteen as {@code maxPerStageDescriptorSamplers}, and that report is the line drawn here.
 * <p>
 * <strong>Where a set without the flag is really given an argument buffer.</strong> Not everywhere:
 * MoltenVK binds every set slot by slot when its argument buffers are turned off
 * ({@code MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS=0}) or below macOS 11, and on a GPU without native
 * texture swizzle it does so for any layout holding a combined image sampler. What it reports
 * instead is {@code maxPerStageDescriptorUpdateAfterBindSamplers}, which it raises past sixteen
 * only while it binds sets through tier 2 argument buffers. Every tier 2 GPU is of Metal's Mac 2 or
 * Apple family, which MoltenVK gives native swizzle, so on a device reporting more there a layout
 * of samplers created without the flag does get its argument buffer. That second report is the
 * other line: a layout past it stays pushed, where Metal refuses it as before, rather than being
 * allocated for nothing.
 * <p>
 * <strong>Narrowed on purpose.</strong> Only a layout on MoltenVK whose widest stage lies between
 * those two reports loses the flag. Every other layout, and every layout on every other driver, is
 * pushed exactly as the game pushes it: an allocated set costs an update and a pool slot on every
 * bind, where a push is what the game was measured with.
 * <p>
 * <strong>Where the set lives</strong> is {@link DescriptorSetPools}, whose javadoc says why its
 * lifetime is the encoder's submit slot.
 * <p>
 * Off until {@code VulkanBackendMixin} says what the device is, which is what the harness gets.
 */
public final class WideSamplerSets {

	/**
	 * {@code VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_SET_BIT_KHR}, the one flag taken off and
	 * the one the game sets on every layout it creates. Written out, this LWJGL carrying no constant
	 * for it.
	 */
	private static final int PUSH = 1;

	/** The stages a pack's layout can name, each counted on its own as MoltenVK counts them. */
	private static final int[] STAGES = {
			VK10.VK_SHADER_STAGE_VERTEX_BIT,
			VK10.VK_SHADER_STAGE_GEOMETRY_BIT,
			VK10.VK_SHADER_STAGE_FRAGMENT_BIT,
			VK10.VK_SHADER_STAGE_COMPUTE_BIT,
	};

	/**
	 * The handles of the layouts created without the flag. By handle because that is all a draw has
	 * in hand, and the game's layout record is built only after the driver returned it.
	 */
	private static final Set<Long> ALLOCATED = ConcurrentHashMap.newKeySet();

	/** The layout names already said, so that a pack built again does not say them again. */
	private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

	private static volatile boolean moltenVk;

	private static volatile int perStageSamplers = Integer.MAX_VALUE;

	private static volatile int tableSamplers;

	/** Whether any layout ever lost the flag, so that a draw on any other machine asks nothing more. */
	private static volatile boolean any;

	private WideSamplerSets() {
	}

	/**
	 * Set once by {@code VulkanBackendMixin}, at device creation and before any layout is created.
	 *
	 * @param moltenVkDriver      whether the device's driver is MoltenVK
	 * @param maxPerStageSamplers what the device reports as {@code maxPerStageDescriptorSamplers}
	 * @param maxTableSamplers    what it reports as {@code maxPerStageDescriptorUpdateAfterBindSamplers}
	 */
	public static void serve(boolean moltenVkDriver, int maxPerStageSamplers, int maxTableSamplers) {
		perStageSamplers = maxPerStageSamplers;
		tableSamplers = maxTableSamplers;
		moltenVk = moltenVkDriver;
	}

	/**
	 * Takes the push flag off a layout about to be created when MoltenVK could not push it and would
	 * bind it through an argument buffer instead.
	 * <p>
	 * Read off the bindings as they go to the driver, so a storage image a rewrite already turned
	 * into one is not counted as a sampler, and the stage flags counted are the ones the layout
	 * really carries.
	 *
	 * @param info the create info, whose flags are rewritten in place
	 * @param name what the layout is for, said once in the log
	 * @return whether the flag was taken off, which is what {@link #created} is then handed
	 */
	public static boolean dropPush(VkDescriptorSetLayoutCreateInfo info, String name) {
		if (!moltenVk || (info.flags() & PUSH) == 0) {
			return false;
		}

		int widest = widestStage(info.pBindings());
		if (widest <= perStageSamplers) {
			return false;
		}

		if (widest > tableSamplers) {
			if (SAID.add(name)) {
				Vitrail.logger().warn("{} reads {} samplers in one stage, past the {} MoltenVK can push, "
						+ "and this device binds sets through no argument buffer taking more, so it stays "
						+ "pushed and Metal refuses it", name, widest, perStageSamplers);
			}

			return false;
		}

		info.flags(info.flags() & ~PUSH);
		if (SAID.add(name)) {
			Vitrail.logger().info("{} reads {} samplers in one stage, past the {} MoltenVK can push, so "
					+ "its descriptor set is allocated and bound through an argument buffer", name, widest,
					perStageSamplers);
		}

		return true;
	}

	/**
	 * Records which road the layout just created takes.
	 * <p>
	 * A pushed layout is taken OUT of the set rather than simply left out of it: a handle under
	 * MoltenVK is an address, and one freed with a pipeline can come back for the next layout
	 * created, which must not inherit the road of the one that held it before.
	 */
	public static void created(long handle, boolean allocated) {
		if (allocated) {
			ALLOCATED.add(handle);
			any = true;
		} else if (any) {
			ALLOCATED.remove(handle);
		}
	}

	/** Whether that layout was created without the push flag, and a draw must bind a set for it. */
	public static boolean allocated(long setLayout) {
		return any && ALLOCATED.contains(setLayout);
	}

	/**
	 * Allocates a set of that layout from the encoder's current slot, writes into it exactly the
	 * descriptors the push would have carried, and binds it where the push would have put them.
	 * <p>
	 * A failure to allocate throws out of here the way the game's own push throws on a missing
	 * sampler, a lost device as {@code GpuDeviceLossException}, so the draw or dispatch is not made
	 * and the catch around it decides, rather than the call going ahead with nothing bound.
	 *
	 * @param writes the writes the push was handed, every one aimed at the new set before the update
	 */
	public static void bind(VulkanCommandEncoder encoder, VkCommandBuffer commands, int bindPoint,
			long pipelineLayout, int set, long setLayout, VkWriteDescriptorSet.Buffer writes) {
		long allocated = ((SetAllocator) encoder).vitrail$allocateSet(setLayout, writes);
		for (int i = writes.position(); i < writes.limit(); i++) {
			writes.get(i).dstSet(allocated);
		}

		VK10.vkUpdateDescriptorSets(commands.getDevice(), writes, null);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VK10.vkCmdBindDescriptorSets(commands, bindPoint, pipelineLayout, set,
					stack.longs(allocated), (IntBuffer) null);
		}
	}

	/** The most combined image samplers any one stage of those bindings holds. */
	private static int widestStage(VkDescriptorSetLayoutBinding.Buffer bindings) {
		if (bindings == null) {
			return 0;
		}

		int widest = 0;
		for (int stage : STAGES) {
			int held = 0;
			for (int i = bindings.position(); i < bindings.limit(); i++) {
				VkDescriptorSetLayoutBinding binding = bindings.get(i);
				if (binding.descriptorType() == VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
						&& (binding.stageFlags() & stage) != 0) {
					held += binding.descriptorCount();
				}
			}

			widest = Math.max(widest, held);
		}

		return widest;
	}

	/**
	 * Implemented on the game's command encoder, which owns the submit slots a set's lifetime
	 * follows. Duck typed onto it for the same reason {@link MipmapCommands} is.
	 */
	public interface SetAllocator {

		/**
		 * A set of that layout, unwritten, from the pools of the slot recording now.
		 *
		 * @param writes the writes the set will be given, which size a pool created for it
		 */
		long vitrail$allocateSet(long setLayout, VkWriteDescriptorSet.Buffer writes);
	}
}
