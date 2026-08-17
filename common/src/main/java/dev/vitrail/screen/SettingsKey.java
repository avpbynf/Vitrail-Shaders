package dev.vitrail.screen;

import dev.vitrail.Vitrail;
import dev.vitrail.render.PackChain;
import dev.vitrail.settings.PackSession;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

/**
 * The two keys this mod binds: R reads the pack again, I opens the settings screen.
 * <p>
 * R is Iris's own, {@code iris.keybind.reload} at {@code Iris.java:785}, so a player who has
 * configured a pack before will try it first and find it. I is NOT: Iris opens its screen on O,
 * {@code Iris.java:787}. Nothing forced that, it is simply the key this mod was given before anyone
 * read which one the reference used, and it is written down here rather than quietly presented as
 * parity.
 * <p>
 * The mappings and what a press does are here; registering them and asking them once a tick is each
 * loader's own business, because those are the two things they do differently. Asking on a tick
 * rather than on a key event is deliberate and is the same on both: the game only feeds key mappings
 * while no screen is open, so the shortcut cannot open a second copy of this screen over the first,
 * and it costs two booleans a tick.
 * <p>
 * That last point is also why the screen keeps a reload button of its own. From an open screen these
 * keys are not fed at all, so the key alone would leave the one place where a pack is being worked on
 * without the one thing that reads it again.
 */
public final class SettingsKey {

	public static final KeyMapping.Category CATEGORY =
			new KeyMapping.Category(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "keybinds"));

	public static final KeyMapping OPEN =
			new KeyMapping(ScreenText.OPEN_SETTINGS, GLFW.GLFW_KEY_I, CATEGORY);

	public static final KeyMapping RELOAD =
			new KeyMapping(ScreenText.RELOAD_PACK, GLFW.GLFW_KEY_R, CATEGORY);

	private SettingsKey() {
	}

	/** Acts on whichever of the two was pressed since the last tick, and does nothing otherwise. */
	public static void poll() {
		if (drain(OPEN)) {
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.gui.setScreen(new SettingsScreen(minecraft.gui.screen()));

			return;
		}

		if (drain(RELOAD)) {
			reload();
		}
	}

	/**
	 * Answers whether a key was pressed, and empties whatever else it queued.
	 * <p>
	 * A tick can carry more than one press, and the rest are dropped rather than acted on. Opening one
	 * screen per press would stack them, each holding the one before it as the screen to go back to,
	 * so leaving would take as many Escapes as the key was tapped; reading the pack once per press
	 * would read the same files twice for nothing.
	 */
	private static boolean drain(KeyMapping key) {
		if (!key.consumeClick()) {
			return false;
		}

		while (key.consumeClick()) {
			// Nothing to do: the queue is only being emptied.
		}

		return true;
	}

	/**
	 * Reads the pack again from disk, and says which of the two things happened.
	 * <p>
	 * The directory is the loaded session's rather than the platform's whenever there is one, which is
	 * the choice the settings screen makes for the same reason: what a hand edit reloads and what the
	 * screen reloads must be the same folder.
	 * <p>
	 * The failure is asked for rather than caught, because there is nothing to catch: reading a pack
	 * never throws out of here, it puts what went wrong where the screen's own bottom line reads it.
	 * So a press that read nothing has to be told apart by asking that same question, or the chat
	 * would report a reload that did not happen. Iris says both lines too, {@code Iris.java:180}.
	 */
	private static void reload() {
		Path directory = PackChain.session()
				.map(PackSession::gameDirectory)
				.orElseGet(() -> Vitrail.platform().gameDirectory());

		PackChain.reload(directory);

		MutableComponent said = PackChain.lastError()
				.map(reason -> Component.translatable(ScreenText.RELOAD_FAILED, reason)
						.withStyle(ChatFormatting.RED))
				.orElseGet(() -> Component.translatable(ScreenText.PACK_RELOADED));

		// In a world only, which is Iris's own guard: outside one there is no chat to say it in, and
		// what was read is in the log either way.
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(said);
		}
	}
}
