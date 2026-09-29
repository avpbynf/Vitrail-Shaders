package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.ExpansionStats;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Holds the profile a full screen vertex stage is translated under to the version line the
 * expander took, and not to one it wrote out on a branch it did not take.
 * <p>
 * The profile decides what {@code vaPosition} reads over a quad: on Iris's core path it is renamed
 * to the quad's own corner, and off it the stage gets a constant. The expander keeps the one live
 * version and writes a dead one out as it stands, so a dead line below the live one is still in the
 * text the translator reads.
 */
class LiveVersionProfileTest {

	/** What the core path makes of the declaration, which the other path never writes. */
	private static final String CORNER = "#define vaPosition Position";

	@Test
	void readsALegacyVersionAboveADeadCoreOneAsLegacy() {
		String text = translate("120", """
				#version 120
				#ifdef NEVER_DEFINED
				#version 330 core
				#endif
				attribute vec3 vaPosition;
				void main() {
					gl_Position = vec4(vaPosition, 1.0);
				}
				""");

		assertFalse(text.lines().anyMatch(CORNER::equals), text);
	}

	@Test
	void readsACoreVersionAboveADeadLegacyOneAsCore() {
		String text = translate("330 core", """
				#version 330 core
				#ifdef NEVER_DEFINED
				#version 120
				#endif
				in vec3 vaPosition;
				void main() {
					gl_Position = vec4(vaPosition, 1.0);
				}
				""");

		assertTrue(text.lines().anyMatch(CORNER::equals), text);
	}

	/** The third line is the dead one in both sources, as the expander would have marked it. */
	private static String translate(String version, String source) {
		List<String> lines = source.lines().toList();
		BitSet live = new BitSet();
		live.set(0, lines.size());
		live.clear(2);
		ExpandedUnit unit = new ExpandedUnit("quad.vsh", lines, version, ExpansionStats.NONE, live,
				Map.of());
		GlslTranslator.Stage stage = GlslTranslator.prepare(unit, ProgramStage.VERTEX,
				VertexInputs.FULLSCREEN, AlphaTest.OFF, "");

		return stage.render(stage.uniforms(), stage.samplers(), stage.varyings()).text();
	}
}
