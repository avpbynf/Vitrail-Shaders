package dev.vitrail.screen;

import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Our own strings, and the one rule about text every widget follows.
 * <p>
 * The keys are gathered here rather than written where they are used, so that this list and
 * {@code assets/vitrail/lang/en_us.json} cannot drift apart: the list is the file. The ones taking an
 * argument say so next to each. A label the game already ships translated is not repeated here: Done,
 * Cancel, Back, On and Off come from {@link CommonComponents}, so they read in the player's language
 * on a client we have no translation for.
 * <p>
 * <b>The English is Iris's, word for word, wherever Iris has a string for the same thing</b>, which is
 * the point of porting its screens: a player who has configured a pack before reads the same words.
 * What is ours is what Iris has no equivalent of, which is the three facts this engine has and it does
 * not: {@link #FORCED}, {@link #REMOVED} and {@link #ERROR}.
 * <p>
 * A pack's own strings never go through a translation. {@link #fromPack(String)} builds a literal
 * component, because a pack that writes a per cent sign in a suffix would break a format string, and
 * one that writes a section sign expects the colour it names to be drawn rather than shown.
 */
public final class ScreenText {

	/**
	 * The screen's own title, and Iris's {@code shaderPackSelection.title}. It names the page in the
	 * video settings that opens this screen too, since that page is this screen.
	 */
	public static final String PACKS_TITLE = "options.vitrail.packs_title";

	/**
	 * The selector for what a startup that ended badly comes back to, its tooltip, and its three
	 * values.
	 * <p>
	 * The two backends are named here rather than borrowed from the game's own setting, which was
	 * the first shape of this and was wrong: its Vulkan entry reads "Prefer Vulkan (Experimental)",
	 * which is right where it stands and runs off the end of a row here. A value in a selector is a
	 * name, not a sentence.
	 */
	public static final String CRASH_API = "options.vitrail.crash_graphics_api";
	public static final String CRASH_API_TOOLTIP = "options.vitrail.crash_graphics_api_tooltip";
	public static final String CRASH_API_VULKAN = "options.vitrail.crash_graphics_api.vulkan";
	public static final String CRASH_API_OPENGL = "options.vitrail.crash_graphics_api.opengl";
	public static final String CRASH_API_GAME = "options.vitrail.crash_graphics_api.game";

	/**
	 * How far the light reaches, on the video settings page this engine owns, with its two
	 * tooltips: what the setting does, and what it says instead while a pack has taken the distance
	 * out of the player's hands. Iris's {@code options.iris.shadowDistance} and its
	 * {@code .enabled} and {@code .disabled}. The page's own name is the game's
	 * {@code options.videoTitle} and is not repeated here.
	 */
	public static final String SHADOW_DISTANCE = "options.vitrail.shadow_distance";
	public static final String SHADOW_DISTANCE_TOOLTIP = "options.vitrail.shadow_distance_tooltip";
	public static final String SHADOW_DISTANCE_FORCED = "options.vitrail.shadow_distance_forced";

	/** The grey line under the title, one per view. */
	public static final String SELECT_TITLE = "pack.vitrail.select_title";
	public static final String CONFIGURE_TITLE = "pack.vitrail.configure_title";

	/** The view switch seen from the pack list. The key takes {@link #OPEN_SETTINGS} instead. */
	public static final String TITLE = "options.vitrail.title";

	/** The same switch seen from a pack's pages. */
	public static final String PACKS = "options.vitrail.packs";

	public static final String APPLY = "options.vitrail.apply";

	/** The way to the folder the packs are read from, which both references put on this screen. */
	public static final String FOLDER = "options.vitrail.folder";

	/** The eye that takes the screen away so the world behind it can be looked at, and F1's label. */
	public static final String GUI_HIDE = "options.vitrail.gui_hide";
	public static final String GUI_SHOW = "options.vitrail.gui_show";

	/** The page's own tools, and the tooltips saying what each of the three would do. */
	public static final String RESET = "options.vitrail.reset";
	public static final String RESET_TOOLTIP = "options.vitrail.reset_tooltip";
	public static final String IMPORT_TOOLTIP = "options.vitrail.import_tooltip";
	public static final String EXPORT_TOOLTIP = "options.vitrail.export_tooltip";

	/** One argument: the pack whose settings would be thrown away. */
	public static final String RESET_CONFIRM = "options.vitrail.reset_confirm";

	/** One argument: the file that would be emptied, by name. */
	public static final String RESET_CONFIRM_DETAIL = "options.vitrail.reset_confirm_detail";

	/**
	 * The button that reads the whole pack again, which Iris has none of and this engine needs: nothing
	 * watches a pack's own files, so a GLSL file edited by hand has no other way in.
	 */
	public static final String RELOAD = "options.vitrail.reload";

	/** Why the import and export windows will not open, since a full screen game hangs behind one. */
	public static final String FULLSCREEN = "options.vitrail.fullscreen";

	/** What shift and a click would do to a setting, shown while shift is held. */
	public static final String SET_TO_DEFAULT = "options.vitrail.set_to_default";

	public static final String PROFILE = "options.vitrail.profile";

	/** What the selector says when the values match no profile the pack declares. */
	public static final String PROFILE_CUSTOM = "options.vitrail.profile_custom";

	/** The three the pack list's top row takes, one per state, and it never changes size. */
	public static final String SHADERS_ENABLED = "options.vitrail.shaders_enabled";
	public static final String SHADERS_DISABLED = "options.vitrail.shaders_disabled";
	public static final String SHADERS_NONE_PRESENT = "options.vitrail.shaders_none_present";

	/** The line under the pack list, which is how anybody learns a pack can be dropped onto it. */
	public static final String PACK_DROP = "options.vitrail.pack_drop";

	/** What became of the files dropped onto the pack list. One argument each but {@link #FAILED_ADD}. */
	public static final String ADDED_PACK = "options.vitrail.added_pack";
	public static final String ADDED_PACKS = "options.vitrail.added_packs";
	public static final String FAILED_ADD = "options.vitrail.failed_add";
	public static final String FAILED_ADD_SINGLE = "options.vitrail.failed_add_single";
	public static final String COPY_ERROR = "options.vitrail.copy_error";
	public static final String COPY_ERROR_EXISTS = "options.vitrail.copy_error_exists";

	/** What became of a settings file dropped onto a pack's page, or chosen in the import window. */
	public static final String TOO_MANY_FILES = "options.vitrail.too_many_files";
	public static final String IMPORTED_SETTINGS = "options.vitrail.imported_settings";
	public static final String FAILED_IMPORT = "options.vitrail.failed_import";

	/** One argument: how many settings {@code vitrail/options.txt} is holding down. */
	public static final String FORCED = "options.vitrail.forced";

	/** One argument: how many passes this backend could not build and left out. */
	public static final String REMOVED = "options.vitrail.removed";

	/** One argument: why the last load failed. */
	public static final String ERROR = "options.vitrail.error";

	public static final String NO_PACK = "options.vitrail.no_pack";

	/** The other reason there is nothing to configure: the folder was left alone on purpose. */
	public static final String PACK_OFF = "options.vitrail.pack_off";

	/**
	 * The third reason, and the only one that is a fault: a pack was named and the folder has no such
	 * pack. One argument, the name that was asked for, without which this says no more than
	 * {@link #NO_PACK} does.
	 */
	public static final String PACK_MISSING = "options.vitrail.pack_missing";

	public static final String OPEN_SETTINGS = "key.vitrail.open_settings";

	/**
	 * The other key, and Iris's {@code keybind.reload}, with the two lines it says once it has read.
	 * {@link #RELOAD_FAILED} takes one argument: why nothing was read.
	 */
	public static final String RELOAD_PACK = "key.vitrail.reload_pack";
	public static final String PACK_RELOADED = "options.vitrail.pack_reloaded";
	public static final String RELOAD_FAILED = "options.vitrail.reload_failed";

	/**
	 * The chat line said once, the first time a world is shown on a backend this engine is not
	 * written for. Three arguments: the backend's name, then the two labels the game itself ships
	 * for the setting and for its Vulkan entry. Those two are the game's keys and not ours, the one
	 * exception to the rule above that a label the game ships is not repeated here: there is no
	 * constant for them, and naming the setting in the player's own language is the whole of why
	 * they are passed in rather than written into the sentence.
	 */
	public static final String OTHER_BACKEND = "options.vitrail.other_backend";
	public static final String GRAPHICS_API = "options.graphicsApi";
	public static final String GRAPHICS_API_VULKAN = "options.graphicsApi.vulkan";

	/** The game's own label for the other one, used by {@link #CRASH_API} for the same reason. */
	public static final String GRAPHICS_API_OPENGL = "options.graphicsApi.opengl";

	/**
	 * The group that key sits in. Nothing names it and nothing can: the game builds it from the
	 * category's identifier, in {@code KeyMapping.Category.label()}, so what decides this string is
	 * the path of the identifier {@code VitrailKeys} registers, not the name of the field holding
	 * it. It is written down here anyway, because this list is the language file and a key left out
	 * of the list is one nobody notices going missing.
	 */
	public static final String KEY_CATEGORY = "key.category.vitrail.keybinds";

	/**
	 * The colour a setting whose pending value differs from the applied one is drawn in, and Iris's own
	 * ({@code BaseOptionElementWidget.java:140}). It is the only thing telling a player whether they
	 * are reading the world they see or the one they are about to get, so it is not decoration, and it
	 * lives in one place for that reason.
	 */
	public static final int MODIFIED = 0xFFffc94a;

	/** Anything a pack wrote, shown as it wrote it. */
	public static MutableComponent fromPack(String text) {
		return Component.literal(text);
	}

	private ScreenText() {
	}
}
