package dev.vitrail.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * What {@code vitrail/pack.txt} carries: which pack was chosen, and whether shaders are on at all.
 * <p>
 * <b>Two facts and not one, and that is what the screen's toggle needs.</b> Iris keeps
 * {@code shaderPack} and {@code enableShaders} apart in its own properties file, so turning shaders
 * off there leaves the pack chosen and turning them back on returns to it. A single line cannot do
 * that: the moment {@code none} is written, which pack the player had is gone, and coming back lands
 * on whatever the list happens to show first.
 * <p>
 * <b>The old spelling still reads.</b> A file whose content is one bare word with no {@code =} in it
 * is that word, which is every file this mod has written until now, and the bare word {@code none}
 * is shaders off with no pack remembered, which is what it has always meant. So a player who had
 * either keeps what they had, and nothing has to be migrated.
 * <p>
 * Nothing here touches Minecraft, which is the rule for this whole package: it is what lets the
 * settings be run against the pack corpus without starting the game.
 */
public record PackFile(String name, boolean enabled) {

	/** The word that means no pack rather than the name of one, kept from the one line format. */
	public static final String NONE = "none";

	private static final String NAME_KEY = "pack";
	private static final String ENABLED_KEY = "enabled";

	/** Nothing chosen. Shaders are on, so choosing a pack is all it takes to draw one. */
	public static final PackFile EMPTY = new PackFile("", true);

	public PackFile {
		name = name.trim();
	}

	/**
	 * What the file says, or {@link #EMPTY} when it is not there. A line that is neither a comment nor
	 * one of the two keys is ignored rather than refused: this file is edited by hand.
	 * <p>
	 * Handed the file rather than the game directory, so that where it lives stays the render layer's
	 * business: this package names no folder of the installation and imports nothing that does.
	 */
	public static PackFile read(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return EMPTY;
		}

		String content = Files.readString(file, StandardCharsets.UTF_8);
		// The one line format, which is what every file written before this class looks like. Told
		// apart by having no key at all rather than by counting lines, so that a stray blank line or a
		// trailing newline does not change which format a file is read as.
		if (!content.contains("=")) {
			String bare = content.trim();

			return NONE.equalsIgnoreCase(bare) ? new PackFile("", false) : new PackFile(bare, true);
		}

		String name = "";
		boolean enabled = true;
		for (String line : content.lines().toList()) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}

			int split = trimmed.indexOf('=');
			if (split < 0) {
				continue;
			}

			String key = trimmed.substring(0, split).trim().toLowerCase(Locale.ROOT);
			String value = trimmed.substring(split + 1).trim();
			switch (key) {
				case NAME_KEY -> name = value;
				// Anything that is not the word for true is false, which is how the pack format's own
				// booleans read and what keeps a typo from turning shaders on by accident.
				case ENABLED_KEY -> enabled = Boolean.parseBoolean(value);
				default -> {
				}
			}
		}

		return new PackFile(name, enabled);
	}

	/**
	 * Writes both keys, always, so that the file a player opens says what state it is in rather than
	 * leaving one of the two to be inferred from its absence.
	 * <p>
	 * In LF and without a byte order mark, like every other file this mod writes.
	 */
	public static void write(Path file, PackFile chosen) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file,
				NAME_KEY + "=" + chosen.name() + "\n"
						+ ENABLED_KEY + "=" + chosen.enabled() + "\n",
				StandardCharsets.UTF_8);
	}

	/**
	 * Whether there is anything to look for: shaders on, and a name at all.
	 * <p>
	 * Deliberately not a test for {@link #NONE}, which belongs where the name is matched against the
	 * folder rather than here: that word is read after the whole names and before the fragments, so
	 * that a folder really called {@code none} stays reachable. Testing it here would take that
	 * folder away.
	 */
	public boolean wantsPack() {
		return this.enabled && !this.name.isEmpty();
	}

	/** Whether the name is the word that means no pack rather than the name of one. */
	public boolean namesNone() {
		return NONE.equalsIgnoreCase(this.name);
	}

	public PackFile withName(String name) {
		return new PackFile(name, this.enabled);
	}

	public PackFile withEnabled(boolean enabled) {
		return new PackFile(this.name, enabled);
	}
}
