package dev.vitrail.glsl;

import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.ExpansionStats;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the translator over a text held in the test, with the preprocessor's answer settled by
 * hand: every line live unless the test names it dead.
 * <p>
 * The support classes ({@link VaryingSplit}, {@link VolumeFlattening} and the rest) have no way in
 * but the translator, so this is how a test reaches them without a pack or a game.
 */
final class TranslateSupport {

	private TranslateSupport() {
	}

	/** A unit of this text, its lines live except the ones counted from zero that are named. */
	static ExpandedUnit unit(String entry, String text, int... dead) {
		List<String> lines = List.of(text.split("\n", -1));
		BitSet live = new BitSet();
		live.set(0, lines.size());
		for (int line : dead) {
			live.clear(line);
		}

		return new ExpandedUnit(entry, lines, "120", ExpansionStats.NONE, live, Map.of());
	}

	static TranslatedUnit translate(ProgramStage stage, String text, int... dead) {
		return GlslTranslator.translate(unit("test." + stage.name().toLowerCase(java.util.Locale.ROOT), text, dead),
				stage);
	}

	/** The whole program, over a quad, with the volumes the pack ships. */
	static ProgramTranslator.TranslatedProgram program(Map<ProgramStage, String> stages,
			Map<String, VolumeAtlas> volumes) {
		Map<ProgramStage, ExpandedUnit> units = new LinkedHashMap<>();
		stages.forEach((stage, text) -> units.put(stage, unit("test." + stage.name().toLowerCase(java.util.Locale.ROOT), text)));

		return ProgramTranslator.translate(units, VertexInputs.FULLSCREEN, List.of("Position", "UV0"),
				AlphaTest.OFF, false, "composite", volumes);
	}

	/** How often a part occurs in a text, counted without overlap. */
	static int count(String text, String part) {
		int count = 0;
		for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
			count++;
		}

		return count;
	}
}
