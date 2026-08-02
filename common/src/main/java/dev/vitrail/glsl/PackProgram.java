package dev.vitrail.glsl;

import dev.vitrail.pack.ChainFilter;
import dev.vitrail.pack.ChainPlan;
import dev.vitrail.pack.DimensionSet;
import dev.vitrail.pack.IncludeExpander;
import dev.vitrail.pack.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.OptionIndex;
import dev.vitrail.pack.OptionValue;
import dev.vitrail.pack.ProgramResolver;
import dev.vitrail.pack.ProgramSet;
import dev.vitrail.pack.ProgramStage;
import dev.vitrail.pack.SamplerPlan;
import dev.vitrail.pack.SettingSet;
import dev.vitrail.pack.ShaderProperties;
import dev.vitrail.pack.ShaderPackSource;
import dev.vitrail.pack.TargetPlan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a program, or a whole chain of them, out of a pack and translates it, with nothing from
 * Minecraft involved.
 * <p>
 * Kept apart from the renderer for the same reason the rest of this package is: it can then be
 * run against the whole corpus in seconds, and a mistake in it shows up before a game session
 * rather than during one.
 */
public final class PackProgram {

	private static final List<ProgramStage> STAGES =
			List.of(ProgramStage.VERTEX, ProgramStage.GEOMETRY, ProgramStage.FRAGMENT);

	private static final String FINAL = "final";

	private PackProgram() {
	}

	/**
	 * @param targets  what the whole dimension declares about its colour targets, which is read
	 *                 from thirty odd programs while only one of them is translated
	 * @param samplers what each sampler of the translated program is bound to, answered here
	 *                 rather than at draw time so that a name nothing serves is known before a
	 *                 frame rather than during one
	 */
	public record Loaded(String packName, String path, ProgramTranslator.TranslatedProgram program,
			TargetPlan targets, SamplerPlan samplers) {
	}

	/**
	 * @param place    where the entry points were read from, {@code ""} at the root
	 * @param programs by bare name, {@code composite4}, in the order they run, the final last
	 */
	public record Chain(String packName, String place, TargetPlan targets, ChainPlan chain,
			Map<String, Loaded> programs) {

		public Chain {
			// Kept in order rather than Map.copyOf: a reader walking this map is walking the frame,
			// and an immutable copy would hand the passes back in whatever order hashing chose.
			programs = Collections.unmodifiableMap(new LinkedHashMap<>(programs));
		}
	}

	/**
	 * @param path       where the program sits inside {@code shaders/}, without an extension,
	 *                   for instance {@code world0/final}
	 * @param fullscreen whether it is drawn over a quad rather than over the world
	 * @return empty when the pack does not serve both halves of this program
	 */
	public static Optional<Loaded> load(Path packPath, String path, boolean fullscreen) throws IOException {
		return load(packPath, path, fullscreen, Map.of());
	}

	/**
	 * @param chosen settings to override, by the name the pack declares them under. Milestone 3
	 *               already resolves these; handing them in here is what lets a pack's own
	 *               features be turned on without touching the pack.
	 */
	public static Optional<Loaded> load(Path packPath, String path, boolean fullscreen,
			Map<String, OptionValue> chosen) throws IOException {
		return load(packPath, path, fullscreen, chosen, "");
	}

	/**
	 * @param profile a profile the pack declares, applied underneath {@code chosen} so that a
	 *                single setting can still be overridden on top of it. Profiles chain, and
	 *                milestone 3 already follows the chain: BSL's ULTRA is HIGH is MEDIUM is LOW.
	 */
	public static Optional<Loaded> load(Path packPath, String path, boolean fullscreen,
			Map<String, OptionValue> chosen, String profile) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			OptionIndex options = OptionIndex.build(source);
			ShaderProperties properties = ShaderProperties.parse(source);
			Map<String, OptionValue> fromProfile = profile.isEmpty()
					? Map.of()
					: properties.expandProfile(profile);
			SettingSet settings = SettingSet.resolve(fromProfile, chosen, profile.isEmpty() ? "chosen" : profile);
			IncludeExpander expander = new IncludeExpander(source, options, settings);

			Map<ProgramStage, ExpandedUnit> units = read(source, expander, path);

			// Both halves or nothing. Iris carries a default vertex shader for packs old enough to
			// ship only a fragment one; that is a compatibility case and not this one.
			if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT)) {
				return Optional.empty();
			}

			// Inside the same opening of the pack, because a zip closed behind us invalidates every
			// path taken from it and the plan reads thirty more files than this program does.
			TargetPlan targets = TargetPlan.build(source, options, settings, properties, dimensionOf(path));
			ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(units, fullscreen);
			List<String> declared = program.samplers().stream()
					.map(TranslatedUnit.Uniform::name)
					.toList();

			return Optional.of(new Loaded(source.packName(), path, program, targets,
					SamplerPlan.of(declared, targets, path)));
		}
	}

	/**
	 * Reads one dimension's whole chain in one opening of the pack.
	 * <p>
	 * Calling {@link #load} once per program would open the pack and build a whole plan each time,
	 * and the plan reads thirty odd files whatever is asked of it: the cost of the chain would be
	 * the cost of the plan times the number of programs. Read this way it is two tenths of a
	 * second for BSL's nine programs and eight tenths for Reverie's twenty one, measured out of
	 * game on the zipped packs, so one plan and one expander are shared by all of them.
	 * <p>
	 * A program the schedule says runs and that the pack does not serve with both stages, or that
	 * throws while being translated, fails the whole chain rather than being dropped. Dropping one
	 * would move the half every later pass reads and writes, and the result of that is not an
	 * error but a picture that looks right and is not.
	 *
	 * @param dimension the directory the programs are wanted from, {@code world0}. The place is
	 *                  worked out from it by the plan and may turn out to be the root
	 * @param filter    what the user asked to run on top of what the pack keeps
	 * @return empty when the pack serves no final with both stages in that place
	 */
	public static Optional<Chain> loadChain(Path packPath, String dimension,
			Map<String, OptionValue> chosen, String profile, ChainFilter filter) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			OptionIndex options = OptionIndex.build(source);
			ShaderProperties properties = ShaderProperties.parse(source);
			Map<String, OptionValue> fromProfile = profile.isEmpty()
					? Map.of()
					: properties.expandProfile(profile);
			SettingSet settings = SettingSet.resolve(fromProfile, chosen, profile.isEmpty() ? "chosen" : profile);
			IncludeExpander expander = new IncludeExpander(source, options, settings);

			TargetPlan targets = TargetPlan.build(source, options, settings, properties, dimension, filter);
			String place = targets.place();

			// Asked before anything is expanded. A place that serves no final draws nothing at all,
			// and finding that out after nine programs have been read is nine wasted seconds.
			if (!targets.running().contains(FINAL) || !serves(source, pathOf(place, FINAL))) {
				return Optional.empty();
			}

			DimensionSet dimensions = DimensionSet.discover(source);
			ProgramSet programs = ProgramSet.enumerate(source, dimensions);
			ChainPlan chain = ChainPlan.of(targets, ProgramResolver.resolve(programs, dimensions));

			Map<String, Loaded> loaded = new LinkedHashMap<>();
			for (String name : targets.running()) {
				String path = pathOf(place, name);
				Map<ProgramStage, ExpandedUnit> units = read(source, expander, path);
				if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT)) {
					throw new IOException(path + " is meant to run and " + source.packName()
							+ " does not serve both of its stages");
				}

				loaded.put(name, translate(source.packName(), path, units, targets));
			}

			return Optional.of(new Chain(source.packName(), place, targets, chain, loaded));
		}
	}

	private static Loaded translate(String packName, String path,
			Map<ProgramStage, ExpandedUnit> units, TargetPlan targets) {
		ProgramTranslator.TranslatedProgram program;
		try {
			program = ProgramTranslator.translate(units, true);
		} catch (RuntimeException e) {
			// Named here rather than let through: the message a translator throws says which line
			// of which unit it choked on and never which program of the chain that unit belongs to.
			throw new IllegalStateException(path + " could not be translated", e);
		}

		List<String> declared = program.samplers().stream()
				.map(TranslatedUnit.Uniform::name)
				.toList();

		return new Loaded(packName, path, program, targets, SamplerPlan.of(declared, targets, path));
	}

	/** Both halves, as files. Iris carries a default vertex stage for old packs and this does not. */
	private static boolean serves(ShaderPackSource source, String path) {
		return source.file(path + "." + ProgramStage.VERTEX.extension()).isPresent()
				&& source.file(path + "." + ProgramStage.FRAGMENT.extension()).isPresent();
	}

	private static Map<ProgramStage, ExpandedUnit> read(ShaderPackSource source,
			IncludeExpander expander, String path) throws IOException {
		Map<ProgramStage, ExpandedUnit> units = new LinkedHashMap<>();
		for (ProgramStage stage : STAGES) {
			Optional<Path> file = source.file(path + "." + stage.extension());
			if (file.isPresent()) {
				units.put(stage, expander.expand(file.get()));
			}
		}

		return units;
	}

	private static String pathOf(String place, String program) {
		return place.isEmpty() ? program : place + "/" + program;
	}

	/** {@code world0/final} sits in world0; a program at the root belongs to no dimension. */
	private static String dimensionOf(String path) {
		int slash = path.indexOf('/');

		return slash < 0 ? ProgramSet.ROOT : path.substring(0, slash);
	}
}
