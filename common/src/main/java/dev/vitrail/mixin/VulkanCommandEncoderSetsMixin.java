package dev.vitrail.mixin;

import dev.vitrail.render.DescriptorSetPools;
import dev.vitrail.render.WideSamplerSets;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Owns the descriptor pools {@link WideSamplerSets} allocates from and turns them over on the
 * encoder's own beat: reset with the command pool of the same submit slot, destroyed with the
 * encoder, which the device destroys before itself.
 * <p>
 * Made on the first set asked for, so a device that never creates such a layout, which is every
 * device but MoltenVK under a pack past its sampler slots, never makes a pool.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderSetsMixin implements WideSamplerSets.SetAllocator {

	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	private long currentSubmitIndex;

	@Unique
	private DescriptorSetPools vitrail$setPools;

	@Override
	public long vitrail$allocateSet(long setLayout, VkWriteDescriptorSet.Buffer writes) {
		if (this.vitrail$setPools == null) {
			this.vitrail$setPools = new DescriptorSetPools(this.device,
					VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT);
		}

		return this.vitrail$setPools.allocate(vitrail$slot(), setLayout, writes);
	}

	/**
	 * At the game's reset of the command pool of the slot about to record, which it reaches only
	 * once the submission that last recorded in that slot is done, and after the index has moved on
	 * to that slot.
	 */
	@Inject(method = "submit", require = 1,
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandPool;reset()V"))
	private void vitrail$resetSets(CallbackInfo callback) {
		if (this.vitrail$setPools != null) {
			this.vitrail$setPools.reset(vitrail$slot());
		}
	}

	/** At the tail, past the wait for idle, and before the device destroys itself. */
	@Inject(method = "destroy", at = @At("TAIL"), require = 1)
	private void vitrail$destroySets(CallbackInfo callback) {
		if (this.vitrail$setPools != null) {
			this.vitrail$setPools.destroy();
			this.vitrail$setPools = null;
		}
	}

	/** The slot recording now, picked the way {@code currentCommandPool} picks its pool. */
	@Unique
	private int vitrail$slot() {
		return (int) (this.currentSubmitIndex % VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT);
	}
}
