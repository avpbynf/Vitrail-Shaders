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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the module cache does for {@link ModuleShare}: the key a unit is shared under, and the units
 * it hands back from the table. The key is held to the recipe {@link ModuleFixtures} spells out
 * independently; the table is filled by hand, through the copy of the class the rig loaded, so that
 * a hit is read on both games and not only where a game module can be made outside the game.
 */
class ModuleCacheShareTest {

	private static final String SOURCE = "#version 460\nvoid main() {\n\tgl_FragData[0] = vec4(1.0);\n}\n";

	@TempDir
	Path temp;

	// -- the key ------------------------------------------------------------------------------

	private static @Nullable String shareKeyOf(ModuleCacheRig rig, String source, String stage,
			String defines, boolean ours) {
		Object[] arguments = rig.game263()
				? new Object[] {source, stage, defines, ours}
				: new Object[] {source, stage, ours};

		return (String) call(rig, "shareKeyOf", arguments);
	}

	@Test
	void theShareKeyIsTheDiskKeyWhereThereIsADisk() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			String[][] units = {
				{SOURCE, "fragment", ""},
				{"", "vertex", ""},
				{"vec3 c = vec3(0.5); // café über 中文 😀\n", "vertex", "#define A 1\n"},
			};

			for (String[] unit : units) {
				for (boolean ours : new boolean[] {true, false}) {
					String key = rig.keyOf(unit[0], unit[1], unit[2], ours);

					assertNotNull(key);
					assertEquals(key, shareKeyOf(rig, unit[0], unit[1], unit[2], ours));
				}
			}
		}
	}

	@Test
	void theShareKeyIsTheDocumentedDigestOfTheInputAndNothingElse() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assertEquals(ModuleFixtures.key(rig.game263(), SOURCE, "fragment", "", true),
					shareKeyOf(rig, SOURCE, "fragment", "", true));
			assertEquals(ModuleFixtures.key(rig.game263(), SOURCE, "vertex", "#define A 1\n", false),
					shareKeyOf(rig, SOURCE, "vertex", "#define A 1\n", false));
		}
	}

	@Test
	void theShareKeyIsMadeWhereThereIsNoDiskAndIsTheKeyADiskWouldHaveMade() {
		String withDisk;
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp.resolve("on"))) {
			withDisk = rig.keyOf(SOURCE, "fragment", "", true);
		}

		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp.resolve("off"), false)) {
			assertNull(rig.keyOf(SOURCE, "fragment", "", true));

			String share = shareKeyOf(rig, SOURCE, "fragment", "", true);

			assertNotNull(share);
			assertEquals(withDisk, share);
			assertFalse(Files.exists(rig.modules()), "making a share key made the cache's folder");
			assertEquals(List.of(), rig.warnings());
		}
	}

	@Test
	void everyInputOfAUnitIsInItsShareKey() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Set<String> keys = new HashSet<>();
			keys.add(shareKeyOf(rig, SOURCE, "fragment", "", true));
			keys.add(shareKeyOf(rig, SOURCE + "\n", "fragment", "", true));
			keys.add(shareKeyOf(rig, SOURCE, "vertex", "", true));
			keys.add(shareKeyOf(rig, SOURCE, "fragment", "", false));
			if (rig.game263()) {
				keys.add(shareKeyOf(rig, SOURCE, "fragment", "#define A 1\n", true));
				keys.add(shareKeyOf(rig, SOURCE, "fragment", "#define B 1\n", true));
			}

			assertEquals(rig.game263() ? 6 : 4, keys.size());
			assertFalse(keys.contains(null));
		}
	}

	@Test
	void anIncludeOn26Dot3HasNoShareKeyToo() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.game263(), "26.3 fetches an included file itself, so it cannot key the text");

			assertNull(shareKeyOf(rig, "#include \"a.glsl\"\nvoid main() {}\n", "fragment", "", true));
			assertNotNull(shareKeyOf(rig, "void main() {}\n", "fragment", "", true));
		}
	}

	// -- the table ----------------------------------------------------------------------------

	/** Puts a unit into the table of the rig's own copy of the class, as {@code keep} would have. */
	private static void plant(ModuleCacheRig rig, String unit, byte[] body) {
		try {
			ClassLoader loader = rig.cacheClass().getClassLoader();
			Class<?> share = Class.forName("dev.vitrail.cache.ModuleShare", true, loader);
			Class<?> blob = Class.forName("dev.vitrail.cache.ModuleShare$Blob", true, loader);
			Constructor<?> make = blob.getDeclaredConstructor(byte[].class, int.class);
			make.setAccessible(true);
			Method offer = share.getDeclaredMethod("offer", String.class, blob);
			offer.setAccessible(true);
			offer.invoke(share.getMethod("load").invoke(null), unit, make.newInstance(body, body.length));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** What the table hands back for a unit: a buffer on 26.3, a game module on 26.2. */
	private static Object shared(ModuleCacheRig rig, String unit) {
		return rig.game263()
				? call(rig, "shared", unit)
				: call(rig, "shared", unit, "vitrail_pack_1_test_vertex");
	}

	private static Object call(ModuleCacheRig rig, String name, Object... arguments) {
		for (Method method : rig.cacheClass().getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
				try {
					return method.invoke(null, arguments);
				} catch (InvocationTargetException e) {
					if (e.getCause() instanceof RuntimeException run) {
						throw run;
					}

					throw new IllegalStateException(e.getCause());
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
		}

		throw new IllegalStateException("no public " + name + " of " + arguments.length + " arguments");
	}

	@Test
	void aUnitNobodyMadeIsNotInTheTable() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assertNull(shared(rig, rig.keyOf(SOURCE, "fragment", "", true)));
			assertNull(shared(rig, null));
		}
	}

	@Test
	void aUnitInTheTableComesBackAsTheWordsThatWentInOnEitherGame() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			byte[] words = ModuleFixtures.words(96, 11);
			String unit = rig.keyOf(SOURCE, "fragment", "", true);
			plant(rig, unit, ModuleFixtures.body(words, ModuleFixtures.emptyFor(rig.game263())));

			Object served = shared(rig, unit);
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
	void everyHitOnTheTableIsAnAllocationOfItsOwn() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			byte[] words = ModuleFixtures.words(32, 5);
			String unit = rig.keyOf(SOURCE, "fragment", "", true);
			plant(rig, unit, ModuleFixtures.body(words, ModuleFixtures.emptyFor(rig.game263())));

			Object first = shared(rig, unit);
			Object second = shared(rig, unit);
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
	void theTableAnswersWhereThereIsNoDiskAndMakesNoFolder() {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp, false)) {
			byte[] words = ModuleFixtures.words(32, 9);
			String unit = shareKeyOf(rig, SOURCE, "fragment", "", true);
			plant(rig, unit, ModuleFixtures.body(words, ModuleFixtures.emptyFor(rig.game263())));

			Object served = shared(rig, unit);
			try {
				assertNotNull(served);
				assertArrayEquals(words, rig.words(served));
			} finally {
				rig.release(served);
			}

			assertFalse(Files.exists(rig.modules()));
		}
	}

	@Test
	void aUnitTheCompilerMadeIsKeptInTheTableAndOnDisk() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");

			byte[] words = ModuleFixtures.words(64, 7);
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			keep(rig, key, key, words);

			assertArrayEquals(ModuleFixtures.sealed(ModuleFixtures.body(words, ModuleFixtures.NONE)),
					Files.readAllBytes(rig.unit(key)));
			Files.delete(rig.unit(key));

			Object served = shared(rig, key);
			try {
				assertNotNull(served);
				assertArrayEquals(words, rig.words(served));
			} finally {
				rig.release(served);
			}
		}
	}

	@Test
	void aUnitKeptOnlyForTheDiskIsNotInTheTable() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");

			byte[] words = ModuleFixtures.words(64, 7);
			String key = rig.keyOf(SOURCE, "fragment", "", false);
			keep(rig, null, key, words);

			assertTrue(Files.exists(rig.unit(key)));
			assertNull(shared(rig, key));
		}
	}

	@Test
	void aUnitKeptOnlyForTheTableWritesNothingToDisk() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");

			byte[] words = ModuleFixtures.words(64, 7);
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			keep(rig, key, null, words);

			try (Stream<Path> found = Files.list(rig.edition())) {
				assertEquals(0L, found.count());
			}

			Object served = shared(rig, key);
			try {
				assertNotNull(served);
				assertArrayEquals(words, rig.words(served));
			} finally {
				rig.release(served);
			}
		}
	}

	@Test
	void keepingNothingUnderNoKeyAtAllDoesNothing() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");

			// A key asked for first, which is what opens the folder there is to be found empty.
			assertNotNull(rig.keyOf(SOURCE, "fragment", "", true));
			keep(rig, null, null, ModuleFixtures.words(8, 1));

			try (Stream<Path> found = Files.list(rig.edition())) {
				assertEquals(0L, found.count());
			}

			assertEquals(List.of(), rig.warnings());
		}
	}

	/** {@code keep} on 26.3, which takes the words: the buffer is made the way the rig makes one. */
	private static void keep(ModuleCacheRig rig, String unit, String key, byte[] words) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(words.length);
		buffer.put(words).flip();
		call(rig, "keep", unit, key, buffer);
	}
}
