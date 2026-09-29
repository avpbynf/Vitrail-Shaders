package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds how a pack is opened and read: a directory and a zip through one door, the shaders root wherever
 * a re-zip left it, files in one fixed order, and text decoded so that a bad byte costs a character and
 * not the pack.
 * <p>
 * Every test that can be run against both shapes is, because the class exists to make them one.
 */
class ShaderPackSourceTest {

	@TempDir
	Path temp;

	private Path pack(Shape shape, String name, Map<String, String> files) throws IOException {
		Files.createDirectories(this.temp);

		return shape.build(this.temp, name, files);
	}

	@Test
	void namesADirectoryByItsFolderAndAZipWithoutItsSuffix() throws IOException {
		Path directory = pack(Shape.DIRECTORY, "Complementary", Map.of("shaders/a.fsh", "x"));
		Path zip = pack(Shape.ZIP, "Complementary", Map.of("shaders/a.fsh", "x"));

		assertEquals("Complementary", ShaderPackSource.nameOf(directory));
		assertEquals("Complementary", ShaderPackSource.nameOf(zip));
		assertEquals("Odd.name", ShaderPackSource.nameOf(this.temp.resolve("Odd.name.ZIP")));
		assertEquals("readme.txt", ShaderPackSource.nameOf(this.temp.resolve("readme.txt")));
		// A directory keeps a suffix that looks like an archive's.
		Path dotted = pack(Shape.DIRECTORY, "pack.zip", Map.of("shaders/a.fsh", "x"));
		assertEquals("pack.zip", ShaderPackSource.nameOf(dotted));

		try (ShaderPackSource source = ShaderPackSource.open(zip)) {
			assertEquals("Complementary", source.packName());
			assertTrue(source.isZip());
		}

		try (ShaderPackSource source = ShaderPackSource.open(directory)) {
			assertEquals("Complementary", source.packName());
			assertFalse(source.isZip());
		}
	}

	@Test
	void refusesAFileThatIsNeitherADirectoryNorAZip() throws IOException {
		Path file = this.temp.resolve("pack.txt");
		Files.writeString(file, "not a pack");

		IOException refused = assertThrows(IOException.class, () -> ShaderPackSource.open(file));

		assertTrue(refused.getMessage().contains("pack.txt"), refused.getMessage());
	}

	@Test
	void refusesAZipThatHoldsNoShadersDirectory() throws IOException {
		Path zip = pack(Shape.ZIP, "empty", Map.of("readme.txt", "hello"));

		IOException refused = assertThrows(IOException.class, () -> ShaderPackSource.open(zip));

		assertEquals("No shaders directory in this pack", refused.getMessage());
	}

	@Test
	void aZipWhoseEntriesAreSeparatedByBackslashesHasNoShadersDirectory() throws IOException {
		// What a tool that writes Windows separators into entry names makes: one flat name per file, so
		// there is no directory to be found and the pack is refused rather than read as empty.
		Path zip = pack(Shape.ZIP, "windows", Map.of("shaders\\composite.fsh", "x", "shaders\\lib\\a.glsl", "x"));

		assertEquals("No shaders directory in this pack",
				assertThrows(IOException.class, () -> ShaderPackSource.open(zip)).getMessage());
	}

	@Test
	void countsEveryOpeningIncludingOnesThatFail() throws IOException {
		Path good = pack(Shape.DIRECTORY, "good", Map.of("shaders/a.fsh", "x"));
		int before = ShaderPackSource.openings();

		ShaderPackSource.open(good).close();
		assertThrows(IOException.class, () -> ShaderPackSource.open(this.temp.resolve("nothing.txt")));

		assertEquals(before + 2, ShaderPackSource.openings());
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void findsAShadersDirectoryUnderAWrapperFolder(Shape shape) throws IOException {
		Path packPath = pack(shape, "wrapped", Map.of(
				"Wrapper/shaders/composite.fsh", "#version 330\n",
				"Wrapper/README.md", "hi"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			assertEquals(List.of("composite.fsh"), relative(source, source.sourceFiles()));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void searchesForAWrappedShadersDirectoryThreeLevelsDownAndNoFurther(Shape shape) throws IOException {
		Path near = pack(shape, "near", Map.of("a/b/shaders/x.fsh", "1"));
		Path far = pack(shape, "far", Map.of("a/b/c/shaders/x.fsh", "1"));

		try (ShaderPackSource source = ShaderPackSource.open(near)) {
			assertEquals(List.of("x.fsh"), relative(source, source.sourceFiles()));
		}

		assertThrows(IOException.class, () -> ShaderPackSource.open(far));
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void takesTheFirstWrappedShadersDirectoryInSortedOrder(Shape shape) throws IOException {
		Path packPath = pack(shape, "two", Map.of(
				"b/shaders/from-b.fsh", "1",
				"a/shaders/from-a.fsh", "1"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			assertEquals(List.of("from-a.fsh"), relative(source, source.sourceFiles()));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void aDirectShadersDirectoryWinsOverAWrappedOne(Shape shape) throws IOException {
		Path packPath = pack(shape, "both", Map.of(
				"shaders/direct.fsh", "1",
				"a/shaders/wrapped.fsh", "1"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			assertEquals(List.of("direct.fsh"), relative(source, source.sourceFiles()));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void listsSourceFilesByExtensionInOneFixedOrder(Shape shape) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/z.fsh", "1");
		files.put("shaders/lib/b.glsl", "1");
		files.put("shaders/lib.glsl", "1");
		files.put("shaders/Upper.FSH", "1");
		files.put("shaders/world0/composite.vsh", "1");
		files.put("shaders/a.inc", "1");
		files.put("shaders/settings.settings", "1");
		files.put("shaders/x.gsh", "1");
		files.put("shaders/x.csh", "1");
		files.put("shaders/x.tcs", "1");
		files.put("shaders/x.tes", "1");
		files.put("shaders/shaders.properties", "1");
		files.put("shaders/noise.png", "1");
		files.put("shaders/data.json", "1");
		files.put("shaders/noext", "1");
		Path packPath = pack(shape, "order", files);

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			// Plain string order of the path under shaders/: capitals before lower case, and "." before
			// "/", so lib.glsl precedes lib/b.glsl. Only the nine source extensions are listed, case aside.
			assertEquals(List.of("Upper.FSH", "a.inc", "lib.glsl", "lib/b.glsl", "settings.settings",
					"world0/composite.vsh", "x.csh", "x.gsh", "x.tcs", "x.tes", "z.fsh"),
					relative(source, source.sourceFiles()));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void listsTheOtherFilesTheSourceListLeavesOut(Shape shape) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/a.fsh", "1");
		files.put("shaders/shaders.properties", "1");
		files.put("shaders/noise.png", "1");
		files.put("shaders/lib/common.h", "1");
		files.put("shaders/noext", "1");
		Path packPath = pack(shape, "others", files);

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			assertEquals(List.of("lib/common.h", "noext", "noise.png", "shaders.properties"),
					relative(source, source.otherFiles()));
		}
	}

	@Test
	void leavesAFileOutOfTheOthersWhenItIsPastTheCeiling() throws IOException {
		Path archive = this.temp.resolve("big.zip");
		try (OutputStream out = Files.newOutputStream(archive);
				ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("shaders/a.fsh"));
			zip.write('x');
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("shaders/atlas.bin"));
			zip.write(new byte[8 * 1024 * 1024]);
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("shaders/toobig.bin"));
			zip.write(new byte[8 * 1024 * 1024 + 1]);
			zip.closeEntry();
		}

		try (ShaderPackSource source = ShaderPackSource.open(archive)) {
			assertEquals(List.of("atlas.bin"), relative(source, source.otherFiles()));
			assertEquals(8 * 1024 * 1024 + 1L, source.size(source.resolveInsideShaders("toobig.bin").orElseThrow()));
			assertEquals(8 * 1024 * 1024, source.bytes(source.resolveInsideShaders("atlas.bin").orElseThrow()).length);

			IOException refused = assertThrows(IOException.class,
					() -> source.bytes(source.resolveInsideShaders("toobig.bin").orElseThrow()));
			assertTrue(refused.getMessage().startsWith("toobig.bin is 8388609 bytes, past the 8388608"),
					refused.getMessage());
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void refusesToReadASourceFilePastTheCeilingAndTakesOneExactlyAtIt(Shape shape) throws IOException {
		String atCeiling = "a".repeat(8 * 1024 * 1024);
		String pastCeiling = atCeiling + "a";
		Path packPath = pack(shape, "ceiling", Map.of(
				"shaders/at.glsl", atCeiling,
				"shaders/past.glsl", pastCeiling));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			assertEquals(1, source.readLines(source.resolveInsideShaders("at.glsl").orElseThrow()).size());

			Path past = source.resolveInsideShaders("past.glsl").orElseThrow();
			IOException refused = assertThrows(IOException.class, () -> source.readLines(past));
			assertEquals("past.glsl is 8388609 bytes, past the 8388608 a shader source is allowed",
					refused.getMessage());
			// Not kept: the next asker gets the refusal too rather than an empty answer.
			assertThrows(IOException.class, () -> source.readLines(past));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void decodesTextSoThatNoByteFailsThePack(Shape shape) throws IOException {
		Path packPath = this.temp.resolve("decode-" + shape);
		Map<String, byte[]> bytes = new LinkedHashMap<>();
		bytes.put("shaders/bom.glsl", concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "#version 330".getBytes(StandardCharsets.UTF_8)));
		bytes.put("shaders/bad.glsl", new byte[] {'a', (byte) 0xC3, (byte) 0x28, 'b'});
		bytes.put("shaders/accents.glsl", "// caf\u00e9 \u00fcber".getBytes(StandardCharsets.UTF_8));
		bytes.put("shaders/endings.glsl", "one\r\ntwo\rthree\nfour\n\nfive".getBytes(StandardCharsets.UTF_8));
		bytes.put("shaders/empty.glsl", new byte[0]);
		bytes.put("shaders/trailing.glsl", "a\n".getBytes(StandardCharsets.UTF_8));
		if (shape == Shape.ZIP) {
			packPath = SyntheticPacks.zipOfBytes(this.temp, "decode", bytes);
		} else {
			Files.createDirectories(packPath.resolve("shaders"));
			for (Map.Entry<String, byte[]> entry : bytes.entrySet()) {
				Files.write(packPath.resolve(entry.getKey()), entry.getValue());
			}
		}

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			// A byte order mark is dropped, and only the one at the very start of the file.
			assertEquals(List.of("#version 330"), lines(source, "bom.glsl"));
			// Malformed UTF-8 is replaced by U+FFFD and the file survives.
			assertEquals(List.of("a\uFFFD(b"), lines(source, "bad.glsl"));
			assertEquals(List.of("// caf\u00e9 \u00fcber"), lines(source, "accents.glsl"));
			// CRLF, a lone CR and LF all end a line, and nothing is trimmed: a last newline leaves an
			// empty final line, and an empty file is one empty line rather than none.
			assertEquals(List.of("one", "two", "three", "four", "", "five"), lines(source, "endings.glsl"));
			assertEquals(List.of(""), lines(source, "empty.glsl"));
			assertEquals(List.of("a", ""), lines(source, "trailing.glsl"));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void readsAFileOncePerOpeningAndHandsBackTheSameList(Shape shape) throws IOException {
		Path packPath = pack(shape, "memo", Map.of("shaders/a.glsl", "one\ntwo\n"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			Path file = source.resolveInsideShaders("a.glsl").orElseThrow();

			List<String> first = source.readLines(file);
			assertSame(first, source.readLines(file));
			assertEquals(1, source.filesRead());
			assertThrows(UnsupportedOperationException.class, () -> first.add("x"));
		}

		// A second opening of the same pack starts from nothing.
		try (ShaderPackSource again = ShaderPackSource.open(packPath)) {
			assertEquals(0, again.filesRead());
		}
	}

	@Test
	void editsMadeAfterAFileWasReadAreNotSeenByThatOpening() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "stale", Map.of("shaders/a.glsl", "old\n"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			Path file = source.resolveInsideShaders("a.glsl").orElseThrow();
			assertEquals(List.of("old", ""), source.readLines(file));

			Files.writeString(file, "new\n");

			assertEquals(List.of("old", ""), source.readLines(file));
		}
	}

	@Test
	void keepsTheFirstDeclarationOfASettingInSortedFileOrder() throws IOException {
		// b.glsl is written first and a.glsl sorts first, so the value in a.glsl is the one kept.
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/b.glsl", "#define QUALITY 3 //[1 2 3]\n");
		files.put("shaders/a.glsl", "#define QUALITY 1 //[1 2 3]\n");
		files.put("shaders/lib/c.glsl", "#define QUALITY 2 //[1 2 3]\n");

		for (Shape shape : Shape.values()) {
			try (ShaderPackSource source = ShaderPackSource.open(pack(shape, "first-" + shape, files))) {
				OptionIndex index = source.options();

				assertEquals("1", index.get("QUALITY").orElseThrow().defaultText());
				assertEquals("a.glsl", index.get("QUALITY").orElseThrow().declaredIn());
				// Asked again, the same index: it is filed on the opening.
				assertSame(index, source.options());
			}
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void reportsPathsRelativeToShadersWithForwardSlashesOnly(Shape shape) throws IOException {
		Path packPath = pack(shape, "rel", Map.of("shaders/world0/lib/deep.glsl", "1"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			Path file = source.resolveInsideShaders("world0/lib/deep.glsl").orElseThrow();

			assertEquals("world0/lib/deep.glsl", source.rel(file));
			assertFalse(source.rel(file).startsWith("/"));
			assertEquals(List.of("world0"), source.topLevelDirectories());
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void listsTheDirectoriesDirectlyUnderShadersSorted(Shape shape) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/world1/a.fsh", "1");
		files.put("shaders/world-1/a.fsh", "1");
		files.put("shaders/lib/a.glsl", "1");
		files.put("shaders/world0/deeper/a.fsh", "1");
		files.put("shaders/empty/", "");
		files.put("shaders/root.fsh", "1");
		Path packPath = pack(shape, "dirs", files);

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			assertEquals(List.of("empty", "lib", "world-1", "world0", "world1"), source.topLevelDirectories());
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void findsAFileIgnoringCaseAndCountsTheHit(Shape shape) throws IOException {
		Path packPath = pack(shape, "case", Map.of("shaders/lib/Noise.PNG", "1", "shaders/composite.fsh", "1"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			assertEquals(Optional.empty(), source.file("lib/absent.png"));
			assertEquals(0, source.caseInsensitiveHits());

			Path found = source.file("/lib/noise.png").orElseThrow();
			assertEquals("lib/noise.png", source.rel(found).toLowerCase(java.util.Locale.ROOT));
			if (shape == Shape.ZIP) {
				assertEquals("lib/Noise.PNG", source.rel(found));
				assertEquals(1, source.caseInsensitiveHits());
			}

			// An exact spelling is never a hit.
			int hits = source.caseInsensitiveHits();
			assertEquals("composite.fsh", source.rel(source.file("composite.fsh").orElseThrow()));
			assertEquals(hits, source.caseInsensitiveHits());
		}
	}

	@Test
	void prefersTheExactSpellingWhenAnArchiveHoldsBothCases() throws IOException {
		Path zip = pack(Shape.ZIP, "both", Map.of("shaders/Foo.glsl", "upper", "shaders/foo.glsl", "lower"));

		try (ShaderPackSource source = ShaderPackSource.open(zip)) {
			assertEquals("Foo.glsl", source.rel(source.file("Foo.glsl").orElseThrow()));
			assertEquals("foo.glsl", source.rel(source.file("foo.glsl").orElseThrow()));
			assertEquals(0, source.caseInsensitiveHits());
		}
	}

	@Test
	void theCaseInsensitiveListingOfADirectoryIsBuiltOnceAndNotRefreshed() throws IOException {
		Path zipLike = pack(Shape.ZIP, "listing", Map.of("shaders/A.glsl", "1"));

		try (ShaderPackSource source = ShaderPackSource.open(zipLike)) {
			// The first miss of the exact name lists the directory and keeps the listing; it is a
			// property of the opening, which is one reading of an archive that cannot change.
			assertTrue(source.file("a.glsl").isPresent());
			assertTrue(source.file("A.GLSL").isPresent());
			assertEquals(2, source.caseInsensitiveHits());
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void searchesThePackForTheHeadOfAFileAndNeverOpensBinaryOnes(Shape shape) throws IOException {
		Path packPath = this.temp.resolve("search-" + shape);
		Map<String, byte[]> bytes = new LinkedHashMap<>();
		bytes.put("shaders/text.h", "#define NAME 1\n".getBytes(StandardCharsets.ISO_8859_1));
		bytes.put("shaders/binary.bin", new byte[] {'N', 'A', 'M', 'E', 0, 'N', 'A', 'M', 'E'});
		bytes.put("shaders/late-zero.bin", concat("NAME".getBytes(StandardCharsets.ISO_8859_1),
				"x".repeat(5000).getBytes(StandardCharsets.ISO_8859_1), new byte[] {0}));
		bytes.put("shaders/a.fsh", "x".getBytes(StandardCharsets.ISO_8859_1));
		if (shape == Shape.ZIP) {
			packPath = SyntheticPacks.zipOfBytes(this.temp, "search", bytes);
		} else {
			Files.createDirectories(packPath.resolve("shaders"));
			for (Map.Entry<String, byte[]> entry : bytes.entrySet()) {
				Files.write(packPath.resolve(entry.getKey()), entry.getValue());
			}
		}

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			assertEquals("#define NAME 1\n", source.searchableText(source.resolveInsideShaders("text.h").orElseThrow()));
			// A zero byte in the first 4096 makes the file "not text": nothing of it is returned.
			assertEquals("", source.searchableText(source.resolveInsideShaders("binary.bin").orElseThrow()));
			// One past the probe is text as far as the probe knows, and comes back whole.
			String late = source.searchableText(source.resolveInsideShaders("late-zero.bin").orElseThrow());
			assertEquals(4 + 5000 + 1, late.length());
			assertTrue(late.startsWith("NAME"));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void readsTheHeadOfAFileAndRefusesOneShorterThanAsked(Shape shape) throws IOException {
		Path packPath = pack(shape, "head", Map.of("shaders/blob.dat", "0123456789"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			Path blob = source.resolveInsideShaders("blob.dat").orElseThrow();

			assertEquals("0123", new String(source.head(blob, 4), StandardCharsets.ISO_8859_1));
			assertEquals("0123456789", new String(source.head(blob, 10), StandardCharsets.ISO_8859_1));
			assertEquals(0, source.head(blob, 0).length);
			assertEquals("blob.dat holds 10 bytes where the declaration asks for 11",
					assertThrows(IOException.class, () -> source.head(blob, 11)).getMessage());
			assertEquals("blob.dat is asked for -1 bytes, which is not a length anything here can hold",
					assertThrows(IOException.class, () -> source.head(blob, -1)).getMessage());
			assertThrows(IOException.class, () -> source.head(blob, Integer.MAX_VALUE + 1L));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void filesADerivedAnswerOncePerKeyAndForgetsItOnDemand(Shape shape) throws IOException {
		Path packPath = pack(shape, "derived", Map.of("shaders/a.fsh", "1"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			AtomicInteger computed = new AtomicInteger();
			String first = source.derived("key", () -> "answer" + computed.incrementAndGet());
			String second = source.derived("key", () -> "answer" + computed.incrementAndGet());
			String other = source.derived("other", () -> "answer" + computed.incrementAndGet());

			assertEquals("answer1", first);
			assertEquals("answer1", second);
			assertEquals("answer2", other);

			source.forgetDerived();

			assertEquals("answer3", source.derived("key", () -> "answer" + computed.incrementAndGet()));
		}
	}

	@Test
	void aZipCannotBeReadAfterItIsClosedAndAClosedDirectoryHoldsNothing() throws IOException {
		Path zip = pack(Shape.ZIP, "closing", Map.of("shaders/a.glsl", "1"));
		ShaderPackSource source = ShaderPackSource.open(zip);
		Path file = source.resolveInsideShaders("a.glsl").orElseThrow();
		source.readLines(file);
		assertEquals(1, source.filesRead());

		source.close();

		assertEquals(0, source.filesRead());
		assertThrows(ClosedFileSystemException.class, () -> source.readLines(file));
		// Closing twice is not an error.
		source.close();

		Path directory = pack(Shape.DIRECTORY, "closing-dir", Map.of("shaders/a.glsl", "1"));
		ShaderPackSource opened = ShaderPackSource.open(directory);
		opened.readLines(opened.resolveInsideShaders("a.glsl").orElseThrow());
		opened.close();
		assertEquals(0, opened.filesRead());
	}

	@Test
	void takesAFreshListOnEveryCallOfTheListings() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "fresh", Map.of("shaders/a.fsh", "1"));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			List<Path> first = source.sourceFiles();
			List<Path> second = source.sourceFiles();

			assertNotSame(first, second);
			assertEquals(first, second);
			assertThrows(UnsupportedOperationException.class, () -> first.add(first.get(0)));
		}
	}

	/**
	 * BUG, pinned as it stands: the confinement compares a NORMALISED target with the shaders root as it
	 * was built, so a pack opened by a path that is not itself normalised has every resolution refused.
	 * Listing still works, which is why the pack looks loaded: nothing includes, no {@code shaders.properties}
	 * is found, and every {@code #include} becomes an error line. The game hands over normalised absolute
	 * paths, which is the only reason this does not bite there; a harness that passes {@code ./packs/x}
	 * or {@code a/../x} sees it. It fails closed, so it is not a security hole, only a silent empty pack.
	 */
	@Test
	void knownBug_aPackOpenedByAnUnnormalisedPathResolvesNothing() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "dotted", Map.of(
				"shaders/a.glsl", "// a\n",
				"shaders/shaders.properties", "sun=false\n"));
		Files.createDirectories(this.temp.resolve("sub"));
		Path unnormalised = this.temp.resolve("sub").resolve("..").resolve("dotted");

		try (ShaderPackSource source = ShaderPackSource.open(unnormalised)) {
			assertEquals(List.of("a.glsl"), relative(source, source.sourceFiles()));
			assertEquals(Optional.empty(), source.resolveInsideShaders("a.glsl"));
			assertEquals(Optional.empty(), source.file("shaders.properties"));
			assertFalse(source.insidePack("a.glsl"));
		}

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			assertTrue(source.resolveInsideShaders("a.glsl").isPresent());
		}
	}

	private static List<String> relative(ShaderPackSource source, List<Path> files) {
		return files.stream().map(source::rel).toList();
	}

	private static List<String> lines(ShaderPackSource source, String name) throws IOException {
		return source.readLines(source.resolveInsideShaders(name).orElseThrow());
	}

	private static byte[] concat(byte[]... parts) {
		int length = 0;
		for (byte[] part : parts) {
			length += part.length;
		}

		byte[] joined = new byte[length];
		int at = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, joined, at, part.length);
			at += part.length;
		}

		return joined;
	}
}
