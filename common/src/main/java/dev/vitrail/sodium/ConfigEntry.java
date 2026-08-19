package dev.vitrail.sodium;

import dev.vitrail.Vitrail;
import dev.vitrail.render.TerrainDraw;
import dev.vitrail.screen.ScreenText;
import dev.vitrail.screen.SettingsScreen;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Set;

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
 * the pack's own and lives on the pack's pages. The one thing registered here that is not a page is
 * an overlay over an option of Sodium's own, whose reason is written where it is registered.
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
	 * The mod's own logo, the one both loaders show in their mod list, laid down under this name by
	 * {@code common/build.gradle} rather than kept in the tree twice. It is named as a plain texture
	 * and not as a GUI sprite because Sodium hands the identifier to the texture manager and blits it
	 * whole, at three lines of text less a margin, rather than looking it up in the atlas.
	 */
	private static final Identifier ICON =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "textures/gui/config-icon.png");

	/**
	 * The blue the icon is mostly made of. Sodium tints the mod's header and its pages with this,
	 * and it would otherwise pick one from a list by hashing the mod id; the icon is the one asset
	 * carrying a colour of ours, so the accent is taken from it rather than left to chance.
	 */
	private static final int THEME = 0xFF2E6FD9;

	/** Sodium's own texture filtering selector, the one option here has anything to say about. */
	private static final Identifier FILTERING = Identifier.parse("sodium:quality.filtering_mode");

	/** What the selector offers while the pack draws the world, and what it offers otherwise. */
	private static final Set<TextureFilteringMethod> WITHOUT_RGSS =
			Set.of(TextureFilteringMethod.NONE, TextureFilteringMethod.ANISOTROPIC);
	private static final Set<TextureFilteringMethod> EVERY_METHOD =
			Set.of(TextureFilteringMethod.values());

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
								Minecraft.getInstance().gui.setScreen(new SettingsScreen(parent))))
				// RGSS is shader code, written into the game's own terrain shader and into Sodium's,
				// so it is worth nothing while the pack's terrain program is the one drawing: the
				// player moves the selector and the image does not move. ANISOTROPIC keeps working,
				// because it is a sampler and an atlas seam fix rather than a shader, and this engine
				// takes both as it finds them. The reference registers this same overlay over the
				// same two values, IrisConfig.java:78.
				//
				// What differs is the question asked, and only because this engine can answer a
				// narrower one. The reference asks whether a pack is loaded, IrisConfig.java:87,
				// which for it is the same thing: a loaded pack there always takes the terrain over.
				// Here the pack's terrain program can be off on its own while the rest of the chain
				// draws, either because the engine options refuse it or because reading it threw, and
				// then Sodium's own shader draws the world and RGSS works again. So the question is
				// whether that program draws, which is what TerrainDraw.asked answers.
				//
				// Asked through UPDATE_ON_REBUILD rather than read once, so the list follows a pack
				// loaded or dropped while the game is up rather than the state the screen was first
				// built under. A stored RGSS is not carried around the gap: Sodium falls back to the
				// option's default for a value its own allowed set no longer holds.
				.registerOptionOverlay(FILTERING,
						builder.createEnumOption(FILTERING, TextureFilteringMethod.class)
								.setAllowedValuesProvider(
										state -> TerrainDraw.asked() ? WITHOUT_RGSS : EVERY_METHOD,
										ConfigState.UPDATE_ON_REBUILD));
	}
}
