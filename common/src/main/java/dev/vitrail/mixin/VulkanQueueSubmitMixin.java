package dev.vitrail.mixin;

import dev.vitrail.render.PassTimings;

import com.mojang.blaze3d.vulkan.VulkanQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts the calls to {@code vkQueueSubmit2KHR}, which is the number a capture tool reports.
 * <p>
 * Issue 161 is about that number and nothing else: a report measured about 150 a frame with a pack
 * drawn here against 45 to 50 without, and the cost of each one is driver work that MoltenVK pays
 * harder than a native driver. Counting it in the engine is what makes the number readable from
 * the log rather than from a capture on somebody else's machine, and it is the only honest way to
 * tell whether a change to the frame moved the thing the report is about.
 * <p>
 * On {@code close} and at the submit itself rather than at the head: the method returns without
 * submitting when the submission carries nothing, and a count that included those would drift away
 * from what a capture shows.
 */
@Mixin(VulkanQueue.Submission.class)
public abstract class VulkanQueueSubmitMixin {

	@Inject(method = "close",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR("
							+ "Lorg/lwjgl/vulkan/VkQueue;"
							+ "Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"),
			require = 1)
	private void vitrail$countSubmit(CallbackInfo callback) {
		PassTimings.censusSubmit();
	}
}
