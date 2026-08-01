package dev.vitrail.pack;

/**
 * What happened while a unit was flattened. These are the numbers the log prints and the ones
 * that can be checked against the corpus measurements, so they count events rather than
 * describing them: an include seen but not followed is not an error, it is a branch the current
 * settings turned off.
 */
public record ExpansionStats(int seen, int followed, int skipped, int missing, int duplicates,
		int cycles, int maxDepth, int tooDeep, int conditionals, int undecidable, int exhausted) {

	public static final ExpansionStats NONE = new ExpansionStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

	public ExpansionStats plus(ExpansionStats other) {
		return new ExpansionStats(
				this.seen + other.seen,
				this.followed + other.followed,
				this.skipped + other.skipped,
				this.missing + other.missing,
				this.duplicates + other.duplicates,
				this.cycles + other.cycles,
				Math.max(this.maxDepth, other.maxDepth),
				this.tooDeep + other.tooDeep,
				this.conditionals + other.conditionals,
				this.undecidable + other.undecidable,
				this.exhausted + other.exhausted);
	}

	/** True when nothing went wrong, as opposed to nothing having been skipped. */
	public boolean clean() {
		return this.missing == 0 && this.cycles == 0 && this.tooDeep == 0 && this.exhausted == 0;
	}

	@Override
	public String toString() {
		return "seen " + this.seen + ", followed " + this.followed + ", skipped " + this.skipped
				+ ", missing " + this.missing + ", duplicates " + this.duplicates
				+ ", cycles " + this.cycles + ", max depth " + this.maxDepth
				+ ", too deep " + this.tooDeep + ", conditionals " + this.conditionals
				+ ", undecidable " + this.undecidable + ", budget exhausted " + this.exhausted;
	}
}
