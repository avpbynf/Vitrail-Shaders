package dev.vitrail.cache;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one pack load has already made of each shader unit, held in memory under the key
 * {@link ModuleCache} files it by on disk, so that a unit the load asks the compiler for a second
 * time is handed a copy of the first one's words instead of being made again.
 * <p>
 * A pack draws its entities with over a hundred programs that between them use a few dozen texts,
 * and the game's compiler is asked once per program. 26.2 remembers a module by its name, which is
 * a program's table row, and only on the road the render thread takes, so the workers' road asks
 * again for every row; 26.3 remembers none. What the compiler makes of a text does not depend on the
 * row that asked, so the second row's question has the first row's answer. The disk cache answers
 * it too where there is one, for a file read and a digest; this answers it out of memory, and
 * answers it where there is no disk cache at all, which is a run with
 * {@code -Dvitrail.moduleCache=false} and a run whose folder could not be made.
 * <p>
 * <strong>What is shared is bytes and never a module.</strong> Both games bend a module to the one
 * pipeline built from it and then let that pipeline free it: 26.2's {@code rebind} and 26.3's
 * pipeline builder write binding numbers into its words, 26.2's pipeline destroys the two device
 * modules it holds when it is destroyed, and 26.3 closes the modules a pipeline was built from once
 * it is. A module handed to two pipelines would be rewritten by the second and freed twice. So the
 * table holds a unit in the layout {@link ModuleCache} writes to disk, and every hit is a module
 * built afresh around a copy of it, as a disk hit is. Nothing native is held here, so nothing has an
 * owner to outlive: no pipeline can be using what the table drops, and a pipeline destroyed at any
 * instant frees only what it was given.
 * <p>
 * <strong>One maker of a unit at a time.</strong> The compile workers of the six families and the
 * render thread ask at once, and a unit two of them ask for in the same half second would be
 * compiled by both. {@link #claim} lets the first asker make it and holds every other asker of that
 * unit until the first is done, when the unit is in the table and the waiter finds it there. A maker
 * that failed, because the pack's text was refused, leaves nothing in the table, and the next asker
 * makes it and meets the same refusal, so every program that names a broken unit still says so.
 * Different units never wait for one another.
 * <p>
 * <strong>The table belongs to one load.</strong> It is emptied where a load begins and where its
 * background warm-up ends, and emptying it is only ever a cost: a unit asked for after that is made
 * again, or read from the disk cache where there is one, and the same words come out. Only this
 * engine's units are put in it, the game's own and Sodium's being compiled once each, so what it
 * holds is bounded by the distinct texts of one pack.
 * <p>
 * {@code -Dvitrail.shareModules=false} turns it off, so a before and an after come out of one jar.
 */
public final class ModuleShare {

	/** Off by property rather than by rebuild, so a before and an after come out of one jar. */
	private static final boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("vitrail.shareModules", "true"));

	private static final ModuleShare LOAD = new ModuleShare(ENABLED);

	private final boolean enabled;

	/** The units made so far, by key. Read by every worker and written by whoever made one. */
	private final Map<String, Blob> units = new ConcurrentHashMap<>();

	/** The units somebody is making right now. Guarded by itself, which waiters wait on too. */
	private final Set<String> making = new HashSet<>();

	/**
	 * A table of its own, which is what the tests build; the engine has the one {@link #load}
	 * answers with.
	 *
	 * @param enabled false for a table that keeps nothing and makes every asker its own maker
	 */
	ModuleShare(boolean enabled) {
		this.enabled = enabled;
	}

	/** The table of the load the engine is on. */
	public static ModuleShare load() {
		return LOAD;
	}

	/**
	 * A unit in the layout {@link ModuleCache} writes to disk, less the digest a file carries behind
	 * it: the words and whatever the game's own tables add to them.
	 *
	 * @param raw    the bytes, of which only the first {@code length} are the unit
	 * @param length how many of them count
	 */
	record Blob(byte[] raw, int length) {
	}

	/**
	 * The right to make one unit while nobody else does, to be {@link #release released} in a
	 * {@code finally} once the unit is in the table or the attempt is over. Held by the thread that
	 * took it and by no other.
	 */
	public static final class Claim {

		private final ModuleShare table;
		private final @Nullable String unit;
		private boolean held;

		private Claim(ModuleShare table, @Nullable String unit) {
			this.table = table;
			this.unit = unit;
			this.held = unit != null;
		}

		/** Lets the next asker of the unit go, which is a no-op for a claim that holds nothing. */
		public void release() {
			if (this.held) {
				this.held = false;
				this.table.done(this.unit);
			}
		}
	}

	/**
	 * Waits until nobody is making {@code unit}, and then makes the caller its maker.
	 * <p>
	 * A caller that finds the unit already made takes the claim all the same and holds it for as long
	 * as it takes to read the table, which is what keeps the waiting to one place: the caller looks
	 * the unit up after this returns, and the answer is either there or its to make.
	 * <p>
	 * A null unit, a unit of no concern to this table, and a table that is off give a claim that
	 * holds nothing. So does a wait that was interrupted: the interrupt is put back and the caller
	 * goes on to make the unit itself, which costs a compile the claim would have saved and hangs
	 * nothing.
	 *
	 * @param unit the key of the unit, or null where it has none
	 */
	public Claim claim(@Nullable String unit) {
		if (!this.enabled || unit == null) {
			return new Claim(this, null);
		}

		synchronized (this.making) {
			while (!this.making.add(unit)) {
				try {
					this.making.wait();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();

					return new Claim(this, null);
				}
			}
		}

		return new Claim(this, unit);
	}

	private void done(@Nullable String unit) {
		synchronized (this.making) {
			this.making.remove(unit);
			this.making.notifyAll();
		}
	}

	/** The unit as an earlier asker of this load made it, or null where nobody has. */
	@Nullable Blob find(@Nullable String unit) {
		return this.enabled && unit != null ? this.units.get(unit) : null;
	}

	/**
	 * Keeps a unit for the rest of the load. A unit already held stays: two makers of one text made
	 * the same words, and the first is as good as the second.
	 */
	void offer(@Nullable String unit, Blob blob) {
		if (this.enabled && unit != null) {
			this.units.putIfAbsent(unit, blob);
		}
	}

	/**
	 * Forgets every unit, which is what a load ends with and the next begins with. A unit being made
	 * meanwhile is still made, and lands in the table afterwards; claims are untouched.
	 */
	public void clear() {
		this.units.clear();
	}

	/** How many units the table holds, for the tests. */
	int size() {
		return this.units.size();
	}
}
