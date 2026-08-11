package dev.vitrail.render;

import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.target.ChainPlan;
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
 * is a plausible identifier in somebody's GLSL. None of the twelve collides with a setting any pack
 * of the corpus declares, and what keeps that true is thinner than it looks: several of these words
 * really are declared by packs, {@code CLOUDS}, {@code WEATHER}, {@code ENTITIES} and
 * {@code SHADOW} among them. What holds them apart is the case alone - the names here are lowercase
 * and the comparison is not - so a word added here in the spelling a pack uses would be swallowed
 * before the pack ever saw it. No count is given on purpose: one taken by hand goes stale at the
 * next pack read, and nothing here measures it.
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
	 * block entity half carrying those same names under a {@code block_} in front. The weather is
	 * {@code weather} and {@code weather_depth}, the particles {@code particles} and
	 * {@code particles_translucent}. A whole half is
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
	 * among these.
	 * <p>
	 * <strong>It is not a convention and it is not a taste.</strong> It used to say it was the shape
	 * every family still to come would land under, and the clouds landed on instead. What a line at
	 * off really names is work not done, standing behind a setting: the file has to be able to stay
	 * empty, because what a reader sees on cloning is what this engine gets judged on.
	 * <p>
	 * What holds this one is one thing and it is not incompleteness. Entity geometry arrives with no
	 * normal and no material id, so a pack that classifies its pixels by material reads an entity as
	 * something else and can fog it as though it were water. That is a family damaging its
	 * neighbours rather than falling short, and it is the whole of the argument. The two other gaps -
	 * a colour target BSL allocates for a family nothing draws through, and the blending half with
	 * the player's own body in it - are visible and corrupt nothing.
	 * <p>
	 * The day the first of those lands, this line goes to on <em>and disappears</em>, along with
	 * everything that documents it. Leaving it behind would turn the debt into a preference, which
	 * is exactly how it got called a convention.
	 * <p>
	 * <strong>It is a divergence from Iris, and a CHOICE rather than a constraint</strong>, so it is
	 * written out in full rather than left to be discovered.
	 * <ul>
	 * <li><em>What Iris does</em>: it routes the game's entities to the pack's program with nothing
	 * to switch, {@code shaderpack/loading/ProgramId.java:40-41}, where {@code Entities} falls back
	 * on {@code TexturedLit} and {@code EntitiesTrans} falls back on {@code Entities}. There is no
	 * line of any file of its own that turns them off.</li>
	 * <li><em>What stops this engine matching it</em>: nothing of the API, and it has to be said
	 * plainly. {@code render/EntityDraw} draws them, and drawing them is one word in this file. What
	 * holds the word at off is the gap above, and the rule this engine works to: a family's line goes
	 * to on once it has been judged in game, and this one has not been. That is a decision about when
	 * to ship a family, not an obstacle.</li>
	 * <li><em>What it costs the image</em>: the game draws its entities with its own shader, already
	 * lit and already tone mapped, and the scene seed carries them into the pack's picture flat. A
	 * mob is then vanilla lit inside a pack lit world, it takes none of the pack's shading, and the
	 * pack's own entity program never runs.</li>
	 * </ul>
	 */
	private static final String ENTITIES_KEY = "entities";

	/**
	 * Draws the game's clouds with the pack's own program. On, like most of these.
	 * <p>
	 * <strong>It was off for one evening and that was one evening too long.</strong> A line of this
	 * file is a thing somebody wrote and knows about; a default is what everyone else gets. Off, the
	 * picture a reader sees on cloning is not the picture this engine was judged on, and every
	 * report about it is about a configuration nobody shipped.
	 * <p>
	 * It carries one thing the others do not: with it off, the {@code clouds} line of the pack's own
	 * {@code shaders.properties} is not honoured either, since that word only means anything where
	 * there is a program of ours behind it. So off costs more than a family: it costs six packs of
	 * the corpus the removal they asked for, and puts the game's own clouds back over the ones they
	 * draw themselves.
	 */
	private static final String CLOUDS_KEY = "clouds";

	/**
	 * Draws the game's rain and snow with the pack's own program. On, like most of these: what this
	 * engine can serve, it serves, and only taking a family back out is written down.
	 * <p>
	 * A line of its own and not part of the entities', though the two arrived together: the weather
	 * is the one family drawn WHOLLY after the deferred stage - the particles straddle it - so it is
	 * the one that can be turned off to tell a curtain the pack drew from a curtain the game drew,
	 * without touching anything the pack does before the deferreds.
	 */
	private static final String WEATHER_KEY = "weather";

	/**
	 * Draws the game's quad particles with the pack's own programs. On, like the weather it lands
	 * beside.
	 * <p>
	 * The one family whose two halves stand on opposite sides of the deferred stage, so what it turns
	 * off is two things at once and deliberately: a smoke plume before the stage and the same plume's
	 * translucent half after the world's water are one word to a player and would be two lines
	 * nobody could keep straight.
	 */
	private static final String PARTICLES_KEY = "particles";

	/**
	 * All thirteen, for the one place that has to tell them from a setting of the pack: the log that
	 * says what the file forces. {@code profile} is the settings layer's own, since that is the side
	 * that writes it back; the other twelve are read here and nowhere else.
	 */
	private static final Set<String> RESERVED = Set.of(SettingsFile.PROFILE_KEY, SEED_KEY,
			PASSES_KEY, SCREEN_KEY, DUMP_KEY, TERRAIN_KEY, CHAIN_KEY, SHADOW_KEY, SKY_KEY,
			ENTITIES_KEY, CLOUDS_KEY, WEATHER_KEY, PARTICLES_KEY);

	private EngineOptions() {
	}

	/**
	 * What the twelve lines this class reads were set to.
	 *
	 * @param seed       whether the game's finished frame is painted where the world would be
	 * @param passes     what the user asked to run on top of what the pack keeps
	 * @param packsFirst whether the settings screen opens on the pack list rather than on the pack
	 * @param dump       the program the decoded dump names, lowercased, or empty
	 * @param terrain    whether a pack's terrain program takes over the opaque chunk pass
	 * @param chain      whether the composite chain and the {@code final} draw at all
	 * @param shadow     whether the world is drawn a second time from the light
	 * @param sky        whether the game's sky is drawn with the pack's own program
	 * @param entities   whether the game's entity geometry is drawn with the pack's own program
	 *                   rather than the game's, both halves of it
	 * @param clouds     whether the game's clouds are drawn with the pack's own program, and with
	 *                   that whether the pack's own {@code clouds} directive is honoured at all
	 * @param weather    whether the game's rain and snow are drawn with the pack's own program
	 * @param particles  whether the game's quad particles are, both halves of them
	 */
	record Read(boolean seed, ChainFilter passes, boolean packsFirst, String dump, boolean terrain,
			boolean chain, boolean shadow, boolean sky, boolean entities, boolean clouds,
			boolean weather, boolean particles) {

		/**
		 * The four of these the chain plan has to be handed, because its verdicts count a target as
		 * already written and would otherwise count it off a default nobody wrote.
		 * <p>
		 * Four and not twelve: what the plan is asked is which targets are filled in EVERY place it is
		 * built for, and the sky, the clouds and the weather are not drawn in every place however
		 * their line reads, so their line cannot move that answer. {@code chain} and {@code passes}
		 * cannot either, for the opposite reason: they take away the passes those lines are about, so
		 * with them there is no frame left for a note to describe. {@code shadow} draws geometry too,
		 * but into the shadow map's own targets, which the plan does not hold, so no verdict can
		 * count it either way; and {@code screen} and {@code dump} draw nothing at all.
		 */
		ChainPlan.Families families() {
			return new ChainPlan.Families(this.terrain, this.entities, this.particles, this.seed);
		}
	}

	/**
	 * Reads the twelve and <strong>removes them</strong> from what is handed to the pack, which is
	 * the point: what is left is settings the pack declared.
	 */
	static Read take(Map<String, OptionValue> chosen) {
		// The seed goes through the same reading as the other eight rather than keeping one of its
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
				asked(chosen.remove(CLOUDS_KEY), CLOUDS_KEY, true),
				asked(chosen.remove(WEATHER_KEY), WEATHER_KEY, true),
				asked(chosen.remove(PARTICLES_KEY), PARTICLES_KEY, true));
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
	 * The eight other lines that take a yes or a no are on unless somebody asks, so their line is a
	 * thing the reader wrote and knows about. This one is the opposite: the picture with it off is
	 * the picture without this mod having heard of entities at all, and a reader who never sees the
	 * name has no reason to look for it.
	 */
	static void announceEntitiesOff(Path gameDirectory) {
		Vitrail.logger().info("{}=off, so the game draws its own entities and the scene seed carries "
				+ "them in, already lit and already tone mapped. Write '{}=on' in {} to have the pack "
				+ "draw them", ENTITIES_KEY, ENTITIES_KEY, SettingsLayers.file(gameDirectory));
	}

	/**
	 * The same for the clouds, and it is not the same case: this line is on by default, so reaching
	 * here means somebody wrote {@code clouds=off} and knows they did.
	 * <p>
	 * It is said all the same, and it is the one line of its kind. The others that are on say nothing
	 * when switched off, because what they cost is on the screen: turn the terrain off and the world
	 * is the game's. This one costs something the screen does not show, the pack's own
	 * {@code clouds} directive going unread, so a pack that draws its own clouds gets the game's back
	 * over them and nothing else would say why.
	 */
	static void announceCloudsOff(Path gameDirectory) {
		Vitrail.logger().info("{}=off in {}, so the game draws its own clouds and the full screen "
				+ "layer carries them in flat. The pack's own clouds directive goes unread with them, "
				+ "which for most of the corpus means the game's clouds are drawn over the ones the "
				+ "pack draws itself", CLOUDS_KEY, SettingsLayers.file(gameDirectory));
	}

	/**
	 * The same for the weather, and it is the clouds' case rather than the entities': this line is on
	 * by default too, so reaching here means somebody wrote {@code weather=off}.
	 * <p>
	 * Said all the same, and for the reason the clouds are. What it costs beyond the curtain's own
	 * shader does not show on the screen: the {@code weather} directive of the pack's own
	 * {@code shaders.properties} goes unread with it, since a pack refusing the game's curtain is
	 * making room for one of its own that nothing would then draw.
	 * <p>
	 * The particles have no line of this kind, and that is the rule rather than an omission: what
	 * {@code particles=off} costs is on the screen, and the one directive it silences,
	 * {@code particles.ordering}, names the placement this engine already performs.
	 */
	static void announceWeatherOff(Path gameDirectory) {
		Vitrail.logger().info("{}=off in {}, so the game draws its own rain and snow with its own "
				+ "shader, into its own target and lit as the game lights them. The pack's own "
				+ "weather directive goes unread with them, so a pack that draws its own curtain gets "
				+ "the game's back in front of it", WEATHER_KEY, SettingsLayers.file(gameDirectory));
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
		// By value, because the question is which chain was asked for and not which object came
		// back. No filter parse can build today is equal to ALL without being ALL, so this changes
		// nothing on screen; it stops depending on that. SettingsScreen answers the other way on
		// its own record, and says there why identity is what it needs.
		if (filter.equals(ChainFilter.ALL) && !text.isBlank()) {
			Vitrail.logger().warn("'{}={}' is neither a count nor a list of program names, so the "
					+ "whole chain runs", PASSES_KEY, text);
		} else if (!filter.equals(ChainFilter.ALL)) {
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

		// Named, like the two readings above do it: nine lines share this one, so the value on its
		// own leaves whoever fixes the typo looking for which of the nine carries it.
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
