package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds one opening of a pack to a ceiling on how much it walks and how much text it reads, where
 * each file had a ceiling and the pack had none.
 * <p>
 * The archives are the shape the ceilings are for: a few kilobytes on disk that list twenty
 * thousand entries or inflate to more text than a pack is allowed. Every file in them stays under
 * the ceiling a single file is held to, which is the point: that ceiling alone let them through.
 */
class PackReadingLimitsTest {

	/** The ceiling one file is held to, which every file here meets exactly. */
	private static final int FILE_BYTES = 8 * 1024 * 1024;

	/** Each file is written as lines this long, so that a split holds a handful of strings. */
	private static final int LINE_BYTES = 1024 * 1024;

	@TempDir
	private Path root;

	@Test
	void refusesAnArchiveOfMoreEntriesThanAPackHolds() throws IOException {
		Path pack = zip("crowded.zip", zip -> {
			directory(zip, "shaders/");
			for (int i = 0; i < 20_000; i++) {
				file(zip, "shaders/f" + i + ".glsl", 0);
			}
		});

		try (ShaderPackSource source = ShaderPackSource.open(pack)) {
			IOException refused = assertThrows(IOException.class, source::options);
			assertTrue(refused.getMessage().startsWith("crowded holds more than"),
					refused.getMessage());
		}
	}

	@Test
	void refusesAnArchiveWhoseSearchForShadersMeetsTooMuch() throws IOException {
		Path pack = zip("buried.zip", zip -> {
			for (int i = 0; i < 20_001; i++) {
				file(zip, "junk/f" + i + ".txt", 0);
			}
			directory(zip, "inner/shaders/");
			file(zip, "inner/shaders/final.fsh", 0);
		});

		IOException refused = assertThrows(IOException.class, () -> ShaderPackSource.open(pack).close());
		assertTrue(refused.getMessage().startsWith("buried holds more than"), refused.getMessage());
	}

	@Test
	void refusesSourcesThatInflatePastTheTextCeiling() throws IOException {
		Path pack = zip("inflating.zip", zip -> {
			directory(zip, "shaders/");
			for (int i = 0; i < 9; i++) {
				file(zip, "shaders/lib/filler" + i + ".glsl", FILE_BYTES);
			}
		});

		try (ShaderPackSource source = ShaderPackSource.open(pack)) {
			IOException refused = assertThrows(IOException.class, source::options);
			assertTrue(refused.getMessage().startsWith("inflating holds more than"),
					refused.getMessage());
			assertTrue(refused.getMessage().contains("lib/filler8.glsl"), refused.getMessage());
		}
	}

	@Test
	void countsTheTextSearchedForANameAsWell() throws IOException {
		Path pack = zip("searched.zip", zip -> {
			directory(zip, "shaders/");
			file(zip, "shaders/final.fsh", 0);
			for (int i = 0; i < 9; i++) {
				file(zip, "shaders/notes" + i + ".txt", FILE_BYTES);
			}
		});

		try (ShaderPackSource source = ShaderPackSource.open(pack)) {
			assertThrows(IOException.class, () -> SourceMentions.of(source, Set.of("NAMED_NOWHERE")));
		}
	}

	/**
	 * A kept opening serves one load after another, and each of them reads the same files again.
	 * Counted per read rather than per file, the ceiling would refuse a pack that had loaded
	 * cleanly a dozen times the moment the player walked through one more portal.
	 */
	@Test
	void countsAFileOnceHoweverOftenItIsRead() throws IOException {
		Path pack = zip("reread.zip", zip -> {
			directory(zip, "shaders/");
			file(zip, "shaders/big.glsl", 5 * LINE_BYTES);
			file(zip, "shaders/notes.txt", 5 * LINE_BYTES);
		});

		try (ShaderPackSource source = ShaderPackSource.open(pack)) {
			Path big = source.file("big.glsl").orElseThrow();
			Path notes = source.file("notes.txt").orElseThrow();

			assertDoesNotThrow(() -> {
				for (int load = 0; load < 20; load++) {
					source.readLines(big);
					source.readLines(notes);
					source.searchableText(notes);
				}
			});
			assertDoesNotThrow(source::options);
		}
	}

	@FunctionalInterface
	private interface Entries {
		void write(ZipOutputStream zip) throws IOException;
	}

	private Path zip(String name, Entries entries) throws IOException {
		Path archive = this.root.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(
				new BufferedOutputStream(Files.newOutputStream(archive)))) {
			entries.write(zip);
		}

		return archive;
	}

	private static void directory(ZipOutputStream zip, String name) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.closeEntry();
	}

	/** A text file of that many bytes, in lines of {@link #LINE_BYTES}, which compresses to nothing. */
	private static void file(ZipOutputStream zip, String name, int bytes) throws IOException {
		zip.putNextEntry(new ZipEntry(name));

		byte[] line = new byte[Math.min(bytes, LINE_BYTES)];
		Arrays.fill(line, (byte) 'a');
		if (line.length > 0) {
			line[line.length - 1] = '\n';
		}

		for (int written = 0; written < bytes; written += line.length) {
			zip.write(line);
		}

		zip.closeEntry();
	}
}
