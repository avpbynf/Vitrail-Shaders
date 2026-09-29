package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the translation cache to one rule at install: only this edition's own directory decides
 * whether the cache is on, and what another edition left, or a dead neighbour of this one, never
 * does. What stays is named in {@link TranslationCache#problem}, the folder of another edition and
 * the dead neighbour alike.
 * <p>
 * A folder whose write permission is taken away is how a file nothing may delete is planted, the
 * POSIX shape of what a scanner or an indexer holding a file does on Windows. The permission is put
 * back in a {@code finally}, so the folder JUnit made can still be cleared after the test.
 * <p>
 * An install leaves the cache standing at the folder JUnit is about to delete, so the statics are
 * put back around each test as {@link TranslationCacheTest} does.
 */
class TranslationCacheStaleEditionTest {

	private static final String FAMILY = "0.13.0_mc26.2";

	@TempDir
	Path temp;

	@BeforeEach
	void startWithTheStaticsAsTheClassLoadLeftThem() throws ReflectiveOperationException {
		TranslationCacheTest.putTheStaticsBack();
	}

	@AfterEach
	void leaveTheStaticsAsTheClassLoadLeftThem() throws ReflectiveOperationException {
		TranslationCacheTest.putTheStaticsBack();
	}

	@Test
	void installsPastAnOtherEditionThatWillNotGo() throws IOException {
		Path root = Files.createDirectories(temp.resolve("translations"));
		Path gone = Files.createDirectories(root.resolve("0.11.0_mc26.2"));
		Files.writeString(gone.resolve("a.tr"), "old");

		Path locked = Files.createDirectories(root.resolve("0.12.0_mc26.2").resolve("locked"));
		Path held = Files.writeString(locked.resolve("b.tr"), "held");

		assertTrue(locked.toFile().setWritable(false), "the planted folder could not be locked");
		try {
			// Root deletes whatever it likes, so there a refusal cannot be planted this way.
			assumeFalse(Files.isWritable(locked), "a folder without write permission is still "
					+ "writable here, as it is to root");

			TranslationCache.install(temp, FAMILY, FAMILY);

			assertTrue(TranslationCache.installed(),
					"the cache is off: " + TranslationCache.problem());
			assertFalse(Files.exists(gone), "an edition that could go was left behind");
			assertTrue(Files.exists(held), "the planted refusal did not refuse");
			assertTrue(TranslationCache.problem().contains("0.12.0_mc26.2"),
					"the folder left behind is not named: " + TranslationCache.problem());
		} finally {
			locked.toFile().setWritable(true);
		}
	}

	@Test
	void installsPastADeadNeighbourThatWillNotGoAndNamesIt() throws IOException {
		Path mine = Files.createDirectories(temp.resolve("translations").resolve(FAMILY));
		Path part = Files.writeString(mine.resolve("key-1.part"), "half");

		assertTrue(mine.toFile().setWritable(false), "the planted folder could not be locked");
		try {
			assumeFalse(Files.isWritable(mine), "a folder without write permission is still "
					+ "writable here, as it is to root");

			TranslationCache.install(temp, FAMILY, FAMILY);

			assertTrue(TranslationCache.installed(),
					"the cache is off: " + TranslationCache.problem());
			assertTrue(Files.exists(part), "the planted refusal did not refuse");
			// The refusal quotes the path again, so the name is told by the bracket that follows it.
			String named = FAMILY + "/key-1.part (";
			String said = TranslationCache.problem();
			assertTrue(said.contains(named), "the dead neighbour left behind is not named: " + said);
			assertEquals(said.indexOf(named), said.lastIndexOf(named),
					"the dead neighbour is named more than once: " + said);
		} finally {
			mine.toFile().setWritable(true);
		}

		// What was refused is tried again by the next install, and said no more once it went.
		TranslationCache.install(temp, FAMILY, FAMILY);

		assertTrue(TranslationCache.installed(), "the cache is off: " + TranslationCache.problem());
		assertFalse(Files.exists(part), "the next install left the dead neighbour");
		assertEquals("", TranslationCache.problem());
	}

	@Test
	void namesADeadNeighbourAndAnOtherEditionThatWillNotGoInOneReport() throws IOException {
		Path root = Files.createDirectories(temp.resolve("translations"));
		Path mine = Files.createDirectories(root.resolve(FAMILY));
		Path part = Files.writeString(mine.resolve("key-1.part"), "half");

		Path locked = Files.createDirectories(root.resolve("0.12.0_mc26.2").resolve("locked"));
		Path held = Files.writeString(locked.resolve("b.tr"), "held");

		assertTrue(mine.toFile().setWritable(false), "the planted folder could not be locked");
		assertTrue(locked.toFile().setWritable(false), "the planted folder could not be locked");
		try {
			assumeFalse(Files.isWritable(mine) || Files.isWritable(locked), "a folder without write "
					+ "permission is still writable here, as it is to root");

			TranslationCache.install(temp, FAMILY, FAMILY);

			assertTrue(TranslationCache.installed(),
					"the cache is off: " + TranslationCache.problem());
			assertTrue(Files.exists(part) && Files.exists(held), "a planted refusal did not refuse");
			String said = TranslationCache.problem();
			assertTrue(said.contains("0.12.0_mc26.2") && said.contains(FAMILY + "/key-1.part ("),
					"what stayed is not all named: " + said);
		} finally {
			mine.toFile().setWritable(true);
			locked.toFile().setWritable(true);
		}
	}
}
