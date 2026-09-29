package dev.vitrail.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the module cache to what its documentation promises a caller: the key is the input and
 * nothing else, a stored unit comes back as the words that went in, and anything a digest, a
 * length or a shape does not vouch for is a silent-to-the-pack miss that is said once.
 * <p>
 * Nothing here needs a game or a device. A unit is put on disk by hand, in the layout
 * {@link ModuleFixtures} spells out, and read back through the public surface of a private copy of
 * the cache ({@link ModuleCacheRig}); where the game lets a test call {@code store}, the bytes it
 * writes are compared to that same hand-made layout. The expected texts are typed out in full: a
 * word moved in a warning is a change a player's log shows.
 */
class ModuleCacheTest {

	private static final String SOURCE = "#version 460\nvoid main() {\n\tgl_FragData[0] = vec4(1.0);\n}\n";

	private static final String TAIL = ", so it was compiled instead. Said once a run: nothing about it "
			+ "stops a pack loading";

	@TempDir
	Path temp;

	private static String miss(String what) {
		return "In the module cache, " + what + TAIL;
	}

	/** What a unit that cannot be turned back into a module is said to be, by game. */
	private static String rebuildMiss(ModuleCacheRig rig, String reason) {
		return miss("a stored module could not be " + (rig.game263() ? "read back" : "rebuilt")
				+ " (" + reason + ")");
	}

	private static byte[] file(ModuleCacheRig rig, byte[] words) {
		return ModuleFixtures.sealed(ModuleFixtures.body(words, ModuleFixtures.emptyFor(rig.game263())));
	}

	private ModuleCacheRig rigIn(String name) throws IOException {
		return new ModuleCacheRig(Files.createDirectories(this.temp.resolve(name)));
	}

	// -- the key ------------------------------------------------------------------------------

	@Test
	void theKeyIsTheDigestOfEveryInputEachBehindItsLength() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			String[][] inputs = {
				{SOURCE, "fragment", ""},
				{"", "", ""},
				{"vec3 c = vec3(0.5); // café über 中文 😀\n", "vertex", "#define A 1\n"},
				{"x", "compute-recipe", "#define B\n#define C 2\n"},
			};

			for (String[] input : inputs) {
				for (boolean ours : new boolean[] {true, false}) {
					assertEquals(ModuleFixtures.key(rig.game263(), input[0], input[1], input[2], ours),
							rig.keyOf(input[0], input[1], input[2], ours),
							"the key is not the documented digest for " + input[1] + " ours=" + ours);
				}
			}
		}
	}

	@Test
	void everyIngredientOfTheKeyMovesIt() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Set<String> keys = new HashSet<>();
			keys.add(rig.keyOf(SOURCE, "fragment", "#define A\n", true));
			keys.add(rig.keyOf(SOURCE + " ", "fragment", "#define A\n", true));
			keys.add(rig.keyOf(SOURCE, "vertex", "#define A\n", true));
			keys.add(rig.keyOf(SOURCE, "fragment", "#define A\n", false));
			if (rig.game263()) {
				keys.add(rig.keyOf(SOURCE, "fragment", "#define B\n", true));
			}

			int game = keys.size();
			rig.platformIs("0.14.0", ModuleCacheRig.MINECRAFT, ModuleCacheRig.LOADER, ModuleCacheRig.LOADER_VERSION);
			keys.add(rig.keyOf(SOURCE, "fragment", "#define A\n", true));
			rig.platformIs(ModuleCacheRig.MOD_VERSION, "26.9", ModuleCacheRig.LOADER, ModuleCacheRig.LOADER_VERSION);
			keys.add(rig.keyOf(SOURCE, "fragment", "#define A\n", true));
			rig.platformIs(ModuleCacheRig.MOD_VERSION, ModuleCacheRig.MINECRAFT, "otherloader", ModuleCacheRig.LOADER_VERSION);
			keys.add(rig.keyOf(SOURCE, "fragment", "#define A\n", true));
			rig.platformIs(ModuleCacheRig.MOD_VERSION, ModuleCacheRig.MINECRAFT, ModuleCacheRig.LOADER, "9.9.9");
			keys.add(rig.keyOf(SOURCE, "fragment", "#define A\n", true));

			assertEquals(game + 4, keys.size(), "an ingredient of the key did not move it");
			assertEquals(rig.game263() ? 5 : 4, game, "the text, the stage, the bit and the defines are all keyed");
		}
	}

	@Test
	void aBranchCutOffTheVersionDoesNotMoveTheKey() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			rig.platformIs("0.13.0-dev.fix.shadow-band", ModuleCacheRig.MINECRAFT, ModuleCacheRig.LOADER,
					ModuleCacheRig.LOADER_VERSION);
			String onOneBranch = rig.keyOf(SOURCE, "fragment", "", true);
			rig.platformIs("0.13.0-dev.other-branch", ModuleCacheRig.MINECRAFT, ModuleCacheRig.LOADER,
					ModuleCacheRig.LOADER_VERSION);
			String onAnother = rig.keyOf(SOURCE, "fragment", "", true);
			rig.platformIs("0.13.0-beta", ModuleCacheRig.MINECRAFT, ModuleCacheRig.LOADER,
					ModuleCacheRig.LOADER_VERSION);

			assertEquals(onOneBranch, onAnother);
			assertNotEquals(onOneBranch, rig.keyOf(SOURCE, "fragment", "", true));
		}
	}

	@Test
	void twoSplitsOfTheSameCharactersAreTwoKeys() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assertNotEquals(rig.keyOf("c", "ab", "", true), rig.keyOf("bc", "a", "", true));
			assertNotEquals(rig.keyOf("bc", "a", "", true), rig.keyOf("abc", "", "", true));
		}
	}

	@Test
	void theTextOfAUnitAndItsFileAreNamedByTheKeyAlone() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			String key = rig.keyOf(SOURCE, "fragment", "", true);

			assertEquals(64, key.length());
			assertTrue(key.matches("[0-9a-f]{64}"), key);
			assertEquals(rig.edition().resolve(key + ".mod"), rig.unit(key));
			assertTrue(Files.isDirectory(rig.edition()), "the edition's folder is made at the first key");
		}
	}

	@Test
	void the26Dot2KeyForACompileUnitOfTheEnginesOwnIsTheOursKey() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(!rig.game263(), "the two argument key is 26.2's compute road");

			assertEquals(rig.keyOf(SOURCE, "compute", "", true), keyOfTwoArguments(rig));
		}
	}

	private static String keyOfTwoArguments(ModuleCacheRig rig) {
		try {
			return (String) rig.cacheClass().getMethod("keyOf", String.class, String.class).invoke(null, SOURCE,
					"compute");
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void anIncludeOn26Dot3IsNoUnitAtAllButTheFolderIsStillMade() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.game263(), "26.3 fetches an included file itself, so it cannot key the text");

			assertNull(rig.keyOf("#include \"a.glsl\"\nvoid main() {}\n", "fragment", "", true));
			assertTrue(Files.isDirectory(rig.edition()));
			assertNotNull(rig.keyOf("void main() {}\n", "fragment", "", true));
		}
	}

	@Test
	void switchedOffThereIsNoKeyNoServeAndNoFolder() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp, false)) {
			assertNull(rig.keyOf(SOURCE, "fragment", "", true));
			assertNull(rig.lookup("0".repeat(64)));
			if (rig.canStore()) {
				rig.store("0".repeat(64), ModuleFixtures.words(8, 1));
			}

			assertFalse(Files.exists(rig.modules()), "an off cache made its folder");
			assertEquals(List.of(), rig.warnings());
		}
	}

	@Test
	void aFolderThatCannotBeMadeIsNoCacheThisRunAndIsSaidOnce() throws IOException {
		Files.write(this.temp.resolve("vitrail"), new byte[] {1});
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assertNull(rig.keyOf(SOURCE, "fragment", "", true));
			assertNull(rig.keyOf(SOURCE + "2", "fragment", "", true));

			List<String> said = rig.warnings();
			assertEquals(1, said.size(), said.toString());
			assertTrue(said.get(0).startsWith("No module cache this run, so every shader is compiled: "),
					said.get(0));
		}
	}

	// -- serving what is stored ---------------------------------------------------------------

	@Test
	void aStoredUnitIsServedBackAsTheWordsThatWentIn() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			byte[] words = ModuleFixtures.words(96, 11);
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			Files.write(rig.unit(key), file(rig, words));

			Object served = rig.lookup(key);
			assertNotNull(served, () -> rig.warnings().toString());
			try {
				assertArrayEquals(words, rig.words(served));
			} finally {
				rig.release(served);
			}

			assertEquals(List.of(), rig.warnings());
		}
	}

	@Test
	void everyHitIsAnAllocationOfItsOwn() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			byte[] words = ModuleFixtures.words(32, 5);
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			Files.write(rig.unit(key), file(rig, words));

			Object first = rig.lookup(key);
			Object second = rig.lookup(key);
			try {
				assertNotNull(first);
				assertNotNull(second);
				assertNotSame(first, second);
				rig.scribble(first);

				assertArrayEquals(words, rig.words(second), "a rewrite of one hit reached another");
				assertNotEquals(words[0], rig.words(first)[0]);
			} finally {
				rig.release(first);
				rig.release(second);
			}
		}
	}

	@Test
	void aHitStampsTheFileSoTheSweepKeepsWhatIsAskedFor() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			Path unit = Files.write(rig.unit(key), file(rig, ModuleFixtures.words(32, 5)));
			ModuleFixtures.stamp(unit, 0);

			Object served = rig.lookup(key);
			try {
				assertNotNull(served);
			} finally {
				rig.release(served);
			}

			assertTrue(Files.getLastModifiedTime(unit).toInstant()
					.isAfter(ModuleFixtures.LONG_AGO.plus(Duration.ofDays(365))), "the hit left the stamp alone");
		}
	}

	@Test
	void aChangedKeyIsAMissAndAnAbsentUnitIsASilentOne() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			Files.write(rig.unit(key), file(rig, ModuleFixtures.words(32, 5)));

			assertNull(rig.lookup(rig.keyOf(SOURCE + "\n", "fragment", "", true)));
			assertNull(rig.lookup(rig.keyOf(SOURCE, "fragment", "", false)));
			assertNull(rig.lookup(null));
			assertEquals(List.of(), rig.warnings(), "an absent unit is the ordinary case and says nothing");
		}
	}

	// -- what a digest, a length and a shape refuse -------------------------------------------

	@Test
	void aSingleFlippedBitAnywhereInAUnitIsRefusedByItsDigestAndSaidOnce() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			byte[] good = file(rig, ModuleFixtures.words(12, 3));
			String key = rig.keyOf(SOURCE, "fragment", "", true);

			for (int at = 0; at < good.length; at++) {
				byte[] bad = good.clone();
				bad[at] ^= 0x01;
				Files.write(rig.unit(key), bad);

				assertNull(rig.lookup(key), "a unit with byte " + at + " changed was served");
			}

			assertEquals(List.of(miss("a stored module did not answer for its own bytes")), rig.warnings(),
					"the latch is once a run, not once a unit");
		}
	}

	@Test
	void aUnitCutAnywhereIsRefusedAndAWholeOneStillServes() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			byte[] good = file(rig, ModuleFixtures.words(12, 3));
			String key = rig.keyOf(SOURCE, "fragment", "", true);

			for (int cut = 0; cut < good.length; cut++) {
				Files.write(rig.unit(key), Arrays.copyOf(good, cut));

				assertNull(rig.lookup(key), "a unit cut to " + cut + " bytes was served");
			}

			Files.write(rig.unit(key), good);
			Object served = rig.lookup(key);
			try {
				assertNotNull(served);
			} finally {
				rig.release(served);
			}
		}
	}

	@Test
	void aUnitLargerThanAnyModuleIsRefusedUnread() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			try (RandomAccessFile raf = new RandomAccessFile(rig.unit(key).toFile(), "rw")) {
				raf.setLength(64L * ModuleCacheRig.MIB + 1L);
			}

			assertNull(rig.lookup(key));
			assertEquals(List.of(miss("a stored module is larger than any module is")), rig.warnings());
		}
	}

	@Test
	void oneLatchAnswersForEveryKindOfRefusal() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			Files.write(rig.unit(key), new byte[] {1, 2, 3});
			assertNull(rig.lookup(key));
			try (RandomAccessFile raf = new RandomAccessFile(rig.unit(key).toFile(), "rw")) {
				raf.setLength(64L * ModuleCacheRig.MIB + 1L);
			}

			assertNull(rig.lookup(key));
			assertEquals(List.of(miss("a stored module did not answer for its own bytes")), rig.warnings());
		}
	}

	@Test
	void aUnitThatVouchesForItselfButClaimsAShapeItCannotHaveIsRefusedWithItsReason() throws IOException {
		byte[] header = ModuleFixtures.words(12, 3);
		byte[] wrongMagic = header.clone();
		wrongMagic[0] ^= 0x10;

		String[] names = {"tooShort", "notWholeWords", "moreThanTheFile", "wrongMagic"};
		byte[][] bodies = {
			claiming(16, Arrays.copyOf(header, 16)),
			claiming(22, Arrays.copyOf(header, 22)),
			claiming(4096, Arrays.copyOf(header, 20)),
			ModuleFixtures.body(wrongMagic, ModuleFixtures.EMPTY),
		};
		String[] reasons = {
			"java.io.IOException: a stored module claims 16 bytes of SPIR-V",
			"java.io.IOException: a stored module claims 22 bytes of SPIR-V",
			"java.io.IOException: a stored module claims 4096 bytes of SPIR-V",
			"java.io.IOException: a stored module does not open on the SPIR-V magic word",
		};

		for (int i = 0; i < names.length; i++) {
			try (ModuleCacheRig rig = rigIn(names[i])) {
				String key = rig.keyOf(SOURCE, "fragment", "", true);
				byte[] body = bodies[i];
				if (rig.game263() && i == 3) {
					body = ModuleFixtures.body(wrongMagic, ModuleFixtures.NONE);
				}

				Files.write(rig.unit(key), ModuleFixtures.sealed(body));

				assertNull(rig.lookup(key), names[i]);
				assertEquals(List.of(rebuildMiss(rig, reasons[i])), rig.warnings(), names[i]);
			}
		}
	}

	/** A body whose first int says {@code claimed} bytes of words and then holds {@code held}. */
	private static byte[] claiming(int claimed, byte[] held) {
		ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + held.length + 16);
		buffer.putInt(claimed).put(held);

		return buffer.array();
	}

	@Test
	void aUnitOfOnlyItsDigestIsRefusedAsNothingBehindIt() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			Files.write(rig.unit(key), ModuleFixtures.sealed(new byte[0]));

			assertNull(rig.lookup(key));
			assertEquals(List.of(miss("a stored module did not answer for its own bytes")), rig.warnings());
		}
	}

	// -- 26.2: what the game's module carries beside its words ----------------------------------

	@Test
	void the26Dot2TablesComeBackAsTheModuleTheGameMade() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(!rig.game263(), "26.3 keeps the words alone");

			byte[] words = ModuleFixtures.words(24, 9);
			ModuleFixtures.Tables tables = out -> {
				out.writeInt(2);
				out.writeUTF("DynamicTransforms");
				out.writeInt(3);
				out.writeUTF("Fog");
				out.writeInt(4);
				out.writeInt(1);
				out.writeUTF("Sampler0");
				out.writeInt(7);
				out.writeInt(2);
				out.writeInt(1);
				out.writeUTF("fragColor");
				out.writeInt(0);
				out.writeInt(2);
				out.writeUTF("Position");
				out.writeInt(0);
				out.writeUTF("UV0");
				out.writeInt(1);
			};
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			Files.write(rig.unit(key), ModuleFixtures.sealed(ModuleFixtures.body(words, tables)));

			Object module = rig.lookup(key);
			assertNotNull(module, () -> rig.warnings().toString());
			try {
				assertArrayEquals(words, rig.words(module));
				assertEquals("pack/1/test", rig.component(module, "name"));
				assertEquals("[SpvUniformBuffer[name=DynamicTransforms, bindingOffset=3], "
						+ "SpvUniformBuffer[name=Fog, bindingOffset=4]]", rig.component(module, "uniformBuffers"));
				assertEquals("[SpvSampler[name=Sampler0, bindingOffset=7, dimensions=2]]",
						rig.component(module, "samplers"));
				assertEquals("[SpvVariable[name=fragColor, locationOffset=0]]", rig.component(module, "outputs"));
				assertEquals("[SpvVariable[name=Position, locationOffset=0], "
						+ "SpvVariable[name=UV0, locationOffset=1]]", rig.component(module, "inputs"));
			} finally {
				rig.release(module);
			}
		}
	}

	@Test
	void the26Dot2TablesAreHeldToTheirMostAndToTheirLength() throws IOException {
		try (ModuleCacheRig probe = new ModuleCacheRig(this.temp)) {
			assumeTrue(!probe.game263(), "26.3 keeps the words alone");
		}

		byte[] words = ModuleFixtures.words(12, 3);
		String[] names = {"overTheMost", "negative", "cutShort"};
		ModuleFixtures.Tables[] tables = {
			out -> out.writeInt(65_537),
			out -> out.writeInt(-1),
			out -> {
				out.writeInt(1);
				out.writeUTF("Fog");
			},
		};
		String[] reasons = {
			"java.io.IOException: a stored module claims 65537 entries of one kind",
			"java.io.IOException: a stored module claims -1 entries of one kind",
			"java.io.EOFException",
		};

		for (int i = 0; i < names.length; i++) {
			try (ModuleCacheRig rig = rigIn(names[i])) {
				String key = rig.keyOf(SOURCE, "fragment", "", true);
				Files.write(rig.unit(key), ModuleFixtures.sealed(ModuleFixtures.body(words, tables[i])));

				assertNull(rig.lookup(key), names[i]);
				assertEquals(List.of(rebuildMiss(rig, reasons[i])), rig.warnings(), names[i]);
			}
		}

		try (ModuleCacheRig rig = rigIn("atTheMost")) {
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			ModuleFixtures.Tables most = out -> {
				out.writeInt(65_536);
				for (int entry = 0; entry < 65_536; entry++) {
					out.writeUTF("b");
					out.writeInt(entry);
				}

				out.writeInt(0);
				out.writeInt(0);
				out.writeInt(0);
			};
			Files.write(rig.unit(key), ModuleFixtures.sealed(ModuleFixtures.body(words, most)));

			Object module = rig.lookup(key);
			try {
				assertNotNull(module, () -> rig.warnings().toString());
			} finally {
				if (module != null) {
					rig.release(module);
				}
			}
		}
	}

	// -- 26.3: keeping the words --------------------------------------------------------------

	@Test
	void storeWritesTheDocumentedFileAndLeavesNoNeighbourBehind() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");

			byte[] words = ModuleFixtures.words(64, 7);
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			rig.store(key, words);

			byte[] expected = ModuleFixtures.sealed(ModuleFixtures.body(words, ModuleFixtures.NONE));
			assertArrayEquals(expected, Files.readAllBytes(rig.unit(key)));
			try (Stream<Path> found = Files.list(rig.edition())) {
				assertEquals(List.of(key + ".mod"), found.map(p -> p.getFileName().toString()).toList());
			}

			Object served = rig.lookup(key);
			try {
				assertNotNull(served);
				assertArrayEquals(words, rig.words(served));
			} finally {
				rig.release(served);
			}
		}
	}

	@Test
	void theFileAFixedModuleMakesIsByteForByteWhatEveryEarlierBuildWrote() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");
			assumeTrue(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN, "the words are native order");

			byte[] words = ModuleFixtures.words(64, 7);
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			rig.store(key, words);

			try {
				assertEquals(GOLDEN_FILE_SHA256, ModuleFixtures.sha256Hex(Files.readAllBytes(rig.unit(key))));
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/**
	 * SHA-256 of the file the 26.3 cache wrote for {@code words(64, 7)}, read off the two separate
	 * copies of the class as they stood before they shared a line. Nothing in it depends on the
	 * environment, unlike a key, which carries the commit of a development build.
	 */
	private static final String GOLDEN_FILE_SHA256 =
			"6f4dbcc8e7511e93125accc1bacd4cd8b9fc3f78ed2e6a3a0137f29b17e9ac3a";

	@Test
	void storeLeavesTheCallersBufferWhereItWasAndKeepsWhatRemains() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");

			byte[] words = ModuleFixtures.words(16, 2);
			ByteBuffer buffer = ByteBuffer.allocateDirect(words.length);
			buffer.put(words).position(8);
			buffer.limit(words.length - 4);
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			rig.store(key, buffer);

			assertEquals(8, buffer.position());
			assertEquals(words.length - 4, buffer.limit());
			byte[] kept = Arrays.copyOfRange(words, 8, words.length - 4);
			assertArrayEquals(ModuleFixtures.sealed(ModuleFixtures.body(kept, ModuleFixtures.NONE)),
					Files.readAllBytes(rig.unit(key)));
		}
	}

	@Test
	void storeWithNoKeyWritesNothingAndAKeyStoredTwiceIsOneFile() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");

			String key = rig.keyOf(SOURCE, "fragment", "", true);
			rig.store(null, ModuleFixtures.words(8, 1));
			try (Stream<Path> found = Files.list(rig.edition())) {
				assertEquals(0L, found.count());
			}

			rig.store(key, ModuleFixtures.words(8, 1));
			byte[] second = ModuleFixtures.words(10, 2);
			rig.store(key, second);

			assertArrayEquals(ModuleFixtures.sealed(ModuleFixtures.body(second, ModuleFixtures.NONE)),
					Files.readAllBytes(rig.unit(key)));
			try (Stream<Path> found = Files.list(rig.edition())) {
				assertEquals(1L, found.count());
			}
		}
	}

	@Test
	void aWriteThatCannotLandIsSaidOnceAndLeavesNoNeighbour() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");

			String key = rig.keyOf(SOURCE, "fragment", "", true);
			Path blocked = Files.createDirectories(rig.unit(key));
			Files.write(blocked.resolve("held"), new byte[] {1});

			rig.store(key, ModuleFixtures.words(8, 1));
			rig.store(key, ModuleFixtures.words(8, 1));

			List<String> said = rig.warnings();
			assertEquals(1, said.size(), said.toString());
			assertTrue(said.get(0).startsWith("In the module cache, a module could not be stored: "), said.get(0));
			assertTrue(said.get(0).endsWith(". Said once a run: the next load pays for the compile again and "
					+ "nothing else changes"), said.get(0));
			try (Stream<Path> found = Files.list(rig.edition())) {
				assertEquals(List.of(key + ".mod"), found.map(p -> p.getFileName().toString()).toList());
			}
		}
	}
}
