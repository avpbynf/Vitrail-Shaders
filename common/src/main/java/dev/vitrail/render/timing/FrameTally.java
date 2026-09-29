package dev.vitrail.render.timing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The sums behind {@link FrameCensus}, and the words they are printed in.
 * <p>
 * Held apart from the hooks that feed them so that the arithmetic can be read without a game
 * running: which bind was redundant, which draw belongs to which family, how many programs wrote
 * their block twice, and what a window's averages come to are all answered here from plain calls.
 * The hooks own the switch and the family of a pipeline; this owns nothing but numbers.
 * <p>
 * <strong>One thread writes, the render thread.</strong> There is no fence anywhere in here, and the
 * reliance is the one the rest of the census stands on: the frame is recorded on one thread, and the
 * two callers that clear a tally are not frames. A descriptor pushed off that thread can lose a count
 * to the race, and nothing here is worth a fence to keep it.
 */
final class FrameTally {

	/** Where a bind or a draw is filed. The order is the order of the rows. */
	enum Family {
		ENTITY("entity"),
		TERRAIN("terrain"),
		PARTICLE("particle"),
		DISTANT("far terrain"),
		SKY("sky, weather and cloud"),
		CHAIN("chain passes"),
		OTHER("not this engine's");

		private final String label;

		Family(String label) {
			this.label = label;
		}

		/**
		 * The family a geometry program names itself by, {@code GeometryProgram.Pass.family}. A name
		 * this census has no row for lands in {@link #OTHER}, which reads as the game's own draws
		 * being heavier than they are and is the one place a renamed family would show.
		 */
		static Family named(String family) {
			return switch (family) {
				case "chunk" -> TERRAIN;
				case "entity" -> ENTITY;
				case "particles" -> PARTICLE;
				case "far terrain" -> DISTANT;
				case "sky", "weather", "cloud" -> SKY;
				default -> OTHER;
			};
		}
	}

	/**
	 * What a geometry program sets on a pass and this census counts the redundant sets of. The order
	 * is the order of the columns.
	 */
	enum Kind {
		BLOCK("block"),
		TEXTURE("texture"),
		TRANSFORMS("transforms");

		private final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	/**
	 * What one name of a pass holds, as the census last saw it set: the pass, the value and, for an
	 * image, the sampler beside it. One per name, made the first time the name is set.
	 */
	private static final class Held {

		Object pass;
		Object value;
		Object sampler;
	}

	private static final int FAMILIES = Family.values().length;
	private static final int KINDS = Kind.values().length;

	private final long[] binds = new long[FAMILIES];
	private final long[] redundant = new long[FAMILIES];
	private final long[] draws = new long[FAMILIES];

	/** The last pass a pipeline was set on, and what was set, so that a repeat can be told. */
	private Object lastPass;
	private Object lastBound;
	private Family lastFamily = Family.OTHER;

	private long pushes;
	private long allocatedSets;
	private long descriptors;
	private long programBinds;
	private long programKept;

	private final long[] sets = new long[KINDS];
	private final long[] unchanged = new long[KINDS];

	/**
	 * What the pass holds under each name a set has been seen for. Both games keep a pass's uniforms
	 * by name and keep them across a pipeline bind, so a set is redundant when the pass, the value and
	 * the sampler are the ones the name was last set with, whichever program set it. Filled as names
	 * are met, and emptied of what it names at the end of every frame, see {@link #endFrame}.
	 */
	private final Map<String, Held> held = new HashMap<>();
	private final List<Held> heldAll = new ArrayList<>();

	private long geometryWrites;
	private long geometryPrograms;
	private long geometryRewritten;
	private long chainWrites;
	private long farWrites;

	private long rotations;

	private long sharedPrograms;
	private long ownPrograms;

	/**
	 * Where the ring the geometry programs share stands, as the ring last said. A reading and not a
	 * count, so {@link #clear} leaves it: the window empties, the ring does not.
	 */
	private int ringCapacity;
	private int ringClaimed;
	private int ringPeak;
	private int ringPrograms;

	private long farCameraSections;
	private long farCameraPasses;
	private long farShadowSections;
	private long farShadowPasses;

	private long readyCalls;
	private long readyPrepared;
	private long readyDone;

	private long frames;

	/**
	 * Which frame is in progress, counted for good. A block remembers the frame it was last written in
	 * against this, so a window that is emptied at a report does not make every program look new.
	 */
	private long frameNumber;

	/**
	 * A pipeline set on a pass.
	 *
	 * @param pass     the pass, by identity
	 * @param pipeline what the pass now holds, by identity
	 * @param family   whose it is
	 */
	void bound(Object pass, Object pipeline, Family family) {
		int at = family.ordinal();
		this.binds[at]++;
		if (pass == this.lastPass && pipeline == this.lastBound) {
			this.redundant[at]++;
		}

		this.lastPass = pass;
		this.lastBound = pipeline;
		this.lastFamily = family;
	}

	/**
	 * A draw recorded into a pass, filed under the family of the pipeline that pass holds. A pass
	 * this tally has seen no bind on has none to go by, and that is a draw of somebody else's.
	 */
	void drawn(Object pass) {
		this.draws[(pass == this.lastPass ? this.lastFamily : Family.OTHER).ordinal()]++;
	}

	/** One descriptor set written for a draw, and whether it was bound as an allocated set instead. */
	void pushed(boolean allocated) {
		this.pushes++;
		if (allocated) {
			this.allocatedSets++;
		}
	}

	/** One descriptor of a push. */
	void described() {
		this.descriptors++;
	}

	void programBound() {
		this.programBinds++;
	}

	/** A program bound into a pass it was already standing in. */
	void programKept() {
		this.programKept++;
	}

	/**
	 * A set of a value on a pass, and whether the pass already held exactly that under the name.
	 * <p>
	 * A set that is not counted still moves what the pass holds, so that the first one that is counted
	 * is compared with what really stands there. Values are compared by their own equality, which is
	 * identity for an image and a sampler and the buffer, offset and length for a slice.
	 *
	 * @param counted whether this is one of the sets the census is about
	 * @param sampler the sampler beside an image, or null for a value that has none
	 */
	void set(Kind kind, Object pass, String name, Object value, Object sampler, boolean counted) {
		Held held = this.held.get(name);
		if (held == null) {
			held = new Held();
			this.held.put(name, held);
			this.heldAll.add(held);
		}

		boolean same = held.pass == pass && held.sampler == sampler && Objects.equals(held.value, value);
		held.pass = pass;
		held.value = value;
		held.sampler = sampler;
		if (counted) {
			this.sets[kind.ordinal()]++;
			if (same) {
				this.unchanged[kind.ordinal()]++;
			}
		}
	}

	/** A geometry program's block, written. The block says whether this is its second time. */
	void geometryWritten(FrameCensus.Block block) {
		this.geometryWrites++;
		if (block.frame != this.frameNumber) {
			block.frame = this.frameNumber;
			block.writes = 0;
			this.geometryPrograms++;
		}

		if (++block.writes == 2) {
			this.geometryRewritten++;
		}
	}

	void chainWritten() {
		this.chainWrites++;
	}

	void farWritten() {
		this.farWrites++;
	}

	void rotated() {
		this.rotations++;
	}

	/**
	 * A geometry program drawn, once for each frame it is, and whether its block stands in the ring
	 * the programs share or in one of its own. The block says whether this is its first time in the
	 * frame.
	 */
	void blockPlaced(FrameCensus.Block block, boolean shared) {
		if (block.placedFrame == this.frameNumber) {
			return;
		}

		block.placedFrame = this.frameNumber;
		if (shared) {
			this.sharedPrograms++;
		} else {
			this.ownPrograms++;
		}
	}

	/** Where the shared ring stands: what it can hold, what stands in it, and how many programs. */
	void blockRing(int capacity, int claimed, int peak, int programs) {
		this.ringCapacity = capacity;
		this.ringClaimed = claimed;
		this.ringPeak = peak;
		this.ringPrograms = programs;
	}

	/** One section of the far terrain given a uniform of its own inside a pass. */
	void farSection(boolean shadow) {
		if (shadow) {
			this.farShadowSections++;
		} else {
			this.farCameraSections++;
		}
	}

	/** One pass of the far terrain that walks its sections. */
	void farPass(boolean shadow) {
		if (shadow) {
			this.farShadowPasses++;
		} else {
			this.farCameraPasses++;
		}
	}

	void readied() {
		this.readyCalls++;
	}

	void readyPrepared() {
		this.readyPrepared++;
	}

	void readyDone() {
		this.readyDone++;
	}

	/**
	 * The end of a frame: counted, and the pass forgotten. Holding the last pass past its frame would
	 * keep a closed one reachable, and a bind on the next frame's pass is a first bind whatever the
	 * pipeline is. What each name held is forgotten for the same reason, and with the pass go the
	 * images and slices it was holding.
	 */
	void endFrame() {
		this.frames++;
		this.frameNumber++;
		this.lastPass = null;
		this.lastBound = null;
		this.lastFamily = Family.OTHER;
		for (int at = 0; at < this.heldAll.size(); at++) {
			Held held = this.heldAll.get(at);
			held.pass = null;
			held.value = null;
			held.sampler = null;
		}
	}

	long frames() {
		return this.frames;
	}

	long binds(Family family) {
		return this.binds[family.ordinal()];
	}

	long redundant(Family family) {
		return this.redundant[family.ordinal()];
	}

	long draws(Family family) {
		return this.draws[family.ordinal()];
	}

	long sets(Kind kind) {
		return this.sets[kind.ordinal()];
	}

	long unchanged(Kind kind) {
		return this.unchanged[kind.ordinal()];
	}

	/** Empties the window and keeps the frame number, which only ever goes up. */
	void clear() {
		Arrays.fill(this.binds, 0L);
		Arrays.fill(this.redundant, 0L);
		Arrays.fill(this.draws, 0L);
		this.lastPass = null;
		this.lastBound = null;
		this.lastFamily = Family.OTHER;
		this.pushes = 0;
		this.allocatedSets = 0;
		this.descriptors = 0;
		this.programBinds = 0;
		this.programKept = 0;
		Arrays.fill(this.sets, 0L);
		Arrays.fill(this.unchanged, 0L);
		this.geometryWrites = 0;
		this.geometryPrograms = 0;
		this.geometryRewritten = 0;
		this.chainWrites = 0;
		this.farWrites = 0;
		this.rotations = 0;
		this.sharedPrograms = 0;
		this.ownPrograms = 0;
		this.farCameraSections = 0;
		this.farCameraPasses = 0;
		this.farShadowSections = 0;
		this.farShadowPasses = 0;
		this.readyCalls = 0;
		this.readyPrepared = 0;
		this.readyDone = 0;
		this.frames = 0;
	}

	/**
	 * The window as lines of the log, every number an average over the frames in it. Empty for a
	 * window with no frame, which is nothing to average.
	 * <p>
	 * The shape is the pass table's: the figures first and the name last, so a column of them reads
	 * down, and a word in brackets where a good number is obvious.
	 *
	 * @param seconds how long the window was, which the header repeats
	 */
	List<String> lines(double seconds) {
		List<String> lines = new ArrayList<>();
		if (this.frames == 0) {
			return lines;
		}

		double frames = this.frames;
		lines.add(String.format(Locale.ROOT, "Frame census over %.1f s, %d frames, an average frame:",
				seconds, this.frames));
		lines.add(String.format(Locale.ROOT, "  %s pipeline binds, %s of them redundant (the pass "
						+ "already held that pipeline: 0 would be ideal), %s draws",
				per(sum(this.binds), frames), per(sum(this.redundant), frames),
				per(sum(this.draws), frames)));
		for (Family family : Family.values()) {
			int at = family.ordinal();
			if (this.binds[at] == 0 && this.draws[at] == 0) {
				continue;
			}

			// Terrain draws are Sodium's own commands, recorded straight into the command buffer and
			// never through the pass, so the pass has no count of them to give. A dash says that,
			// where a nought would say there were none.
			String drawn = family == Family.TERRAIN ? "       -" : column(this.draws[at], frames);
			lines.add(String.format(Locale.ROOT, "  %s binds %s redundant %s draws  %s",
					column(this.binds[at], frames), column(this.redundant[at], frames), drawn,
					family.label));
		}

		lines.add(String.format(Locale.ROOT, "  %s descriptor pushes, %s descriptors each, %s bound as "
						+ "an allocated set instead (a push a draw is the most it can be)",
				per(this.pushes, frames),
				this.pushes == 0 ? "0.0" : String.format(Locale.ROOT, "%.1f",
						this.descriptors / (double) this.pushes),
				per(this.allocatedSets, frames)));
		lines.add(String.format(Locale.ROOT, "  %s program binds, each one a uniform block and its "
						+ "samplers set on the pass, and %s more that found the program already "
						+ "standing in it and set only the images the draw brought",
				per(this.programBinds, frames), per(this.programKept, frames)));
		lines.add(String.format(Locale.ROOT, "  %s sets that changed nothing (the pass already held that "
						+ "value: 0 would be ideal), of those a program made while already standing in "
						+ "its pass: %s",
				per(sum(this.unchanged), frames), unchangedByKind(frames)));
		lines.add(String.format(Locale.ROOT, "  %s geometry block writes over %s programs, %s of them "
						+ "written more than once (0 would be ideal), %s chain block writes, %s far "
						+ "terrain block writes",
				per(this.geometryWrites, frames), per(this.geometryPrograms, frames),
				per(this.geometryRewritten, frames), per(this.chainWrites, frames),
				per(this.farWrites, frames)));
		lines.add(String.format(Locale.ROOT, "  %s ring rotations, each one a fence created",
				per(this.rotations, frames)));
		lines.add(String.format(Locale.ROOT, "  %s drawn geometry programs keep their block in the ring "
						+ "they share, %s in a ring of their own (0 would be ideal)",
				per(this.sharedPrograms, frames), per(this.ownPrograms, frames)));
		if (this.ringCapacity > 0) {
			lines.add(String.format(Locale.ROOT, "  the shared ring holds %d blocks in %d of its %d "
							+ "bytes, %d bytes at most",
					this.ringPrograms, this.ringClaimed, this.ringCapacity, this.ringPeak));
		}
		if (this.farCameraPasses + this.farShadowPasses > 0) {
			lines.add(String.format(Locale.ROOT, "  far terrain section uniforms, one descriptor push "
							+ "each: %s over %s camera passes, %s over %s shadow passes",
					per(this.farCameraSections, frames), per(this.farCameraPasses, frames),
					per(this.farShadowSections, frames), per(this.farShadowPasses, frames)));
		}

		lines.add(String.format(Locale.ROOT, "  %s PackChain.ready() calls, %s of them reaching the "
						+ "targets' prepare and %s handing a frame back (one full prepare would do)",
				per(this.readyCalls, frames), per(this.readyPrepared, frames),
				per(this.readyDone, frames)));

		return lines;
	}

	/** The redundant sets of each kind over the sets of that kind, as one phrase of the line. */
	private String unchangedByKind(double frames) {
		StringBuilder text = new StringBuilder();
		for (Kind kind : Kind.values()) {
			int at = kind.ordinal();
			if (at > 0) {
				text.append(", ");
			}

			text.append(kind.label).append(' ').append(per(this.unchanged[at], frames)).append(" of ")
					.append(per(this.sets[at], frames));
		}

		return text.toString();
	}

	private static long sum(long[] counts) {
		long total = 0;
		for (long count : counts) {
			total += count;
		}

		return total;
	}

	private static String per(long count, double frames) {
		return String.format(Locale.ROOT, "%.1f", count / frames);
	}

	private static String column(long count, double frames) {
		return String.format(Locale.ROOT, "%8.1f", count / frames);
	}
}
