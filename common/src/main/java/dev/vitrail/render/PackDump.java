package dev.vitrail.render;

import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.uniform.BiomeCategory;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the block of the program {@code dump=} names out as {@code name = value} text.
 * <p>
 * It is the instrument every milestone since the fifth has been closed with, and it exists because a
 * uniform can be non zero, plausible and wrong: reading the number is the only cheap way to tell,
 * and it costs nothing on screen and no command typed in the game. The values it prints come from
 * the same walk that fills the uniform buffer, a {@link dev.vitrail.uniform.TextSink} standing in
 * for the byte sink, so what is read here is what a shader was handed and not a second look at the
 * catalogue that could disagree with it.
 * <p>
 * Once a second and not once a frame, because the file is meant to be read by a human or tailed by a
 * script, and because two passes of one frame are handed the same numbers anyway. It is written
 * whole each time rather than appended, so what is in it is always this second and never a history
 * to scroll through; a curve is taken by reading it repeatedly, which is what the halflife checks do.
 */
final class PackDump {

	/** The program named by the line, lowercased, matched on the tail of a path. Empty is off. */
	private static volatile String wanted = "";
	private static volatile Path file;
	private static long lastNanos;

	private PackDump() {
	}

	/** Set at every load, from the line {@code options.txt} holds and the game's own directory. */
	static void configure(String program, Path into) {
		wanted = program;
		file = into;
	}

	/**
	 * Takes this frame's dump, if a second has gone by and the line names something that is running.
	 *
	 * @param terrain the pack's terrain programs, empty until they are read, and first on purpose:
	 *                they are answered from a different catalogue, their {@code of_ModelViewMatrix}
	 *                being the world's where a composite's is the identity, which is exactly the pair
	 *                a reading has to tell apart
	 * @param sky     the pack's sky programs, empty until the sky has been read and empty for good
	 *                for a pack that serves none. Named by element as the terrain is by pass: four
	 *                of the six are one file, so a line that said gbuffers_skybasic four times would
	 *                not say which piece was read
	 * @param passes  the chain's own passes, empty for the frame or two before they are built
	 */
	static void take(String place, int load, Collection<TerrainProgram> terrain,
			Collection<SkyProgram> sky, List<PackPass> passes, WorldState world) {
		if (wanted.isEmpty() || file == null) {
			return;
		}

		long now = System.nanoTime();
		if (lastNanos != 0L && now - lastNanos < 1_000_000_000L) {
			return;
		}

		lastNanos = now;
		List<String> running = new ArrayList<>();
		String path = null;
		String decoded = null;

		// Named by pass and not only by path: two of the three are usually the same file, and a line
		// that said gbuffers_terrain twice would not say which of the two was read. The label is
		// matched as well as the path, so dump=cutout names the pass and dump=gbuffers_terrain still
		// names the file, taking the first pass drawn with it.
		for (TerrainProgram program : terrain) {
			running.add(program.label());
			if (decoded == null && (names(program.path()) || names(program.label()))) {
				path = program.label();
				decoded = program.decoded(world);
			}
		}

		for (SkyProgram program : sky) {
			running.add(program.label());
			if (decoded == null && (names(program.path()) || names(program.label()))) {
				path = program.label();
				decoded = program.decoded(world);
			}
		}

		for (PackPass pass : passes) {
			running.add(pass.path());
			if (decoded == null && names(pass.path())) {
				path = pass.path();
				decoded = pass.decoded(world);
			}
		}

		if (decoded == null) {
			// Nothing is built yet, which is the first frame or two rather than a wrong name. The
			// line has to survive that or a dump asked for in the file would be turned off before
			// the thing it names exists.
			if (running.isEmpty()) {
				return;
			}

			Vitrail.logger().warn("{}={} names no program of this chain, so nothing is dumped. This "
					+ "chain runs: {}", EngineOptions.DUMP_KEY, wanted, running);
			wanted = "";

			return;
		}

		write("# " + place + ", " + path + ", load " + load + "\n" + situation(world) + decoded);
	}

	private static boolean names(String path) {
		return path.toLowerCase(Locale.ROOT).endsWith(wanted);
	}

	/**
	 * Failing to write is said once and then dropped. A dump that cannot be taken is a lost
	 * measurement, and turning it into a crash would make a debugging aid the reason the game stopped.
	 */
	private static void write(String text) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Vitrail.logger().warn("Could not write the decoded dump to {}: {}", file, e.toString());
			wanted = "";
		}
	}

	/**
	 * Where the camera stood when these values were taken, and what the biome table says about it.
	 * <p>
	 * Written into the same file as the values and read from the same frame, because a reading is
	 * only worth something if what it describes is known at the same instant. A pack's biome uniforms
	 * are smoothed over a second, so a player who walked between the screenshot and the file is enough
	 * to make a table that works look broken, or the other way round, which has already happened once.
	 * <p>
	 * The define is printed beside the number the uniform carries because those two are the pair that
	 * has to agree. {@link BiomeClassifier} hands out both from one table so that they cannot drift;
	 * this line is what proves it rather than assuming it.
	 */
	private static String situation(WorldState world) {
		int id = world.biomeId();
		// Backwards, on purpose. Asking the define table which name carries the number the uniform
		// just reported is the whole check: the two are built from one walk of the registry and are
		// meant to agree, and a number no define carries is that promise broken, said out loud here
		// rather than found later as a pack lighting the wrong biome.
		String name = EngineDefines.machine().biomes().entrySet().stream()
				.filter(entry -> entry.getValue() == id)
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse("!! no BIOME_ define carries this number");

		BiomeCategory[] categories = BiomeCategory.values();
		int category = world.biomeCategory();

		return "# biome " + id + " is BIOME_" + name + ", category " + category + " "
				+ (category >= 0 && category < categories.length ? categories[category] : "?") + "\n"
				+ "# camera " + Math.round(world.cameraPosition().x()) + ", "
				+ Math.round(world.cameraPosition().y()) + ", "
				+ Math.round(world.cameraPosition().z()) + "\n";
	}
}
