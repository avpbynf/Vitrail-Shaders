package dev.vitrail.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which byte ranges of one buffer are spoken for, so that many uniform blocks can stand in it at
 * once, each at an offset the device accepts for a bound block.
 * <p>
 * The blocks are not one size: a program lifts only the uniforms its stages use, so two programs of
 * a pack come to blocks of different lengths, and a layout of one width for all would be as wide as
 * the widest. A range is taken from the first free stretch that holds it, rounded up to the
 * alignment, and every offset handed out is a multiple of it.
 * <p>
 * <strong>A range given back is not free until the ring has turned.</strong> A pass that names a
 * block's slice is recorded and executed later, so the range a released program stood in is still
 * read by whatever it recorded this frame; handing it to another program at once would have that
 * program's bytes drawn under the first one's draws. After a turn the buffer in hand is another one,
 * and the buffer that was read from is not written again until the ring has come round to it, which
 * the ring's own fence waits for.
 * <p>
 * <strong>The layout is fixed in size and says no when it is full.</strong> Growing it would mean a
 * new buffer while passes recorded this frame hold slices of the old one, and a block that finds no
 * room can stand in a ring of its own instead. See {@link #claim}.
 * <p>
 * Render thread only, like the ring it lays out.
 */
final class RingLayout {

	private final int capacity;
	private final int alignment;

	/** The free stretches by offset, each with its length, never two of them touching. */
	private final TreeMap<Integer, Integer> free = new TreeMap<>();

	/** Offsets and lengths given back since the last turn, in pairs, and not yet free. */
	private final List<int[]> returned = new ArrayList<>();

	private int claimed;
	private int peak;
	private int claims;

	/**
	 * @param capacity  how many bytes the buffer holds, of which the last part that does not fill an
	 *                  alignment is left unused
	 * @param alignment what every offset is a multiple of, and every length is rounded up to
	 */
	RingLayout(int capacity, int alignment) {
		if (alignment <= 0 || capacity < alignment) {
			throw new IllegalArgumentException("a layout of " + capacity + " bytes at alignment "
					+ alignment);
		}

		this.alignment = alignment;
		this.capacity = capacity / alignment * alignment;
		this.free.put(0, this.capacity);
	}

	/**
	 * How much room a block of this length takes when its neighbours have to start where the device
	 * accepts a bound block: the length, rounded up to the alignment.
	 */
	static int slotBytes(int blockBytes, int alignment) {
		return Math.ceilDiv(blockBytes, alignment) * alignment;
	}

	/**
	 * Takes a range for a block.
	 *
	 * @param blockBytes the block's length
	 * @return the range's offset, or -1 when no free stretch holds it: the layout is full, or the
	 *         block is wider than the whole buffer. Either way the caller keeps its block somewhere
	 *         else and nothing here has changed
	 */
	int claim(int blockBytes) {
		int size = slotBytes(Math.max(1, blockBytes), this.alignment);
		if (size > this.capacity) {
			return -1;
		}

		int at = -1;
		int length = 0;
		for (Map.Entry<Integer, Integer> stretch : this.free.entrySet()) {
			if (stretch.getValue() >= size) {
				at = stretch.getKey();
				length = stretch.getValue();
				break;
			}
		}

		if (at < 0) {
			return -1;
		}

		this.free.remove(at);
		if (length > size) {
			this.free.put(at + size, length - size);
		}

		this.claimed += size;
		this.peak = Math.max(this.peak, this.claimed);
		this.claims++;

		return at;
	}

	/**
	 * Gives a range back. It stays out of reach of {@link #claim} until {@link #turned}.
	 *
	 * @throws IllegalArgumentException for a range that was not handed out, or is given back twice:
	 *                                  two blocks standing in the same bytes is a frame that draws
	 *                                  one program with another's values and says nothing
	 */
	void release(int offset, int blockBytes) {
		int size = slotBytes(Math.max(1, blockBytes), this.alignment);
		if (offset < 0 || offset % this.alignment != 0 || offset + size > this.capacity) {
			throw new IllegalArgumentException("no range of " + size + " bytes at " + offset);
		}

		Map.Entry<Integer, Integer> before = this.free.floorEntry(offset);
		Map.Entry<Integer, Integer> after = this.free.ceilingEntry(offset);
		if ((before != null && before.getKey() + before.getValue() > offset)
				|| (after != null && after.getKey() < offset + size)) {
			throw new IllegalArgumentException("the range at " + offset + " is free already");
		}

		for (int[] gone : this.returned) {
			if (offset < gone[0] + gone[1] && gone[0] < offset + size) {
				throw new IllegalArgumentException("the range at " + offset
						+ " was given back already");
			}
		}

		this.returned.add(new int[] {offset, size});
		this.claimed -= size;
	}

	/** The ring has turned: what was given back since the last turn is free from here on. */
	void turned() {
		for (int[] gone : this.returned) {
			int at = gone[0];
			int length = gone[1];
			Map.Entry<Integer, Integer> before = this.free.floorEntry(at);
			if (before != null && before.getKey() + before.getValue() == at) {
				at = before.getKey();
				length += before.getValue();
				this.free.remove(at);
			}

			Integer after = this.free.get(at + length);
			if (after != null) {
				this.free.remove(at + length);
				length += after;
			}

			this.free.put(at, length);
		}

		this.returned.clear();
	}

	/** How many bytes the buffer holds that this layout can hand out. */
	int capacity() {
		return this.capacity;
	}

	/** What every offset is a multiple of. */
	int alignment() {
		return this.alignment;
	}

	/** How many bytes stand claimed now, rounded up as they are laid out. */
	int claimed() {
		return this.claimed;
	}

	/** The most bytes that stood claimed at once since the layout was made. */
	int peak() {
		return this.peak;
	}

	/** How many ranges have been claimed since the layout was made, given back ones included. */
	int claims() {
		return this.claims;
	}
}
