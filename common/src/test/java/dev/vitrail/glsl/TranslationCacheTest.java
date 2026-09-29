package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.PackTexture;
import dev.vitrail.pack.model.PixelFormat;
import dev.vitrail.pack.model.PixelType;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.source.ExpansionStats;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds {@link TranslationCache} to its contract: a kept translation is served back exactly, and
 * anything short of that (absent, damaged, foreign, too large) is a miss and never a wrong hit.
 * <p>
 * The cache keeps its state in statics, which is the one thing that makes it awkward to test: the
 * directory it is installed in, the note of a refusal and its latch, the sweep's backoff and the
 * byte count. Every test starts and ends with all of them put back to their state at class load, by
 * reflection, since nothing in the class takes the directory away again.
 * <p>
 * The ceiling is a quarter of a gigabyte, which no test may write. The sweep is reached with sparse
 * files instead: they are big to the cache, which measures {@link Files#size}, and take no disk.
 */
class TranslationCacheTest {

	private static final long MIB = 1024L * 1024L;

	private static final String EDITION = "edition-a";

	@TempDir
	Path root;

	@BeforeEach
	void startWithTheStaticsAsTheClassLoadLeftThem() throws ReflectiveOperationException {
		putTheStaticsBack();
	}

	@AfterEach
	void leaveTheStaticsAsTheClassLoadLeftThem() throws ReflectiveOperationException {
		putTheStaticsBack();
	}

	static void putTheStaticsBack() throws ReflectiveOperationException {
		set("directory", null);
		set("problem", "");
		// Nothing is asked of the clock at class load, and the smallest value is the one no clock is behind.
		set("nextSweepNanos", Long.MIN_VALUE);
		set("refused", false);
		((AtomicLong) get("BYTES")).set(0L);
		TranslationCache.takeRefusal();
		TranslationCache.reset();
	}

	private static Object get(String name) throws ReflectiveOperationException {
		Field field = TranslationCache.class.getDeclaredField(name);
		field.setAccessible(true);

		return field.get(null);
	}

	private static void set(String name, Object value) throws ReflectiveOperationException {
		Field field = TranslationCache.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

	private Path store() {
		return this.root.resolve("translations").resolve(EDITION);
	}

	private void install() {
		TranslationCache.install(this.root, EDITION, EDITION);
		assertTrue(TranslationCache.installed(), TranslationCache.problem());
	}

	private static ProgramTranslator.TranslatedProgram program(String text) {
		return SampleTranslations.program(VertexInputs.TERRAIN, text);
	}

	private static ExpandedUnit unit(String entry, String version, List<String> lines, int... live) {
		BitSet bits = new BitSet();
		for (int line : live) {
			bits.set(line);
		}

		return new ExpandedUnit(entry, lines, version, ExpansionStats.NONE, bits, Map.of());
	}

	private static Map<ProgramStage, ExpandedUnit> units() {
		Map<ProgramStage, ExpandedUnit> units = new LinkedHashMap<>();
		units.put(ProgramStage.VERTEX, unit("a.vsh", "460", List.of("void main() {", "}"), 0, 1));
		units.put(ProgramStage.FRAGMENT, unit("a.fsh", "460", List.of("void main() {", "}"), 0, 1));

		return units;
	}

	private static VolumeAtlas atlas(int size, boolean clamp) {
		return VolumeAtlas.of(new PackTexture.Raw(PackTexture.Shape.TEXTURE_3D, null, size, size, size,
				PixelFormat.RGBA, PixelType.UNSIGNED_BYTE), clamp);
	}

	private static String key(Map<ProgramStage, ExpandedUnit> units, VertexInputs inputs, List<String> elements,
			AlphaTest alpha, boolean coverage, String program, Map<String, VolumeAtlas> volumes) {
		return TranslationCache.keyOf(units, inputs, elements, alpha, coverage, program, volumes);
	}

	private static String baseKey() {
		return key(units(), VertexInputs.TERRAIN, List.of("Position", "Color"), AlphaTest.OFF, false,
				"gbuffers_terrain", Map.of());
	}

	private static void sparse(Path file, long size, long stampMillis) throws IOException {
		try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
			handle.setLength(size);
		}

		Files.setLastModifiedTime(file, FileTime.fromMillis(stampMillis));
	}

	private static List<String> names(Path directory) throws IOException {
		try (Stream<Path> found = Files.list(directory)) {
			return found.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	// ------------------------------------------------------------------------------ off, and on

	@Test
	void isOffUntilSomebodyInstallsItAndAnswersNothingWhileItIs() throws IOException {
		assertFalse(TranslationCache.installed());
		assertNull(baseKey());
		assertNull(TranslationCache.lookup("0".repeat(64), VertexInputs.TERRAIN));

		TranslationCache.store("0".repeat(64), program("x"));
		// The tally counts a translation whether or not it could be kept.
		assertEquals(1, TranslationCache.translated());
		assertEquals(0, TranslationCache.served());
		assertFalse(Files.exists(this.root.resolve("translations")));
	}

	@Test
	void aNullKeyIsNeverAskedForAndNeverKept() {
		install();
		TranslationCache.store(null, program("x"));
		assertNull(TranslationCache.lookup(null, VertexInputs.TERRAIN));
		assertEquals(1, TranslationCache.translated());
	}

	@Test
	void installMakesTheEditionsDirectoryAndSaysNothingWentWrong() {
		install();

		assertTrue(Files.isDirectory(store()));
		assertEquals("", TranslationCache.problem());
	}

	@Test
	void installUnderAFileLeavesTheCacheOffAndSaysWhy() throws IOException {
		Path file = Files.writeString(this.root.resolve("in-the-way"), "x");
		TranslationCache.install(file, EDITION, EDITION);

		assertFalse(TranslationCache.installed());
		assertFalse(TranslationCache.problem().isEmpty());
	}

	@Test
	void installDeletesTheUnfinishedWritesItFinds() throws IOException {
		Files.createDirectories(store());
		Files.writeString(store().resolve("ab-1.part"), "half");
		Files.writeString(store().resolve("cd.tr"), "kept");
		install();

		assertEquals(List.of("cd.tr"), names(store()));
	}

	// -------------------------------------------------------------------- keeping and serving

	@Test
	void keptTranslationIsServedBackAsTheSameProgramAndCounted() {
		install();
		String key = baseKey();
		ProgramTranslator.TranslatedProgram kept = program("void main() {}");

		assertNull(TranslationCache.lookup(key, VertexInputs.TERRAIN));
		assertEquals(0, TranslationCache.served());

		TranslationCache.store(key, kept);
		ProgramTranslator.TranslatedProgram served = TranslationCache.lookup(key, VertexInputs.TERRAIN);

		assertEquals(kept, served);
		assertEquals(1, TranslationCache.translated());
		assertEquals(1, TranslationCache.served());

		TranslationCache.reset();
		assertEquals(0, TranslationCache.translated());
		assertEquals(0, TranslationCache.served());
	}

	@Test
	void aFileIsTheDeflatedBlobBehindTheSha256OfThoseBytes() throws Exception {
		install();
		String key = baseKey();
		ProgramTranslator.TranslatedProgram kept = program("void main() {}");
		TranslationCache.store(key, kept);

		byte[] file = Files.readAllBytes(store().resolve(key + ".tr"));
		byte[] packed = Arrays.copyOf(file, file.length - 32);
		byte[] digest = Arrays.copyOfRange(file, file.length - 32, file.length);

		assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(packed), digest);

		ByteArrayOutputStream inflated = new ByteArrayOutputStream();
		Inflater inflater = new Inflater();
		try (InflaterOutputStream out = new InflaterOutputStream(inflated, inflater)) {
			out.write(packed);
		}

		assertArrayEquals(TranslatedProgramCodec.write(kept), inflated.toByteArray());
		assertEquals(List.of(key + ".tr"), names(store()));
	}

	@Test
	void keepingTheSameKeyAgainReplacesItAndLeavesNoUnfinishedFile() throws IOException {
		install();
		String key = baseKey();
		TranslationCache.store(key, program("first"));
		TranslationCache.store(key, program("second"));

		assertEquals(program("second"), TranslationCache.lookup(key, VertexInputs.TERRAIN));
		assertEquals(List.of(key + ".tr"), names(store()));
	}

	@Test
	void aBlobKeptForAnotherVertexFormatIsAMissAndNotAProgram() {
		install();
		String key = baseKey();
		TranslationCache.store(key, program("x"));

		assertNull(TranslationCache.lookup(key, VertexInputs.ENTITY));
		assertEquals(0, TranslationCache.served());
	}

	// ----------------------------------------------------------------------------------- damage

	private byte[] keptFile(String key) throws IOException {
		return Files.readAllBytes(store().resolve(key + ".tr"));
	}

	@Test
	void everyKindOfDamagedFileIsAMissAndNeverAProgramOrAnError() throws IOException {
		install();
		String key = baseKey();
		TranslationCache.store(key, program("void main() {}"));
		byte[] good = keptFile(key);
		Path file = store().resolve(key + ".tr");

		List<byte[]> damaged = new ArrayList<>();
		byte[] flippedBlob = good.clone();
		flippedBlob[5] ^= 0x40;
		damaged.add(flippedBlob);
		byte[] flippedDigest = good.clone();
		flippedDigest[good.length - 1] ^= 1;
		damaged.add(flippedDigest);
		damaged.add(Arrays.copyOf(good, good.length - 1));
		damaged.add(Arrays.copyOf(good, good.length + 1));
		damaged.add(Arrays.copyOfRange(good, good.length - 32, good.length));
		damaged.add(Arrays.copyOf(good, 31));
		damaged.add(new byte[0]);
		damaged.add(new byte[1000]);

		for (byte[] bytes : damaged) {
			Files.write(file, bytes);
			assertNull(TranslationCache.lookup(key, VertexInputs.TERRAIN), "a file of " + bytes.length + " bytes");
		}

		assertEquals("", TranslationCache.takeRefusal());
		Files.write(file, good);
		assertNotNull(TranslationCache.lookup(key, VertexInputs.TERRAIN));
	}

	@Test
	void anyRandomDamageToAKeptFileIsAMiss() throws IOException {
		install();
		String key = baseKey();
		TranslationCache.store(key, program("void main() { gl_FragData[0] = vec4(1.0); }\n".repeat(50)));
		byte[] good = keptFile(key);
		Path file = store().resolve(key + ".tr");
		Random random = new Random(0xD15C);

		for (int round = 0; round < 300; round++) {
			byte[] bytes = good.clone();
			bytes[random.nextInt(bytes.length)] ^= (byte) (1 + random.nextInt(255));
			Files.write(file, bytes);

			assertNull(TranslationCache.lookup(key, VertexInputs.TERRAIN), "round " + round);
		}
	}

	/**
	 * The digest answers for the bytes, so a blob that still does not decode is the writer and the
	 * reader of this build disagreeing, and not a damaged file: it is a miss, and it is said.
	 */
	@Test
	void aBlobWithARightDigestThatDoesNotDecodeIsAMissAndIsReportedOnce() throws Exception {
		install();
		String key = baseKey();
		byte[] packed = deflate("not a translated program".getBytes(StandardCharsets.UTF_8));
		byte[] file = Arrays.copyOf(packed, packed.length + 32);
		System.arraycopy(MessageDigest.getInstance("SHA-256").digest(packed), 0, file, packed.length, 32);
		Files.write(store().resolve(key + ".tr"), file);

		assertNull(TranslationCache.lookup(key, VertexInputs.TERRAIN));
		String refusal = TranslationCache.takeRefusal();
		assertTrue(refusal.contains("could not be read back"), refusal);
		assertEquals("", TranslationCache.takeRefusal());
	}

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

	@Test
	void aFileLargerThanAnyTranslationIsRefusedUnreadAndTheRefusalIsTakenOnce() throws IOException {
		install();
		String key = baseKey();
		sparse(store().resolve(key + ".tr"), 64 * MIB + 1, 1_000L);

		assertNull(TranslationCache.lookup(key, VertexInputs.TERRAIN));
		String refusal = TranslationCache.takeRefusal();
		assertTrue(refusal.contains("larger than any translation"), refusal);
		assertEquals("", TranslationCache.takeRefusal());

		// The latch stays up: a second refusal of the run is the same story and is not told again.
		assertNull(TranslationCache.lookup(key, VertexInputs.TERRAIN));
		assertEquals("", TranslationCache.takeRefusal());
	}

	// ------------------------------------------------------------------------------------- key

	@Test
	void aKeyIsSixtyFourLowerCaseHexDigitsAndTheSameAskGivesTheSameKey() {
		install();
		String key = baseKey();

		assertTrue(key.matches("[0-9a-f]{64}"), key);
		assertEquals(key, baseKey());
	}

	@Test
	void everyInputOfATranslationMovesTheKey() {
		install();
		List<String> elements = List.of("Position", "Color");
		Map<String, VolumeAtlas> volumes = Map.of("noise", atlas(8, false));
		String base = key(units(), VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", volumes);

		Map<ProgramStage, ExpandedUnit> otherEntry = units();
		otherEntry.put(ProgramStage.FRAGMENT, unit("b.fsh", "460", List.of("void main() {", "}"), 0, 1));
		Map<ProgramStage, ExpandedUnit> otherVersion = units();
		otherVersion.put(ProgramStage.FRAGMENT, unit("a.fsh", "450", List.of("void main() {", "}"), 0, 1));
		Map<ProgramStage, ExpandedUnit> otherText = units();
		otherText.put(ProgramStage.FRAGMENT, unit("a.fsh", "460", List.of("void main() { }", "}"), 0, 1));
		Map<ProgramStage, ExpandedUnit> otherLive = units();
		otherLive.put(ProgramStage.FRAGMENT, unit("a.fsh", "460", List.of("void main() {", "}"), 0));
		Map<ProgramStage, ExpandedUnit> fewer = units();
		fewer.remove(ProgramStage.VERTEX);
		Map<ProgramStage, ExpandedUnit> another = units();
		another.put(ProgramStage.GEOMETRY, unit("a.gsh", "460", List.of("void main() {", "}"), 0, 1));

		List<String> others = List.of(
				key(otherEntry, VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", volumes),
				key(otherVersion, VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", volumes),
				key(otherText, VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", volumes),
				key(otherLive, VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", volumes),
				key(fewer, VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", volumes),
				key(another, VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", volumes),
				key(units(), VertexInputs.ENTITY, elements, AlphaTest.OFF, false, "p", volumes),
				key(units(), VertexInputs.TERRAIN, List.of("Color", "Position"), AlphaTest.OFF, false, "p", volumes),
				key(units(), VertexInputs.TERRAIN, List.of("Position"), AlphaTest.OFF, false, "p", volumes),
				key(units(), VertexInputs.TERRAIN, elements, AlphaTest.CUTOUT, false, "p", volumes),
				key(units(), VertexInputs.TERRAIN, elements, new AlphaTest(AlphaTest.Function.GREATER, 0.25F), false, "p",
						volumes),
				key(units(), VertexInputs.TERRAIN, elements, new AlphaTest(AlphaTest.Function.LESS, 0.0F), false, "p",
						volumes),
				key(units(), VertexInputs.TERRAIN, elements, AlphaTest.OFF, true, "p", volumes),
				key(units(), VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "q", volumes),
				key(units(), VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", Map.of()),
				key(units(), VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", Map.of("noise", atlas(8, true))),
				key(units(), VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", Map.of("noise", atlas(16, false))),
				key(units(), VertexInputs.TERRAIN, elements, AlphaTest.OFF, false, "p", Map.of("other", atlas(8, false))));

		assertEquals(others.size(), Set.copyOf(others).size(), "two different asks share a key");
		assertFalse(others.contains(base));
	}

	@Test
	void theKeyDoesNotDependOnTheOrderTheCallerFilledTheStageMapIn() {
		install();
		Map<ProgramStage, ExpandedUnit> reversed = new LinkedHashMap<>();
		reversed.put(ProgramStage.FRAGMENT, units().get(ProgramStage.FRAGMENT));
		reversed.put(ProgramStage.VERTEX, units().get(ProgramStage.VERTEX));

		assertEquals(baseKey(), key(reversed, VertexInputs.TERRAIN, List.of("Position", "Color"), AlphaTest.OFF, false,
				"gbuffers_terrain", Map.of()));
	}

	@Test
	void theKeyDoesDependOnTheOrderOfTheVolumesBecauseTheTextDoes() {
		install();
		Map<String, VolumeAtlas> ab = new LinkedHashMap<>();
		ab.put("a", atlas(8, false));
		ab.put("b", atlas(8, false));
		Map<String, VolumeAtlas> ba = new LinkedHashMap<>();
		ba.put("b", atlas(8, false));
		ba.put("a", atlas(8, false));

		assertNotEquals(key(units(), VertexInputs.TERRAIN, List.of(), AlphaTest.OFF, false, "p", ab),
				key(units(), VertexInputs.TERRAIN, List.of(), AlphaTest.OFF, false, "p", ba));
	}

	@Test
	void theKeyHoldsPiecesBehindTheirLengthsSoTwoSplitsOfTheSameCharactersDoNotCollide() {
		install();
		String split = key(units(), VertexInputs.TERRAIN, List.of("ab", "c"), AlphaTest.OFF, false, "p", Map.of());
		String other = key(units(), VertexInputs.TERRAIN, List.of("a", "bc"), AlphaTest.OFF, false, "p", Map.of());
		assertNotEquals(split, other);

		Map<ProgramStage, ExpandedUnit> one = units();
		one.put(ProgramStage.FRAGMENT, unit("a.fsh", "460", List.of("ab", "c"), 0, 1));
		Map<ProgramStage, ExpandedUnit> two = units();
		two.put(ProgramStage.FRAGMENT, unit("a.fsh", "460", List.of("a", "bc"), 0, 1));
		assertNotEquals(key(one, VertexInputs.TERRAIN, List.of(), AlphaTest.OFF, false, "p", Map.of()),
				key(two, VertexInputs.TERRAIN, List.of(), AlphaTest.OFF, false, "p", Map.of()));
	}

	@Test
	void lineBreaksWithinALineAndBetweenLinesAreTheSameTextAndTheSameKey() {
		install();
		Map<ProgramStage, ExpandedUnit> split = units();
		split.put(ProgramStage.FRAGMENT, unit("a.fsh", "460", List.of("a", "b"), 0, 1));
		Map<ProgramStage, ExpandedUnit> joined = units();
		joined.put(ProgramStage.FRAGMENT, unit("a.fsh", "460", List.of("a\nb"), 0, 1));

		assertEquals(key(split, VertexInputs.TERRAIN, List.of(), AlphaTest.OFF, false, "p", Map.of()),
				key(joined, VertexInputs.TERRAIN, List.of(), AlphaTest.OFF, false, "p", Map.of()));
	}

	@Test
	void theEnginesDefineTableIsInTheKeySoAnotherGameVersionIsAnotherKey() {
		install();
		EngineDefines.Environment before = EngineDefines.machine();
		String base = baseKey();
		try {
			EngineDefines.machine(EngineDefines.Environment.of(before.mcVersion() + 1));
			assertNotEquals(base, baseKey());
		} finally {
			EngineDefines.machine(before);
		}

		assertEquals(base, baseKey());
	}

	@Test
	void theDevicesAnswerOnTheDriverIsInTheKey() {
		install();
		String base = baseKey();
		boolean moltenVk = VendorExtensions.moltenVk();
		try {
			VendorExtensions.serveMoltenVk(!moltenVk);
			assertNotEquals(base, baseKey());
		} finally {
			VendorExtensions.serveMoltenVk(moltenVk);
		}

		assertEquals(base, baseKey());
	}

	// ---------------------------------------------------------------------------------- sweep

	@Test
	void aStoreOverTheCeilingDropsTheOldestBlobsDownToThreeQuartersAndNoFurther() throws IOException {
		Files.createDirectories(store());
		sparse(store().resolve("old.tr"), 150 * MIB, 1_000L);
		sparse(store().resolve("newer.tr"), 120 * MIB, 2_000L);
		install();

		String key = baseKey();
		TranslationCache.store(key, program("x"));

		// 270 MiB and a blob is past 256; the oldest goes and 120 is under the 192 it sweeps to.
		assertEquals(List.of(key + ".tr", "newer.tr"), names(store()));
		assertEquals(program("x"), TranslationCache.lookup(key, VertexInputs.TERRAIN));
	}

	@Test
	void aStoreUnderTheCeilingDeletesNothing() throws IOException {
		Files.createDirectories(store());
		sparse(store().resolve("old.tr"), 100 * MIB, 1_000L);
		sparse(store().resolve("newer.tr"), 100 * MIB, 2_000L);
		install();

		TranslationCache.store(baseKey(), program("x"));

		assertEquals(3, names(store()).size());
	}

	@Test
	void aBlobThatIsServedIsNewerThanOneThatIsOnlyOlderSoTheSweepKeepsWhatIsAskedFor() throws IOException {
		install();
		String asked = baseKey();
		String other = key(units(), VertexInputs.TERRAIN, List.of("Position"), AlphaTest.OFF, false, "other", Map.of());
		TranslationCache.store(asked, program("asked"));
		TranslationCache.store(other, program("other"));
		Files.setLastModifiedTime(store().resolve(asked + ".tr"), FileTime.fromMillis(1_000L));
		Files.setLastModifiedTime(store().resolve(other + ".tr"), FileTime.fromMillis(3_000L));
		sparse(store().resolve("filler.tr"), 270 * MIB, 2_000L);
		// Installed again so that the count takes the filler in, which the first install never saw.
		install();

		assertNotNull(TranslationCache.lookup(asked, VertexInputs.TERRAIN));
		String third = key(units(), VertexInputs.TERRAIN, List.of("Color"), AlphaTest.OFF, false, "third", Map.of());
		TranslationCache.store(third, program("third"));

		// Without the stamp the lookup put on it, the asked blob is the oldest and goes first.
		assertEquals(List.of(asked + ".tr", other + ".tr", third + ".tr").stream().sorted().toList(), names(store()));
	}

	@Test
	void aSweepThatCannotFinishStandsAsideForSixtySecondsAndTheNextOneFinishesTheJob() throws Exception {
		Path junk = Files.createDirectories(store().resolve("junk"));
		Files.writeString(junk.resolve("keep.txt"), "x");
		Files.setLastModifiedTime(junk, FileTime.fromMillis(1_000L));
		sparse(store().resolve("filler.tr"), 270 * MIB, 3_000L);
		install();

		// The oldest entry is a directory with something in it: it cannot be deleted and ends the sweep.
		TranslationCache.store(baseKey(), program("one"));
		assertTrue(Files.exists(store().resolve("filler.tr")));

		// The block is gone, and the count is still over the ceiling; but the sweep stands aside for a
		// minute rather than walk the directory again on every write.
		Files.delete(junk.resolve("keep.txt"));
		Files.delete(junk);
		String second = key(units(), VertexInputs.TERRAIN, List.of("Position"), AlphaTest.OFF, false, "two", Map.of());
		TranslationCache.store(second, program("two"));
		assertTrue(Files.exists(store().resolve("filler.tr")));

		// Once the minute has run out, the next write sweeps what was left.
		set("nextSweepNanos", Long.MIN_VALUE);
		String third = key(units(), VertexInputs.TERRAIN, List.of("Color"), AlphaTest.OFF, false, "three", Map.of());
		TranslationCache.store(third, program("three"));

		assertFalse(Files.exists(store().resolve("filler.tr")));
		assertEquals(3, names(store()).size());
	}

	// -------------------------------------------------------------------------- neighbours

	@Test
	void aReleaseInstallDeletesEveryOtherFolderOfTheStore() throws IOException {
		Path translations = this.root.resolve("translations");
		Files.createDirectories(translations.resolve("0.11.0-beta"));
		Files.writeString(translations.resolve("0.11.0-beta").resolve("x.tr"), "old");
		Files.createDirectories(translations.resolve("0.12.0-beta_abc"));

		TranslationCache.install(this.root, "0.12.0-beta", "0.12.0-beta");

		assertEquals(List.of("0.12.0-beta"), names(translations));
	}

	@Test
	void aBuildWithACommitKeepsTheNewestNeighbourOfItsFamilyAndNoOtherFolder() throws IOException {
		Path translations = this.root.resolve("translations");
		Files.createDirectories(translations.resolve("0.12.0-beta_old"));
		Files.createDirectories(translations.resolve("0.12.0-beta_older"));
		Files.createDirectories(translations.resolve("0.12.0-beta"));
		Files.createDirectories(translations.resolve("0.12.0-betaX_new"));
		Files.createDirectories(translations.resolve("0.11.0-beta"));
		Files.setLastModifiedTime(translations.resolve("0.12.0-beta_old"), FileTime.fromMillis(5_000L));
		Files.setLastModifiedTime(translations.resolve("0.12.0-beta_older"), FileTime.fromMillis(1_000L));
		Files.setLastModifiedTime(translations.resolve("0.12.0-beta"), FileTime.fromMillis(3_000L));
		Files.setLastModifiedTime(translations.resolve("0.12.0-betaX_new"), FileTime.fromMillis(9_000L));

		// The plus of a version with a commit reaches the disk as an underscore.
		TranslationCache.install(this.root, "0.12.0-beta+abc", "0.12.0-beta");

		// betaX begins with the family and is another version: the separator is what keeps it out.
		assertEquals(List.of("0.12.0-beta_abc", "0.12.0-beta_old"), names(translations));
	}

	/**
	 * What another edition left is nothing this build reads, so a folder of it that cannot be
	 * emptied costs its disk and is named, and the cache is on all the same.
	 */
	@Test
	void oneFolderThatCannotBeDeletedLeavesTheCacheOnAndIsNamed() throws IOException {
		Path translations = this.root.resolve("translations");
		Path stuck = Files.createDirectories(translations.resolve("0.10.0-beta"));
		Files.writeString(stuck.resolve("x.tr"), "old");
		try {
			Files.setPosixFilePermissions(stuck, PosixFilePermissions.fromString("r-xr-xr-x"));
		} catch (UnsupportedOperationException e) {
			assumeTrue(false, "no POSIX permissions here");
		}

		try {
			assumeTrue(!Files.isWritable(stuck), "a user who can write anywhere cannot be refused");
			TranslationCache.install(this.root, "0.12.0-beta", "0.12.0-beta");

			assertTrue(TranslationCache.installed(), TranslationCache.problem());
			assertTrue(Files.exists(stuck.resolve("x.tr")));
			assertTrue(TranslationCache.problem().contains("0.10.0-beta"), TranslationCache.problem());
		} finally {
			Files.setPosixFilePermissions(stuck, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	void installStampsTheEditionSoARunThatOnlyHitsIsNotTakenForAbandoned() throws IOException {
		Files.createDirectories(store());
		Files.setLastModifiedTime(store(), FileTime.fromMillis(1_000L));

		install();

		assertTrue(Files.getLastModifiedTime(store()).toMillis() > 1_000L);
	}
}
