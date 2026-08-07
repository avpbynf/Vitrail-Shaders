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
 * <strong>That old file is carried over at the first load of its pack, and {@link #migrate} says
 * how.</strong> It holds everything a player applied before the move, and it is the only place
 * holding it.
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

	/** What one of those files is renamed to once it has been moved into the shared one. */
	private static final String MIGRATED = ".migrated";

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
	 * Read once and then moved aside, which {@link #migrate} does in full. A player who had applied
	 * anything through the screen has their values here and nowhere else, and the move would
	 * otherwise hand every one of those packs back its own defaults without a word.
	 */
	public static Path legacy(Path gameDirectory, String packFileName) {
		return gameDirectory.resolve(LEGACY_DIRECTORY).resolve(LEGACY_SETTINGS)
				.resolve(packFileName + SUFFIX);
	}

	/**
	 * Moves one pack's settings out of the file this engine used to keep and into the one both
	 * engines read, and answers whether it did.
	 * <p>
	 * <strong>At the load and in one go, rather than by reading the old file for as long as the new
	 * one is absent.</strong> Three things make the lazy form wrong, and all three were measured:
	 * the screen's Apply rebases on the shared file, so a first Apply after a lazy migration would
	 * write the one setting just clicked and drop the rest; an Apply with nothing pending writes
	 * nothing at all, so a pack the player only looks at would never migrate; and the absence of the
	 * shared file does not mean "not migrated yet", Iris DELETING it whenever nothing differs from
	 * the pack's defaults ({@code Iris.tryUpdateConfigPropertiesFile}). Under the lazy form a Reset
	 * performed in Iris would bring the old values back.
	 * <p>
	 * <strong>The profile line is expanded and not dropped, and that is the whole of the danger.</strong>
	 * The old writer stored a file RELATIVE to the chosen profile: every value the profile already
	 * named was left out, so a player who had picked one has {@code profile=NAME} and nothing else.
	 * Dropping that line drops their settings entirely. It is turned back into the values it names,
	 * which is what the shared file has to carry since neither engine has a key for a profile.
	 * <p>
	 * The old file is renamed rather than deleted. It is the only copy, this runs once, and a rename
	 * leaves a player something to look at if any of the above turns out to be wrong.
	 *
	 * @param profileValues what a profile of this pack names, by profile name, from the menu that
	 *                      has just been read. Empty for a pack that declares none
	 */
	public static boolean migrate(Path gameDirectory, String packFileName,
			Map<String, Map<String, String>> profileValues) throws IOException {
		Path shared = of(gameDirectory, packFileName);
		Path legacy = legacy(gameDirectory, packFileName);
		if (Files.isRegularFile(shared) || !Files.isRegularFile(legacy)) {
			return false;
		}

		Map<String, String> values = new LinkedHashMap<>();
		String profile = "";
		for (Map.Entry<String, String> entry : lines(legacy).entrySet()) {
			if (entry.getKey().equals(PROFILE_KEY)) {
				profile = entry.getValue();
			} else {
				values.put(entry.getKey(), entry.getValue());
			}
		}

		// The profile first and the file's own values over it, which is the order the old writer
		// took them apart in: what it left out was exactly what the profile already said.
		Map<String, String> merged = new LinkedHashMap<>(
				profileValues.getOrDefault(profile, Map.of()));
		merged.putAll(values);

		write(shared, new Stored(merged));
		Files.move(legacy, legacy.resolveSibling(legacy.getFileName() + MIGRATED),
				StandardCopyOption.REPLACE_EXISTING);

		return true;
	}

	/**
	 * A missing file reads as an empty one. Comments and blank lines are skipped, which is what
	 * lets a file written by Iris, whose first line is the date it wrote it, be read as is.
	 * <p>
	 * {@link #PROFILE_KEY} is dropped, and only {@link #migrate} ever does anything else with it.
	 * Nothing writes it any more, and kept it would stop being the name of a set of values and
	 * become a value: {@code SettingSet.headerDefines} writes every name of this file into the head
	 * of each compiled unit, so the pack would be built with {@code #define profile ULTRA} and a
	 * name it never declared. What that costs is a pack declaring an option literally called
	 * {@code profile}, which would be silently dropped; no pack of the corpus does, and the name is
	 * this format's own.
	 */
	public static Stored read(Path file) throws IOException {
		Map<String, String> values = new LinkedHashMap<>(lines(file));
		values.remove(PROFILE_KEY);

		return new Stored(values);
	}

	/** Every {@code NAME=value} of a file, in order, with nothing taken out. */
	private static Map<String, String> lines(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}

		Map<String, String> values = new LinkedHashMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
			String trimmed = line.trim();
			int equals = trimmed.indexOf('=');
			if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 1) {
				continue;
			}

			values.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
		}

		return values;
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
