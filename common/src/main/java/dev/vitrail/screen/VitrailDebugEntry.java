package dev.vitrail.screen;

import dev.vitrail.Vitrail;
import dev.vitrail.render.PackChain;
import dev.vitrail.render.PackChoice;
import dev.vitrail.settings.PackSession;
import dev.vitrail.sodium.ShadowTerrain;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jspecify.annotations.Nullable;

/**
 * The engine's lines of the F3 screen, in Iris's wording so a capture of one reads against a
 * capture of the other: version, shaderpack, the scanned profile with the dirty count, then
 * Sodium's shadow {@code C: a/b D: d}. Color space is omitted: this engine has no color-space
 * setting to name, and inventing one would be a line Iris cannot match the other way. While a
 * pack is still compiling, one extra line carries the overlay's own words
 * ({@code overlay.vitrail.compiling}) so F3 can hide that overlay without going silent.
 * <p>
 * A pack that is not being drawn says why in one line, and the three reasons are kept apart the
 * way {@link PackChain} keeps them: asked off, named but missing, or refused. Saying "none" for a
 * pack that was refused would send the reader to the settings screen when the log is where the
 * answer is.
 * <p>
 * Registration is {@code DebugScreenEntriesMixin}'s, and the line showing without a hand's turn of
 * F3 configuration is {@code DebugScreenEntryListMixin}'s; this class only says the words. All
 * three follow Iris's own construction on the same vanilla seams.
 */
public final class VitrailDebugEntry implements DebugScreenEntry {

	/** The entry as the F3 configuration screen lists it. */
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, Vitrail.MOD_ID);

	/** The group the lines land in, so they hold together instead of scattering between vanilla's. */
	private static final Identifier GROUP = Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "name");

	private static final String PREFIX = "[" + Vitrail.MOD_NAME + "] ";

	/**
	 * The session the profile line below was built for, and the line. The sentence is a scan of
	 * the pack's profiles against the applied settings, and both are fixed for the life of a
	 * session, so it is built when the session changes rather than on every frame F3 is open.
	 */
	private static PackSession profiledSession;
	private static String profileLine = "";

	@Override
	public void display(DebugScreenDisplayer displayer, @Nullable Level level,
			@Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
		displayer.addToGroup(GROUP, PREFIX + "Version: " + Vitrail.platform().modVersion());
		PackChain.compilingWords().ifPresent(words ->
				displayer.addToGroup(GROUP, PREFIX + words.getString()));

		PackChoice.session().ifPresentOrElse(session -> {
			displayer.addToGroup(GROUP, PREFIX + "Shaderpack: " + session.packFileName());
			displayer.addToGroup(GROUP, profileLine(session));
			if (level != null) {
				shadowLines(displayer);
			}
		}, () -> displayer.addToGroup(GROUP, PREFIX + undrawnLine()));
	}

	private static String profileLine(PackSession session) {
		if (session != profiledSession) {
			profiledSession = session;
			profileLine = PREFIX + session.profileInfo();
		}

		return profileLine;
	}

	/**
	 * What the shadow map cost to fill, said the way Iris says it on the default F3
	 * ({@code gui/debug/IrisDebugEntry.java:26}): Sodium's {@code C: a/b D: d} for the light.
	 * The cull shape stays in the log ({@code ShadowTerrain}'s {@code shadow-cull} line), not
	 * here: a capture next to Iris has to read the same family of lines, and
	 * {@code ADVANCED BOX r=} is a dialect only this engine speaks.
	 * <p>
	 * <strong>The number to read against Iris is the first one</strong>, both sides counting the
	 * sections that carry block geometry and no others. Iris does not append {@code (no terrain)}
	 * on this default line; that fragment lives on its extra debug entry.
	 */
	private static void shadowLines(DebugScreenDisplayer displayer) {
		ShadowTerrain.Walk walk = ShadowTerrain.lastWalk();
		int drawn = walk == null ? 0 : walk.drawn();
		int total = walk == null ? 0 : walk.total();
		displayer.addToGroup(GROUP, PREFIX + "Shadows: C: " + drawn + "/" + total
				+ " D: " + Minecraft.getInstance().options.getEffectiveRenderDistance());
	}

	/**
	 * The one line said when no pack is being drawn, naming which of the three reasons it is.
	 */
	private static String undrawnLine() {
		if (PackChoice.noPackWanted()) {
			return "Shaders are disabled";
		}
		if (PackChoice.packMissing()) {
			return "Shaderpack: " + PackChoice.askedFor().name() + " (missing from the folder)";
		}
		return PackChoice.lastError()
				.map(error -> "Shaderpack refused: " + error)
				.orElse("Shaderpack: none");
	}
}
