package dev.vitrail.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one word {@code vitrail/graphics-api.txt} holds, which is read inside the game's own
 * constructor before this mod exists. Only the half that does not ask the game where its directory is
 * can be run here: what the words are, and what is written and where. The read is the game's to
 * find a directory for and is not reachable from a test.
 */
class GraphicsApiChoiceTest {

	@TempDir
	Path game;

	@Test
	void theThreeChoicesAreTheThreeWordsTheFileIsReadBy() {
		assertEquals("vulkan", GraphicsApiChoice.VULKAN.word());
		assertEquals("opengl", GraphicsApiChoice.OPENGL.word());
		assertEquals("game", GraphicsApiChoice.GAME.word());
		assertEquals(3, GraphicsApiChoice.values().length);
	}

	@Test
	void everyWordIsLowerCaseAndUniqueBecauseTheReadLowerCasesWhatItFindsAndMatchesTheWholeWord() {
		Set<String> seen = new HashSet<>();
		for (GraphicsApiChoice choice : GraphicsApiChoice.values()) {
			assertEquals(choice.word().toLowerCase(Locale.ROOT), choice.word());
			assertEquals(choice.word().trim(), choice.word());
			assertTrue(seen.add(choice.word()), choice.word());
		}
	}

	@Test
	void aFreshInstallComesBackToVulkanSinceThisModDrawsNothingElsewhere() {
		assertEquals(GraphicsApiChoice.VULKAN, GraphicsApiChoice.DEFAULT);
	}

	@Test
	void writeLeavesTheWordAndALineBreakInTheModsFolderOfTheGameDirectory() throws IOException {
		for (GraphicsApiChoice choice : GraphicsApiChoice.values()) {
			GraphicsApiChoice.write(this.game, choice);

			Path file = this.game.resolve("vitrail").resolve("graphics-api.txt");
			assertEquals(choice.word() + "\n", Files.readString(file, StandardCharsets.UTF_8), choice.name());
		}
	}

	@Test
	void writeCreatesTheFolderAndReplacesWhatWasThere() throws IOException {
		Path file = this.game.resolve("vitrail").resolve("graphics-api.txt");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "opengl\nand a long tail of something else that must not survive\n",
				StandardCharsets.UTF_8);

		GraphicsApiChoice.write(this.game, GraphicsApiChoice.GAME);

		assertEquals("game\n", Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void writeThatCannotBeDoneSaysSoInTheLogAndNeverThrows() throws IOException {
		// The game directory is a file, so there is nowhere to put the folder.
		Path notADirectory = this.game.resolve("file");
		Files.writeString(notADirectory, "x", StandardCharsets.UTF_8);

		GraphicsApiChoice.write(notADirectory, GraphicsApiChoice.OPENGL);

		assertFalse(Files.exists(notADirectory.resolve("vitrail")));
		assertEquals("x", Files.readString(notADirectory, StandardCharsets.UTF_8));
	}
}
