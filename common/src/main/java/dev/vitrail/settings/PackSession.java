package dev.vitrail.settings;

import dev.vitrail.pack.menu.PackMenu;
import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.source.PackLang;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *
 * @param carried  what became of the file this engine kept before the settings moved, which the
 *                 caller reports and nothing else acts on. It is the only load where the values on
 *                 screen were somewhere else a moment ago
 * @param declared every name the pack declares, which is not the same question as what its menu
 *                 shows and is the one the layers are judged against: a chosen name that is not in
 *                 here changes nothing about the pack, so the load has to say it is being dropped
 */
public record PackSession(Path gameDirectory, Path packPath, String packFileName, PackMenu menu,
		Set<String> declared, SettingsFile.Carried carried, SettingsFile.Stored saved,
		Map<String, OptionValue> forced) {

	public PackSession {
		declared = Set.copyOf(declared);
	}

	public static PackSession read(Path gameDirectory, Path packPath, String languageCode)
			throws IOException {
		String packFileName = packPath.getFileName().toString();

		PackMenu menu;
		Set<String> declared;
		// One opening for both, the index being what the menu is built from anyway. Read here rather
		// than left to the loader further down because the layers are resolved before a program is
		// translated, and a value dropped has to be named at the moment it is dropped.
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			OptionIndex index = OptionIndex.build(source);
			declared = index.names();
			menu = PackMenu.build(source.packName(), index, ShaderProperties.parse(source),
					PackLang.read(source, languageCode));
		}

		// Before the reading and never after it: what it writes is what the reading then finds, so
		// there is one answer and not a first load that behaves unlike every later one.
		//
		// Caught here rather than left to the loader, and that is the point of catching it at all:
		// a shaderpacks folder the player cannot write to, or an old file another process is
		// holding, would otherwise take the whole pack down through the loader's own catch. What is
		// at stake is settings that are still on disk, so the pack draws with the pack's defaults
		// and the old file waits for the next load.
		//
		// Swallowed rather than reported, and the caller says it instead: nothing in this package
		// nor in pack/ IMPORTS a Minecraft API, which is what lets both be compiled and run against
		// the corpus without starting the game. One logger would end that.
		SettingsFile.Carried carried;
		try {
			carried = SettingsFile.migrate(gameDirectory, packFileName, menu);
		} catch (IOException e) {
			carried = new SettingsFile.Carried(SettingsFile.Carry.FAILED, "", SettingsFile.legacy(gameDirectory, packFileName));
		}

		return new PackSession(gameDirectory, packPath, packFileName, menu, declared, carried,
				SettingsFile.read(SettingsFile.of(gameDirectory, packFileName)),
				SettingsLayers.forced(gameDirectory));
	}

	/** Where the screen writes, which is where Iris reads: one file per pack, shared by both. */
	public Path settingsFile() {
		return SettingsFile.of(this.gameDirectory, this.packFileName);
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
	 * pack is built with, because the authority on what a pack declares is {@link #declared} and not
	 * a menu: a setting can be declared and simply no longer be on a page, and dropping a value here
	 * on the weaker evidence would cost it. A name neither of the two knows reaches the pack's
	 * source and finds no declaration to rewrite, which is where it stops.
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
