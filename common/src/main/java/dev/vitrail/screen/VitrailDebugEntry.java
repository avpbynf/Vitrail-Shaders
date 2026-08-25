package dev.vitrail.screen;

import dev.vitrail.Vitrail;
import dev.vitrail.render.PackChain;
import dev.vitrail.sodium.ShadowTerrain;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jspecify.annotations.Nullable;

/**
 * The engine's lines of the F3 screen: version, the pack being drawn, and the profile it stands
 * on. They are the heart of what Iris shows, because they answer the questions a screenshot gets asked -
 * which engine drew this, with which pack, set how - and a capture with the F3 open is how those
 * questions arrive. Without them a Vitrail frame and an Iris frame of the same pack read as the
 * same picture.
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

	@Override
	public void display(DebugScreenDisplayer displayer, @Nullable Level level,
			@Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
		displayer.addToGroup(GROUP, PREFIX + "Version: " + Vitrail.platform().modVersion());

		PackChain.session().ifPresentOrElse(session -> {
			displayer.addToGroup(GROUP, PREFIX + "Shaderpack: " + session.packFileName());
			String profile = session.settings().profile();
			if (!profile.isEmpty()) {
				displayer.addToGroup(GROUP, PREFIX + "Profile: " + profile);
			}
			if (level != null) {
				shadowLines(displayer);
			}
		}, () -> displayer.addToGroup(GROUP, PREFIX + undrawnLine()));
	}

	/**
	 * What the shadow map cost to fill, said the way the reference says it so that a capture of one
	 * screen reads against a capture of the other.
	 * <p>
	 * The first line is Sodium's own shape for the camera, which Iris reuses for the light and
	 * shows by default ({@code gui/debug/IrisDebugEntry}, raising its shadow flag around the ask):
	 * sections drawn over sections loaded, then the render distance. <strong>The number to read
	 * against Iris is the first one</strong>, both sides counting the sections that carry block
	 * geometry and no others.
	 * <p>
	 * The second line is what Iris keeps for its debug options and this engine has no reason to
	 * hide: the shape the walk measured against, and how many sections came back. Two counts that
	 * differ by little say the cull is running and finding nothing to drop, which is a state worth
	 * seeing rather than guessing at.
	 */
	private static void shadowLines(DebugScreenDisplayer displayer) {
		ShadowTerrain.Walk walk = ShadowTerrain.lastWalk();
		if (walk == null) {
			displayer.addToGroup(GROUP, PREFIX + "Shadows: (unavailable)");
			return;
		}

		displayer.addToGroup(GROUP, PREFIX + "Shadows: C: " + walk.drawn() + "/" + walk.total()
				+ " D: " + Minecraft.getInstance().options.getEffectiveRenderDistance()
				+ (walk.terrain() ? "" : " (no terrain)"));
		displayer.addToGroup(GROUP, PREFIX + "Shadow cull: " + walk.kept() + " sections kept, "
				+ walk.culling());
	}

	/**
	 * The one line said when no pack is being drawn, naming which of the three reasons it is.
	 */
	private static String undrawnLine() {
		if (PackChain.noPackWanted()) {
			return "Shaders are disabled";
		}
		if (PackChain.packMissing()) {
			return "Shaderpack: " + PackChain.askedFor().name() + " (missing from the folder)";
		}
		return PackChain.lastError()
				.map(error -> "Shaderpack refused: " + error)
				.orElse("Shaderpack: none");
	}
}
