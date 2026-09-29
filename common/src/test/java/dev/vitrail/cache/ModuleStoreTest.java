package dev.vitrail.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the write half of the module cache on both games, which {@link ModuleCacheTest} can only
 * reach on 26.3: 26.2 stores a game module through a mixin accessor that exists only in a running
 * game, so no test can hand its {@code store} a module. What {@code store} does after it has
 * described one is shared, and is called here directly with the bytes a module describes to, and
 * read back through the game's own {@code lookup}.
 */
class ModuleStoreTest {

	private static final long MIB = ModuleCacheRig.MIB;

	private static final String SOURCE = "#version 460\nvoid main() {\n}\n";

	@TempDir
	Path temp;

	/** The shared write, reached the way nothing but a test reaches it. */
	private static void keep(ModuleCacheRig rig, String key, byte[] raw) throws ReflectiveOperationException {
		Class<?> store = rig.cacheClass().getClassLoader().loadClass("dev.vitrail.cache.ModuleStore");
		Method keep = store.getDeclaredMethod("keep", Path.class, String.class, byte[].class);
		keep.setAccessible(true);
		try {
			keep.invoke(null, rig.edition(), key, raw);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e.getCause());
		}
	}

	private static byte[] body(ModuleCacheRig rig, byte[] words) {
		return ModuleFixtures.body(words, ModuleFixtures.emptyFor(rig.game263()));
	}

	@Test
	void aKeptUnitIsTheDocumentedFileAndIsServedBackByTheGamesOwnLookup() throws Exception {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			byte[] words = ModuleFixtures.words(40, 21);
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			keep(rig, key, body(rig, words));

			assertArrayEquals(ModuleFixtures.sealed(body(rig, words)), Files.readAllBytes(rig.unit(key)));
			try (Stream<Path> found = Files.list(rig.edition())) {
				assertEquals(List.of(key + ".mod"), found.map(p -> p.getFileName().toString()).toList());
			}

			Object served = rig.lookup(key);
			assertNotNull(served, () -> rig.warnings().toString());
			try {
				assertArrayEquals(words, rig.words(served));
			} finally {
				rig.release(served);
			}

			byte[] second = ModuleFixtures.words(12, 22);
			keep(rig, key, body(rig, second));
			assertArrayEquals(ModuleFixtures.sealed(body(rig, second)), Files.readAllBytes(rig.unit(key)));
			assertEquals(List.of(), rig.warnings());
		}
	}

	@Test
	void aKeepOverTheCeilingDropsTheOldestAndKeepsWhatItJustWrote() throws Exception {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Path edition = Files.createDirectories(rig.edition());
			Files.createDirectories(rig.ceilingFile().getParent());
			Files.writeString(rig.ceilingFile(), "128\n", StandardCharsets.UTF_8);
			ModuleFixtures.sparse(edition.resolve("old.mod"), 128 * MIB - 10L, 1);

			String key = rig.keyOf(SOURCE, "fragment", "", true);
			keep(rig, key, body(rig, ModuleFixtures.words(16, 3)));

			assertFalse(Files.exists(edition.resolve("old.mod")), "the oldest unit outlived the sweep");
			assertTrue(Files.exists(rig.unit(key)), "the sweep took the unit it was called for");
		}
	}

	@Test
	void aSweepAKeepStartedThatCannotFinishStandsBackUntilTheCeilingIsSetAgain() throws Exception {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Path edition = Files.createDirectories(rig.edition());
			Files.createDirectories(rig.ceilingFile().getParent());
			Files.writeString(rig.ceilingFile(), "128\n", StandardCharsets.UTF_8);
			// The oldest thing there is a folder with something in it, which nothing here may delete.
			Path stuck = Files.createDirectories(edition.resolve("stuck.mod"));
			Path held = Files.write(stuck.resolve("held"), new byte[] {1});
			ModuleFixtures.stamp(stuck, 1);
			ModuleFixtures.sparse(edition.resolve("big1.mod"), 100 * MIB, 2);
			ModuleFixtures.sparse(edition.resolve("big2.mod"), 100 * MIB, 3);

			String key = rig.keyOf(SOURCE, "fragment", "", true);
			keep(rig, key, body(rig, ModuleFixtures.words(16, 3)));

			List<String> said = rig.warnings();
			assertEquals(1, said.size(), said.toString());
			assertTrue(said.get(0).startsWith("In the module cache, the cache could not be swept: "), said.get(0));
			assertTrue(Files.exists(edition.resolve("big1.mod")));

			Files.delete(held);
			Files.delete(stuck);
			String other = rig.keyOf(SOURCE + "\n", "fragment", "", true);
			keep(rig, other, body(rig, ModuleFixtures.words(16, 4)));

			assertTrue(Files.exists(edition.resolve("big1.mod")), "a keep swept inside the backoff");
			assertTrue(Files.exists(edition.resolve("big2.mod")), "a keep swept inside the backoff");

			rig.setCeilingMib(128);

			assertFalse(Files.exists(edition.resolve("big1.mod")), "setting the ceiling did not sweep");
			assertFalse(Files.exists(edition.resolve("big2.mod")), "setting the ceiling did not sweep");
			assertTrue(Files.exists(rig.unit(key)));
			assertTrue(Files.exists(rig.unit(other)));
		}
	}

	@Test
	void aKeepThatCannotLandIsSaidOnceAndLeavesNoNeighbour() throws Exception {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			String key = rig.keyOf(SOURCE, "fragment", "", true);
			Path blocked = Files.createDirectories(rig.unit(key));
			Files.write(blocked.resolve("held"), new byte[] {1});

			keep(rig, key, body(rig, ModuleFixtures.words(8, 1)));
			keep(rig, key, body(rig, ModuleFixtures.words(8, 1)));

			List<String> said = rig.warnings();
			assertEquals(1, said.size(), said.toString());
			assertTrue(said.get(0).startsWith("In the module cache, a module could not be stored: "), said.get(0));
			try (Stream<Path> found = Files.list(rig.edition())) {
				assertEquals(List.of(key + ".mod"), found.map(p -> p.getFileName().toString()).toList());
			}
		}
	}
}
