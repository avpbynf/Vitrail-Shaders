package dev.vitrail.glsl;

import dev.vitrail.pack.ProgramStage;

import java.util.List;

/**
 * One unit of pack source turned into Vulkan GLSL, with what the translation had to decide along
 * the way and what the engine now owes it.
 * <p>
 * The notes are not decoration. A translation that produces text a compiler accepts can still be
 * wrong, and the only warning of that is a count that moves: an output written through an index
 * nothing can resolve, a name declared twice under two types, an extension nobody expected. Those
 * are counted here so a run over the corpus can rank them instead of discovering them one broken
 * image at a time.
 *
 * @param drawBuffers  the colour attachments this program writes, in the order its outputs are
 *                     numbered, taken from the {@code DRAWBUFFERS} or {@code RENDERTARGETS}
 *                     comment. Empty when the program declares neither.
 * @param blockMembers what the uniform block holds, in the order it is written. The order is the
 *                     layout: a std140 buffer is filled by walking it, so it is part of the
 *                     result rather than something to work out again later.
 * @param samplers     the opaque uniforms left declared on their own, one bind each.
 */
public record TranslatedUnit(String entry, ProgramStage stage, String text, Notes notes,
		List<Integer> drawBuffers, List<Uniform> blockMembers, List<Uniform> samplers) {

	/**
	 * One value the engine has to supply, named exactly as the translated text declares it. The
	 * name has to match character for character in three places, the GLSL, the bind group layout
	 * and the call that binds it, so it is carried rather than rebuilt from the text.
	 *
	 * @param declaration the whole thing as written, which is the only place an array size shows
	 */
	public record Uniform(String name, String type, String declaration) {

		public static Uniform of(String name, String declaration) {
			int space = declaration.indexOf(' ');

			return new Uniform(name, space < 0 ? declaration : declaration.substring(0, space),
					declaration);
		}
	}

	/**
	 * @param fragmentOutputs    how many fragment outputs the header declares, counting both the
	 *                           {@code ofFragData} slots and the outputs the pack names itself and
	 *                           the translator lifts. An upper bound: writes from branches nobody
	 *                           takes are counted too, on purpose
	 * @param dynamicFragData    writes through {@code gl_FragData[i]} with an index that is not a
	 *                           literal, which no declaration can be generated for
	 * @param uniformConflicts   names declared more than once under different types; the first
	 *                           declaration wins and the rest are lost
	 * @param shadowCalls        legacy shadow lookups wrapped back to a {@code vec4}
	 * @param unwrappedShadow    shadow lookups whose closing parenthesis could not be found, left
	 *                           as they were. Zero on the corpus, and it should stay zero
	 * @param strippedExtensions {@code #extension} lines dropped as core in 4.60
	 * @param conflictNames      the names behind {@code uniformConflicts}, so a run over a corpus
	 *                           can name them rather than only count them
	 */
	public record Notes(int fragmentOutputs, int dynamicFragData, int uniformConflicts,
			int shadowCalls, int unwrappedShadow, int strippedExtensions,
			List<String> conflictNames) {
	}
}
