package dev.vitrail.pack.menu;

import dev.vitrail.pack.option.OptionValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * What a setting is worth, in three layers that must stay apart.
 * <p>
 * Applied is what the pack was last built with. Pending is what the player has clicked and not
 * applied yet, and the difference between the two is the only thing telling them whether they
 * are looking at what they see or at what they are about to get. Forced is
 * {@code vitrail/options.txt}, which wins over both and cannot be edited here: it is the file
 * that makes a pass provable, and a click silently losing to it would be worse than a greyed
 * out widget.
 * <p>
 * The pending table holds only what was touched, never a copy of the file. That is what lets an
 * edit made by hand while the screen is open and an edit made in the screen compose instead of
 * overwriting each other.
 * <p>
 * A profile is not a layer and not a name held anywhere. Picking one queues every value it names,
 * which is what Iris does ({@code Iris.queueShaderPackOptionsFromProfile}), and the profile a
 * screen shows is read back out of the values. The one exception is a profile named in
 * {@code options.txt}: that one is a layer, under the pack's own file the way it is when the pack
 * is built, and like a forced value it decides what is drawn and never what is written.
 */
public final class MenuValues {

	/**
	 * The one line {@code vitrail/options.txt} keeps for a whole set of values rather than for one
	 * of them. Spelled out rather than borrowed from the settings package, which reads this one.
	 */
	private static final String PROFILE_KEY = "profile";

	private final PackMenu menu;

	private Map<String, String> saved;
	private Map<String, String> forced;

	private final Map<String, String> pending = new LinkedHashMap<>();

	private MenuValues(PackMenu menu, Map<String, String> saved, Map<String, String> forced) {
		this.menu = menu;
		this.saved = copy(saved);
		this.forced = copy(forced);
	}

	public static MenuValues of(PackMenu menu, Map<String, String> saved,
			Map<String, String> forced) {
		return new MenuValues(menu, saved, forced);
	}

	/**
	 * The same choices on a pack that was read again, menu included.
	 * <p>
	 * A menu is built from the pack's own source, so a reload can bring back a different one: a
	 * setting the pack no longer declares, one that turned from a toggle into a cycle, a page that
	 * moved. {@link #rebase} cannot answer for any of that, the menu being what says which layer a
	 * value belongs to and how it is spelled, so a reload has to build on the menu it just read
	 * rather than on the one the screen was holding.
	 * <p>
	 * What was clicked and not applied comes across, which is the difference between a reload and
	 * picking another pack: Cancel is the button that drops a pending value, and a reload the player
	 * asked for has no more reason to drop it than one the watcher noticed.
	 */
	public MenuValues reread(PackMenu menu, Map<String, String> saved, Map<String, String> forced) {
		MenuValues next = new MenuValues(menu, saved, forced);
		next.pending.putAll(this.pending);

		return next;
	}

	/** Replaces the base under the same menu, keeping whatever is pending. */
	public void rebase(Map<String, String> saved, Map<String, String> forced) {
		this.saved = copy(saved);
		this.forced = copy(forced);
	}

	public String applied(String name) {
		String value = this.forced.get(name);
		if (value == null) {
			value = this.saved.get(name);
		}
		if (value == null) {
			value = this.menu.profile(forcedProfile()).get(name);
		}

		return value == null ? packDefault(name) : value;
	}

	public String pending(String name) {
		String value = this.forced.get(name);

		return value == null ? unforced(name) : value;
	}

	public boolean modified(String name) {
		return !pending(name).equals(applied(name));
	}

	public boolean forced(String name) {
		return this.forced.containsKey(name);
	}

	/**
	 * How many settings {@code options.txt} holds down that a screen actually greys out: the ones
	 * some page places, plus the profile, which greys the selector.
	 * <p>
	 * The rest of that file is left out rather than counted with them. Some of its lines name this
	 * engine rather than the pack, which is {@code EngineOptions.RESERVED} without the profile the
	 * line above already counts, and any other name is a setting no page places, which is exactly
	 * what that file exists to reach. Neither kind has a widget, so counting them would send a
	 * player looking for greyed settings that were never drawn.
	 * <p>
	 * The profile goes the same way when the pack declares none: {@link PackMenu} drops the selector
	 * rather than drawing an empty one, and Sildur's declares none at all.
	 */
	public int forcedShown() {
		int count = 0;
		for (String name : this.forced.keySet()) {
			boolean shown = PROFILE_KEY.equals(name)
					? !this.menu.profileNames().isEmpty()
					: this.menu.option(name).isPresent();
			if (shown) {
				count++;
			}
		}

		return count;
	}

	/**
	 * A setting {@code vitrail/options.txt} forces still records the click. The widget is only
	 * inactive for as long as that file names it, and the choice made underneath comes back when
	 * the line goes away.
	 */
	public void queue(String name, String value) {
		this.pending.put(name, value);
	}

	/**
	 * Picks a profile by queueing every value it names, and keeping nothing else about it.
	 * <p>
	 * A profile is a set of values and nothing else. Iris queues them one by one and writes them out
	 * one by one, so a file written here carries the eight values BSL's ULTRA constrains rather than
	 * its name, and reads back as ULTRA under either engine. Fewer lines than that reach the file:
	 * only what differs from the pack's own defaults is written.
	 * <p>
	 * A value the player queued by hand before picking a profile is overwritten by it. That is the
	 * way round a player expects, the profile being the broader gesture of the two, and it stays
	 * correctable afterwards: picking the profile first and the setting second leaves the setting.
	 */
	public void queueProfile(String name) {
		this.pending.putAll(this.menu.profile(name));
	}

	/**
	 * Which profile the values on screen amount to, or the empty string when they amount to none of
	 * them and the screen has to say so itself.
	 * <p>
	 * Worked out from the values and never stored, which is Iris's rule
	 * ({@code ProfileSet.scan}): the honest answer to "which profile is this" is the one the values
	 * give. Storing the name instead makes a screen answer "none" the moment the file holding it is
	 * deleted, while the values on screen are exactly the ones a profile names, which is what
	 * pressing Reset used to look like.
	 * <p>
	 * The most constrained profile wins, as it does there, because one profile is usually another
	 * plus a setting or two and the looser of the pair would otherwise answer for both. Two profiles
	 * that name the same count and both match are settled by the order the pack declared them, which
	 * is what the sort behind {@code PackMenu.order} keeps; no pack of the corpus has such a pair.
	 */
	public String matchedProfile() {
		return match(this::pending);
	}

	private String match(UnaryOperator<String> layer) {
		String best = "";
		int constraints = -1;
		for (String name : this.menu.profileNames()) {
			Map<String, String> profile = this.menu.profile(name);
			if (profile.isEmpty() || profile.size() <= constraints || !matches(profile, layer)) {
				continue;
			}

			best = name;
			constraints = profile.size();
		}

		return best;
	}

	private boolean matches(Map<String, String> profile, UnaryOperator<String> layer) {
		for (Map.Entry<String, String> setting : profile.entrySet()) {
			if (!setting.getValue().equals(layer.apply(setting.getKey()))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * The profile in effect: the one {@code options.txt} names when it names one, and the one the
	 * pending values amount to otherwise. Forced like any other setting, and shown as forced for the
	 * same reason: a selector that let a click lose to that file in silence would be worse than a
	 * grey one.
	 */
	public String profile() {
		String over = this.forced.get(PROFILE_KEY);

		return over == null ? matchedProfile() : over;
	}

	/**
	 * The profile the pack was last built with, which is what the world on screen amounts to. A
	 * screen needs both this and {@link #profile()} to tell a chosen profile from an applied one,
	 * exactly as it does for a single setting.
	 */
	public String appliedProfile() {
		String over = this.forced.get(PROFILE_KEY);

		return over == null ? match(this::applied) : over;
	}

	/**
	 * Back to the value the pack ships, not to the value the profile or the file would give. That
	 * is what a shift click means, and it is also what makes the line disappear from the file
	 * rather than being written out with the default in it.
	 */
	public void reset(String name) {
		this.pending.put(name, packDefault(name));
	}

	public void clearPending() {
		this.pending.clear();
	}

	public int pendingCount() {
		int count = 0;
		for (String name : touched()) {
			if (modified(name)) {
				count++;
			}
		}

		return count;
	}

	/**
	 * Only what differs from what the pack itself declares, which is what gets written and what Iris
	 * keeps ({@code MutableOptionValues.addAll} drops a value equal to the pack's default).
	 * <p>
	 * A profile's values are in here one by one like any other, since picking one queued them.
	 */
	public Map<String, String> toSave() {
		Map<String, String> result = new LinkedHashMap<>();

		for (String name : touched()) {
			// Never write what options.txt forces. The day that line goes away the setting has to
			// come back to what was chosen underneath, not stay stuck at what was forced over it.
			if (this.forced.containsKey(name)) {
				String kept = this.saved.get(name);
				if (kept != null) {
					result.put(name, written(name, kept));
				}

				continue;
			}

			// What this side chose, resolved without the profile options.txt may be forcing: that
			// profile decides what is drawn and never what is written, exactly like a forced value.
			String value = chosen(name);
			// A name the pack does not declare has no default to compare against, so it is kept as
			// it was: dropping it loses a player's settings the day they go back to an older pack.
			if (!value.equals(packDefault(name))) {
				result.put(name, written(name, value));
			}
		}

		return result;
	}

	/** Everything but the forced layer, which is what a widget shows for an unforced setting. */
	private String unforced(String name) {
		String value = this.pending.get(name);
		if (value == null) {
			value = this.saved.get(name);
		}
		if (value == null) {
			value = this.menu.profile(forcedProfile()).get(name);
		}

		return value == null ? packDefault(name) : value;
	}

	/** The same without the profile options.txt forces, which is what reaches the file. */
	private String chosen(String name) {
		String value = this.pending.get(name);
		if (value == null) {
			value = this.saved.get(name);
		}

		return value == null ? packDefault(name) : value;
	}

	private String forcedProfile() {
		return this.forced.getOrDefault(PROFILE_KEY, "");
	}

	/** Every name any layer above the pack's own defaults has an opinion about. */
	private Set<String> touched() {
		Set<String> names = new LinkedHashSet<>(this.saved.keySet());
		names.addAll(this.pending.keySet());

		return names;
	}

	private String packDefault(String name) {
		return this.menu.option(name).map(MenuOption::defaultValue).orElse("");
	}

	/**
	 * Every layer read in the words a widget cycles through, so that the screen and the engine
	 * cannot disagree about the file they both read.
	 * <p>
	 * Iris spells a boolean {@code true} and {@code false}, and its files are read as they are.
	 * {@code SettingsLayers.resolve} takes them through {@link OptionValue#parse}, so the pack
	 * really is built with those toggles on, while a menu works in {@code on} and {@code off}
	 * because those are the two values a toggle offers. Handing the file's spelling straight to a
	 * widget draws every one of them the wrong way round, and silently: both layers carry the same
	 * word, so nothing is marked as waiting, and the first Apply copies that word into the file it
	 * came from.
	 * <p>
	 * This is the layer that has to do it rather than the reader, which serves the engine too and
	 * cannot tell a toggle from a cycle whose values happen to be those words. Here the pack has
	 * already said which is which.
	 */
	private Map<String, String> copy(Map<String, String> values) {
		Map<String, String> copied = new LinkedHashMap<>();
		values.forEach((name, value) -> copied.put(name, spelt(name, value)));

		return Collections.unmodifiableMap(copied);
	}

	/**
	 * Only a toggle is respelt. A name this pack no longer places is left exactly as it was
	 * written: it is kept rather than dropped, and rewriting a value nothing on screen can explain
	 * would edit a player's file on the strength of a guess.
	 */
	private String spelt(String name, String value) {
		return toggle(name) ? OptionValue.parse(value).asText() : value;
	}

	/**
	 * The way back out, for the file rather than for a widget. Iris writes a boolean as
	 * {@code Boolean.toString} ({@code Iris.java:348}) and its reader takes literally nothing else:
	 * {@code MutableOptionValues.addAll} calls any other spelling an invalid value and falls back on
	 * the pack's own default, so a toggle written {@code on} is a toggle lost.
	 */
	private String written(String name, String value) {
		return written(this.menu, name, value);
	}

	/**
	 * The same, for a caller that has a menu and no values yet. The settings carry-over is the one
	 * there is: it builds a file out of a profile's own values, which are held in the spelling a
	 * widget wants, and writing them as they stand would lose every toggle it moved.
	 */
	public static String written(PackMenu menu, String name, String value) {
		// Every value of a toggle goes through Boolean.toString, including one that is not one of
		// the four words, which happens where a new version of a pack turns a cycle into a toggle.
		// Copying such a value instead LOOKS kinder and is not: this engine would rewrite it as
		// {@code #define NAME Medium}, which leaves the toggle DEFINED and therefore on, while Iris
		// turns an unreadable spelling into the option's default. One shared file would then draw
		// two different images. Written false, both engines read false and both draw off.
		return menu.option(name).filter(option -> option.form() == MenuOption.Form.TOGGLE).isPresent()
				? Boolean.toString(OptionValue.parse(value).asBoolean())
				: value;
	}

	private boolean toggle(String name) {
		return this.menu.option(name)
				.filter(option -> option.form() == MenuOption.Form.TOGGLE)
				.isPresent();
	}
}
