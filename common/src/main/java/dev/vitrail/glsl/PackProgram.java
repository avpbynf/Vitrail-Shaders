package dev.vitrail.glsl;

import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.program.ProgramResolver;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.SamplerTypes;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.pack.texture.PackTextures;
import dev.vitrail.pack.texture.TextureStage;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
	 * @param supplied  the names the pack supplies a file for at this program's stage, carried for
	 *                  {@link Loaded#rebind}: a plan rebuilt without it would put the colour target
	 *                  back behind a name the pack moved onto a lookup table of its own
	 */
	public record Loaded(String packName, String path, ProgramTranslator.TranslatedProgram program,
			TargetPlan targets, SamplerPlan samplers, AlphaTest alphaTest, Set<String> supplied) {

		public Loaded {
			supplied = Set.copyOf(supplied);
		}

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
		 * The storage blocks this program declares, which nothing in this game binds. Empty is the
		 * norm, and the one pack of the corpus that is not says so in every ordinary program.
		 * <p>
		 * Worth asking separately from {@link #unbindable}: a sampler the backend refuses stops the
		 * pipeline from being built, which every caller already notices, and a storage block does
		 * not. It compiles, it is left out of the bind group, and the descriptor keeps the binding
		 * the pack wrote.
		 */
		public List<String> storageBlocks() {
			return this.program.stages().values().stream()
					.flatMap(stage -> stage.notes().storageBlocks().stream())
					.distinct()
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
					SamplerPlan.of(declared, types, plan, step, this.supplied), this.alphaTest,
					this.supplied);
		}
	}

	/**
	 * Why no pipeline can be built for one program, which is one word over three different
	 * failures. They are kept apart because what a reader is meant to do about them differs:
	 * the first is a texture this engine could serve one day, the second waits on machinery
	 * nothing here runs, and the third cannot be served at all under this API.
	 *
	 * @param unbindable samplers declared under a shape the backend refuses, and that nothing this
	 *                   engine can serve stands behind. A directive may well name one: what it
	 *                   named was refused in its turn, with its own line and its own reason
	 * @param volumes    the same shapes, for names an {@code image} directive hangs a volume on.
	 *                   Nothing is missing there but the compute pass that would fill it, and this
	 *                   engine runs none; every one of them in the corpus sits under a setting that
	 *                   is off by default
	 * @param storage    storage blocks. {@code IntermediaryShaderModule.createFromSpirv} lists a
	 *                   module's uniform buffers and its sampled images and nothing else, so one of
	 *                   these never enters a bind group, its binding is never rewritten, and the
	 *                   descriptor stays on the number the pack wrote
	 */
	public record Refusal(List<TranslatedUnit.Uniform> unbindable,
			List<TranslatedUnit.Uniform> volumes, List<String> storage) {

		public Refusal {
			unbindable = List.copyOf(unbindable);
			volumes = List.copyOf(volumes);
			storage = List.copyOf(storage);
		}

		public boolean any() {
			return !this.unbindable.isEmpty() || !this.volumes.isEmpty() || !this.storage.isEmpty();
		}

		/** What is wrong, in as many clauses as there are kinds of it, each naming its own names. */
		public String reason() {
			List<String> said = new ArrayList<>();
			if (!this.unbindable.isEmpty()) {
				said.add("declares " + describe(this.unbindable) + ", a shape this backend cannot bind, "
						+ "with nothing behind that name this engine knows how to serve");
			}

			if (!this.volumes.isEmpty()) {
				said.add("declares " + describe(this.volumes) + ", a volume an image directive asks a "
						+ "compute pass to fill and this engine runs no compute pass");
			}

			if (!this.storage.isEmpty()) {
				said.add("declares the storage block " + String.join(", ", this.storage)
						+ ", which nothing binds: the game lists a module's uniform buffers and its "
						+ "sampled images and neither includes one, so its descriptor stays on the "
						+ "binding the pack wrote");
			}

			return String.join(", and ", said);
		}

		/** {@code colortex6 as sampler3D}. The type is beside the name because the name misleads. */
		private static String describe(List<TranslatedUnit.Uniform> samplers) {
			return samplers.stream()
					.map(sampler -> sampler.name() + " as " + sampler.type())
					.collect(Collectors.joining(", "));
		}
	}

	/**
	 * @param place    where the entry points were read from, {@code ""} at the root
	 * @param programs by bare name, {@code composite4}, in the order they run, the final last
	 * @param removed  the full screen programs no pipeline can be built for, by bare name, each with
	 *                 what did it. They are gone from {@link #programs} and the plan was rebuilt
	 *                 without them, unless the {@code final} is one of them: nothing is then removed
	 *                 at all and {@link ChainPlan#refusals()} refuses the whole chain
	 */
	public record Chain(String packName, String place, TargetPlan targets, ChainPlan chain,
			Map<String, Loaded> programs, Map<String, Refusal> removed) {

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
		return load(packPath, path, inputs, inputs.elements(), chosen, profile);
	}

	/**
	 * @param boundElements the elements of the vertex format the pass this program is drawn in
	 *                      actually binds. Only {@link VertexInputs#SKY} needs it: the sky binds
	 *                      four formats between its passes, so one program is loaded once per
	 *                      format it may be drawn against
	 */
	public static Optional<Loaded> load(Path packPath, String path, VertexInputs inputs,
			List<String> boundElements, Map<String, OptionValue> chosen, String profile)
			throws IOException {
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
			PackTextures textures = textures(source, properties, options, settings);
			ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(units, inputs,
					boundElements, AlphaTest.OFF, false, programOf(path), textures.volumes());

			return Optional.of(bind(source.packName(), path, program, targets, AlphaTest.OFF,
					textures));
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
			PackTextures textures = textures(source, properties, options, settings);

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
				// The pass's own program and not the file that serves it, for the reason the alpha
				// test is taken that way: what the engine supplies belongs to what is being drawn.
				loaded.put(pass, bind(source.packName(), path,
						ProgramTranslator.translate(units, VertexInputs.TERRAIN,
								VertexInputs.TERRAIN.elements(), alphaTest, pass.covers(),
								pass.program(), textures.volumes()),
						targets, alphaTest, textures));
			}

			return loaded;
		}
	}

	/**
	 * Reads and translates one of the three programs the game may draw its sky with, against the
	 * vertex format the pass that draws it really binds.
	 * <p>
	 * One at a time and not three like the terrain, because the three are not asked for together: the
	 * game opens a render pass per element of the sky and each one binds its own format, so a program
	 * is loaded when the pass that draws it is first reached. The fallback tree is walked like
	 * everywhere else, so a pack shipping only {@code gbuffers_basic} still serves the disc.
	 *
	 * @param program the bare name the game would draw with, {@code gbuffers_skybasic}
	 * @param bound   the elements of the format that pass binds, in the format's own order. Exactly
	 *                these are declared: the sky binds four different formats between its passes, and
	 *                an element left undeclared shifts the location of every one after it in silence
	 * @return empty when the pack serves neither this program nor anything it falls back to, in which
	 *         case the game keeps its own sky
	 */
	public static Optional<Loaded> loadSky(Path packPath, String place, String program,
			List<String> bound, Map<String, OptionValue> chosen, String profile) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			OptionIndex options = OptionIndex.build(source);
			ShaderProperties properties = ShaderProperties.parse(source);
			Map<String, OptionValue> fromProfile = profile.isEmpty()
					? Map.of()
					: properties.expandProfile(profile);
			SettingSet settings = SettingSet.resolve(fromProfile, chosen, profile.isEmpty() ? "chosen" : profile);
			IncludeExpander expander = new IncludeExpander(source, options, settings);
			TargetPlan targets = TargetPlan.build(source, options, settings, properties, place);
			PackTextures textures = textures(source, properties, options, settings);

			DimensionSet dimensions = DimensionSet.discover(source);
			ProgramResolver resolver = ProgramResolver.resolve(ProgramSet.enumerate(source, dimensions),
					dimensions);
			Optional<ProgramResolver.Resolution> resolution = resolver.lookup(place, program);
			if (resolution.isEmpty()) {
				return Optional.empty();
			}

			String path = pathOf(place, resolution.get().servedBy());
			Map<ProgramStage, ExpandedUnit> units = read(source, expander, path);
			if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT)) {
				return Optional.empty();
			}

			// No alpha test anywhere in the sky: the format has no line for one, and nothing the game
			// draws there is cut out. The program the engine supplies uniforms for is the one the pass
			// wanted, not the file that ended up serving it, as everywhere else.
			return Optional.of(bind(source.packName(), path,
					ProgramTranslator.translate(units, VertexInputs.SKY, bound, AlphaTest.OFF, false,
							program, textures.volumes()),
					targets, AlphaTest.OFF, textures));
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

			PackTextures textures = textures(source, properties, options, settings);
			Map<String, ProgramTranslator.TranslatedProgram> translated = new LinkedHashMap<>();
			for (String name : targets.running()) {
				String path = pathOf(place, name);
				Map<ProgramStage, ExpandedUnit> units = read(source, expander, path);
				if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT)) {
					throw new IOException(path + " is meant to run and " + source.packName()
							+ " does not serve both of its stages");
				}

				translated.put(name, translate(path, units, textures.volumes()));
			}

			Map<String, Refusal> refused =
					unbindable(translated, properties.imageSamplers(settings.globalDefines(options)));
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
						AlphaTest.OFF, textures));
			}

			return Optional.of(new Chain(source.packName(), place, targets, chain, loaded, refused));
		}
	}

	/**
	 * The programs no pipeline can be built for, by bare name and in frame order, each with what
	 * did it.
	 *
	 * @param filled the sampler names an {@code image} directive hangs a volume on, which is what
	 *               tells a name this engine has nothing to put behind from one that waits on a
	 *               compute pass
	 */
	private static Map<String, Refusal> unbindable(
			Map<String, ProgramTranslator.TranslatedProgram> translated, Set<String> filled) {
		Map<String, Refusal> refused = new LinkedHashMap<>();
		translated.forEach((name, program) -> {
			// Stage by stage, and not through the merged list. That list keeps the first stage to
			// declare a name, so a vertex declaring sampler2D would hide a fragment declaring the
			// same name a sampler3D, and the pipeline would be built against a type one of the two
			// modules does not have. Rare, and the failure it leaves is the raw driver error this
			// whole check exists to replace.
			Map<String, TranslatedUnit.Uniform> found = new LinkedHashMap<>();
			List<String> storage = new ArrayList<>();
			program.stages().values().forEach(stage -> {
				stage.samplers().stream()
						.filter(sampler -> SamplerTypes.refused(sampler.type()))
						.forEach(sampler -> found.putIfAbsent(sampler.name(), sampler));
				stage.notes().storageBlocks().stream()
						.filter(block -> !storage.contains(block))
						.forEach(storage::add);
			});

			List<TranslatedUnit.Uniform> volumes = found.values().stream()
					.filter(sampler -> filled.contains(sampler.name()))
					.toList();
			List<TranslatedUnit.Uniform> plain = found.values().stream()
					.filter(sampler -> !filled.contains(sampler.name()))
					.toList();

			Refusal refusal = new Refusal(plain, volumes, storage);
			if (refusal.any()) {
				refused.put(name, refusal);
			}
		});

		return refused;
	}

	/** One sentence naming what refuses the final, and how much of the pack goes with it. */
	private static String refusal(String packName, String path, Map<String, Refusal> refused) {
		String line = path + " " + refused.get(FINAL).reason()
				+ ", and a final cannot be taken out of a chain, so nothing of " + packName
				+ " can be drawn";

		return refused.size() == 1
				? line
				: line + " (" + (refused.size() - 1) + " other passes of this place are refused as "
						+ "well: " + names(refused) + ")";
	}

	private static String names(Map<String, Refusal> refused) {
		return refused.keySet().stream()
				.filter(name -> !name.equals(FINAL))
				.collect(Collectors.joining(", "));
	}

	private static ProgramTranslator.TranslatedProgram translate(String path,
			Map<ProgramStage, ExpandedUnit> units, Map<String, VolumeAtlas> volumes) {
		try {
			return ProgramTranslator.translate(units, VertexInputs.FULLSCREEN,
					VertexInputs.FULLSCREEN.elements(), AlphaTest.OFF, false, programOf(path), volumes);
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
			ProgramTranslator.TranslatedProgram program, TargetPlan targets, AlphaTest alphaTest,
			PackTextures textures) {
		List<String> declared = new ArrayList<>();
		Map<String, String> types = new LinkedHashMap<>();
		for (TranslatedUnit.Uniform sampler : program.samplers()) {
			declared.add(sampler.name());
			types.putIfAbsent(sampler.name(), sampler.type());
		}

		// The stage of the program the pass draws, which is what narrows a texture.STAGE.NAME
		// override to the half of the frame the pack meant it for.
		Set<String> supplied = TextureStage.of(programOf(path))
				.map(textures::suppliedTo)
				.orElse(Set.of());

		return new Loaded(packName, path, program, targets,
				SamplerPlan.of(declared, types, targets, path, supplied), alphaTest, supplied);
	}

	/**
	 * What the pack ships behind its own names, read inside the opening that is already in hand.
	 * <p>
	 * Read here as well as beside the device, and deliberately: this side needs the shape of a
	 * volume to write the arithmetic into the shader and the list of names to bind, and the other
	 * side needs the bytes. Both read the same file against the same settings, so the two answers
	 * are one answer computed twice, and keeping the translation able to run without a device is
	 * worth the second pass over one text file.
	 */
	private static PackTextures textures(ShaderPackSource source, ShaderProperties properties,
			OptionIndex options, SettingSet settings) throws IOException {
		return PackTextures.read(properties, settings.globalDefines(options), source);
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

	/** The bare name {@code world0/gbuffers_entities} is asked for, without its place. */
	private static String programOf(String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}
}
