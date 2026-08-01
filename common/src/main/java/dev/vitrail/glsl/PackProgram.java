package dev.vitrail.glsl;

import dev.vitrail.pack.IncludeExpander;
import dev.vitrail.pack.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.OptionIndex;
import dev.vitrail.pack.ProgramStage;
import dev.vitrail.pack.SettingSet;
import dev.vitrail.pack.ShaderPackSource;

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

	public record Loaded(String packName, String path, ProgramTranslator.TranslatedProgram program) {
	}

	/**
	 * @param path       where the program sits inside {@code shaders/}, without an extension,
	 *                   for instance {@code world0/final}
	 * @param fullscreen whether it is drawn over a quad rather than over the world
	 * @return empty when the pack does not serve both halves of this program
	 */
	public static Optional<Loaded> load(Path packPath, String path, boolean fullscreen) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			OptionIndex options = OptionIndex.build(source);
			IncludeExpander expander = new IncludeExpander(source, options, SettingSet.defaults());

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

			return Optional.of(new Loaded(source.packName(), path,
					ProgramTranslator.translate(units, fullscreen)));
		}
	}
}
