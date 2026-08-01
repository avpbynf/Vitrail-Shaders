package dev.vitrail.pack;

import dev.vitrail.Vitrail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes what was read into the log, in the shape of the reference measurements.
 * <p>
 * Milestone 3 has nothing to show on screen, so the log is the whole result and it is written
 * to be compared rather than skimmed: the counters carry the same names and the same order as
 * the columns of the measurement files, so checking the engine is reading two lines side by
 * side. Nothing here prints an absolute path.
 */
public final class PackReport {

	private static final String PREFIX = "[pack] ";

	/** Long lists are cut here; the log is evidence, not a dump of the pack. */
	private static final int SAMPLE = 8;

	private PackReport() {
	}

	public static void logAll(Path gameDirectory) {
		List<Path> candidates;
		try {
			candidates = PackLoader.candidates(gameDirectory);
		} catch (IOException e) {
			Vitrail.logger().error("{}could not read {}", PREFIX, PackLoader.directory(gameDirectory), e);
			return;
		}

		if (candidates.isEmpty()) {
			Vitrail.logger().info("{}no pack in {}, nothing to read", PREFIX, PackLoader.directory(gameDirectory));
			return;
		}

		Vitrail.logger().info("{}{}", PREFIX, PackStats.tsvHeader());

		for (Path candidate : candidates) {
			try {
				LoadedPack pack = PackLoader.load(candidate);
				Vitrail.logger().info("{}{}", PREFIX, pack.stats().tsvLine(pack.packName()));
				detail(pack);
			} catch (IOException | RuntimeException e) {
				String name = candidate.getFileName() == null ? "?" : candidate.getFileName().toString();
				Vitrail.logger().error("{}{} could not be read", PREFIX, name, e);
			}
		}
	}

	private static void detail(LoadedPack pack) {
		PackStats stats = pack.stats();
		ProgramSet programs = pack.programs();

		Vitrail.logger().info("{}{}: {} in {} ms, {} files{}", PREFIX, pack.packName(),
				pack.fromZip() ? "zip" : "directory", pack.loadMillis(), stats.files(),
				pack.caseInsensitiveHits() == 0 ? "" : ", " + pack.caseInsensitiveHits() + " resolved ignoring case");

		Vitrail.logger().info("{}  files by extension {}", PREFIX, stats.filesByExtension());

		// Two counts, deliberately. The first is every file that carries a stage extension,
		// which is what the reference measurements counted. The second is how many of those
		// this engine can actually place, which is lower whenever a pack files programs under a
		// directory of its own or invents a name.
		Vitrail.logger().info("{}  stage files {}, shared bodies {}", PREFIX,
				stats.stageFiles(), stats.includeFiles());

		Vitrail.logger().info("{}  programs recognised {} across {}, by stage {}, {} distinct names", PREFIX,
				programs.count(), programs.countByDimension(), programs.countByStage(), programs.distinctNames());

		Vitrail.logger().info("{}  options {}: toggle {}, value {}, const {}, commented out {}, with a value list {}",
				PREFIX, pack.options().count(),
				pack.options().countByKind(PackOption.Kind.TOGGLE),
				pack.options().countByKind(PackOption.Kind.VALUE),
				pack.options().countByKind(PackOption.Kind.CONST),
				pack.options().disabledCount(), pack.options().withValueListCount());

		Vitrail.logger().info("{}  dimensions {}{}", PREFIX, pack.dimensions().names(),
				pack.dimensions().hasDimensionProperties() ? ", declared in dimension.properties" : "");

		ShaderProperties properties = pack.properties();
		if (properties.present()) {
			Vitrail.logger().info(
					"{}  shaders.properties: {} directives, {} continuations, {} profiles, {} custom uniforms, "
							+ "{} screen tokens, {} sliders, {} blend directives, {} programs switched off",
					PREFIX, properties.directiveCount(), properties.continuationCount(),
					properties.profiles().size(), properties.customUniformTypes().size(),
					properties.screenTokens().size(), properties.sliders().size(),
					properties.blend().size(), pack.disabledPrograms().size());

			logIfAny("  keys not read", properties.ignoredPrefixes());
		} else {
			Vitrail.logger().info("{}  no shaders.properties", PREFIX);
		}

		Vitrail.logger().info("{}  expansion of {} units: {}", PREFIX, pack.expandedUnits(), pack.expansion());
		if (!pack.expansion().clean()) {
			Vitrail.logger().warn("{}  expansion did not come out clean, see the counters above", PREFIX);
		}

		logIfAny("  ignored directories", programs.skippedDirectories());
		logIfAny("  unrecognised program names", programs.skippedNames());

		if (!pack.options().caseCollisions().isEmpty()) {
			Vitrail.logger().warn("{}  settings differing only by case: {}", PREFIX,
					pack.options().caseCollisions());
		}
	}

	private static void logIfAny(String label, Map<String, Integer> counts) {
		if (counts.isEmpty()) {
			return;
		}

		String sample = counts.entrySet().stream()
				.limit(SAMPLE)
				.map(entry -> entry.getKey() + " x" + entry.getValue())
				.reduce((a, b) -> a + ", " + b)
				.orElse("");

		Vitrail.logger().info("{}{} {}: {}{}", PREFIX, label, counts.size(), sample,
				counts.size() > SAMPLE ? ", ..." : "");
	}
}
