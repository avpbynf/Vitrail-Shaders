package dev.vitrail.settings;

import dev.vitrail.Vitrail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * What the graphics API becomes after a startup that ended badly.
 * <p>
 * The game keeps a flag, {@code Options.startedCleanly}, and anything that dies before startup
 * finishes leaves it false. The next launch then walks the preferred API down to Default, and the
 * launch after that forces OpenGL. One crash from any mod at all therefore costs a restart to put
 * Vulkan back by hand, and the crash is almost never the backend's doing.
 * <p>
 * <strong>Why this is a file of its own and not a line of {@code pack.txt}.</strong> It is read
 * inside {@code Minecraft}'s constructor, before this mod is constructed and before there is a
 * platform to ask for a game directory. Everything in {@code pack.txt} is read much later, by
 * something that already exists. Sharing that file would mean loading the pack settings machinery at
 * a moment when the game itself is half built.
 * <p>
 * One word, and an unreadable or absent file reads as the default. That default is {@link #VULKAN}
 * and not the game's own behaviour, deliberately: this mod draws nothing off Vulkan and says so, so
 * a reset does not rescue the session, it empties it.
 */
public enum GraphicsApiChoice {

	/** Come back to Vulkan, which is what a mod that only draws there wants. */
	VULKAN("vulkan"),

	/** Come back to OpenGL, for a machine where Vulkan really cannot start. */
	OPENGL("opengl"),

	/** Leave the game alone: reset to Default, then force OpenGL on the launch after. */
	GAME("game");

	/** What a fresh install does, and it is the whole point of the setting. */
	public static final GraphicsApiChoice DEFAULT = VULKAN;

	private static final String FILE = "graphics-api.txt";

	private final String word;

	GraphicsApiChoice(String word) {
		this.word = word;
	}

	public String word() {
		return this.word;
	}

	/**
	 * What the instance asks for, or {@link #DEFAULT} where it asks for nothing this understands.
	 * <p>
	 * Resolved against the working directory rather than a game directory: the launcher runs the
	 * game with the instance as its working directory, and at the moment this is first read there is
	 * nothing else to ask. A typo reads as the default and says so, because a setting that silently
	 * means something else is worse than one that is ignored out loud.
	 */
	public static GraphicsApiChoice read() {
		Path file = file();
		String asked;
		try {
			if (!Files.isRegularFile(file)) {
				return DEFAULT;
			}

			asked = Files.readString(file, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
		} catch (IOException | RuntimeException ignored) {
			return DEFAULT;
		}

		for (GraphicsApiChoice choice : values()) {
			if (choice.word.equals(asked)) {
				return choice;
			}
		}

		Vitrail.logger().warn("vitrail/{} says \"{}\", which is not one of vulkan, opengl or game, "
				+ "so the default is used", FILE, asked);

		return DEFAULT;
	}

	/** Writes the choice where {@link #read} will find it. Failure costs the next session, not this one. */
	public static void write(Path gameDirectory, GraphicsApiChoice choice) {
		Path file = gameDirectory.resolve("vitrail").resolve(FILE);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, choice.word + "\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Vitrail could not write the graphics API choice to {}", file, e);
		}
	}

	private static Path file() {
		return Path.of("vitrail", FILE);
	}
}
