package dev.vitrail.pack.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
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
	 * Walks every source file of the pack once and notes which of {@code names} it writes.
	 *
	 * <p>Every file under the shaders root, not the ones a program includes: an include is a file of
	 * the pack like any other, and walking the tree rather than the include graph is what makes the
	 * answer hold for families nothing has read yet.
	 *
	 * @param names the names to look for, each matched as plain text anywhere in a line
	 */
	public static SourceMentions of(ShaderPackSource source, Set<String> names) throws IOException {
		Set<String> found = new LinkedHashSet<>();
		boolean pasting = false;

		for (Path file : source.sourceFiles()) {
			List<String> lines = source.readLines(file);
			for (String line : lines) {
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
