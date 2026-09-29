package dev.vitrail.render;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The reasons a family has already given for handing a draw back, one line each per load, and what
 * keeps a lasting reason from costing anything on the draws after its first.
 * <p>
 * A line is still said once per KEY, the string a family composes out of the reason and whatever
 * the reason is about, and {@link #add} is that set exactly as it was: what the log says and how
 * often is untouched by anything here. What changed is the price of asking. The key and the
 * sentence beside it were composed on every draw the reason handed back, and a lasting reason hands
 * back every draw: a group the load dropped is every draw of its pieces for as long as the pack is
 * loaded, each building a key only for the set to find it already there, and a sentence the logger
 * was never going to print. So a call site whose key is composed asks {@link #first} before it
 * composes anything, with a word of its own and the object the key is built from, and composes
 * only when that pair is new.
 * <p>
 * <strong>The pair has to settle the key, or a line goes missing.</strong> Whatever key a call site
 * would compose after a pair it has already been reached about has to be the key it composed the
 * first time, which holds when the key is built out of the pair and nothing else. The pair may be
 * finer than the key, several pieces sharing one, and that is what {@link #add} is still there for:
 * the second piece composes once, finds its key said and says nothing.
 * <p>
 * Held by equality and not by identity, which is the answer the key itself gives: two equal objects
 * compose one key. What is held is the rows of a family's own tables and the names of the game's
 * targets, so neither set grows past a handful.
 * <p>
 * Render thread only, like the draws it answers, and emptied with the family's programs.
 */
final class Refusals {

	/** The keys already said, which is what decides whether a line is printed. */
	private final Set<String> said = new LinkedHashSet<>();

	/** What each call site has been reached about, by the word it asks under. */
	private final Map<String, Set<Object>> reached = new HashMap<>();

	/**
	 * Whether the call site asking under {@code site} is reached about {@code about} for the first
	 * time this load, and so whether it has a key to compose. It counts as reached from here on.
	 *
	 * @param site  a constant of the call site's own, which keeps two sites asking about one object
	 *              apart
	 * @param about what the site's key is built from
	 */
	boolean first(String site, Object about) {
		Set<Object> seen = this.reached.get(site);
		if (seen == null) {
			seen = new HashSet<>();
			this.reached.put(site, seen);
		}

		return seen.add(about);
	}

	/** Whether the call site asking under {@code site} has been reached about {@code about}. */
	boolean reached(String site, Object about) {
		Set<Object> seen = this.reached.get(site);

		return seen != null && seen.contains(about);
	}

	/** Whether a line under this key is still to be said. It counts as said from here on. */
	boolean add(String key) {
		return this.said.add(key);
	}

	/** Forgets both, for a load that reads the pack again and may refuse again. */
	void clear() {
		this.said.clear();
		this.reached.clear();
	}
}
