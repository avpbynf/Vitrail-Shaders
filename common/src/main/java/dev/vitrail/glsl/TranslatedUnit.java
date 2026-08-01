package dev.vitrail.glsl;

import dev.vitrail.pack.ProgramStage;

import java.util.List;

/**
 * One unit of pack source turned into Vulkan GLSL, with what the translation had to decide along
 * the way.
 * <p>
 * The notes are not decoration. A translation that produces text a compiler accepts can still be
 * wrong, and the only warning of that is a count that moves: a uniform declared twice under two
 * types, an output written through an index nothing can resolve, an extension nobody expected.
 * Those are counted here so a run over the corpus can rank them instead of discovering them one
 * broken image at a time.
 *
 * @param drawBuffers  the colour attachments this program writes, in the order its outputs are
 *                     numbered, taken from the {@code DRAWBUFFERS} or {@code RENDERTARGETS}
 *                     comment. Empty when the program declares neither.
 */
public record TranslatedUnit(String entry, ProgramStage stage, String text, Notes notes,
		List<Integer> drawBuffers) {

	/**
	 * @param fragmentOutputs   how many {@code ofFragData} outputs were declared. An upper bound:
	 *                          writes from branches nobody takes are counted too, on purpose
	 * @param dynamicFragData   writes through {@code gl_FragData[i]} with an index that is not a
	 *                          literal, which no declaration can be generated for
	 * @param blockUniforms     plain uniforms lifted into the block
	 * @param opaqueUniforms    samplers and images left as uniforms of their own
	 * @param uniformConflicts  names declared more than once under different types; the first
	 *                          declaration wins and the rest are lost
	 * @param shadowCalls       legacy shadow lookups wrapped back to a {@code vec4}
	 * @param strippedExtensions {@code #extension} lines dropped as core in 4.60
	 * @param conflictNames     the names behind {@code uniformConflicts}, so a run over a corpus
	 *                          can name them rather than only count them
	 */
	public record Notes(int fragmentOutputs, int dynamicFragData, int blockUniforms,
			int opaqueUniforms, int uniformConflicts, int shadowCalls, int strippedExtensions,
			List<String> conflictNames) {
	}
}
