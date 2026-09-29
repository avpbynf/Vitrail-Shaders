package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds every path a pack writes inside {@code shaders/}: an include, a texture key, a language file.
 * A pack is downloaded content, and a path that is followed out of it hands the engine a file of the
 * player's disk to read into a shader.
 * <p>
 * Every hostile spelling here is aimed at a file that EXISTS, one beside {@code shaders/} in the pack
 * and, for a directory, one beside the pack itself. An empty answer is therefore the refusal, and not
 * merely a file that was never there; {@link #theAimedFilesReallyExist} says so for each spelling, on
 * the same layout the assertions run against.
 */
class PathConfinementTest {

	private static final String SECRET = "secret text of the pack root";
	private static final String OUTSIDE = "secret text beside the pack";

	/** Spellings that normalise out of {@code shaders/}, written for a file that includes from lib/. */
	private static final List<String> ESCAPES_FROM_LIB = List.of(
			"../../secret.png",
			"./../../secret.png",
			".//..//..//secret.png",
			"lib/../../../secret.png",
			"x/./y/../../../../secret.png",
			"..//..///secret.png",
			"../../../outside-secret.png");

	/** The same, written from the root of {@code shaders/}, leading slashes included. */
	private static final List<String> ESCAPES_FROM_ROOT = List.of(
			"../secret.png",
			"/../secret.png",
			"//../secret.png",
			"///..///secret.png",
			"./../secret.png",
			"lib/../../secret.png",
			"a/b/../../../secret.png",
			"../../outside-secret.png");

	@TempDir
	Path temp;

	private Path packIn(Shape shape) throws IOException {
		Files.createDirectories(this.temp.resolve("packs"));
		Files.writeString(this.temp.resolve("packs/outside-secret.png"), OUTSIDE);

		Map<String, String> files = new LinkedHashMap<>();
		files.put("secret.png", SECRET);
		files.put("shaders/lib/inc.glsl", "// inc\n");
		files.put("shaders/composite.fsh", "#version 330\n");
		files.put("shaders/lib/Mixed.glsl", "// mixed\n");

		return shape.build(this.temp.resolve("packs"), "pack", files);
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void theAimedFilesReallyExist(Shape shape) throws IOException {
		Path packPath = packIn(shape);
		try (FileSystem zip = shape == Shape.ZIP ? FileSystems.newFileSystem(packPath) : null) {
			Path lib = shape == Shape.ZIP ? zip.getPath("/shaders/lib") : packPath.resolve("shaders/lib");
			Path root = shape == Shape.ZIP ? zip.getPath("/shaders") : packPath.resolve("shaders");

			for (String spec : ESCAPES_FROM_LIB) {
				if (spec.contains("outside") && shape == Shape.ZIP) {
					continue;
				}

				assertTrue(Files.isRegularFile(lib.resolve(spec).normalize()), spec);
			}

			for (String spec : ESCAPES_FROM_ROOT) {
				if (spec.contains("outside") && shape == Shape.ZIP) {
					continue;
				}

				String stripped = spec.replaceAll("^/+", "");
				assertTrue(Files.isRegularFile(root.resolve(stripped).normalize()), spec);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void refusesAnIncludeThatLeavesShadersFromTheIncludingFile(Shape shape) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packIn(shape))) {
			Path from = source.resolveInsideShaders("lib/inc.glsl").orElseThrow();
			for (String spec : ESCAPES_FROM_LIB) {
				assertEquals(Optional.empty(), source.resolveRelativeTo(from, spec), spec);
				assertFalse(source.insidePack("lib/" + spec.replaceAll("^/+", "")), spec);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void refusesARootRelativePathThatLeavesShaders(Shape shape) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packIn(shape))) {
			for (String spec : ESCAPES_FROM_ROOT) {
				assertEquals(Optional.empty(), source.resolveInsideShaders(spec), spec);
				assertEquals(Optional.empty(), source.file(spec), spec);
				assertFalse(source.insidePack(spec), spec);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void refusesAnAbsolutePathToTheSecret(Shape shape) throws IOException {
		Path packPath = packIn(shape);
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			Path from = source.resolveInsideShaders("lib/inc.glsl").orElseThrow();
			String absolute = this.temp.resolve("packs/outside-secret.png").toAbsolutePath().toString();

			// Relative to the including file an absolute path is taken as it is, and lands outside.
			assertEquals(Optional.empty(), source.resolveRelativeTo(from, absolute));
			assertEquals(Optional.empty(), source.resolveRelativeTo(from, "/secret.png"));
			assertEquals(Optional.empty(), source.resolveRelativeTo(from, "/"));
			assertFalse(source.insidePack("../" + absolute));

			// Against shaders/ the leading slash is dropped and the rest is a path inside the pack, which
			// names nothing there: the disk path does not exist below shaders/.
			assertEquals(Optional.empty(), source.resolveInsideShaders(absolute));
			assertTrue(source.insidePack(absolute));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void takesBackslashesAndDriveLettersAsNamesOfTheirOwnHere(Shape shape) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packIn(shape))) {
			Path from = source.resolveInsideShaders("lib/inc.glsl").orElseThrow();

			// Whatever the platform makes of these, none of them may reach the secret. On a platform
			// where the backslash separates, they normalise out of shaders/ and are refused; on one
			// where it does not they are odd file names inside shaders/ and name nothing.
			for (String spec : List.of("..\\..\\secret.png", "..\\..\\..\\outside-secret.png",
					"C:\\secret.png", "C:/secret.png", "\\\\host\\share\\secret.png", "..\\/..\\/secret.png")) {
				assertEquals(Optional.empty(), source.resolveRelativeTo(from, spec), spec);
				assertEquals(Optional.empty(), source.resolveInsideShaders(spec), spec);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void refusesASpecThePathParserRejects(Shape shape) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packIn(shape))) {
			Path from = source.resolveInsideShaders("lib/inc.glsl").orElseThrow();

			// A NUL byte is an InvalidPathException on the default filesystem and on a zip's, and the
			// refusal has to be an answer rather than an exception out of a load.
			assertEquals(Optional.empty(), source.resolveRelativeTo(from, "a\0b.glsl"));
			assertEquals(Optional.empty(), source.resolveInsideShaders("a\0b.glsl"));
			assertFalse(source.insidePack("a\0b.glsl"));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void followsAPathThatLeavesAndComesBackIntoShaders(Shape shape) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packIn(shape))) {
			Path from = source.resolveInsideShaders("lib/inc.glsl").orElseThrow();

			// Normalised before it is compared, so where a path wanders on the way is not the question:
			// where it lands is. This is the shape that would be refused by a check on the text.
			Optional<Path> found = source.resolveRelativeTo(from, "../../shaders/lib/inc.glsl");
			assertTrue(found.isPresent());
			assertEquals("lib/inc.glsl", source.rel(found.get()));

			assertEquals("lib/inc.glsl",
					source.rel(source.resolveRelativeTo(from, ".//./inc.glsl").orElseThrow()));
			assertEquals("lib/inc.glsl",
					source.rel(source.resolveInsideShaders("//lib///./x/../inc.glsl").orElseThrow()));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void aDirectoryIsNotAFileEvenWhenItIsInsideThePack(Shape shape) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packIn(shape))) {
			Path from = source.resolveInsideShaders("lib/inc.glsl").orElseThrow();

			assertEquals(Optional.empty(), source.resolveRelativeTo(from, "."));
			assertEquals(Optional.empty(), source.resolveRelativeTo(from, "../lib"));
			assertEquals(Optional.empty(), source.resolveInsideShaders("lib"));
			assertEquals(Optional.empty(), source.resolveInsideShaders(""));
			assertEquals(Optional.empty(), source.resolveInsideShaders("/"));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void anIncludeOutOfThePackIsAnErrorInTheUnitAndNeverItsText(Shape shape) throws IOException {
		Files.createDirectories(this.temp.resolve("packs"));
		Files.writeString(this.temp.resolve("packs/outside-secret.glsl"), OUTSIDE);

		Map<String, String> files = new LinkedHashMap<>();
		files.put("secret.glsl", SECRET);
		files.put("shaders/lib/inc.glsl", "// inc\n");
		files.put("shaders/composite.fsh", """
				#version 330
				#include "../secret.glsl"
				#include "/../secret.glsl"
				#include "lib/../../secret.glsl"
				#include "../../outside-secret.glsl"
				#include "lib/inc.glsl"
				void main() {}
				""");
		Path packPath = shape.build(this.temp.resolve("packs"), "pack", files);

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			Path entry = source.resolveInsideShaders("composite.fsh").orElseThrow();
			IncludeExpander.ExpandedUnit unit = new IncludeExpander(source, SettingSet.defaults()).expand(entry);

			assertFalse(unit.text().contains(SECRET));
			assertFalse(unit.text().contains(OUTSIDE));
			assertEquals(4, unit.stats().missing());
			assertEquals(1, unit.stats().followed());
			assertTrue(unit.lines().contains("#error include not found: ../secret.glsl"));
			assertTrue(unit.lines().contains("#error include not found: ../../outside-secret.glsl"));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void refusesATextureKeyPathThatLeavesShaders(Shape shape) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packIn(shape))) {
			// The texture keys go through file(String), so this is the route customTexture.x = ../../.. takes.
			for (String spec : List.of("../secret.png", "../../outside-secret.png", "/../secret.png",
					"tex/../../secret.png")) {
				assertEquals(Optional.empty(), source.file(spec), spec);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void refusesALanguageCodeThatWalksOut(Shape shape) throws IOException {
		Files.createDirectories(this.temp.resolve("packs"));
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/lang/en_us.lang", "option.A=Inside\n");
		files.put("lang.lang", "option.A=Outside\n");
		Path packPath = shape.build(this.temp.resolve("packs"), "pack", files);

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			// The player's language code is pasted into the path: ../../lang would name lang.lang beside
			// shaders/, and what comes back is the pack's own en_us instead.
			PackLang lang = PackLang.read(source, "../../lang");

			assertEquals("en_us.lang", lang.file());
			assertEquals("Inside", lang.option("A"));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void aZipOrDirectoryKeepsTheCaseInsensitiveFallbackInsideShaders(Shape shape) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packIn(shape))) {
			Path from = source.resolveInsideShaders("lib/inc.glsl").orElseThrow();

			// The fallback matches by name in the parent directory, which for a confined target is
			// always inside shaders/: the lower-cased spelling of a real file is found, and only there.
			Path found = source.resolveRelativeTo(from, "MIXED.GLSL").orElseThrow();
			assertEquals("lib/mixed.glsl", source.rel(found).toLowerCase(Locale.ROOT));
			if (shape == Shape.ZIP) {
				// A directory on a case-insensitive disk opens the file under either spelling and never
				// needs the fallback, so only the archive is exact about it.
				assertEquals("lib/Mixed.glsl", source.rel(found));
				assertEquals(1, source.caseInsensitiveHits());
			}

			assertEquals(Optional.empty(), source.resolveRelativeTo(from, "../SECRET.PNG"));
		}
	}

	@Test
	void anArchiveWithAnEntryNamedToWalkOutIsRefusedWhenItIsOpened() throws IOException {
		Files.createDirectories(this.temp.resolve("packs"));
		for (String name : List.of("../evil.glsl", "shaders/../evil2.glsl", "shaders/lib/../../evil3.glsl",
				"shaders/./here.glsl")) {
			Path packPath = SyntheticPacks.zip(this.temp.resolve("packs"), "traversal" + Math.abs(name.hashCode()),
					Map.of("shaders/composite.fsh", "#version 330\n", name, "an entry named to leave shaders"));

			// The filesystem the JDK mounts an archive as will not hold an entry with a dot element, so the
			// pack never opens and no name of it is ever a path here; the refusal is an IOException the
			// callers already treat as a pack that cannot be read.
			IOException refused = assertThrows(IOException.class, () -> ShaderPackSource.open(packPath), name);
			assertTrue(refused.getMessage().contains("'.' or '..' element"), refused.getMessage());
		}
	}

	/**
	 * The one target whose parent is not inside {@code shaders/} is {@code shaders/} itself, and the
	 * case-insensitive fallback lists a target's parent. An archive can hold a regular file beside
	 * {@code shaders/} whose name differs from it only by case, and a path that names the root found
	 * that file: {@code #include ".."}, {@code #include "/"} and a texture key of {@code .} all answered
	 * it, and the text of a file that is not under {@code shaders/} was read.
	 * <p>
	 * The file stays inside the pack, so this was not a road to the player's disk. The fallback now
	 * refuses a target that is the root, and every one of those roads finds nothing.
	 */
	@Test
	void aPathNamingTheShadersRootFindsNothingBesideIt() throws IOException {
		assertEquals(List.of(), rootRoadsInto(shadersRootBesideAFile()));
	}

	private Path shadersRootBesideAFile() throws IOException {
		Files.createDirectories(this.temp.resolve("packs"));

		return SyntheticPacks.zip(this.temp.resolve("packs"), "pack",
				Map.of("shaders/lib/inc.glsl", "// inc\n", "SHADERS", SECRET));
	}

	private static List<String> rootRoadsInto(Path packPath) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			Path from = source.resolveInsideShaders("lib/inc.glsl").orElseThrow();
			List<String> found = new java.util.ArrayList<>();
			for (String spec : List.of("", ".", "/", "lib/..", "//./lib/../")) {
				source.resolveInsideShaders(spec).ifPresent(path -> found.add("inside:" + spec + "=" + path));
			}

			for (String spec : List.of("..", "../../shaders", "../")) {
				source.resolveRelativeTo(from, spec).ifPresent(path -> found.add("rel:" + spec + "=" + path));
			}

			return found;
		}
	}

	/**
	 * A symlink INSIDE a directory pack is not followed out of it, wherever it points.
	 * <p>
	 * The confinement compares the normalised text of a path, and a link is invisible to that; the read
	 * after it follows the link. So a directory pack that carried {@code shaders/lib/link.glsl} pointing
	 * at a file outside the pack had that file opened by {@code #include "lib/link.glsl"}, and its text
	 * landed in the unit handed to the compiler. A directory pack is now also asked where a path really
	 * lands, links followed, and refused past its own folder. A zip cannot do this: its filesystem holds
	 * no links. A directory gets links when a pack is extracted by a tool that honours the mode bits of an
	 * archive entry.
	 */
	@Test
	void aSymlinkInsideADirectoryPackIsNotFollowedOutOfIt() throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(symlinkedPack())) {
			Path entry = source.resolveInsideShaders("composite.fsh").orElseThrow();
			IncludeExpander.ExpandedUnit unit = new IncludeExpander(source, SettingSet.defaults()).expand(entry);

			assertFalse(unit.text().contains(OUTSIDE), "the text of a file beside the pack reached the unit");
			assertEquals(Optional.empty(), source.resolveInsideShaders("lib/link.glsl"));
			assertEquals(Optional.empty(), source.resolveInsideShaders("linked/outside-secret.glsl"));
			assertFalse(source.sourceFiles().stream().anyMatch(file -> source.rel(file).equals("lib/link.glsl")));
		}
	}

	private Path symlinkedPack() throws IOException {
		Path outside = this.temp.resolve("outside");
		Files.createDirectories(outside);
		Files.writeString(outside.resolve("outside-secret.glsl"), OUTSIDE);

		Path packPath = SyntheticPacks.directory(this.temp.resolve("packs"), "linked-pack",
				Map.of("shaders/composite.fsh", "#version 330\n#include \"lib/link.glsl\"\n",
						"shaders/lib/other.glsl", "// other\n"));
		try {
			Files.createSymbolicLink(packPath.resolve("shaders/lib/link.glsl"),
					outside.resolve("outside-secret.glsl"));
			Files.createSymbolicLink(packPath.resolve("shaders/linked"), outside);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			Assumptions.abort("this platform cannot create symbolic links here: " + e);
		}

		return packPath;
	}
}
