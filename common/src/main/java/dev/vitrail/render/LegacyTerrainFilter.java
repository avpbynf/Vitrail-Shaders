package dev.vitrail.render;

import dev.vitrail.Vitrail;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether a pack's terrain reads the block atlas through the game's filtered sampler, as this
 * engine did before it bound Iris's unfiltered one.
 * <p>
 * <strong>It exists so that the difference can be SEEN rather than argued about.</strong> A filter
 * decides the silhouette of cutout foliage, and whatever that changes on screen is exactly the kind
 * of thing an eye judges badly across two launches minutes apart, and badly again across two jars.
 * One jar and one keypress puts both states in the same window, the same world and the same second,
 * with one variable between them.
 * <p>
 * A file {@code vitrail/legacy-terrain-filter} in the game directory, or
 * {@code -Dvitrail.legacyTerrainFilter=true}. Read again at every pack load, so the Reload Shaders
 * key is the whole gesture. F3 and T is not one: a resource reload does not read the pack again, so
 * it leaves the state where it was, whether or not it rebuilds the pipelines the pack draws with.
 * The state is written to the log
 * BOTH WAYS, once per pack load at the first terrain the pack draws, so a reading taken afterwards
 * can always say which of the two it belongs to.
 * <p>
 * It is not a setting anybody should keep on. The filtered sampler is what this engine had wrong,
 * and Iris has never bound it.
 */
public final class LegacyTerrainFilter {

	private static final boolean PROPERTY = Boolean.getBoolean("vitrail.legacyTerrainFilter");

	private static final String ARM_FILE = "legacy-terrain-filter";

	/**
	 * Settled at the head of each pack load, like {@link DriverTrig}, and volatile for the reason
	 * {@code RenderScale.percent} gives: the load may run on a loading worker while every reader
	 * is the render thread.
	 */
	private static volatile boolean armed;

	private static volatile boolean announced;

	private LegacyTerrainFilter() {
	}

	/**
	 * Whether a pack's terrain is to read the atlas through the game's own filtered sampler.
	 *
	 * @return true while the file or the property asks for the old filter
	 */
	public static boolean armed() {
		announce(armed);

		return armed;
	}

	/**
	 * Read at the head of a pack load, so the file is looked at once per load and not per pass.
	 * The game directory is handed in rather than asked of the game, as {@link DriverTrig#read}
	 * takes it: the load already knows where it is reading from.
	 */
	public static void read(Path gameDirectory) {
		armed = PROPERTY
				|| Files.isRegularFile(gameDirectory.resolve(Vitrail.MOD_ID).resolve(ARM_FILE));
		announced = false;
	}

	/**
	 * Said once per pack load, and said BOTH WAYS on purpose. A line that only appears in one of the
	 * two states makes every load without one ambiguous, and an ambiguous reading is worth nothing.
	 */
	private static void announce(boolean on) {
		if (announced) {
			return;
		}

		announced = true;
		if (on) {
			Vitrail.logger().warn("A pack's terrain reads the block atlas through the GAME's "
					+ "filtered sampler, asked for by vitrail/{} or by "
					+ "-Dvitrail.legacyTerrainFilter. That is what this engine bound before and what "
					+ "Iris never binds. Remove it and reload the pack to go back",
					ARM_FILE);
			return;
		}

		Vitrail.logger().info("A pack's terrain reads the block atlas unfiltered, as it does under "
				+ "Iris. vitrail/{} would put the game's filtered sampler back, and this line is "
				+ "said either way so that a reading can name the state it was taken under",
				ARM_FILE);
	}
}
