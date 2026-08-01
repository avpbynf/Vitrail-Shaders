package dev.vitrail.pack;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What counts as the name of a program, and what does not.
 * <p>
 * The list of {@code gbuffers_} names is closed on purpose. The measuring prototype accepted
 * anything after the prefix, which is fine when the goal is to count files, but an engine has
 * to know what it is being handed: a name outside this list has no place to be drawn from and
 * silently ignoring it would leave the pack looking broken with nothing in the log. Anything
 * rejected here is meant to be reported, not dropped.
 */
public final class ProgramNames {

	/**
	 * The named programs are exactly those that take part in the fallback tree, so the list
	 * lives in one place. Keeping a second copy here is how the two drift apart and how a
	 * program ends up recognised but unplaceable, or the reverse.
	 */
	private static Set<String> named() {
		return ProgramFallbacks.names();
	}

	/** Families that carry an optional number, as in {@code composite3}. */
	private static final Set<String> NUMBERED = Set.of(
			"composite", "deferred", "prepare", "shadowcomp");

	/** Families that stand alone. */
	private static final Set<String> SIMPLE = Set.of(
			"shadow", "final", "setup", "begin");

	/** Highest slot the format allows on a numbered family. */
	private static final int MAX_SLOT = 99;

	/**
	 * A trailing letter marks a compute pass attached to the program before it. The underscore
	 * is optional because both spellings are in use across the corpus, and a pack that writes
	 * {@code composite21_a.csh} would otherwise lose every compute pass it has.
	 */
	private static final Pattern COMPUTE_SUFFIX = Pattern.compile("^(.*?)_?([a-z])$");

	private ProgramNames() {
	}

	public static Optional<ProgramName> parse(String baseName) {
		if (named().contains(baseName) || SIMPLE.contains(baseName)) {
			return Optional.of(new ProgramName(baseName, -1, null));
		}

		Optional<ProgramName> numbered = parseNumbered(baseName);
		if (numbered.isPresent()) {
			return numbered;
		}

		// Only after the plain forms have failed: a compute pass is written by hanging a letter
		// off a name that has to be valid on its own.
		Matcher compute = COMPUTE_SUFFIX.matcher(baseName);
		if (compute.matches()) {
			String stem = compute.group(1);
			if (SIMPLE.contains(stem)) {
				return Optional.of(new ProgramName(stem, -1, compute.group(2)));
			}

			Optional<ProgramName> stemNumbered = parseNumbered(stem);
			if (stemNumbered.isPresent()) {
				ProgramName base = stemNumbered.get();
				return Optional.of(new ProgramName(base.family(), base.slot(), compute.group(2)));
			}
		}

		return Optional.empty();
	}

	private static Optional<ProgramName> parseNumbered(String baseName) {
		for (String family : NUMBERED) {
			if (!baseName.startsWith(family)) {
				continue;
			}

			String tail = baseName.substring(family.length());
			if (tail.isEmpty()) {
				return Optional.of(new ProgramName(family, 0, null));
			}

			if (tail.length() > 2 || !tail.chars().allMatch(Character::isDigit)) {
				continue;
			}

			int slot = Integer.parseInt(tail);
			if (slot <= MAX_SLOT) {
				return Optional.of(new ProgramName(family, slot, null));
			}
		}

		return Optional.empty();
	}

	/**
	 * A program's identity: its family, the slot for the numbered families, and the letter that
	 * marks a compute pass hanging off it.
	 */
	public record ProgramName(String family, int slot, String computeSuffix) {

		public boolean isCompute() {
			return this.computeSuffix != null;
		}

		public String baseName() {
			return this.slot < 0 ? this.family : this.family + (this.slot == 0 ? "" : Integer.toString(this.slot));
		}
	}
}
