package dev.vitrail.pack.source;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds shader packs out of a map of file names to text, as a directory or as a zip, so that a test
 * reads exactly the shape a player installs without a pack ever being shipped in the repository.
 * <p>
 * The two shapes take the same map, and that is what lets one assertion be run against both: the
 * reading under test is supposed to be blind to which of them it was handed. A name ending in a slash
 * is a directory entry, kept because an empty directory is a thing a pack can ship and the dimension
 * rules read the existence of one rather than its contents. Names are written into a zip as they are
 * given, {@code ..} and backslashes included, which a directory cannot hold.
 */
public final class SyntheticPacks {

	private SyntheticPacks() {
	}

	/** The two shapes a pack is installed in, so that one test body can be run against both. */
	public enum Shape {
		DIRECTORY,
		ZIP;

		/** The pack of {@code files} in this shape, named {@code name} under {@code parent}. */
		public Path build(Path parent, String name, Map<String, String> files) throws IOException {
			return this == DIRECTORY ? directory(parent, name, files) : zip(parent, name, files);
		}
	}

	/** A directory pack named {@code name} under {@code parent}, holding the files of {@code files}. */
	public static Path directory(Path parent, String name, Map<String, String> files) throws IOException {
		Path root = parent.resolve(name);
		Files.createDirectories(root);
		for (Map.Entry<String, String> file : files.entrySet()) {
			Path target = root.resolve(file.getKey());
			if (file.getKey().endsWith("/")) {
				Files.createDirectories(target);
				continue;
			}

			Files.createDirectories(target.getParent());
			Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
		}

		return root;
	}

	/** A zip pack {@code name}.zip under {@code parent}, one entry per file of {@code files}. */
	public static Path zip(Path parent, String name, Map<String, String> files) throws IOException {
		Path archive = parent.resolve(name + ".zip");
		try (OutputStream out = Files.newOutputStream(archive);
				ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			for (Map.Entry<String, String> file : files.entrySet()) {
				zip.putNextEntry(new ZipEntry(file.getKey()));
				if (!file.getKey().endsWith("/")) {
					zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
				}

				zip.closeEntry();
			}
		}

		return archive;
	}

	/** A zip pack whose entries carry raw bytes, for a file that is not text. */
	public static Path zipOfBytes(Path parent, String name, Map<String, byte[]> files) throws IOException {
		Path archive = parent.resolve(name + ".zip");
		try (OutputStream out = Files.newOutputStream(archive);
				ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			for (Map.Entry<String, byte[]> file : files.entrySet()) {
				zip.putNextEntry(new ZipEntry(file.getKey()));
				zip.write(file.getValue());
				zip.closeEntry();
			}
		}

		return archive;
	}
}
