package dev.vitrail.pack.menu;

import dev.vitrail.pack.option.OptionValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
 * A chosen profile sits above the pack's own file rather than under it, which is how Iris
 * behaves: picking a profile is meant to decide the settings it names, and it leaves the ones it
 * does not name exactly where they were. A profile named in {@code options.txt} takes the whole
 * selector over, the same way that file takes a single setting over, and like a forced value it
 * decides what is drawn and never what is written.
 */
public final class MenuValues {

	/**
	 * The one line either settings file keeps for a whole set of values rather than for one of
	 * them. Spelled out rather than borrowed from the settings package, which reads this one.
	 */
	private static final String PROFILE_KEY = "profile";

	private final PackMenu menu;

	private Map<String, String> saved;
	private String appliedProfile;
	private Map<String, String> forced;

	private final Map<String, String> pending = new LinkedHashMap<>();
	private String pendingProfile;
	private boolean profileChosen;

	private MenuValues(PackMenu menu, Map<String, String> saved, String savedProfile,
			Map<String, String> forced) {
		this.menu = menu;
		this.saved = copy(saved);
		this.appliedProfile = savedProfile == null ? "" : savedProfile;
		this.forced = copy(forced);
		this.pendingProfile = this.appliedProfile;
	}

	public static MenuValues of(PackMenu menu, Map<String, String> saved, String savedProfile,
			Map<String, String> forced) {
		return new MenuValues(menu, saved, savedProfile, forced);
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
	public MenuValues reread(PackMenu menu, Map<String, String> saved, String savedProfile,
			Map<String, String> forced) {
		MenuValues next = new MenuValues(menu, saved, savedProfile, forced);
		next.pending.putAll(this.pending);
		next.profileChosen = this.profileChosen;
		if (this.profileChosen) {
			next.pendingProfile = this.pendingProfile;
		}

		return next;
	}

	/** Replaces the base under the same menu, keeping whatever is pending. */
	public void rebase(Map<String, String> saved, String savedProfile, Map<String, String> forced) {
		this.saved = copy(saved);
		this.appliedProfile = savedProfile == null ? "" : savedProfile;
		this.forced = copy(forced);

		if (!this.profileChosen) {
			this.pendingProfile = this.appliedProfile;
		}
	}

	public String applied(String name) {
		String value = this.forced.get(name);
		if (value == null) {
			value = this.saved.get(name);
		}
		if (value == null) {
			value = this.menu.profile(appliedProfile()).get(name);
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
	 * The rest of that file is left out rather than counted with them. Four of its lines name this
	 * engine rather than the pack, and any other name is a setting no page places, which is exactly
	 * what that file exists to reach. Neither kind has a widget, so counting them would send a
	 * player looking for greyed settings that were never drawn.
	 */
	public int forcedShown() {
		int count = 0;
		for (String name : this.forced.keySet()) {
			if (PROFILE_KEY.equals(name) || this.menu.option(name).isPresent()) {
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
	 * Picks a profile by queueing every value it names.
	 * <p>
	 * A profile is a set of values and nothing else, so queueing them is what makes the rest of the
	 * screen tell the truth about it: the count in the status line, and a commit button that has
	 * something to commit. Holding the name beside the values instead, which is what this did, left
	 * a profile picked and no way to apply it.
	 * <p>
	 * A value the player queued by hand before picking a profile is overwritten by it. That is the
	 * way round a player expects, the profile being the broader gesture of the two, and it stays
	 * correctable afterwards: picking the profile first and the setting second leaves the setting.
	 */
	public void queueProfile(String name) {
		this.pendingProfile = name;
		this.profileChosen = true;
		this.pending.putAll(this.menu.profile(name));
	}

	/**
	 * Which profile the values in effect amount to, or the empty string when they amount to none of
	 * them and the screen has to say so itself.
	 * <p>
	 * Worked out from the values and never stored, which is Iris's rule
	 * ({@code ProfileSet.scan}): the honest answer to "which profile is this" is the one the values
	 * give. Storing the name instead makes a screen answer "none" the moment the file holding it is
	 * deleted, while the values on screen are exactly the ones a profile names, which is what
	 * pressing Reset used to look like.
	 * <p>
	 * The most constrained profile wins, as it does there, because one profile is usually another
	 * plus a setting or two and the looser of the pair would otherwise answer for both.
	 */
	public String matchedProfile() {
		String best = "";
		int constraints = -1;
		for (String name : this.menu.profileNames()) {
			Map<String, String> profile = this.menu.profile(name);
			if (profile.isEmpty() || profile.size() <= constraints || !matches(profile)) {
				continue;
			}

			best = name;
			constraints = profile.size();
		}

		return best;
	}

	private boolean matches(Map<String, String> profile) {
		for (Map.Entry<String, String> setting : profile.entrySet()) {
			if (!setting.getValue().equals(pending(setting.getKey()))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * The profile in effect: the one {@code options.txt} names when it names one, and what was
	 * chosen here otherwise. Forced like any other setting, and shown as forced for the same
	 * reason: a selector that let a click lose to that file in silence would be worse than a grey
	 * one.
	 */
	public String profile() {
		String over = this.forced.get(PROFILE_KEY);

		return over == null ? this.pendingProfile : over;
	}

	/**
	 * The profile the pack was last built with, which is what the world on screen was built from.
	 * A screen needs both this and {@link #profile()} to tell a chosen profile from an applied one,
	 * exactly as it does for a single setting.
	 */
	public String appliedProfile() {
		String over = this.forced.get(PROFILE_KEY);

		return over == null ? this.appliedProfile : over;
	}

	/**
	 * What a settings file carries, which is the one chosen here and never the one forced over it.
	 * The day that line goes away the pack has to come back to the profile that was picked, not
	 * stay on the one that was forced.
	 */
	public String chosenProfile() {
		return this.pendingProfile;
	}

	/** Whether any pending value contradicts what the profile in effect sets. */
	public boolean profileOverridden() {
		for (Map.Entry<String, String> setting : this.menu.profile(profile()).entrySet()) {
			if (!pending(setting.getKey()).equals(setting.getValue())) {
				return true;
			}
		}

		return false;
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
		this.pendingProfile = this.appliedProfile;
		this.profileChosen = false;
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

	/** Only what differs from what the layers below would produce, which is what gets written. */
	public Map<String, String> toSave() {
		Map<String, String> result = new LinkedHashMap<>();

		for (String name : touched()) {
			// Never write what options.txt forces. The day that line goes away the setting has to
			// come back to what was chosen underneath, not stay stuck at what was forced over it.
			if (this.forced.containsKey(name)) {
				String kept = this.saved.get(name);
				if (kept != null) {
					result.put(name, kept);
				}

				continue;
			}

			String below = this.menu.profile(this.pendingProfile).get(name);
			if (below == null) {
				below = this.menu.option(name).map(MenuOption::defaultValue).orElse(null);
			}

			// What this side chose, resolved without the profile options.txt may be forcing: that
			// profile decides what is drawn and never what is written, exactly like a forced value.
			String value = chosen(name);
			// A name below cannot answer for is one the pack no longer declares. It is kept as it
			// was: dropping it loses a player's settings the day they go back to an older pack.
			if (below == null || !below.equals(value)) {
				result.put(name, value);
			}
		}

		return result;
	}

	/** Everything but the forced layer, which is what a widget shows for an unforced setting. */
	private String unforced(String name) {
		return resolve(name, profile(), appliedProfile());
	}

	/** The same without the profile options.txt forces, which is what reaches the file. */
	private String chosen(String name) {
		return resolve(name, this.pendingProfile, this.appliedProfile);
	}

	private String resolve(String name, String pending, String applied) {
		String value = this.pending.get(name);
		if (value == null && this.profileChosen) {
			value = this.menu.profile(pending).get(name);
		}
		if (value == null) {
			value = this.saved.get(name);
		}
		if (value == null) {
			value = this.menu.profile(applied).get(name);
		}

		return value == null ? packDefault(name) : value;
	}

	/** Every name any layer above the pack's own defaults has an opinion about. */
	private Set<String> touched() {
		Set<String> names = new LinkedHashSet<>(this.saved.keySet());
		names.addAll(this.pending.keySet());
		names.addAll(this.menu.profile(this.appliedProfile).keySet());
		names.addAll(this.menu.profile(this.pendingProfile).keySet());

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
	 * word, so nothing is marked as waiting, and the first Apply copies that word into our own
	 * file, where it stays.
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
		boolean toggle = this.menu.option(name)
				.filter(option -> option.form() == MenuOption.Form.TOGGLE)
				.isPresent();

		return toggle ? OptionValue.parse(value).asText() : value;
	}
}
