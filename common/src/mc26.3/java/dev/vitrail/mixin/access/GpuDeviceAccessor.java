package dev.vitrail.mixin.access;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The backend behind the device the game hands out.
 * <p>
 * The 26.3 half. {@code RenderSystem.getDevice()} answers with {@code FrontendGpuDevice}, which
 * implements the {@code GpuDevice} interface every caller holds and keeps the backend in a private
 * field, as the 26.2 front did in the class of that name. The accessor sits on the implementing
 * class, so a cast of whatever {@code getDevice()} returned reaches it the same way on both.
 */
@Mixin(FrontendGpuDevice.class)
public interface GpuDeviceAccessor {

	@Accessor("backend")
	GpuDeviceBackend vitrail$backend();
}
