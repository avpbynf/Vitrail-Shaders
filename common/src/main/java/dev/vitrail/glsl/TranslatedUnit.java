package dev.vitrail.glsl;

import dev.vitrail.pack.program.ProgramStage;

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
	 * @param depthEpilogue      one when the vertex body was wrapped so that the clip depth it
	 *                           writes is converted once, after everything the pack did to it
	 * @param alphaEpilogue      one when the fragment body was wrapped so that the alpha test of its
	 *                           pass discards after the pack has written its colour. Zero where the
	 *                           pass asks for no test, and zero where it asked and the stage could
	 *                           not be given one, which is the case worth reading: the picture is
	 *                           then drawn without the discard it needed
	 * @param coverage           one when the fragment stage was given the coverage mask its pass
	 *                           asked for, an output above every one the pack declared and written
	 *                           after the discard. Zero where the pass asked for none, and zero
	 *                           where it asked and there was no rank left to give it, which is the
	 *                           case worth reading: whoever reads the mask is then reading an image
	 *                           this stage never wrote
	 * @param depthLookups       lookups made through a name that says it reads a depth texture. Not
	 *                           rewritten, and counted only so that the next one can be read against
	 *                           something
	 * @param parameterLookups   lookups made through a sampler the enclosing function was handed,
	 *                           which is the same call whether what it reads is a depth or a colour.
	 *                           No rule on the name of a sampler can classify one of these, so this
	 *                           is the size of the blind spot such a rule would have; the engine
	 *                           binds an image already in the pack's window rather than rewrite the
	 *                           ones it can see
	 * @param fragCoordZ         reads of {@code gl_FragCoord.z} converted
	 * @param fragCoordXyz       reads of {@code gl_FragCoord.xyz}, where only the third component
	 *                           is a depth and the rewrite has to rebuild the vector
	 * @param fragCoordUnhandled reads of {@code gl_FragCoord} that reach the third component some
	 *                           other way. Zero on the corpus: a pack that moves it has to show up
	 *                           here rather than be guessed at
	 * @param fragDepthWrites    writes to {@code gl_FragDepth} converted back to the convention the
	 *                           target is rasterised in
	 * @param fragDepthUnhandled anything else done to {@code gl_FragDepth}, a compound assignment
	 *                           above all, which cannot be rewritten where it stands because it
	 *                           reads back a value the stage never wrote
	 * @param conflictNames      the names behind {@code uniformConflicts}, so a run over a corpus
	 *                           can name them rather than only count them
	 * @param comparedSamplers   the samplers the pack asked the hardware to compare and that are
	 *                           declared here as ordinary ones. Read off the declarations rather
	 *                           than off {@code samplers}, whose types have already been rewritten
	 * @param storageBlocks      the storage blocks this unit declares at file scope, which nothing
	 *                           binds and no rewrite can help: they are what refuses the program
	 *                           that carries one
	 */
	public record Notes(int fragmentOutputs, int dynamicFragData, int uniformConflicts,
			int shadowCalls, int unwrappedShadow, int strippedExtensions,
			int depthEpilogue, int alphaEpilogue, int coverage,
			int depthLookups, int parameterLookups,
			int fragCoordZ, int fragCoordXyz, int fragCoordUnhandled,
			int fragDepthWrites, int fragDepthUnhandled,
			List<String> conflictNames, List<String> comparedSamplers, List<String> storageBlocks) {
	}
}
