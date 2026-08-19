package dev.vitrail.pack.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which of a handful of names appear anywhere in a pack's own text, read once when the pack is.
 *
 * <p><strong>This is a NECESSARY condition and never a sufficient one, and that asymmetry is the
 * whole of why it is allowed to exist.</strong> A name no source of the pack writes cannot be
 * declared by any program of it, whichever family that program belongs to and whenever that family
 * happens to be read; a name that does appear may still be in a comment, behind a branch nothing
 * takes, or in a file no program includes. So a false answer here can only ever be "the pack might
 * read this", never "the pack does not", and a guard built on it can only ever do work that turns
 * out to have been unnecessary.
 *
 * <p>That is what makes it a different thing from {@link dev.vitrail.pack.target.SamplerPlan},
 * which answers what a LINKED program really declares and is the authority wherever the answer
 * decides what is bound. This one answers a question the plan cannot: the plan speaks for one
 * program, and only once that program has been read, while six of the seven geometry families are
 * read at the first draw of their own kind. Anything the engine has to settle before the first
 * frame, for the pack as a whole, has no other source of truth this cheap.
 *
 * <p><strong>Token pasting turns every answer conservative.</strong> A pack that builds a sampler
 * name out of pieces, {@code shadowtex##N}, would never spell it here, and that is the one way this
 * reading could say "no" about a name that is really read. Rather than trust that no pack does it,
 * a {@code ##} anywhere in the sources makes {@link #maybe} answer yes to everything, which costs
 * exactly what not having a guard costs. No pack of the August 2026 corpus writes one at all.
 *
 * <p>Reading a name proves it absent only if everything was read, so <strong>every file under the
 * shaders root is</strong>, and not the ones an extension marks as a source: an include is settled
 * by the path it names, so a declaration can arrive from a file that list never had. Nothing of the
 * corpus does it today, every include of the eight packs landing on an extension the list holds; it
 * is the claim above that has to hold for a pack nobody here has seen.
 */
public final class SourceMentions {

	/**
	 * The preprocessor's pasting operator. Looked for as text, since finding it at all is enough:
	 * this is not parsing the pack, it is deciding whether parsing it as text can be trusted.
	 */
	private static final String PASTING = "##";

	private final Set<String> found;

	private final boolean pasting;

	private SourceMentions(Set<String> found, boolean pasting) {
		this.found = Set.copyOf(found);
		this.pasting = pasting;
	}

	/**
	 * Walks the pack's files once and notes which of {@code names} they write.
	 *
	 * <p>The tree and not the include graph: walking what a program includes would answer for the
	 * families that have been read, and the whole point is to answer for the ones that have not.
	 *
	 * <p><strong>The walk is in two parts, and they are not asked the same thing.</strong> The
	 * sources are read first, and they alone settle {@link #PASTING}: what that flag decides is
	 * whether this pack's GLSL can be trusted to spell its own names, which is a question about
	 * GLSL. A name, on the other hand, has to be proved absent from the pack ENTIRELY, and an
	 * include reaches whatever path it names, so anything the source list leaves out is read too
	 * rather than assumed empty. Reading a texture for a {@code ##} instead would turn the flag on
	 * for almost every pack, the two bytes falling out of compressed noise on their own, and a flag
	 * on is a guard off.
	 *
	 * <p>The second part is skipped whole once the sources have written every name, which is the
	 * ordinary case and is why a pack that reads what it is asked about never opens a texture. What
	 * is left of the cost falls on the packs a guard is about to fire for.
	 *
	 * @param names the names to look for, each matched as plain text anywhere in a file
	 */
	public static SourceMentions of(ShaderPackSource source, Set<String> names) throws IOException {
		Set<String> found = new LinkedHashSet<>();
		boolean pasting = false;

		for (Path file : source.sourceFiles()) {
			for (String line : source.readLines(file)) {
				if (line.contains(PASTING)) {
					pasting = true;
				}

				for (String name : names) {
					if (line.contains(name)) {
						found.add(name);
					}
				}
			}

			// Only once everything is known: the pasting flag makes every name answer yes, so
			// leaving early on a full set would miss a paste in a file further down and hand back an
			// answer narrower than the pack deserves.
			if (found.size() == names.size() && pasting) {
				break;
			}
		}

		if (found.size() == names.size()) {
			return new SourceMentions(found, pasting);
		}

		for (Path file : source.otherFiles()) {
			// Empty for anything that is not text, a pack's own textures included, and decided on
			// the head of the file rather than on its name.
			String text = source.searchableText(file);
			for (String name : names) {
				if (text.contains(name)) {
					found.add(name);
				}
			}

			if (found.size() == names.size()) {
				break;
			}
		}

		return new SourceMentions(found, pasting);
	}

	/** An empty reading, which answers yes to everything: what a pack nothing was read for gets. */
	public static SourceMentions unread() {
		return new SourceMentions(Set.of(), true);
	}

	/**
	 * Whether the pack might read this name somewhere. False is a proof and true is a possibility,
	 * for the reason the class comment gives.
	 */
	public boolean maybe(String name) {
		return this.pasting || this.found.contains(name);
	}
}
