package dev.vitrail.pack;

import dev.vitrail.pack.source.ShaderPackSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes a small synthetic pack into a temporary directory, for the tests of the option, menu and
 * texture packages that have to read a pack through {@link ShaderPackSource} the way a load does.
 * <p>
 * A key is a path under {@code shaders/}, with forward slashes; a value is the file's text. Nothing
 * is written outside the directory handed in, which is a JUnit {@code TempDir}.
 */
public final class OptionPackFixture {

	private OptionPackFixture() {
	}

	/** Writes the files under {@code root/shaders/} and answers the pack directory. */
	public static Path write(Path root, Map<String, String> files) throws IOException {
		for (Map.Entry<String, String> file : files.entrySet()) {
			Path target = root.resolve("shaders").resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue().getBytes(StandardCharsets.UTF_8));
		}

		return root;
	}

	/** Writes raw bytes, for a texture blob or a picture header. */
	public static void writeBytes(Path root, String relative, byte[] bytes) throws IOException {
		Path target = root.resolve("shaders").resolve(relative);
		Files.createDirectories(target.getParent());
		Files.write(target, bytes);
	}

	public static ShaderPackSource open(Path root) throws IOException {
		return ShaderPackSource.open(root);
	}
}
