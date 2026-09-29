package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds a path that names {@code shaders/} itself to finding nothing, where a file beside it shares
 * its name in another case.
 * <p>
 * The case-insensitive fallback lists the parent of the path it is asked about, and every confined
 * path but one has its parent inside {@code shaders/}. The one is {@code shaders/} itself, whose
 * parent is the root of the pack, so {@code #include ".."}, {@code #include "/"} or a texture key of
 * {@code .} found a file there named {@code SHADERS} and read it. An archive holds both names side by
 * side, and so does a folder on a disk that tells case apart.
 */
class ShadersRootLookalikeTest {

	private static final String BESIDE = "the text of a file beside shaders/";

	@TempDir
	private Path root;

	@Test
	void findsNothingBesideShadersInAnArchive() throws IOException {
		Path pack = this.root.resolve("pack.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			entry(zip, "shaders/lib/inc.glsl", "// inc");
			entry(zip, "SHADERS", BESIDE);
		}

		assertEquals(List.of(), rootRoads(pack));
	}

	@Test
	void findsNothingBesideShadersInAFolderThatTellsCaseApart() throws IOException {
		Path pack = this.root.resolve("pack");
		Files.createDirectories(pack.resolve("shaders/lib"));
		Files.writeString(pack.resolve("shaders/lib/inc.glsl"), "// inc", StandardCharsets.UTF_8);
		try {
			Files.writeString(pack.resolve("SHADERS"), BESIDE, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Assumptions.abort("this disk does not tell SHADERS from shaders: " + e);
		}

		Assumptions.assumeTrue(Files.isRegularFile(pack.resolve("SHADERS")),
				"this disk does not tell SHADERS from shaders");
		assertEquals(List.of(), rootRoads(pack));
	}

	@Test
	void stillFindsAFileInsideShadersIgnoringCase() throws IOException {
		Path pack = this.root.resolve("pack.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			entry(zip, "shaders/lib/Inc.glsl", "// inc");
			entry(zip, "SHADERS", BESIDE);
		}

		try (ShaderPackSource source = ShaderPackSource.open(pack)) {
			Optional<Path> found = source.resolveInsideShaders("lib/inc.glsl");

			assertTrue(found.isPresent());
			assertEquals("lib/Inc.glsl", source.rel(found.get()));
		}
	}

	/** Every road a pack has to the root of {@code shaders/}, and what each of them found. */
	private static List<String> rootRoads(Path pack) throws IOException {
		List<String> found = new ArrayList<>();
		try (ShaderPackSource source = ShaderPackSource.open(pack)) {
			Path from = source.resolveInsideShaders("lib/inc.glsl").orElseThrow();
			for (String spec : List.of("", ".", "/", "lib/..", "//./lib/../")) {
				source.resolveInsideShaders(spec).ifPresent(path -> found.add("inside " + spec));
				source.file(spec).ifPresent(path -> found.add("file " + spec));
			}

			for (String spec : List.of("..", "../", "../../shaders")) {
				source.resolveRelativeTo(from, spec).ifPresent(path -> found.add("relative " + spec));
			}
		}

		return found;
	}

	private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(text.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
