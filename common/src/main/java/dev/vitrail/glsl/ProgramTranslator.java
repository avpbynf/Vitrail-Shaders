package dev.vitrail.glsl;

import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;

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
 * which is the failure that leaves no trace at all.</li>
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
	 * @param uniforms    the block every stage declares, in the order a std140 buffer is filled in
	 * @param samplers    every opaque uniform any stage binds, which is what the pipeline declares
	 * @param synthesized vertex inputs the mesh has not got, answered with a constant, by name and
	 *                    with the type the pack declared them under. Empty for every pass drawn over
	 *                    a quad, which is every pass this engine drew before milestone six
	 */
	public record TranslatedProgram(Map<ProgramStage, TranslatedUnit> stages,
			List<TranslatedUnit.Uniform> uniforms, List<TranslatedUnit.Uniform> samplers,
			Map<String, String> synthesized) {
	}

	/**
	 * @param program the bare name of the program the pass wants, {@code gbuffers_entities}, or
	 *                empty where the caller is measuring and no pass is named
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units, String program) {
		return translate(units, VertexInputs.WORLD, program);
	}

	/**
	 * @param fullscreen whether this program is drawn over a quad, which changes where the vertex
	 *                   stage takes its inputs from
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units, boolean fullscreen) {
		return translate(units, fullscreen ? VertexInputs.FULLSCREEN : VertexInputs.WORLD, "");
	}

	/**
	 * @param inputs where this program's vertex stage takes its inputs from
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, String program) {
		return translate(units, inputs, AlphaTest.OFF, program);
	}

	/**
	 * @param alphaTest what the fragment stage discards at, which belongs to the pass the program is
	 *                  drawn in rather than to the program: one {@code gbuffers_terrain} serves both
	 *                  the solid half of the chunk pass, with no test, and the cutout half, at a half
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, AlphaTest alphaTest, String program) {
		return translate(units, inputs, alphaTest, false, program);
	}

	/**
	 * @param coverage whether the fragment stage also writes the mask saying where this pass drew.
	 *                 A property of the pass, like the alpha test: the two opaque halves of the chunk
	 *                 pass write it and no other pass of the engine does
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, AlphaTest alphaTest, boolean coverage, String program) {
		return translate(units, inputs, inputs.elements(), alphaTest, coverage, program);
	}

	/**
	 * The same, for a family that binds more than one vertex format.
	 * <p>
	 * Only {@link VertexInputs#SKY} is one today: {@code SkyRenderer} binds four formats between its
	 * eight passes, so which elements a stage declares is the pass's answer and not the family's.
	 *
	 * @param boundElements the elements of the format this pass binds, in the format's own order
	 */
	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, List<String> boundElements, AlphaTest alphaTest, String program) {
		return translate(units, inputs, boundElements, alphaTest, false, program);
	}

	private static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, List<String> boundElements, AlphaTest alphaTest, boolean coverage,
			String program) {
		Map<ProgramStage, GlslTranslator.Stage> prepared = new LinkedHashMap<>();
		for (ProgramStage stage : PIPELINE_ORDER) {
			ExpandedUnit unit = units.get(stage);
			if (unit != null) {
				prepared.put(stage, GlslTranslator.prepare(unit, stage, inputs, boundElements,
						alphaTest, coverage, program));
			}
		}

		Map<String, TranslatedUnit.Uniform> uniforms = new LinkedHashMap<>();
		Map<String, TranslatedUnit.Uniform> samplers = new LinkedHashMap<>();
		Map<String, String> synthesized = new LinkedHashMap<>();
		Set<String> varyings = new LinkedHashSet<>();

		for (GlslTranslator.Stage stage : prepared.values()) {
			synthesized.putAll(stage.synthesized());
			// First declaration of a name wins, as it does within one stage. A name declared
			// under two types in two stages is the pack's problem, and taking the first keeps the
			// answer the same whichever stage is looked at.
			stage.uniforms().forEach(uniform -> uniforms.putIfAbsent(uniform.name(), uniform));
			stage.samplers().forEach(sampler -> samplers.putIfAbsent(sampler.name(), sampler));
			varyings.addAll(stage.varyings());
		}

		List<TranslatedUnit.Uniform> block = fixedFunctionFirst(uniforms);
		List<TranslatedUnit.Uniform> bound = List.copyOf(samplers.values());
		Set<String> elements = clashingElements(prepared, inputs);

		Map<ProgramStage, TranslatedUnit> translated = new LinkedHashMap<>();
		prepared.forEach((stage, prepare) -> {
			Set<String> shadowed = new LinkedHashSet<>(elements);
			shadowed.addAll(shadowedBy(prepare, block));
			translated.put(stage, prepare.render(block, bound, varyings, shadowed));
		});

		return new TranslatedProgram(Map.copyOf(translated), block, bound, Map.copyOf(synthesized));
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
}
