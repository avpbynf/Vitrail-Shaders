package dev.vitrail.sodium;

import dev.vitrail.cache.ModuleCache;
import dev.vitrail.HostReport;
import dev.vitrail.IrisBeside;
import dev.vitrail.render.PackChoice;
import dev.vitrail.render.ShadowAmortisation;
import dev.vitrail.render.StartupGuard;
import dev.vitrail.render.TemporalAccumulation;
import dev.vitrail.render.TerrainDraw;
import dev.vitrail.screen.SettingsScreen;
import dev.vitrail.ScreenText;
import dev.vitrail.settings.GraphicsApiChoice;
import dev.vitrail.settings.PackFile;
import dev.vitrail.Vitrail;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.network.chat.CommonComponents;
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
 * this screen with the video settings as the screen to come back to, and a second page for the
 * settings that are this engine's own rather than a pack's. That second page is thin on purpose:
 * almost everything this engine has to offer is the pack's and lives on the pack's pages, and only
 * what a player sets over every pack belongs here. The one thing registered that is not a page is an
 * overlay over an option of Sodium's own, whose reason is written where it is registered.
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

	/** What this engine's own option is known by, to Sodium and to anything overlaying it later. */
	private static final Identifier SHADOW_DISTANCE =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "shadow_distance");

	/** The fraction of the window the world renders at, beside the distance above. */
	private static final Identifier RENDER_SCALE =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "render_scale");

	/** The fraction of the shadow map the pack asked for that is drawn, beside both. */
	private static final Identifier SHADOW_MAP_SCALE =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "shadow_map_scale");

	/** Which backend a startup that ended badly comes back to. */
	private static final Identifier GRAPHICS_API =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "graphics_api");

	/** How large the compiled-shader disk store may grow, on the engine page. */
	private static final Identifier MODULE_CACHE_CEILING =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "module_cache_ceiling");

	/** How many frames the shadow map is kept for, on the engine page beside the two scales. */
	private static final Identifier SHADOW_AMORTISATION =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "shadow_amortisation");

	/** Whether the upscaled frames are folded together, directly under the scale it serves. */
	private static final Identifier TEMPORAL_FOLD =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "temporal_fold");

	/** What the selector offers while the pack draws the world, and what it offers otherwise. */
	private static final Set<TextureFilteringMethod> WITHOUT_RGSS =
			Set.of(TextureFilteringMethod.NONE, TextureFilteringMethod.ANISOTROPIC);
	private static final Set<TextureFilteringMethod> EVERY_METHOD =
			Set.of(TextureFilteringMethod.values());

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		ModOptionsBuilder options = builder.registerOwnModOptions()
				// Not tinted: this icon is the mod's own, in colour, where Sodium's screen is drawn
				// for the monochrome ones it can paint in the theme colour.
				.setNonTintedIcon(ICON)
				.setColorTheme(builder.createColorTheme().setBaseThemeRGB(THEME))
				.addPage(builder.createExternalPage()
						.setName(Component.translatable(ScreenText.PACKS_TITLE))
						.setScreenConsumer(parent ->
								Minecraft.getInstance().gui.setScreen(new SettingsScreen(parent))))
				.addPage(builder.createOptionPage()
						.setName(Component.translatable(ScreenText.PAGE_TITLE))
						.addOptionGroup(builder.createOptionGroup()
								.addOption(shadowDistance(builder))
								.addOption(shadowMapScale(builder))
								.addOption(renderScale(builder))
								.addOption(temporalFold(builder))
								.addOption(shadowAmortisation(builder))
								.addOption(graphicsApi(builder))
								.addOption(moduleCacheCeiling(builder))));

		// Only on the backend this engine draws on. Anywhere else the pack draws nothing, so RGSS
		// keeps working and there is nothing to narrow. And never where Iris draws, which registers
		// its own overlay of this option in exactly that case (IrisConfig.java:40 and 54): Sodium
		// refuses two overlays of one option, so a game on OpenGL with both mods installed closed at
		// startup on the settings it could not build. Iris decides from options.txt and this engine
		// from the device, and IrisBeside says where those two part company, which is why both
		// questions are asked. The device is up by this walk.
		if (HostReport.otherBackend() || IrisBeside.draws()) {
			return;
		}

		// RGSS is shader code, written into the game's own terrain shader and into Sodium's, so it
		// is worth nothing while the pack's terrain program is the one drawing: the player moves the
		// selector and the image does not move. ANISOTROPIC keeps working, because it is a sampler
		// and an atlas seam fix rather than a shader, and this engine takes both as it finds them.
		// The reference registers this same overlay over the same two values, IrisConfig.java:58.
		//
		// What differs is the question asked, and only because this engine can answer a narrower
		// one. The reference asks whether a pack is loaded, IrisConfig.java:74, which for it is the
		// same thing: a loaded pack there always takes the terrain over. Here the pack's terrain
		// program can be off on its own while the rest of the chain draws, either because the engine
		// options refuse it or because reading it threw, and then Sodium's own shader draws the world
		// and RGSS works again. So the question is whether that program draws, which is what
		// TerrainDraw.asked answers.
		//
		// Asked through UPDATE_ON_REBUILD rather than read once, so the list follows a pack loaded or
		// dropped while the game is up rather than the state the screen was first built under.
		//
		// A stored RGSS survives the narrowing, and that is not something this overlay can change
		// from here. Sodium answers a value its allowed set no longer holds with the option's default
		// (EnumOption.validateValue), and its default for this option is RGSS itself
		// (SodiumConfigBuilder.java:511), so the fall back lands on the value it was meant to replace:
		// the selector opens on a name outside the set it offers, and the player cannot cycle back to
		// it once they have moved off.
		options.registerOptionOverlay(FILTERING,
				builder.createEnumOption(FILTERING, TextureFilteringMethod.class)
						.setAllowedValuesProvider(
								state -> TerrainDraw.asked() ? WITHOUT_RGSS : EVERY_METHOD,
								ConfigState.UPDATE_ON_REBUILD));
	}

	/**
	 * What fraction of the window the world renders at before being upscaled back onto it, as a
	 * percentage on each axis, applied while a pack draws and stored in {@code pack.txt} beside
	 * the distance above.
	 * <p>
	 * The step of five exists for the slider alone: the file takes any number the range holds, and
	 * a hand-written 33 survives the read, the clamp in {@code PackFile} being the only correction
	 * ever written back.
	 * <p>
	 * The value is shown with a literal percent sign rather than through the game's own
	 * {@code options.percent_value}, which is not a value string: it carries the option's caption
	 * and a colon along with the number.
	 */
	private static OptionBuilder renderScale(ConfigBuilder builder) {
		return builder.createIntegerOption(RENDER_SCALE)
				.setName(Component.translatable(ScreenText.RENDER_SCALE))
				.setTooltip(_ -> Component.translatable(ScreenText.RENDER_SCALE_TOOLTIP))
				.setDefaultValue(PackFile.DEFAULT_RENDER_SCALE)
				.setRange(new Range(PackFile.MIN_RENDER_SCALE, PackFile.MAX_RENDER_SCALE, 5))
				.setBinding(percent -> PackChoice.renderScale(Vitrail.platform().gameDirectory(),
								percent),
						PackChoice::renderScale)
				.setValueFormatter(percent -> Component.literal(percent + "%"))
				// Sodium refuses to build an option without one, at the loading screen and not at
				// compile time. The binding above has already written pack.txt.
				.setStorageHandler(() -> {})
				.setImpact(OptionImpact.HIGH);
	}

	/**
	 * How much of the shadow map the pack asked for is drawn, as a percentage on each axis, stored
	 * in {@code pack.txt} beside the render scale above.
	 * <p>
	 * <strong>It is a slider of its own and not a consequence of the one above it</strong>, and the
	 * reason is a pack's own arithmetic. The map is the one image of the frame this engine does not
	 * size from the window, because a pack picks its filter radius as a fraction of the map it was
	 * told it has: rewriting that number is exactly how this works, and it makes a smaller map a
	 * wider penumbra, an image its author never saw and never approved. Dragging it along behind
	 * the render scale would hand that to
	 * anybody who only wanted the world smaller, in silence. So it defaults to the whole map, where
	 * nothing of it runs at all, and a player who wants what it trades has to say so.
	 * <p>
	 * The step of five, the literal percent sign and the empty storage handler are the slider above
	 * this one's, for its reasons, written there.
	 */
	private static OptionBuilder shadowMapScale(ConfigBuilder builder) {
		return builder.createIntegerOption(SHADOW_MAP_SCALE)
				.setName(Component.translatable(ScreenText.SHADOW_MAP_SCALE))
				.setTooltip(_ -> Component.translatable(ScreenText.SHADOW_MAP_SCALE_TOOLTIP))
				.setDefaultValue(PackFile.DEFAULT_SHADOW_MAP_SCALE)
				.setRange(new Range(PackFile.MIN_SHADOW_MAP_SCALE, PackFile.MAX_SHADOW_MAP_SCALE, 5))
				.setBinding(percent -> PackChoice.shadowMapScale(Vitrail.platform().gameDirectory(),
								percent),
						PackChoice::shadowMapScale)
				.setValueFormatter(percent -> Component.literal(percent + "%"))
				.setStorageHandler(() -> {})
				.setImpact(OptionImpact.HIGH);
	}

	/**
	 * Which backend the game comes back to after a startup that ended badly.
	 * <p>
	 * The game's own answer is to walk the preferred API down to Default, then to OpenGL, whenever
	 * the previous startup did not finish. That rescue is meant for a machine whose Vulkan cannot
	 * start; here it fires for any crash at all, from any mod, and empties the session rather than
	 * saving it, since nothing of this engine is drawn off Vulkan. So the default here is Vulkan.
	 * <p>
	 * The two other answers are real answers and not politeness. OpenGL is for a machine where Vulkan
	 * really does not start, and leaving it to the game is for anyone who would rather have the
	 * vanilla behaviour back.
	 * <p>
	 * Written through {@link GraphicsApiChoice} into a file of its own, which is what lets it be read
	 * inside {@code Minecraft}'s constructor, long before this screen or the mod exists.
	 * {@link StartupGuard#forget} is what makes the next startup read the new value.
	 *
	 * @see StartupGuard
	 */
	private static OptionBuilder graphicsApi(ConfigBuilder builder) {
		return builder.createEnumOption(GRAPHICS_API, GraphicsApiChoice.class)
				.setName(Component.translatable(ScreenText.CRASH_API))
				.setTooltip(_ -> Component.translatable(ScreenText.CRASH_API_TOOLTIP))
				.setDefaultValue(GraphicsApiChoice.DEFAULT)
				.setBinding(chosen -> {
					GraphicsApiChoice.write(Vitrail.platform().gameDirectory(), chosen);
					StartupGuard.forget();
				}, GraphicsApiChoice::read)
				.setElementNameProvider(chosen -> Component.translatable(switch (chosen) {
					case VULKAN -> ScreenText.CRASH_API_VULKAN;
					case OPENGL -> ScreenText.CRASH_API_OPENGL;
					case GAME -> ScreenText.CRASH_API_GAME;
				}))
				// Empty for the same reason the slider above leaves it empty, and required for the
				// same reason: Sodium refuses to build a stateful option without one, at the loading
				// screen rather than at compile time. The binding has already written the file.
				//
				// No impact is declared either, and that is not an omission: this decides what a
				// LATER launch starts on and costs the running frame nothing at all. Sodium's own
				// impact labels are about the frame being drawn.
				.setStorageHandler(() -> {});
	}

	/**
	 * Whether the upscaled frames are folded into one another, which puts back some of the detail
	 * the render scale above stopped drawing.
	 * <p>
	 * <strong>Greyed out at a scale of a hundred, because there it does nothing at all.</strong> The
	 * fold rebuilds a large picture out of several small ones, and at full size the small ones are
	 * the picture: there is nothing to recover and the pass would be spent for no gain. Asked of the
	 * live state rather than read once, and declared against {@code RENDER_SCALE} so that moving that
	 * slider greys this one in and out under the player's hand rather than at the next screen.
	 * <p>
	 * MEDIUM and not LOW, and the cost is worth counting properly: TWO passes, one at the render
	 * size that writes the motion vectors and one at the window's size that folds, and THREE images,
	 * a two-channel one at the render size and a pair of half-float ones at the window's size, since
	 * a fold cannot read the image it writes. It is spent to make a cheaper scale look better rather
	 * than to make the frame faster.
	 */
	private static OptionBuilder temporalFold(ConfigBuilder builder) {
		return builder.createBooleanOption(TEMPORAL_FOLD)
				.setName(Component.translatable(ScreenText.TEMPORAL_FOLD))
				.setTooltip(_ -> Component.translatable(ScreenText.TEMPORAL_FOLD_TOOLTIP))
				.setDefaultValue(TemporalAccumulation.DEFAULT_WANTED)
				.setBinding(TemporalAccumulation::setWanted, TemporalAccumulation::wanted)
				.setEnabledProvider(state -> state.readIntOption(RENDER_SCALE) < PackFile.MAX_RENDER_SCALE,
						RENDER_SCALE)
				.setStorageHandler(() -> {})
				.setImpact(OptionImpact.MEDIUM);
	}

	/**
	 * How many frames the shadow map is kept for after the one that drew it.
	 * <p>
	 * The pass that fills that map is the most expensive thing the engine does, and between two
	 * frames of a player standing still nothing in it moves. What the frames cost is on the GROUND: a
	 * block placed or broken keeps the shadow it had when the map was drawn, while a mob, a boat and
	 * the player's own shadow are drawn into the kept map again on every frame.
	 * The slider stops at {@link ShadowAmortisation#MAX_FRAMES} because a walk finds the lag at
	 * three, and a value nobody should choose is better left out of the selector than explained in
	 * a tooltip.
	 * <p>
	 * HIGH impact and not medium: on the corpus it moves the frame rate by a fifth to a third, which
	 * is more than either scale above it does at its usual settings.
	 */
	private static OptionBuilder shadowAmortisation(ConfigBuilder builder) {
		return builder.createIntegerOption(SHADOW_AMORTISATION)
				.setName(Component.translatable(ScreenText.SHADOW_AMORTISATION))
				.setTooltip(_ -> Component.translatable(ScreenText.SHADOW_AMORTISATION_TOOLTIP))
				.setDefaultValue(ShadowAmortisation.DEFAULT_FRAMES)
				.setRange(new Range(ShadowAmortisation.MIN_FRAMES, ShadowAmortisation.MAX_FRAMES, 1))
				.setBinding(ShadowAmortisation::setFrames, ShadowAmortisation::frames)
				.setValueFormatter(frames -> switch (frames) {
					case 0 -> Component.translatable(ScreenText.SHADOW_AMORTISATION_OFF);
					case 1 -> Component.translatable(ScreenText.SHADOW_AMORTISATION_FRAME, frames);
					default -> Component.translatable(ScreenText.SHADOW_AMORTISATION_FRAMES, frames);
				})
				.setStorageHandler(() -> {})
				.setImpact(OptionImpact.HIGH);
	}

	/**
	 * How large the compiled-shader store may grow, in mebibytes. The file and the live ceiling
	 * move together, so a lower number sweeps at once and a higher one is seen by the next store.
	 * No pack reload and no restart.
	 */
	private static OptionBuilder moduleCacheCeiling(ConfigBuilder builder) {
		return builder.createIntegerOption(MODULE_CACHE_CEILING)
				.setName(Component.translatable(ScreenText.MODULE_CACHE_CEILING))
				.setTooltip(_ -> Component.translatable(ScreenText.MODULE_CACHE_CEILING_TOOLTIP))
				.setDefaultValue(ModuleCache.DEFAULT_CEILING_MIB)
				.setRange(new Range(ModuleCache.MIN_CEILING_MIB, ModuleCache.MAX_CEILING_MIB,
						ModuleCache.CEILING_STEP_MIB))
				.setBinding(ModuleCache::setCeilingMib, ModuleCache::ceilingMib)
				.setValueFormatter(mib -> Component.literal(mib + " MiB"))
				.setStorageHandler(() -> {})
				.setImpact(OptionImpact.LOW);
	}

	/**
	 * How far the light reaches, in CHUNKS, which is the one video setting this engine has of its
	 * own and the cheapest thing a player can trade for frames.
	 * <p>
	 * It is the reference's option, built the same way and over the same range,
	 * {@code compat/sodium/config/IrisConfig.java:163-194}: zero to thirty-two, thirty-two by default,
	 * greyed out while the pack forces a distance of its own, and shown as the pack's number then
	 * rather than as the player's. What the two numbers mean and which of them wins is
	 * {@code PackValues.shadowRenderDistance}, and the conversion into blocks lives there too.
	 * <p>
	 * <strong>The forced number is clamped into the slider's range before it is shown.</strong> Not
	 * cosmetic: Sodium validates whatever the binding hands back, and writes the correction out
	 * through the same binding when it has to ({@code StatefulOption.resetFromBinding}). A pack
	 * asking for more than thirty-two chunks would come back clamped and be SAVED over the player's
	 * own number, which they never touched and cannot see.
	 */
	private static OptionBuilder shadowDistance(ConfigBuilder builder) {
		return builder.createIntegerOption(SHADOW_DISTANCE)
				.setName(Component.translatable(ScreenText.SHADOW_DISTANCE))
				.setTooltip(_ -> Component.translatable(
						TerrainDraw.forcedShadowDistanceChunks().isPresent()
								? ScreenText.SHADOW_DISTANCE_FORCED
								: ScreenText.SHADOW_DISTANCE_TOOLTIP))
				.setDefaultValue(PackFile.DEFAULT_SHADOW_DISTANCE)
				.setRange(new Range(PackFile.MIN_SHADOW_DISTANCE, PackFile.MAX_SHADOW_DISTANCE, 1))
				.setBinding(chunks -> PackChoice.shadowDistance(Vitrail.platform().gameDirectory(),
								chunks),
						() -> Math.clamp(TerrainDraw.shadowDistanceChunks(),
								PackFile.MIN_SHADOW_DISTANCE, PackFile.MAX_SHADOW_DISTANCE))
				// Zero is not a short distance, it is no shadow map worth drawing, so it is named
				// rather than counted. Both the word and the unit are the game's own strings, so
				// they read in the player's language on a client this mod ships no translation for.
				.setValueFormatter(chunks -> chunks <= 0
						? CommonComponents.OPTION_OFF
						: Component.translatable("options.chunks", chunks))
				// Asked again whenever the screen rebuilds, like the overlay above and for the same
				// reason: a pack loaded or dropped while the game is up decides whether this slider
				// still belongs to the player.
				.setEnabledProvider(_ -> TerrainDraw.forcedShadowDistanceChunks().isEmpty(),
						ConfigState.UPDATE_ON_REBUILD)
				// Sodium refuses to build an option without one, at the loading screen and not at
				// compile time. It has nothing left to do here: the binding above writes pack.txt
				// as it is moved, where the reference's binding only moves a field and its handler
				// saves the whole config file afterwards, IrisConfig.java:192.
				.setStorageHandler(() -> {})
				.setImpact(OptionImpact.HIGH);
	}
}
