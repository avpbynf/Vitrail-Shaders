package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.ExpansionStats;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;

import java.io.IOException;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds the two maps a translated program is walked through to one order on every launch.
 * <p>
 * {@code Map.copyOf} draws its order afresh each time the process starts, and both maps are walked:
 * the storage blocks of the stages are handed to the bind group layout in the order the stages are
 * met, the first stage to declare a refused sampler is the one reported, and the codec writes the
 * program to disk in that order. The stages come back in the enum's order whether the program was
 * translated or read back from a blob, and the synthesised names in the order they were declared.
 */
class TranslatedProgramOrderTest {

	/**
	 * Every input a quad does not carry that the engine answers with a constant, by the type it is
	 * declared under, in an order that is neither theirs in {@link VertexPrologue#SYNTHESIZED} nor
	 * the alphabet's.
	 */
	private static final Map<String, String> DECLARED = declared("mc_chunkFade float", "vaUV2 ivec2",
			"at_tangent vec4", "mc_midTexCoord vec2", "vaColor vec4", "mc_Entity vec4",
			"dhMaterialId int", "vaNormal vec3", "at_midBlock vec4", "vaUV1 ivec2");

	@Test
	void translatesTheStagesIntoTheEnumsOrder() {
		Map<ProgramStage, ExpandedUnit> units = new LinkedHashMap<>();
		units.put(ProgramStage.FRAGMENT, unit("order.fsh", """
				#version 150
				in vec4 tint;
				out vec4 colour;
				void main() {
					colour = tint;
				}
				"""));
		units.put(ProgramStage.GEOMETRY, unit("order.gsh", """
				#version 150
				layout(triangles) in;
				layout(triangle_strip, max_vertices = 3) out;
				in vec4 vertexTint[];
				out vec4 tint;
				void main() {
					for (int i = 0; i < 3; i++) {
						tint = vertexTint[i];
						gl_Position = gl_in[i].gl_Position;
						EmitVertex();
					}
					EndPrimitive();
				}
				"""));
		StringBuilder vertex = new StringBuilder("#version 150\n");
		DECLARED.forEach((name, type) -> vertex.append("in ").append(type).append(' ').append(name)
				.append(";\n"));
		vertex.append("""
				out vec4 vertexTint;
				void main() {
					vertexTint = mc_Entity;
					gl_Position = vec4(0.0);
				}
				""");
		units.put(ProgramStage.VERTEX, unit("order.vsh", vertex.toString()));

		// Over a quad, which carries none of them, so every one is synthesised.
		ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(units, true);

		// Not the order they run in, which puts the geometry stage second: the order the codec
		// hands a program back in, which has only the enum to go by.
		assertEquals(List.of(ProgramStage.VERTEX, ProgramStage.FRAGMENT, ProgramStage.GEOMETRY),
				List.copyOf(program.stages().keySet()));
		assertEquals(List.copyOf(DECLARED.keySet()), List.copyOf(program.synthesized().keySet()));
	}

	@Test
	void readsTheStagesBackInTheEnumsOrder() throws IOException {
		Map<ProgramStage, TranslatedUnit> stages = new LinkedHashMap<>();
		for (ProgramStage stage : List.of(ProgramStage.TESSELLATION_EVALUATION, ProgramStage.COMPUTE,
				ProgramStage.FRAGMENT, ProgramStage.TESSELLATION_CONTROL, ProgramStage.GEOMETRY,
				ProgramStage.VERTEX)) {
			stages.put(stage, emptyUnit(stage));
		}

		byte[] written = TranslatedProgramCodec.write(new ProgramTranslator.TranslatedProgram(stages,
				List.of(), List.of(), Set.of(), DECLARED, VertexInputs.WORLD));

		ProgramTranslator.TranslatedProgram read = TranslatedProgramCodec.read(written, written.length,
				VertexInputs.WORLD);

		assertEquals(List.of(ProgramStage.values()), List.copyOf(read.stages().keySet()));
		assertEquals(List.copyOf(DECLARED.keySet()), List.copyOf(read.synthesized().keySet()));
	}

	private static Map<String, String> declared(String... declarations) {
		Map<String, String> declared = new LinkedHashMap<>();
		for (String declaration : declarations) {
			int space = declaration.indexOf(' ');
			declared.put(declaration.substring(0, space), declaration.substring(space + 1));
		}

		return declared;
	}

	private static ExpandedUnit unit(String entry, String source) {
		List<String> lines = source.lines().toList();
		BitSet live = new BitSet();
		live.set(0, lines.size());

		return new ExpandedUnit(entry, lines, "150", ExpansionStats.NONE, live, Map.of());
	}

	private static TranslatedUnit emptyUnit(ProgramStage stage) {
		TranslatedUnit.Notes notes = new TranslatedUnit.Notes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
				0, 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0, 0);

		return new TranslatedUnit("order." + stage.extension(), stage, "", notes, List.of(), List.of(),
				List.of());
	}
}
