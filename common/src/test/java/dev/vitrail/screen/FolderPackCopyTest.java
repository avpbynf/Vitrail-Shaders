package dev.vitrail.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.load.PackLoader;
import dev.vitrail.pack.source.ShaderPackSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds a folder dropped onto the pack list to arriving whole or not at all.
 * <p>
 * It was copied file by file straight into place. One file that could not be read left a pack in
 * the list with the rest of its files missing, and every drop after it was told the pack already
 * existed. And a link inside it was copied as what it pointed at, which made a link out of the
 * folder a plain file of the pack, out of reach of the check that refuses it.
 */
class FolderPackCopyTest {

	@TempDir
	private Path root;

	private Path shaderpacks;
	private Path dropped;

	@BeforeEach
	void layOut() throws IOException {
		this.shaderpacks = this.root.resolve("shaderpacks");
		this.dropped = this.root.resolve("Downloads/Pack");
		write(this.dropped.resolve("shaders/a.glsl"), "a");
		write(this.dropped.resolve("shaders/lib/b.glsl"), "b");
		write(this.dropped.resolve("shaders/locked.glsl"), "locked");
	}

	@Test
	void leavesNothingWhenAFileCannotBeCopiedAndCopiesItAgainAfter() throws IOException {
		Path locked = this.dropped.resolve("shaders/locked.glsl");
		lock(locked);

		assertThrows(IOException.class, () -> SettingsScreen.copyInto(this.shaderpacks, this.dropped));

		assertEquals(List.of(), entries(this.shaderpacks));

		Files.setPosixFilePermissions(locked, Set.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE));
		SettingsScreen.copyInto(this.shaderpacks, this.dropped);

		assertCopied();
	}

	@Test
	void clearsACopyAnEarlierCrashLeftHalfDone() throws IOException {
		write(this.shaderpacks.resolve(".Pack.part/shaders/stale.glsl"), "stale");

		SettingsScreen.copyInto(this.shaderpacks, this.dropped);

		assertCopied();
		assertFalse(Files.exists(this.shaderpacks.resolve("Pack/shaders/stale.glsl")));
	}

	@Test
	void copiesALinkAsALinkForTheConfinementToSee() throws IOException {
		write(this.root.resolve("outside/secret.glsl"), "secret");
		link(this.dropped.resolve("shaders/out"), Path.of("../../../outside"));
		link(this.dropped.resolve("shaders/leak.glsl"), this.root.resolve("outside/secret.glsl"));
		link(this.dropped.resolve("shaders/alias.glsl"), Path.of("a.glsl"));

		SettingsScreen.copyInto(this.shaderpacks, this.dropped);

		Path shaders = this.shaderpacks.resolve("Pack/shaders");
		assertTrue(Files.isSymbolicLink(shaders.resolve("out")));
		assertEquals(Path.of("../../../outside"), Files.readSymbolicLink(shaders.resolve("out")));
		assertTrue(Files.isSymbolicLink(shaders.resolve("leak.glsl")));
		assertTrue(Files.isSymbolicLink(shaders.resolve("alias.glsl")));

		try (ShaderPackSource source = ShaderPackSource.open(this.shaderpacks.resolve("Pack"))) {
			assertEquals(Optional.empty(), source.file("leak.glsl"));
			assertEquals(Optional.empty(), source.file("out/secret.glsl"));
			assertEquals(List.of("a"), source.readLines(source.file("alias.glsl").orElseThrow()));
		}
	}

	@Test
	void listsNoCopyStillBeingMadeAndEveryOtherFolder() throws IOException {
		Files.createDirectories(this.shaderpacks.resolve(".Pack.part/shaders"));
		Files.createDirectories(this.shaderpacks.resolve(".dotted/shaders"));
		Files.createDirectories(this.shaderpacks.resolve("Other/shaders"));

		List<String> listed = PackLoader.candidates(this.root).stream()
				.map(path -> path.getFileName().toString())
				.toList();

		assertEquals(List.of(".dotted", "Other"), listed);
	}

	@Test
	void stillRefusesAPackAlreadyThere() throws IOException {
		write(this.shaderpacks.resolve("Pack/shaders/mine.glsl"), "mine");

		assertThrows(FileAlreadyExistsException.class,
				() -> SettingsScreen.copyInto(this.shaderpacks, this.dropped));

		assertEquals(List.of("Pack"), entries(this.shaderpacks));
		assertEquals("mine", Files.readString(this.shaderpacks.resolve("Pack/shaders/mine.glsl")));
	}

	private void assertCopied() throws IOException {
		Path pack = this.shaderpacks.resolve("Pack");

		assertEquals(List.of("Pack"), entries(this.shaderpacks));
		assertEquals("a", Files.readString(pack.resolve("shaders/a.glsl")));
		assertEquals("b", Files.readString(pack.resolve("shaders/lib/b.glsl")));
		assertEquals("locked", Files.readString(pack.resolve("shaders/locked.glsl")));
	}

	/** What the pack folder holds, hidden names included, or nothing where it is not there. */
	private static List<String> entries(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) {
			return List.of();
		}

		try (Stream<Path> listed = Files.list(directory)) {
			return listed.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	/** Skipped where the system has no such permissions, or where they bind nobody here. */
	private static void lock(Path file) throws IOException {
		try {
			Files.setPosixFilePermissions(file, Set.of());
		} catch (UnsupportedOperationException e) {
			Assumptions.abort("no POSIX permissions here");
		}

		Assumptions.assumeFalse(Files.isReadable(file), "running as a user no permission stops");
	}

	/** Skipped rather than failed where the system will not make a link for this user. */
	private static void link(Path link, Path target) {
		try {
			Files.createSymbolicLink(link, target);
		} catch (IOException | UnsupportedOperationException e) {
			Assumptions.abort("no symbolic links here: " + e);
		}
	}

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}
}
