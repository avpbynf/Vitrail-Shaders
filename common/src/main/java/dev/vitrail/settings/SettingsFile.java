package dev.vitrail.settings;

import dev.vitrail.pack.source.PackLoader;

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
 * One pack's settings file, {@code shaderpacks/<pack file name>.txt}.
 * <p>
 * That is the path Iris resolves and the only one it reads, so the two engines share one file per
 * pack: a setting changed on either side is the setting the other reads next. This engine kept a
 * file of its own until then, which it read once as a fallback and never wrote back, so nothing
 * ever travelled from here to Iris and nothing travelled the other way after the first Apply.
 * <p>
 * <strong>That old file is still read, and {@link #legacy} says why.</strong> It holds everything a
 * player applied before the move, and it is the only place holding it.
 * <p>
 * Read and written in ISO-8859-1, which is what OptiFine specifies for these files and what Iris
 * does on both sides. Strict UTF-8 threw on any byte past 0x7F, and the file is no longer ours
 * alone to keep in ASCII.
 * <p>
 * A name the pack no longer declares is kept and reported once. Dropping it, which is what Iris
 * does, loses a player's settings for good when they try a new version of a pack and go back.
 * <p>
 * {@code vitrail/options.txt} keeps its job unchanged and is never written here.
 */
public final class SettingsFile {

	private static final String SUFFIX = ".txt";

	/** The two folders this engine kept its own settings under, before the move. See {@link #legacy}. */
	private static final String LEGACY_DIRECTORY = "vitrail";
	private static final String LEGACY_SETTINGS = "settings";

	/**
	 * The one line of {@code vitrail/options.txt} that names a whole set of settings rather than one
	 * of them. No pack in the corpus declares a setting under that name, and a profile is a different
	 * thing from a value anyway: it is a whole set of them.
	 * <p>
	 * Nothing writes it. A pack's own file holds the values a profile chooses, one per line, the way
	 * Iris holds them; forcing a profile from outside is what this key is left for.
	 */
	public static final String PROFILE_KEY = "profile";

	private static final List<String> HEADER = List.of(
			"# Written by Vitrail's settings screen and by Iris, one NAME=value per line.",
			"# Only what differs from the pack's own defaults.",
			"# vitrail/options.txt is a different file, it is never written here, and it wins.");

	private SettingsFile() {
	}

	public static Path of(Path gameDirectory, String packFileName) {
		return PackLoader.directory(gameDirectory).resolve(packFileName + SUFFIX);
	}

	/**
	 * Where this engine kept a pack's settings before they moved to the file Iris reads.
	 * <p>
	 * Still read, never written. A player who had applied anything through the screen has their
	 * values here and nowhere else, and the move would otherwise hand every one of those packs back
	 * its own defaults without a word.
	 */
	public static Path legacy(Path gameDirectory, String packFileName) {
		return gameDirectory.resolve(LEGACY_DIRECTORY).resolve(LEGACY_SETTINGS)
				.resolve(packFileName + SUFFIX);
	}

	/**
	 * Which of the two files this pack's settings really come from: the shared one, or the one this
	 * engine used to keep when the shared one does not exist yet.
	 * <p>
	 * The shared file wins whenever it exists, even empty: it is the one both engines write, so a
	 * player who has applied anything since the move has said what they want there.
	 */
	public static Path sourceOf(Path gameDirectory, String packFileName) {
		Path shared = of(gameDirectory, packFileName);
		Path legacy = legacy(gameDirectory, packFileName);

		return Files.isRegularFile(shared) || !Files.isRegularFile(legacy) ? shared : legacy;
	}

	/**
	 * A missing file reads as an empty one. Comments and blank lines are skipped, which is what
	 * lets a file written by Iris, whose first line is the date it wrote it, be read as is.
	 * <p>
	 * {@link #PROFILE_KEY} is dropped wherever it is found, and that is not tidiness. Nothing writes
	 * it any more, but the files this engine wrote before the move carry it, and a hand copy into
	 * the shared file is the obvious thing to do with them. Kept, it would stop being the name of a
	 * set of values and become a value: {@code SettingSet.headerDefines} writes every name of this
	 * file into the head of each compiled unit, so the pack would be built with
	 * {@code #define profile ULTRA} and a name it never declared.
	 */
	public static Stored read(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return Stored.empty();
		}

		Map<String, String> values = new LinkedHashMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
			String trimmed = line.trim();
			int equals = trimmed.indexOf('=');
			if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 1) {
				continue;
			}

			String name = trimmed.substring(0, equals).trim();
			if (!name.equals(PROFILE_KEY)) {
				values.put(name, trimmed.substring(equals + 1).trim());
			}
		}

		return new Stored(values);
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
		stored.values().forEach((name, value) -> lines.add(name + "=" + value));

		Path temporary = file.resolveSibling(file.getFileName() + ".part");
		Files.write(temporary, lines, StandardCharsets.ISO_8859_1);
		try {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public record Stored(Map<String, String> values) {

		public Stored {
			// Copied in the order they were read rather than through Map.copyOf, so that a file
			// written back keeps the shape a player last saw.
			values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		}

		public static Stored empty() {
			return new Stored(Map.of());
		}
	}
}
