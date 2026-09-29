package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.option.SettingSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds a folder pack to the files inside it when a link inside it leads somewhere else.
 * <p>
 * The text of a path is confined to the shader root, and a link is followed by the read that comes
 * after that check. So a pack carrying {@code shaders/lib} as a link out of itself reached any file
 * the game can read through {@code #include "lib/..."} or a texture path, and a zip unpacked with
 * its links kept is that pack. A link between two of the pack's own directories has to go on
 * working.
 */
class SymlinkEscapeTest {

	private static final String SECRET = "#define LEAKED_FROM_OUTSIDE";

	@TempDir
	private Path root;

	private Path shaders;

	@BeforeEach
	void layOut() throws IOException {
		Path outside = Files.createDirectories(this.root.resolve("outside"));
		write(outside.resolve("secret.glsl"), SECRET);
		write(outside.resolve("secret.png"), "not a picture");
		write(outside.resolve("notes.txt"), "LEAKED_FROM_OUTSIDE");

		this.shaders = Files.createDirectories(this.root.resolve("pack/shaders"));
		write(this.shaders.resolve("real/common.glsl"), "#define SHARED");
		write(this.shaders.resolve("gbuffers_basic.fsh"),
				"#include \"lib/secret.glsl\"\n#include \"shared/common.glsl\"\nvoid main() {}");

		link(this.shaders.resolve("lib"), Path.of("../../outside"));
		link(this.shaders.resolve("leak.glsl"), Path.of("../../outside/secret.glsl"));
		link(this.shaders.resolve("notes.txt"), Path.of("../../outside/notes.txt"));
		link(this.shaders.resolve("shared"), Path.of("real"));
		link(this.shaders.resolve("alias.glsl"), Path.of("real/common.glsl"));
	}

	@Test
	void refusesAnIncludeThroughALinkOutOfThePack() throws IOException {
		try (ShaderPackSource source = open()) {
			Path entry = this.shaders.resolve("gbuffers_basic.fsh");

			assertEquals(Optional.empty(), source.resolveRelativeTo(entry, "lib/secret.glsl"));
			assertEquals(Optional.empty(), source.resolveInsideShaders("/lib/secret.glsl"));
			assertEquals(Optional.empty(), source.resolveInsideShaders("leak.glsl"));
		}
	}

	@Test
	void flattensNothingOfTheFileOutside() throws IOException {
		try (ShaderPackSource source = open()) {
			IncludeExpander.ExpandedUnit unit = IncludeExpander
					.forTheReport(source, SettingSet.defaults())
					.expand(this.shaders.resolve("gbuffers_basic.fsh"));

			assertFalse(unit.text().contains("LEAKED_FROM_OUTSIDE"), unit.text());
			assertTrue(unit.text().contains("#error include not found: lib/secret.glsl"), unit.text());
			assertTrue(unit.text().contains("#define SHARED"), unit.text());
		}
	}

	@Test
	void refusesATexturePathThroughALinkOutOfThePack() throws IOException {
		try (ShaderPackSource source = open()) {
			assertEquals(Optional.empty(), source.file("lib/secret.png"));

			// Refused rather than missing, so that the name stays claimed and reads black, and a
			// name under the link that nothing answers to is outside all the same.
			assertFalse(source.insidePack("lib/secret.png"));
			assertFalse(source.insidePack("/lib/absent.png"));
		}
	}

	@Test
	void refusesALinkOutFoundByIgnoringCase() throws IOException {
		try (ShaderPackSource source = open()) {
			assertEquals(Optional.empty(), source.file("LEAK.GLSL"));
		}
	}

	@Test
	void leavesALinkedFileOutOfBothWalks() throws IOException {
		try (ShaderPackSource source = open()) {
			List<String> sources = source.sourceFiles().stream().map(source::rel).toList();
			List<String> others = source.otherFiles().stream().map(source::rel).toList();

			assertFalse(sources.contains("leak.glsl"), sources.toString());
			assertFalse(others.contains("notes.txt"), others.toString());
			assertFalse(source.options().names().contains("LEAKED_FROM_OUTSIDE"));
			assertTrue(source.options().names().contains("SHARED"));
		}
	}

	@Test
	void followsALinkThatStaysInsideThePack() throws IOException {
		try (ShaderPackSource source = open()) {
			Optional<Path> shared = source.file("shared/common.glsl");
			Optional<Path> alias = source.file("alias.glsl");

			assertTrue(shared.isPresent());
			assertTrue(alias.isPresent());
			assertEquals(List.of("#define SHARED"), source.readLines(shared.get()));
			assertEquals(List.of("#define SHARED"), source.readLines(alias.get()));
			assertTrue(source.insidePack("shared/absent.png"));
			assertTrue(source.sourceFiles().stream().map(source::rel).toList().contains("alias.glsl"));
		}
	}

	private ShaderPackSource open() throws IOException {
		return ShaderPackSource.open(this.root.resolve("pack"));
	}

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	/** Skipped rather than failed where the system will not make a link for this user. */
	private static void link(Path link, Path target) {
		try {
			Files.createSymbolicLink(link, target);
		} catch (IOException | UnsupportedOperationException e) {
			Assumptions.abort("no symbolic links here: " + e);
		}
	}
}
