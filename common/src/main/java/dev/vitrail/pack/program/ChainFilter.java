package dev.vitrail.pack.program;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the user asked to run, and what this engine cannot run, over and above what the pack keeps.
 * <p>
 * It exists to bisect: which pass breaks the picture, which pass costs, and what the chain is
 * worth against no chain at all. Removing a pass changes the parity of everything after it, so
 * the schedule is always rebuilt on what this filter leaves, never trimmed afterwards. That is
 * also why a pass the backend refuses is taken out here rather than anywhere downstream: it is
 * the one place where a removal is paid for by the walk that everything else reads.
 *
 * @param without programs the engine itself will not run, whatever the user asked. It is kept
 *                apart from {@code only} so that a log can say which of the two took a pass out;
 *                the two reasons are nothing alike and only one of them is a choice
 */
public record ChainFilter(List<String> only, List<String> without, int limit) {

	/** Everything the pack keeps on. */
	public static final ChainFilter ALL = new ChainFilter(List.of(), -1);

	/** A program is named by itself here, never by its place, because that is how a user types it. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z_]\\w*");

	public ChainFilter {
		only = List.copyOf(only);
		without = List.copyOf(without);
	}

	public ChainFilter(List<String> only, int limit) {
		this(only, List.of(), limit);
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
	 * The same filter with more programs the engine will not run.
	 * <p>
	 * The rest is carried over untouched, {@code limit} included, so that the ranks a second walk
	 * hands out are the ranks the first one did. A refusal that shifted them would make
	 * {@code passes=6} mean six different programs, and the picture would change for a reason
	 * nobody asked for.
	 */
	public ChainFilter without(Collection<String> programs) {
		if (programs.isEmpty()) {
			return this;
		}

		List<String> refused = new ArrayList<>(this.without);
		programs.stream().filter(name -> !refused.contains(name)).forEach(refused::add);

		return new ChainFilter(this.only, refused, this.limit);
	}

	/** Whether this engine, rather than the user or the pack, is what takes the program out. */
	public boolean refuses(String bareName) {
		return this.without.contains(bareName);
	}

	/**
	 * @param bareName the program without its place, for instance {@code composite4}
	 * @param rank     its position among the full screen programs the pack itself keeps, from 0.
	 *                 The final is never offered here and is never filtered.
	 */
	public boolean accepts(String bareName, int rank) {
		if (refuses(bareName)) {
			return false;
		}

		if (!this.only.isEmpty()) {
			return this.only.contains(bareName);
		}

		return this.limit < 0 || rank < this.limit;
	}
}
