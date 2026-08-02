package dev.vitrail.glsl;

import dev.vitrail.pack.IncludeExpander;
import dev.vitrail.pack.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.OptionIndex;
import dev.vitrail.pack.OptionValue;
import dev.vitrail.pack.ProgramSet;
import dev.vitrail.pack.ProgramStage;
import dev.vitrail.pack.SamplerPlan;
import dev.vitrail.pack.SettingSet;
import dev.vitrail.pack.ShaderProperties;
import dev.vitrail.pack.ShaderPackSource;
import dev.vitrail.pack.TargetPlan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads one program out of a pack and translates it, with nothing from Minecraft involved.
 * <p>
 * Kept apart from the renderer for the same reason the rest of this package is: it can then be
 * run against the whole corpus in seconds, and a mistake in it shows up before a game session
 * rather than during one.
 */
public final class PackProgram {

	private static final List<ProgramStage> STAGES =
			List.of(ProgramStage.VERTEX, ProgramStage.GEOMETRY, ProgramStage.FRAGMENT);

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

			Map<ProgramStage, ExpandedUnit> units = new LinkedHashMap<>();
			for (ProgramStage stage : STAGES) {
				Optional<Path> file = source.file(path + "." + stage.extension());
				if (file.isPresent()) {
					units.put(stage, expander.expand(file.get()));
				}
			}

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

	/** {@code world0/final} sits in world0; a program at the root belongs to no dimension. */
	private static String dimensionOf(String path) {
		int slash = path.indexOf('/');

		return slash < 0 ? ProgramSet.ROOT : path.substring(0, slash);
	}
}
