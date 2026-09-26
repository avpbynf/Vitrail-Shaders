package dev.vitrail.neoforge;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import net.neoforged.fml.loading.EarlyLoadingScreenController;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

/**
 * Keeps the game from adopting NeoForge's early loading window when it is about to draw with Vulkan.
 * <p>
 * FML opens a window of its own before Minecraft exists, to draw the mod loading bar, and that
 * window carries an OpenGL context. {@code Window.createGlfwWindow} then asks
 * {@link EarlyLoadingScreenController#current()} and, when there is one, takes over its handle
 * instead of calling {@code glfwCreateWindow}. The hints the backend set one line earlier are then
 * hints on a window nobody creates: the Vulkan surface wants a window whose client API is
 * {@code GLFW_NO_API}, it is handed one made for OpenGL, and the boot falls back. The shape this is
 * recognised by is a log that says "Using graphics backend OpenGL" although {@code options.txt} asks
 * for Vulkan.
 * <p>
 * The answer is to hand the game nothing under Vulkan, so it creates its own window with its own
 * hints, and to dispose of the one FML is left holding once FML has stopped drawing into it. Nothing
 * here touches {@code GLFW_CLIENT_API}: the backend already sets what it needs, it was simply never
 * reached.
 * <p>
 * <strong>NeoForge only, and that is not a limitation.</strong> Fabric has no early loading window,
 * so there is nothing to refuse there, and the Fabric bench boots on Vulkan with none of this.
 */
public final class EarlyWindow {

	/** Whether FML was left holding a window because this is the refusal that answered the game. */
	private static boolean claimed;

	/** The window FML is still holding, zero once it has been disposed of. */
	private static long orphan;

	private EarlyWindow() {
	}

	/**
	 * What the game is told when it asks for the early loading screen, which is nothing at all when
	 * the backend is Vulkan.
	 * <p>
	 * <strong>Only a controller that was really there is claimed</strong>, and that is what makes
	 * this safe beside another mod doing the same job. Two wrappers around one call nest, so only the
	 * innermost reaches {@code current()}: it sees the controller and claims the window, and the
	 * outer one is handed the null the inner one returned and claims nothing. Whichever way round the
	 * two end up, exactly one of them ends up owning the orphan.
	 *
	 * @param backend    the backend the window is being built for, taken from the call rather than
	 *                   from any state of ours: this runs before there is a device to ask
	 * @param controller what the game would have been handed
	 * @param claim      whether this is the call that decides the handle, and so the one that takes
	 *                   ownership of what is left over
	 * @return the controller where the window may be adopted, or null to make the game build its own
	 */
	public static EarlyLoadingScreenController hand(GpuBackend backend,
			EarlyLoadingScreenController controller, boolean claim) {
		if (!(backend instanceof VulkanBackend)) {
			return controller;
		}

		if (claim && controller != null) {
			claimed = true;
		}

		return null;
	}

	/**
	 * Takes the leftover window off FML's hands, without closing it.
	 * <p>
	 * Handing it over is what stops the background thread that has been drawing the loading bar into
	 * it, and it leaves that window's OpenGL context current on this thread, which is why the
	 * capabilities are built right after: FML goes on being asked to tick the screen it thinks it
	 * still owns, and a tick is GL calls.
	 * <p>
	 * <strong>Before the call and not after, which is the whole of why the hook sits there.</strong>
	 * {@code ClientModLoader.finish()} ticks that screen while it works and then closes the loading
	 * screen renderer, which deletes GL objects. Each of those makes the window's context current on
	 * its own, but both then run GL through capabilities this thread does not have, because the game
	 * never adopted a GL window here, and building them is the half only this call does.
	 * <p>
	 * The window is deliberately still alive when this returns. Destroying it here, in the middle of
	 * the game's own construction and with its context current on the thread, is what an earlier
	 * attempt did and what took the process down with no stack to read.
	 */
	public static void takeOver() {
		if (!claimed) {
			return;
		}

		claimed = false;
		EarlyLoadingScreenController controller = EarlyLoadingScreenController.current();
		if (controller == null) {
			// Somebody else took it between the refusal and here. Said rather than passed over: what
			// is left is a window nothing will close, alive behind the game's own for the rest of the
			// session, and a line is the only thing that would ever point at it.
			Vitrail.logger().warn("NeoForge's early loading window was claimed and is gone by the "
					+ "time it should have been taken over, so it stays open behind the game");
			return;
		}

		try {
			orphan = controller.takeOverGlfwWindow();
			GL.createCapabilities();
		} catch (RuntimeException | LinkageError e) {
			// Warned and swallowed. What is lost is a window that is already behind the game's own;
			// what throwing would cost is the boot itself, for a tidying job.
			Vitrail.logger().warn("Could not take NeoForge's early loading window over", e);
		}
	}

	/**
	 * Closes the leftover window, once a frame of the game's own has been through the screen.
	 * <p>
	 * The context goes first and the capabilities with it, so that nothing is left pointing at
	 * functions that belong to a window about to stop existing. Called on every frame and does
	 * nothing on all but the first.
	 */
	public static void close() {
		if (orphan == 0L) {
			return;
		}

		try {
			GLFW.glfwMakeContextCurrent(0L);
			GL.setCapabilities(null);
			GLFW.glfwDestroyWindow(orphan);
			Vitrail.logger().info("Closed the early loading window NeoForge was left holding");
		} catch (RuntimeException | LinkageError e) {
			Vitrail.logger().warn("Could not close the early loading window NeoForge was left "
					+ "holding", e);
		} finally {
			orphan = 0L;
		}
	}
}
