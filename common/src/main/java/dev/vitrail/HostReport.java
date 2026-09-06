package dev.vitrail;


import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The two parts of an install that nothing here can set and that both change what is drawn: which
 * graphics backend the game came up on, and which of Chloride's own settings are on.
 * <p>
 * They are said at startup because neither announces itself in the picture. A game running OpenGL
 * loads this mod and is then drawn by the game alone, the pack read and inert; a mob Chloride culled
 * is simply not there. Both look like this engine failing, and a report about either would be filed
 * against it and read against its code. Said at the moment the log is still short enough to read,
 * and only where there is something to say.
 * <p>
 * The backend is the one of the two said a second time, in chat, the first time a world is shown:
 * the log is read by whoever goes looking, and a player whose pack does nothing at all is not yet
 * looking. Once a session and not once a world, since the answer cannot change between them.
 * <p>
 * Nothing here is fixed on the player's behalf, and that is the whole shape of it: the backend is
 * the game's to choose and is chosen before this runs, and Chloride's file belongs to Chloride.
 * What this can do is name the setting, spell it the way the file spells it, and say what it costs.
 * <p>
 * <strong>It is a reading of one moment and does not follow either of them afterwards.</strong>
 * Chloride offers its settings in the Sodium screen and can rebuild the device without a restart,
 * so a session that changes one of them mid-way has a report that no longer describes it. What that
 * costs is a line that is missing rather than a line that is wrong, which is the direction to be
 * wrong in.
 */
public final class HostReport {

	/**
	 * The word {@code DeviceInfo.backendName()} holds on the backend this mod is written for, which
	 * each backend passes in itself: {@code VulkanDevice} passes this one and {@code GlHeuristics}
	 * passes {@code OpenGL}. Compared rather than matched against a list, so a backend nobody here
	 * has heard of is treated as one this mod was not written for, which is what it would be.
	 */
	private static final String VULKAN = "Vulkan";

	/**
	 * Answered where the device is not up yet, which at client setup cannot happen: the game builds
	 * it in the {@code Minecraft} constructor and dispatches mod events long afterwards. It is here
	 * so that a caller earlier than that gets a word rather than a crash, and it is a word the error
	 * below stays silent on: a backend nobody can name is not one to send somebody into the video
	 * settings over.
	 */
	private static final String UNKNOWN = "unknown";

	/**
	 * Chloride's own file, under the game's config folder rather than under this mod's: it belongs to
	 * Chloride, and the path is spelled here the way {@code INSTALL.md} spells it to whoever has to
	 * go and edit it.
	 */
	private static final String CHLORIDE_CONFIG = "config/chloride-client.toml";

	/** Chloride, asked for by id because it is optional and most installs will not have it. */
	private static final String CHLORIDE_ID = "chloride";

	/**
	 * Whether the chat line has been said, or found to have nothing to say, which closes it for the
	 * session either way.
	 */
	private static volatile boolean saidInWorld;

	/**
	 * The entries of Chloride's file this engine has something to say about. Three of them take
	 * geometry away before this engine sees it at all and two hand it to a path this engine's own
	 * final pass then covers, so a family missing from the picture is worth looking for here before
	 * it is looked for in the pack.
	 * <p>
	 * Each is written as its table and its key, which is how the messages spell them and how the
	 * file does: {@code entities} alone would name a key another of Chloride's tables declares as
	 * well, and {@code tileEntities} alone sits among neighbours that begin with the same word.
	 * <p>
	 * The three culling entries take by DISTANCE and by nothing else, which is why none of the
	 * messages names a count. Chloride has one, {@code entityLimit}, but it is a key of its own read
	 * by a different mixin, so a message about these three that named it would send whoever reads it
	 * to a line none of them is behind.
	 */
	private static final List<Entry> CHLORIDE = List.of(
			new Entry("fastBlocks", "chests",
					"Chloride draws chests as static block models. That path is covered over by this "
							+ "engine's final pass, so chests go invisible with no other symptom. Set "
							+ "it to false to see them"),
			new Entry("fastBlocks", "beds",
					"Chloride draws beds as static block models, and they go the way chests do: "
							+ "covered over by the final pass, invisible with no other symptom. Set "
							+ "it to false to see them"),
			new Entry("culling", "tileEntities",
					"Chloride decides on its own which block entities are drawn, by distance. What it "
							+ "takes out is never handed to the pack, so a chest or a sign that is "
							+ "not there is this rather than anything the pack does. Set it to false "
							+ "to have them all"),
			new Entry("culling", "entities",
					"Chloride decides on its own which entities are drawn, by distance, and this one "
							+ "governs every kind but the monsters. What it takes out is never handed "
							+ "to the pack either, so a boat, an item frame or a villager that is "
							+ "missing, or that appears as you walk towards it, is this. Set it to "
							+ "false to have them all"),
			new Entry("culling", "monsters",
					"Chloride decides on its own which monsters are drawn, by a distance of their "
							+ "own. A zombie or a creeper takes this line rather than the one above, "
							+ "so it is the one to set to false when what is missing is a monster"));

	private HostReport() {
	}

	/**
	 * The backend the game really came up on, which is not the same question as the one
	 * {@code options.txt} answers: asked for Vulkan, the game tries it and falls back to OpenGL in
	 * the same run when it cannot be brought up, leaving the file saying one thing and the session
	 * running on the other. Only the device knows.
	 *
	 * @return the backend name, or {@link #UNKNOWN} before the device exists
	 */
	public static String backend() {
		GpuDevice device = RenderSystem.tryGetDevice();
		return device == null ? UNKNOWN : device.getDeviceInfo().backendName();
	}

	/**
	 * Whether the game is known to have come up on a backend this mod was not written for. No both
	 * on Vulkan and before the device exists, {@link #UNKNOWN} being a word rather than a backend:
	 * what answers yes here is refused a picture, and a backend nobody can name is not one to refuse
	 * it over.
	 */
	public static boolean otherBackend() {
		String backend = backend();

		return !UNKNOWN.equals(backend) && !VULKAN.equals(backend);
	}

	/**
	 * Says in chat, once a session, that nothing of the pack is drawn on this backend and which
	 * setting draws it. Asked every tick and answering nothing until a world is on the screen with
	 * no screen over it: said from the login packet, the line would land behind the loading
	 * terrain screen and be fading by the time the world appears.
	 * <p>
	 * Added to the chat as a message of the client's own rather than sent to the player the way the
	 * reload key's line is: {@code LocalPlayer.sendSystemMessage} hands it to the chat listener as
	 * a server message, and a chat set to hidden drops those, {@code ChatListener.handleSystemMessage}
	 * checking {@code canReceiveSystemMessages} first. The client's own source is the one
	 * {@code ChatAbilities.selectVisibleMessages} always lets through, and a line about the whole
	 * picture being missing is not one to leave to a chat setting.
	 * <p>
	 * The setting and its entry are named by the game's own labels rather than by a translation of
	 * ours, so that the words match the screen the player is sent to whatever language the game is
	 * in.
	 */
	public static void sayInWorld() {
		if (saidInWorld) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.gui.screen() != null) {
			return;
		}

		saidInWorld = true;
		if (!otherBackend()) {
			return;
		}

		minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable(
				ScreenText.OTHER_BACKEND, backend(), Component.translatable(ScreenText.GRAPHICS_API),
				Component.translatable(ScreenText.GRAPHICS_API_VULKAN))
				.withStyle(ChatFormatting.RED));
	}

	/**
	 * Says what an install decides for this mod, once, and only where it decides something. A backend
	 * that is Vulkan and a Chloride with nothing on cost no line at all: the log is read by whoever is
	 * chasing something else, and a line saying that all is well is one more to step over.
	 */
	public static void say(Path gameDirectory) {
		sayBackend();

		// Asked before the file is looked for, and that question is the difference between a mod
		// that is installed and has not written its settings yet, which is worth a line because its
		// defaults are then in play, and a mod that is simply not there, which is worth none.
		if (Vitrail.platform().isModLoaded(CHLORIDE_ID)) {
			sayChloride(gameDirectory.resolve(CHLORIDE_CONFIG));
		}
	}

	/**
	 * Said as an error rather than a warning because the pack a player asks for is not drawn at all,
	 * and that is the engine's doing: {@code PackChoice.load} reads whichever one is named, publishes
	 * it to the settings screen and stops there. It stops because of what the passes drew when they were let run on the
	 * other backend, the symptom this repository has seen there: a picture both credible and wrong,
	 * the programs having been translated against Vulkan's depth and clip conventions. Credible and
	 * wrong reads as a pack fault, which is worse than a picture the game draws alone, so nothing is
	 * drawn and the line says why.
	 */
	private static void sayBackend() {
		if (!otherBackend()) {
			return;
		}

		Vitrail.logger().error("This game is running the {} backend and {}'s programs are translated "
				+ "for {} alone. The mod loads, a pack it is asked for is read and shown in its "
				+ "settings screen, and nothing of it is drawn: the game keeps its own image. Set "
				+ "Graphics API to \"Prefer Vulkan (Experimental)\" under Options, Video Settings, "
				+ "and restart. If it was already set there, either a launch argument forced this "
				+ "backend, which the game says higher up, or the Vulkan boot failed and the game "
				+ "fell back: the reason is in the lines the game logged before this one",
				backend(), Vitrail.MOD_NAME, VULKAN);
	}

	/**
	 * Reads Chloride's file rather than Chloride, which nothing here calls into and which the caller
	 * has already established is installed.
	 * <p>
	 * <strong>An entry that is not there is said as well, and that is not tidiness.</strong> Some of
	 * them are ones Chloride starts with on, so answering a missing line the way a line written
	 * {@code false} is answered would leave the exact case that costs a picture silent: what a player
	 * would then have is block entities disappearing and a log that never mentioned it. Which way any
	 * given build of Chloride writes them is its business and is not asserted here, so what is said
	 * is that they were not found rather than what they are.
	 */
	private static void sayChloride(Path file) {
		List<String> lines;
		try {
			if (!Files.isRegularFile(file)) {
				Vitrail.logger().warn("Chloride's own settings were not read: there is no file at {}. "
						+ "Some of them decide which blocks and which entities are drawn at all, "
						+ "before anything of the pack is asked", file);
				return;
			}

			lines = Files.readAllLines(file);
		} catch (IOException failure) {
			Vitrail.logger().warn("Chloride's own settings were not read: {} could not be opened. Some "
					+ "of them decide which blocks and which entities are drawn at all, before "
					+ "anything of the pack is asked", file, failure);
			return;
		}

		List<String> missing = new ArrayList<>();
		for (Entry entry : CHLORIDE) {
			Boolean written = written(lines, entry.table(), entry.key());
			if (written == null) {
				missing.add(entry.name());
			} else if (written) {
				Vitrail.logger().warn("{} is on in {}. {}", entry.name(), file, entry.cost());
			}
		}

		if (!missing.isEmpty()) {
			// Joined rather than handed over whole: each name is already bracketed, and a list
			// printed as one reads "for [[culling] entities]".
			Vitrail.logger().warn("{} writes no line for {}, so whether Chloride is drawing what that "
					+ "governs was not established. It decides which blocks and which entities reach "
					+ "the pack at all", file, String.join(", ", missing));
		}
	}

	/**
	 * One boolean out of one table of a TOML file, read by hand rather than by a parser: a handful of
	 * keys of one file is not worth a dependency.
	 * <p>
	 * A value that is neither {@code true} nor {@code false} answers the same as an absent key, which
	 * is the reading that says it does not know rather than the one that says no.
	 *
	 * @return what the file writes, or null where it writes nothing this understands
	 */
	private static Boolean written(List<String> lines, String table, String key) {
		String current = "";
		for (String raw : lines) {
			// A comment runs to the end of the line, and both a table header and a value may carry
			// one. Cutting it here rather than at each reading below is also what turns a whole line
			// of comment into an empty one.
			int hash = raw.indexOf('#');
			String line = (hash < 0 ? raw : raw.substring(0, hash)).trim();

			if (line.startsWith("[") && line.endsWith("]")) {
				current = line.substring(1, line.length() - 1).trim();
				continue;
			}

			int equals = line.indexOf('=');
			if (!current.equals(table) || equals < 1
					|| !line.substring(0, equals).trim().equals(key)) {
				continue;
			}

			String value = line.substring(equals + 1).trim();
			if (value.equals("true")) {
				return Boolean.TRUE;
			}

			return value.equals("false") ? Boolean.FALSE : null;
		}

		return null;
	}

	/** One entry of Chloride's file, its table, its key and what having it on costs here. */
	private record Entry(String table, String key, String cost) {

		String name() {
			return "[" + this.table + "] " + this.key;
		}
	}
}
