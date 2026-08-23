package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Where the GPU spends a frame, one row per render pass label, printed to the log at an interval.
 * <p>
 * Every pass the game opens, its own, Sodium's, this engine's and any other mod's, carries a label,
 * and the one thing a frame-rate counter cannot say is which of them the time goes to. This writes a
 * timestamp before each pass is recorded and another after it is submitted, on the device's own
 * query pool, reads the pair back a few frames later when the card has answered, and sums the
 * difference under the label. The report is sorted by cost and says what share of the pass total
 * each label takes, how many times a frame it ran, and how that total compares to the span from
 * the first pass of the frame to the last and to the interval between frames: the gap between the
 * first two is copies, clears and barriers between passes, the gap to the third is the CPU, or
 * vertical sync, or the limiter.
 * <p>
 * A one-shot count of the first full frame a pack draws is always on: how many render passes
 * opened, how many textures were cleared, how many were copied. That is the number a queue-submit
 * trace is counting, and it costs an integer add per pass. The timed report stays off unless the
 * JVM is started with {@code -Dvitrail.passTimings=N}, N being the seconds between two reports.
 * The timestamps are written where the game's own Tracy profiler writes its zones, outside the pass
 * on the frame's command buffer, and they are written at the all-commands stage, so a pass's time is
 * measured from the end of whatever preceded it to the end of its own work: serialised, which is
 * what a frame of mostly full-screen passes with barriers between them is anyway.
 * <p>
 * <strong>The numbers are the card's, not the clock's.</strong> Ticks are converted with the
 * device's timestamp period, and a pair is only counted once both halves are available, without
 * waiting; a frame whose queries the card has not answered by the time its slot comes round again,
 * eight frames later, is dropped and counted as dropped in the report. A frame with more passes
 * than a slot holds is measured up to the last one that fits and counted as overflowed.
 */
public final class PassTimings {

	/** Seconds between two reports, and the switch: absent or zero, nothing here runs. */
	private static final int REPORT_SECONDS = Integer.getInteger("vitrail.passTimings", 0);

	private static final boolean ENABLED = REPORT_SECONDS > 0;

	/**
	 * Passes one frame can record. A pack whose chain is long and whose mip chains are deep sits
	 * around a hundred with the game's own passes counted; this leaves room for a frame that is not
	 * typical without sizing the pool for one that cannot happen.
	 */
	private static final int PASSES_PER_FRAME = 512;

	private static final int QUERIES_PER_FRAME = PASSES_PER_FRAME * 2;

	/**
	 * Frames the pool holds at once, each in its own slot. The game keeps two in flight, and a
	 * query written eight frames ago that is still unanswered is one to give up on, not to wait for.
	 */
	private static final int SLOTS = 8;

	/** Rows printed before the rest is folded into one line. */
	private static final int ROWS = 24;

	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	private static GpuQueryPool pool;
	private static float nanosPerTick;
	private static long frameNumber;
	private static Frame current;
	private static final ArrayDeque<Frame> pending = new ArrayDeque<>();

	private static final Map<String, Row> rows = new HashMap<>();
	private static long framesSummed;
	private static long framesDropped;
	private static long framesOverflowed;
	private static long passTicks;
	private static long spanTicks;
	private static long frameEnds;
	private static long lastReport;

	/**
	 * One-shot census of the first frame a pack actually draws. Cheap integers, no GPU queries: the
	 * pass-timings flag still owns the clock. Armed at the frame's open, printed at its close, and
	 * only once the chain is warm, so a frame still compiling one program a time is not the one
	 * counted.
	 */
	private static boolean censusPrinted;
	private static boolean censusArmed;
	private static boolean censusComplete;
	private static int censusPasses;
	private static int censusClears;
	private static int censusCopies;
	private static Supplier<String> censusOpenLabel;
	private static final Map<String, Integer> censusLabels = new HashMap<>();

	private PassTimings() {
	}

	/**
	 * Starts counting this frame's render passes, clears and copies, unless a pack load has already
	 * printed that line. Safe to call twice in one frame: the second is free.
	 */
	public static void armCensus() {
		if (censusPrinted || censusArmed) {
			return;
		}

		censusArmed = true;
		censusComplete = false;
		censusPasses = 0;
		censusClears = 0;
		censusCopies = 0;
		censusOpenLabel = null;
		censusLabels.clear();
	}

	/** The chain is warm: this frame is the one whose totals should be printed. */
	public static void finishCensus() {
		if (censusPrinted || !censusArmed) {
			return;
		}

		censusComplete = true;
	}

	/** A new pack is being read, so the next full frame it draws is counted again. */
	public static void resetCensus() {
		censusPrinted = false;
		censusArmed = false;
		censusComplete = false;
		censusPasses = 0;
		censusClears = 0;
		censusCopies = 0;
		censusOpenLabel = null;
		censusLabels.clear();
	}

	/** A standalone texture clear, which the backend ends with a full memory barrier. */
	public static void censusClear() {
		if (censusArmed) {
			censusClears++;
		}
	}

	/** A texture-to-texture copy, which the backend ends the same way. */
	public static void censusCopy() {
		if (censusArmed) {
			censusCopies++;
		}
	}

	/**
	 * Writes the opening timestamp of a pass, before the backend records it. The label is not read
	 * yet: suppliers concatenate, and a pass that overflows the slot never asks for its name.
	 */
	public static void open(CommandEncoder encoder, Supplier<String> label) {
		if (censusArmed) {
			censusOpenLabel = label;
		}

		if (!ENABLED) {
			return;
		}

		Frame frame = current;
		if (frame == null) {
			frame = start();
		}

		if (frame.open) {
			// A pass inside a pass, which the encoder refuses before this is reached.
			return;
		}

		if (frame.count == PASSES_PER_FRAME) {
			frame.overflow = true;
			return;
		}

		encoder.writeTimestamp(pool, frame.slot * QUERIES_PER_FRAME + frame.count * 2);
		frame.openLabel = label;
		frame.open = true;
	}

	/** Writes the closing timestamp of the pass {@link #open} began, after the backend submitted it. */
	public static void close(CommandEncoder encoder) {
		if (censusArmed && censusOpenLabel != null) {
			String name = censusOpenLabel.get();
			censusLabels.merge(name, 1, Integer::sum);
			censusPasses++;
			censusOpenLabel = null;
		}

		if (!ENABLED) {
			return;
		}

		Frame frame = current;
		if (frame == null || !frame.open) {
			return;
		}

		encoder.writeTimestamp(pool, frame.slot * QUERIES_PER_FRAME + frame.count * 2 + 1);
		frame.labels[frame.count] = frame.openLabel.get();
		frame.count++;
		frame.open = false;
		frame.openLabel = null;
	}

	/**
	 * Closes the frame at the end of the game's own, queues it for reading, reads every older frame
	 * the card has answered, and prints the report when the interval is up.
	 */
	public static void endFrame() {
		if (censusComplete && !censusPrinted) {
			printCensus();
			censusPrinted = true;
		}

		censusArmed = false;
		censusComplete = false;

		if (!ENABLED) {
			return;
		}

		long now = System.nanoTime();
		if (lastReport == 0) {
			lastReport = now;
		}

		frameEnds++;
		Frame frame = current;
		if (frame != null) {
			current = null;
			pending.addLast(frame);
		}

		resolve();
		if (now - lastReport >= REPORT_SECONDS * NANOS_PER_SECOND) {
			report(now);
		}
	}

	/**
	 * One line of totals, then the labels that made them, most frequent first. The names are the
	 * ones the game already puts on a pass, so a mip reduction, a composite and a chunk pass read
	 * as three different rows rather than one pile.
	 */
	private static void printCensus() {
		Vitrail.logger().info("This pack's first full frame opened {} render passes, cleared {} "
				+ "textures and copied {}", censusPasses, censusClears, censusCopies);

		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(censusLabels.entrySet());
		sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		int shown = 0;
		int restPasses = 0;
		int restLabels = 0;
		for (Map.Entry<String, Integer> entry : sorted) {
			if (shown < ROWS) {
				Vitrail.logger().info("  x{}  {}", entry.getValue(), entry.getKey());
				shown++;
			} else {
				restPasses += entry.getValue();
				restLabels++;
			}
		}

		if (restLabels > 0) {
			Vitrail.logger().info("  x{}  {} other labels", restPasses, restLabels);
		}
	}

	private static Frame start() {
		if (pool == null) {
			GpuDevice device = RenderSystem.getDevice();
			pool = device.createTimestampQueryPool(SLOTS * QUERIES_PER_FRAME);
			nanosPerTick = device.getDeviceInfo().timestampPeriod();
		}

		int slot = (int) (frameNumber % SLOTS);
		frameNumber++;
		// The frame that last used this slot is SLOTS frames old. Still unread, its queries are
		// about to be reset under it, so it goes now rather than being read over the new writes.
		Frame oldest = pending.peekFirst();
		if (oldest != null && oldest.slot == slot) {
			pending.pollFirst();
			framesDropped++;
		}

		Frame frame = new Frame(slot);
		current = frame;
		return frame;
	}

	/** Reads pending frames oldest first and stops at the first the card has not answered whole. */
	private static void resolve() {
		while (!pending.isEmpty()) {
			Frame frame = pending.peekFirst();
			if (frame.count > 0) {
				OptionalLong[] values = pool.getValues(frame.slot * QUERIES_PER_FRAME, frame.count * 2);
				for (OptionalLong value : values) {
					if (value.isEmpty()) {
						return;
					}
				}

				long first = Long.MAX_VALUE;
				long last = Long.MIN_VALUE;
				for (int i = 0; i < frame.count; i++) {
					long begin = values[i * 2].getAsLong();
					long end = values[i * 2 + 1].getAsLong();
					long ticks = end - begin;
					Row row = rows.computeIfAbsent(frame.labels[i], label -> new Row());
					row.ticks += ticks;
					row.count++;
					passTicks += ticks;
					first = Math.min(first, begin);
					last = Math.max(last, end);
				}

				spanTicks += last - first;
			}

			if (frame.overflow) {
				framesOverflowed++;
			}

			framesSummed++;
			pending.pollFirst();
		}
	}

	private static void report(long now) {
		double seconds = (now - lastReport) / (double) NANOS_PER_SECOND;
		if (framesSummed == 0) {
			Vitrail.logger().info("Pass timings over {} s: no frame answered yet",
					String.format(Locale.ROOT, "%.1f", seconds));
		} else {
			double frames = framesSummed;
			double betweenFrames = frameEnds == 0 ? 0 : seconds * 1000 / frameEnds;
			Vitrail.logger().info("Pass timings over {} s, {} frames: {} ms of passes in a {} ms span, "
					+ "{} ms between frames", String.format(Locale.ROOT, "%.1f", seconds), framesSummed,
					millis(passTicks, frames), millis(spanTicks, frames),
					String.format(Locale.ROOT, "%.2f", betweenFrames));

			List<Map.Entry<String, Row>> sorted = new ArrayList<>(rows.entrySet());
			sorted.sort((a, b) -> Long.compare(b.getValue().ticks, a.getValue().ticks));
			double total = Math.max(1, passTicks);
			int shown = 0;
			long restTicks = 0;
			int restLabels = 0;
			for (Map.Entry<String, Row> entry : sorted) {
				Row row = entry.getValue();
				if (shown < ROWS) {
					Vitrail.logger().info("  {} ms {}%  x{}  {}", millis(row.ticks, frames),
							percent(row.ticks, total), perFrame(row.count, frames), entry.getKey());
					shown++;
				} else {
					restTicks += row.ticks;
					restLabels++;
				}
			}

			if (restLabels > 0) {
				Vitrail.logger().info("  {} ms {}%  {} other labels", millis(restTicks, frames),
						percent(restTicks, total), restLabels);
			}

			if (framesDropped > 0 || framesOverflowed > 0) {
				Vitrail.logger().info("  {} frames dropped unanswered, {} overflowed {} passes",
						framesDropped, framesOverflowed, PASSES_PER_FRAME);
			}
		}

		rows.clear();
		framesSummed = 0;
		framesDropped = 0;
		framesOverflowed = 0;
		passTicks = 0;
		spanTicks = 0;
		frameEnds = 0;
		lastReport = now;
	}

	private static String millis(long ticks, double frames) {
		return String.format(Locale.ROOT, "%7.3f", ticks * (double) nanosPerTick / 1_000_000.0 / frames);
	}

	private static String percent(long ticks, double total) {
		return String.format(Locale.ROOT, "%5.1f", 100.0 * ticks / total);
	}

	private static String perFrame(long count, double frames) {
		return String.format(Locale.ROOT, "%-5.1f", count / frames);
	}

	/** One frame's passes: the slot its queries live in, and a label per pass once closed. */
	private static final class Frame {

		final int slot;
		final String[] labels = new String[PASSES_PER_FRAME];
		int count;
		boolean open;
		boolean overflow;
		Supplier<String> openLabel;

		Frame(int slot) {
			this.slot = slot;
		}
	}

	private static final class Row {

		long ticks;
		long count;
	}
}
