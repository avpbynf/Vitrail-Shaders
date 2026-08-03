package dev.vitrail.render;

import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.ShaderPackSource;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Which world the player is in, in the terms a pack is written in, and which of the pack's
 * directories that world is drawn from.
 * <p>
 * A dimension directory replaces the root rather than layering over it, so the answer here decides
 * the programs, the colour targets, the clear colours and the settings of everything downstream.
 * Getting it wrong is silent: the Nether drawn with the overworld's chain is a picture, and a
 * plausible one.
 * <p>
 * The whole chain is read again when the answer changes, and nothing is kept warm for the world
 * left behind. BSL's colour targets measure 99 MiB at 1920x1009, so holding the three vanilla
 * worlds ready would cost about 300 MiB of video memory for the whole session to save one hitch at
 * a portal. The hitch is the cheaper of the two and it is named in the log rather than suffered
 * quietly.
 */
public final class PackPlace {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String END = "minecraft:the_end";

	/** What the loaded pack maps worlds to folders with, so that asking costs no disk. */
	private static volatile DimensionSet known;

	/**
	 * The last world the client had. A client between two worlds answers with it rather than with
	 * nothing, which is what keeps the menu from being a world of its own: quitting from the Nether
	 * and joining again would otherwise be two reloads, and the overworld a fresh client starts on
	 * would read as a change on the first frame drawn.
	 */
	private static volatile String seen = OVERWORLD;

	/** The world the chain now loaded was read for, kept for the line that says what a reload cost. */
	private static volatile String loaded = OVERWORLD;

	/**
	 * The directory that world resolved to, which is the only thing a reload would change.
	 * <p>
	 * Compared rather than the world itself, because a pack answers several worlds out of one
	 * directory: every pack of the corpus puts its catch-all on {@code world0}, so walking from the
	 * overworld into a modded dimension both of them serve would otherwise cost a full reload,
	 * every target reallocated, to rebuild a chain identical to the one already running. And the
	 * log would announce a change of place that did not happen.
	 */
	private static volatile String loadedPlace = "";

	private PackPlace() {
	}

	/**
	 * Reads the pack's own map of worlds to folders and answers where this world's programs live,
	 * the empty string for the root.
	 * <p>
	 * One more opening of the pack per load, which is two files and a directory listing against the
	 * second the load itself costs. The map is kept afterwards because {@link #world()} needs it
	 * every second and must not touch the disk to get it.
	 */
	public static String place(Path packPath) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			known = DimensionSet.discover(source);
		}

		return known.place(world());
	}

	/**
	 * The world the player is in, by the identifier a pack names it with.
	 * <p>
	 * Ported from Iris {@code Iris.java:606-641}. A world the pack does not name itself is answered
	 * for by its sky rather than by its identifier, so that a modded overworld gets the overworld's
	 * programs instead of falling through to the root, and a pack that does name it is taken at its
	 * word. Vanilla passes through untouched: the Nether's sky is neither of the two.
	 */
	public static String world() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (level == null) {
			return seen;
		}

		String world = level.dimension().identifier().toString();
		DimensionSet dimensions = known;
		if (dimensions == null || !dimensions.declares(world)) {
			DimensionType.Skybox sky = level.dimensionType().skybox();
			if (sky == DimensionType.Skybox.END) {
				world = END;
			} else if (sky == DimensionType.Skybox.OVERWORLD) {
				world = OVERWORLD;
			}
		}

		seen = world;

		return world;
	}

	/**
	 * Whether the player has left the DIRECTORY the loaded chain was read for. Before the first
	 * read there is no table to ask, and nothing is loaded either, so nothing has moved.
	 */
	public static boolean moved() {
		DimensionSet dimensions = known;

		return dimensions != null && !dimensions.place(world()).equals(loadedPlace);
	}

	/** The world the loaded chain was read for, for the line that says what a reload costs. */
	public static String settled() {
		return loaded;
	}

	/** Takes this world as the one the chain was read for, whether or not the read got that far. */
	public static void settle() {
		DimensionSet dimensions = known;
		loaded = world();
		loadedPlace = dimensions == null ? "" : dimensions.place(loaded);
	}
}
