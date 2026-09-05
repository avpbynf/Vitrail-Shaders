package dev.vitrail.glsl;

import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

/**
 * Keeps a translated program on disk, so that loading the same pack again does not translate the
 * same text into the same text.
 * <p>
 * It is the third and last of the posts a pack load is made of. A clock around the load named them:
 * the compile, the reflection that reads what the compile emitted, and this. With the first two
 * served from disk this is what a warm load still pays, and it is a pure function of its input like
 * the other two.
 * <p>
 * <strong>It lives here and not beside the renderer, and that is what shapes it.</strong>
 * {@code dev.vitrail.glsl} and {@code dev.vitrail.pack} name no Minecraft API, which is what lets
 * the whole translator be run over the pack corpus without starting the game. A cache written in
 * the render tree could not be reached from here without ending that, so this one takes its
 * directory from whoever installs it and knows nothing about a game directory. That is also what
 * makes it PROVABLE: the off-game harness translates the corpus, and translating it twice with this
 * in the way has to produce the same tree to the byte.
 * <p>
 * <strong>The key is every input the translation has</strong>, and the list is short because the
 * translator has no others: the expanded text of each stage with the lines the preprocessor left
 * live, the vertex format, the elements the pass binds, the alpha test, the coverage flag, the name
 * of the program, the volumes the pack ships, and the engine's own define table, which carries the
 * game version, the driver's vendor and renderer, the mipmap level and everything else the machine
 * decides. The table goes in whole rather than field by field, so a define added later is in the
 * key the day it is added rather than the day somebody remembers this class.
 * <p>
 * <strong>A wrong hit is a wrong picture and never a crash</strong>, which is why every file
 * carries a digest of its own bytes and a blob its digest does not answer for is never used. The
 * vertex format is written into the blob as well as into the key, and read back and compared, so a
 * key that has come apart from its blob is caught rather than believed.
 * <p>
 * Absent, unreadable, corrupt, larger than any translation is, or of a shape this build cannot
 * read: every one of them is a MISS, and a miss is the translation that would have happened anyway.
 * <p>
 * The store is bounded at a quarter of a gigabyte and bounded per edition, and it is off until
 * somebody installs it. {@code -Dvitrail.translationDir=<path>} installs it outside the game, which
 * is how the harness reaches it.
 * <p>
 * <strong>The edition is the version this build DECLARES</strong>, and that is all it can be.
 * Two builds carrying one version number share a folder and share every key, which is every
 * build made between two releases: a translator changed without the version moving is served its
 * predecessor's units, and nothing says so. What answers that in the workshop is deleting the
 * folder, or bumping {@code TranslatedProgramCodec.FORMAT} by hand, and neither is automatic.
 */
public final class TranslationCache {

	/** How the harness, or anything else without a game around it, turns this on. */
	private static final String DIRECTORY_PROPERTY = "vitrail.translationDir";

	private static final String FOLDER = "translations";
	private static final String SUFFIX = ".tr";
	private static final String PART_SUFFIX = ".part";

	/** SHA-256, sitting behind the blob in every file and answering for it. */
	private static final int DIGEST_BYTES = 32;

	/**
	 * How large one file may be before it is refused unread. A translated program comes to a
	 * quarter of a megabyte before it is squeezed, so this is two orders of magnitude of room; what
	 * it is really for is a file that grew for a reason nothing here can name, which would
	 * otherwise be read whole into the heap before anything got the chance to refuse it.
	 */
	private static final long MOST_BYTES = 64L * 1024L * 1024L;

	private static final long CEILING_BYTES = 256L * 1024L * 1024L;
	private static final long SWEEP_TARGET = CEILING_BYTES / 4L * 3L;

	private static final long SWEEP_BACKOFF_NANOS = 60_000_000_000L;

	private static final AtomicLong SERVED = new AtomicLong();
	private static final AtomicLong TRANSLATED = new AtomicLong();
	private static final AtomicLong BYTES = new AtomicLong();

	/** The one blob refused rather than served this run, waiting for whoever has a logger. */
	private static final AtomicReference<String> REFUSAL = new AtomicReference<>("");

	/** Held for the one scan at install and for every sweep. */
	private static final Object LOCK = new Object();

	/** Where the blobs live, or null while the cache is off, which is until somebody installs it. */
	private static volatile Path directory;
	private static volatile String problem = "";

	/** How long a sweep that could not finish stays out of the way of the next write. */
	private static volatile long nextSweepNanos;

	/** Raised by the first refusal of the run and never lowered, which is what makes it one a run. */
	private static volatile boolean refused;

	static {
		// Inside the try and not beside it: Path.of refuses a name Windows will not have, and an
		// exception out of a static initialiser is an Error, which goes straight past every catch
		// that turns a bad pack into a report and takes the pack load down with it.
		try {
			String outside = System.getProperty(DIRECTORY_PROPERTY, "");
			if (!outside.isEmpty()) {
				open(Path.of(outside).resolve(FOLDER).resolve("outside-the-game"), false);
			}
		} catch (RuntimeException e) {
			problem = e.toString();
		}
	}

	private TranslationCache() {
	}

	/**
	 * Puts the cache under a directory of the caller's choosing, and clears out every other
	 * edition's.
	 * <p>
	 * The edition names a whole set of keys at once: nothing under another one can ever be asked for
	 * again, and the ceiling has to be about what is still reachable. Called once, before the first
	 * pack is read; a failure here leaves the cache off for the run and the loads exactly as long as
	 * they were.
	 */
	public static void install(Path parent, String edition) {
		open(parent.resolve(FOLDER).resolve(edition.replaceAll("[^A-Za-z0-9._-]", "_")), true);
	}

	/**
	 * Makes the directory, measures what is in it, and takes it into service.
	 *
	 * @param dropOthers whether every sibling edition goes with it. True for the game, where the
	 *                   edition is the only one that can ever be asked for again and the ceiling
	 *                   has to be about what is still reachable. False for the road the property
	 *                   opens, which runs in a static initialiser and would therefore delete the
	 *                   game's own edition a moment before the game installed it
	 */
	private static void open(Path mine, boolean dropOthers) {
		synchronized (LOCK) {
			try {
				Files.createDirectories(mine);
				if (dropOthers) {
					dropOtherEditions(mine.getParent(), mine);
				}

				BYTES.set(total(scan(mine, true)));
				directory = mine;
				problem = "";
			} catch (IOException | RuntimeException e) {
				directory = null;
				problem = e.toString();
			}
		}
	}

	/** What went wrong at install, for whoever has a logger, or empty when nothing did. */
	public static String problem() {
		return problem;
	}

	public static boolean installed() {
		return directory != null;
	}

	/**
	 * The one blob this run refused rather than served, taken and cleared, or empty when there was
	 * none and empty for the rest of the run once it has been taken.
	 * <p>
	 * Taken rather than read, because there is no logger to reach from here: this package names
	 * nothing a game brings, which is what lets the whole translator be run over the corpus without
	 * starting one. The pack chain takes it at the end of a load, which is late by up to a load: a
	 * load that turned back before it, or a family that translates on a worker after it, leaves the
	 * note for the load after. Installed from {@code vitrail.translationDir} instead, nothing takes
	 * it at all and a refusal there is silent, which is what running without a game costs.
	 */
	public static String takeRefusal() {
		return REFUSAL.getAndSet("");
	}

	/**
	 * Keeps the first refusal of the run and no other: the ones after it are the same story told
	 * again. The latch stays up once it is raised, so a refusal in a later load finds the note
	 * already said rather than saying it a second time.
	 */
	private static void refuse(String what) {
		if (!refused) {
			refused = true;
			REFUSAL.set(what);
		}
	}

	public static long served() {
		return SERVED.get();
	}

	public static long translated() {
		return TRANSLATED.get();
	}

	/** Emptied at the head of a load, like the clock beside it: a tally belongs to one load. */
	public static void reset() {
		SERVED.set(0L);
		TRANSLATED.set(0L);
	}

	/**
	 * What this exact translation came to last time, or null when it has to be done.
	 * <p>
	 * The key is worked out by the caller and handed to both ends, the expanded text of a composite
	 * being large enough that hashing it twice to answer one question is work for nothing.
	 */
	static ProgramTranslator.TranslatedProgram lookup(String key, VertexInputs inputs) {
		Path root = directory;
		if (key == null || root == null) {
			return null;
		}

		Path file = root.resolve(key + SUFFIX);
		byte[] raw;
		try {
			// Asked before the read and not after it: the digest sits behind the blob, so nothing
			// can answer for a file that is not already whole in the heap, and a file that grew
			// past what a translation is would be read before anything could refuse it.
			if (Files.size(file) > MOST_BYTES) {
				refuse("a stored translation is larger than any translation is");

				return null;
			}

			raw = Files.readAllBytes(file);
		} catch (IOException e) {
			// Absent is the ordinary case and unreadable the rare one. What follows either way is
			// the translation that would have happened anyway.
			return null;
		} catch (OutOfMemoryError e) {
			// The size was asked for above, so this is a heap that was already at its edge rather
			// than a file that lied about itself. It is still a miss and never a dead load.
			refuse("there was no room to read a stored translation");

			return null;
		}

		int length = raw.length - DIGEST_BYTES;
		if (length <= 0 || !answersForItself(raw, length)) {
			return null;
		}

		ProgramTranslator.TranslatedProgram program;
		try {
			byte[] blob = inflate(raw, length);
			program = TranslatedProgramCodec.read(blob, blob.length, inputs);
		} catch (IOException | RuntimeException e) {
			return null;
		} catch (OutOfMemoryError e) {
			// Not the damaged file: the digest above answers for the bytes on disk, so damage is
			// already a miss by the time the inflate begins. What the digest says nothing about is
			// what those bytes unpack to, which leaves a file written to inflate far past the size
			// it was refused on, or a heap that was already at its edge.
			refuse("a stored translation did not fit the heap once it was unpacked");

			return null;
		}

		touch(file);
		SERVED.incrementAndGet();

		return program;
	}

	/** Keeps what the translator has just made, under the key of everything it was made from. */
	static void store(String key, ProgramTranslator.TranslatedProgram program) {
		TRANSLATED.incrementAndGet();

		Path root = directory;
		if (key == null || root == null) {
			return;
		}

		byte[] raw;
		try {
			raw = deflate(TranslatedProgramCodec.write(program));
		} catch (IOException | RuntimeException e) {
			return;
		}

		Path file = root.resolve(key + SUFFIX);
		Path part = root.resolve(key + "-"
				+ Long.toHexString(Thread.currentThread().threadId()) + PART_SUFFIX);

		try {
			// The blob, then the digest that answers for it, then the move. A process killed
			// halfway through leaves a neighbour and never half a translation under a whole name.
			Files.write(part, raw);
			Files.write(part, sha256(raw), StandardOpenOption.APPEND);
			move(part, file);
			// Added whether or not this replaced a blob already under the same key, which happens
			// whenever a lookup refused one and the translation wrote over it. The count then runs
			// ahead of the directory until a sweep rescans and puts it right, which is what the
			// sweep does before it deletes anything.
			BYTES.addAndGet(raw.length + (long) DIGEST_BYTES);
		} catch (IOException e) {
			try {
				Files.deleteIfExists(part);
			} catch (IOException ignored) {
				// The next install collects it: that scan deletes the neighbours it comes across.
			}

			return;
		}

		if (BYTES.get() > CEILING_BYTES) {
			sweep(root);
		}
	}

	/**
	 * What names this translation on disk, or null when there is nowhere to look.
	 * <p>
	 * Each piece goes in behind its own length, so that two different splits of the same characters
	 * cannot hash alike. The stages are walked in the enum's own order rather than the map's, a
	 * map's order being the caller's business and not a property of what is being translated.
	 */
	static String keyOf(Map<ProgramStage, ExpandedUnit> units,
			VertexInputs inputs, List<String> boundElements, AlphaTest alphaTest, boolean coverage,
			String program, Map<String, VolumeAtlas> volumes) {
		if (directory == null) {
			return null;
		}

		MessageDigest digest = sha256();
		feed(digest, TranslatedProgramCodec.FORMAT);
		// The switches of the translator itself, which are the one input that is not an argument:
		// the trig substitution and the shadow comparison both change what it emits and neither
		// says so in the text it was handed.
		feed(digest, GlslTranslator.emissionSwitches());

		// The whole table, in its own order, which is fixed by the code that builds it. It carries
		// the game version, the operating system, the driver's vendor and renderer, the mipmap
		// level and every other symbol the machine decides, and a define added to it later is in
		// the key from that day without a line here.
		for (Map.Entry<String, String> define
				: EngineDefines.table(EngineDefines.machine()).entrySet()) {
			feed(digest, define.getKey());
			feed(digest, define.getValue());
		}

		feed(digest, inputs.name());
		feed(digest, program);
		feed(digest, alphaTest.function().name());
		digest.update(intBytes(Float.floatToIntBits(alphaTest.reference())));
		digest.update(new byte[] {(byte) (coverage ? 1 : 0)});

		digest.update(intBytes(boundElements.size()));
		for (String element : boundElements) {
			feed(digest, element);
		}

		// IN THE MAP'S OWN ORDER, and not sorted, because the order is not decoration: the
		// translator walks this map to flatten the lookups and emits one helper body per volume in
		// the order it walked, so two maps holding the same entries in a different order are two
		// different texts. Sorting here would have given them one key.
		digest.update(intBytes(volumes.size()));
		for (Map.Entry<String, VolumeAtlas> volume : volumes.entrySet()) {
			feed(digest, volume.getKey());
			digest.update(intBytes(volume.getValue().width()));
			digest.update(intBytes(volume.getValue().height()));
			digest.update(intBytes(volume.getValue().depth()));
			// The helper's text differs between a volume that repeats and one that clamps.
			digest.update(new byte[] {(byte) (volume.getValue().clamp() ? 1 : 0)});
		}

		for (ProgramStage stage : ProgramStage.values()) {
			ExpandedUnit unit = units.get(stage);
			if (unit == null) {
				continue;
			}

			feed(digest, stage.name());
			feed(digest, unit.entry());
			feed(digest, unit.version());
			feedLines(digest, unit.lines());
			// The lines the preprocessor left live, which the translator reads on nearly every walk
			// it makes: two texts that differ only in which of their lines are dead translate
			// differently and would otherwise share a key.
			byte[] live = unit.live().toByteArray();
			digest.update(intBytes(live.length));
			digest.update(live);
		}

		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Squeezed before it goes to disk, because what a translated program mostly is, is GLSL.
	 * <p>
	 * Measured on the corpus: a program comes to a quarter of a megabyte written out plainly, and
	 * ten packs fill a quarter of a gigabyte, which is the whole ceiling. Text of this shape gives
	 * most of that back for a millisecond of work either way, against the sixty a translation
	 * costs. The digest answers for the squeezed bytes, which is what is actually on the disk and
	 * therefore what has to be checked before anything unpacks it.
	 */
	private static byte[] deflate(byte[] blob) throws IOException {
		ByteArrayOutputStream packed = new ByteArrayOutputStream();
		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		try (DeflaterOutputStream out = new DeflaterOutputStream(packed, deflater)) {
			out.write(blob);
		} finally {
			deflater.end();
		}

		return packed.toByteArray();
	}

	private static byte[] inflate(byte[] raw, int length) throws IOException {
		ByteArrayOutputStream blob = new ByteArrayOutputStream();
		Inflater inflater = new Inflater();
		try (InflaterOutputStream out = new InflaterOutputStream(blob, inflater)) {
			out.write(raw, 0, length);
		} finally {
			inflater.end();
		}

		return blob.toByteArray();
	}

	private static boolean answersForItself(byte[] raw, int length) {
		MessageDigest digest = sha256();
		digest.update(raw, 0, length);

		return Arrays.equals(digest.digest(), 0, DIGEST_BYTES, raw, length, raw.length);
	}

	private static void feed(MessageDigest digest, String text) {
		byte[] raw = text.getBytes(StandardCharsets.UTF_8);
		digest.update(intBytes(raw.length));
		digest.update(raw);
	}

	/**
	 * The same bytes {@link #feed} would take for the lines joined by newlines, fed a line at a
	 * time: the joined text of a unit runs to megabytes and was built, and copied again into
	 * bytes, for every stage of every program of a load, cache hits included. The length prefix
	 * is counted first, so the key does not move.
	 */
	private static void feedLines(MessageDigest digest, List<String> lines) {
		int length = Math.max(0, lines.size() - 1);
		for (String line : lines) {
			length += utf8Length(line);
		}

		digest.update(intBytes(length));
		for (int at = 0; at < lines.size(); at++) {
			if (at > 0) {
				digest.update((byte) '\n');
			}

			digest.update(lines.get(at).getBytes(StandardCharsets.UTF_8));
		}
	}

	/** How many bytes {@code getBytes(UTF_8)} yields, a lone surrogate counting as its replacement. */
	private static int utf8Length(String text) {
		int length = 0;
		for (int at = 0; at < text.length(); at++) {
			char c = text.charAt(at);
			if (c < 0x80) {
				length += 1;
			} else if (c < 0x800) {
				length += 2;
			} else if (Character.isHighSurrogate(c) && at + 1 < text.length()
					&& Character.isLowSurrogate(text.charAt(at + 1))) {
				length += 4;
				at++;
			} else if (Character.isSurrogate(c)) {
				length += 1;
			} else {
				length += 3;
			}
		}

		return length;
	}

	private static byte[] intBytes(int value) {
		return new byte[] {
				(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value,
		};
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

	private static void move(Path part, Path file) throws IOException {
		try {
			Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Marks a blob as asked for, so a sweep drops what nothing loads rather than what is oldest. */
	private static void touch(Path file) {
		try {
			Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
		} catch (IOException ignored) {
			// A stamp that cannot be set costs a worse choice at the next sweep and nothing now.
		}
	}

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
	 * Every blob on disk, oldest stamp first.
	 * <p>
	 * <strong>A neighbour is only deleted when {@code prunePartials} says so, which is at install
	 * and nowhere else.</strong> A sweep runs while other workers are in the middle of their own
	 * writes, and deleting what they hold open takes their blob down on one system and aborts the
	 * sweep on the other. What a sweep does with a neighbour is ignore it.
	 */
	private static List<Blob> scan(Path root, boolean prunePartials) throws IOException {
		List<Blob> blobs = new ArrayList<>();

		try (Stream<Path> entries = Files.list(root)) {
			for (Path entry : entries.toList()) {
				if (entry.getFileName().toString().endsWith(PART_SUFFIX)) {
					if (prunePartials) {
						Files.deleteIfExists(entry);
					}
				} else {
					try {
						blobs.add(new Blob(entry, Files.getLastModifiedTime(entry).toMillis(),
								Files.size(entry)));
					} catch (IOException ignored) {
						// Gone, or momentarily unreadable. One blob uncounted, and the next sweep
						// counts it.
					}
				}
			}
		}

		blobs.sort(Comparator.comparingLong(Blob::stamp));

		return blobs;
	}

	private static long total(List<Blob> blobs) {
		long sum = 0L;
		for (Blob blob : blobs) {
			sum += blob.size();
		}

		return sum;
	}

	/**
	 * Brings the directory back under the ceiling, oldest stamp first.
	 * <p>
	 * The count is put down in a {@code finally}, because the caller's test is that same count: a
	 * refusal anywhere in here without it leaves the count high and turns every later write into a
	 * full walk of the directory under this lock, for the rest of the session and with nothing said.
	 */
	private static void sweep(Path root) {
		synchronized (LOCK) {
			if (System.nanoTime() < nextSweepNanos) {
				return;
			}

			long total = BYTES.get();
			try {
				List<Blob> blobs = scan(root, false);
				total = total(blobs);

				for (Blob blob : blobs) {
					if (total <= SWEEP_TARGET) {
						break;
					}

					Files.deleteIfExists(blob.path());
					total -= blob.size();
				}
			} catch (IOException e) {
				// The ceiling is a courtesy and a refusal here is not worth a load. What it must not
				// do is come straight back: a scan that throws leaves the count where it was, which
				// is over the ceiling, and every later write would then walk the directory again
				// under this lock for the rest of the session.
				nextSweepNanos = System.nanoTime() + SWEEP_BACKOFF_NANOS;
			} finally {
				BYTES.set(total);
			}
		}
	}

	/** One file of the cache, with what the sweep needs to order it and to subtract it. */
	private record Blob(Path path, long stamp, long size) {
	}
}
