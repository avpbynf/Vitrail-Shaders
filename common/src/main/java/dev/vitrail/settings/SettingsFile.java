package dev.vitrail.settings;

import dev.vitrail.pack.PackLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One pack's settings file, {@code vitrail/settings/<pack file name>.txt}.
 * <p>
 * One file per pack rather than one for all of them, and not for tidiness. A chosen name the pack
 * does not declare is written into the head of every one of its units by
 * {@code SettingSet.headerDefines}. Two thousand two hundred and twenty two of the corpus's names
 * are foreign to BSL, so a single shared file would inject that many bare identifiers into BSL's
 * GLSL the day a screen starts writing everything the player touched.
 * <p>
 * A name the pack no longer declares is kept and reported once. Dropping it, which is what Iris
 * does, loses a player's settings for good when they try a new version of a pack and go back.
 * <p>
 * {@code vitrail/options.txt} keeps its job unchanged and is never written here.
 */
public final class SettingsFile {

	/**
	 * The mod's own folder next to {@code mods/}. Spelled out rather than taken from
	 * {@code Vitrail.MOD_ID}, which lives in the class a loader initialises: this package is read
	 * by the out of game harness and one import of that class would end that.
	 */
	private static final String DIRECTORY = "vitrail";

	private static final String SETTINGS = "settings";

	private static final String SUFFIX = ".txt";

	/**
	 * The one line of either file that names a whole set of settings rather than one of them. No
	 * pack in the corpus declares a setting under that name, and a profile is a different thing
	 * from a value anyway: it is a whole set of them.
	 */
	public static final String PROFILE_KEY = "profile";

	private static final List<String> HEADER = List.of(
			"# Written by Vitrail's settings screen, one NAME=value per line.",
			"# Only what differs from the pack's own defaults and from the profile named below.",
			"# vitrail/options.txt is a different file, it is never written here, and it wins.");

	private SettingsFile() {
	}

	public static Path of(Path gameDirectory, String packFileName) {
		return gameDirectory.resolve(DIRECTORY).resolve(SETTINGS).resolve(packFileName + SUFFIX);
	}

	/**
	 * Where a pack's settings are read from: ours, or the file Iris writes for the same pack when
	 * we have none of our own yet.
	 * <p>
	 * Read once and never written back. Iris does not know the {@code profile} line, so rewriting
	 * one of its files through {@link #write} would silently drop what the player chose there the
	 * next time they open the pack under Iris. Four of these files already sit in the test
	 * instance, so the case is not hypothetical.
	 */
	public static Path source(Path gameDirectory, String packFileName) {
		Path ours = of(gameDirectory, packFileName);
		if (Files.isRegularFile(ours)) {
			return ours;
		}

		Path iris = PackLoader.directory(gameDirectory).resolve(packFileName + SUFFIX);

		return Files.isRegularFile(iris) ? iris : ours;
	}

	/**
	 * A missing file reads as an empty one. Comments and blank lines are skipped, which is what
	 * lets a file written by Iris, whose first line is the date it wrote it, be read as is.
	 */
	public static Stored read(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return Stored.empty();
		}

		Map<String, String> values = new LinkedHashMap<>();
		String profile = "";
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String trimmed = line.trim();
			int equals = trimmed.indexOf('=');
			if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 1) {
				continue;
			}

			String name = trimmed.substring(0, equals).trim();
			String value = trimmed.substring(equals + 1).trim();
			if (PROFILE_KEY.equals(name)) {
				profile = value;
			} else {
				values.put(name, value);
			}
		}

		return new Stored(values, profile);
	}

	/**
	 * Through a temporary and an {@code ATOMIC_MOVE} in the same folder, so a crash cannot
	 * truncate it.
	 */
	public static void write(Path file, Stored stored) throws IOException {
		Path directory = file.getParent();
		if (directory != null) {
			Files.createDirectories(directory);
		}

		List<String> lines = new ArrayList<>(HEADER);
		if (!stored.profile().isEmpty()) {
			lines.add(PROFILE_KEY + "=" + stored.profile());
		}

		stored.values().forEach((name, value) -> lines.add(name + "=" + value));

		Path temporary = file.resolveSibling(file.getFileName() + ".part");
		Files.write(temporary, lines, StandardCharsets.UTF_8);
		try {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Everything back to the pack's defaults. {@code options.txt} is not touched. */
	public static void delete(Path file) throws IOException {
		Files.deleteIfExists(file);
	}

	/**
	 * @param profile the reserved {@code profile} key, "" when the file names none. No pack
	 *                declares a setting by that name, and a profile is a different thing from a
	 *                value: it is a whole set of them.
	 */
	public record Stored(Map<String, String> values, String profile) {

		public Stored {
			// Copied in the order they were read rather than through Map.copyOf, so that a file
			// written back keeps the shape a player last saw.
			values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		}

		public static Stored empty() {
			return new Stored(Map.of(), "");
		}

		public boolean isEmpty() {
			return this.values.isEmpty() && this.profile.isEmpty();
		}
	}
}
