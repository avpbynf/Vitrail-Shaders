package dev.vitrail.sodium;

import dev.vitrail.Vitrail;
import dev.vitrail.screen.ScreenText;
import dev.vitrail.screen.SettingsScreen;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Puts the settings screen in the video settings, which Sodium owns.
 * <p>
 * Sodium redirects the game's Video Settings screen to its own, so that screen is where a player
 * looks for anything to do with how the world is drawn, and the pack settings belong there. It is
 * also the one way in that costs nothing to reach: both menus lead to it already, so nothing has to
 * be added to either.
 * <p>
 * This is the same entry the reference takes, {@code IrisConfig} in its own tree, and by the same
 * public API rather than by reaching into Sodium: one page under the mod's own name, which opens
 * this screen with the video settings as the screen to come back to. What the reference has and this
 * does not is a second page of its own video options, because everything this engine has to offer is
 * the pack's own and lives on the pack's pages.
 * <p>
 * Each loader's metadata names this class, and each reaches it its own way: on NeoForge Sodium is
 * handed the name and builds an instance by reflection, on Fabric the loader's own entry point
 * machinery builds it and hands the object over. Sodium then walks that list twice, once before the
 * window is created and once with the game up, and only the second walk reaches the screen. Nothing
 * here is kept between the two. The name and the version shown beside the icon are read from that
 * same metadata, so they are not written here and cannot drift from it.
 */
public final class ConfigEntry implements ConfigEntryPoint {

	/**
	 * A plain texture and not a GUI sprite, because Sodium hands the identifier to the texture
	 * manager and blits it whole rather than looking it up in the atlas.
	 */
	private static final Identifier ICON =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "textures/gui/config-icon.png");

	/**
	 * The blue the icon is mostly made of. Sodium tints the mod's header and its pages with this,
	 * and it would otherwise pick one from a list by hashing the mod id; the icon is the one asset
	 * carrying a colour of ours, so the accent is taken from it rather than left to chance.
	 */
	private static final int THEME = 0xFF2E6FD9;

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		builder.registerOwnModOptions()
				// Not tinted: this icon is the mod's own, in colour, where Sodium's screen is drawn
				// for the monochrome ones it can paint in the theme colour.
				.setNonTintedIcon(ICON)
				.setColorTheme(builder.createColorTheme().setBaseThemeRGB(THEME))
				.addPage(builder.createExternalPage()
						.setName(Component.translatable(ScreenText.PACKS_TITLE))
						.setScreenConsumer(parent ->
								Minecraft.getInstance().gui.setScreen(new SettingsScreen(parent))));
	}
}
