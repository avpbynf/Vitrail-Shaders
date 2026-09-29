package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.render.timing.FrameCensus;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.GpuDevice;
import net.minecraft.client.renderer.MappableRingBuffer;

import java.util.function.Supplier;

/**
 * Where the uniform blocks of one chain are kept: one ring buffer, the block of each geometry
 * program and the one that holds the full screen passes' each standing in a range of it.
 * <p>
 * A ring buffer is three buffers and a fence. It turns once a frame, and a turn closes the fence of
 * the buffer it leaves and makes another, and the first look at a buffer after the ring has come
 * round to it waits on the fence the turn before made. A ring for each program pays that once a
 * frame for every program, and a frame draws sixteen to nineteen of them. One ring for all of them
 * pays it once, which is the rule {@code PackChain} states for the passes' block: one ring and not N.
 * <p>
 * <strong>The ring is turned once, at the close of the frame, and by the chain and not by the
 * programs</strong>: {@link #rotate}. A block's own {@link Slot#rotate} is for a ring of its own and
 * does nothing to a range, which is what lets the same call stand in the programs' turn for either
 * kind of block.
 * <p>
 * <strong>Ranges are taken when a block is first to be written, not when its program is
 * made.</strong> A program is built on the pack-load worker, after the chain, and most of the ones
 * a pack ships are never drawn in a given world; a range spent at construction would be spent on
 * all of them. The ring is made at the first range asked for, on the render thread that holds the
 * device, and is a fixed {@value #CAPACITY_BYTES} bytes a buffer: see {@link RingLayout} for why it
 * does not grow. A block that finds no room, or is wider than the buffer, gets a ring of its own,
 * which draws exactly as a range does.
 * <p>
 * <strong>Both kinds of block answer the same questions</strong>, which are the buffer to bind a
 * slice of, where in it, the bytes to write, and how many times it has turned, so that the program
 * asks them without knowing which kind it holds. The turn is the one the stamp of
 * {@link BlockStamp} is keyed on, and it is the ring's for a range and never the program's own: a
 * count kept beside a shared ring by each program would disagree with it the frame a program is not
 * rotated but the ring is.
 * <p>
 * {@link #close} takes the ring down and leaves this able to make another, a chain that has released
 * being one that draws again when the next world is joined. Every program is released before it, and
 * a range given back after it is ignored, so that a stale range can never be laid over one of the
 * next ring's.
 * <p>
 * Render thread only.
 */
final class BlockRing {

	/**
	 * How many bytes one buffer of the shared ring holds. A block is a few kilobytes, so this is a few
	 * hundred programs where a frame draws sixteen to nineteen; three buffers of it is three
	 * megabytes of memory the CPU writes.
	 */
	static final int CAPACITY_BYTES = 1 << 20;

	private static final String LABEL = "Vitrail geometry OfGlobals";

	private final boolean shared;

	private MappableRingBuffer ring;
	private RingLayout layout;

	/** How many times the ring has turned, which is the turn a range's stamp is keyed on. */
	private long turn;

	/** Which ring the ranges belong to, so that one given back after a close is told from its own. */
	private int generation;

	/** How many ranges of the ring are held now. */
	private int held;

	/** How many blocks were refused a range since the ring was made, for the line at its close. */
	private int refused;

	/**
	 * @param shared whether programs stand in one ring. False gives every program a ring of its own,
	 *               which is {@code PassTimings.ringPerProgram}
	 */
	BlockRing(boolean shared) {
		this.shared = shared;
	}

	/**
	 * Gives a program a place for its block.
	 *
	 * @param label the name the buffers are given when the block has a ring of its own, and the
	 *              program's name in what is said of it
	 * @param bytes the block's length
	 * @return a range of the shared ring, or a ring of the block's own when there is no shared ring
	 *         or no room in it. Never null
	 */
	Slot open(GpuDevice device, Supplier<String> label, int bytes) {
		if (this.shared) {
			if (this.ring == null) {
				make(device);
			}

			int offset = this.layout.claim(bytes);
			if (offset >= 0) {
				this.held++;
				census();

				return new Slot(null, offset, bytes, this.generation);
			}

			if (this.refused++ == 0) {
				Vitrail.logger().info("{} keeps its uniform block in a ring of its own: it takes {} "
						+ "bytes and the ring the geometry programs share has {} of its {} bytes "
						+ "unclaimed, in no stretch that wide", label.get(), bytes,
						this.layout.capacity() - this.layout.claimed(), this.layout.capacity());
			}
		}

		return new Slot(new MappableRingBuffer(label,
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, bytes), 0, bytes, -1);
	}

	private void make(GpuDevice device) {
		// Never under sixteen, which is what a std140 block starts on whatever the device would
		// accept: the chain's own ring is laid out the same way.
		int alignment = Math.max(16, device.getDeviceInfo().limits().minUniformOffsetAlignment());
		this.layout = new RingLayout(CAPACITY_BYTES, alignment);
		this.ring = new MappableRingBuffer(() -> LABEL,
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, this.layout.capacity());
		this.refused = 0;
		Vitrail.logger().info("The uniform blocks of this load share one ring of {} bytes a buffer, "
				+ "each block at a multiple of {} bytes", this.layout.capacity(), alignment);
	}

	/**
	 * Turns the ring, once a frame, at the close of it. Called whether or not a program has drawn:
	 * the ring is what the terrain writes into on the frames the chain draws nothing.
	 */
	void rotate() {
		if (this.ring == null) {
			return;
		}

		FrameCensus.rotated();
		this.layout.turned();
		this.ring.rotate();
		this.turn++;
	}

	/**
	 * Takes the ring down, for a chain that is released. Every program has given its range back by
	 * now, and what is said here is the most the ring held.
	 */
	void close() {
		if (this.ring == null) {
			return;
		}

		Vitrail.logger().info("The uniform blocks of that load held at most {} of the {} bytes of "
				+ "their shared ring, and {} kept a ring of their own", this.layout.peak(),
				this.layout.capacity(), this.refused);
		this.ring.close();
		this.ring = null;
		this.layout = null;
		this.held = 0;
		this.generation++;
		FrameCensus.blockRing(0, 0, 0, 0);
	}

	private void census() {
		FrameCensus.blockRing(this.layout.capacity(), this.layout.claimed(), this.layout.peak(),
				this.held);
	}

	/**
	 * One program's block: a range of the shared ring, or a ring of its own.
	 * <p>
	 * The range and the ring are the two answers to one question, so a program holds one of these and
	 * asks it for everything, and never learns which it has been given. A block is closed once, by
	 * the program that holds it, and closing it twice is the same as once.
	 */
	final class Slot {

		private final MappableRingBuffer own;
		private final int offset;
		private final int bytes;
		private final int generation;

		/** How many times a ring of its own has turned. A range has no turns of its own. */
		private long turns;

		private boolean open = true;

		private Slot(MappableRingBuffer own, int offset, int bytes, int generation) {
			this.own = own;
			this.offset = offset;
			this.bytes = bytes;
			this.generation = generation;
		}

		/** Whether this is a range of the shared ring and not a ring of its own. */
		boolean shared() {
			return this.own == null;
		}

		/**
		 * The buffer the block stands in this frame. Looking at one the ring has just come round to
		 * waits on the fence that guards it, whichever program looks first.
		 */
		GpuBuffer buffer() {
			return this.own != null ? this.own.currentBuffer() : BlockRing.this.ring.currentBuffer();
		}

		/** Where in {@link #buffer} the block starts. */
		int offset() {
			return this.offset;
		}

		/**
		 * The block's bytes in the buffer of this frame, mapped for writing. A range is mapped as
		 * itself and not as the whole buffer, so that a write that ran past the block's length would
		 * be refused where it starts, and not land on the range of another program.
		 */
		GpuBufferSlice.MappedView map() {
			return this.own != null
					? this.own.currentBuffer().map(false, true)
					: BlockRing.this.ring.currentBuffer().map(this.offset, this.bytes, false, true);
		}

		/**
		 * How many times the ring the block stands in has turned, which is the turn a stamp of what
		 * was written into it is keyed on.
		 */
		long turn() {
			return this.own != null ? this.turns : BlockRing.this.turn;
		}

		/**
		 * Turns a ring of its own, and leaves a range alone: the shared ring is turned once by
		 * {@link BlockRing#rotate}, and turning it here would turn it once for every program.
		 */
		void rotate() {
			if (this.own != null) {
				FrameCensus.rotated();
				this.own.rotate();
				this.turns++;
			}
		}

		/**
		 * Gives the block back. A ring of its own is closed; a range is free for another program from
		 * the ring's next turn, and the ring itself is left standing.
		 */
		void close() {
			if (!this.open) {
				return;
			}

			this.open = false;
			if (this.own != null) {
				this.own.close();

				return;
			}

			BlockRing blocks = BlockRing.this;
			if (this.generation != blocks.generation) {
				return;
			}

			blocks.layout.release(this.offset, this.bytes);
			blocks.held--;
			blocks.census();
		}
	}
}
