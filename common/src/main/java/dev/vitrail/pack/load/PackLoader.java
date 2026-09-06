package dev.vitrail.pack.load;

import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.program.ProgramResolver;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.ExpansionStats;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a pack from disk and hands back what was found.
 * <p>
 * The source is closed before the result is returned, so the result cannot hold anything that
 * depends on it still being open. Nothing is compiled and nothing is drawn here: this stage
 * reads and counts, so that what it reports can be checked against measurements taken from the
 * same packs before any of the engine existed.
 */
public final class PackLoader {

	public static final String DIRECTORY_NAME = "shaderpacks";

	private PackLoader() {
	}

	/**
	 * Just {@code shaders.properties}, for the checks that have to be made at every load and before
	 * anything is translated.
	 * <p>
	 * One file out of a pack rather than {@link #load}, which enumerates every program and expands
	 * every include. Those checks run again at every reload, and a reload happens at each portal and
	 * at each Apply, on the render thread; none of them needs any of that. It is one archive opening
	 * more, not one less: what it saves is the weight of that opening rather than their number.
	 */
	public static ShaderProperties properties(Path packPath) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			return ShaderProperties.parse(source);
		}
	}

	public static LoadedPack load(Path packPath) throws IOException {
		long start = System.nanoTime();

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			DimensionSet dimensions = DimensionSet.discover(source);
			ShaderProperties properties = ShaderProperties.parse(source);
			OptionIndex options = source.options();
			ProgramSet programs = ProgramSet.enumerate(source, dimensions);
			ProgramResolver resolved = ProgramResolver.resolve(programs, dimensions);
			PackStats stats = PackStats.measure(source, options);

			// Every entry point is flattened, and the text is then thrown away. This stage is
			// judged on whether the graph resolves, not on what comes out of it, and holding
			// two hundred flattened units would cost a lot of memory to prove nothing.
			SettingSet settings = SettingSet.defaults();

			// The file has conditionals of its own, and they read the settings, so the toggles
			// can only be known once the settings are.
			Set<String> disabled = new LinkedHashSet<>();
			properties.programToggles(settings.globalDefines(options), options).forEach((program, on) -> {
				if (!on) {
					disabled.add(program);
				}
			});

			// This walk is a diagnosis of a pack and not a step of a load: it is made the first
			// time a pack is read and never again for the same one, so what it costs is left out
			// of the load's clock rather than doubling a figure the log invites the two to be
			// compared on. It leaves nothing in the opening's memo either, which is what keeps
			// the note above true: a key here is a file of the pack and each of them comes up
			// once, so there would be nothing to find there and every unit to hold.
			IncludeExpander expander = IncludeExpander.forTheReport(source, settings);
			ExpansionStats expansion = ExpansionStats.NONE;
			int expanded = 0;
			for (ProgramSet.ProgramKey key : programs.keys()) {
				Path file = source.file(key.file()).orElse(null);
				if (file != null) {
					expansion = expansion.plus(expander.expand(file).stats());
					expanded++;
				}
			}

			return new LoadedPack(source.packName(), source.isZip(), dimensions, properties, options,
					programs, resolved, stats, expansion, expanded, expander.looseConditionals(),
					Set.copyOf(disabled), source.caseInsensitiveHits(),
					(System.nanoTime() - start) / 1_000_000L);
		}
	}

	/** Where packs are looked for, next to {@code mods/}, as every other engine does it. */
	public static Path directory(Path gameDirectory) {
		return gameDirectory.resolve(DIRECTORY_NAME);
	}

	/**
	 * Every pack in the directory, in name order. A directory and a zip are both candidates; a
	 * zip that turns out not to be a pack fails when it is opened, not here.
	 */
	public static List<Path> candidates(Path gameDirectory) throws IOException {
		Path directory = directory(gameDirectory);
		if (!Files.isDirectory(directory)) {
			return List.of();
		}

		try (Stream<Path> entries = Files.list(directory)) {
			List<Path> found = new ArrayList<>(entries.filter(PackLoader::looksLikeAPack).toList());
			found.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));

			return List.copyOf(found);
		}
	}

	/**
	 * Whether a path is worth treating as a pack at all, which is the same question the listing above
	 * answers and the one the settings screen asks of a file dropped onto it.
	 */
	public static boolean looksLikeAPack(Path entry) {
		if (Files.isDirectory(entry)) {
			return true;
		}

		Path name = entry.getFileName();

		return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".zip");
	}
}
