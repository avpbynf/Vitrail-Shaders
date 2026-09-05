package dev.vitrail.glsl;

import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.texture.CustomStorage;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates the stages of one program together, so that they agree on what they share.
 * <p>
 * A stage translated on its own is not wrong, it is just incomplete. Three of the things the
 * translation produces are properties of the program and not of the file:
 * <ul>
 * <li>The uniform block. Both stages call it {@code OfGlobals} and the engine binds one buffer
 * under that name, so if the vertex stage declares six members and the fragment stage six
 * different ones, at most one of them reads what it thinks it reads. Body Camera's {@code final}
 * is exactly that case, six members each and almost nothing in common.</li>
 * <li>The varyings the engine names. A varying the vertex stage writes and the fragment stage
 * never declares is accepted without a word and shifts the location of everything after it,
 * which is the failure that leaves no trace at all. The other way round is not silent at all and
 * is dealt with here too: an input the fragment declares that nothing writes costs the whole
 * module, so it is taken back out where the body never reads it.</li>
 * <li>The names the vertex format takes for itself. Only the vertex stage declares an input, but a
 * pack using one of those names for a varying of its own writes it in one stage and reads it in
 * the next, so moving it out of the way is a decision about the whole program. See
 * {@link #clashingElements}.</li>
 * </ul>
 * The first two are settled by giving every stage the union, in one order. A stage then declares
 * things it does not use, which costs nothing: an unread uniform is still a member of the block,
 * and an unread varying still occupies its location.
 * <p>
 * Vertex attributes themselves are deliberately not shared. Only a vertex stage has inputs from a
 * buffer, so there is no other side for it to agree with.
 */
public final class ProgramTranslator {

	/**
	 * Stages in the order they run. The union of the uniforms is built by walking this, so the
	 * order of the block follows the pipeline rather than whatever order the caller passed.
	 */
	private static final List<ProgramStage> PIPELINE_ORDER = List.of(
			ProgramStage.VERTEX,
			ProgramStage.TESSELLATION_CONTROL,
			ProgramStage.TESSELLATION_EVALUATION,
			ProgramStage.GEOMETRY,
			ProgramStage.FRAGMENT,
			ProgramStage.COMPUTE);

	private ProgramTranslator() {
	}

	/**
	 * Every stage of one program, translated, with what the pipeline will have to declare.
	 *
	 * @param uniforms    the block every stage declares, in the order a std140 buffer is filled in
	 * @param samplers    every opaque uniform any stage binds, which is what the pipeline declares.
	 *                    Names a stage actually samples come first, so their bindings stay inside
	 *                    Metal's sixteen sampler slots; unused declarations follow rather than
	 *                    pushing a used name onto slot 17
	 * @param synthesized vertex inputs the mesh has not got, answered with a constant, by name and
	 *                    with the type the pack declared them under. Empty for every pass drawn over
	 *                    a quad, which is every pass this engine drew before milestone six
	 * @param inputs      where the vertex stage was translated to take its inputs from. Carried out
	 *                    of the translation rather than tabulated again beside it, so that whoever
	 *                    binds the samplers reads the very value that wrote the vertex head: the
	 *                    light map is answered in the head AND behind a sampler, and a piece given
	 *                    one half without the other is served an image neither authority asked for
	 */
	public record TranslatedProgram(Map<ProgramStage, TranslatedUnit> stages,
			List<TranslatedUnit.Uniform> uniforms, List<TranslatedUnit.Uniform> samplers,
			Map<String, String> synthesized, VertexInputs inputs) {
	}

	/**
	 * Translates every stage of one program, drawn over the world.
	 *
	 * @param program the bare name of the program the pass wants, {@code gbuffers_entities}, or
	 *                empty where the caller is measuring and no pass is named
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units, String program) {
		return translate(units, VertexInputs.WORLD, program);
	}

	/**
	 * The same, told whether the program is drawn over a quad rather than over the world.
	 *
	 * @param fullscreen whether this program is drawn over a quad, which changes where the vertex
	 *                   stage takes its inputs from
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units, boolean fullscreen) {
		return translate(units, fullscreen ? VertexInputs.FULLSCREEN : VertexInputs.WORLD, "");
	}

	/**
	 * The same, with the vertex stage's inputs named outright rather than read off a flag.
	 *
	 * @param inputs where this program's vertex stage takes its inputs from
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, String program) {
		return translate(units, inputs, AlphaTest.OFF, program);
	}

	/**
	 * The same, under the alpha test the pass carries rather than the program.
	 *
	 * @param alphaTest what the fragment stage discards at, which belongs to the pass the program is
	 *                  drawn in rather than to the program: one {@code gbuffers_terrain} serves both
	 *                  the solid half of the chunk pass, with no test, and the cutout half, at a half
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, AlphaTest alphaTest, String program) {
		return translate(units, inputs, alphaTest, false, program);
	}

	/**
	 * The same, told whether the pass also writes the mask carrying the depth it leaves.
	 *
	 * @param coverage whether the fragment stage also writes the mask carrying the depth this pass
	 *                 leaves. A property of the pass, like the alpha test: every pass drawn before
	 *                 the scene seed and into the pack's own targets writes it, which is the two
	 *                 opaque halves of the chunk pass, the sky's disc and the entities
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, AlphaTest alphaTest, boolean coverage, String program) {
		return translate(units, inputs, inputs.elements(), alphaTest, coverage, program, Map.of());
	}

	/**
	 * Which elements of its mesh one program's vertex stage really reads, without a line of it being
	 * written.
	 * <p>
	 * The chunk mesh is the one family whose format follows the pack, so its caller has to know what
	 * every one of the pack's chunk programs asks for before it can settle what any of them
	 * declares. This is the half of the translation that answers that: the stage is rewritten, its
	 * names are read off it, and the header it would have been given is never built.
	 * <p>
	 * <strong>The elements handed in here are not the ones the head will declare</strong>, and they
	 * cannot be: the format is what this call is being asked for. Nothing between here and
	 * {@code render} reads them, which is what makes the two halves separable at all.
	 */
	public static Set<String> reads(ExpandedUnit vertex, VertexInputs inputs, AlphaTest alphaTest,
			boolean coverage, String program, Map<String, VolumeAtlas> volumes) {
		// Clocked like the whole translations: this half-translation is real translator work a
		// chunk program pays before its full one, and leaving it out would undercount terrain.
		// The caller keeps the answer per opening, so the passes a file serves clock it once.
		long began = System.nanoTime();
		try {
			return GlslTranslator.prepare(vertex, ProgramStage.VERTEX, inputs, inputs.elements(),
					alphaTest, coverage, program, volumes).reads();
		} finally {
			LoadClock.translation(System.nanoTime() - began);
		}
	}

	/**
	 * The same, for a family that binds more than one vertex format.
	 * <p>
	 * Two families are: {@code SkyRenderer} binds four formats between its eight passes and the
	 * game's eight text pipelines bind four between them, so which elements a stage declares is the
	 * pass's or the piece's answer and not the family's.
	 *
	 * @param boundElements the elements of the format this pass binds, in the format's own order
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, List<String> boundElements, AlphaTest alphaTest, String program) {
		return translate(units, inputs, boundElements, alphaTest, false, program, Map.of());
	}

	/**
	 * The same again, told what the pack ships behind the names it samples as volumes.
	 * <p>
	 * Handed to every stage and not only to the one that reads: the declaration is what a backend
	 * refuses, Reverie writes its own in a header its vertex stages include as well, and a program
	 * whose two stages disagree about the type of one name is built against a type one of the two
	 * modules has not got.
	 *
	 * @param volumes by the name the pack samples them under, each with the layout of its atlas
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, List<String> boundElements, AlphaTest alphaTest, boolean coverage,
			String program, Map<String, VolumeAtlas> volumes) {
		// Every overload above funnels through here, which is what makes this the one place a
		// program's whole translation can be clocked, and the one place it can be kept.
		//
		// The clock encloses the cache rather than sitting beside it, so a served program is
		// counted like a translated one: the figure is what getting a program cost, whichever way
		// it came, and a load whose translation post has collapsed says so instead of going quiet.
		long began = System.nanoTime();
		try {
			String key = TranslationCache.keyOf(units, inputs, boundElements, alphaTest, coverage,
					program, volumes);
			TranslatedProgram served = TranslationCache.lookup(key, inputs);
			if (served != null) {
				return declared(served);
			}

			TranslatedProgram built =
					translated(units, inputs, boundElements, alphaTest, coverage, program, volumes);
			TranslationCache.store(key, built);

			return declared(built);
		} finally {
			LoadClock.translation(System.nanoTime() - began);
		}
	}

	/**
	 * Files this program's storage blocks with the bindings they were written at, and hands it back.
	 * <p>
	 * Here rather than where the text is read, and that is the whole point: a program served out of
	 * {@link TranslationCache} never goes near the translator, so a table filled while the tokens
	 * were walked would hold the blocks of the programs this run happened to translate and not the
	 * ones the pack declares. What reads it is
	 * {@link dev.vitrail.pack.texture.CustomStorage#named}, which decides whether a program keeps
	 * its place in the chain and, for one that does, whether the bind group layout declares its slot
	 * a storage buffer rather than a uniform one. A draw's descriptor write does not ask it at all,
	 * taking its buffer from {@code StorageBuffers.bound}, and only a compute dispatch asks the name
	 * again to type its own write. Answered off a warm cache the question refused Complementary's
	 * world-space reflection composite and left the targets it writes on their clear.
	 */
	private static TranslatedProgram declared(TranslatedProgram program) {
		program.stages().values().forEach(stage -> stage.notes().storageBlocks()
				.forEach(block -> CustomStorage.declare(block.name(), block.binding())));

		return program;
	}

	private static TranslatedProgram translated(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, List<String> boundElements, AlphaTest alphaTest, boolean coverage,
			String program, Map<String, VolumeAtlas> volumes) {
		Map<ProgramStage, GlslTranslator.Stage> prepared = new LinkedHashMap<>();
		for (ProgramStage stage : PIPELINE_ORDER) {
			ExpandedUnit unit = units.get(stage);
			if (unit != null) {
				prepared.put(stage, GlslTranslator.prepare(unit, stage, inputs, boundElements,
						alphaTest, coverage, program, volumes));
			}
		}

		// Before anything is read off a stage, because it changes what the stages read. A stage is
		// only ever asked to give up an input against what the stages BEFORE it write, which is the
		// order rebind pairs them in, so this walks the pipeline and not the map it filled.
		if (prepared.containsKey(ProgramStage.VERTEX)) {
			Set<String> provided = new LinkedHashSet<>();
			for (ProgramStage stage : PIPELINE_ORDER) {
				GlslTranslator.Stage prepare = prepared.get(stage);
				if (prepare == null) {
					continue;
				}

				if (stage != ProgramStage.VERTEX) {
					prepare.dropUnprovidedInputs(provided);
				}

				provided.addAll(prepare.provides());
			}

			// What the drop would not take, handed back the other way. Taking it out was the first
			// answer because it is the one that changes nothing; what is left is read by the body, so
			// the only answers remaining are to have the stage before hand it over or to lose the
			// pass. Mellow's deferred1 is drawn over a quad and includes the header its geometry
			// passes use, which declares eleven varyings; three of them are read by functions this
			// pass never calls, and the game refused the whole module over exactly those three.
			owed(prepared);

			// And last, the other way round, because it reads what the two above have just settled:
			// the first takes inputs out and the second adds outputs, and both change the answer.
			withheld(prepared);
		}

		Map<String, TranslatedUnit.Uniform> uniforms = new LinkedHashMap<>();
		Map<String, TranslatedUnit.Uniform> samplers = new LinkedHashMap<>();
		Map<String, String> synthesized = new LinkedHashMap<>();
		Set<String> varyings = new LinkedHashSet<>();

		// A pass of its own and ahead of everything else, because one of these answers changes
		// another: the overlay colour is a varying the vertex stage owes as soon as ANY stage reads
		// it, and owing it is what makes that stage declare the texture it is fetched from. Asking
		// for the samplers in the same walk would collect the vertex stage's before the fragment
		// stage had said it wanted the colour.
		for (GlslTranslator.Stage stage : prepared.values()) {
			varyings.addAll(stage.varyings());
		}

		for (GlslTranslator.Stage stage : prepared.values()) {
			stage.makesOverlayColour(varyings);
		}

		for (GlslTranslator.Stage stage : prepared.values()) {
			synthesized.putAll(stage.synthesized());
			// First declaration of a name wins, as it does within one stage. A name declared
			// under two types in two stages is the pack's problem, and taking the first keeps the
			// answer the same whichever stage is looked at.
			stage.uniforms().forEach(uniform -> uniforms.putIfAbsent(uniform.name(), uniform));
			stage.samplers().forEach(sampler -> samplers.putIfAbsent(sampler.name(), sampler));
		}

		List<TranslatedUnit.Uniform> block = fixedFunctionFirst(uniforms);
		List<TranslatedUnit.Uniform> bound = sampledFirst(samplers, prepared);
		Set<String> elements = clashingElements(prepared, inputs);

		Map<ProgramStage, TranslatedUnit> translated = new LinkedHashMap<>();
		prepared.forEach((stage, prepare) -> {
			Set<String> shadowed = new LinkedHashSet<>(elements);
			shadowed.addAll(shadowedBy(prepare, block));
			translated.put(stage, prepare.render(block, bound, varyings, shadowed));
		});

		return new TranslatedProgram(Map.copyOf(translated), block, bound, Map.copyOf(synthesized),
				inputs);
	}

	/**
	 * Makes each stage declare the varyings the stage after it kept, and assign each one its zero.
	 * <p>
	 * <strong>This is Iris's own patch, and it is taken whole rather than adapted.</strong>
	 * {@code CompatibilityTransformer.java:442-504} does exactly this: same condition, the input
	 * having to be referenced somewhere (:469); same interpolation qualifier carried over (:485-488);
	 * same pairing with the stage before; and the declaration is followed by an initialiser
	 * prepended to that stage's main (:494). <strong>The initialiser is not decoration.</strong>
	 * Under Iris these varyings hold ZERO, deterministically, and dropping it would leave them
	 * holding whatever the stage happens to leave in them. Packs are written against Iris, so the
	 * value they see here has to be the value they see there.
	 * <p>
	 * <strong>Why this cannot shift a location.</strong> {@code GlslCompiler.compile:69-86} hands the
	 * fragment stage the vertex stage's output list in order; {@code createFromSpirv:114-116} had
	 * numbered that list {@code 0..n-1} with nothing skipped; {@code rebind:151-163} then numbers the
	 * fragment stage over the same list, counting only the names the fragment declares. The two agree
	 * on every name with no undeclared one before it in the list. What is added here is a name the
	 * fragment already declares, so it is counted on both sides and opens no such gap. A name the
	 * fragment does NOT declare is what would open one, and nothing here adds one of those.
	 * <p>
	 * <strong>What this does not reach.</strong> A declaration whose names are only PARTLY provided,
	 * {@code in vec3 a, b;} with a stage before writing {@code a} alone, is neither dropped nor owed:
	 * it is offered only when nothing before writes any of its names. The module is then refused as
	 * it was. No pack of the corpus writes one, and the shape is worth naming rather than reading as
	 * covered.
	 * <p>
	 * <strong>The stage before is the vertex stage, and in this game it can be nothing else.</strong>
	 * {@code GlslCompiler.compile:62-63} takes one vertex module and one fragment module and pairs
	 * those two at :86; 26.2 compiles no geometry stage at all. The walk is written over the pipeline
	 * order anyway rather than reaching for the vertex stage by name, so that a stage appearing
	 * between them is paired correctly rather than silently skipped.
	 *
	 * @param prepared the stages of one program, keyed by stage, in pipeline order
	 */
	private static void owed(Map<ProgramStage, GlslTranslator.Stage> prepared) {
		GlslTranslator.Stage previous = null;
		for (GlslTranslator.Stage stage : prepared.values()) {
			if (previous != null) {
				Map<String, String> declarations = stage.unprovided();
				if (!declarations.isEmpty()) {
					previous.owe(declarations);
				}
			}

			previous = stage;
		}
	}

	/**
	 * Stops each stage handing on the varyings the stage after it does not declare.
	 * <p>
	 * <strong>The same pairing as {@link #owed}, read from the other end, and the end that says
	 * nothing.</strong> A varying the next stage declares and this one never wrote is a refusal,
	 * loud, and the whole module goes with it. A varying this stage writes and the next one never
	 * declares raises nothing: {@code rebind:151-163} counts only the names the next stage declares
	 * while {@code createFromSpirv:114-116} numbered the whole list, so the two agree on nothing
	 * after the first name that is missing. What the picture then shows is a fragment stage reading
	 * its neighbour's varying, which looks like a shader that is merely wrong.
	 * <p>
	 * <strong>Last of the three, and the order is what makes it right.</strong> The drop takes
	 * inputs out of the next stage, {@link #owed} puts outputs into this one, and both change the
	 * two sets this compares. Running it first would withhold a name {@code owed} is about to make
	 * the pair agree on.
	 * <p>
	 * <strong>A compute stage is stepped over, and it is not tidiness.</strong> It stands LAST in
	 * the pipeline order, after the fragment stage, so pairing it with the stage before it would
	 * make the fragment stage the one handing something on. What a fragment stage declares as
	 * {@code out} is a colour attachment and not a varying, and one written without an explicit
	 * {@code layout(location = n)} is still in the body here, {@code liftFragmentOutputs} only
	 * taking out the ones that carry one. Demoting it would leave the pass compiling, drawing, and
	 * writing to nothing. It costs nothing to step over: a compute stage is dispatched on its own,
	 * and {@code GlslCompiler.compile:62-63} pairs one vertex module with one fragment module and
	 * takes no third.
	 *
	 * @param prepared the stages of one program, keyed by stage, in pipeline order
	 */
	private static void withheld(Map<ProgramStage, GlslTranslator.Stage> prepared) {
		GlslTranslator.Stage previous = null;
		for (Map.Entry<ProgramStage, GlslTranslator.Stage> entry : prepared.entrySet()) {
			if (entry.getKey() == ProgramStage.COMPUTE) {
				continue;
			}

			if (previous != null) {
				previous.withhold(entry.getValue().requires());
			}

			previous = entry.getValue();
		}
	}

	/**
	 * The vertex input names this program uses for something of its own, and which therefore have
	 * to be moved out of the way in every one of its stages.
	 * <p>
	 * The names of a vertex input are not ours to choose: {@code rebind} looks each element of the
	 * format up in the SPIR-V under the name the format gives it, so the head has to declare
	 * {@code Normal} and cannot declare {@code ofNormal}. Two packs of the corpus already use
	 * {@code Normal} or {@code Color} for a varying of their own, on sixteen entity stages between
	 * them, which is a redefinition at file scope and refuses the stage outright.
	 * <p>
	 * <strong>The whole program moves or none of it does.</strong> Body Camera writes
	 * {@code out vec3 Normal} in its vertex stage and reads it back in its fragment stage; renaming
	 * one half would leave the other declaring a varying nobody writes, which is the failure that
	 * says nothing at all and shifts every location after it.
	 */
	private static Set<String> clashingElements(Map<ProgramStage, GlslTranslator.Stage> prepared,
			VertexInputs inputs) {
		Set<String> clashing = new LinkedHashSet<>();

		for (String element : inputs.elements()) {
			if (prepared.values().stream().anyMatch(stage -> stage.declared().contains(element))) {
				clashing.add(element);
			}
		}

		return clashing;
	}

	/**
	 * Which of the shared block's names this stage already uses for something of its own.
	 * <p>
	 * The block carries what every stage of the program needs, so a stage is handed members it
	 * never asked for, and one of those names may already be taken. Bliss works out {@code sunVec}
	 * in its vertex shader and reads it as a uniform in its fragment shader: give both the same
	 * block and the vertex declares it twice. A stage that never lifted the name as a uniform has
	 * only one meaning for it, which is what makes moving its own out of the way safe.
	 */
	private static Set<String> shadowedBy(GlslTranslator.Stage stage,
			List<TranslatedUnit.Uniform> block) {
		Set<String> shadowed = new LinkedHashSet<>();

		for (TranslatedUnit.Uniform member : block) {
			if (stage.declared().contains(member.name()) && !stage.lifted().contains(member.name())) {
				shadowed.add(member.name());
			}
		}

		return shadowed;
	}

	/**
	 * Fixed function state at the top, in the table's own order, then everything the packs
	 * declared in the order the stages mentioned it. Walking the stages alone would interleave
	 * the two, which still compiles but reads as though nobody chose.
	 */
	private static List<TranslatedUnit.Uniform> fixedFunctionFirst(
			Map<String, TranslatedUnit.Uniform> uniforms) {
		List<TranslatedUnit.Uniform> block = new ArrayList<>();

		for (String name : LegacyGlsl.FIXED_FUNCTION_MEMBERS.keySet()) {
			TranslatedUnit.Uniform member = uniforms.get(name);
			if (member != null) {
				block.add(member);
			}
		}

		for (Map.Entry<String, TranslatedUnit.Uniform> member : uniforms.entrySet()) {
			if (!LegacyGlsl.FIXED_FUNCTION_MEMBERS.containsKey(member.getKey())) {
				block.add(member.getValue());
			}
		}

		return List.copyOf(block);
	}

	/**
	 * Names a stage samples first, unused declarations after, so both the bind group and the
	 * shader text meet the used names first. The compiler assigns bindings in the order it first
	 * meets a name, and MoltenVK turns that into a Metal sampler that only accepts 0 through 15.
	 */
	private static List<TranslatedUnit.Uniform> sampledFirst(
			Map<String, TranslatedUnit.Uniform> samplers,
			Map<ProgramStage, GlslTranslator.Stage> prepared) {
		Set<String> sampled = new LinkedHashSet<>();
		for (GlslTranslator.Stage stage : prepared.values()) {
			sampled.addAll(stage.sampled());
		}

		List<TranslatedUnit.Uniform> bound = new ArrayList<>();
		for (TranslatedUnit.Uniform sampler : samplers.values()) {
			if (sampled.contains(sampler.name())) {
				bound.add(sampler);
			}
		}

		for (TranslatedUnit.Uniform sampler : samplers.values()) {
			if (!sampled.contains(sampler.name())) {
				bound.add(sampler);
			}
		}

		return List.copyOf(bound);
	}
}
