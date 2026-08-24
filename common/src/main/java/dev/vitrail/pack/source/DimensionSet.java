package dev.vitrail.pack.source;

import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.target.TargetPlan;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The directories a program may live in: the root of {@code shaders/}, and one per dimension.
 * <p>
 * A dimension directory is not always named after a number. A pack may map its own folder names
 * to dimensions in {@code dimension.properties}, and folders declared that way have to be read
 * from it: guessing from the name alone loses them, and their programs then look missing while
 * sitting in plain sight.
 * <p>
 * The same file is also what says which folder a given world is drawn from, which is the question
 * {@link #place(String)} answers. A pack that ships no such file means the convention by it, and
 * a pack that ships one means only what it wrote there: the two are not layered, or a pack that
 * deliberately sends the End to its overworld folder would be overruled by its own folder names.
 */
public final class DimensionSet {

	/** The conventional names, used when the pack declares nothing. */
	private static final Pattern CONVENTIONAL = Pattern.compile("^world-?\\d+$");

	private static final Pattern DECLARATION =
			Pattern.compile("^\\s*dimension\\.([A-Za-z0-9_-]+)\\s*=(.*)$");

	/**
	 * Any line the reference's properties reader would keep: not a comment, and carrying an
	 * equals sign. What it decides is only whether the file counts as saying something, see
	 * {@link #discover}.
	 */
	private static final Pattern ANY_KEY = Pattern.compile("^\\s*[^#!\\s][^=]*=.*$");

	private static final String PROPERTIES = "dimension.properties";

	/** What the three conventional folders mean when no {@code dimension.properties} says. */
	private static final Map<String, String> CONVENTION = Map.of(
			"world0", "minecraft:overworld",
			"world-1", "minecraft:the_nether",
			"world1", "minecraft:the_end");

	/** The identifier that is not one: a folder declared under it takes every world unnamed. */
	private static final String ANY = "*";

	private final List<String> names;
	private final Map<String, String> places;
	private final boolean declared;

	private DimensionSet(List<String> names, Map<String, String> places, boolean declared) {
		this.names = List.copyOf(names);
		this.places = Map.copyOf(places);
		this.declared = declared;
	}

	public static DimensionSet discover(ShaderPackSource source) throws IOException {
		Set<String> found = new LinkedHashSet<>();
		Map<String, String> places = new LinkedHashMap<>();
		boolean declared = false;

		Optional<java.nio.file.Path> properties = source.file(PROPERTIES);
		if (properties.isPresent()) {
			// The engine's table and never the pack's settings: the reference preprocesses this
			// file before the include graph exists, so a conditional here can only see the
			// environment defines (ShaderPack.java:125-126). A declaration in a dead branch is
			// no declaration.
			Map<String, String> defines = EngineDefines.table(EngineDefines.machine());
			ConditionStack conditions = new ConditionStack();

			// A folder to the LAST worlds it was assigned, in first-assignment order. The
			// reference reads this file as ordered properties, so re-assigning dimension.<folder>
			// keeps the key's place, drops the earlier line's worlds entirely, and it is the
			// FOLDER that is the key: two lines about one folder are one entry, two folders
			// claiming one world are settled by whichever entry sits later.
			Map<String, String> assignments = new LinkedHashMap<>();

			for (String line : source.readLines(properties.get())) {
				Matcher directive = ShaderProperties.DIRECTIVE.matcher(line);
				if (directive.matches()) {
					ShaderProperties.applyDirective(directive.group(1), line, conditions, defines);
					continue;
				}

				if (!conditions.active()) {
					continue;
				}

				Matcher declaration = DECLARATION.matcher(line);
				if (declaration.matches()) {
					assignments.put(declaration.group(1), declaration.group(2));
				}

				// ANY live key suppresses the conventional folders, well formed or not: the
				// reference's test is that the parsed file is not empty (ShaderPack.java:127),
				// not that it holds a dimension line.
				if (ANY_KEY.matcher(line).matches()) {
					declared = true;
				}
			}

			assignments.forEach((folder, worlds) -> {
				found.add(folder);
				for (String world : worlds.trim().split("\\s+", -1)) {
					if (!world.isEmpty()) {
						places.put(normalise(world), folder);
					}
				}
			});
		}

		for (String directory : source.topLevelDirectories()) {
			if (CONVENTIONAL.matcher(directory).matches()) {
				found.add(directory);
			}
		}

		if (!declared) {
			// Ported from Iris ShaderPack.java:132-148, defaults included: the overworld folder is
			// also what every world the pack never heard of is drawn from, and the other two answer
			// for themselves alone.
			CONVENTION.forEach((directory, world) -> {
				if (found.contains(directory)) {
					places.put(world, directory);
				}
			});

			if (found.contains("world0")) {
				places.put(ANY, "world0");
			}
		}

		return new DimensionSet(found.stream().sorted().toList(), places, declared);
	}

	public boolean isDimensionDirectory(String name) {
		return this.names.contains(name);
	}

	public List<String> names() {
		return this.names;
	}

	public boolean hasDimensionProperties() {
		return this.declared;
	}

	/**
	 * Which directory this world's programs are read from, the empty string for the root.
	 * <p>
	 * The root is the answer for a world the pack says nothing about and has no catch-all for. A
	 * folder named here answers for itself when it is on disk, whatever it holds, an empty one
	 * drawing nothing rather than falling back; named and absent, the root answers instead. Which
	 * of the two it is, {@link TargetPlan} decides off the directory's existence and never off its
	 * contents: a name here is where to look, not what is there.
	 *
	 * @param world the dimension's identifier, {@code minecraft:the_nether}
	 */
	public String place(String world) {
		String named = this.places.get(world);

		return named != null ? named : this.places.getOrDefault(ANY, "");
	}

	/** Whether the pack names this world itself, rather than answering for it out of a catch-all. */
	public boolean declares(String world) {
		return this.places.containsKey(world);
	}

	/** {@code the_nether} and {@code minecraft:the_nether} are one world, as they are in Iris. */
	private static String normalise(String world) {
		if (ANY.equals(world)) {
			return ANY;
		}

		return world.indexOf(':') < 0 ? "minecraft:" + world : world;
	}
}
