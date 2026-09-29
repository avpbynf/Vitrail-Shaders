package dev.vitrail.pack.target;

import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.program.ProgramResolver;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes a shader pack made of a few small text files under a temporary directory and reads its
 * plan the way the game does, so that the tests of this package can drive {@link TargetPlan} and
 * {@link ChainPlan} from the same inputs a real pack gives them and never from a hand built plan.
 * <p>
 * Nothing here is a second reading of the plan: it only spells out the files and calls the
 * production entry points, in the order {@code TargetPlan.build} and {@code ChainPlan.of} document.
 */
final class SyntheticPack {

	private SyntheticPack() {
	}

	/** One pack read: the plan of a place and the chain unfolded from it. */
	record Read(TargetPlan plan, ChainPlan chain) {
	}

	/**
	 * Writes {@code files}, keyed by their path under {@code shaders/}, into the pack directory
	 * {@code name} below {@code root}, and returns it. A file the directory holds from an earlier call
	 * and {@code files} does not name is removed, so that one directory can stand for a long series of
	 * packs without the creation of a new one being what a test spends its time on.
	 */
	static Path write(Path root, String name, Map<String, String> files) {
		Path pack = root.resolve(name);
		Path shaders = pack.resolve("shaders");
		try {
			Files.createDirectories(shaders);
			try (Stream<Path> stale = Files.list(shaders)) {
				for (Path old : stale.toList()) {
					String fileName = String.valueOf(old.getFileName());
					if (Files.isRegularFile(old) && !files.containsKey(fileName)) {
						Files.delete(old);
					}
				}
			}

			for (Map.Entry<String, String> file : files.entrySet()) {
				Path target = shaders.resolve(file.getKey());
				Path parent = target.getParent();
				if (parent != null && !Files.isDirectory(parent)) {
					Files.createDirectories(parent);
				}

				Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return pack;
	}

	/** The plan of the root place, under the default settings, with every pass kept. */
	static Read read(Path pack) {
		return read(pack, "", ChainFilter.ALL);
	}

	static Read read(Path pack, String dimension, ChainFilter filter) {
		return read(pack, dimension, filter, List.of(), ChainPlan.Families.DEFAULT);
	}

	/** The same, told what the engine refuses outright and which families of the world it draws. */
	static Read read(Path pack, List<String> refused, ChainPlan.Families families) {
		return read(pack, "", ChainFilter.ALL, refused, families);
	}

	private static Read read(Path pack, String dimension, ChainFilter filter, List<String> refused,
			ChainPlan.Families families) {
		try (ShaderPackSource source = ShaderPackSource.open(pack)) {
			OptionIndex options = source.options();
			SettingSet settings = SettingSet.defaults();
			ShaderProperties properties = ShaderProperties.parse(source);
			TargetPlan plan = TargetPlan.build(source, options, settings, properties, dimension, filter);

			// The two walks of the archive the plan already made for this opening, asked under the
			// keys it asks them under, so that a second one is not paid for.
			DimensionSet dimensions = source.derived(DimensionSet.class,
					() -> DimensionSet.discover(source));
			ProgramSet programs = source.derived(ProgramSet.class,
					() -> ProgramSet.enumerate(source, dimensions));
			Set<String> off = properties.switchedOff(settings.globalDefines(options), options);
			ProgramResolver resolver = ProgramResolver.resolve(programs, dimensions, off);

			return new Read(plan, ChainPlan.of(plan, resolver, refused, families));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The lines of a text block as one file, so that a test reads like the file it writes. */
	static String lines(String... lines) {
		return String.join("\n", List.of(lines)) + "\n";
	}
}
