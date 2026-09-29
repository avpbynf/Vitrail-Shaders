package dev.vitrail.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code vitrail/pack.txt} makes of whatever a player, an editor or an old version of this mod
 * left in it. The file is edited by hand, so most of these are inputs that are not what the screen
 * writes: a bare word, a byte order mark, old Mac line ends, a byte that is not UTF-8, a number that
 * is not one.
 * <p>
 * Everything goes through a temporary folder; the expected values are the ones the class comment
 * promises, written out as literals.
 */
class PackFileTest {

	@TempDir
	Path folder;

	private PackFile readBytes(byte[] content) throws IOException {
		Path file = this.folder.resolve("pack.txt");
		Files.write(file, content);

		return PackFile.read(file);
	}

	private PackFile read(String content) throws IOException {
		return readBytes(content.getBytes(StandardCharsets.UTF_8));
	}

	private static void assertFile(PackFile actual, String name, boolean enabled, int shadowDistance,
			int renderScale, int shadowMapScale) {
		assertEquals(new PackFile(name, enabled, shadowDistance, renderScale, shadowMapScale), actual);
	}

	// -- absent and empty ---------------------------------------------------------------------

	@Test
	void aFileThatIsNotThereIsNothingChosenWithShadersOn() throws IOException {
		PackFile read = PackFile.read(this.folder.resolve("missing.txt"));

		assertEquals(PackFile.EMPTY, read);
		assertFile(read, "", true, 32, 100, 100);
		assertFalse(read.wantsPack());
	}

	@Test
	void aDirectoryWhereTheFileShouldBeReadsAsNothingChosen() throws IOException {
		assertEquals(PackFile.EMPTY, PackFile.read(this.folder));
	}

	@Test
	void anEmptyFileIsNothingChosenWithShadersOn() throws IOException {
		assertEquals(PackFile.EMPTY, read(""));
		assertEquals(PackFile.EMPTY, read("\n\n  \n"));
	}

	// -- the old one word spelling ------------------------------------------------------------

	@Test
	void aBareWordIsThePackAndShadersAreOn() throws IOException {
		assertFile(read("BSL_v8.1.zip"), "BSL_v8.1.zip", true, 32, 100, 100);
		assertFile(read("  Complementary Reimagined  \n"), "Complementary Reimagined", true, 32, 100, 100);
		assertFile(read("BSL\r\n"), "BSL", true, 32, 100, 100);
	}

	@Test
	void theBareWordNoneIsShadersOffWithNoPackRemembered() throws IOException {
		assertFile(read("none"), "", false, 32, 100, 100);
		assertFile(read("NONE\n"), "", false, 32, 100, 100);
		assertFile(read("  None  "), "", false, 32, 100, 100);
	}

	@Test
	void aBareNameThatMerelyStartsWithNoneIsAPack() throws IOException {
		assertFile(read("none of the above"), "none of the above", true, 32, 100, 100);
		assertFile(read("nonesuch"), "nonesuch", true, 32, 100, 100);
	}

	@Test
	void aByteOrderMarkDoesNotRideOnTheBareWord() throws IOException {
		assertFile(read("\uFEFFBSL"), "BSL", true, 32, 100, 100);
		assertFile(read("\uFEFFnone"), "", false, 32, 100, 100);
	}

	// -- the key format -----------------------------------------------------------------------

	@Test
	void everyKeyIsReadWhenAllFiveAreThere() throws IOException {
		PackFile read = read("""
				pack=SEUS PTGI HRR 2.1
				enabled=false
				shadowdistance=12
				renderscale=75
				shadowmapscale=50
				""");

		assertFile(read, "SEUS PTGI HRR 2.1", false, 12, 75, 50);
		assertFalse(read.wantsPack(), "shaders are off");
	}

	@Test
	void keysAreCaseInsensitiveAndTolerateSpacesAroundThem() throws IOException {
		assertFile(read("PACK = Foo Bar \nEnabled=TRUE\nShadowDistance = 8\nRENDERSCALE=50\nshadowMapScale=25\n"),
				"Foo Bar", true, 8, 50, 25);
	}

	@Test
	void aMissingKeyKeepsItsDefault() throws IOException {
		assertFile(read("pack=X\n"), "X", true, 32, 100, 100);
		assertFile(read("shadowdistance=4\n"), "", true, 4, 100, 100);
		assertFile(read("renderscale=60\n"), "", true, 32, 60, 100);
		assertFile(read("shadowmapscale=60\n"), "", true, 32, 100, 60);
		assertFile(read("enabled=false\n"), "", false, 32, 100, 100);
	}

	@Test
	void commentsBlankLinesUnknownKeysAndLinesWithoutAnEqualsAreIgnored() throws IOException {
		PackFile read = read("""
				# a comment with pack=Ghost inside it

				   # an indented one, renderscale=30
				nothing to see here
				colour=blue
				pack=Real
				""");

		assertFile(read, "Real", true, 32, 100, 100);
	}

	@Test
	void theNameKeepsAnEqualsSignAfterTheFirstOne() throws IOException {
		assertFile(read("pack=a=b\nenabled=true\n"), "a=b", true, 32, 100, 100);
	}

	@Test
	void anEmptyNameIsNoPack() throws IOException {
		PackFile read = read("pack=\nenabled=true\n");

		assertFile(read, "", true, 32, 100, 100);
		assertFalse(read.wantsPack());
	}

	@Test
	void onlyTheWordTrueTurnsShadersOnAndAnythingElseIsOff() throws IOException {
		assertTrue(read("pack=X\nenabled=true").enabled());
		assertTrue(read("pack=X\nenabled=TRUE").enabled());
		assertTrue(read("pack=X\nenabled= True ").enabled());
		assertFalse(read("pack=X\nenabled=yes").enabled());
		assertFalse(read("pack=X\nenabled=1").enabled());
		assertFalse(read("pack=X\nenabled=on").enabled());
		assertFalse(read("pack=X\nenabled=").enabled());
		assertFalse(read("pack=X\nenabled=tru").enabled());
	}

	@Test
	void theLastLineOfAKeyWins() throws IOException {
		assertFile(read("pack=A\npack=B\nshadowdistance=1\nshadowdistance=2\nenabled=false\nenabled=true\n"),
				"B", true, 2, 100, 100);
	}

	@Test
	void everyKindOfLineEndSplitsALine() throws IOException {
		String expected = "pack=A\nenabled=false\nrenderscale=50\n";
		for (String ending : new String[] {"\n", "\r\n", "\r"}) {
			assertFile(read(expected.replace("\n", ending)), "A", false, 32, 50, 100);
		}
	}

	@Test
	void aByteOrderMarkDoesNotRideOnTheFirstKey() throws IOException {
		assertFile(read("\uFEFFpack=Foo\nenabled=false\n"), "Foo", false, 32, 100, 100);
	}

	@Test
	void aByteThatIsNotUtf8CostsOneCharacterAndNotTheFile() throws IOException {
		byte[] bytes = "pack=Caf\u00E9\nshadowdistance=8\n".getBytes(StandardCharsets.ISO_8859_1);

		PackFile read = readBytes(bytes);

		assertEquals("Caf\uFFFD", read.name());
		assertEquals(8, read.shadowDistance());
		assertTrue(read.enabled());
	}

	@Test
	void aTruncatedMultiByteSequenceAtTheEndOfTheFileIsSurvived() throws IOException {
		byte[] full = "pack=\u00E9\u00E9".getBytes(StandardCharsets.UTF_8);
		byte[] cut = new byte[full.length - 1];
		System.arraycopy(full, 0, cut, 0, cut.length);

		PackFile read = readBytes(cut);

		assertEquals("\u00E9\uFFFD", read.name());
	}

	// -- numbers ------------------------------------------------------------------------------

	@Test
	void numbersAreClampedToTheRangeEachIsOfferedOver() throws IOException {
		assertFile(read("shadowdistance=99\nrenderscale=1000\nshadowmapscale=101\n"), "", true, 32, 100, 100);
		assertFile(read("shadowdistance=-5\nrenderscale=10\nshadowmapscale=0\n"), "", true, 0, 25, 25);
		assertFile(read("shadowdistance=0\nrenderscale=25\nshadowmapscale=25\n"), "", true, 0, 25, 25);
		assertFile(read("shadowdistance=32\nrenderscale=100\nshadowmapscale=100\n"), "", true, 32, 100, 100);
		assertFile(read("shadowdistance=-2147483648\nrenderscale=2147483647\n"), "", true, 0, 100, 100);
	}

	@Test
	void aNumberThatIsNotOneKeepsTheValueBeforeIt() throws IOException {
		// The fallback is what the key held a line ago, which is the default only when nothing set it.
		assertFile(read("shadowdistance=abc\n"), "", true, 32, 100, 100);
		assertFile(read("shadowdistance=8\nshadowdistance=abc\n"), "", true, 8, 100, 100);
		assertFile(read("renderscale=12.5\n"), "", true, 32, 100, 100);
		assertFile(read("renderscale=\n"), "", true, 32, 100, 100);
		assertFile(read("shadowmapscale=0x10\n"), "", true, 32, 100, 100);
		assertFile(read("shadowdistance=2147483648\n"), "", true, 32, 100, 100);
		assertFile(read("shadowdistance=1 2\n"), "", true, 32, 100, 100);
	}

	@Test
	void aNumberMayCarryASignAndSpaces() throws IOException {
		assertFile(read("shadowdistance= 7 \n"), "", true, 7, 100, 100);
		assertFile(read("shadowdistance=+7\n"), "", true, 7, 100, 100);
		assertFile(read("shadowdistance=007\n"), "", true, 7, 100, 100);
	}

	// -- the record ---------------------------------------------------------------------------

	@Test
	void theRecordTrimsTheNameAndClampsEveryNumberWhereverItIsBuilt() {
		PackFile made = new PackFile("  Pack  ", true, 500, -1, 1000);

		assertEquals("Pack", made.name());
		assertEquals(32, made.shadowDistance());
		assertEquals(25, made.renderScale());
		assertEquals(100, made.shadowMapScale());
		assertEquals(0, made.withShadowDistance(-1).shadowDistance());
		assertEquals(100, made.withRenderScale(101).renderScale());
		assertEquals(25, made.withShadowMapScale(0).shadowMapScale());
	}

	@Test
	void theWithersChangeOneFactAndKeepTheRest() {
		PackFile base = new PackFile("A", true, 8, 60, 40);

		assertEquals(new PackFile("B", true, 8, 60, 40), base.withName("B"));
		assertEquals(new PackFile("A", false, 8, 60, 40), base.withEnabled(false));
		assertEquals(new PackFile("C", false, 8, 60, 40), base.withChoice("C", false));
		assertEquals(new PackFile("A", true, 3, 60, 40), base.withShadowDistance(3));
		assertEquals(new PackFile("A", true, 8, 70, 40), base.withRenderScale(70));
		assertEquals(new PackFile("A", true, 8, 60, 30), base.withShadowMapScale(30));
	}

	@Test
	void wantsAPackOnlyWhenShadersAreOnAndAnyNameIsGiven() {
		assertTrue(new PackFile("A", true, 32, 100, 100).wantsPack());
		assertFalse(new PackFile("A", false, 32, 100, 100).wantsPack());
		assertFalse(new PackFile("", true, 32, 100, 100).wantsPack());
		assertFalse(new PackFile("   ", true, 32, 100, 100).wantsPack());
		// The word none is matched where the folder is, not here, so a folder called none stays reachable.
		assertTrue(new PackFile("none", true, 32, 100, 100).wantsPack());
	}

	@Test
	void namesNoneMatchesTheWordInAnyCase() {
		assertTrue(new PackFile("none", true, 32, 100, 100).namesNone());
		assertTrue(new PackFile("None", true, 32, 100, 100).namesNone());
		assertFalse(new PackFile("nonesuch", true, 32, 100, 100).namesNone());
		assertFalse(PackFile.EMPTY.namesNone());
	}

	// -- writing ------------------------------------------------------------------------------

	@Test
	void writeSaysEveryKeyInTheOrderTheClassDocumentsInLfWithNoByteOrderMark() throws IOException {
		Path file = this.folder.resolve("vitrail").resolve("pack.txt");

		PackFile.write(file, new PackFile("BSL", false, 12, 75, 50));

		assertEquals("pack=BSL\nenabled=false\nshadowdistance=12\nrenderscale=75\nshadowmapscale=50\n",
				Files.readString(file, StandardCharsets.UTF_8));
		byte[] bytes = Files.readAllBytes(file);
		assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB);
	}

	@Test
	void writeCreatesTheFolderReplacesTheFileAndLeavesNoTemporaryBehind() throws IOException {
		Path file = this.folder.resolve("a").resolve("b").resolve("pack.txt");

		PackFile.write(file, new PackFile("One", true, 32, 100, 100));
		PackFile.write(file, new PackFile("Two", false, 4, 30, 30));

		assertEquals(new PackFile("Two", false, 4, 30, 30), PackFile.read(file));
		try (Stream<Path> siblings = Files.list(file.getParent())) {
			assertEquals(1, siblings.count(), "nothing but the file itself");
		}
	}

	@Test
	void whatWriteSaysReadGivesBackForTheEdgesOfEveryRange() throws IOException {
		Path file = this.folder.resolve("pack.txt");
		int[][] edges = {{0, 25, 25}, {32, 100, 100}, {1, 26, 99}, {31, 99, 26}};
		for (int[] edge : edges) {
			for (boolean enabled : new boolean[] {true, false}) {
				for (String name : new String[] {"", "none", "A B", "caf\u00E9 \u00FC\u4E2D", "a=b"}) {
					PackFile written = new PackFile(name, enabled, edge[0], edge[1], edge[2]);
					PackFile.write(file, written);
					assertEquals(written, PackFile.read(file), written.toString());
				}
			}
		}
	}

	@Test
	void aWriteThatCannotFinishLeavesNoTemporaryAndSaysSo() throws IOException {
		Path file = this.folder.resolve("pack.txt");
		Files.createDirectory(file);
		Files.writeString(file.resolve("keep"), "x", StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> PackFile.write(file, PackFile.EMPTY));

		assertFalse(Files.exists(this.folder.resolve("pack.txt.part")), "the .part is taken away again");
		assertTrue(Files.isDirectory(file), "what was there is still there");
	}

	// -- known defects, pinned as they are ---------------------------------------------------

	@Test
	void knownBug_aBareNameContainingAnEqualsSignIsReadAsAKeyValueFile() throws IOException {
		// The one line spelling is told apart by having no '=' at all, so a pack folder called
		// "Foo=Bar.zip" in the old spelling reads as an unknown key and the choice is lost.
		assertFile(read("Foo=Bar.zip"), "", true, 32, 100, 100);
	}

	@Test
	void knownBug_aNameWithALineBreakInItIsCutAtTheBreakOnTheWayBack() throws IOException {
		// write() puts the name on the line as it is, so what follows a break becomes a line of its own;
		// the real keys are written after it and win, which is the only thing that limits the damage.
		Path file = this.folder.resolve("pack.txt");
		PackFile.write(file, new PackFile("A\nenabled=false", true, 32, 100, 100));

		PackFile read = PackFile.read(file);

		assertEquals("A", read.name());
		assertTrue(read.enabled(), "the injected line is overridden by the real one below it");
	}
}
