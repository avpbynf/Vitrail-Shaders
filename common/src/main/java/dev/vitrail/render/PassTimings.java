package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * A count of the first full frame a pack draws is always on: how many render passes opened, how
 * many textures were cleared, how many were copied, and how many times the queue was submitted.
 * The last of those is the number a capture tool reports, counted here where the backend makes the
 * call, and the whole census costs an integer add apiece. It can be asked for every N seconds
 * instead of once, which is the only way to see a frame of play rather than a frame of setup.
 * The timed report is a different switch, and stays off unless the JVM is started with
 * {@code -Dvitrail.passTimings=N}, N being the seconds between two reports.
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
	 * Census of the render passes, clears and copies one frame costs. Cheap integers, no GPU
	 * queries: the pass-timings flag still owns the clock. Armed at the frame's open, printed at
	 * its close, and only once the chain is warm, so a frame still compiling one program a time is
	 * not the one counted.
	 * <p>
	 * One shot by default, on the first full frame, which is the one a pack load can be compared
	 * against. That frame is NOT a frame of play: it allocates every target and empties each one,
	 * so its clear count is the setup rather than the running cost, and reading it as the steady
	 * state is how a budget gets attacked at the wrong end. Asking for one every N seconds instead
	 * gives the count that says what a frame really pays.
	 * <p>
	 * Two ways to ask, and the second is the one that matters: {@code -Dvitrail.passCensus=N} on
	 * the command line, or a file {@code vitrail/pass-census} in the instance holding N, empty
	 * meaning five. A JVM flag lives in the launcher, which is a place a session cannot reach; a
	 * file beside the pack is a place it can, so a measurement can be armed, changed and disarmed
	 * without anybody opening the launcher. The compute probe was armed the same way.
	 * <p>
	 * Read again at every pack load, so the interval can be changed without leaving the game.
	 */
	private static final int CENSUS_PROPERTY = Integer.getInteger("vitrail.passCensus", 0);

	private static final boolean KEEP_PROPERTY = Boolean.getBoolean("vitrail.keepRedoneWork");

	private static final String KEEP_ARM_FILE = "keep-redone-work";

	/** Negative until the property and the file have been read, which needs the game directory. */
	private static int keepRedone = -1;

	private static final boolean FIRST_DRAW_PROPERTY =
			Boolean.getBoolean("vitrail.keepFirstDrawCompiles");

	private static final String FIRST_DRAW_ARM_FILE = "keep-first-draw-compiles";

	/** Negative until the property and the file have been read, which needs the game directory. */
	private static int keepFirstDraw = -1;

	/** What an arming file with nothing in it asks for. */
	private static final int ARMED_BY_FILE_SECONDS = 5;

	private static final String CENSUS_ARM_FILE = "pass-census";

	/** Negative until the property and the file have been read, which needs the game directory. */
	private static int censusSeconds = -1;

	/** When the last census was printed, so the repeating one waits its interval out. */
	private static long lastCensus;

	private static boolean censusPrinted;
	private static boolean censusArmed;
	private static boolean censusComplete;
	private static int censusPasses;
	private static int censusClears;
	private static int censusCopies;

	/**
	 * Calls to {@code vkQueueSubmit2KHR}, which is the number a capture tool reports and the one
	 * issue 161 is about. Counted rather than inferred: a pass, a clear and a copy each tend to
	 * cost one, but the backend decides that and not this class, so the totals beside it are what
	 * says which of them the count is made of.
	 */
	private static int censusSubmits;
	private static Supplier<String> censusOpenLabel;
	private static final Map<String, Integer> censusLabels = new HashMap<>();

	/**
	 * Per pass label, why the geometry family behind it had to open a pass rather than join the one
	 * already recording, and how often each of those reasons came up.
	 * <p>
	 * The counts above say a family opened nine passes; without this, that nine can only be guessed
	 * at, and a guess about a number is how a frame gets attacked at the wrong end. Only the passes
	 * {@link GeometryHold} arbitrates appear here, the full-screen passes of a pack included since
	 * they may join the one before them, and a join is counted beside the refusals so that the
	 * census can say it happened; a mip level is not a reopening, it is a pass of its own.
	 */
	private static final Map<String, Map<String, Integer>> censusReopens = new HashMap<>();

	/**
	 * Work a frame does again that its answer did not change since: uniform slices rebuilt at a bind
	 * whose two arguments are fixed for the pass, terrain programs looked for by walking a map at
	 * every draw, and far-terrain sections read back out of Distant Horizons by reflection.
	 * <p>
	 * Counted rather than timed, and that is deliberate: these are CPU, so a clock on them measures
	 * the machine's mood as much as the change, where a count is the same number twice. What a count
	 * cannot say is whether removing them is worth anything, which is what the frame rate beside it
	 * is for.
	 */
	private static int censusSlices;

	private static int censusProgramWalks;
	private static int censusFarSections;

	/**
	 * Frames since the last census, and when that was, which is what turns the census into a reading
	 * of the frame rate as well as of the frame.
	 * <p>
	 * Counted here rather than read off an overlay or asked of a JVM flag, and that is the whole
	 * point: the two things this class measures are armed by a file beside the pack, so a session
	 * with no way into the launcher and no way to read the screen can still say whether a change
	 * bought anything. Every frame pays one increment for it, armed or not.
	 */
	private static long censusFrames;

	private static long censusFramesAt;

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

		// The repeating census waits its interval out. Without this it would count and print every
		// frame, which is a line a second at best and a log nobody can read at worst.
		if (lastCensus != 0L
				&& System.nanoTime() - lastCensus < censusSeconds() * NANOS_PER_SECOND) {
			return;
		}

		censusArmed = true;
		censusComplete = false;
		censusPasses = 0;
		censusClears = 0;
		censusCopies = 0;
		censusSlices = 0;
		censusProgramWalks = 0;
		censusFarSections = 0;
		censusSubmits = 0;
		censusOpenLabel = null;
		censusLabels.clear();
		censusReopens.clear();
	}

	/** Whether a census is counting this frame, asked before anything is spent naming a cause. */
	public static boolean censusArmed() {
		return censusArmed;
	}

	/**
	 * One geometry pass that could not join the one before it, and what stopped it.
	 *
	 * @param label the pass about to open, the same name its row above is counted under
	 * @param cause already in words: the caller has checked {@link #censusArmed} before building it
	 */
	public static void censusReopen(Supplier<String> label, String cause) {
		if (!censusArmed) {
			return;
		}

		String name = label == null ? "an unnamed pass" : label.get();
		censusReopens.computeIfAbsent(name, key -> new HashMap<>()).merge(cause, 1, Integer::sum);
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
		censusSlices = 0;
		censusProgramWalks = 0;
		censusFarSections = 0;
		censusSubmits = 0;
		lastCensus = 0L;
		// Read again, so an arming file written or changed while the game runs is picked up by the
		// next pack load rather than by the next launch.
		censusSeconds = -1;
		keepRedone = -1;
		keepFirstDraw = -1;
		censusOpenLabel = null;
		censusLabels.clear();
		censusReopens.clear();
	}

	/**
	 * Seconds between two censuses, or nought for the single one on the first full frame. The
	 * command line wins over the file, so a flag can override an arming file somebody left behind.
	 */
	private static int censusSeconds() {
		if (censusSeconds >= 0) {
			return censusSeconds;
		}

		censusSeconds = CENSUS_PROPERTY > 0 ? CENSUS_PROPERTY : armedByFile();

		return censusSeconds;
	}

	/**
	 * Puts back the two things every draw used to redo, the uniform slice and the walk for the
	 * terrain program, so that before and after are read off ONE jar rather than two.
	 * <p>
	 * Two builds compared against each other measure the two builds as much as the change: a
	 * different compile, a different pack read, a different moment of the day. One jar with a switch
	 * measures the change, and nothing else moves between the two readings.
	 * <p>
	 * Armed the way the census is, by {@code vitrail/keep-redone-work} beside the pack, and for the
	 * same reason: a JVM flag lives in the launcher, and a session measuring this cannot open the
	 * launcher. The command line still wins where somebody has one. Off otherwise, so a player never
	 * carries the old path.
	 * <p>
	 * The far terrain is not under it: what changed there is allocation inside a walk that runs twice
	 * a frame either way, so there is no second path to switch to, only a count to read.
	 * <p>
	 * Settled once per pack load, like the census interval, so the file can be written or removed
	 * without leaving the game.
	 */
	public static boolean keepRedoneWork() {
		if (keepRedone < 0) {
			keepRedone = KEEP_PROPERTY || armFile(KEEP_ARM_FILE) != null ? 1 : 0;
		}

		return keepRedone == 1;
	}

	/**
	 * Puts back the render-thread first-draw compiles of the six on-demand families, so that the
	 * hitch and its absence are read off ONE jar, for the reason {@link #keepRedoneWork} gives.
	 * Armed by {@code vitrail/keep-first-draw-compiles} beside the pack or by
	 * {@code -Dvitrail.keepFirstDrawCompiles}, read again at every pack load, and off otherwise,
	 * so a player never carries the old path.
	 */
	public static boolean keepFirstDrawCompiles() {
		if (keepFirstDraw < 0) {
			keepFirstDraw = FIRST_DRAW_PROPERTY || armFile(FIRST_DRAW_ARM_FILE) != null ? 1 : 0;
		}

		return keepFirstDraw == 1;
	}

	/** The arming file of that name beside the pack, or null where there is none to read. */
	private static Path armFile(String name) {
		try {
			Path file = Vitrail.platform().gameDirectory().resolve("vitrail").resolve(name);

			return Files.isRegularFile(file) ? file : null;
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	/**
	 * What {@code vitrail/pass-census} asks for, or nought when it is not there.
	 * <p>
	 * A file that IS there and cannot be read as a number still arms, at the default interval: it
	 * was put there on purpose, and answering a typo with silence is how a measurement gets waited
	 * for and never comes.
	 */
	private static int armedByFile() {
		Path file = armFile(CENSUS_ARM_FILE);
		if (file == null) {
			return 0;
		}

		try {
			String asked = Files.readString(file).trim();
			if (asked.isEmpty()) {
				return ARMED_BY_FILE_SECONDS;
			}

			int seconds = Integer.parseInt(asked);

			return seconds > 0 ? seconds : ARMED_BY_FILE_SECONDS;
		} catch (IOException | RuntimeException ignored) {
			return ARMED_BY_FILE_SECONDS;
		}
	}

	/**
	 * One call to {@code vkQueueSubmit2KHR}, counted where the backend really makes it rather than
	 * guessed from what was recorded into it.
	 */
	public static void censusSubmit() {
		if (censusArmed) {
			censusSubmits++;
		}
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

	/** One uniform slice built at a bind, whose offset and length the pass had already settled. */
	public static void censusSlice() {
		if (censusArmed) {
			censusSlices++;
		}
	}

	/** One program looked for by walking the terrain map, which a draw does before it can bind. */
	public static void censusProgramWalk() {
		if (censusArmed) {
			censusProgramWalks++;
		}
	}

	/** Far-terrain sections read back out of Distant Horizons, which happens twice a frame. */
	public static void censusFarSections(int built) {
		if (censusArmed) {
			censusFarSections += built;
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
		censusFrames++;
		if (censusFramesAt == 0L) {
			censusFramesAt = System.nanoTime();
		}

		if (censusComplete && !censusPrinted) {
			printCensus();
			lastCensus = System.nanoTime();
			// One shot unless a flag asked for more, in which case the next arm is what the
			// interval above gates rather than this.
			censusPrinted = censusSeconds() <= 0;
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
		Vitrail.logger().info("{} opened {} render passes, cleared {} textures and copied {}, for {} "
						+ "queue submits",
				censusSeconds() > 0 ? "A frame of this pack" : "This pack's first full frame",
				censusPasses, censusClears, censusCopies, censusSubmits);
		Vitrail.logger().info("  and redid {} uniform slices, {} terrain program walks and {} far "
				+ "terrain sections", censusSlices, censusProgramWalks, censusFarSections);
		printRate();

		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(censusLabels.entrySet());
		sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		int shown = 0;
		int restPasses = 0;
		int restLabels = 0;
		for (Map.Entry<String, Integer> entry : sorted) {
			if (shown < ROWS) {
				Vitrail.logger().info("  x{}  {}", entry.getValue(), entry.getKey());
				printReopens(entry.getKey());
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

	/**
	 * Frames a second since the last census, and the milliseconds one frame took on average.
	 * <p>
	 * The window is the census interval, so it is the whole of it and not a moment inside it, which
	 * is what a screen overlay gives. Said whether or not it is flattering: a change that costs
	 * frames has to be as easy to read here as one that buys them.
	 * <p>
	 * The FIRST census of a session is not a reading of play. Its window reaches back to the world
	 * being built, so it is named as what it is rather than dressed up as a rate.
	 */
	private static void printRate() {
		long now = System.nanoTime();
		double seconds = (now - censusFramesAt) / (double) NANOS_PER_SECOND;
		if (censusFrames > 0 && seconds > 0) {
			Vitrail.logger().info("  {} frames a second over the last {} s, {} ms a frame",
					String.format(Locale.ROOT, "%.1f", censusFrames / seconds),
					String.format(Locale.ROOT, "%.1f", seconds),
					String.format(Locale.ROOT, "%.2f", seconds * 1000 / censusFrames));
		}

		censusFrames = 0;
		censusFramesAt = now;
	}

	/**
	 * Under one label, why each of its passes had to be opened instead of joining the one before it.
	 * <p>
	 * Silent for a label no geometry hold arbitrates, which is most of them: a composite opens
	 * because it is a composite, and printing a reason for it would be a line saying nothing.
	 */
	private static void printReopens(String label) {
		Map<String, Integer> causes = censusReopens.get(label);
		if (causes == null) {
			return;
		}

		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(causes.entrySet());
		sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		for (Map.Entry<String, Integer> cause : sorted) {
			Vitrail.logger().info("        x{}  because {}", cause.getValue(), cause.getKey());
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
