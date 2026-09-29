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
 * The last of those is also said, once a run, since a blob its digest answers for and this build
 * cannot read is a writer and a reader that disagree rather than a file that was damaged.
 * <p>
 * The store is bounded at a quarter of a gigabyte, and bounded per edition rather than in total: a
 * development install that keeps a neighbour holds two editions and half a gigabyte. It is off
 * until somebody installs it. {@code -Dvitrail.translationDir=<path>} installs it outside the game,
 * which is how the harness reaches it.
 * <p>
 * <strong>The edition is the version this build declares, and the commit it was built
 * from.</strong> The version alone cannot hold two builds apart: every build made between two
 * releases declares the same one, so a translator changed without the version moving would be
 * served its predecessor's units with nothing saying so. A development build therefore translates
 * into a folder named for its commit, which no other build has written to. A release build does
 * not, and must not: a player's translations are worth keeping across every jar of one version,
 * and nothing but a release changes what those jars translate. {@code Vitrail.cacheEdition} is
 * where the two halves are put together, for this cache and for the module cache at once.
 * <p>
 * A build that carries a commit keeps one neighbour rather than emptying the whole folder, and
 * {@link #dropOtherEditions} says which and why.
 */
public final class TranslationCache {

	/** How the harness, or anything else without a game around it, turns this on. */
	private static final String DIRECTORY_PROPERTY = "vitrail.translationDir";

	private static final String FOLDER = "translations";
	private static final String SUFFIX = ".tr";
	private static final String PART_SUFFIX = ".part";

	/**
	 * What stands between the family and the commit in the name of a directory here. The caller
	 * joins the two halves with a plus, which {@link #plain} cannot hold in a directory name and
	 * replaces, so what a neighbour is matched on is the underscore that reaches the disk.
	 */
	private static final String EDITION_SEPARATOR = "_";

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
				open(Path.of(outside).resolve(FOLDER).resolve("outside-the-game"), "");
			}
		} catch (RuntimeException e) {
			problem = e.toString();
		}
	}

	private TranslationCache() {
	}

	/**
	 * Puts the cache under a directory of the caller's choosing, and clears out what another
	 * edition left.
	 * <p>
	 * The edition names a whole set of keys at once: nothing under another one can be asked for by
	 * this build, and the ceiling has to be about what is still reachable. One neighbour is spared
	 * for the build that owns it, and {@link #dropOtherEditions} says which and why. Called once,
	 * before the first pack is read; a failure of this edition's own folder leaves the cache off
	 * for the run and the loads exactly as long as they were, while a leftover of another edition
	 * that will not go leaves it on and is kept in {@link #problem()}.
	 *
	 * @param edition what this build translates into, which carries the commit it was built from
	 *                when there is one to carry and is otherwise the family entire
	 * @param family  what every edition of this version and this game begins with, which is what
	 *                decides whether a neighbour is another build of this version or the leavings
	 *                of another version altogether
	 */
	public static void install(Path parent, String edition, String family) {
		open(parent.resolve(FOLDER).resolve(plain(edition)), plain(family));
	}

	/**
	 * Makes the directory, measures what is in it, and takes it into service.
	 * <p>
	 * <strong>Only this edition's own directory decides whether it is taken into service.</strong>
	 * What another edition left is nothing this build reads, so a file in it that will not go, held
	 * by a scanner or an indexer or made read-only by hand, costs the disk it sits on and is kept in
	 * {@link #problem} for whoever has a logger. Were it to throw out of here, one stale file in a
	 * folder no build would ever read again would leave the cache off at every launch for as long
	 * as the file stayed.
	 *
	 * @param family the sanitized family of this edition, or empty to leave every neighbour alone.
	 *               Empty for the road the property opens, which runs in a static initialiser and
	 *               would otherwise delete the game's own edition a moment before the game
	 *               installed it
	 */
	private static void open(Path mine, String family) {
		synchronized (LOCK) {
			try {
				Files.createDirectories(mine);
				String left = "";
				if (!family.isEmpty()) {
					touch(mine);
					left = dropOtherEditions(mine.getParent(), mine, family);
				}

				String dead = dropPartials(mine);
				if (!dead.isEmpty()) {
					left = left.isEmpty() ? dead : left + ", " + dead;
				}

				BYTES.set(total(scan(mine)));
				directory = mine;
				problem = left.isEmpty() ? "" : "what an earlier run left in " + mine.getParent()
						+ " could not all be taken away, and the next launch tries again: " + left;
			} catch (IOException | RuntimeException e) {
				directory = null;
				problem = e.toString();
			}
		}
	}

	/**
	 * What went wrong at install, for whoever has a logger, or empty when nothing did. A cache that
	 * {@link #installed} can have one too: another edition's folder it could not wholly empty, or a
	 * dead write of this one it could not delete, which costs disk and leaves every translation of
	 * this build where it was.
	 */
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
			// Said, where a damaged file above is not. The digest has answered for these bytes, so
			// they are the ones the writer put down, and a blob that still does not read back is
			// this build's writer and reader disagreeing: a miss every load and nothing else to see.
			// A blob made for another vertex format lands here too and is said for the same
			// reason. The format is in the key, and the program stored under a key was translated
			// for the format that key was made of, so no ordinary road reaches another one: only a
			// key that has come apart from its blob does.
			refuse("a stored translation answered for its own bytes and still could not be read "
					+ "back (" + e + ")");

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
		// And the device's answer on the vendor extensions, on the stages it runs subgroup
		// operations in and on whether its driver is MoltenVK, which decide which branch of a pack
		// compiles and what a float packing call becomes: a card that has one of them translates
		// differently from a card that has not.
		feed(digest, VendorExtensions.key());

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
	 * <p>
	 * <strong>Each line is encoded once</strong>, and the arrays are kept between the count and the
	 * feed. Counting the bytes by walking the characters and then encoding them walked every line
	 * twice, and that was most of what a key cost apart from the digest itself. What the arrays
	 * hold is the unit's text once, and only for the length of this call. The count is theirs, so
	 * it is what the encoder actually wrote, a lone surrogate's replacement included.
	 */
	private static void feedLines(MessageDigest digest, List<String> lines) {
		byte[][] encoded = new byte[lines.size()][];
		int length = Math.max(0, lines.size() - 1);
		for (int at = 0; at < encoded.length; at++) {
			encoded[at] = lines.get(at).getBytes(StandardCharsets.UTF_8);
			length += encoded[at].length;
		}

		digest.update(intBytes(length));
		for (int at = 0; at < encoded.length; at++) {
			if (at > 0) {
				digest.update((byte) '\n');
			}

			digest.update(encoded[at]);
		}
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

	/**
	 * Marks a blob as asked for, so a sweep drops what nothing loads rather than what is oldest,
	 * and at install the edition's own directory as opened.
	 * <p>
	 * The directory is stamped for the choice {@link #dropOtherEditions} makes, and stamped at
	 * install rather than only by the blobs landing in it: a file system moves a directory's own
	 * stamp when a blob is created inside it, so without this line a run that hit on everything and
	 * wrote nothing would leave a folder reading as abandoned. With it, the stamp is the last time
	 * the edition was used at all, by a launch or by a write.
	 */
	private static void touch(Path file) {
		try {
			Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
		} catch (IOException ignored) {
			// A stamp that cannot be set costs a worse choice at the next sweep and nothing now.
		}
	}

	/**
	 * Deletes what another edition left, sparing one neighbour when this build has a commit in its
	 * name: the edition of this build's own family used most recently.
	 * <p>
	 * Two builds of one version are two editions, and a developer moving between them would
	 * otherwise have each of them empty the other's folder on the way in, which is every pack
	 * translated from cold at every swap. One is what a swap needs, and it is what bounds the disk
	 * at two editions rather than at one per build ever made. A build whose edition IS the family
	 * spares none, which is every release and also a development build no commit could be read for.
	 * <p>
	 * It never throws, and goes on past whatever refuses it: every folder is attempted, and within
	 * one every file that will go does.
	 *
	 * @return each folder left behind with the first refusal it gave, or empty when all of it went
	 */
	private static String dropOtherEditions(Path root, Path mine, String family) {
		List<Path> entries;
		try (Stream<Path> found = Files.list(root)) {
			entries = found.toList();
		} catch (IOException | RuntimeException e) {
			return "the folder could not be listed (" + e + ")";
		}

		String kept = mine.getFileName().toString().equals(family)
				? "" : newestSibling(entries, mine, family);
		List<String> left = new ArrayList<>();

		for (Path entry : entries) {
			if (!entry.equals(mine) && !entry.getFileName().toString().equals(kept)) {
				Exception refusal = dropTree(entry);
				if (refusal != null) {
					left.add(entry.getFileName() + " (" + refusal + ")");
				}
			}
		}

		return String.join(", ", left);
	}

	/**
	 * The name of the edition of this family used most recently, or empty when this build is the
	 * only one of its family to have run here. A directory has a name, so an empty answer matches
	 * nothing. A folder whose stamp cannot be read is not a candidate, which costs a swap one
	 * store at worst.
	 */
	private static String newestSibling(List<Path> entries, Path mine, String family) {
		String newest = "";
		long stamp = Long.MIN_VALUE;

		for (Path entry : entries) {
			String name = entry.getFileName().toString();
			if (entry.equals(mine) || !ofFamily(name, family)) {
				continue;
			}

			long when;
			try {
				when = Files.getLastModifiedTime(entry).toMillis();
			} catch (IOException e) {
				continue;
			}

			if (when > stamp) {
				stamp = when;
				newest = name;
			}
		}

		return newest;
	}

	/**
	 * Whether a directory name is an edition of this family: the family entire, or the family and
	 * then a commit behind the separator.
	 * <p>
	 * The separator is what makes this an answer and not a guess. A name beginning with the family
	 * is not an edition of it: {@code ...mc26.20} begins with {@code ...mc26.2} and is another game
	 * version altogether, whose blobs are exactly the ones nothing can ever ask for again, so a
	 * prefix on its own would spare the folder that most needs sweeping.
	 */
	private static boolean ofFamily(String name, String family) {
		return name.equals(family) || name.startsWith(family + EDITION_SEPARATOR);
	}

	/** Whatever a directory name cannot hold, replaced so that a name is never two names. */
	private static String plain(String text) {
		return text.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	/**
	 * Deletes what it can of one folder, deepest first, and goes on past a file that refuses: the
	 * files beside it are space as well. The folders above a refusal then refuse in their turn, not
	 * being empty, so the FIRST refusal is the one that says why.
	 *
	 * @return that first refusal, or null when the whole folder went
	 */
	private static Exception dropTree(Path entry) {
		List<Path> tree;
		try (Stream<Path> walk = Files.walk(entry)) {
			tree = walk.sorted(Comparator.reverseOrder()).toList();
		} catch (IOException | RuntimeException e) {
			// A folder inside it that cannot be read, which the walk throws unchecked.
			return e;
		}

		Exception first = null;
		for (Path found : tree) {
			try {
				Files.deleteIfExists(found);
			} catch (IOException e) {
				if (first == null) {
					first = e;
				}
			}
		}

		return first;
	}

	/**
	 * Deletes the {@code .part} files a killed writer left in this edition's own directory, and
	 * answers for the ones that stay.
	 * <p>
	 * <strong>A neighbour is only deleted here, which is at install and nowhere else.</strong> A
	 * sweep runs while other workers are in the middle of their own writes, and deleting what they
	 * hold open takes their blob down on one system and aborts the sweep on the other.
	 * <p>
	 * One that refuses is held by something outside this process, and is named beside the folders
	 * {@link #dropOtherEditions} could not empty, in the same {@link #problem}: the next install
	 * tries again, and refused out of here it would have left the whole cache off over one file
	 * nothing reads. It is not counted, since {@link #scan} does not count a neighbour and a sweep
	 * has nothing to drop for one.
	 *
	 * @return each file left behind with its refusal, or empty when all of it went
	 * @throws IOException when the directory cannot be listed, which is this edition's own and so
	 *                     leaves the cache off
	 */
	private static String dropPartials(Path mine) throws IOException {
		List<String> left = new ArrayList<>();

		try (Stream<Path> entries = Files.list(mine)) {
			for (Path entry : entries.toList()) {
				if (entry.getFileName().toString().endsWith(PART_SUFFIX)) {
					try {
						Files.deleteIfExists(entry);
					} catch (IOException e) {
						left.add(mine.getFileName() + "/" + entry.getFileName() + " (" + e + ")");
					}
				}
			}
		}

		return String.join(", ", left);
	}

	/**
	 * Every blob on disk, oldest stamp first. A neighbour is ignored: it is not reachable, it is
	 * about to become a blob, and it is nobody's to count. {@link #dropPartials} is what deletes
	 * the dead ones.
	 */
	private static List<Blob> scan(Path root) throws IOException {
		List<Blob> blobs = new ArrayList<>();

		try (Stream<Path> entries = Files.list(root)) {
			for (Path entry : entries.toList()) {
				if (!entry.getFileName().toString().endsWith(PART_SUFFIX)) {
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
	 * <p>
	 * It is put down by the difference the sweep found and not as the figure, because a store adds
	 * to the count outside this lock: a blob that lands after the listing and is added before the
	 * count is set would otherwise be wiped from it, and a count that runs short is one the ceiling
	 * is late to catch. What the difference can do instead is count a blob the listing saw twice,
	 * which is the direction the count already errs in, and the next sweep's rescan puts it right.
	 */
	private static void sweep(Path root) {
		synchronized (LOCK) {
			if (System.nanoTime() < nextSweepNanos) {
				return;
			}

			long counted = BYTES.get();
			long total = counted;
			try {
				List<Blob> blobs = scan(root);
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
				BYTES.addAndGet(total - counted);
			}
		}
	}

	/** One file of the cache, with what the sweep needs to order it and to subtract it. */
	private record Blob(Path path, long stamp, long size) {
	}
}
