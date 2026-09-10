package dev.vitrail.render;

import dev.vitrail.IrisBeside;
import dev.vitrail.settings.GraphicsApiChoice;
import dev.vitrail.Vitrail;

import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;

/**
 * Keeps the graphics API across a startup that ended badly, instead of losing it to a crash that had
 * nothing to do with the backend.
 * <p>
 * <strong>What the game does, and why it costs a launch every time.</strong> {@code Minecraft} reads
 * {@code Options.startedCleanly} once at the head of its constructor, sets it false, and saves.
 * Anything that dies before startup finishes leaves it false, and the NEXT launch takes both of
 * these:
 * <pre>
 *   Detected unexpected shutdown during last game startup: resetting fullscreen mode
 *   Detected unexpected shutdown during last game startup: resetting preferred graphics API to Default
 * </pre>
 * and a third, forcing OpenGL, if the launch after that is dirty too. Measured here: a Distant
 * Horizons failure over a native library it could not extract, nothing to do with graphics, took the
 * backend down with it and cost a restart to put back.
 * <p>
 * <strong>Both messages hang off that one read</strong>, which is why answering it is the whole
 * intervention: the fullscreen mode is kept by the same stroke, and it was the second half of the
 * same complaint.
 * <p>
 * The rescue is not wrong in itself, it is wrong here. It exists for a machine whose Vulkan cannot
 * start, and for a vanilla player who wandered into the setting it is the way back. For a player who
 * installed this mod it empties the session instead of saving it: the mod draws nothing off Vulkan
 * and says so. Which is why the default is to come back to Vulkan, and why the other two answers
 * exist beside it.
 */
public final class StartupGuard {

	private static GraphicsApiChoice choice;

	private StartupGuard() {
	}

	/**
	 * Answers the game's question about the last startup, having first put the API back where the
	 * player asked for it to be, except beside Iris, which has already chosen its side.
	 *
	 * @param options the game's options, already loaded, and the only thing that exists this early
	 * @param cleanly what the field really holds
	 * @return what the game should believe, which decides both resets it is about to consider
	 */
	public static boolean startedCleanly(Options options, boolean cleanly) {
		// Before anything below can move the backend: Iris read the same file before the game was
		// built, and which engine draws this session follows that value and nothing written after it.
		IrisBeside.loaded(options);
		if (cleanly) {
			return true;
		}

		GraphicsApiChoice asked = asked();
		if (asked == GraphicsApiChoice.GAME) {
			return false;
		}

		// Beside Iris the backend is not this mod's to move. Iris took its hooks for the backend the
		// file names before the game was built, so putting the API back to Vulkan here would leave
		// Iris hooked for OpenGL on a Vulkan device. Kept as the file has it, and the resets are
		// still refused, for the reason below. A player who chose the game's own rescue still has it,
		// above: that one is theirs to take whatever it does to Iris.
		if (IrisBeside.installed()) {
			Vitrail.logger().warn("The last startup ended badly. The game was about to reset the "
					+ "graphics API and the fullscreen mode; both are kept as they were, {}, and the API "
					+ "is not put back to Vulkan because Iris is installed and has already chosen its "
					+ "hooks for this backend",
					options.preferredGraphicsBackend().get().getSerializedName());

			return true;
		}

		PreferredGraphicsApi wanted = asked == GraphicsApiChoice.OPENGL
				? PreferredGraphicsApi.OPENGL
				: PreferredGraphicsApi.VULKAN;
		// Set rather than merely kept: a launch that already walked the setting down to Default, or
		// on to OpenGL, would otherwise stay there for good. Coming BACK is what was asked for.
		if (options.preferredGraphicsBackend().get() != wanted) {
			options.preferredGraphicsBackend().set(wanted);
		}

		Vitrail.logger().warn("The last startup ended badly. The game was about to reset the "
				+ "graphics API and the fullscreen mode; both are kept and the API is put back to "
				+ "{}, because a crash during startup is almost never the backend. Change this under "
				+ "Video Settings, in this mod's page, or in vitrail/graphics-api.txt",
				wanted.getSerializedName());

		// True, so neither reset runs. The game still wrote the flag false and saved it just before
		// this, so the marker itself goes on working for whatever else reads it.
		return true;
	}

	/** Read once: this is asked inside a constructor, and the answer cannot change during it. */
	private static GraphicsApiChoice asked() {
		GraphicsApiChoice known = choice;
		if (known == null) {
			known = GraphicsApiChoice.read();
			choice = known;
		}

		return known;
	}

	/** The screen has just written a new choice, so the next startup reads it rather than the old one. */
	public static void forget() {
		choice = null;
	}
}
