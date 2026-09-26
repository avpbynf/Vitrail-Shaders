package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.EarlyWindow;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import net.neoforged.fml.loading.EarlyLoadingScreenController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The two places the window asks NeoForge for its early loading screen, and what
 * {@link EarlyWindow} answers under Vulkan.
 * <p>
 * Two and not one because the game asks twice. The static call is where the handle is either taken
 * over or created, so it is the one that decides and the one that takes ownership of what is left
 * over. The constructor asks again afterwards, only to inherit the size of a window it thinks it
 * adopted, and it has to be given the same answer or it reads the size of a window nobody adopted.
 */
@Mixin(Window.class)
public abstract class WindowEarlyScreenMixin {

	private static final String CURRENT =
			"Lnet/neoforged/fml/loading/EarlyLoadingScreenController;current()"
					+ "Lnet/neoforged/fml/loading/EarlyLoadingScreenController;";

	@WrapOperation(method = "createGlfwWindow", at = @At(value = "INVOKE", target = CURRENT),
			require = 1)
	private static EarlyLoadingScreenController vitrail$refuseTheEarlyWindow(
			Operation<EarlyLoadingScreenController> original,
			@Local(argsOnly = true) GpuBackend backend) {
		return EarlyWindow.hand(backend, original.call(), true);
	}

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = CURRENT), require = 1)
	private static EarlyLoadingScreenController vitrail$sayItAgainOnInit(
			Operation<EarlyLoadingScreenController> original,
			@Local(argsOnly = true) GpuBackend backend) {
		return EarlyWindow.hand(backend, original.call(), false);
	}
}
