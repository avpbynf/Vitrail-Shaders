package dev.vitrail.neoforge;

/**
 * Keeps the game from adopting NeoForge's early loading window when it is about to draw with Vulkan.
 * <p>
 * The 26.3 half, which has nothing to refuse. On 26.2 the game's window was built by
 * {@code Window.createGlfwWindow}, which took over the OpenGL window FML had opened for its loading
 * bar, so a Vulkan boot was handed a window made for OpenGL and fell back. 26.3 creates its window
 * through the backend with SDL, {@code GpuBackend.createWindow}, and never asks FML for one, so the
 * window the Vulkan backend draws into is always its own. There is no mixin behind this half and no
 * leftover window to close.
 */
public final class EarlyWindow {

	private EarlyWindow() {
	}

	/** Closes the leftover window, of which this game leaves none. */
	public static void close() {
		// Nothing was taken over, so nothing is left to close.
	}
}
