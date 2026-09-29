package dev.vitrail.glsl;

import dev.vitrail.pack.model.ProgramStage;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translated programs built by hand for the tests of the codec and the cache, which have to keep
 * and give back a program without ever translating one.
 * <p>
 * Every number in the notes is different, so that two fields swapped on the way through a codec
 * change the result rather than cancel out.
 */
final class SampleTranslations {

	private SampleTranslations() {
	}

	/** Nineteen to twenty-three follow the eighteen counts, then the names, then the blocks. */
	static TranslatedUnit.Notes notes() {
		return new TranslatedUnit.Notes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
				List.of("n1"), List.of(), List.of("h"), List.of(),
				List.of(new TranslatedUnit.StorageBlock("B", 3)), 19, 20, 21, 22, 23);
	}

	static TranslatedUnit unit(ProgramStage stage, String text) {
		return new TranslatedUnit("main", stage, text, notes(), List.of(0, 1),
				List.of(TranslatedUnit.Uniform.of("u", "float u")), List.of());
	}

	/** One fragment stage, one sampler, one name sampled and one input synthesised. */
	static ProgramTranslator.TranslatedProgram program(VertexInputs inputs, String fragmentText) {
		Map<ProgramStage, TranslatedUnit> stages = new LinkedHashMap<>();
		stages.put(ProgramStage.FRAGMENT, unit(ProgramStage.FRAGMENT, fragmentText));

		return new ProgramTranslator.TranslatedProgram(stages, List.of(),
				List.of(TranslatedUnit.Uniform.of("s", "sampler2D s")), Set.of("s"), Map.of("k", "v"), inputs);
	}

	/** Two stages, with the awkward strings a translation may hold in each field it has. */
	static ProgramTranslator.TranslatedProgram busy() {
		Map<ProgramStage, TranslatedUnit> stages = new LinkedHashMap<>();
		stages.put(ProgramStage.VERTEX, new TranslatedUnit("", ProgramStage.VERTEX, "",
				new TranslatedUnit.Notes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
						List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0, 0),
				List.of(), List.of(), List.of()));
		stages.put(ProgramStage.FRAGMENT, new TranslatedUnit("entr\u00e9e.fsh", ProgramStage.FRAGMENT,
				"// caf\u00e9 \u4e2d\u6587 \ud83d\ude00\r\nvoid main() {}\n\0",
				notes(), List.of(0, 3, Integer.MAX_VALUE, Integer.MIN_VALUE, -1),
				List.of(TranslatedUnit.Uniform.of("a", "vec4 a[4]"), TranslatedUnit.Uniform.of("b", "int b")),
				List.of(TranslatedUnit.Uniform.of("colortex0", "sampler2D colortex0"))));

		Set<String> sampled = new LinkedHashSet<>(List.of("colortex0", "", "shadowtex1"));
		Map<String, String> synthesized = new LinkedHashMap<>();
		synthesized.put("mc_Entity", "vec4");
		synthesized.put("", "");

		return new ProgramTranslator.TranslatedProgram(stages,
				List.of(TranslatedUnit.Uniform.of("a", "vec4 a[4]"), TranslatedUnit.Uniform.of("b", "int b")),
				List.of(TranslatedUnit.Uniform.of("colortex0", "sampler2D colortex0"),
						TranslatedUnit.Uniform.of("noSpace", "")),
				sampled, synthesized, VertexInputs.TERRAIN);
	}
}
