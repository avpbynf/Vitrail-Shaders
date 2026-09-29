package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.ExpansionStats;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Holds a macro's parameter and its use to one spelling when the macro sits below an interface
 * block.
 * <p>
 * {@code #define CALL(texture, uv) texture(uv)} keeps both {@code texture}s as they are: the one in
 * the list is a parameter and the one in the body is a call, and neither is the reserved name the
 * rename is for. Flattening a block puts tokens in ahead of every line under it, so a parameter
 * remembered by its position before the flattening is looked for somewhere else afterwards, and the
 * rename then takes the list and leaves the body: the preprocessor binds nothing at the use, and the
 * stage is refused on an undeclared {@code texture}.
 */
class MacroParameterMarkTest {

	private static final String MACRO = "#define CALL(texture, uv) texture(uv)";

	@Test
	void keepsTheParameterAndItsUseAlikeUnderAFlattenedBlock() {
		String text = translate("""
				#version 330 compatibility
				out VertexData {
					vec2 coord;
					vec4 tint;
				} v;
				%s
				void main() {
					v.coord = vec2(0.0);
					v.tint = vec4(1.0);
					gl_Position = vec4(0.0);
				}
				""".formatted(MACRO));

		assertEquals(List.of(MACRO), defineLines(text), text);
	}

	private static String translate(String source) {
		List<String> lines = source.lines().toList();
		BitSet live = new BitSet();
		live.set(0, lines.size());
		ExpandedUnit unit = new ExpandedUnit("macro.vsh", lines, "330 compatibility", ExpansionStats.NONE,
				live, Map.of());
		GlslTranslator.Stage stage = GlslTranslator.prepare(unit, ProgramStage.VERTEX, VertexInputs.WORLD,
				AlphaTest.OFF, "");

		return stage.render(stage.uniforms(), stage.samplers(), stage.varyings()).text();
	}

	private static List<String> defineLines(String text) {
		return text.lines().filter(line -> line.startsWith("#define CALL")).toList();
	}
}
