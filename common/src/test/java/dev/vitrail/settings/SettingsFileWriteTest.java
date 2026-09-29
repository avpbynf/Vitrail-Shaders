package dev.vitrail.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds a pack's settings file to leaving nothing behind it when it cannot be written.
 * <p>
 * The file is written beside itself as a {@code .part} and moved over the old one. A move that
 * fails must not leave that {@code .part} in the shaderpacks folder, among the packs, for good.
 */
class SettingsFileWriteTest {

	@TempDir
	private Path root;

	@Test
	void leavesNoPartWhereTheMoveFails() throws IOException {
		Path file = this.root.resolve("Pack.zip.txt");
		// A folder with something in it where the file goes, which no move replaces.
		Files.createDirectories(file.resolve("in-the-way"));

		assertThrows(IOException.class,
				() -> SettingsFile.write(file, new SettingsFile.Stored(Map.of("SHADOWS", "true"))));

		assertFalse(Files.exists(this.root.resolve("Pack.zip.txt.part")));
	}

	@Test
	void stillWritesAndReadsBack() throws IOException {
		Path file = this.root.resolve("Pack.zip.txt");

		SettingsFile.write(file, new SettingsFile.Stored(Map.of("SHADOWS", "true")));

		assertEquals(Map.of("SHADOWS", "true"), SettingsFile.read(file).values());
		assertFalse(Files.exists(this.root.resolve("Pack.zip.txt.part")));
	}
}
