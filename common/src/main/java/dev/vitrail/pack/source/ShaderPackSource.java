package dev.vitrail.pack.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Read access to a pack, whether it is a directory or a zip, behind one API. A zip is opened
 * as a {@link FileSystem} so that everything above this class works on {@link Path} and never
 * has to know which of the two it is looking at.
 * <p>
 * Nothing this class hands out may outlive {@link #close()}. Closing the zip invalidates every
 * {@code Path} taken from it, and the failure shows up much later as a
 * {@code ClosedFileSystemException} on an unrelated read, so the loaded form of a pack holds
 * strings only.
 */
public final class ShaderPackSource implements AutoCloseable {

	/**
	 * The only extensions that are read. Widening this list is not free: several packs ship
	 * JSON for other mods that contains lines starting with {@code #include}, and counting
	 * those would move numbers that are supposed to be comparable against the measurements.
	 */
	private static final Set<String> SOURCE_EXTENSIONS =
			Set.of("glsl", "fsh", "vsh", "gsh", "csh", "tcs", "tes", "inc", "settings");

	private static final String SHADERS_DIRECTORY = "shaders";

	/** How deep to look for a misplaced {@code shaders/} before giving up. */
	private static final int SHADERS_SEARCH_DEPTH = 3;

	/** Fifty times the largest source file in the corpus, and a bound on a hostile archive. */
	private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

	private final String packName;
	private final Path shadersRoot;
	private final FileSystem ownedFileSystem;

	// Directory listings, lower-cased, kept for the case-insensitive fallback below. Built on
	// demand because most packs never need it.
	private final Map<String, Map<String, Path>> listingsByDirectory = new HashMap<>();

	private int caseInsensitiveHits;

	private ShaderPackSource(String packName, Path shadersRoot, FileSystem ownedFileSystem) {
		this.packName = packName;
		this.shadersRoot = shadersRoot;
		this.ownedFileSystem = ownedFileSystem;
	}

	/**
	 * What a pack is called: the folder's own name, or the archive's without the extension.
	 * <p>
	 * The one answer to that question, and it is answered from the path alone, without opening
	 * anything: a refusal has to name the pack it refuses, and it is often refusing it precisely
	 * because it could not be opened. Everything that names a pack in the log goes through here, so
	 * that one startup cannot call the same pack two things two lines apart.
	 */
	public static String nameOf(Path packPath) {
		Path name = packPath.getFileName();
		if (name == null) {
			return packPath.toString();
		}

		String text = name.toString();
		if (Files.isDirectory(packPath) || !text.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			return text;
		}

		return text.substring(0, text.length() - 4);
	}

	public static ShaderPackSource open(Path packPath) throws IOException {
		if (Files.isDirectory(packPath)) {
			return new ShaderPackSource(nameOf(packPath), findShadersRoot(packPath), null);
		}

		String fileName = packPath.getFileName() == null ? "" : packPath.getFileName().toString();
		if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			throw new IOException("A shader pack is either a directory or a .zip, and " + fileName + " is neither");
		}

		FileSystem zip = FileSystems.newFileSystem(packPath);
		try {
			return new ShaderPackSource(nameOf(packPath), findShadersRoot(zip.getPath("/")), zip);
		} catch (IOException | RuntimeException e) {
			zip.close();
			throw e;
		}
	}

	/**
	 * The pack is whatever contains a {@code shaders} directory. Usually that is the root, but
	 * a pack re-zipped by hand ends up one level down, and packs do ship other things beside
	 * it: licences, readmes, and files meant for other mods entirely.
	 */
	private static Path findShadersRoot(Path root) throws IOException {
		Path direct = root.resolve(SHADERS_DIRECTORY);
		if (Files.isDirectory(direct)) {
			return direct;
		}

		try (Stream<Path> tree = Files.walk(root, SHADERS_SEARCH_DEPTH)) {
			return tree.filter(Files::isDirectory)
					.filter(path -> path.getFileName() != null
							&& path.getFileName().toString().equals(SHADERS_DIRECTORY))
					.min(Comparator.comparing(Path::toString))
					.orElseThrow(() -> new IOException("No shaders directory in this pack"));
		}
	}

	public String packName() {
		return this.packName;
	}

	public boolean isZip() {
		return this.ownedFileSystem != null;
	}

	/** How many reads only succeeded because the name was matched ignoring case. */
	public int caseInsensitiveHits() {
		return this.caseInsensitiveHits;
	}

	/**
	 * A file's path relative to {@code shaders/}, with forward slashes. This is the only form
	 * of a path that leaves this class: an absolute one means nothing inside a zip, and outside
	 * one it would put the player's directory layout in the log.
	 */
	public String rel(Path file) {
		return this.shadersRoot.relativize(file).toString().replace('\\', '/');
	}

	/**
	 * Every source file, in a fixed order. The order is part of the contract rather than an
	 * accident: the settings index keeps the first declaration of a name and drops later ones,
	 * so a different walk order would silently give a different answer on the packs that
	 * declare the same setting twice.
	 */
	public List<Path> sourceFiles() throws IOException {
		try (Stream<Path> tree = Files.walk(this.shadersRoot)) {
			List<Path> files = new ArrayList<>(tree.filter(Files::isRegularFile)
					.filter(path -> SOURCE_EXTENSIONS.contains(extensionOf(path)))
					.toList());
			files.sort(Comparator.comparing(this::rel));

			return List.copyOf(files);
		}
	}

	private static String extensionOf(Path path) {
		Path name = path.getFileName();
		if (name == null) {
			return "";
		}

		String text = name.toString();
		int dot = text.lastIndexOf('.');

		return dot < 0 ? "" : text.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/**
	 * Decoding never throws. A pack that ships one file in the wrong encoding should lose that
	 * file's accented comments, not fail to load; the compiler will complain later if the loss
	 * touched something that mattered.
	 */
	public List<String> readLines(Path file) throws IOException {
		// The largest source file in the corpus is a hundred and sixty kilobytes. Reading without
		// a ceiling means a zip that unpacks to half a gigabyte is read whole into memory before
		// anything downstream gets the chance to refuse it.
		long size = Files.size(file);
		if (size > MAX_FILE_BYTES) {
			throw new IOException(rel(file) + " is " + size + " bytes, past the " + MAX_FILE_BYTES
					+ " a shader source is allowed");
		}

		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
		CharBuffer decoded = decoder.decode(ByteBuffer.wrap(Files.readAllBytes(file)));

		String text = decoded.toString();
		if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
			text = text.substring(1);
		}

		// Splitting on a lone carriage return as well: some pack files still carry classic Mac
		// line endings, and treating such a file as one long line loses every directive in it.
		return List.of(text.split("\r\n|\n|\r", -1));
	}

	/** Resolves a path written with a leading slash, which means relative to {@code shaders/}. */
	public Optional<Path> resolveInsideShaders(String spec) {
		return resolveAgainst(this.shadersRoot, withoutLeadingSlash(spec));
	}

	private static String withoutLeadingSlash(String spec) {
		return spec.replaceAll("^/+", "");
	}

	/** Resolves a path written without a leading slash, which means next to the including file. */
	public Optional<Path> resolveRelativeTo(Path fromFile, String spec) {
		Path parent = fromFile.getParent();

		return parent == null ? Optional.empty() : resolveAgainst(parent, spec);
	}

	private Optional<Path> resolveAgainst(Path base, String spec) {
		Optional<Path> target = confine(base, spec);
		if (target.isEmpty()) {
			return Optional.empty();
		}

		if (Files.isRegularFile(target.get())) {
			return target;
		}

		return resolveIgnoringCase(target.get());
	}

	/**
	 * Where a path a pack wrote lands once normalised, or nothing when that is outside the pack.
	 * Says nothing about whether anything is there.
	 * <p>
	 * A pack is downloaded content. Without this check a crafted include could walk out of the pack
	 * with ".." and have the engine read any file the game can reach. It is the one place the
	 * shader root is compared against, so that every road into the pack passes it exactly once.
	 */
	private Optional<Path> confine(Path base, String spec) {
		Path target;
		try {
			target = base.resolve(spec).normalize();
		} catch (RuntimeException e) {
			return Optional.empty();
		}

		return target.startsWith(this.shadersRoot) ? Optional.of(target) : Optional.empty();
	}

	/**
	 * Whether a path a pack wrote still lands inside the pack once normalised, WITHOUT asking
	 * whether the pack ships anything there.
	 * <p>
	 * The two questions are one for every caller but one, and that caller needs them apart. A
	 * directive naming a file the pack simply forgot to ship is a mistake of the pack's, and the
	 * name it claimed goes back to whatever it meant before, which is what Iris does with it. A
	 * directive naming a file OUTSIDE the pack is the refusal this class exists for, and the name
	 * stays claimed and reads black, so that a path crafted to leave the pack cannot also come out
	 * looking like a pass that drew normally.
	 */
	public boolean insidePack(String relative) {
		return confine(this.shadersRoot, withoutLeadingSlash(relative)).isPresent();
	}

	/**
	 * Packs are authored on Windows, where a name that disagrees with the file on disk still
	 * opens. Inside a zip it does not, so the same pack that works as a folder would fail as an
	 * archive. Matching without case keeps both working; the hits are counted so that a pack
	 * relying on it can be named in the log.
	 */
	private Optional<Path> resolveIgnoringCase(Path target) {
		Path parent = target.getParent();
		Path name = target.getFileName();
		if (parent == null || name == null || !Files.isDirectory(parent)) {
			return Optional.empty();
		}

		Map<String, Path> listing = this.listingsByDirectory.computeIfAbsent(rel(parent), _ -> listing(parent));
		Path found = listing.get(name.toString().toLowerCase(Locale.ROOT));
		if (found == null) {
			return Optional.empty();
		}

		this.caseInsensitiveHits++;

		return Optional.of(found);
	}

	private static Map<String, Path> listing(Path directory) {
		Map<String, Path> byLowerName = new HashMap<>();
		try (Stream<Path> entries = Files.list(directory)) {
			entries.filter(Files::isRegularFile).forEach(entry -> {
				Path name = entry.getFileName();
				if (name != null) {
					byLowerName.putIfAbsent(name.toString().toLowerCase(Locale.ROOT), entry);
				}
			});
		} catch (IOException e) {
			return Map.of();
		}

		return byLowerName;
	}

	/** Directories directly under {@code shaders/}, by name. */
	public List<String> topLevelDirectories() throws IOException {
		try (Stream<Path> entries = Files.list(this.shadersRoot)) {
			return entries.filter(Files::isDirectory)
					.map(this::rel)
					.sorted()
					.toList();
		}
	}

	/**
	 * A path a pack wrote, relative to {@code shaders/} whether or not it opens with a slash.
	 * <p>
	 * The leading slash has to come off before the resolution and not after. {@code Path.resolve}
	 * throws the base away when it is handed something absolute, so the search would drop to the
	 * root of the archive and find nothing, and a texture that is not found is black rather than
	 * an error. Mellow and Reverie write every one of their texture paths that way.
	 * <p>
	 * Resolved by the same road an {@code #include} takes, and for the same reason: a path written
	 * in {@code shaders.properties} is downloaded content exactly as an include is, so it is
	 * normalised and confined to the pack before anything opens it. Without that, one
	 * {@code customTexture} of dots and slashes reads any file the game can reach and hands it to a
	 * shader as a picture. The case-insensitive fallback comes with it, which is what a texture path
	 * needs most: it is typed by hand, and a pack that works as a folder on Windows would otherwise
	 * lose the same file once it is zipped.
	 */
	public Optional<Path> file(String relative) {
		return resolveInsideShaders(relative);
	}

	/**
	 * How many bytes a file holds ON DISK, which for a zip entry is the uncompressed length.
	 * <p>
	 * Asked rather than taken from {@link #bytes}, because the one caller that needs it is
	 * checking whether a raw texture is as long as its directive claims, and a blob that is not is
	 * to be refused rather than read whole and then refused.
	 */
	public long size(Path file) throws IOException {
		return Files.size(file);
	}

	/** A file's raw bytes, under the same ceiling as the sources: an image is not exempt. */
	public byte[] bytes(Path file) throws IOException {
		long size = Files.size(file);
		if (size > MAX_FILE_BYTES) {
			throw new IOException(rel(file) + " is " + size + " bytes, past the " + MAX_FILE_BYTES
					+ " a pack file is allowed");
		}

		return Files.readAllBytes(file);
	}

	@Override
	public void close() throws IOException {
		if (this.ownedFileSystem != null) {
			this.ownedFileSystem.close();
		}
	}
}
