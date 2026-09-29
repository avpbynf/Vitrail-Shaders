package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vitrail.glsl.GlslTranslatorCases.Pair;
import dev.vitrail.glsl.GlslTranslatorCases.Single;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds what {@link GlslTranslator} emits to the bytes it emitted when these files were written.
 * <p>
 * The game keeps a translation on disk and serves it back for the same pack, so a change of one
 * byte in what the translator writes either throws every cached program away or, where a version
 * gate was forgotten, hands out a program the code no longer makes. A refactor or an optimisation of
 * the translator therefore has one test to pass before any other: this one. The inputs are
 * {@link GlslTranslatorCases}, one or two rewrite families to a stage, and the files are under
 * {@code dev/vitrail/glsl/translator/}, one per case, named after it.
 * <p>
 * <strong>A golden is read, not trusted.</strong> Each one was checked line by line against its
 * input when it was written, and a mismatch here is a question about the translator before it is a
 * reason to write the file again. Where a golden records something that looks wrong it says so in
 * the comment of its case, and pins it as it is: fixing it is a change of output and belongs to a
 * commit of its own that says so.
 * <p>
 * The engine's own symbol block, the same seventy lines at the head of every file, is folded to a
 * marker by the case that prints it, so that what a file shows is what the translation did.
 */
class GlslTranslatorGoldenTest {

	private static final String DIRECTORY = "/dev/vitrail/glsl/translator/";

	static Stream<Single> singles() {
		return GlslTranslatorCases.singles().stream();
	}

	static Stream<Pair> pairs() {
		return GlslTranslatorCases.pairs().stream();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("singles")
	void translatesOneStageToItsGolden(Single one) throws IOException {
		assertGolden(one.name(), GlslTranslatorCases.run(one));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("pairs")
	void translatesAProgramToItsGolden(Pair pair) throws IOException {
		assertGolden(pair.name(), GlslTranslatorCases.run(pair));
	}

	@Test
	void everyMeshKeepsTheTextItsStagesTranslateTo() throws IOException {
		assertGolden("mesh-sweep", GlslTranslatorCases.meshSweep());
	}

	@Test
	void everyAlphaTestFunctionIsWrittenAsItsComparison() throws IOException {
		assertGolden("alpha-sweep", GlslTranslatorCases.alphaSweep());
	}

	@Test
	void translatingTwiceInOneProcessGivesOneText() {
		for (Single one : GlslTranslatorCases.everySingle()) {
			assertEquals(GlslTranslatorCases.run(one), GlslTranslatorCases.run(one), one.name());
		}
	}

	@Test
	void everyGoldenHasACaseAndEveryCaseHasAGolden() throws IOException, URISyntaxException {
		Set<String> cases = new TreeSet<>();
		GlslTranslatorCases.everySingle().forEach(one -> cases.add(one.name() + ".txt"));
		GlslTranslatorCases.everyPair().forEach(pair -> cases.add(pair.name() + ".txt"));
		cases.addAll(List.of("mesh-sweep.txt", "alpha-sweep.txt", GlslTranslatorRecombinationTest.GOLDEN));

		URL directory = GlslTranslatorGoldenTest.class.getResource(DIRECTORY);
		assertNotNull(directory, DIRECTORY + " is not on the test classpath");
		if (!"file".equals(directory.getProtocol())) {
			// Run out of a jar, where a directory cannot be listed. The cases were each read above.
			return;
		}

		Set<String> files = new TreeSet<>();
		try (Stream<Path> listed = Files.list(Path.of(directory.toURI()))) {
			listed.filter(Files::isRegularFile).forEach(file -> files.add(file.getFileName().toString()));
		}

		assertEquals(cases, files, "the cases the corpus builds against the files kept for them");
	}

	/** The text of one golden, which is ASCII with line feeds and nothing else. */
	static String golden(String name) throws IOException {
		String resource = DIRECTORY + (name.endsWith(".txt") ? name : name + ".txt");
		try (InputStream in = GlslTranslatorGoldenTest.class.getResourceAsStream(resource)) {
			assertNotNull(in, resource + " is not on the test classpath");

			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Compares a translation with its golden and, where they part, says on which line rather than
	 * printing two files of two hundred lines side by side.
	 */
	static void assertGolden(String name, String actual) throws IOException {
		String expected = golden(name);
		if (expected.equals(actual)) {
			return;
		}

		List<String> want = expected.lines().toList();
		List<String> got = actual.lines().toList();
		int at = 0;
		while (at < want.size() && at < got.size() && want.get(at).equals(got.get(at))) {
			at++;
		}

		List<String> shown = new ArrayList<>();
		shown.add(name + ": the translation differs from its golden at line " + (at + 1) + " of "
				+ want.size() + " (translated " + got.size() + " lines)");
		shown.add("  golden:     " + (at < want.size() ? want.get(at) : "(ends)"));
		shown.add("  translated: " + (at < got.size() ? got.get(at) : "(ends)"));
		fail(String.join("\n", shown));
	}
}
