package dev.vitrail.render;

import dev.vitrail.Vitrail;

import org.jspecify.annotations.Nullable;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Keeps what shaderc turned each shader unit into, on disk, so a second load of the same pack does
 * not pay for the same compile twice.
 * <p>
 * Loading a pack costs seconds of compilation, and one of the steps behind that number is a pure
 * function of its input: the GLSL text becoming SPIR-V inside the game's {@code GlslCompiler}. So
 * that step is cached by its input. What the driver then does with the result is its own affair and
 * none of this class's.
 * <p>
 * <strong>The key IS the input, hashed</strong>, and nothing else: the exact text handed to the
 * compiler, the stage it is compiled for, the debug name the module carries, and everything that
 * decides what that text turns into, which is the mod's version, the game's, the loader with its
 * own version, and the LWJGL build whose bundled shaderc does the compiling. Nothing is keyed on a
 * pack name or a file path, so there is no invalidation to get wrong and none is written. An edited
 * shader, a moved pack setting, a translator that emits one word differently, a loader that patches
 * the compiler: each is a different key, and the blob under the old one is never asked for again.
 * <p>
 * <strong>What is stored is what shaderc produced, before anything has read it.</strong> Reflection
 * rewrites the output locations of the module it walks, so the buffer a caller ends up holding is
 * not the buffer shaderc returned. A hit hands back the untouched bytes and lets that same rewrite
 * happen again, which is what makes a served unit identical to a compiled one rather than merely
 * equivalent to it.
 * <p>
 * A wrong blob is worse than a slow load: it is a picture that is wrong, or a lost device, with
 * nothing on screen pointing back here. A length and the SPIR-V magic word are not enough on their
 * own: a truncation on a four byte boundary keeps the magic word and a length both checks accept,
 * and what it then reaches is a native parser that no Java catch stands in front of. So every file
 * carries a digest of its own bytes behind them, and a blob its digest does not answer for is never
 * handed on. Absent, truncated, corrupt, unreadable, or refused by the reflection that follows:
 * every one of them is a MISS rather than an error, and ends in the compiler running exactly as it
 * did before this class existed.
 * <p>
 * A write lands through a neighbouring file and a move, so a process killed halfway through cannot
 * leave half a module under a name that says it is whole.
 * <p>
 * <strong>The disk is bounded</strong>, at half a gigabyte, and bounded per edition: the files sit
 * under a directory named for the mod and game versions, and any directory named for another
 * edition is deleted when this one opens. Without that an update would fill a fresh set of keys on
 * top of the set it had just made unreachable, and two packs plus one update would go over the
 * ceiling with nothing in the way. Past the ceiling the units nothing has asked for lately go
 * first, down to three quarters of it so that the sweep is not paid again at the very next write.
 * <p>
 * {@code -Dvitrail.spirvCache=false} turns the whole thing off, and the line is still printed, so
 * one jar answers the question in both directions.
 */
public final class SpirvCache {

	/** Off by property rather than by rebuild, so a before and an after come out of one jar. */
	private static final boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("vitrail.spirvCache", "true"));

	/** First word of any SPIR-V module, and the cheapest proof that a file is one. */
	private static final int MAGIC = 0x07230203;

	/** Five words is the header alone, so nothing shorter can be a module. */
	private static final int SHORTEST = 20;

	/** SHA-256, sitting behind the module in every file and answering for it. */
	private static final int DIGEST_BYTES = 32;

	private static final long CEILING_BYTES = 512L * 1024L * 1024L;
	private static final long SWEEP_TARGET = CEILING_BYTES / 4L * 3L;

	/** How long a sweep that could not finish stays out of the way of the next write. */
	private static final long SWEEP_BACKOFF_NANOS = 60_000_000_000L;

	/**
	 * Bumped by hand when the layout of a file changes rather than its content. Every blob written
	 * under an older one is then unreachable, and the sweep is what eventually collects it.
	 */
	private static final String FORMAT = "vitrail-spirv-2";

	private static final String FOLDER = "spirv";
	private static final String SUFFIX = ".spv";
	private static final String PART_SUFFIX = ".part";

	/** How long the compiler has to stay quiet before a load is taken to be over. */
	private static final long QUIET_NANOS = 2_000_000_000L;

	/**
	 * The key of the unit this thread is compiling, put there by {@link #lookup} and read by
	 * {@link #store}.
	 * <p>
	 * A field on the thread rather than a value handed along, because the two ends are two separate
	 * injection handlers on one method of the game's compiler and neither can pass the other
	 * anything. It is written before it is ever read within a call, so a stale one left behind by a
	 * compile that threw is overwritten rather than used.
	 */
	private static final ThreadLocal<String> KEY = new ThreadLocal<>();

	private static final AtomicLong SERVED = new AtomicLong();
	private static final AtomicLong COMPILED = new AtomicLong();
	private static final AtomicLong SERVED_SINCE_LAUNCH = new AtomicLong();
	private static final AtomicLong COMPILED_SINCE_LAUNCH = new AtomicLong();
	private static final AtomicLong BYTES = new AtomicLong();

	/** Held for the first look at the directory and for every sweep, which are the two scans. */
	private static final Object LOCK = new Object();

	private static volatile @Nullable Path directory;
	private static volatile boolean unavailable;
	private static volatile long lastUnitNanos;
	private static volatile long nextSweepNanos;
	private static volatile boolean saidAboutReading;
	private static volatile boolean saidAboutWriting;

	private SpirvCache() {
	}

	/**
	 * What shaderc would have produced for this unit, or null when it has to be compiled.
	 * <p>
	 * The key is recorded for this thread either way, because a caller that gets no buffer is a
	 * caller about to compile one and hand it back through {@link #store}.
	 *
	 * @param filename the debug name the module carries, which the compiler writes into the module
	 *                 it emits and which therefore belongs in the key
	 * @param source   the text the compiler was handed, the pipeline's defines already injected
	 * @param stage    vertex or fragment, which decides the whole compile
	 * @return a buffer the caller owns, allocated the way the game allocates its own so that the
	 *         module built from it is freed the same way, or null on any kind of miss
	 */
	public static @Nullable ByteBuffer lookup(String filename, String source, String stage) {
		KEY.remove();
		Path root = directory();
		if (root == null) {
			return null;
		}

		String key = key(filename, source, stage);
		KEY.set(key);

		Path file = root.resolve(key + SUFFIX);
		byte[] raw;
		try {
			raw = Files.readAllBytes(file);
		} catch (IOException e) {
			// Absent is the ordinary case and unreadable the rare one, and neither is worth a word:
			// what follows either way is the compile that would have happened anyway.
			return null;
		}

		int length = raw.length - DIGEST_BYTES;
		if (length < SHORTEST || length % 4 != 0
				|| ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder()).getInt(0) != MAGIC
				|| !answersForItself(raw, length)) {
			sayAboutReading("a stored unit was not the SPIR-V module its name claims");

			return null;
		}

		touch(file);

		ByteBuffer buffer = MemoryUtil.memAlloc(length);
		buffer.put(raw, 0, length);
		buffer.flip();

		return buffer;
	}

	/**
	 * Whether the digest a file carries answers for the module in front of it.
	 * <p>
	 * The magic word and the length are cheap and they are not enough: a file cut on a four byte
	 * boundary keeps both, and what a cut module reaches next is a native parser with nothing
	 * between it and the process. This is the check that makes the class's promise true.
	 */
	private static boolean answersForItself(byte[] raw, int length) {
		MessageDigest digest = sha256();
		digest.update(raw, 0, length);

		return Arrays.equals(digest.digest(), 0, DIGEST_BYTES, raw, length, raw.length);
	}

	/** Counts a unit that came off the disk and was accepted by the reflection that reads it. */
	public static void served() {
		SERVED.incrementAndGet();
		SERVED_SINCE_LAUNCH.incrementAndGet();
		lastUnitNanos = System.nanoTime();
	}

	/**
	 * Gives up on a buffer that passed the checks above and was refused anyway, and frees it.
	 * <p>
	 * The blob is left where it is rather than deleted: the compile that follows writes over it
	 * under the same key, which repairs it without a second gesture.
	 */
	public static void rejected(ByteBuffer buffer, String why) {
		MemoryUtil.memFree(buffer);
		sayAboutReading("a stored unit was refused (" + why + ")");
	}

	/**
	 * The bytes shaderc has just produced, taken before anything rewrites them, or null when there
	 * is nowhere to write them to.
	 * <p>
	 * Through a view of its own, so the caller's position and limit are left where they were.
	 */
	public static byte @Nullable [] copyOf(ByteBuffer spirv) {
		if (directory() == null || KEY.get() == null) {
			return null;
		}

		ByteBuffer view = spirv.duplicate();
		byte[] raw = new byte[view.remaining()];
		view.get(raw);

		return raw;
	}

	/** Counts a unit shaderc had to compile, and keeps it for the next load when there is one. */
	public static void store(byte @Nullable [] raw) {
		COMPILED.incrementAndGet();
		COMPILED_SINCE_LAUNCH.incrementAndGet();
		lastUnitNanos = System.nanoTime();

		Path root = directory();
		String key = KEY.get();
		if (raw == null || root == null || key == null) {
			return;
		}

		Path file = root.resolve(key + SUFFIX);
		Path part = root.resolve(key + "-"
				+ Long.toHexString(Thread.currentThread().threadId()) + PART_SUFFIX);

		try {
			// The module, then the digest that answers for it. Behind rather than in front, so the
			// offset of the first word does not move and the read stays one allocation.
			Files.write(part, raw);
			Files.write(part, sha256(raw), StandardOpenOption.APPEND);
			move(part, file);
			BYTES.addAndGet(raw.length + (long) DIGEST_BYTES);
		} catch (IOException e) {
			sayAboutWriting("a unit could not be stored", e);

			try {
				Files.deleteIfExists(part);
			} catch (IOException ignored) {
				// The next sweep collects it: every scan deletes the neighbours it comes across.
			}

			return;
		}

		if (BYTES.get() > CEILING_BYTES) {
			sweep(root);
		}
	}

	/**
	 * One line for the load that has just finished, in both directions and whatever happened.
	 * <p>
	 * Called at every client tick and silent at almost all of them: it speaks once the compiler has
	 * been quiet long enough for a load to be over, which is what makes the line a load's total
	 * rather than a running commentary. A load whose leftovers straggle in after a longer pause than
	 * that comes out as two lines, and the totals since launch are on the line so that the two can
	 * still be added up without ambiguity.
	 * <p>
	 * <strong>It says the misses as loudly as the hits.</strong> A cache that only speaks when it
	 * helps makes every later reading of a log ambiguous, and a silence that could mean either
	 * nothing happened or everything did is worse than no line at all.
	 */
	public static void say() {
		if (SERVED.get() == 0L && COMPILED.get() == 0L) {
			return;
		}

		if (System.nanoTime() - lastUnitNanos < QUIET_NANOS) {
			return;
		}

		long hits = SERVED.getAndSet(0L);
		long misses = COMPILED.getAndSet(0L);

		if (!ENABLED) {
			Vitrail.logger().info("SPIR-V cache off, so shaderc compiled all {} units of this load "
					+ "({} since this launch)", misses, COMPILED_SINCE_LAUNCH.get());

			return;
		}

		Path root = directory;
		Vitrail.logger().info("SPIR-V cache: {} units served, {} compiled by shaderc, {} and {} "
						+ "since this launch, {} MB in {}",
				hits, misses, SERVED_SINCE_LAUNCH.get(), COMPILED_SINCE_LAUNCH.get(),
				megabytes(BYTES.get()), root == null ? "nowhere" : root);
	}

	/** The directory, made and measured at the first unit of the run, or null when there is none. */
	private static @Nullable Path directory() {
		if (!ENABLED || unavailable) {
			return null;
		}

		Path known = directory;
		if (known != null) {
			return known;
		}

		synchronized (LOCK) {
			if (directory == null && !unavailable) {
				open();
			}

			return directory;
		}
	}

	/**
	 * Makes the edition's directory, clears out every other edition's, and measures what is left.
	 * <p>
	 * The only moment at which a leftover neighbour can be swept up: nothing else has been handed
	 * the directory yet, because {@link #directory} is set on the last line, so a {@code .part} seen
	 * here is a dead one from a run that was killed and never a live write of a worker's.
	 */
	private static void open() {
		try {
			Path root = Vitrail.platform().gameDirectory().resolve(Vitrail.MOD_ID).resolve(FOLDER);
			Path mine = root.resolve(edition());
			Files.createDirectories(mine);
			dropOtherEditions(root, mine);
			BYTES.set(total(scan(mine, true)));
			directory = mine;
		} catch (IOException | RuntimeException e) {
			unavailable = true;
			Vitrail.logger().warn("No SPIR-V cache this run, so every shader is compiled: {}",
					e.toString());
		}
	}

	/** What names a whole set of keys at once, and therefore what names their directory. */
	private static String edition() {
		return plain(Vitrail.platform().modVersion()) + "+mc"
				+ plain(Vitrail.platform().minecraftVersion());
	}

	private static String plain(String text) {
		return text.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	/**
	 * Deletes what an earlier or later edition left, because not one of its keys can ever be asked
	 * for again and the ceiling has to be about the blobs that are still reachable.
	 */
	private static void dropOtherEditions(Path root, Path mine) throws IOException {
		try (Stream<Path> entries = Files.list(root)) {
			for (Path entry : entries.toList()) {
				if (!entry.equals(mine)) {
					dropTree(entry);
				}
			}
		}
	}

	private static void dropTree(Path entry) throws IOException {
		try (Stream<Path> tree = Files.walk(entry)) {
			for (Path found : tree.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(found);
			}
		}
	}

	/**
	 * The key of a unit: everything that decides what shaderc emits, and nothing that does not.
	 * <p>
	 * Each piece goes in behind its own length, so that two different splits of the same characters
	 * cannot hash alike. <strong>The four versions are the half that is easy to leave out and
	 * expensive to leave out</strong>: the text alone names none of them, and a translator that
	 * emits differently, a game that compiles differently, a loader that patches the compiler, or an
	 * LWJGL bump that brings a new shaderc with it are each a different answer to a question whose
	 * text has not moved. Serving the old blob for one of those is exactly the failure this class
	 * has to be unable to have, and none of the four announces itself any other way.
	 */
	private static String key(String filename, String source, String stage) {
		MessageDigest digest = sha256();

		feed(digest, FORMAT);
		feed(digest, Vitrail.platform().modVersion());
		feed(digest, Vitrail.platform().minecraftVersion());
		feed(digest, Vitrail.platform().loaderName());
		feed(digest, Vitrail.platform().loaderVersion());
		feed(digest, Version.getVersion());
		feed(digest, stage);
		feed(digest, filename);
		feed(digest, source);

		return HexFormat.of().formatHex(digest.digest());
	}

	private static byte[] sha256(byte[] raw) {
		return sha256().digest(raw);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
		}
	}

	private static void feed(MessageDigest digest, String text) {
		byte[] raw = text.getBytes(StandardCharsets.UTF_8);
		digest.update(new byte[] {
				(byte) (raw.length >>> 24), (byte) (raw.length >>> 16),
				(byte) (raw.length >>> 8), (byte) raw.length,
		});
		digest.update(raw);
	}

	/** The neighbour and then the move, which is what makes a half written file impossible. */
	private static void move(Path part, Path file) throws IOException {
		try {
			Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Marks a unit as asked for, so that the sweep drops what nothing loads rather than what was
	 * written longest ago. A stamp that cannot be set costs a worse choice later and nothing now.
	 */
	private static void touch(Path file) {
		try {
			Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
		} catch (IOException ignored) {
			// Deliberately silent, and deliberately not a miss: the blob itself is fine either way.
		}
	}

	/**
	 * Every unit on disk, oldest stamp first.
	 * <p>
	 * <strong>A neighbour is only ever deleted when {@code prunePartials} says so, which is at
	 * {@link #open} and nowhere else.</strong> A sweep runs while other workers are in the middle of
	 * their own writes, and deleting what they are holding open takes their unit down on one system
	 * and aborts the whole sweep with a refusal on the other. What a sweep does with a neighbour is
	 * ignore it: it is not reachable, it is about to become a unit, and it is nobody's to count.
	 * <p>
	 * A file that goes missing between the listing and the question is skipped rather than fatal,
	 * for the same reason: the listing is a snapshot and the directory is not frozen behind it.
	 */
	private static List<Unit> scan(Path root, boolean prunePartials) throws IOException {
		List<Unit> units = new ArrayList<>();

		try (Stream<Path> entries = Files.list(root)) {
			for (Path entry : entries.toList()) {
				if (entry.getFileName().toString().endsWith(PART_SUFFIX)) {
					if (prunePartials) {
						Files.deleteIfExists(entry);
					}
				} else {
					try {
						units.add(new Unit(entry, Files.getLastModifiedTime(entry).toMillis(),
								Files.size(entry)));
					} catch (IOException ignored) {
						// Gone, or momentarily unreadable. One unit uncounted, and the next sweep
						// counts it.
					}
				}
			}
		}

		units.sort(Comparator.comparingLong(Unit::stamp));

		return units;
	}

	private static long total(List<Unit> units) {
		long sum = 0L;
		for (Unit unit : units) {
			sum += unit.size();
		}

		return sum;
	}

	/**
	 * Brings the directory back under the ceiling, oldest stamp first.
	 * <p>
	 * It rescans rather than trusting the running count, which is also what puts that count right
	 * again: a unit written twice under one key is added twice and subtracted once, so the two only
	 * come back together on the other side of a sweep.
	 * <p>
	 * <strong>What it leaves behind on the way out matters more than what it frees.</strong> The
	 * count is put down in a {@code finally} and a sweep that ends still over the ceiling stands
	 * back for a while, because the caller's test is that same count: a single refusal anywhere in
	 * here, without both of those, leaves the count high and turns every later write into a full
	 * walk of the directory under this lock, for the rest of the session and with nothing said.
	 */
	private static void sweep(Path root) {
		synchronized (LOCK) {
			if (System.nanoTime() < nextSweepNanos) {
				return;
			}

			long total = BYTES.get();
			try {
				List<Unit> units = scan(root, false);
				total = total(units);
				long before = total;

				for (Unit unit : units) {
					if (total <= SWEEP_TARGET) {
						break;
					}

					Files.deleteIfExists(unit.path());
					total -= unit.size();
				}

				if (total < before) {
					Vitrail.logger().info("The SPIR-V cache went over its ceiling, so the units "
							+ "nothing has asked for lately were dropped, {} MB left",
							megabytes(total));
				}
			} catch (IOException e) {
				sayAboutWriting("the cache could not be swept", e);
			} finally {
				BYTES.set(total);

				if (total > CEILING_BYTES) {
					nextSweepNanos = System.nanoTime() + SWEEP_BACKOFF_NANOS;
				}
			}
		}
	}

	private static void sayAboutReading(String what) {
		if (!saidAboutReading) {
			saidAboutReading = true;
			Vitrail.logger().warn("In the SPIR-V cache, {}, so it was compiled instead. Said once a "
					+ "run: nothing about it stops a pack loading", what);
		}
	}

	private static void sayAboutWriting(String what, IOException cause) {
		if (!saidAboutWriting) {
			saidAboutWriting = true;
			Vitrail.logger().warn("In the SPIR-V cache, {}: {}. Said once a run: the next load pays "
					+ "for the compile again and nothing else changes", what, cause.toString());
		}
	}

	private static String megabytes(long amount) {
		return String.format(Locale.ROOT, "%.1f", amount / 1048576.0D);
	}

	/** One file of the cache, with what the sweep needs to order it and to subtract it. */
	private record Unit(Path path, long stamp, long size) {
	}
}
