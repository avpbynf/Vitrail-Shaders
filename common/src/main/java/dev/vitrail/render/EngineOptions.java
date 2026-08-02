package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.ChainFilter;
import dev.vitrail.pack.OptionValue;
import dev.vitrail.settings.PackSession;
import dev.vitrail.settings.SettingsFile;
import dev.vitrail.settings.SettingsLayers;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The lines of {@code vitrail/options.txt} that name what this mod does rather than what the pack
 * declares.
 * <p>
 * They are kept together, and taken out of the pack's settings in one place, because a name left in
 * would be written into the head of every translated unit as {@code #define screen settings}, which
 * is a plausible identifier in somebody's GLSL. None of the seven collides with a setting any pack
 * of the corpus declares.
 * <p>
 * Every one of them answers a question the picture cannot: which passes ran, which half a target
 * was read from, whether the values a program was handed were the right numbers. A word that is
 * neither of the two a line takes keeps the default <em>and says so</em>, because a typo that
 * silently draws something else is the failure this whole file exists to rule out.
 */
final class EngineOptions {

	/**
	 * Turns the scene seed off. What is left is the seeded target holding its clear colour, which is
	 * what proves the clears work on their own.
	 */
	private static final String SEED_KEY = "seed";

	/**
	 * Cuts the chain down: {@code passes=0}, {@code passes=6}, or
	 * {@code passes=composite4,composite5}.
	 * <p>
	 * This is how a broken picture is bisected, and the only honest way to price the chain:
	 * {@code passes=0} is the final alone, the image every earlier milestone was measured on. The
	 * schedule is rebuilt on what it leaves, never trimmed afterwards.
	 */
	private static final String PASSES_KEY = "passes";

	/** Which of its two views the settings screen opens on. */
	private static final String SCREEN_KEY = "screen";

	/** The two words that line takes, the first being what it does when the line is missing. */
	private static final String ON_PACKS = "packs";
	private static final String ON_SETTINGS = "settings";

	/**
	 * Takes the decoded dump: {@code dump=composite5}, or the whole path, or {@code dump=final}. One
	 * program, because the point is to read the file rather than to search it, and because two
	 * programs of one frame are handed the same values anyway.
	 * <p>
	 * It is the instrument the milestones are verified with: a value can be non zero, plausible and
	 * wrong, and the only cheap way to tell is to read the number.
	 */
	static final String DUMP_KEY = "dump";

	/**
	 * Draws the pack's own terrain program over Sodium's chunk mesh. Off unless asked for, which is
	 * the opposite of every other line here and deliberately so: it takes over the game's own
	 * geometry shader, and everything before milestone six was verified without it.
	 */
	private static final String TERRAIN_KEY = "terrain";

	/**
	 * Stops the composite chain from drawing at all. Different from {@code passes=0}, which still
	 * draws the {@code final} over the whole screen.
	 * <p>
	 * It exists for one job. A terrain program writes the game's own target, and a {@code final}
	 * drawn afterwards would tone map it and hold whatever the seed put in colortex0: the picture
	 * would then be a judgement about lighting rather than about whether the geometry arrived.
	 */
	private static final String CHAIN_KEY = "chain";

	/**
	 * All seven, for the one place that has to tell them from a setting of the pack: the log that
	 * says what the file forces. {@code profile} is the settings layer's own, since that is the side
	 * that writes it back; the other six are read here and nowhere else.
	 */
	private static final Set<String> RESERVED = Set.of(SettingsFile.PROFILE_KEY, SEED_KEY,
			PASSES_KEY, SCREEN_KEY, DUMP_KEY, TERRAIN_KEY, CHAIN_KEY);

	private EngineOptions() {
	}

	/**
	 * What the six lines this class reads were set to.
	 *
	 * @param seed       whether the game's finished frame is painted where the world would be
	 * @param passes     what the user asked to run on top of what the pack keeps
	 * @param packsFirst whether the settings screen opens on the pack list rather than on the pack
	 * @param dump       the program the decoded dump names, lowercased, or empty
	 * @param terrain    whether a pack's terrain program takes over the opaque chunk pass
	 * @param chain      whether the composite chain and the {@code final} draw at all
	 */
	record Read(boolean seed, ChainFilter passes, boolean packsFirst, String dump, boolean terrain,
			boolean chain) {
	}

	/**
	 * Reads the seven and <strong>removes them</strong> from what is handed to the pack, which is
	 * the point: what is left is settings the pack declared.
	 */
	static Read take(Map<String, OptionValue> chosen) {
		OptionValue seed = chosen.remove(SEED_KEY);

		return new Read(seed == null || !seed.isBoolean() || seed.asBoolean(),
				filterOf(chosen.remove(PASSES_KEY)),
				packsFirst(chosen.remove(SCREEN_KEY)),
				named(chosen.remove(DUMP_KEY)),
				asked(chosen.remove(TERRAIN_KEY), false),
				asked(chosen.remove(CHAIN_KEY), true));
	}

	/**
	 * What {@code options.txt} holds, split in two. The reserved lines are counted apart rather than
	 * with the rest: a line calling {@code passes} a setting of the pack would send whoever reads
	 * the log looking through the pack for a setting it never had.
	 */
	static void announceForced(Path gameDirectory, PackSession opened) {
		List<String> settings = opened.forced().keySet().stream()
				.filter(name -> !RESERVED.contains(name))
				.toList();
		List<String> reserved = opened.forced().keySet().stream()
				.filter(RESERVED::contains)
				.toList();

		if (!settings.isEmpty()) {
			Vitrail.logger().info("Forcing {} pack settings from {}: {}", settings.size(),
					SettingsLayers.file(gameDirectory), settings);
		}

		if (!reserved.isEmpty()) {
			Vitrail.logger().info("{} lines of {} name this engine rather than a setting of the "
					+ "pack: {}", reserved.size(), SettingsLayers.file(gameDirectory), reserved);
		}
	}

	/** Said once when the chain is off, since nothing else on screen would say why. */
	static void announceChainOff() {
		Vitrail.logger().info("{}=off, so nothing of the chain is drawn and the game keeps its own "
				+ "image. The pack is still read, which is what lets a terrain program be judged on "
				+ "its own", CHAIN_KEY);
	}

	/**
	 * A line of {@code passes=} is a count, a list of names, or a word. Anything else keeps the
	 * whole chain and is said so.
	 */
	private static ChainFilter filterOf(OptionValue value) {
		if (value == null) {
			return ChainFilter.ALL;
		}

		// off means none of them, which is the final alone, and on means the lot. A count and a
		// list of names go through untouched.
		String text = value.isBoolean() ? (value.asBoolean() ? "" : "0") : value.text();
		ChainFilter filter = ChainFilter.parse(text);
		if (filter == ChainFilter.ALL && !text.isBlank()) {
			Vitrail.logger().warn("'{}={}' is neither a count nor a list of program names, so the "
					+ "whole chain runs", PASSES_KEY, text);
		} else if (filter != ChainFilter.ALL) {
			Vitrail.logger().info("Running only part of the chain, {}={}", PASSES_KEY, text);
		}

		return filter;
	}

	/**
	 * Which of its two views the settings screen opens on. A word that is neither opens the pack
	 * list and says so, rather than quietly opening the other one.
	 */
	private static boolean packsFirst(OptionValue value) {
		if (value == null) {
			return true;
		}

		// asText rather than text: a line written screen=on is a boolean, whose text is null.
		String text = value.asText().trim().toLowerCase(Locale.ROOT);
		if (ON_SETTINGS.equals(text)) {
			return false;
		}

		if (!ON_PACKS.equals(text)) {
			Vitrail.logger().warn("'{}={}' is neither {} nor {}, so the settings screen opens on the "
					+ "pack list", SCREEN_KEY, value.asText(), ON_PACKS, ON_SETTINGS);
		}

		return true;
	}

	/**
	 * A reserved line read as a yes or a no. A word that is neither keeps the default and says so,
	 * rather than being taken for the answer nobody wrote.
	 */
	private static boolean asked(OptionValue value, boolean byDefault) {
		if (value == null) {
			return byDefault;
		}

		if (value.isBoolean()) {
			return value.asBoolean();
		}

		String text = value.asText().trim().toLowerCase(Locale.ROOT);
		if (text.equals("on") || text.equals("true") || text.equals("1")) {
			return true;
		}

		if (text.equals("off") || text.equals("false") || text.equals("0")) {
			return false;
		}

		Vitrail.logger().warn("'{}' is neither on nor off, so this line is ignored", value.asText());

		return byDefault;
	}

	/** The program the dump line names, lowercased, or empty when the line is missing. */
	private static String named(OptionValue value) {
		// asText rather than text, for the same reason as above: dump=1 parses as a boolean.
		return value == null ? "" : value.asText().trim().toLowerCase(Locale.ROOT);
	}
}
