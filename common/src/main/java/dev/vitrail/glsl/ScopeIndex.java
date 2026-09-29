package dev.vitrail.glsl;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Names that mean something particular over a range of lines, answered without walking every name
 * that ever did.
 * <p>
 * {@link GlslTranslator} asks, once per lookup it meets, whether the sampler that lookup goes
 * through is a parameter of the function it stands in, a comparison sampler, or a parameter
 * already proven to be read at the base level: is there a range recorded for this name that holds
 * this line. A pack spells the sampler parameter of every helper the same way ({@code image},
 * {@code tex}), so the ranges of one name run into thousands and a list walked from its head made
 * the question cost as much as the unit was long, once per lookup: the passes that ask it were
 * quadratic, and at eighty thousand lines they were most of the translation.
 * <p>
 * The ranges of a name are kept sorted by where they begin, each with the furthest end reached by
 * everything that begins at or before it, so that a line is inside some range exactly when the
 * furthest end reached by the ranges beginning at or before that line is not short of it. That
 * holds whatever the ranges are, overlapping or not, which is what a list walk answered for and
 * what a unit no compiler has read requires. The order is settled when a name is first asked about
 * after it gained a range, and not when it gains one.
 */
final class ScopeIndex {

	private final Map<String, Ranges> byName = new HashMap<>();

	/** Records that the name means something from one line to another, both included. */
	void add(String name, int from, int to) {
		this.byName.computeIfAbsent(name, ignored -> new Ranges()).add(from, to);
	}

	/** Whether some range recorded for this name holds this line. */
	boolean covers(String name, int line) {
		Ranges own = this.byName.get(name);

		return own != null && own.covers(line);
	}

	/** The ranges of one name, as the pairs they were recorded in and, once asked, in order. */
	private static final class Ranges {

		/** Each range as its beginning in the high half and its end in the low half. */
		private long[] packed = new long[2];
		private int size;

		/** The furthest end reached by the ranges up to each place of the sorted order. */
		private int[] reach = new int[0];

		private boolean sorted = true;

		void add(int from, int to) {
			if (this.size == this.packed.length) {
				this.packed = Arrays.copyOf(this.packed, this.size * 2);
			}

			this.packed[this.size++] = ((long) from << 32) | (to & 0xFFFFFFFFL);
			this.sorted = false;
		}

		boolean covers(int line) {
			if (!this.sorted) {
				settle();
			}

			// The last range that begins at or before the line, by bisection on the beginnings.
			int low = 0;
			int high = this.size - 1;
			int last = -1;
			while (low <= high) {
				int middle = (low + high) >>> 1;
				if ((int) (this.packed[middle] >> 32) <= line) {
					last = middle;
					low = middle + 1;
				} else {
					high = middle - 1;
				}
			}

			return last >= 0 && this.reach[last] >= line;
		}

		private void settle() {
			Arrays.sort(this.packed, 0, this.size);
			if (this.reach.length < this.size) {
				this.reach = new int[this.packed.length];
			}

			int furthest = Integer.MIN_VALUE;
			for (int at = 0; at < this.size; at++) {
				furthest = Math.max(furthest, (int) this.packed[at]);
				this.reach[at] = furthest;
			}

			this.sorted = true;
		}
	}
}
