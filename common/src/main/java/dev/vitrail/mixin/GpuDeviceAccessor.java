package dev.vitrail.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The backend behind the device the game hands out, which is where the pipeline cache lives.
 * <p>
 * {@code RenderSystem.getDevice()} answers with the front, a plain class that forwards every call
 * and shows the backend to nobody. {@code EntityMesh.settle} needs to ask whether that backend is
 * one {@code VulkanDeviceMixin} taught to set entity pipelines aside, and an instanceof against a
 * private field is an accessor's whole job.
 */
@Mixin(GpuDevice.class)
public interface GpuDeviceAccessor {

	@Accessor("backend")
	GpuDeviceBackend vitrail$backend();
}
