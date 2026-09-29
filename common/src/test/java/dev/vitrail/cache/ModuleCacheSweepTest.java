package dev.vitrail.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the module cache's bounds to what they promise: the ceiling the Sodium slider writes is
 * snapped, kept and read back; a store over it drops the units nothing has asked for lately, oldest
 * stamp first, down to three quarters of it; and what the cache says about a load is said in the
 * words it has always used.
 * <p>
 * The smallest ceiling the class offers is 128 MiB, which no test may write. A unit that is big to
 * the cache is a sparse file instead: the cache measures {@link Files#size}, and such a file takes
 * no disk. Every stamp is an exact number of seconds after a fixed instant, never the clock.
 */
class ModuleCacheSweepTest {

	private static final long MIB = ModuleCacheRig.MIB;

	private static final String SOURCE = "#version 460\nvoid main() {\n}\n";

	@TempDir
	Path temp;

	private static Path edition(ModuleCacheRig rig) throws IOException {
		return Files.createDirectories(rig.edition());
	}

	// -- the ceiling --------------------------------------------------------------------------

	@Test
	void theCeilingIsSnappedToItsStepsKeptInAFileAndReadBackByTheNextRun() throws IOException {
		int[][] table = {
			{0, 128}, {128, 128}, {191, 128}, {192, 256}, {255, 256}, {300, 256}, {319, 256}, {320, 384},
			{1983, 1920}, {1984, 2048}, {2048, 2048}, {5000, 2048}, {-5, 128},
			{Integer.MIN_VALUE, 128}, {Integer.MAX_VALUE, 2048},
		};

		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assertEquals(512, rig.ceilingMib(), "with no file the ceiling is the default");
			assertFalse(Files.exists(rig.ceilingFile()), "asking made a file");

			for (int[] row : table) {
				rig.setCeilingMib(row[0]);

				assertEquals(row[1], rig.ceilingMib(), "asked for " + row[0]);
				assertEquals(row[1] + "\n", Files.readString(rig.ceilingFile(), StandardCharsets.UTF_8));
			}

			rig.setCeilingMib(300);
		}

		try (ModuleCacheRig next = new ModuleCacheRig(this.temp)) {
			assertEquals(256, next.ceilingMib());
		}
	}

	@Test
	void aCeilingFileIsSnappedAndAGarbledOneIsTheDefaultAndSaidOnce() throws IOException {
		String[] contents = {"64", "99999", "  384 \n", "200"};
		int[] expected = {128, 2048, 384, 256};

		for (int i = 0; i < contents.length; i++) {
			try (ModuleCacheRig rig = new ModuleCacheRig(Files.createDirectories(this.temp.resolve("f" + i)))) {
				Files.createDirectories(rig.ceilingFile().getParent());
				Files.writeString(rig.ceilingFile(), contents[i], StandardCharsets.UTF_8);

				assertEquals(expected[i], rig.ceilingMib(), contents[i]);
				assertEquals(List.of(), rig.warnings());
			}
		}

		for (String garbage : new String[] {"lots", ""}) {
			try (ModuleCacheRig rig = new ModuleCacheRig(Files.createDirectories(this.temp.resolve("g" + garbage.length())))) {
				Files.createDirectories(rig.ceilingFile().getParent());
				Files.writeString(rig.ceilingFile(), garbage, StandardCharsets.UTF_8);

				assertEquals(512, rig.ceilingMib());
				assertEquals(512, rig.ceilingMib());
				assertEquals(List.of("vitrail/module-cache-ceiling.txt is not a size in mebibytes, so the "
						+ "default 512 is used"), rig.warnings());
			}
		}
	}

	// -- the sweep ----------------------------------------------------------------------------

	@Test
	void loweringTheCeilingDropsTheOldestFirstDownToThreeQuartersOfIt() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Path edition = edition(rig);
			String[] names = {"a.mod", "b.mod", "c.mod", "d.mod", "e.mod"};
			// Written newest first, so the order on disk and the order of the stamps disagree.
			for (int i = names.length - 1; i >= 0; i--) {
				ModuleFixtures.sparse(edition.resolve(names[i]), 40 * MIB, 10 * (i + 1));
			}

			assertNotNull(rig.keyOf(SOURCE, "fragment", "", true));
			rig.setCeilingMib(128);

			// 200 MiB over a ceiling of 128 goes to the first total at or under 96: three units out.
			for (int i = 0; i < 3; i++) {
				assertFalse(Files.exists(edition.resolve(names[i])), names[i] + " outlived the sweep");
			}

			assertTrue(Files.exists(edition.resolve("d.mod")));
			assertTrue(Files.exists(edition.resolve("e.mod")));
			assertTrue(rig.log().contains("INFO The module cache went over its ceiling, so the units nothing "
					+ "has asked for lately were dropped, 80.0 MB left"), rig.log().toString());
		}
	}

	@Test
	void theSweepStopsAtTheTargetItselfAndNotOneUnitPast() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Path edition = edition(rig);
			long[] sizes = {33, 32, 32, 32};
			for (int i = 0; i < sizes.length; i++) {
				ModuleFixtures.sparse(edition.resolve("u" + i + ".mod"), sizes[i] * MIB, i + 1);
			}

			assertNotNull(rig.keyOf(SOURCE, "fragment", "", true));
			rig.setCeilingMib(128);

			assertFalse(Files.exists(edition.resolve("u0.mod")));
			for (int i = 1; i < sizes.length; i++) {
				assertTrue(Files.exists(edition.resolve("u" + i + ".mod")), "u" + i + " went past the target");
			}

			assertTrue(rig.log().contains("INFO The module cache went over its ceiling, so the units nothing "
					+ "has asked for lately were dropped, 96.0 MB left"), rig.log().toString());
		}
	}

	@Test
	void aStoreExactlyAtTheCeilingIsLeftAlone() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Path edition = edition(rig);
			for (int i = 0; i < 4; i++) {
				ModuleFixtures.sparse(edition.resolve("u" + i + ".mod"), 32 * MIB, i + 1);
			}

			assertNotNull(rig.keyOf(SOURCE, "fragment", "", true));
			rig.setCeilingMib(128);

			for (int i = 0; i < 4; i++) {
				assertTrue(Files.exists(edition.resolve("u" + i + ".mod")));
			}

			assertEquals(List.of(), rig.warnings());
			assertFalse(rig.log().stream().anyMatch(line -> line.contains("went over its ceiling")));
		}
	}

	@Test
	void theSweepIgnoresANeighbourAndTheOpeningClearsTheDeadOnes() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Path edition = edition(rig);
			ModuleFixtures.sparse(edition.resolve("dead.part"), 100 * MIB, 1);
			ModuleFixtures.sparse(edition.resolve("old.mod"), 100 * MIB, 2);
			ModuleFixtures.sparse(edition.resolve("new.mod"), 60 * MIB, 3);

			assertNotNull(rig.keyOf(SOURCE, "fragment", "", true));
			assertFalse(Files.exists(edition.resolve("dead.part")), "the opening left a dead neighbour");

			ModuleFixtures.sparse(edition.resolve("live.part"), 100 * MIB, 4);
			rig.setCeilingMib(128);

			// 160 MiB of units, the neighbour uncounted: the oldest goes and 60 is under the target.
			assertFalse(Files.exists(edition.resolve("old.mod")));
			assertTrue(Files.exists(edition.resolve("new.mod")));
			assertTrue(Files.exists(edition.resolve("live.part")), "the sweep deleted a write in flight");
			assertTrue(rig.log().contains("INFO The module cache went over its ceiling, so the units nothing "
					+ "has asked for lately were dropped, 60.0 MB left"), rig.log().toString());
		}
	}

	@Test
	void aUnitThatWasAskedForOutlivesOneThatWasOnlyWritten() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Path edition = edition(rig);
			String key = ModuleFixtures.key(rig.game263(), SOURCE, "fragment", "", true);
			Path asked = Files.write(edition.resolve(key + ".mod"),
					ModuleFixtures.sealed(ModuleFixtures.body(ModuleFixtures.words(8, 1),
							ModuleFixtures.emptyFor(rig.game263()))));
			ModuleFixtures.stamp(asked, 0);
			ModuleFixtures.sparse(edition.resolve("b.mod"), 70 * MIB, 1);
			ModuleFixtures.sparse(edition.resolve("c.mod"), 70 * MIB, 2);

			assertEquals(key, rig.keyOf(SOURCE, "fragment", "", true));
			Object served = rig.lookup(key);
			assertNotNull(served, () -> rig.warnings().toString());
			rig.release(served);
			rig.setCeilingMib(128);

			assertTrue(Files.exists(asked), "the unit that was just asked for went first");
			assertFalse(Files.exists(edition.resolve("b.mod")));
			assertTrue(Files.exists(edition.resolve("c.mod")));
		}
	}

	@Test
	void aStoreOverTheCeilingDropsTheOldestAndKeepsWhatItJustWrote() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");

			Path edition = edition(rig);
			Files.createDirectories(rig.ceilingFile().getParent());
			Files.writeString(rig.ceilingFile(), "128\n", StandardCharsets.UTF_8);
			ModuleFixtures.sparse(edition.resolve("old.mod"), 128 * MIB - 10L, 1);

			String key = rig.keyOf(SOURCE, "fragment", "", true);
			rig.store(key, ModuleFixtures.words(16, 3));

			assertFalse(Files.exists(edition.resolve("old.mod")), "the oldest unit outlived the sweep");
			assertTrue(Files.exists(rig.unit(key)), "the sweep took the unit it was called for");
			assertTrue(rig.log().stream().anyMatch(line -> line.startsWith("INFO The module cache went over "
					+ "its ceiling")), rig.log().toString());
		}
	}

	@Test
	void aSweepThatCannotFinishStandsBackUntilTheCeilingIsSetAgain() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			assumeTrue(rig.canStore(), "a game module cannot be made outside the game");

			Path edition = edition(rig);
			Files.createDirectories(rig.ceilingFile().getParent());
			Files.writeString(rig.ceilingFile(), "128\n", StandardCharsets.UTF_8);
			// The oldest thing there is a folder with something in it, which nothing here may delete.
			Path stuck = Files.createDirectories(edition.resolve("stuck.mod"));
			Path held = Files.write(stuck.resolve("held"), new byte[] {1});
			ModuleFixtures.stamp(stuck, 1);
			ModuleFixtures.sparse(edition.resolve("big1.mod"), 100 * MIB, 2);
			ModuleFixtures.sparse(edition.resolve("big2.mod"), 100 * MIB, 3);

			String key = rig.keyOf(SOURCE, "fragment", "", true);
			rig.store(key, ModuleFixtures.words(16, 3));

			List<String> said = rig.warnings();
			assertEquals(1, said.size(), said.toString());
			assertTrue(said.get(0).startsWith("In the module cache, the cache could not be swept: "), said.get(0));
			assertTrue(said.get(0).endsWith(". Said once a run: the next load pays for the compile again and "
					+ "nothing else changes"), said.get(0));
			assertTrue(Files.exists(edition.resolve("big1.mod")));

			Files.delete(held);
			Files.delete(stuck);
			String other = rig.keyOf(SOURCE + "\n", "fragment", "", true);
			rig.store(other, ModuleFixtures.words(16, 4));

			assertTrue(Files.exists(edition.resolve("big1.mod")), "a store swept inside the backoff");
			assertTrue(Files.exists(edition.resolve("big2.mod")), "a store swept inside the backoff");

			rig.setCeilingMib(128);

			assertFalse(Files.exists(edition.resolve("big1.mod")), "setting the ceiling did not sweep");
			assertFalse(Files.exists(edition.resolve("big2.mod")), "setting the ceiling did not sweep");
			assertTrue(Files.exists(rig.unit(key)));
			assertTrue(Files.exists(rig.unit(other)));
		}
	}

	// -- opening the folder -------------------------------------------------------------------

	@Test
	void openingClearsWhatOtherEditionsLeftAndKeepsItsOwn() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Path own = edition(rig);
			Path kept = Files.write(own.resolve("keep.mod"), new byte[] {1, 2, 3});
			Path old = Files.createDirectories(rig.modules().resolve("0.11.0+mc26.2").resolve("deep").resolve("er"));
			Files.write(old.resolve("a.mod"), new byte[] {9});
			Path stray = Files.write(rig.modules().resolve("stray.txt"), new byte[] {9});

			assertNotNull(rig.keyOf(SOURCE, "fragment", "", true));

			assertTrue(Files.exists(kept), "the opening touched its own store");
			assertFalse(Files.exists(rig.modules().resolve("0.11.0+mc26.2")), "an old edition was left");
			assertFalse(Files.exists(stray), "a stray file in the folder of editions was left");
			assertEquals(List.of(), rig.warnings());
		}
	}

	@Test
	void aFileThatWillNotGoIsSaidAtTheOpeningAndTheCacheOpensAnyway() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(this.temp)) {
			Path locked = Files.createDirectories(rig.modules().resolve("0.12.0+mc26.2").resolve("locked"));
			Files.write(locked.resolve("b.mod"), new byte[] {1});

			assertTrue(locked.toFile().setWritable(false), "the planted folder could not be locked");
			try {
				assumeFalse(Files.isWritable(locked), "a folder without write permission is still writable "
						+ "here, as it is to root");

				assertNotNull(rig.keyOf(SOURCE, "fragment", "", true));

				List<String> said = rig.warnings();
				assertEquals(1, said.size(), said.toString());
				assertTrue(said.get(0).startsWith("The module cache could not take away all that an earlier run "
						+ "left in " + rig.modules() + ": 0.12.0+mc26.2 ("), said.get(0));
				assertTrue(said.get(0).endsWith("). This build keeps its own store all the same, and the next "
						+ "launch tries again"), said.get(0));
				assertTrue(Files.isDirectory(rig.edition()));
			} finally {
				locked.toFile().setWritable(true);
			}
		}
	}

	// -- what a load says ---------------------------------------------------------------------

	/** Everything this rig's cache is counting for one load: units served, then units built. */
	private static void load(ModuleCacheRig rig, int hits, int misses, String prefix, long fillerMib)
			throws IOException {
		Path edition = edition(rig);
		if (fillerMib > 0L) {
			ModuleFixtures.sparse(edition.resolve("filler.mod"), fillerMib * MIB, 1);
		}

		String key = ModuleFixtures.key(rig.game263(), SOURCE, "fragment", "", true);
		Files.write(edition.resolve(key + ".mod"), ModuleFixtures.sealed(ModuleFixtures.body(
				ModuleFixtures.words(8, 1), ModuleFixtures.emptyFor(rig.game263()))));

		assertEquals(key, rig.keyOf(SOURCE, "fragment", "", true));
		for (int i = 0; i < hits; i++) {
			Object served = rig.lookup(key);
			assertNotNull(served, () -> rig.warnings().toString());
			rig.release(served);
		}

		for (int i = 0; i < misses; i++) {
			rig.building(prefix + i);
		}
	}

	/**
	 * The lines of the summaries loads end in, without the ones the engine's passes add beside them.
	 * Every rig listens to the one logger of the mod, so a rig sees the lines of the others alive
	 * with it, which is what lets one list stand for them all.
	 */
	private static List<String> summary(ModuleCacheRig rig, String... namesLines) {
		List<String> lines = new ArrayList<>();
		for (String line : rig.log()) {
			if (line.startsWith("INFO Module cache") || List.of(namesLines).stream().anyMatch(line::startsWith)) {
				lines.add(line);
			}
		}

		return lines;
	}

	@Test
	void theLineAtTheEndOfALoadSaysHitsMissesTotalsSizeAndWhereItIs() throws IOException, InterruptedException {
		try (ModuleCacheRig many = new ModuleCacheRig(Files.createDirectories(this.temp.resolve("many")))) {
			load(many, 1, 14, "n", 40);
			try (ModuleCacheRig few = new ModuleCacheRig(Files.createDirectories(this.temp.resolve("few")))) {
				load(few, 1, 2, "p", 0);
				try (ModuleCacheRig cold = new ModuleCacheRig(Files.createDirectories(this.temp.resolve("cold")))) {
					load(cold, 0, 3, "q", 0);
					try (ModuleCacheRig off = new ModuleCacheRig(Files.createDirectories(this.temp.resolve("off")), false)) {
						off.building("a");
						off.building("b");

						// The compiler has to have been quiet for two seconds before a load is over.
						Thread.sleep(2_200L);
						many.say();
						few.say();
						cold.say();
						off.say();
						few.say();

						assertEquals(List.of(
								"INFO Module cache: 1 units served, 14 built by the compiler, 1 and 14 since this "
										+ "launch, 40.0 MB in " + many.edition(),
								"INFO The 14 built this load begin with: n0, n1, n2, n3, n4, n5, n6, n7, n8, n9, "
										+ "n10, n11",
								"INFO Module cache: 1 units served, 2 built by the compiler, 1 and 2 since this "
										+ "launch, 0.0 MB in " + few.edition(),
								"INFO The 2 built this load are: p0, p1",
								"INFO Module cache: 0 units served, 3 built by the compiler, 0 and 3 since this "
										+ "launch, 0.0 MB in " + cold.edition(),
								"INFO Module cache off, so all 2 units of this load were compiled "
										+ (off.game263() ? "" : "and reflected ") + "(2 since this launch)"),
								summary(many, "INFO The 14 built", "INFO The 2 built", "INFO The 3 built"),
								"a line more means the counts of a load were not taken when it was said, and a "
										+ "line fewer that a load was not said");
					}
				}
			}
		}
	}
}
