package dev.vitrail.pack.source;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

/**
 * Tracks which branch of the conditional directives is live while reading a file.
 * <p>
 * Each level remembers three things: whether it is live, whether any branch of it has been
 * taken already, and whether its parent was live. The middle one is what makes {@code #elif}
 * and {@code #else} behave: once a branch has run, the rest of the chain stays dead even when
 * its own condition happens to be true.
 * <p>
 * The same stack is used for pack sources and for {@code shaders.properties}, which also
 * carries conditionals. Two implementations would eventually disagree about what is live, and
 * the disagreement would show up as a program that exists in one place and not the other.
 */
public final class ConditionStack {

	private final Deque<Level> levels = new ArrayDeque<>();

	public void ifDirective(boolean condition) {
		push(condition, false);
	}

	/**
	 * A conditional whose directive is written out rewritten, because what the pack wrote is not
	 * one the compiler will read. Marked apart from the rest so that one the file never closes can
	 * be closed where the file ends: the text carries its directives to the compiler, and what this
	 * reader put in the text has to balance in the text.
	 */
	public void rewrittenIfDirective(boolean condition) {
		push(condition, true);
	}

	private void push(boolean condition, boolean rewritten) {
		boolean parent = active();
		this.levels.push(new Level(parent && condition, condition, parent, rewritten));
	}

	/**
	 * The condition is only evaluated when it can still matter, which is not an optimisation:
	 * a later branch may well be nonsense once an earlier one has been taken.
	 */
	public void elifDirective(BooleanSupplier condition) {
		Level level = this.levels.peek();
		if (level == null) {
			return;
		}

		if (level.taken) {
			level.active = false;
			return;
		}

		boolean value = condition.getAsBoolean();
		level.active = level.parentActive && value;
		level.taken = value;
	}

	public void elseDirective() {
		Level level = this.levels.peek();
		if (level == null) {
			return;
		}

		level.active = level.parentActive && !level.taken;
		level.taken = true;
	}

	/** An unmatched {@code #endif} is ignored rather than fatal; packs do ship them. */
	public void endifDirective() {
		this.levels.poll();
	}

	public boolean active() {
		Level level = this.levels.peek();

		return level == null || level.active;
	}

	public int depth() {
		return this.levels.size();
	}

	/** How many of the levels still open were opened by {@link #rewrittenIfDirective}. */
	public int unclosedRewritten() {
		return (int) this.levels.stream().filter(level -> level.rewritten).count();
	}

	private static final class Level {

		private boolean active;
		private boolean taken;
		private final boolean parentActive;
		private final boolean rewritten;

		private Level(boolean active, boolean taken, boolean parentActive, boolean rewritten) {
			this.active = active;
			this.taken = taken;
			this.parentActive = parentActive;
			this.rewritten = rewritten;
		}
	}
}
