package dev.vitrail.settings;

import dev.vitrail.pack.menu.PackMenu;
import dev.vitrail.pack.option.OptionValue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One reading of one pack: what is loaded right now, and what a screen may show and change about
 * it. Held by the render layer and handed to the screen, so that the screen shows the settings the
 * image was built from rather than a second reading that could disagree with it.
 * <p>
 * Nothing here is a snapshot the screen then works from on its own. A file edited by hand while
 * the screen is open reloads the pack, which builds a new session, and the screen puts what it
 * had pending back on top of it. That is why this is read again on every load and why it is
 * cheap to: twenty five to eighty eight milliseconds on the corpus, against the half second the
 * translation costs.
 */
public record PackSession(Path gameDirectory, Path packPath, String packFileName, PackMenu menu,
		SettingsFile.Stored saved, Map<String, OptionValue> forced) {

	public static PackSession read(Path gameDirectory, Path packPath, String languageCode)
			throws IOException {
		String packFileName = packPath.getFileName().toString();

		return new PackSession(gameDirectory, packPath, packFileName,
				PackMenu.read(packPath, languageCode),
				SettingsFile.read(SettingsFile.source(gameDirectory, packFileName)),
				SettingsLayers.forced(gameDirectory));
	}

	/** Where the screen writes. Never the file Iris left, even when that is what was read. */
	public Path settingsFile() {
		return SettingsFile.of(this.gameDirectory, this.packFileName);
	}

	/** What was read, which is {@link #settingsFile()} unless Iris's file stood in for it. */
	public Path readFrom() {
		return SettingsFile.source(this.gameDirectory, this.packFileName);
	}

	/** The pack's own file with {@code options.txt} over it, ready to build the pack with. */
	public SettingsLayers.Resolved settings() {
		return SettingsLayers.resolve(this.saved, this.forced);
	}

	/**
	 * Names the pack's own file carries that this pack's menu knows nothing about, for one line in
	 * the log.
	 * <p>
	 * They are reported and nothing more. They stay in the file, which is the point of one file per
	 * pack: a player who tries a new version of a pack and goes back finds their settings where
	 * they left them, rather than deleted the way Iris deletes them. They also stay in what the
	 * pack is built with, because the authority on what a pack declares is its
	 * {@code OptionIndex}, read further down by {@code SettingSet.headerDefines}, and a name that
	 * index does not know is written into the head of each unit exactly as a line of
	 * {@code options.txt} is. Dropping a value here on the weaker evidence of a menu would cost a
	 * setting the pack still declares but no longer puts on a page.
	 */
	public List<String> stale() {
		List<String> stale = new ArrayList<>();
		for (String name : this.saved.values().keySet()) {
			// A name in options.txt is deliberate, never stale: it is how a setting no pack
			// declares is forced in the first place.
			if (this.menu.option(name).isEmpty() && !this.forced.containsKey(name)) {
				stale.add(name);
			}
		}

		return List.copyOf(stale);
	}

	/** The forced values as the screen wants them, as text. */
	public Map<String, String> forcedText() {
		Map<String, String> text = new LinkedHashMap<>();
		this.forced.forEach((name, value) -> text.put(name, value.asText()));

		return Collections.unmodifiableMap(text);
	}
}
