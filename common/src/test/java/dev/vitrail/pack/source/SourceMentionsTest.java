package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds the one-way question {@link SourceMentions} answers: a name proved absent from every file of the
 * pack is absent, and a name that appears may or may not be read. False is a proof and true is a
 * possibility, and a {@code ##} anywhere in the GLSL turns every answer into a possibility.
 */
class SourceMentionsTest {

	@TempDir
	Path temp;

	private SourceMentions of(Shape shape, Map<String, String> files, String... names) throws IOException {
		Map<String, String> all = new LinkedHashMap<>();
		files.forEach((name, text) -> all.put("shaders/" + name, text));
		Path packPath = shape.build(Files.createTempDirectory(this.temp, "packs"), "pack", all);
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			return SourceMentions.of(source, Set.of(names));
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void saysYesToANameSomeSourceWritesAndNoToOneNoFileDoes(Shape shape) throws IOException {
		SourceMentions mentions = of(shape, Map.of(
				"composite.fsh", "uniform sampler2D shadowtex1;\n",
				"lib/a.glsl", "// depthtex2 in a comment still counts\n"), "shadowtex1", "depthtex2", "colortex9");

		assertTrue(mentions.maybe("shadowtex1"));
		assertTrue(mentions.maybe("depthtex2"));
		assertFalse(mentions.maybe("colortex9"));
		assertFalse(mentions.maybe("never asked about"));
	}

	@Test
	void matchesAPlainSubstringAnywhereInALine() throws IOException {
		SourceMentions mentions = of(Shape.DIRECTORY, Map.of("a.fsh", "float xshadowtex1y;\n"), "shadowtex1", "shadow");

		assertTrue(mentions.maybe("shadowtex1"));
		assertTrue(mentions.maybe("shadow"));
	}

	@Test
	void aTokenPastingOperatorInTheGlslMakesEveryAnswerAYes() throws IOException {
		SourceMentions mentions = of(Shape.DIRECTORY, Map.of(
				"a.glsl", "#define TEX(N) shadowtex##N\n",
				"b.fsh", "x\n"), "shadowtex1", "colortex9");

		assertTrue(mentions.maybe("colortex9"));
		assertTrue(mentions.maybe("anything at all"));
	}

	@Test
	void aPastingOperatorInAFileThatIsNotASourceIsNotOne() throws IOException {
		SourceMentions mentions = of(Shape.DIRECTORY, Map.of(
				"a.fsh", "x\n",
				"data.json", "{\"note\": \"a ## b\"}\n"), "colortex9");

		assertFalse(mentions.maybe("colortex9"));
	}

	@Test
	void readsTheFilesNoExtensionMarksAsASourceBecauseAnIncludeReachesWhateverPathItNames() throws IOException {
		SourceMentions mentions = of(Shape.DIRECTORY, Map.of(
				"a.fsh", "#include \"common.h\"\n",
				"common.h", "uniform sampler2D noisetex;\n"), "noisetex", "colortex9");

		assertTrue(mentions.maybe("noisetex"));
		assertFalse(mentions.maybe("colortex9"));
	}

	@Test
	void neverSearchesAFileThatOpensWithAZeroByte() throws IOException {
		Path packPath = SyntheticPacks.zipOfBytes(Files.createTempDirectory(this.temp, "z"), "bin", Map.of(
				"shaders/a.fsh", "x".getBytes(StandardCharsets.ISO_8859_1),
				"shaders/blob.bin", new byte[] {0, 'n', 'o', 'i', 's', 'e', 't', 'e', 'x'}));

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			assertFalse(SourceMentions.of(source, Set.of("noisetex")).maybe("noisetex"));
			assertFalse(SourceMentions.of(source, Set.of("etex")).maybe("etex"));
		}
	}

	@Test
	void askedAboutNoNamesItStillReadsEverySourceForThePastingFlag() throws IOException {
		SourceMentions clean = of(Shape.DIRECTORY, Map.of("a.fsh", "x\n"));
		SourceMentions pasting = of(Shape.DIRECTORY, Map.of("a.fsh", "x\n", "z.glsl", "#define J(a) a##a\n"));

		assertFalse(clean.maybe("x"));
		assertTrue(pasting.maybe("x"));
	}

	@Test
	void anUnreadPackAnswersYesToEverything() {
		assertTrue(SourceMentions.unread().maybe("anything"));
		assertTrue(SourceMentions.unread().maybe(""));
	}
}
