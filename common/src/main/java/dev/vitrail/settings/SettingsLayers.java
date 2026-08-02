package dev.vitrail.settings;

import dev.vitrail.pack.OptionValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The order settings are resolved in, from the bottom: what the pack declares, then the profile it
 * names, then the pack's own file, then {@code vitrail/options.txt} over everything.
 * <p>
 * The last layer is deliberately last and deliberately global. It proves a pass runs, it may name
 * a setting no pack declares, and it is edited by hand while the game runs. What it holds is
 * reported so that the screen can grey those settings out instead of letting a click lose to them
 * in silence.
 * <p>
 * Only the top two layers are resolved here. The two below belong to the pack: a value nobody
 * chose is the one written in its source, and a profile is expanded by
 * {@code ShaderProperties.expandProfile} where the chain it may be part of can be followed.
 */
public final class SettingsLayers {

	/** The mod's own folder, spelled out for the reason given in {@link SettingsFile}. */
	private static final String DIRECTORY = "vitrail";

	private static final String OPTIONS = "options.txt";

	private SettingsLayers() {
	}

	/** Named so that a caller can say in its log where a forced setting came from. */
	public static Path file(Path gameDirectory) {
		return gameDirectory.resolve(DIRECTORY).resolve(OPTIONS);
	}

	/**
	 * Reads {@code vitrail/options.txt}, one {@code NAME=value} per line, the reserved
	 * {@code profile} key included.
	 * <p>
	 * This file exists to make a pass provable. A pack's {@code final} is often nearly an identity
	 * with its settings at their defaults, which looks exactly like a pass that never ran; turning
	 * on one of the pack's own features settles it without touching the pack or writing a test
	 * shader that proves only itself. That is why it stays a file edited by hand, why it applies to
	 * whatever pack is loaded, and why it wins over the screen rather than the other way round.
	 */
	public static Map<String, OptionValue> forced(Path gameDirectory) throws IOException {
		Path file = file(gameDirectory);
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}

		Map<String, OptionValue> chosen = new LinkedHashMap<>();
		for (String line : Files.readAllLines(file)) {
			String trimmed = line.trim();
			int equals = trimmed.indexOf('=');
			if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 1) {
				continue;
			}

			chosen.put(trimmed.substring(0, equals).trim(),
					OptionValue.parse(trimmed.substring(equals + 1).trim()));
		}

		return Collections.unmodifiableMap(chosen);
	}

	/** The user layer, the only one this side owns: the pack's file with the forced one on top. */
	public static Resolved resolve(SettingsFile.Stored saved, Map<String, OptionValue> forced) {
		Map<String, OptionValue> chosen = new LinkedHashMap<>();
		saved.values().forEach((name, value) -> chosen.put(name, OptionValue.parse(value)));

		String profile = saved.profile();
		Set<String> forcedNames = new LinkedHashSet<>(forced.keySet());
		for (Map.Entry<String, OptionValue> entry : forced.entrySet()) {
			if (SettingsFile.PROFILE_KEY.equals(entry.getKey())) {
				profile = entry.getValue().asText();
			} else {
				chosen.put(entry.getKey(), entry.getValue());
			}
		}

		return new Resolved(Collections.unmodifiableMap(chosen), profile,
				Collections.unmodifiableSet(forcedNames));
	}

	/**
	 * @param chosen      everything a pack is built with, the reserved key taken out of it
	 * @param profile     the profile to expand underneath, "" for none
	 * @param forcedNames what {@code options.txt} holds, the reserved key included, so that a
	 *                    screen greys the profile selector out too when the file names a profile
	 */
	public record Resolved(Map<String, OptionValue> chosen, String profile,
			Set<String> forcedNames) {
	}
}
