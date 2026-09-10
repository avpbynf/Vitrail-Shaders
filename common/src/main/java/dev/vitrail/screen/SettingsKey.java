package dev.vitrail.screen;

import dev.vitrail.IrisBeside;
import dev.vitrail.render.PackChoice;
import dev.vitrail.ScreenText;
import dev.vitrail.settings.PackSession;
import dev.vitrail.Vitrail;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

/**
 * The two keys this mod binds: R reads the pack again, I opens the pack screen.
 * <p>
 * Both are Iris's own, {@code iris.keybind.reload} and {@code iris.keybind.shaderPackSelection} at
 * {@code Iris.java:811} and {@code 813}, so a player who has configured a pack before will try them
 * first and find them. The game hands a press to every mapping bound to its key, so beside Iris one
 * press reaches both mods, and only one of them may answer it. Where Iris draws, the reload stands
 * aside, Iris's answering only with its debug options on ({@code Iris.java:181}), and so does this
 * screen's key while it shares Iris's. Where Iris does not draw, on Vulkan, Iris binds I to a screen
 * saying it cannot run there ({@code IrisVKOnly.java:17}), and the press is taken from Iris's mapping
 * before Iris asks it ({@link #beforeTick}). A player who moved either key gets each screen on its
 * own.
 * <p>
 * The mappings and what a press does are here; registering them and asking them on the tick is each
 * loader's own business, because those are the two things they do differently. Asking on a tick
 * rather than on a key event is deliberate and is the same on both: the game only feeds key mappings
 * while no screen is open, so the shortcut cannot open a second copy of this screen over the first,
 * and it costs two booleans a tick, and one lookup more beside Iris.
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

	/** Iris's mapping for its pack screen, by the name it registers under on both backends. */
	private static final String IRIS_SCREEN_KEY = "iris.keybind.shaderPackSelection";

	private SettingsKey() {
	}

	/**
	 * Takes the press of the key this mod shares with Iris from Iris's mapping, where Iris does not
	 * draw. Called before anything of the tick has asked a key, because Iris asks its own at the end
	 * of the tick ({@code VKOnly_InitKeys.java:27}) and would open its screen over this one.
	 */
	public static void beforeTick() {
		if (!IrisBeside.installed() || PackScreens.irisDraws()) {
			return;
		}

		KeyMapping iris = sharedIrisKey();
		if (iris != null) {
			drain(iris);
		}
	}

	/** Acts on whichever of the two was pressed since the last tick, and does nothing otherwise. */
	public static void poll() {
		if (drain(OPEN)) {
			// Where Iris draws and the key is shared, the same press reached Iris's mapping, which opens
			// Iris's screen: opening it here as well would lay a second over it.
			if (!PackScreens.irisDraws() || sharedIrisKey() == null) {
				Minecraft minecraft = Minecraft.getInstance();
				minecraft.gui.setScreen(PackScreens.open(minecraft.gui.screen()));
			}

			return;
		}

		if (drain(RELOAD)) {
			reload();
		}
	}

	/** Iris's mapping for its pack screen where it is bound to the same key as {@link #OPEN}, or null. */
	private static @Nullable KeyMapping sharedIrisKey() {
		KeyMapping iris = KeyMapping.get(IRIS_SCREEN_KEY);

		return iris != null && iris.same(OPEN) ? iris : null;
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
	 * The failure is asked for rather than caught, because catching is not where it lands: reading a
	 * pack puts what went wrong where the screen's own bottom line reads it instead of throwing, and
	 * the load's own catch is what makes that so. So a press that read nothing is told apart by asking
	 * that same question. Iris says both lines too, {@code Iris.java:187} and {@code Iris.java:194},
	 * off a catch rather than off a question because throwing is what its own reload does.
	 * <p>
	 * <b>A reading that read nothing because the named pack is gone answers as a failure.</b> Warned
	 * about and then treated as no pack having been asked for, it would put "Shaders Reloaded!" over
	 * a reading that opened nothing, and the screen's own bottom line would go further and say the
	 * game was drawing its own image on purpose. Asking for no pack at all is still no failure and
	 * still says the first line, which is the whole distinction: {@code PackChoice.packMissing} holds
	 * it.
	 */
	private static void reload() {
		// Where Iris draws this session the key is Iris's: it reloads its own pack on the same R, and
		// this engine, which draws nothing on that backend, would only answer the press with a red
		// line saying the pack is not drawn.
		if (IrisBeside.draws()) {
			return;
		}

		Path directory = PackChoice.session()
				.map(PackSession::gameDirectory)
				.orElseGet(() -> Vitrail.platform().gameDirectory());

		PackChoice.reload(directory);

		MutableComponent said = PackChoice.lastError()
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
