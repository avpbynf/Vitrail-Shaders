package dev.vitrail.glsl;

import dev.vitrail.pack.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.ProgramStage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates the stages of one program together, so that they agree on what they share.
 * <p>
 * A stage translated on its own is not wrong, it is just incomplete. Two of the things the
 * translation produces are properties of the program and not of the file:
 * <ul>
 * <li>The uniform block. Both stages call it {@code OfGlobals} and the engine binds one buffer
 * under that name, so if the vertex stage declares six members and the fragment stage six
 * different ones, at most one of them reads what it thinks it reads. Body Camera's {@code final}
 * is exactly that case, six members each and almost nothing in common.</li>
 * <li>The varyings the engine names. A varying the vertex stage writes and the fragment stage
 * never declares is accepted without a word and shifts the location of everything after it,
 * which is the failure that leaves no trace at all.</li>
 * </ul>
 * Both are settled here by giving every stage the union, in one order. A stage then declares
 * things it does not use, which costs nothing: an unread uniform is still a member of the block,
 * and an unread varying still occupies its location.
 * <p>
 * Vertex attributes are deliberately not shared. Only a vertex stage has inputs from a buffer, so
 * there is no other side for it to agree with.
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
	 * @param uniforms the block every stage declares, in the order a std140 buffer is filled in
	 * @param samplers every opaque uniform any stage binds, which is what the pipeline declares
	 */
	public record TranslatedProgram(Map<ProgramStage, TranslatedUnit> stages,
			List<TranslatedUnit.Uniform> uniforms, List<TranslatedUnit.Uniform> samplers) {
	}

	public static TranslatedProgram translate(Map<ProgramStage, ExpandedUnit> units) {
		Map<ProgramStage, GlslTranslator.Stage> prepared = new LinkedHashMap<>();
		for (ProgramStage stage : PIPELINE_ORDER) {
			ExpandedUnit unit = units.get(stage);
			if (unit != null) {
				prepared.put(stage, GlslTranslator.prepare(unit, stage));
			}
		}

		Map<String, TranslatedUnit.Uniform> uniforms = new LinkedHashMap<>();
		Map<String, TranslatedUnit.Uniform> samplers = new LinkedHashMap<>();
		Set<String> varyings = new LinkedHashSet<>();

		for (GlslTranslator.Stage stage : prepared.values()) {
			// First declaration of a name wins, as it does within one stage. A name declared
			// under two types in two stages is the pack's problem, and taking the first keeps the
			// answer the same whichever stage is looked at.
			stage.uniforms().forEach(uniform -> uniforms.putIfAbsent(uniform.name(), uniform));
			stage.samplers().forEach(sampler -> samplers.putIfAbsent(sampler.name(), sampler));
			varyings.addAll(stage.varyings());
		}

		List<TranslatedUnit.Uniform> block = fixedFunctionFirst(uniforms);
		List<TranslatedUnit.Uniform> bound = List.copyOf(samplers.values());

		Map<ProgramStage, TranslatedUnit> translated = new LinkedHashMap<>();
		prepared.forEach((stage, prepare) ->
				translated.put(stage, prepare.render(block, bound, varyings, shadowedBy(prepare, block))));

		return new TranslatedProgram(Map.copyOf(translated), block, bound);
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
