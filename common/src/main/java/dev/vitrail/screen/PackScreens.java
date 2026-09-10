package dev.vitrail.screen;

import dev.vitrail.HostReport;
import dev.vitrail.IrisBeside;
import dev.vitrail.Vitrail;

import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Which pack screen a door of this mod opens: this engine's own, or Iris's in a session where Iris
 * draws.
 * <p>
 * Beside Iris on OpenGL the picture is Iris's, and so is the pack: Iris reads its own choice and its
 * own settings, and this engine draws nothing on that backend. This engine's screen would list the
 * same folder and write its own files, so a pack picked there would change nothing on screen while the
 * one Iris draws stayed where it was. The page of Sodium's video settings and the Config button of
 * NeoForge's mod list therefore lead to Iris's screen in that session, and so does the key, which
 * stands aside while it shares Iris's own ({@link SettingsKey}). The way in is the one Iris publishes for other mods,
 * {@code IrisApi.openMainIrisScreenObj} at {@code IrisApi.java:83}, called by name because Iris is
 * nowhere on this mod's compile path.
 * <p>
 * The device is asked as well as the options, as the Sodium page does before its overlay: a launch
 * argument forcing Vulkan over a file that asks for OpenGL leaves Iris hooked for OpenGL and this
 * engine drawing, and this engine's screen is then the one that changes the picture.
 */
public final class PackScreens {

	private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

	private PackScreens() {
	}

	/**
	 * The screen to open over {@code parent}. Should Iris's entry point not answer, which a later Iris
	 * could cause by moving it, this engine's screen opens rather than nothing, and the log says why.
	 */
	public static Screen open(@Nullable Screen parent) {
		if (irisDraws()) {
			try {
				Class<?> api = Class.forName(IRIS_API, true, PackScreens.class.getClassLoader());
				Object instance = api.getMethod("getInstance").invoke(null);
				Object screen = api.getMethod("openMainIrisScreenObj", Object.class).invoke(instance, parent);
				if (screen instanceof Screen irisScreen) {
					return irisScreen;
				}

				Vitrail.logger().warn("Iris draws this session but its pack screen entry point answered {}, "
						+ "so Vitrail's screen opens instead", screen);
			} catch (ReflectiveOperationException | LinkageError e) {
				Vitrail.logger().warn("Iris draws this session but its pack screen could not be opened, "
						+ "so Vitrail's screen opens instead", e);
			}
		}

		return new SettingsScreen(parent);
	}

	/** Whether Iris draws this session, by the options it read at startup and on the device it hooked. */
	static boolean irisDraws() {
		return IrisBeside.draws() && HostReport.otherBackend();
	}
}
