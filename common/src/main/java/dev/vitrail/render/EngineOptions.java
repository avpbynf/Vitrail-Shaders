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
 * would be offered to the pack as a setting of its own: where the pack happens to declare that word,
 * {@code screen=settings} would rewrite its declaration and this engine would lose the line as well.
 * None of the thirteen collides with a setting any pack of the corpus declares, and what keeps that
 * true is thinner than it looks: several of these words really are declared by packs,
 * {@code CLOUDS}, {@code WEATHER}, {@code ENTITIES} and {@code SHADOW} among them. What holds them
 * apart is the case alone - the names here are lowercase and the comparison is not - so a word added
 * here in the spelling a pack uses would be swallowed before the pack ever saw it. No count is given
 * on purpose: one taken by hand goes stale at the next pack read, and nothing here measures it.
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
	 * block entity half carrying those same names under a {@code block_} in front and the two hand
	 * passes under a {@code hand_} and a {@code hand_water_}. The weather is
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
	 * Draws the game's own entity geometry with the pack's own program. On, like the rest of them.
	 * <p>
	 * <strong>It was off, and what turned it on is the rule rather than the gap closing.</strong>
	 * Every line here that is supported is on, and only a disabling is explicit: a line at off names
	 * work not done, and once a family is drawn and judged in game, leaving it behind a setting makes
	 * the picture a reader sees on cloning a picture nobody ships.
	 * <p>
	 * <strong>What it still costs is real and is named rather than counted.</strong> The normal is
	 * carried, {@code DefaultVertexFormat.ENTITY} holding one and the prologue publishing it. What a
	 * pack reads as a constant rather than as this entity's own are the identifiers: {@code mc_Entity},
	 * which an entity mesh has no room to carry and the prologue answers with a constant, and the three
	 * {@link dev.vitrail.uniform.UniformGaps} holds still, {@code entityId}, {@code blockEntityId} and
	 * {@code currentRenderedItemId}. A pack that branches
	 * on any of them takes the same branch for every draw, which reads on screen as anything from a
	 * mob fogged like water to a mob in a colour that belongs nowhere. {@code entityColor} stood
	 * beside them and has left: it is made from the overlay the mesh really carries, so a hurt mob
	 * flashes again. The log names every one of them at each load, and closing it is a lot of its own.
	 * <p>
	 * Iris routes the same geometry with nothing to switch,
	 * {@code shaderpack/loading/ProgramId.java:40-41}, so this line is no longer a divergence from it
	 * at the default; it stays a line because turning a family off in one word is what tells a wrong
	 * picture from a wrong family, without a rebuild.
	 */
	private static final String ENTITIES_KEY = "entities";

	/**
	 * Takes the player's own hand out of the game's late call and draws it inside the level, with the
	 * pack's own {@code gbuffers_hand} and {@code gbuffers_hand_water}. On, with the entities.
	 * <p>
	 * It was off for the entities' reason and for one of its own. The entities' reason is above and
	 * has not changed: the hand comes in by the same door and through the same vertex format, so the
	 * identifiers above reach a pack as constants here too, {@code currentRenderedItemId} among them,
	 * which is the one that names what is being held. Its own was that the half that blends served the
	 * ARM alone, and that one is spent: the entities' blending rows landed, every row of that table
	 * has a hand twin ({@link EntityDraw}), so a translucent block held in hand goes through the water
	 * pass with the arm and both are the pack's. What still goes back to the game there goes back
	 * everywhere else too, the rows naming the item entity target under the game's improved
	 * transparency.
	 * <p>
	 * <strong>It carries the position as well as the shading, and that is what changed.</strong> The
	 * solid half is drawn where the reference draws it, after the game's own opaque features and
	 * before the deferred stage, with the depth taken past it first; the blending half goes in ahead
	 * of the chain rather than after it, so what it draws is in the picture the composites read. Off,
	 * the hand goes back to the game's late call, which is after the whole chain has finished: painted
	 * over an image the pack had already composed, absent from every gbuffer and from every depth a
	 * composite reads.
	 */
	private static final String HAND_KEY = "hand";

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
	 * All fourteen, for the one place that has to tell them from a setting of the pack: the log that
	 * says what the file forces. {@code profile} is the settings layer's own, since that is the side
	 * that writes it back; the other thirteen are read here and nowhere else.
	 */
	private static final Set<String> RESERVED = Set.of(SettingsFile.PROFILE_KEY, SEED_KEY,
			PASSES_KEY, SCREEN_KEY, DUMP_KEY, TERRAIN_KEY, CHAIN_KEY, SHADOW_KEY, SKY_KEY,
			ENTITIES_KEY, HAND_KEY, CLOUDS_KEY, WEATHER_KEY, PARTICLES_KEY);

	private EngineOptions() {
	}

	/**
	 * What the thirteen lines this class reads were set to.
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
	 * @param hand       whether the player's own hand is taken out of the game's late call and drawn
	 *                   inside the level with the pack's own two hand programs
	 * @param clouds     whether the game's clouds are drawn with the pack's own program, and with
	 *                   that whether the pack's own {@code clouds} directive is honoured at all
	 * @param weather    whether the game's rain and snow are drawn with the pack's own program
	 * @param particles  whether the game's quad particles are, both halves of them
	 */
	record Read(boolean seed, ChainFilter passes, boolean packsFirst, String dump, boolean terrain,
			boolean chain, boolean shadow, boolean sky, boolean entities, boolean hand,
			boolean clouds, boolean weather, boolean particles) {

		/**
		 * The four of these the chain plan has to be handed, because its verdicts count a target as
		 * already written and would otherwise count it off a default nobody wrote.
		 * <p>
		 * Four and not thirteen: what the plan is asked is which targets are filled in EVERY place
		 * it is built for, and the sky, the clouds and the weather are not drawn in every place
		 * however their line reads, so their line cannot move that answer, and neither can the
		 * {@code hand}'s, its family being drawn only where a player holds one, which is the frame's
		 * camera and not the place. {@code chain} and
		 * {@code passes} cannot either, for the opposite reason: they take away the passes those
		 * lines are about, so with them there is no frame left for a note to describe. {@code shadow}
		 * draws geometry too, but into the shadow map's own targets, which the plan does not hold,
		 * so no verdict can count it either way; and {@code screen} and {@code dump} draw nothing at
		 * all.
		 */
		ChainPlan.Families families() {
			return new ChainPlan.Families(this.terrain, this.entities, this.particles, this.seed);
		}
	}

	/**
	 * Reads the thirteen and <strong>removes them</strong> from what is handed to the pack, which is
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
				asked(chosen.remove(ENTITIES_KEY), ENTITIES_KEY, true),
				asked(chosen.remove(HAND_KEY), HAND_KEY, true),
				asked(chosen.remove(CLOUDS_KEY), CLOUDS_KEY, true),
				asked(chosen.remove(WEATHER_KEY), WEATHER_KEY, true),
				asked(chosen.remove(PARTICLES_KEY), PARTICLES_KEY, true));
	}

	/**
	 * What {@code options.txt} holds, split in three. The reserved lines are counted apart rather
	 * than with the rest: a line calling {@code passes} a setting of the pack would send whoever
	 * reads the log looking through the pack for a setting it never had.
	 * <p>
	 * The third are the lines that are neither: a word this engine does not answer for and that the
	 * loaded pack declares nowhere either. They force nothing, so they are said one by one and named
	 * rather than counted with the settings that do. A count that included them would be the log's
	 * own version of the fault, promising a setting was forced when the pack has no such setting to
	 * force, and a name is what a typo is fixed from.
	 */
	static void announceForced(Path gameDirectory, PackSession opened) {
		Path file = SettingsLayers.file(gameDirectory);
		List<String> settings = opened.forced().keySet().stream()
				.filter(name -> !RESERVED.contains(name) && opened.declared().contains(name))
				.toList();
		List<String> reserved = opened.forced().keySet().stream()
				.filter(RESERVED::contains)
				.toList();
		List<String> unknown = opened.forced().keySet().stream()
				.filter(name -> !RESERVED.contains(name) && !opened.declared().contains(name))
				.toList();

		if (!settings.isEmpty()) {
			Vitrail.logger().info("Forcing {} pack settings from {}: {}", settings.size(), file,
					settings);
		}

		if (!reserved.isEmpty()) {
			Vitrail.logger().info("{} lines of {} name this engine rather than a setting of the "
					+ "pack: {}", reserved.size(), file, reserved);
		}

		unknown.forEach(name -> Vitrail.logger().warn("'{}={}' in {} names neither a line this engine"
				+ " reads nor a setting {} declares, so it forces nothing and the pack keeps its own"
				+ " defaults. A setting is applied where the pack declares it, and this one has no"
				+ " declaration to apply it to", name, opened.forced().get(name).asText(), file,
				opened.packFileName()));
	}

	/**
	 * Said when the entities are off, which is now the clouds' case rather than its own: this line is
	 * on by default, so reaching here means somebody wrote {@code entities=off} and knows they did.
	 * <p>
	 * It is said all the same, and for the reason the clouds' line is: what off costs here does not
	 * announce itself on the screen the way the terrain's does. A mob keeps being drawn, lit and tone
	 * mapped by the game and carried in flat by the scene seed, which reads as a pack that lights mobs
	 * oddly rather than as a family nobody served.
	 */
	static void announceEntitiesOff(Path gameDirectory) {
		Vitrail.logger().info("{}=off, so the game draws its own entities and the scene seed carries "
				+ "them in, already lit and already tone mapped. The glint an enchantment puts over "
				+ "what they hold or wear goes back with them, this switch carrying the two halves of "
				+ "it that are drawn in the world. Remove that line from {} to have the pack draw "
				+ "them, which is the default", ENTITIES_KEY, SettingsLayers.file(gameDirectory));
	}

	/**
	 * The same for the hand, and the same case: on by default, so this line is a thing somebody wrote.
	 * <p>
	 * What it costs is worth two sentences rather than one, because half of it is not a shader. Off,
	 * the hand is drawn where the game draws it, which is after the pack's whole chain has run: it is
	 * not merely lit by the game, it is painted over an image the pack has already finished, so no
	 * depth of it reaches a composite and nothing the pack does to the world reaches it.
	 */
	static void announceHandOff(Path gameDirectory) {
		Vitrail.logger().info("{}=off, so the player's own hand stays where the game draws it, after "
				+ "the pack's chain: painted over the finished image by the game's own shader, absent "
				+ "from every gbuffer and from every depth a composite reads, and the glint over what "
				+ "it holds with it. Remove that line from {} to have it drawn inside the level with "
				+ "the pack's two hand programs, which is the default", HAND_KEY,
				SettingsLayers.file(gameDirectory));
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

		// Named, like the two readings above do it: ten lines share this one, so the value on its
		// own leaves whoever fixes the typo looking for which of them carries it.
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
