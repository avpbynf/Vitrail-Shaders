package dev.vitrail.glsl;

import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.program.ProgramResolver;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.SourceMentions;
import dev.vitrail.pack.source.ShaderProperties;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.SamplerTypes;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.pack.texture.CustomStorage;
import dev.vitrail.pack.texture.PackTextures;
import dev.vitrail.pack.model.TextureStage;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

	/**
	 * The names the engine has to have an answer about before its first frame, and which it
	 * therefore reads out of the pack's text rather than out of a program that may not be read
	 * yet. Both are things paid for every frame and useful only to a pack that reads them.
	 * <p>
	 * {@code watershadow} rides with {@code shadowtex1} because it is what moves the meaning of
	 * the bare {@code shadow}: a program declaring it reads the map WITHOUT the translucents
	 * under that name, so a pack writing it is a pack that may want the copy even though it
	 * never spells {@code shadowtex1} ({@code SamplerPlan.withoutTranslucents}).
	 */
	private static final Set<String> SETTLED_EARLY =
			Set.of("shadowtex1", "watershadow", "depthtex2");

	/** The one name of the format the game's clouds are drawn under. */
	private static final String CLOUD_PROGRAM = "gbuffers_clouds";

	private PackProgram() {
	}

	/**
	 * One program of the pack, translated, with everything the draw will need to bind it.
	 *
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
		 * Whether this program voxelises, in Iris's sense: a geometry stage is present, or an image
		 * load / store uniform survived translation.
		 * <p>
		 * Iris reads the geometry file at {@code shadows/ShadowRenderer.java:163-165}. It has a
		 * {@code setUsesImages} for the image half ({@code :224-225}) and calls it from nowhere: the
		 * flag is computed at {@code pipeline/IrisRenderingPipeline.java:453-456} and never read, so
		 * an image alone decides nothing there. Here it does count, which is this engine's own
		 * answer and not the reference's. A {@code .gsh} is enough even when this engine never
		 * binds it; an image uniform that the preprocessor left standing is enough without one. A name gated off, Complementary LOW's {@code voxel_img} behind
		 * {@code COLORED_LIGHTING_INTERNAL}, does not count.
		 */
		public boolean voxelises() {
			if (this.program.stages().containsKey(ProgramStage.GEOMETRY)) {
				return true;
			}

			return this.program.samplers().stream().anyMatch(Loaded::imageUniform);
		}

		private static boolean imageUniform(TranslatedUnit.Uniform sampler) {
			String type = sampler.type();

			return type.startsWith("image") || type.startsWith("iimage")
					|| type.startsWith("uimage");
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
					.filter(sampler -> !CustomImages.named(sampler.name()))
					.toList();
		}

		/**
		 * The storage blocks this program declares. Empty is the norm. Complementary Ultra writes
		 * {@code blockDataBuffer} when world-space reflections are on; those names are bound when
		 * {@link CustomStorage#named} answers, and refused when it does not.
		 * <p>
		 * Worth asking separately from {@link #unbindable}: a sampler the backend refuses stops the
		 * pipeline from being built, which every caller already notices, and a storage block does
		 * not. It compiles, and without a bind-group entry its descriptor keeps the binding the
		 * pack wrote.
		 */
		public List<String> storageBlocks() {
			return this.program.stages().values().stream()
					.flatMap(stage -> stage.notes().storageBlocks().stream())
					.map(TranslatedUnit.StorageBlock::name)
					.distinct()
					.toList();
		}

		/**
		 * Whether this program was translated for a piece the game draws at full light, and therefore
		 * whether the name {@code lightmap} is bound to one white texel rather than to the game's own
		 * image.
		 * <p>
		 * <strong>Read off the translation and not tabulated beside it</strong>, which is
		 * {@link #readsGameTransforms}'s reason with a different consequence: full light is TWO
		 * answers, the constant the vertex head hands the light map names and the texture the fragment
		 * stage samples, and a pack multiplies one by the other. Given the constant alone the piece
		 * comes out with whatever light the mob is standing in; given the texture alone it comes out
		 * at the light the mesh carries. Both are darker than the game would have drawn it, and
		 * neither says anything.
		 * <p>
		 * Iris answers both off one field for the same reason, {@code ShaderKey}'s lighting model
		 * reaching the head through {@code gl/state/ShaderAttributeInputs.java:42} and the sampler
		 * through {@code samplers/IrisSamplers.java:202-206}.
		 */
		public boolean fullbright() {
			return this.program.inputs().fullbright();
		}

		/**
		 * Whether any stage of this program reads the game's own per draw transforms, and therefore
		 * whether the pipeline has to carry the bind group that block is bound through.
		 * <p>
		 * <strong>This is the one answer, and everything about the block is asked of it.</strong> The
		 * text declares the block, the pipeline layout carries the group and the draw binds the slice,
		 * and a stage declaring a block its layout has not got is a draw that throws by name in the
		 * middle of the world. Tabulating any of the three beside the translation would be the same
		 * answer written twice, and the copy that drifted would be that throw.
		 * <p>
		 * <strong>What can turn it on is a whole family and not only its roots.</strong>
		 * {@link LegacyGlsl#bindsGameTransforms} walks the fallback CHAIN, so twelve names pass it:
		 * the five entity roots, six more under them, and the glint, which is a root of its own for
		 * the reason that predicate gives. Eight of the twelve are drawn, and all eight by one door,
		 * {@code EntityDraw.record}, which is where the slice comes from. The other four are asked for
		 * by nobody - {@code shadow_block}, which is a root the shadow table has no row for,
		 * {@code gbuffers_entities_glowing}, {@code gbuffers_lightning} and {@code shadow_lightning} -
		 * and a name nobody asks for is a program nobody builds a pipeline out of.
		 * <p>
		 * Both stages at once, for {@link #storageBlocks}'s reason: a bind group belongs to the
		 * pipeline and not to the stage that named it.
		 */
		public boolean readsGameTransforms() {
			return this.program.stages().values().stream()
					.anyMatch(stage -> stage.notes().gameTextureMatrix() > 0
							|| stage.notes().gameModelView() > 0);
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
	 * Why no pipeline can be built for one program, which is one word over two different
	 * failures. They are kept apart because what a reader is meant to do about them differs:
	 * the first is a texture this engine could serve one day, the second cannot be served at all
	 * under this API.
	 *
	 * @param unbindable samplers declared under a shape the backend refuses, and that nothing this
	 *                   engine can serve stands behind. A directive may well name one: what it
	 *                   named was refused in its turn, with its own line and its own reason
	 * @param storage    storage blocks this engine has no {@code bufferObject} for. A served one
	 *                   is left out of this list and enters the bind group as a uniform name the
	 *                   mixins turn into a storage buffer
	 */
	public record Refusal(List<TranslatedUnit.Uniform> unbindable, List<String> storage) {

		public Refusal {
			unbindable = List.copyOf(unbindable);
			storage = List.copyOf(storage);
		}

		public boolean any() {
			return !this.unbindable.isEmpty() || !this.storage.isEmpty();
		}

		/** What is wrong, in as many clauses as there are kinds of it, each naming its own names. */
		public String reason() {
			List<String> said = new ArrayList<>();
			if (!this.unbindable.isEmpty()) {
				said.add("declares " + describe(this.unbindable) + ", a shape this backend cannot bind, "
						+ "with nothing behind that name this engine knows how to serve");
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
	 * The full screen chain of one place, in the order it runs, with what could not be built.
	 *
	 * @param place    where the entry points were read from, {@code ""} at the root
	 * @param programs by bare name, {@code composite4}, in the order they run, the final last
	 * @param mentions which of the few names the engine has to settle before the first frame appear
	 *                 anywhere in this pack's text. Read here because the pack is open here and
	 *                 every source is walked here anyway; asked because six of the seven geometry
	 *                 families are read at the first draw of their own kind, so nothing else can
	 *                 answer for the pack as a whole this early
	 * @param removed  the full screen programs no pipeline can be built for, by bare name, each with
	 *                 what did it. They are gone from {@link #programs} and the plan was rebuilt
	 *                 without them, unless the {@code final} is one of them: nothing is then removed
	 *                 at all and {@link ChainPlan#refusals()} refuses the whole chain
	 */
	public record Chain(String packName, String place, TargetPlan targets, ChainPlan chain,
			Map<String, Loaded> programs, Map<String, Refusal> removed, SourceMentions mentions) {

		public Chain {
			// Kept in order rather than Map.copyOf: a reader walking this map is walking the frame,
			// and an immutable copy would hand the passes back in whatever order hashing chose.
			programs = Collections.unmodifiableMap(new LinkedHashMap<>(programs));
			removed = Collections.unmodifiableMap(new LinkedHashMap<>(removed));
		}
	}

	/**
	 * Reads and translates one program of the pack, with nothing of the pack's own overridden.
	 *
	 * @param path       where the program sits inside {@code shaders/}, without an extension,
	 *                   for instance {@code world0/final}
	 * @param fullscreen whether it is drawn over a quad rather than over the world
	 * @return empty when the pack does not serve both halves of this program
	 */
	public static Optional<Loaded> load(Path packPath, String path, boolean fullscreen) throws IOException {
		return load(packPath, path, fullscreen, Map.of());
	}

	/**
	 * The same, with some of the pack's settings answered from outside it.
	 *
	 * @param chosen settings to override, by the name the pack declares them under. Milestone 3
	 *               already resolves these; handing them in here is what lets a pack's own
	 *               features be turned on without touching the pack.
	 */
	public static Optional<Loaded> load(Path packPath, String path, boolean fullscreen,
			Map<String, OptionValue> chosen) throws IOException {
		return load(packPath, path, fullscreen, chosen, "");
	}

	/**
	 * The same, read under one of the profiles the pack declares.
	 *
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
	 * The same, with the vertex stage's inputs named outright rather than read off a flag.
	 *
	 * @param inputs where the vertex stage takes its inputs from. {@link VertexInputs#TERRAIN} is
	 *               what a chunk mesh of Sodium's is drawn under, and the only mode that answers
	 *               for the names the mesh has not got
	 */
	public static Optional<Loaded> load(Path packPath, String path, VertexInputs inputs,
			Map<String, OptionValue> chosen, String profile) throws IOException {
		return load(packPath, path, inputs, inputs.elements(), chosen, profile);
	}

	/**
	 * The same, told which elements of that format the pass really binds.
	 *
	 * @param boundElements the elements of the vertex format the pass this program is drawn in
	 *                      actually binds. Only {@link VertexInputs#SKY} needs it: the sky binds
	 *                      four formats between its passes, so one program is loaded once per
	 *                      format it may be drawn against
	 */
	public static Optional<Loaded> load(Path packPath, String path, VertexInputs inputs,
			List<String> boundElements, Map<String, OptionValue> chosen, String profile)
			throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			OptionIndex options = source.options();
			ShaderProperties properties = ShaderProperties.parse(source);
			Map<String, OptionValue> fromProfile = profile.isEmpty()
					? Map.of()
					: properties.expandProfile(profile);
			SettingSet settings = SettingSet.resolve(fromProfile, chosen, profile.isEmpty() ? "chosen" : profile);
			IncludeExpander expander = new IncludeExpander(source, settings);

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
	 * A compute-only program, translated, with everything its dispatch has to be sized from.
	 * <p>
	 * Iris sizes a dispatch four ways ({@code ComputeProgram.java:47-64}). A
	 * {@code const ivec3 workGroups} is a count of work groups and is dispatched exactly as
	 * written, whatever size the pass runs at. A {@code const vec2 workGroupsRender} is a
	 * multiplier of that size, and the count becomes that many pixels divided by the shader's own
	 * {@code local_size}. A program writing neither covers the whole of the size, which is the
	 * same division with the multiplier left at one.
	 * <p>
	 * <strong>The fourth is a known hole here.</strong> An {@code indirect.<pass>} line in
	 * {@code shaders.properties} points at a buffer holding the counts
	 * ({@code ShaderProperties.java:374-380}), and Iris then dispatches indirectly and reads no
	 * directive at all: {@code getWorkGroups} answers null for such a program
	 * ({@code ComputeProgram.java:48}) and shadow composites carry the pointer like every other
	 * family ({@code ShadowCompositeRenderer.java:349}). Nothing here reads that line, so a
	 * program relying on it would be dispatched off its directives instead, which is the wrong
	 * count. No pack of the corpus writes one.
	 * <p>
	 * <strong>Only the first road was read here, and a program on either derived road was
	 * dispatched as a single work group.</strong> One group of a sixteen by sixteen local size
	 * covers two hundred and fifty-six texels of a target that asked for the screen, so the pass
	 * ran, wrote its corner, and left everything past it holding whatever the frame before had put
	 * there. Nothing errors and nothing is missing: the effect appears in one small square and is
	 * stale everywhere else.
	 * <p>
	 * None of this is a formula over depth or clip space, so the reversed Z this engine rasterises
	 * with does not enter it. A count of work groups reads the same here as it does there.
	 *
	 * @param groupsX the count the pack wrote, or -1 in all three when it wrote no
	 *                {@code workGroups}. Absence cannot be spelt with nought, which is a value a
	 *                pack does mean: Reverie writes {@code ivec3(0, 0, 0)} on the branch that turns
	 *                a pass off, and Iris dispatches nothing for it
	 * @param renderX the multiplier of the pass size, or -1 in both when the pack wrote no
	 *                {@code workGroupsRender}
	 * @param localX  the shader's own {@code local_size_x}, or -1 in both when it is not written
	 *                as a literal there. A derived road cannot be walked without it, and
	 *                {@link #sized()} is what says so
	 */
	public record Compute(Loaded loaded, int groupsX, int groupsY, int groupsZ, float renderX,
			float renderY, int localX, int localY) {

		/** Whether the pack asked for a count of its own, the one road that ignores the size. */
		public boolean fixed() {
			return this.groupsX >= 0;
		}

		/** Whether the pack asked for a fraction of the size rather than the whole of it. */
		public boolean relative() {
			return this.renderX >= 0.0F;
		}

		/**
		 * Whether the count can be worked out at all. A program that asked for a count of its own
		 * always can; a program on a derived road cannot without the local size it divides by.
		 * <p>
		 * <strong>There is no defensible default for a local size, and guessing one is a freeze
		 * rather than a wrong image.</strong> Read as one where the shader means sixteen by
		 * sixteen, a full screen road dispatches a group per pixel: two million groups at 1080p
		 * where the shader asked for eight thousand, each still running its real two hundred and
		 * fifty-six invocations. The caller is expected to leave such a program undispatched and
		 * say so, which is why this is asked rather than answered with a number.
		 */
		public boolean sized() {
			return fixed() || this.localX > 0;
		}

		/**
		 * How many groups to dispatch over a pass run at that size, for a program {@link #sized()}
		 * answers for. Iris hands its shadow composites the main render target's size
		 * ({@code ShadowCompositeRenderer.java:212}), so that is the size this is asked about, and
		 * the answer moves with the window.
		 */
		public int[] groupsAt(int width, int height) {
			if (fixed()) {
				return new int[] { this.groupsX, this.groupsY, this.groupsZ };
			}

			int spanX = relative() ? (int) Math.ceil(width * (double) this.renderX) : width;
			int spanY = relative() ? (int) Math.ceil(height * (double) this.renderY) : height;
			return new int[] { cover(spanX, this.localX), cover(spanY, this.localY), 1 };
		}

		/** Groups enough to cover that many pixels, the last of them part used, never fewer. */
		private static int cover(int span, int local) {
			return span <= 0 ? 0 : (span + local - 1) / local;
		}
	}

	private static final Pattern WORK_GROUPS = Pattern.compile(
			"const\\s+ivec3\\s+workGroups\\s*=\\s*ivec3\\s*\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)");

	/**
	 * The second road's directive. Its arguments are floats and a pack does write them with no
	 * decimal point at all, Reverie's {@code vec2(1, 1)}, so the number is matched in both forms.
	 */
	private static final Pattern WORK_GROUPS_RENDER = Pattern.compile(
			"const\\s+vec2\\s+workGroupsRender\\s*=\\s*vec2\\s*\\(\\s*(-?\\d*\\.?\\d+)[fF]?\\s*,"
					+ "\\s*(-?\\d*\\.?\\d+)[fF]?\\s*\\)");

	/**
	 * What both derived roads divide the pass size by, written on the shader's {@code layout}.
	 * The value is captured whole rather than as digits, so that one written through a macro is
	 * found and refused rather than missed and replaced by a default.
	 */
	private static final Pattern LOCAL_SIZE =
			Pattern.compile("local_size_([xy])\\s*=\\s*([^,)\\s]+)");

	/** A literal the reader may believe, as opposed to a macro nothing here substitutes. */
	private static final Pattern LITERAL = Pattern.compile("\\d+");

	/**
	 * One {@code .csh} entry point, translated on its own. Empty when the pack does not ship it.
	 * <p>
	 * Reads the opening the caller already holds rather than one of its own, and this is the entry
	 * point where that matters most: a pack's shadow computes are read in a loop, so an opening
	 * apiece meant mounting the archive and rebuilding the whole settings index once per compute
	 * program rather than once for the load.
	 */
	public static Optional<Compute> loadCompute(OpenedPack pack, String path) throws IOException {
		ShaderPackSource source = pack.source();
		OptionIndex options = pack.options();
		ShaderProperties properties = pack.properties();
		SettingSet settings = pack.settings();
		IncludeExpander expander = new IncludeExpander(source, settings);
		Optional<Path> file = source.file(path + "." + ProgramStage.COMPUTE.extension());
		if (file.isEmpty()) {
			return Optional.empty();
		}

		ExpandedUnit unit = expander.expand(file.get());
		TargetPlan targets = TargetPlan.build(source, options, settings, properties,
				dimensionOf(path));
		PackTextures textures = textures(source, properties, options, settings);
		Map<ProgramStage, ExpandedUnit> units = new LinkedHashMap<>();
		units.put(ProgramStage.COMPUTE, unit);
		ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(units,
				VertexInputs.FULLSCREEN, VertexInputs.FULLSCREEN.elements(), AlphaTest.OFF, false,
				programOf(path), textures.volumes());
		int[] groups = workGroupsOf(unit);
		float[] render = workGroupsRenderOf(unit);
		int[] local = localSizeOf(unit);
		return Optional.of(new Compute(
				bind(source.packName(), path, program, targets, AlphaTest.OFF, textures),
				groups[0], groups[1], groups[2], render[0], render[1], local[0], local[1]));
	}

	/**
	 * Iris reads {@code const ivec3 workGroups} off the live preprocessor branch. Complementary
	 * lists every volume size, dead branches kept in the text, so a reader that took them all
	 * would end on the 128 one even on Ultra.
	 * <p>
	 * <strong>The LAST live match wins, which is Iris' rule</strong>: it walks every directive of
	 * the source and lets each overwrite the one before ({@code ProgramSet.java:245-248}). One
	 * question deserves one tie-break, so the multiplier and the local size below are read the
	 * same way. It settles nothing in the corpus, where the branches are exclusive and exactly one
	 * match of each survives, and it is what a pack listing two live ones would expect.
	 * <p>
	 * <strong>A negative count is clamped to nought, and that is a divergence.</strong> What Iris
	 * does: it stores the value as parsed ({@code ComputeDirectiveParser.java:34-37}) and hands it
	 * to {@code glDispatchCompute} unexamined ({@code IrisRenderSystem.java:322-323}). What
	 * prevents it here: {@code vkCmdDispatch} counts groups in unsigned words, so a minus one
	 * arrives as four billion, which is past {@code maxComputeWorkGroupCount} on every device and
	 * is a lost device rather than a slow frame. What it costs the image: nothing measurable, the
	 * pack having asked for a dispatch no driver can make either way. Nought itself is NOT
	 * clamped, a pack writing it to turn a pass off, which is why an absent directive is answered
	 * with -1 and not with a count.
	 */
	private static int[] workGroupsOf(ExpandedUnit unit) {
		int[] groups = { -1, -1, -1 };
		List<String> lines = unit.lines();
		for (int i = 0; i < lines.size(); i++) {
			if (!unit.isLive(i)) {
				continue;
			}

			Matcher matcher = WORK_GROUPS.matcher(lines.get(i));
			while (matcher.find()) {
				groups[0] = Math.max(0, Integer.parseInt(matcher.group(1)));
				groups[1] = Math.max(0, Integer.parseInt(matcher.group(2)));
				groups[2] = Math.max(0, Integer.parseInt(matcher.group(3)));
			}
		}

		return groups;
	}

	/**
	 * The multiplier of the pass size, which is the road Iris takes when the pack asked for no
	 * count of its own. Read off the live branch like the count above, and for the same reason.
	 * <p>
	 * <strong>A pack may write the value as a macro rather than as a literal</strong>, Reverie's
	 * {@code vec2(VOLUMETRICS_RES, VOLUMETRICS_RES)}, and Iris reads it substituted because it
	 * parses a preprocessed source. Nothing substitutes it here, so such a directive reads as
	 * absent and the program covers the whole pass rather than the fraction the pack asked for.
	 * That errs by a factor the local size caps, the tile of pixels a group covers being the same
	 * on both roads, so unlike a missing local size it stays a wrong image and not a stalled one.
	 * No pack of the corpus writes it that way on a program this engine dispatches.
	 */
	private static float[] workGroupsRenderOf(ExpandedUnit unit) {
		float[] render = { -1.0F, -1.0F };
		List<String> lines = unit.lines();
		for (int i = 0; i < lines.size(); i++) {
			if (!unit.isLive(i)) {
				continue;
			}

			Matcher matcher = WORK_GROUPS_RENDER.matcher(lines.get(i));
			while (matcher.find()) {
				render[0] = Math.max(0.0F, Float.parseFloat(matcher.group(1)));
				render[1] = Math.max(0.0F, Float.parseFloat(matcher.group(2)));
			}
		}

		return render;
	}

	/**
	 * The shader's own work group size, which both derived roads divide the pass size by, or -1 in
	 * both where it cannot be read as a literal. Iris asks the linked program for it
	 * ({@code GL_COMPUTE_WORK_GROUP_SIZE}) and is never in that position; nothing is linked here
	 * at the moment the question is asked, so it comes off the same live text as the directives
	 * and by the same last match wins rule.
	 * <p>
	 * <strong>An axis written through a macro refuses the whole answer rather than falling back on
	 * the GLSL default of one.</strong> The default is right for an axis the shader really leaves
	 * out and catastrophic for one it writes and this engine cannot read: sixteen read as one is
	 * two million groups at 1080p where the shader asked for eight thousand, at the shader's real
	 * two hundred and fifty-six invocations apiece, which is a frozen game rather than a wrong
	 * picture. Which of the two it is cannot be told from a missed match, so the value is captured
	 * whole and refused when it is not a number. {@code local_size_x} is required of every compute
	 * shader, so finding none of it at all is the same failure to read and gets the same answer.
	 */
	private static int[] localSizeOf(ExpandedUnit unit) {
		int[] local = { -1, 1 };
		List<String> lines = unit.lines();
		for (int i = 0; i < lines.size(); i++) {
			if (!unit.isLive(i)) {
				continue;
			}

			Matcher matcher = LOCAL_SIZE.matcher(lines.get(i));
			while (matcher.find()) {
				if (!LITERAL.matcher(matcher.group(2)).matches()) {
					return new int[] { -1, -1 };
				}

				local["x".equals(matcher.group(1)) ? 0 : 1] =
						Math.max(1, Integer.parseInt(matcher.group(2)));
			}
		}

		return local;
	}

	/**
	 * What every family of programs works out of one opening before reading its own files: the
	 * expander, the plan of the place, the pack's textures and the program tree. Kept on the
	 * opening for its life, so that the six families a load reads through one opening pay for
	 * it once rather than each walking the archive again for the same answers.
	 */
	private record Reading(IncludeExpander expander, TargetPlan targets, PackTextures textures,
			ProgramResolver resolver) {
	}

	/** The settings and the place a reading was taken for; the settings compare by identity. */
	private record ReadingKey(SettingSet settings, String place) {
	}

	/**
	 * What decides which elements a vertex stage reads: the unit, the mesh contract, the program
	 * name and the volumes behind its samplers. The alpha test and the coverage mask are left out
	 * because both act on the fragment stage alone, so two passes served by one file share the
	 * answer whatever they discard at.
	 */
	private record ReadsKey(ExpandedUnit vertex, VertexInputs inputs, String program,
			Map<String, VolumeAtlas> volumes) {
	}

	/**
	 * {@link ProgramTranslator#reads}, once per opening for each vertex unit and contract. The
	 * answer is a whole translation of the stage thrown away but for a set of names, and the
	 * chunk passes asked it up to six times per place for three distinct answers.
	 */
	private static Set<String> reads(ShaderPackSource source, ExpandedUnit vertex,
			VertexInputs inputs, AlphaTest alphaTest, boolean coverage, String program,
			Map<String, VolumeAtlas> volumes) throws IOException {
		return source.derived(new ReadsKey(vertex, inputs, program, volumes),
				() -> ProgramTranslator.reads(vertex, inputs, alphaTest, coverage, program, volumes));
	}

	private static Reading reading(OpenedPack pack, String place) throws IOException {
		ShaderPackSource source = pack.source();
		return source.derived(new ReadingKey(pack.settings(), place), () -> {
			IncludeExpander expander = new IncludeExpander(source, pack.settings());
			TargetPlan targets = TargetPlan.build(source, pack.options(), pack.settings(),
					pack.properties(), place);
			PackTextures textures = textures(source, pack.properties(), pack.options(), pack.settings());
			DimensionSet dimensions = source.derived(DimensionSet.class,
					() -> DimensionSet.discover(source));
			ProgramResolver resolver = ProgramResolver.resolve(
					source.derived(ProgramSet.class, () -> ProgramSet.enumerate(source, dimensions)),
					dimensions, pack.properties().switchedOff(pack.settings().globalDefines(pack.options()),
							pack.options()));

			return new Reading(expander, targets, textures, resolver);
		});
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
	 * <p>
	 * <strong>The passes are walked twice, and that is what settles the mesh.</strong> The chunk
	 * mesh carries what the pack reads and no more, so the first walk rewrites each vertex stage and
	 * asks it what it reads, and only the union of the six is enough to say what any of them may
	 * declare. The second walk is the translation proper, against that answer. What the two walks
	 * cost is one extra rewrite per vertex stage; what a single walk would cost is either a mesh
	 * built before it is known or six programs each declaring its own idea of the format, which
	 * shifts every location after the first difference and says nothing.
	 *
	 * @param place  where the entry points are read from, {@code world0} or the root, already
	 *               settled by the chain
	 * @param inputs which of the two terrain contracts the stages are written against, which is
	 *               where the pack's {@code separateAo} lands. Handed in rather than read off the
	 *               properties here: the caller already holds the pack's own reading of that
	 *               directive, and two readings of one directive are two answers waiting to disagree.
	 *               It has to be one of the two, and that is checked rather than trusted: a constant
	 *               of another family would translate all six chunk passes against a prologue built
	 *               for another mesh, which declares other names and would be found out by nothing
	 *               short of the picture
	 */
	public static Terrain loadTerrain(Path packPath, String place,
			Map<String, OptionValue> chosen, String profile, VertexInputs inputs)
			throws IOException {
		try (OpenedPack pack = OpenedPack.open(packPath, chosen, profile)) {
			return loadTerrain(pack, place, inputs);
		}
	}

	/**
	 * The same, reading an opening the caller already holds, which is the road the engine takes:
	 * the chunk programs are read where the pack is loaded, beside the chain and the computes, and
	 * the three of them share one reading of the archive.
	 */
	public static Terrain loadTerrain(OpenedPack pack, String place, VertexInputs inputs)
			throws IOException {
		if (!inputs.terrain()) {
			throw new IllegalArgumentException("The chunk passes are drawn from Sodium's own mesh, so "
					+ inputs + " is not one of the contracts they may be written against");
		}

		ShaderPackSource source = pack.source();
		OptionIndex options = pack.options();
		ShaderProperties properties = pack.properties();
		SettingSet settings = pack.settings();
		Reading reading = reading(pack, place);
		IncludeExpander expander = reading.expander();
		TargetPlan targets = reading.targets();
		PackTextures textures = reading.textures();
		ProgramResolver resolver = reading.resolver();

		Map<String, AlphaTest> overrides = properties.alphaTests(settings.globalDefines(options));
		// The pack's own switch comes before its programs: the reference nulls its whole
		// shadow renderer on shadow.enabled=false, however many shadow programs the pack
		// ships, and only an explicit false moves anything.
		boolean shadowOff =
				properties.shadowEnabled(settings.globalDefines(options)).equals(Optional.of(false));

		// What each pass is served by, read once and kept, because the passes are walked twice:
		// the mesh is built out of what ALL of them ask for, and every one of them then declares
		// that whole mesh. Expanding the includes a second time would be the same files read
		// again for the same answer.
		record Served(String path, Map<ProgramStage, ExpandedUnit> units, AlphaTest alphaTest) {
		}

		Map<TerrainPass, Served> served = new LinkedHashMap<>();
		Set<String> reads = new LinkedHashSet<>();
		for (TerrainPass pass : TerrainPass.values()) {
			if (pass.shadow() && shadowOff) {
				continue;
			}

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

			AlphaTest alphaTest = pass.alphaTest(overrides, servedBy);
			served.put(pass, new Served(path, units, alphaTest));
			reads.addAll(reads(source, units.get(ProgramStage.VERTEX), inputs, alphaTest,
					pass.covers(), pass.program(), textures.volumes()));
		}

		// Nothing to draw and therefore nothing to carry, which is not the same answer as a mesh
		// whose pack reads none of the six: the caller leaves Sodium's own format alone either
		// way, and this is the road where it also has no program to leave it alone for.
		if (served.isEmpty()) {
			return new Terrain(Map.of(), List.of());
		}

		List<String> carried = SodiumVertex.carried(reads);
		Map<TerrainPass, Loaded> loaded = new LinkedHashMap<>();
		for (Map.Entry<TerrainPass, Served> entry : served.entrySet()) {
			TerrainPass pass = entry.getKey();
			Served one = entry.getValue();
			// The pass's own program and not the file that serves it, for the reason the alpha
			// test is taken that way: what the engine supplies belongs to what is being drawn.
			loaded.put(pass, bind(source.packName(), one.path(),
					ProgramTranslator.translate(one.units(), inputs, carried, one.alphaTest(),
							pass.covers(), pass.program(), textures.volumes()),
					targets, one.alphaTest(), textures));
		}

		return new Terrain(loaded, carried);
	}

	/**
	 * The pack's chunk programs and the mesh they were written against.
	 * <p>
	 * The two travel together because neither is worth anything without the other. A vertex stage
	 * declares exactly the elements listed here, so a caller that took these programs and bound a
	 * format built from anything else would be shifting the location of every element after the
	 * first difference, in silence.
	 *
	 * @param programs one per pass the pack serves, absent for a pass it does not
	 * @param carried  the elements the chunk mesh has to carry, Sodium's own four first and then
	 *                 whatever the union of the six programs reads. Empty when there is no program
	 *                 at all, where the mesh keeps Sodium's own format
	 */
	public record Terrain(Map<TerrainPass, Loaded> programs, List<String> carried) {

		public Terrain {
			programs = Map.copyOf(programs);
			carried = List.copyOf(carried);
		}
	}

	/**
	 * Reads and translates the programs the far terrain is drawn with, in one opening of the pack.
	 * <p>
	 * <strong>The passes are walked twice, and it is the chunk mesh's reason exactly.</strong> Every
	 * stage drawn from one mesh has to declare the same elements of it, or the location of every
	 * element after the first difference moves in silence, so the first walk asks each vertex stage
	 * what it reads and the second translates all of them against the union. What that costs is one
	 * extra rewrite per vertex stage, and it is DH's mesh here rather than Sodium's:
	 * {@link DistantVertex} says which of its six elements may be left off and why leaving one off is
	 * forced rather than thrifty.
	 * <p>
	 * All of them together and not one at a time, for the reason {@code loadGeometry} gives: the
	 * plan reads thirty odd files whatever is asked of it, and what is asked for here is drawn
	 * inside one frame - the two halves the camera sees, the opaque one and the one DH defers, and
	 * the same two again from the light where the caller asks for those.
	 *
	 * @param elements the halves to read, each carrying the name the pack is asked for and the key it
	 *                 gets its answer back under. Every one of them has to be written against
	 *                 {@link VertexInputs#DISTANT}, which is checked rather than trusted: a constant
	 *                 of another family would translate against a head built for another mesh
	 */
	public static Distant loadDistant(Path packPath, String place, List<GeometryElement> elements,
			Map<String, OptionValue> chosen, String profile) throws IOException {
		try (OpenedPack pack = OpenedPack.open(packPath, chosen, profile)) {
			return loadDistant(pack, place, elements);
		}
	}

	/** The same, reading an opening the caller already holds. */
	public static Distant loadDistant(OpenedPack pack, String place, List<GeometryElement> elements)
			throws IOException {
		for (GeometryElement element : elements) {
			if (element.inputs() != VertexInputs.DISTANT) {
				throw new IllegalArgumentException("The far terrain is drawn from Distant Horizons' own "
						+ "mesh, so " + element.inputs() + " is not a contract its programs may be "
						+ "written against");
			}
		}

		ShaderPackSource source = pack.source();
		OptionIndex options = pack.options();
		ShaderProperties properties = pack.properties();
		SettingSet settings = pack.settings();
		Reading reading = reading(pack, place);
		IncludeExpander expander = reading.expander();
		TargetPlan targets = reading.targets();
		PackTextures textures = reading.textures();
		ProgramResolver resolver = reading.resolver();

		Map<String, AlphaTest> overrides = properties.alphaTests(settings.globalDefines(options));

		record Served(String path, Map<ProgramStage, ExpandedUnit> units, AlphaTest alphaTest,
				GeometryElement element) {
		}

		Map<String, Served> served = new LinkedHashMap<>();
		Set<String> reads = new LinkedHashSet<>();
		for (GeometryElement element : elements) {
			Optional<ProgramResolver.Resolution> resolution =
					resolver.lookup(place, element.program());
			if (resolution.isEmpty()) {
				continue;
			}

			// The file that really serves the half and not the name asked for, which is how Iris
			// looks it up and how the chunk passes read the same line: a pack shipping one
			// dh_terrain and no dh_water draws its far water with the first.
			String servedBy = resolution.get().servedBy();
			String path = pathOf(place, servedBy);
			Map<ProgramStage, ExpandedUnit> units = read(source, expander, path);
			if (!units.containsKey(ProgramStage.VERTEX)
					|| !units.containsKey(ProgramStage.FRAGMENT)) {
				continue;
			}

			AlphaTest alphaTest = overrides.getOrDefault(servedBy, element.alphaTest());
			served.put(element.element(), new Served(path, units, alphaTest, element));
			reads.addAll(reads(source, units.get(ProgramStage.VERTEX), element.inputs(), alphaTest,
					element.coverage(), element.program(), textures.volumes()));
		}

		if (served.isEmpty()) {
			return new Distant(Map.of(), List.of());
		}

		List<String> carried = DistantVertex.carried(reads);
		Map<String, Loaded> loaded = new LinkedHashMap<>();
		served.forEach((key, one) -> loaded.put(key, bind(source.packName(), one.path(),
				ProgramTranslator.translate(one.units(), one.element().inputs(), carried,
						one.alphaTest(), one.element().coverage(), one.element().program(),
						textures.volumes()),
				targets, one.alphaTest(), textures)));

		return new Distant(loaded, carried);
	}

	/**
	 * The pack's far terrain programs and the elements of DH's mesh they were written against.
	 * <p>
	 * The two travel together for the reason {@link Terrain}'s pair does: a vertex stage declares
	 * exactly the elements listed here, so a caller binding a format built from anything else would
	 * shift the location of every element after the first difference without a word.
	 *
	 * @param programs one per half the pack serves, keyed by the caller's own word for it and absent
	 *                 for a half it does not serve
	 * @param carried  the elements of DH's mesh these stages declare, in DH's own order. Empty when
	 *                 there is no program at all, where DH goes on drawing its far terrain itself
	 */
	public record Distant(Map<String, Loaded> programs, List<String> carried) {

		public Distant {
			programs = Map.copyOf(programs);
			carried = List.copyOf(carried);
		}
	}

	/**
	 * One piece of the sky, as a pack has to be read for it.
	 *
	 * @param element  what the caller calls this piece, one word, and the key it gets its answer back
	 *                 under. Not a name of the format: two pieces are commonly one program drawn
	 *                 under one format, and they are still two programs here, because each carries
	 *                 its own uniform block and its own compiled module
	 * @param program  the bare name the game would draw with, {@code gbuffers_skybasic}
	 * @param bound    the elements of the vertex format the pass that draws this piece binds, in the
	 *                 format's own order. Exactly these are declared: the sky binds four different
	 *                 formats between its passes, and an element left undeclared shifts the location
	 *                 of every one after it in silence
	 * @param coverage whether the fragment stage also writes the mask saying where this piece drew
	 */
	public record SkyElement(String element, String program, List<String> bound, boolean coverage) {

		public SkyElement {
			bound = List.copyOf(bound);
		}

		/** What two pieces have to agree on to be one translation. The element is not part of it. */
		private String translation() {
			return this.program + "|" + String.join(",", this.bound) + "|" + this.coverage;
		}
	}

	/**
	 * Reads and translates the programs the game draws the pieces of its sky with, in one opening of
	 * the pack, keyed by the piece each one serves.
	 * <p>
	 * All of them together and not one at a time, for the reason {@link #loadTerrain} gives: the plan
	 * reads thirty odd files whatever is asked of it, so six separate calls would pay for six plans
	 * to translate six programs, and each of those six would land on the render thread at whatever
	 * moment of the game first drew that piece. The band at the horizon is the sharpest of them:
	 * the game skips it until its alpha passes a thousandth, so the pack was opened, expanded and
	 * translated in the frame the sun first neared the horizon.
	 * <p>
	 * What moves is the reading and the translation, and not the compiling: a module is still built
	 * from this text when the piece it belongs to is first drawn, one per piece, so two pieces that
	 * share a translation are still compiled twice.
	 * <p>
	 * Two pieces that ask for one program under one format and for the same mask share a translation,
	 * since the text would be identical: the disc and the void plane are one. They are still two
	 * programs to the caller, and the file that serves them is expanded once whatever the pieces ask
	 * for.
	 * <p>
	 * A piece the pack serves nothing for is simply absent from the answer, and the game then keeps
	 * its own shader for it. The fallback tree is walked like everywhere else, so a pack shipping
	 * only {@code gbuffers_basic} still serves the disc.
	 */
	public static Map<String, Loaded> loadSky(Path packPath, String place, List<SkyElement> elements,
			Map<String, OptionValue> chosen, String profile) throws IOException {
		try (OpenedPack pack = OpenedPack.open(packPath, chosen, profile)) {
			return loadSky(pack, place, elements);
		}
	}

	/** The same, reading an opening the caller already holds. */
	public static Map<String, Loaded> loadSky(OpenedPack pack, String place,
			List<SkyElement> elements) throws IOException {
		ShaderPackSource source = pack.source();
		OptionIndex options = pack.options();
		ShaderProperties properties = pack.properties();
		SettingSet settings = pack.settings();
		Reading reading = reading(pack, place);
		IncludeExpander expander = reading.expander();
		TargetPlan targets = reading.targets();
		PackTextures textures = reading.textures();
		ProgramResolver resolver = reading.resolver();

		Map<String, Map<ProgramStage, ExpandedUnit>> expanded = new LinkedHashMap<>();
		Map<String, Loaded> translated = new LinkedHashMap<>();
		Map<String, Loaded> loaded = new LinkedHashMap<>();
		for (SkyElement element : elements) {
			Optional<ProgramResolver.Resolution> resolution =
					resolver.lookup(place, element.program());
			if (resolution.isEmpty()) {
				continue;
			}

			String path = pathOf(place, resolution.get().servedBy());
			if (!expanded.containsKey(path)) {
				expanded.put(path, read(source, expander, path));
			}

			Map<ProgramStage, ExpandedUnit> units = expanded.get(path);
			if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT)) {
				continue;
			}

			// No alpha test anywhere in the sky: the format has no line for one, and nothing the
			// game draws there is cut out. The program the engine supplies uniforms for is the one
			// the piece wanted, not the file that ended up serving it, as everywhere else.
			translated.computeIfAbsent(element.translation(), _ -> bind(source.packName(), path,
					ProgramTranslator.translate(units, VertexInputs.SKY, element.bound(),
							AlphaTest.OFF, element.coverage(), element.program(),
							textures.volumes()),
					targets, AlphaTest.OFF, textures));
			loaded.put(element.element(), translated.get(element.translation()));
		}

		return loaded;
	}

	/**
	 * Reads and translates the program the game's clouds are drawn with, or empty where the pack
	 * serves none and the game keeps its own.
	 * <p>
	 * One program and one reading, unlike {@link #loadSky}: {@code gbuffers_clouds} is a single name
	 * of the format and the renderer draws every cloud with it. What the fancy and the flat cloud
	 * differ by is a culling, which is a property of the pipeline and not of the text, so the two
	 * share this one translation.
	 * <p>
	 * A reading of its own and not a sixth piece of {@link #loadSky}'s, which costs one plan build
	 * and buys the only thing that matters here: the sky is read at the first piece of sky the game
	 * draws, and there are places that draw clouds and no sky at all. Folded together, a pack would
	 * be opened and translated for a sky nobody was going to see, or the clouds would wait for one.
	 * <p>
	 * The fallback tree is walked like everywhere else, so a pack shipping only
	 * {@code gbuffers_textured} still serves its clouds through it.
	 */
	public static Optional<Loaded> loadClouds(Path packPath, String place,
			Map<String, OptionValue> chosen, String profile) throws IOException {
		try (OpenedPack pack = OpenedPack.open(packPath, chosen, profile)) {
			return loadClouds(pack, place);
		}
	}

	/** The same, reading an opening the caller already holds. */
	public static Optional<Loaded> loadClouds(OpenedPack pack, String place) throws IOException {
		ShaderPackSource source = pack.source();
		OptionIndex options = pack.options();
		ShaderProperties properties = pack.properties();
		SettingSet settings = pack.settings();
		Reading reading = reading(pack, place);
		IncludeExpander expander = reading.expander();
		TargetPlan targets = reading.targets();
		PackTextures textures = reading.textures();
		ProgramResolver resolver = reading.resolver();

		Optional<ProgramResolver.Resolution> resolution = resolver.lookup(place, CLOUD_PROGRAM);
		if (resolution.isEmpty()) {
			return Optional.empty();
		}

		String path = pathOf(place, resolution.get().servedBy());
		Map<ProgramStage, ExpandedUnit> units = read(source, expander, path);
		if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT)) {
			return Optional.empty();
		}

		// No alpha test, as in the sky: the format has no line for one over this name and nothing
		// the game draws here is cut out. No coverage mask either, the clouds being drawn long
		// after the scene seed has run.
		//
		// No bound elements, and that is the whole difference from the sky: this pass binds no
		// vertex format at all, so exactly nothing is declared as an input.
		return Optional.of(bind(source.packName(), path,
				ProgramTranslator.translate(units, VertexInputs.CLOUDS, List.of(), AlphaTest.OFF,
						false, CLOUD_PROGRAM, textures.volumes()),
				targets, AlphaTest.OFF, textures));
	}

	/**
	 * One piece of geometry the game hands over as a render pipeline, as a pack has to be read for it.
	 * <p>
	 * The list of bound elements is here for the reason it is on {@link SkyElement}, and only one
	 * family of this door needs it: most pieces name a {@link VertexInputs} that stands for one
	 * format, so the elements to declare are that constant's and the short constructor fills them
	 * in. The text does not. Its eight pipelines bind four formats between them,
	 * {@code GlyphVertex} listing which, so which elements a stage declares is settled per PIECE
	 * and there is nowhere else to put it.
	 * <p>
	 * <strong>The format belongs to the piece and not to the family asking</strong>, because one door
	 * serves two: the entity door draws its own rows from the entity mesh and an enchantment's glint
	 * from {@code DefaultVertexFormat.POSITION_TEX}, seven elements against two.
	 * <strong>That door checks each piece's claim against the pipeline in hand</strong>,
	 * reading the format off the binding rather than trusting the constant; the particles and the
	 * weather trust it, so a pipeline of theirs that ever bound something else would read its
	 * attributes off the wrong offsets in silence.
	 *
	 * @param element   what the caller calls this piece, one word, and the key it gets its answer back
	 *                  under. Several pieces are commonly one program under one format, and they are
	 *                  still several programs here, each carrying its own uniform block and its own
	 *                  compiled module
	 * @param program   the bare name the pack is asked for, {@code gbuffers_entities}
	 * @param alphaTest what this piece discards at when the pack says nothing, which is the threshold
	 *                  the game's own pipeline was built with. A pack overrides it with
	 *                  {@code alphaTest.<program>}, written under the file that really serves the
	 *                  piece, exactly as the chunk passes read it
	 * @param inputs    where this piece's vertex stage takes its inputs from, which is the format the
	 *                  pipeline drawing it binds
	 * @param bound     the elements of that format, in the format's own order. Exactly these are
	 *                  declared, and no others
	 * @param coverage  whether this piece's fragment stage writes the coverage mask on top of what
	 *                  the pack asked for, which every piece drawn before the scene seed and into
	 *                  the pack's own targets has to. It is part of what two pieces have to agree on
	 *                  to share a translation, and not for tidiness: the mask is one more output,
	 *                  and a piece whose pass attaches no image for it would be writing at a rank
	 *                  its pipeline carries no state for
	 */
	public record GeometryElement(String element, String program, AlphaTest alphaTest,
			VertexInputs inputs, List<String> bound, boolean coverage) {

		public GeometryElement {
			bound = List.copyOf(bound);
		}

		/** A piece whose contract stands for one format, which is every family here but the text. */
		public GeometryElement(String element, String program, AlphaTest alphaTest,
				VertexInputs inputs, boolean coverage) {
			this(element, program, alphaTest, inputs, inputs.elements(), coverage);
		}
	}

	/**
	 * Reads and translates the programs one family of the game's own geometry is drawn with, in one
	 * opening of the pack, keyed by the piece each one serves.
	 * <p>
	 * All of them together and not one at a time, for the reason {@link #loadSky} gives: the plan
	 * reads thirty odd files whatever is asked of it, and the moment one piece is first drawn is a
	 * moment of the world's choosing. The sharpest of them is the armour decal, which nothing draws
	 * until somebody wears armour that carries one; the weather is the same question asked of the
	 * sky, since a pack may be loaded for an hour before it rains.
	 * <p>
	 * Two pieces asking one program for one threshold share a translation, since the text would be
	 * identical: the alpha test is written into the fragment stage and is the only thing that differs
	 * between most of these. They are still two programs to the caller, each compiled on its own.
	 * <p>
	 * A piece the pack serves nothing for is simply absent from the answer, and the game then keeps
	 * its own shader for it. The fallback tree is walked like everywhere else, so a pack shipping only
	 * {@code gbuffers_basic} still serves every entity it has.
	 *
	 * @param elements the pieces to read, each carrying the format its own pipeline binds. Carried on
	 *                 the piece rather than handed in for the family: the caller is the door that read
	 *                 the format off the game's own pipeline, and one door now serves two formats
	 */
	public static Map<String, Loaded> loadGeometry(Path packPath, String place,
			List<GeometryElement> elements, Map<String, OptionValue> chosen, String profile)
			throws IOException {
		try (OpenedPack pack = OpenedPack.open(packPath, chosen, profile)) {
			return loadGeometry(pack, place, elements);
		}
	}

	/** The same, reading an opening the caller already holds. */
	public static Map<String, Loaded> loadGeometry(OpenedPack pack, String place,
			List<GeometryElement> elements) throws IOException {
		ShaderPackSource source = pack.source();
		OptionIndex options = pack.options();
		ShaderProperties properties = pack.properties();
		SettingSet settings = pack.settings();
		Reading reading = reading(pack, place);
		IncludeExpander expander = reading.expander();
		TargetPlan targets = reading.targets();
		PackTextures textures = reading.textures();
		ProgramResolver resolver = reading.resolver();

		Map<String, AlphaTest> overrides = properties.alphaTests(settings.globalDefines(options));

		Map<String, Map<ProgramStage, ExpandedUnit>> expanded = new LinkedHashMap<>();
		Map<String, Loaded> translated = new LinkedHashMap<>();
		Map<String, Loaded> loaded = new LinkedHashMap<>();
		for (GeometryElement element : elements) {
			Optional<ProgramResolver.Resolution> resolution =
					resolver.lookup(place, element.program());
			if (resolution.isEmpty()) {
				continue;
			}

			// The name the override is written under is the file that really serves the piece and
			// not the one that was asked for, which is how Iris looks it up and how the chunk
			// passes read the same line.
			String servedBy = resolution.get().servedBy();
			String path = pathOf(place, servedBy);
			if (!expanded.containsKey(path)) {
				expanded.put(path, read(source, expander, path));
			}

			Map<ProgramStage, ExpandedUnit> units = expanded.get(path);
			if (!units.containsKey(ProgramStage.VERTEX) || !units.containsKey(ProgramStage.FRAGMENT)) {
				continue;
			}

			AlphaTest alphaTest = overrides.getOrDefault(servedBy, element.alphaTest());
			// What two pieces have to agree on to be one translation, and the element is not part
			// of it. The threshold is, because it is written into the fragment stage: two pieces of
			// one program discarding at different alphas are two texts, and sharing one would draw
			// a mob with the silhouette of whichever piece was translated first.
			//
			// The FILE and not the name asked for, which is the whole point of sharing: the two
			// names of this family walk to one file wherever the fallback tree lands them on the
			// same program, and keying by name would expand, translate and compile that file
			// twice there, which is the one thing reading them all at once exists to avoid.
			//
			// A program NAME reaches the translation through two answers, and both are in the key
			// while the name itself is not: LegacyGlsl.drawsEntities decides whether the entity
			// uniforms are declared, and LegacyGlsl.bindsGameTransforms whether a read of
			// gl_TextureMatrix[0] goes to the game's own block.
			//
			// A third answer, LegacyGlsl.readsDrawModelView, is NOT here and does not need to be:
			// it differs from the second on the two shadow roots alone, and the shadow chain of
			// ProgramFallbacks shares no name with the gbuffers one, so no file is ever asked for
			// both ways and a key on the second already tells those two files apart.
			//
			// THE GLINT IS WHAT MAKES THEM TWO ANSWERS RATHER THAN ONE: its mesh carries no entity
			// and its draw is still one the game prepared, so it is the first name asked for here
			// that answers the two differently. The shape that would cost something is a pack
			// shipping no gbuffers_armor_glint: it and gbuffers_entities then walk to one
			// gbuffers_textured, one file serving both, and a key without this answer would hand
			// whichever was translated second the other one's uniforms without a word. Both are
			// written out all the same rather than left to the two lines below to imply, because
			// what saves that case today is that the glint is also the only element carrying its
			// format and its threshold, and neither of those is a fact about the uniforms.
			//
			// The format is in the key for the same reason and a harder one: it decides which
			// names the vertex head declares as inputs, and two stages built from one text against
			// two formats are two different modules.
			//
			// The mask is in the key as well, and it is the one answer here that is not a fact
			// about the text: two pieces of one file are two texts as soon as one of them writes
			// the mask, because the mask is an output the other one's pipeline carries no state
			// for, and one module cannot be right for both.
			//
			// The bound elements are in it BESIDE the format, and the text is why: its eight
			// pipelines share one VertexInputs and bind four formats, so a key on the contract
			// alone would hand a name plate's background the module built for a glyph, which
			// declares a texture coordinate the background's buffer has not got.
			VertexInputs inputs = element.inputs();
			String key = path + "|" + alphaTest + "|" + LegacyGlsl.drawsEntities(element.program())
					+ "|" + LegacyGlsl.bindsGameTransforms(element.program()) + "|" + inputs
					+ "|" + String.join(",", element.bound()) + "|" + element.coverage();
			translated.computeIfAbsent(key, _ -> bind(source.packName(), path,
					ProgramTranslator.translate(units, inputs, element.bound(), alphaTest,
							element.coverage(), element.program(), textures.volumes()),
					targets, alphaTest, textures));
			loaded.put(element.element(), translated.get(key));
		}

		return loaded;
	}

	/**
	 * The same chain for a caller with no {@code options.txt} to read, which is the harness and the
	 * corpus measurements. See {@link ChainPlan.Families#DEFAULT}.
	 */
	public static Optional<Chain> loadChain(Path packPath, String dimension,
			Map<String, OptionValue> chosen, String profile, ChainFilter filter) throws IOException {
		return loadChain(packPath, dimension, chosen, profile, filter, ChainPlan.Families.DEFAULT);
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
	 * @param families  which of the families the plan's verdicts may count are really drawn, which
	 *                  is what keeps those lines from claiming a target is filled by a family
	 *                  somebody switched off
	 * @return empty when the pack serves no final with both stages in that place
	 */
	public static Optional<Chain> loadChain(Path packPath, String dimension,
			Map<String, OptionValue> chosen, String profile, ChainFilter filter,
			ChainPlan.Families families) throws IOException {
		try (OpenedPack pack = OpenedPack.open(packPath, chosen, profile)) {
			return loadChain(pack, dimension, filter, families);
		}
	}

	/**
	 * The same, reading an opening the caller already holds, which is the road the engine takes:
	 * the chain, the chunk programs and the shadow computes are all read at the load and share one
	 * reading of the archive between them.
	 */
	public static Optional<Chain> loadChain(OpenedPack pack, String dimension, ChainFilter filter,
			ChainPlan.Families families) throws IOException {
		ShaderPackSource source = pack.source();
		OptionIndex options = pack.options();
		ShaderProperties properties = pack.properties();
		SettingSet settings = pack.settings();
		IncludeExpander expander = new IncludeExpander(source, settings);

		TargetPlan targets = TargetPlan.build(source, options, settings, properties, dimension, filter);
		String place = targets.place();

		// Asked before anything is expanded. A place that serves no final draws nothing at all,
		// and finding that out after nine programs have been read is nine wasted seconds.
		if (!targets.running().contains(FINAL) || !serves(source, pathOf(place, FINAL))) {
			return Optional.empty();
		}

		PackTextures textures = textures(source, properties, options, settings);

		// Inside the same opening as everything else, for the reason the other readings give: a
		// zip closed behind us invalidates every path taken from it.
		SourceMentions mentions = SourceMentions.of(source, SETTLED_EARLY);
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
		ChainPlan chain = ChainPlan.of(targets, ProgramResolver.resolve(programs, dimensions,
				properties.switchedOff(settings.globalDefines(options), options)),
				refusals, families);

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

		return Optional.of(
				new Chain(source.packName(), place, targets, chain, loaded, refused, mentions));
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
						.map(TranslatedUnit.StorageBlock::name)
						.filter(block -> !storage.contains(block))
						.filter(block -> !CustomStorage.named(block))
						.forEach(storage::add);
			});

			// A volume an image directive fills is served, so a sampler3D that names one is bound
			// and never refused; only a shape nothing stands behind is.
			List<TranslatedUnit.Uniform> plain = found.values().stream()
					.filter(sampler -> !filled.contains(sampler.name())
							&& !CustomImages.named(sampler.name()))
					.toList();

			Refusal refusal = new Refusal(plain, storage);
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
		CustomImages.install(properties.imageDirectives(settings.globalDefines(options)));
		CustomStorage.install(properties.bufferObjects(settings.globalDefines(options)));
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
