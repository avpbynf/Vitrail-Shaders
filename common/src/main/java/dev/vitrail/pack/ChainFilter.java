package dev.vitrail.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the user asked to run, over and above what the pack itself keeps on.
 * <p>
 * It exists to bisect: which pass breaks the picture, which pass costs, and what the chain is
 * worth against no chain at all. Removing a pass changes the parity of everything after it, so
 * the schedule is always rebuilt on what this filter leaves, never trimmed afterwards.
 */
public record ChainFilter(List<String> only, int limit) {

	/** Everything the pack keeps on. */
	public static final ChainFilter ALL = new ChainFilter(List.of(), -1);

	/** A program is named by itself here, never by its place, because that is how a user types it. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z_]\\w*");

	public ChainFilter {
		only = List.copyOf(only);
	}

	/**
	 * "0", "6", or "composite4,composite5". Anything else is ALL, and the caller says so.
	 * <p>
	 * A name that no program of the place carries is not an error and is not corrected: it simply
	 * keeps nothing, which is the whole of what a bisection tool owes anybody.
	 */
	public static ChainFilter parse(String text) {
		if (text == null) {
			return ALL;
		}

		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return ALL;
		}

		if (trimmed.chars().allMatch(Character::isDigit)) {
			try {
				return new ChainFilter(List.of(), Integer.parseInt(trimmed));
			} catch (NumberFormatException e) {
				return ALL;
			}
		}

		List<String> names = new ArrayList<>();
		for (String token : trimmed.split(",")) {
			String name = token.trim();
			if (!NAME.matcher(name).matches()) {
				return ALL;
			}

			names.add(name);
		}

		return names.isEmpty() ? ALL : new ChainFilter(names, -1);
	}

	/**
	 * @param bareName the program without its place, for instance {@code composite4}
	 * @param rank     its position among the full screen programs the pack itself keeps, from 0.
	 *                 The final is never offered here and is never filtered.
	 */
	public boolean accepts(String bareName, int rank) {
		if (!this.only.isEmpty()) {
			return this.only.contains(bareName);
		}

		return this.limit < 0 || rank < this.limit;
	}
}
