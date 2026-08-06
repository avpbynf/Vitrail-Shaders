package dev.vitrail.screen;

import dev.vitrail.pack.menu.MenuOption;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;

/**
 * The {@code <profile>} slot, shown as an ordinary setting whose values are the pack's profile
 * names.
 * <p>
 * It looks like one because walking through the names is the whole of the interaction. Shift and a
 * click do nothing here, a profile being a whole set of values and not one of them, and choosing
 * one does not clear what the player set on a setting the profile never mentions.
 * <p>
 * <strong>What is shown is worked out from the values, not read from a name kept beside them</strong>,
 * which is what {@link #valueLabel()} and {@link #cycle(int)} both do and what the line below used
 * to deny. The name IS stored, in the settings file, and it is not the authority: after a Reset
 * that file is gone and the pack's own values are back, and those values still amount to a profile
 * that this then names. Storing the answer instead would show Custom over a pack sitting exactly on
 * its own ULTRA.
 * <p>
 * A pack declaring no profile never reaches this class: the token disappears from the page rather
 * than becoming a blank.
 */
public final class ProfileWidget extends OptionWidget {

	/** No pack declares a setting by this name, and both settings files reserve the line. */
	private static final String NAME = "profile";

	private final List<String> profiles;

	public ProfileWidget(List<String> profiles, ScreenHost host, int width) {
		super(asOption(profiles), host, width);
		this.profiles = List.copyOf(profiles);
		syncFromValues();
	}

	private static MenuOption asOption(List<String> profiles) {
		return new MenuOption(NAME,
				profiles.size() > 1 ? MenuOption.Form.CYCLE : MenuOption.Form.FIXED,
				profiles.isEmpty() ? "" : profiles.get(0), profiles, false);
	}

	@Override
	protected Component nameLabel() {
		return Component.translatable(ScreenText.PROFILE);
	}

	/**
	 * The profile the values on screen amount to, and {@code Custom} when they amount to none. Read
	 * from the values rather than from a name held beside them, which is what Iris shows and what
	 * makes this label survive a Reset: the file is gone, the pack's own values are back, and those
	 * values are a profile.
	 */
	@Override
	protected Component valueLabel() {
		String current = host().values().matchedProfile();
		return current.isEmpty()
				? Component.translatable(ScreenText.PROFILE_CUSTOM)
				: ScreenText.fromPack(host().lang().profile(current));
	}

	/**
	 * Walks from whichever profile the values match, so that a click always lands on the next one in
	 * the pack's own order. From Custom it starts at the first, since there is no next to a set of
	 * values that is not a profile.
	 */
	@Override
	protected void cycle(int direction) {
		if (this.profiles.isEmpty()) {
			return;
		}

		int index = this.profiles.indexOf(host().values().matchedProfile());
		int next = index < 0 ? 0 : Math.floorMod(index + direction, this.profiles.size());
		host().values().queueProfile(this.profiles.get(next));
	}

	@Override
	protected void toDefault() {
	}

	/**
	 * Amber for a profile chosen and not applied yet, and amber as well when a pending value
	 * contradicts what that profile sets, which is the closest we get to Iris's guessed "Custom"
	 * without guessing. Never amber while {@code options.txt} names the profile: nothing about a
	 * selector that file decides is waiting on this screen.
	 */
	@Override
	protected boolean modified() {
		return !forced()
				&& (!host().values().profile().equals(host().values().appliedProfile())
						|| host().values().profileOverridden());
	}

	@Override
	protected Optional<String> comment() {
		return host().lang().profileComment();
	}
}
