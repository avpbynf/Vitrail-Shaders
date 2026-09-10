package dev.vitrail;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;

/**
 * Iris in the same instance, and which of the two engines draws this session.
 * <p>
 * <strong>Iris picks its side once, before the game is built</strong>, off the
 * {@code preferredGraphicsBackend} line of {@code options.txt}: a line asking for Vulkan takes its
 * Vulkan-only hooks and draws nothing, anything else takes its OpenGL hooks and draws there
 * ({@code IrisMixinPlugin.java:54} and {@code 72-73}). What this engine does beside it follows that
 * read and not the device. The two part company after a Vulkan boot that failed and fell back to
 * OpenGL, where the file still asks for Vulkan and Iris draws nothing, and under a launch argument
 * that forces a backend: asked of the device, both engines would register the same overlay of
 * Sodium's filtering option, which Sodium refuses at startup, or neither would say why the picture
 * is missing.
 * <p>
 * The line is taken from the options as the game has just loaded them, at the one read of this mod
 * that runs that early ({@code StartupGuard}), which is the same file Iris read and before anything
 * has written the backend back. A later question reads that answer.
 */
public final class IrisBeside {

	/**
	 * A class of Iris's own, looked for as a resource: the first question comes from inside the
	 * game's constructor, before this mod is constructed and before any loader platform exists to
	 * ask by mod id.
	 */
	private static final String MARKER = "net/irisshaders/iris/Iris.class";

	private static volatile Boolean installed;

	/** Whether the options the game loaded asked for Vulkan, or null until they have been seen. */
	private static volatile Boolean askedVulkan;

	private IrisBeside() {
	}

	/** Whether Iris is in this instance at all, whichever backend it took. */
	public static boolean installed() {
		Boolean known = installed;
		if (known == null) {
			known = IrisBeside.class.getClassLoader().getResource(MARKER) != null;
			installed = known;
		}

		return known;
	}

	/**
	 * Remembers what the options asked for as the game loaded them. Only the first call counts: what
	 * Iris read is the file as it stood at startup, whatever is written into the options afterwards.
	 *
	 * @param options the game's options, loaded and not yet touched by anything of this mod
	 */
	public static void loaded(Options options) {
		if (askedVulkan == null) {
			askedVulkan = options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN;
		}
	}

	/**
	 * Whether Iris draws this session: installed, and started on options that did not ask for
	 * Vulkan. Where the startup read was never seen, the game's options stand in for it, which is
	 * the same answer unless the player moved the setting during the session.
	 */
	public static boolean draws() {
		if (!installed()) {
			return false;
		}

		Boolean asked = askedVulkan;
		if (asked == null) {
			Minecraft minecraft = Minecraft.getInstance();
			asked = minecraft != null
					&& minecraft.options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN;
		}

		return !asked;
	}
}
