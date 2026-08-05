package dev.vitrail.screen;

import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.Duration;

/**
 * Our own strings, and the two rules about text every widget follows.
 * <p>
 * The keys are gathered here rather than written where they are used, so that this list and
 * {@code assets/vitrail/lang/en_us.json} cannot drift apart: the list is the file. Three of them
 * take one argument, said next to each. A label the game already ships translated is not repeated
 * here: Done, Cancel and Back come from {@link CommonComponents}, so they read in the player's
 * language on a client we have no translation for.
 * <p>
 * A pack's own strings never go through a translation. {@link #fromPack(String)} builds a literal
 * component, because a pack that writes a per cent sign in a suffix would break a format string,
 * and one that writes a section sign expects the colour it names to be drawn rather than shown.
 */
public final class ScreenText {

	public static final String TITLE = "options.vitrail.title";
	public static final String APPLY = "options.vitrail.apply";
	public static final String RELOAD = "options.vitrail.reload";
	public static final String RESET = "options.vitrail.reset";

	/** One argument: the pack whose settings would be thrown away. */
	public static final String RESET_CONFIRM = "options.vitrail.reset_confirm";

	/** One argument: the file that would be emptied, by name. */
	public static final String RESET_CONFIRM_DETAIL = "options.vitrail.reset_confirm_detail";
	/** The view switch seen from a pack's pages. {@link #TITLE} is the same control seen from the list. */
	public static final String PACKS = "options.vitrail.packs";
	public static final String PACKS_TITLE = "options.vitrail.packs_title";
	public static final String PROFILE = "options.vitrail.profile";

	/** What the selector says when the values match no profile the pack declares. */
	public static final String PROFILE_CUSTOM = "options.vitrail.profile_custom";

	/** The way to the folder the packs are read from, which both references put on this screen. */
	public static final String FOLDER = "options.vitrail.folder";

	/** One argument: how many settings are waiting to be applied. */
	public static final String PENDING = "options.vitrail.pending";
	public static final String REMOVED = "options.vitrail.removed";

	/** One argument: how many settings options.txt is holding down. */
	public static final String FORCED = "options.vitrail.forced";

	public static final String NO_PACK = "options.vitrail.no_pack";

	/** The other reason there is nothing to configure: the folder was left alone on purpose. */
	public static final String PACK_OFF = "options.vitrail.pack_off";

	/** One argument: why the last load failed. */
	public static final String ERROR = "options.vitrail.error";

	public static final String OPEN_SETTINGS = "key.vitrail.open_settings";

	/**
	 * The group that key sits in. Nothing names it and nothing can: the game builds it from the
	 * category's identifier, in {@code KeyMapping.Category.label()}, so what decides this string is
	 * the path of the identifier {@code VitrailKeys} registers, not the name of the field holding
	 * it. It is written down here anyway, because this list is the language file and a key left out
	 * of the list is one nobody notices going missing.
	 */
	public static final String KEY_CATEGORY = "key.category.vitrail.keybinds";

	/**
	 * The colour a setting whose pending value differs from the applied one is drawn in. It is
	 * the only thing telling a player whether they are reading the world they see or the one they
	 * are about to get, so it is not decoration, and it lives in one place for that reason.
	 */
	public static final int MODIFIED = 0xFFffc94a;

	public static final Duration TOOLTIP_DELAY = Duration.ofMillis(500);

	/**
	 * The name a pack gives a setting, a colon, and its value, joined the way the game joins its
	 * own, so that the separator follows the player's language rather than being written in here:
	 * French puts a space before the colon and Japanese uses a full width one. The colour is set on
	 * the whole label rather than on its parts, so a value that carries a colour of its own keeps
	 * it.
	 */
	public static MutableComponent setting(Component name, Component value, boolean modified) {
		MutableComponent label = CommonComponents.optionNameValue(name, value);
		return modified ? label.withColor(MODIFIED) : label;
	}

	/** Anything a pack wrote, shown as it wrote it. */
	public static MutableComponent fromPack(String text) {
		return Component.literal(text);
	}

	private ScreenText() {
	}
}
