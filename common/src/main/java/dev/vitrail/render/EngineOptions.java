package dev.vitrail.render;

import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.settings.PackSession;
import dev.vitrail.settings.SettingsFile;
import dev.vitrail.settings.SettingsLayers;
import dev.vitrail.Vitrail;

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
 * is a plausible identifier in somebody's GLSL. None of the ten collides with a setting any pack
 * of the corpus declares, and one of them very nearly does: BSL and Reverie both declare a
 * {@code CLOUDS} of their own, which misses {@code clouds} by its case alone. The names here are
 * lowercase and the comparison is not, so the two stay apart; a line of this file spelled in capitals
 * would eat a setting of theirs.
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
	 * Takes the decoded dump: {@code dump=composite5}, or the whole path, or {@code dump=final}, or
	 * one of {@code solid}, {@code cutout} and {@code translucent} for a chunk pass, two of which are
	 * usually served by the one file and could not otherwise be told apart. The sky answers the same
	 * way, by element rather than by file: {@code disc}, {@code dark}, {@code stars},
	 * {@code sunrise}, {@code sun} and {@code moon}, four of the six being one file. The entities
	 * answer the same way, {@code cutout_cull}, {@code armor}, {@code item} and the rest, with the
	 * block entity half carrying those same names under a {@code block_} in front. A whole half is
	 * reached by the name it asks the pack for, {@code dump=gbuffers_entities} or
	 * {@code dump=gbuffers_block}, <strong>only where the pack really ships that file</strong>: the
	 * line is matched against the file that ends up SERVING, so wherever the fallback tree leads
	 * elsewhere the name matches nothing at all and the element names are the only way in.
	 * <strong>Two of their element names cannot be reached at
	 * all</strong>, and it is a property of the matching rather than a fault: the line is matched on
	 * the TAIL of a label, the terrain is walked first, and its own passes are called {@code solid}
	 * and {@code cutout}. Whichever is named, the file the dump writes says in its first line which
	 * program was really read.
	 * <p>
	 * One program and not several, because the point is to read the file rather than to search it,
	 * and because what two programs of one frame are handed differs in three values that the dump
	 * cannot show apart anyway, which {@link PackDump#take} spells out.
	 * <p>
	 * It is the instrument the milestones are verified with: a value can be non zero, plausible and
	 * wrong, and the only cheap way to tell is to read the number.
	 */
	static final String DUMP_KEY = "dump";

	/**
	 * Draws the pack's own terrain program over Sodium's chunk mesh. On, like every other line here:
	 * a pack that does not light the world it is loaded for is not the pack the player picked.
	 * <p>
	 * It was off while it was being built, because it takes over the game's own geometry shader and
	 * everything before milestone six was verified without it. Turning it off is now what it is for:
	 * telling a wrong gbuffer from a wrong composite, in one line and without a rebuild.
	 */
	private static final String TERRAIN_KEY = "terrain";

	/**
	 * Draws the world a second time from the light, into the pack's shadow map. On, and worth
	 * nothing without {@code terrain}: the map is filled by the pack's own shadow program or it is
	 * not filled at all.
	 * <p>
	 * It is a line of its own rather than part of {@code terrain} because it costs a second pass
	 * over the whole terrain and because it is the one thing that can be turned off to tell a wrong
	 * shadow from a wrong gbuffer.
	 */
	private static final String SHADOW_KEY = "shadow";

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
	 * Draws the game's sky with the pack's own program. On, like the rest.
	 * <p>
	 * A line of its own for the same reason {@code shadow} is one: it is the one thing that can be
	 * turned off to tell a sky the pack drew from a sky the seed carried in, in one line and without
	 * a rebuild.
	 */
	private static final String SKY_KEY = "sky";

	/**
	 * Draws the game's own entity geometry with the pack's own program. <strong>Off</strong>, alone
	 * among these, and it is the convention every family still to come lands under.
	 * <p>
	 * What being off buys is that the work lands without waiting to be judged: it is turned on to
	 * look at it, turned off to compare, and a defect between the two is bisected in one line of a
	 * text file instead of a rebuild. Every other line here is on because what it names has been
	 * looked at in the game and kept.
	 */
	private static final String ENTITIES_KEY = "entities";

	/**
	 * Draws the game's clouds with the pack's own program. <strong>Off</strong>, under the same
	 * convention {@code entities} lands under and for the same reason.
	 * <p>
	 * It carries one thing the others do not: with it off, the {@code clouds} line of the pack's own
	 * {@code shaders.properties} is not honoured either, since that word only means anything where
	 * there is a program of ours behind it.
	 */
	private static final String CLOUDS_KEY = "clouds";

	/**
	 * All eleven, for the one place that has to tell them from a setting of the pack: the log that
	 * says what the file forces. {@code profile} is the settings layer's own, since that is the side
	 * that writes it back; the other ten are read here and nowhere else.
	 */
	private static final Set<String> RESERVED = Set.of(SettingsFile.PROFILE_KEY, SEED_KEY,
			PASSES_KEY, SCREEN_KEY, DUMP_KEY, TERRAIN_KEY, CHAIN_KEY, SHADOW_KEY, SKY_KEY,
			ENTITIES_KEY, CLOUDS_KEY);

	private EngineOptions() {
	}

	/**
	 * What the ten lines this class reads were set to.
	 *
	 * @param seed       whether the game's finished frame is painted where the world would be
	 * @param passes     what the user asked to run on top of what the pack keeps
	 * @param packsFirst whether the settings screen opens on the pack list rather than on the pack
	 * @param dump       the program the decoded dump names, lowercased, or empty
	 * @param terrain    whether a pack's terrain program takes over the opaque chunk pass
	 * @param chain      whether the composite chain and the {@code final} draw at all
	 * @param shadow     whether the world is drawn a second time from the light
	 * @param sky        whether the game's sky is drawn with the pack's own program
	 * @param entities   whether the game's opaque entity geometry is drawn with the pack's own
	 *                   program rather than the game's
	 * @param clouds     whether the game's clouds are drawn with the pack's own program, and with
	 *                   that whether the pack's own {@code clouds} directive is honoured at all
	 */
	record Read(boolean seed, ChainFilter passes, boolean packsFirst, String dump, boolean terrain,
			boolean chain, boolean shadow, boolean sky, boolean entities, boolean clouds) {
	}

	/**
	 * Reads the ten and <strong>removes them</strong> from what is handed to the pack, which is
	 * the point: what is left is settings the pack declared.
	 */
	static Read take(Map<String, OptionValue> chosen) {
		// The seed goes through the same reading as the other four rather than keeping one of its
		// own. It had one, and it was the only line here that took an unreadable word in silence:
		// seed=0 left the seed drawing, and an experiment run to see the clears on their own would
		// have concluded from a picture the seed had painted.
		return new Read(asked(chosen.remove(SEED_KEY), SEED_KEY, true),
				filterOf(chosen.remove(PASSES_KEY)),
				packsFirst(chosen.remove(SCREEN_KEY)),
				named(chosen.remove(DUMP_KEY)),
				asked(chosen.remove(TERRAIN_KEY), TERRAIN_KEY, true),
				asked(chosen.remove(CHAIN_KEY), CHAIN_KEY, true),
				asked(chosen.remove(SHADOW_KEY), SHADOW_KEY, true),
				asked(chosen.remove(SKY_KEY), SKY_KEY, true),
				asked(chosen.remove(ENTITIES_KEY), ENTITIES_KEY, false),
				asked(chosen.remove(CLOUDS_KEY), CLOUDS_KEY, false));
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

	/**
	 * Said once when the entities are off, which is the default, because nothing else would say the
	 * line exists.
	 * <p>
	 * The five other lines that take a yes or a no are on unless somebody asks, so their line is a
	 * thing the reader wrote and knows about. This one is the opposite: the picture with it off is
	 * the picture without this mod
	 * having heard of entities at all, and a reader who never sees the name has no reason to look for
	 * it.
	 */
	static void announceEntitiesOff(Path gameDirectory) {
		Vitrail.logger().info("{}=off, so the game draws its own entities and the scene seed carries "
				+ "them in, already lit and already tone mapped. Write '{}=on' in {} to have the pack "
				+ "draw the opaque ones", ENTITIES_KEY, ENTITIES_KEY,
				SettingsLayers.file(gameDirectory));
	}

	/**
	 * The same for the clouds, and it says one thing more than its neighbour: the pack's own
	 * {@code clouds} directive hangs off this line, so a reader wondering why a pack that writes
	 * {@code clouds=fancy} is drawing flat ones finds the answer here.
	 */
	static void announceCloudsOff(Path gameDirectory) {
		Vitrail.logger().info("{}=off, so the game draws its own clouds and the full screen layer "
				+ "carries them in flat, and the pack's own clouds directive is left unread with them. "
				+ "Write '{}=on' in {} to have the pack draw them", CLOUDS_KEY, CLOUDS_KEY,
				SettingsLayers.file(gameDirectory));
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
	private static boolean asked(OptionValue value, String key, boolean byDefault) {
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

		// Named, like the two readings above do it: seven lines share this one, so the value on its
		// own leaves whoever fixes the typo looking for which of the seven carries it.
		Vitrail.logger().warn("'{}={}' is neither on nor off, so this line is ignored and {} stays "
				+ "{}", key, value.asText(), key, byDefault ? "on" : "off");

		return byDefault;
	}

	/** The program the dump line names, lowercased, or empty when the line is missing. */
	private static String named(OptionValue value) {
		// asText rather than text, for the same reason as above: dump=1 parses as a boolean.
		return value == null ? "" : value.asText().trim().toLowerCase(Locale.ROOT);
	}
}
