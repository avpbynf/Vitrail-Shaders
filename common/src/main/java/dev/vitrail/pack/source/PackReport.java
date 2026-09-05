package dev.vitrail.pack.source;

import dev.vitrail.pack.option.PackOption;
import dev.vitrail.pack.program.ProgramSet;

import dev.vitrail.Vitrail;

import java.util.ArrayList;
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

	/**
	 * Reports the one pack that is about to be drawn.
	 * <p>
	 * One and not the folder. Reading every pack there cost eight zips and a hundred lines at every
	 * startup for the seven that were not going to be drawn, and a pack nobody had selected could
	 * stop the client from reaching its first frame. What the whole corpus says is measured out of
	 * the game, against the same columns, which is what this report was shaped for in the first place.
	 */
	public static void log(LoadedPack pack) {
		Vitrail.logger().info("{}{}", PREFIX, PackStats.tsvHeader());
		Vitrail.logger().info("{}{}", PREFIX, pack.stats().tsvLine(pack.packName()));
		detail(pack);
	}

	/**
	 * The block that is not there, said in its own terms. Written here rather than where the failure
	 * is caught, so that it carries the same prefix as every line the report would have printed: a
	 * reader, or a script, filtering on that prefix would otherwise find an absent block and no
	 * marker at all.
	 */
	public static void couldNotRead(String name, Exception cause) {
		Vitrail.logger().warn("{}{} could not be read, so there is no report of it", PREFIX, name,
				cause);
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
					properties.blendCount(), pack.disabledPrograms().size());

			logIfAny("  keys not read", properties.ignoredPrefixes());
		} else {
			Vitrail.logger().info("{}  no shaders.properties", PREFIX);
		}

		List<String> places = new ArrayList<>();
		places.add(ProgramSet.ROOT);
		places.addAll(pack.dimensions().names());

		for (String place : places) {
			// A dimension with nothing of its own is a dimension the pack does not touch, and
			// saying so for every one of them buries the ones that matter.
			if (pack.resolved().resolutions(place).isEmpty()) {
				continue;
			}

			Vitrail.logger().info("{}  {}: {} programs served directly, {} through a fallback, {} not served",
					PREFIX, place.isEmpty() ? "(root)" : place,
					pack.resolved().directCount(place), pack.resolved().inheritedCount(place),
					pack.resolved().unservedCount(place));
		}

		Vitrail.logger().info("{}  expansion of {} units: {}", PREFIX, pack.expandedUnits(), pack.expansion());
		if (!pack.expansion().clean()) {
			Vitrail.logger().warn("{}  expansion did not come out clean, see the counters above", PREFIX);
		}

		// One line per shape and not per program, because the file it is written in is shared: the
		// pack builds either way, and what is said is which of its own lines this engine read for it.
		pack.looseConditionals().forEach(loose -> Vitrail.logger().warn(
				"{}  loosely written conditional, read as the reference reads it: {}", PREFIX, loose));

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
