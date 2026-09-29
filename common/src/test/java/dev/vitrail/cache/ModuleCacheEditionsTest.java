package dev.vitrail.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the choice of which other editions the module cache clears, and which one it spares, to
 * the reasons its documentation gives.
 * <p>
 * A folder's stamp is what says which edition was used last, so every folder here is stamped an
 * exact number of seconds after a fixed instant once its contents are in, never by the clock.
 * The refusals a file can make are held by {@link ModuleCacheStaleEditionTest}, and what these
 * cases hold is the choosing.
 */
class ModuleCacheEditionsTest {

	private static final String FAMILY = "0.13.0+mc26.2";

	@TempDir
	Path temp;

	/** A folder holding one unit, stamped as used {@code seconds} after the fixed instant. */
	private Path edition(String name, int seconds) throws IOException {
		Path folder = Files.createDirectories(this.temp.resolve(name).resolve("nested"));
		Files.write(folder.resolve("a.mod"), new byte[] {1});

		return ModuleFixtures.stamp(this.temp.resolve(name), seconds);
	}

	@Test
	void aReleaseBuildWhoseEditionIsTheFamilySparesNoNeighbour() throws IOException {
		Path mine = edition(FAMILY, 10);
		Path commit = edition(FAMILY + "+abc1234", 20);
		Path older = edition("0.12.0+mc26.2", 30);

		String left = ModuleCache.dropOtherEditions(this.temp, mine, FAMILY);

		assertEquals("", left);
		assertTrue(Files.exists(mine.resolve("nested").resolve("a.mod")), "its own store was touched");
		assertFalse(Files.exists(commit), "a development build's folder outlived a release");
		assertFalse(Files.exists(older));
	}

	@Test
	void aBuildCarryingACommitSparesTheNewestEditionOfItsFamilyAndOnlyThat() throws IOException {
		Path mine = edition(FAMILY + "+cccc", 5);
		Path older = edition(FAMILY + "+aaaa", 100);
		Path newest = edition(FAMILY + "+bbbb", 200);
		Path whole = edition(FAMILY, 50);
		Path otherVersion = edition("0.12.0+mc26.2", 999);

		String left = ModuleCache.dropOtherEditions(this.temp, mine, FAMILY);

		assertEquals("", left);
		assertTrue(Files.exists(mine));
		assertTrue(Files.exists(newest), "the neighbour used most recently was not spared");
		assertFalse(Files.exists(older));
		assertFalse(Files.exists(whole));
		assertFalse(Files.exists(otherVersion), "the newest folder of all is not the newest of the family");
	}

	@Test
	void theFamilyEntireIsAnEditionOfTheFamilyAndSparedWhenItIsTheNewest() throws IOException {
		Path mine = edition(FAMILY + "+cccc", 5);
		Path whole = edition(FAMILY, 300);
		Path commit = edition(FAMILY + "+aaaa", 100);

		ModuleCache.dropOtherEditions(this.temp, mine, FAMILY);

		assertTrue(Files.exists(whole));
		assertFalse(Files.exists(commit));
	}

	@Test
	void aNameThatOnlyBeginsWithTheFamilyIsAnotherGameAndNotSpared() throws IOException {
		Path mine = edition(FAMILY + "+cccc", 5);
		Path longer = edition("0.13.0+mc26.20", 500);
		Path glued = edition(FAMILY + "x", 400);
		Path sibling = edition(FAMILY + "+aaaa", 100);

		ModuleCache.dropOtherEditions(this.temp, mine, FAMILY);

		assertFalse(Files.exists(longer), "a prefix was taken for an edition of the family");
		assertFalse(Files.exists(glued));
		assertTrue(Files.exists(sibling), "the real sibling was passed over for a newer namesake");
	}

	@Test
	void aBuildWithNoSiblingSparesNothingAndAFileInTheFolderOfEditionsGoesToo() throws IOException {
		Path mine = edition(FAMILY + "+cccc", 5);
		Path stray = Files.write(this.temp.resolve("stray.txt"), new byte[] {1});
		Path other = edition("0.12.0+mc26.2", 30);

		assertEquals("", ModuleCache.dropOtherEditions(this.temp, mine, FAMILY));

		assertFalse(Files.exists(stray));
		assertFalse(Files.exists(other));
		assertTrue(Files.exists(mine));
	}

	@Test
	void aFolderThatCannotBeListedIsAnsweredAndNeverThrown() {
		Path absent = this.temp.resolve("absent");

		String left = ModuleCache.dropOtherEditions(absent, absent.resolve(FAMILY), FAMILY);

		assertTrue(left.startsWith("the folder could not be listed ("), left);
	}
}
