package dev.vitrail.settings;

import dev.vitrail.pack.menu.MenuOption;
import dev.vitrail.pack.menu.MenuValues;
import dev.vitrail.pack.menu.PackMenu;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.load.PackLoader;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
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
 * does on both sides. Strict UTF-8 throws on any byte past 0x7F, and the file is not ours alone to
 * keep in ASCII. The one exception is the file {@link #migrate} carries over, read as the
 * UTF-8 this engine wrote it in.
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

	/** The three bytes an editor may put in front of a UTF-8 file, which Latin-1 reads as letters. */
	private static final byte[] BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

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
	 * @param menu this pack's menu, just read. It is what says which names are toggles, what each
	 *             one defaults to, and what a profile names, and all three are needed: the old file
	 *             held a toggle as {@code on}, which Iris reads as no value at all
	 */
	public static Carried migrate(Path gameDirectory, String packFileName, PackMenu menu)
			throws IOException {
		Path shared = of(gameDirectory, packFileName);
		Path legacy = legacy(gameDirectory, packFileName);
		if (Files.isRegularFile(shared) || !Files.isRegularFile(legacy)) {
			return new Carried(Carry.NOTHING, "", null);
		}

		// In UTF-8 and not in the format's own encoding, because this file was written by this
		// engine before the move and this engine wrote UTF-8. Reading it as the shared file is read
		// would turn an accented value into mojibake, and now that the value is copied on it would
		// be mojibake for good rather than for one load.
		Map<String, String> values = new LinkedHashMap<>();
		String profile = "";
		for (Map.Entry<String, String> entry : lines(legacy, StandardCharsets.UTF_8).entrySet()) {
			if (entry.getKey().equals(PROFILE_KEY)) {
				profile = entry.getValue();
			} else {
				values.put(entry.getKey(), entry.getValue());
			}
		}

		// A profile this version of the pack no longer declares cannot be turned into anything, and
		// the old file holds only what DIFFERED from it. Moving what is left would move a fraction
		// of what the player chose and rename the only copy away, so nothing is touched at all and
		// the caller says so.
		if (!profile.isEmpty() && !menu.profileNames().contains(profile)) {
			return new Carried(Carry.UNKNOWN_PROFILE, profile, legacy);
		}

		// The profile first and the file's own values over it, which is the order the old writer
		// took them apart in: what it left out was exactly what the profile already said.
		Map<String, String> merged = new LinkedHashMap<>(menu.profile(profile));
		merged.putAll(values);

		Map<String, String> written = new LinkedHashMap<>();
		merged.forEach((name, value) -> {
			// What a profile names is usually what the pack already defaults to, and this file is
			// only ever the difference. Writing the whole profile out would be a fatter file than
			// either engine writes and a count that says eight settings moved when none did, BSL's HIGH
			// being eight values that are all the pack's own defaults.
			// Through asText on both sides, because the two spellings of a boolean have to compare
			// equal here: the pack's default is held as the menu holds it and the old file wrote
			// whichever of on and true the screen had at the time.
			String fallback = menu.option(name).map(MenuOption::defaultValue).orElse(null);
			if (fallback == null
					|| !OptionValue.parse(fallback).asText().equals(OptionValue.parse(value).asText())) {
				written.put(name, MenuValues.written(menu, name, value));
			}
		});

		// Written first, because it is the half that can be undone. A shared file this method wrote
		// and then removes leaves the pack exactly as it found it; an old file renamed and then not
		// written out would be settings nobody can reach.
		try {
			write(shared, new Stored(written));
		} catch (IOException e) {
			return new Carried(Carry.FAILED, profile, shared);
		}

		// ALL OR NOTHING, and the rename is what makes it so. Leaving the old file readable would
		// let this run a second time, which is not the harmless repeat it looks like: the guard
		// above is the presence of the shared file, and Iris DELETES that file whenever nothing
		// differs from the pack's defaults. A Reset performed there would be undone by the next
		// load, silently, out of a file the player believed gone. So a refused rename undoes the
		// write and answers a failure, which is what it is.
		try {
			Files.move(legacy, legacy.resolveSibling(legacy.getFileName() + MIGRATED),
					StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Files.deleteIfExists(shared);

			return new Carried(Carry.FAILED, profile, legacy);
		}

		return new Carried(Carry.MOVED, profile, null);
	}

	/**
	 * What {@link #migrate} did, which the caller reports and nothing else acts on.
	 *
	 * @param profile the profile the old file named, for the one answer that has to name it. Empty
	 *                where it named none, and empty as well where nothing got as far as reading it
	 * @param file    the path this answer is about, which is not the same one for each of them: the
	 *                old file where it could not be read or could not be completed, and the shared
	 *                one where that is what could not be written. Null where there is nothing to
	 *                name, which is every ordinary load
	 */
	public record Carried(Carry carry, String profile, Path file) {
	}

	/**
	 * What became of a settings file left behind by an older layout.
	 *
	 * @see Carried
	 */
	public enum Carry {

		/** No old file, or a shared one already there. The ordinary case, and silent. */
		NOTHING,

		/** The old file was read, written out to the shared one, and renamed aside. */
		MOVED,

		/**
		 * The old file names a profile this version of the pack does not declare, so what it holds
		 * cannot be completed. Nothing was written and nothing was renamed; the player still has
		 * their file, and the caller names the missing profile out of {@link Carried#profile}.
		 */
		UNKNOWN_PROFILE,

		/** Nothing could be read or written at all. The old file is untouched and tried again. */
		FAILED
	}

	/**
	 * A missing file reads as an empty one. Comments and blank lines are skipped, which is what
	 * lets a file written by Iris, whose first line is the date it wrote it, be read as is.
	 * <p>
	 * {@link #PROFILE_KEY} is dropped, and only {@link #migrate} ever does anything else with it.
	 * Nothing writes it, and kept it would stop being the name of a set of values and
	 * become one: it would go down with the settings, and a pack declaring an option literally
	 * called {@code profile} would have that declaration rewritten to the profile's name. What
	 * dropping it costs is the same pack, whose {@code profile} setting can then not be chosen from
	 * this file; no pack of the corpus declares one, and the name is this format's own.
	 */
	public static Stored read(Path file) throws IOException {
		Map<String, String> values = new LinkedHashMap<>(lines(file, StandardCharsets.ISO_8859_1));
		values.remove(PROFILE_KEY);

		return new Stored(values);
	}

	/**
	 * Every {@code NAME=value} of a file, in order, with nothing taken out.
	 * <p>
	 * Decoded through the {@code String} constructor rather than through {@code readAllLines},
	 * because that one THROWS on a byte the charset cannot make sense of and this one replaces it.
	 * A file the player edited in another editor must not be able to take a pack down, and neither
	 * must one this engine wrote in another encoding years ago: what is unreadable is one value, and
	 * a lost value is what pressing Apply fixes.
	 * <p>
	 * A byte order mark is taken off, and the lines are cut by {@code lines()} rather than by a
	 * split on {@code \r?\n}, for the reasons {@code SettingsLayers.text} gives: left on, the
	 * mark rides on the first key and matches no setting, and a file saved with lone carriage
	 * returns would come back as one line with the first key swallowing every value after it.
	 * This is the one file shared with Iris and edited by hand, so both happen to it. The mark
	 * is taken off the bytes and not off the text, because the format's own charset has no
	 * notion of it: decoded as Latin-1 the three bytes come out as three letters, and a test on
	 * the character would pass them through onto the first key.
	 */
	private static Map<String, String> lines(Path file, Charset charset) throws IOException {
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}

		byte[] bytes = Files.readAllBytes(file);
		int start = bytes.length >= BYTE_ORDER_MARK.length
				&& Arrays.equals(bytes, 0, BYTE_ORDER_MARK.length, BYTE_ORDER_MARK, 0,
						BYTE_ORDER_MARK.length)
				? BYTE_ORDER_MARK.length
				: 0;
		String text = new String(bytes, start, bytes.length - start, charset);

		Map<String, String> values = new LinkedHashMap<>();
		for (String line : text.lines().toList()) {
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
	 * <p>
	 * Encoded through {@code String.getBytes}, which REPLACES a character this charset has no byte
	 * for, where {@code Files.write} of a list of lines throws on one. The carry-over reads the old
	 * file as UTF-8 and writes here as the format wants, so the two charsets do not agree on
	 * everything, and a value nobody can spell must not be able to fail a load for ever.
	 */
	public static void write(Path file, Stored stored) throws IOException {
		Path directory = file.getParent();
		if (directory != null) {
			Files.createDirectories(directory);
		}

		List<String> lines = new ArrayList<>(HEADER);
		stored.values().forEach((name, value) -> lines.add(name + "=" + value));

		Path temporary = file.resolveSibling(file.getFileName() + ".part");
		Files.write(temporary,
				String.join("\n", lines).concat("\n").getBytes(StandardCharsets.ISO_8859_1));
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
