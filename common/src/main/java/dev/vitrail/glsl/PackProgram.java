package dev.vitrail.glsl;

import dev.vitrail.pack.AlphaTest;
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
import dev.vitrail.pack.SamplerTypes;
import dev.vitrail.pack.SettingSet;
import dev.vitrail.pack.ShaderProperties;
import dev.vitrail.pack.ShaderPackSource;
import dev.vitrail.pack.TargetPlan;
import dev.vitrail.pack.TargetSchedule;
import dev.vitrail.pack.TerrainPass;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
	 * @param targets   what the whole dimension declares about its colour targets, which is read
	 *                  from thirty odd programs while only one of them is translated
	 * @param samplers  what each sampler of the translated program is bound to, answered here
	 *                  rather than at draw time so that a name nothing serves is known before a
	 *                  frame rather than during one
	 * @param alphaTest what the fragment stage was translated to discard at. Carried rather than
	 *                  worked out again, because the text has already been written for it and a
	 *                  second answer could differ from the one that is in the shader
	 */
	public record Loaded(String packName, String path, ProgramTranslator.TranslatedProgram program,
			TargetPlan targets, SamplerPlan samplers, AlphaTest alphaTest) {

		/**
		 * The samplers this program declares under a type no pipeline can carry, with their types.
		 * <p>
		 * Both stages at once, which is why it is answered from the translation rather than from the
		 * text of the fragment entry point: Reverie declares its {@code sampler3D} in a header its
		 * vertex stages include as well, and a program is refused whichever of the two carries it.
		 */
		public List<TranslatedUnit.Uniform> unbindable() {
			return this.program.samplers().stream()
					.filter(sampler -> SamplerTypes.refused(sampler.type()))
					.toList();
		}

		/**
		 * The same program bound against another plan, with the reader's own step handed in.
		 * <p>
		 * The chunk passes need both halves of that. They are loaded against a plan of their own,
		 * built without the user's pass filter, while the halves they read have to come from the
		 * schedule of the chain that really runs: two schedules walked over different sets of
		 * passes hand out different parities, and a read on the wrong half is a clear colour, not
		 * an error. And the step is the pass's rather than the file's, because the translucent
		 * pass reads its targets on the sides the deferred stage leaves them, whatever file
		 * serves it.
		 */
		public Loaded rebind(TargetPlan plan, Optional<TargetSchedule.Bound> step) {
			List<String> declared = new ArrayList<>();
			Map<String, String> types = new LinkedHashMap<>();
			for (TranslatedUnit.Uniform sampler : this.program.samplers()) {
				declared.add(sampler.name());
				types.putIfAbsent(sampler.name(), sampler.type());
			}

			return new Loaded(this.packName, this.path, this.program, plan,
					SamplerPlan.of(declared, types, plan, step), this.alphaTest);
		}
	}

	/**
	 * @param place    where the entry points were read from, {@code ""} at the root
	 * @param programs by bare name, {@code composite4}, in the order they run, the final last
	 * @param removed  the full screen programs no pipeline can be built for, by bare name, each with
	 *                 the samplers that did it. They are gone from {@link #programs} and the plan was
	 *                 rebuilt without them, unless the {@code final} is one of them: nothing is then
	 *                 removed at all and {@link ChainPlan#refusals()} refuses the whole chain
	 */
	public record Chain(String packName, String place, TargetPlan targets, ChainPlan chain,
			Map<String, Loaded> programs, Map<String, List<TranslatedUnit.Uniform>> removed) {

		public Chain {
			// Kept in order rather than Map.copyOf: a reader walking this map is walking the frame,
			// and an immutable copy would hand the passes back in whatever order hashing chose.
			programs = Collections.unmodifiableMap(new LinkedHashMap<>(programs));
			removed = Collections.unmodifiableMap(new LinkedHashMap<>(removed));
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
		return load(packPath, path, fullscreen ? VertexInputs.FULLSCREEN : VertexInputs.WORLD,
				chosen, profile);
	}

	/**
	 * @param inputs where the vertex stage takes its inputs from. {@link VertexInputs#TERRAIN} is
	 *               what a chunk mesh of Sodium's is drawn under, and the only mode that answers
	 *               for the names the mesh has not got
	 */
	public static Optional<Loaded> load(Path packPath, String path, VertexInputs inputs,
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
			ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(units, inputs);

			return Optional.of(bind(source.packName(), path, program, targets, AlphaTest.OFF));
		}
	}

	/**
	 * Reads the three programs the chunk renderer draws a section with, in one opening of the pack.
	 * <p>
	 * The three are read together and not one at a time for the reason {@link #loadChain} gives: the
	 * plan reads thirty odd files whatever is asked of it, so three separate calls would pay for
	 * three plans to translate three programs. They are also the same three files as often as not,
	 * every pack of the corpus serving the solid and the cutout pass from one
	 * {@code gbuffers_terrain}; they are still translated once each, because the alpha test differs
	 * between them and it is written into the text.
	 * <p>
	 * A pass the pack serves no program for is simply absent from the answer, and the chunk renderer
	 * keeps the game's own shader for it. That is a normal thing for a pack to do rather than a
	 * failure: nothing in the format obliges a pack to ship a {@code gbuffers_water}.
	 *
	 * @param place where the entry points are read from, {@code world0} or the root, already settled
	 *              by the chain
	 */
	public static Map<TerrainPass, Loaded> loadTerrain(Path packPath, String place,
			Map<String, OptionValue> chosen, String profile) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			OptionIndex options = OptionIndex.build(source);
			ShaderProperties properties = ShaderProperties.parse(source);
			Map<String, OptionValue> fromProfile = profile.isEmpty()
					? Map.of()
					: properties.expandProfile(profile);
			SettingSet settings = SettingSet.resolve(fromProfile, chosen, profile.isEmpty() ? "chosen" : profile);
			IncludeExpander expander = new IncludeExpander(source, options, settings);
			TargetPlan targets = TargetPlan.build(source, options, settings, properties, place);

			DimensionSet dimensions = DimensionSet.discover(source);
			ProgramResolver resolver = ProgramResolver.resolve(ProgramSet.enumerate(source, dimensions),
					dimensions);

			Map<TerrainPass, Loaded> loaded = new LinkedHashMap<>();
			for (TerrainPass pass : TerrainPass.values()) {
				Optional<ProgramResolver.Resolution> resolution = resolver.lookup(place, pass.program());
				if (resolution.isEmpty()) {
					continue;
				}

				// The name the override is written under is the file that really serves the pass and
				// not the one the pass asked for, which is how Iris looks it up: a pack shipping one
				// gbuffers_terrain moves both halves of the chunk pass with one line.
				String servedBy = resolution.get().servedBy();
				String path = pathOf(place, servedBy);
				Map<ProgramStage, ExpandedUnit> units = read(source, expander, path);
				if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT)) {
					continue;
				}

				AlphaTest alphaTest = pass.alphaTest(properties, servedBy);
				loaded.put(pass, bind(source.packName(), path,
						ProgramTranslator.translate(units, VertexInputs.TERRAIN, alphaTest),
						targets, alphaTest));
			}

			return loaded;
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
	 * <p>
	 * A program declaring a sampler the backend cannot bind is the one thing that is dropped, and
	 * that is why the plan is built twice for the packs it happens to. The parity argument above is
	 * exactly why it cannot be trimmed afterwards: the second walk starts from the same files and
	 * the same ranks and hands out the halves again, so what is left is a chain and not the remains
	 * of one. The second walk costs what the first did, seven tenths of a second at worst, and is
	 * only ever paid by a pack that has such a program.
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

			Map<String, ProgramTranslator.TranslatedProgram> translated = new LinkedHashMap<>();
			for (String name : targets.running()) {
				String path = pathOf(place, name);
				Map<ProgramStage, ExpandedUnit> units = read(source, expander, path);
				if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT)) {
					throw new IOException(path + " is meant to run and " + source.packName()
							+ " does not serve both of its stages");
				}

				translated.put(name, translate(path, units));
			}

			Map<String, List<TranslatedUnit.Uniform>> refused = unbindable(translated);
			List<String> refusals = new ArrayList<>();
			if (refused.containsKey(FINAL)) {
				// A final is never offered to a filter and cannot be: with it gone nothing of the
				// chain reaches the screen, so there is nothing left to salvage and the whole pack
				// is refused instead. Mellow and Reverie are both this case as they ship.
				refusals.add(refusal(source.packName(), pathOf(place, FINAL), refused));
			} else if (!refused.isEmpty()) {
				targets = TargetPlan.build(source, options, settings, properties, dimension,
						filter.without(refused.keySet()));
			}

			DimensionSet dimensions = DimensionSet.discover(source);
			ProgramSet programs = ProgramSet.enumerate(source, dimensions);
			ChainPlan chain = ChainPlan.of(targets, ProgramResolver.resolve(programs, dimensions),
					refusals);

			Map<String, Loaded> loaded = new LinkedHashMap<>();
			for (String name : targets.running()) {
				ProgramTranslator.TranslatedProgram program = translated.get(name);
				if (program == null) {
					// The second walk reads the same files with the same ranks and only ever takes
					// programs away, so a name appearing that the first one never had is this class
					// contradicting itself rather than anything a pack can cause.
					throw new IllegalStateException(name + " is in the rebuilt chain of "
							+ source.packName() + " and was never translated");
				}

					// A full screen pass has no alpha test. The fixed function one was for geometry, and
				// nothing in the format lets a composite ask for it.
				loaded.put(name, bind(source.packName(), pathOf(place, name), program, targets,
						AlphaTest.OFF));
			}

			return Optional.of(new Chain(source.packName(), place, targets, chain, loaded, refused));
		}
	}

	/**
	 * The programs no pipeline can be built for, by bare name and in frame order, each with the
	 * samplers that did it.
	 */
	private static Map<String, List<TranslatedUnit.Uniform>> unbindable(
			Map<String, ProgramTranslator.TranslatedProgram> translated) {
		Map<String, List<TranslatedUnit.Uniform>> refused = new LinkedHashMap<>();
		translated.forEach((name, program) -> {
			// Stage by stage, and not through the merged list. That list keeps the first stage to
			// declare a name, so a vertex declaring sampler2D would hide a fragment declaring the
			// same name a sampler3D, and the pipeline would be built against a type one of the two
			// modules does not have. Rare, and the failure it leaves is the raw driver error this
			// whole check exists to replace.
			Map<String, TranslatedUnit.Uniform> found = new LinkedHashMap<>();
			program.stages().values().forEach(stage -> stage.samplers().stream()
					.filter(sampler -> SamplerTypes.refused(sampler.type()))
					.forEach(sampler -> found.putIfAbsent(sampler.name(), sampler)));
			if (!found.isEmpty()) {
				refused.put(name, List.copyOf(found.values()));
			}
		});

		return refused;
	}

	/** One sentence naming the sampler, its type, and how much of the pack goes with it. */
	private static String refusal(String packName, String path,
			Map<String, List<TranslatedUnit.Uniform>> refused) {
		TranslatedUnit.Uniform first = refused.get(FINAL).get(0);
		String line = path + " declares " + first.name() + " as " + first.type()
				+ ", which this backend cannot bind, and a final cannot be taken out of a chain, so "
				+ "nothing of " + packName + " can be drawn";

		return refused.size() == 1
				? line
				: line + " (" + (refused.size() - 1) + " other passes of this place are refused for "
						+ "the same reason: " + names(refused) + ")";
	}

	private static String names(Map<String, List<TranslatedUnit.Uniform>> refused) {
		return refused.keySet().stream()
				.filter(name -> !name.equals(FINAL))
				.collect(Collectors.joining(", "));
	}

	private static ProgramTranslator.TranslatedProgram translate(String path,
			Map<ProgramStage, ExpandedUnit> units) {
		try {
			return ProgramTranslator.translate(units, VertexInputs.FULLSCREEN);
		} catch (RuntimeException e) {
			// Named here rather than let through: the message a translator throws says which line
			// of which unit it choked on and never which program of the chain that unit belongs to.
			throw new IllegalStateException(path + " could not be translated", e);
		}
	}

	/**
	 * Puts a translated program against the plan it will be drawn under. Kept apart from the
	 * translation because a chain that has to be walked twice translates once and binds twice: the
	 * text does not depend on the plan, and only the samplers do.
	 */
	private static Loaded bind(String packName, String path,
			ProgramTranslator.TranslatedProgram program, TargetPlan targets, AlphaTest alphaTest) {
		List<String> declared = new ArrayList<>();
		Map<String, String> types = new LinkedHashMap<>();
		for (TranslatedUnit.Uniform sampler : program.samplers()) {
			declared.add(sampler.name());
			types.putIfAbsent(sampler.name(), sampler.type());
		}

		return new Loaded(packName, path, program, targets,
				SamplerPlan.of(declared, types, targets, path), alphaTest);
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
