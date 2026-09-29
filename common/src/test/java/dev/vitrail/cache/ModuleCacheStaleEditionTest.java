package dev.vitrail.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the module cache's clearing of other editions to what it is: a courtesy to the disk, which
 * a file that will not go can make incomplete and never make fatal. What stays is named in the one
 * warning the opening says, the folder of another edition and a dead {@code .part} of this one
 * alike.
 * <p>
 * A folder whose write permission is taken away is how a refusal is planted, a file inside it then
 * being one nothing may delete. That is the POSIX shape of what a scanner or an indexer holding a
 * file does on Windows. The permission is put back in a {@code finally}, so the folder JUnit made
 * can still be cleared after the test.
 */
class ModuleCacheStaleEditionTest {

	private static final String FAMILY = "0.13.0+mc26.2";

	private static final String SOURCE = "#version 460\nvoid main() {\n}\n";

	@TempDir
	Path temp;

	@Test
	void clearsEveryOtherEditionItCanAndNamesTheOneThatStays() throws IOException {
		Path root = Files.createDirectories(temp.resolve("modules"));
		Path mine = Files.createDirectories(root.resolve(FAMILY));
		Files.writeString(mine.resolve("kept.mod"), "mine");

		Path gone = Files.createDirectories(root.resolve("0.11.0+mc26.2"));
		Files.writeString(gone.resolve("a.mod"), "old");

		Path stuck = Files.createDirectories(root.resolve("0.12.0+mc26.2"));
		Path locked = Files.createDirectories(stuck.resolve("locked"));
		Path held = Files.writeString(locked.resolve("b.mod"), "held");

		assertTrue(locked.toFile().setWritable(false), "the planted folder could not be locked");
		try {
			// Root deletes whatever it likes, so there a refusal cannot be planted this way.
			assumeFalse(Files.isWritable(locked), "a folder without write permission is still "
					+ "writable here, as it is to root");

			String left = ModuleCache.dropOtherEditions(root, mine, FAMILY);

			assertTrue(Files.exists(mine.resolve("kept.mod")),
					"this edition's own store was touched");
			assertFalse(Files.exists(gone), "an edition that could go was left behind");
			assertTrue(Files.exists(held), "the planted refusal did not refuse");
			assertTrue(left.contains("0.12.0+mc26.2"),
					"the folder left behind is not named: " + left);
			assertFalse(left.contains("0.11.0+mc26.2"), "a folder that went is named: " + left);
		} finally {
			locked.toFile().setWritable(true);
		}
	}

	@Test
	void opensPastADeadWriteThatWillNotGoAndNamesIt() throws IOException {
		Path part;
		try (ModuleCacheRig rig = new ModuleCacheRig(temp)) {
			Path mine = Files.createDirectories(rig.edition());
			part = Files.write(mine.resolve("key-1.part"), new byte[] {1});

			assertTrue(mine.toFile().setWritable(false), "the planted folder could not be locked");
			try {
				assumeFalse(Files.isWritable(mine), "a folder without write permission is still "
						+ "writable here, as it is to root");

				assertNotNull(rig.keyOf(SOURCE, "fragment", "", true), "the store is off");

				List<String> said = rig.warnings();
				assertEquals(1, said.size(), said.toString());
				// The refusal quotes the path again, so the name is told by the bracket that follows it.
				String named = mine.getFileName() + "/key-1.part (";
				assertTrue(said.get(0).contains(named), "the dead write left behind is not named: " + said);
				assertEquals(said.get(0).indexOf(named), said.get(0).lastIndexOf(named),
						"the dead write is named more than once: " + said);
				assertTrue(Files.exists(part), "the planted refusal did not refuse");
			} finally {
				mine.toFile().setWritable(true);
			}
		}

		// What was refused is tried again by the next opening, and said no more once it went.
		try (ModuleCacheRig rig = new ModuleCacheRig(temp)) {
			assertNotNull(rig.keyOf(SOURCE, "fragment", "", true), "the store is off");

			assertFalse(Files.exists(part), "the next opening left the dead write");
			assertEquals(List.of(), rig.warnings());
		}
	}

	@Test
	void namesADeadWriteAndAnOtherEditionThatWillNotGoInOneWarning() throws IOException {
		try (ModuleCacheRig rig = new ModuleCacheRig(temp)) {
			Path mine = Files.createDirectories(rig.edition());
			Path part = Files.write(mine.resolve("key-1.part"), new byte[] {1});
			Path locked = Files.createDirectories(rig.modules().resolve("0.12.0+mc26.2").resolve("locked"));
			Path held = Files.write(locked.resolve("b.mod"), new byte[] {1});

			assertTrue(mine.toFile().setWritable(false), "the planted folder could not be locked");
			assertTrue(locked.toFile().setWritable(false), "the planted folder could not be locked");
			try {
				assumeFalse(Files.isWritable(mine) || Files.isWritable(locked), "a folder without write "
						+ "permission is still writable here, as it is to root");

				assertNotNull(rig.keyOf(SOURCE, "fragment", "", true), "the store is off");

				List<String> said = rig.warnings();
				assertEquals(1, said.size(), said.toString());
				assertTrue(Files.exists(part) && Files.exists(held), "a planted refusal did not refuse");
				assertTrue(said.get(0).contains("0.12.0+mc26.2 (")
						&& said.get(0).contains(mine.getFileName() + "/key-1.part ("),
						"what stayed is not all named: " + said);
			} finally {
				mine.toFile().setWritable(true);
				locked.toFile().setWritable(true);
			}
		}
	}
}
