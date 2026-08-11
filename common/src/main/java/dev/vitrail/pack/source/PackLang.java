package dev.vitrail.pack.source;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The names a pack gives its own settings, from {@code shaders/lang/<code>.lang}.
 * <p>
 * Without this a screen shows the identifiers a pack uses in its GLSL, and BSL's sixty seven pages
 * read as three hundred and sixty six words in capitals. The file is a {@link Properties} table in
 * UTF-8, which is what packs are written against rather than what the format documents.
 * <p>
 * Nothing is injected into the game's own language table. Whoever displays these asks this class
 * and builds a literal string, which keeps two things true: a pack is free to write a per cent
 * sign in a suffix, and the section signs it puts in its own labels still colour the text.
 */
public final class PackLang {

	/** What a pack ships when it ships one file, and what to fall back on when it ships several. */
	private static final String FALLBACK_CODE = "en_us";

	private static final PackLang EMPTY = new PackLang("", Map.of());

	private final String file;
	private final Map<String, String> entries;

	private PackLang(String file, Map<String, String> entries) {
		this.file = file;
		this.entries = entries;
	}

	/**
	 * Reads the pack's own labels for the player's language.
	 *
	 * @param languageCode the player's language, lower case, for instance {@code fr_fr}. Falls back
	 *                     to {@code en_us}, then to nothing. Resolved through
	 *                     {@link ShaderPackSource#resolveInsideShaders(String)} and never through
	 *                     {@link ShaderPackSource#file(String)}: seven of the eight packs measured
	 *                     name the file {@code en_US.lang} and only the former matches without
	 *                     case, which inside a zip is the difference between every label and none.
	 */
	public static PackLang read(ShaderPackSource source, String languageCode) throws IOException {
		Optional<Path> found = find(source, languageCode);
		if (found.isEmpty()) {
			return EMPTY;
		}

		Path file = found.get();
		Properties properties = new Properties();
		try {
			// Read through the source rather than from the file, which is what caps the size, drops
			// a byte order mark and replaces anything that is not UTF-8 instead of failing.
			properties.load(new StringReader(String.join("\n", source.readLines(file))));
		} catch (IllegalArgumentException e) {
			// What Properties throws, rather than an IOException, on a broken \\u escape.
			throw new IOException(source.rel(file) + " is not readable as a language file", e);
		}

		Map<String, String> entries = new LinkedHashMap<>();
		properties.forEach((key, value) -> entries.put(key.toString(), value.toString()));

		Path name = file.getFileName();

		return new PackLang(name == null ? "" : name.toString(), Map.copyOf(entries));
	}

	private static Optional<Path> find(ShaderPackSource source, String languageCode) {
		String code = languageCode.toLowerCase(Locale.ROOT);
		Optional<Path> file = source.resolveInsideShaders("lang/" + code + ".lang");
		if (file.isPresent() || code.equals(FALLBACK_CODE)) {
			return file;
		}

		return source.resolveInsideShaders("lang/" + FALLBACK_CODE + ".lang");
	}

	public static PackLang empty() {
		return EMPTY;
	}

	/** The file that was read, which is the one found and not the one asked for, or "". */
	public String file() {
		return this.file;
	}

	public int size() {
		return this.entries.size();
	}

	/** {@code option.NAME}, or the raw name. */
	public String option(String name) {
		return this.entries.getOrDefault("option." + name, name);
	}

	/** {@code option.NAME.comment}. */
	public Optional<String> optionComment(String name) {
		return entry("option." + name + ".comment");
	}

	/**
	 * {@code prefix.NAME}, then either {@code value.NAME.VALUE} or the value itself followed by
	 * {@code suffix.NAME}.
	 * <p>
	 * A value the pack has a name for drops the suffix, which is not an oversight. Bliss suffixes
	 * LPV_NORMAL_STRENGTH with a per cent sign and calls its zero "OFF", so appending both would
	 * read "OFF%". This is what Iris does with the same table and therefore what the pack was
	 * written against.
	 */
	public String value(String name, String value) {
		String prefix = this.entries.getOrDefault("prefix." + name, "");
		String named = this.entries.get("value." + name + "." + value);
		if (named != null) {
			return prefix + named;
		}

		return prefix + value + this.entries.getOrDefault("suffix." + name, "");
	}

	/** {@code screen.ID}, or the raw id. It names both the page and the link leading to it. */
	public String page(String id) {
		return this.entries.getOrDefault("screen." + id, id);
	}

	/** {@code screen.ID.comment}. */
	public Optional<String> pageComment(String id) {
		return entry("screen." + id + ".comment");
	}

	/** {@code profile.NAME}, or the raw name. */
	public String profile(String name) {
		return this.entries.getOrDefault("profile." + name, name);
	}

	/** {@code profile.comment}, one comment for the selector rather than one per profile. */
	public Optional<String> profileComment() {
		return entry("profile.comment");
	}

	private Optional<String> entry(String key) {
		return Optional.ofNullable(this.entries.get(key));
	}
}
