package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds the labels a pack gives its own settings: which language file is read, what a key falls back to,
 * and the one rule of {@link PackLang#value} that keeps "OFF" from becoming "OFF%".
 */
class PackLangTest {

	@TempDir
	Path temp;

	private PackLang read(Shape shape, String code, Map<String, String> lang) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/composite.fsh", "x");
		lang.forEach((name, text) -> files.put("shaders/lang/" + name, text));
		Path packPath = shape.build(Files.createTempDirectory(this.temp, "packs"), "pack", files);
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			return PackLang.read(source, code);
		}
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void readsTheFileOfThePlayersLanguageWhateverCaseThePackSpelledItIn(Shape shape) throws IOException {
		PackLang lang = read(shape, "FR_fr", Map.of(
				"fr_FR.lang", "option.BLOOM=Floraison\noption.BLOOM.comment=Halo autour de la lumiere\n",
				"en_US.lang", "option.BLOOM=Bloom\n"));

		// The archive answers with the spelling the pack wrote; a folder on a case-insensitive disk answers
		// with the spelling that was asked for, and both open the same file.
		assertEquals("fr_fr.lang", lang.file().toLowerCase(Locale.ROOT));
		if (shape == Shape.ZIP) {
			assertEquals("fr_FR.lang", lang.file());
		}

		assertEquals("Floraison", lang.option("BLOOM"));
		assertEquals(Optional.of("Halo autour de la lumiere"), lang.optionComment("BLOOM"));
		assertEquals(2, lang.size());
	}

	@Test
	void fallsBackToEnglishThenToNothing() throws IOException {
		PackLang english = read(Shape.DIRECTORY, "de_de", Map.of("en_US.lang", "option.A=Alpha\n"));
		assertEquals("en_us.lang", english.file().toLowerCase(Locale.ROOT));
		assertEquals("Alpha", english.option("A"));

		PackLang none = read(Shape.DIRECTORY, "de_de", Map.of("fr_FR.lang", "option.A=Alpha\n"));
		assertSame(PackLang.empty(), none);
		assertEquals("", none.file());
		assertEquals(0, none.size());
		assertEquals("RAW", none.option("RAW"));

		assertSame(PackLang.empty(), read(Shape.DIRECTORY, "en_us", Map.of()));
	}

	@Test
	void answersTheRawNameWhereThePackWroteNoLabel() throws IOException {
		PackLang lang = read(Shape.DIRECTORY, "en_us", Map.of("en_us.lang", "option.A=Alpha\n"));

		assertEquals("B", lang.option("B"));
		assertEquals("SCREEN_ID", lang.page("SCREEN_ID"));
		assertEquals("PROFILE_ID", lang.profile("PROFILE_ID"));
		assertEquals(Optional.empty(), lang.optionComment("A"));
		assertEquals(Optional.empty(), lang.pageComment("SCREEN_ID"));
		assertEquals(Optional.empty(), lang.profileComment());
	}

	@Test
	void labelsPagesProfilesAndTheirComments() throws IOException {
		PackLang lang = read(Shape.DIRECTORY, "en_us", Map.of("en_us.lang", """
				screen.POST=Post effects
				screen.POST.comment=Everything after the scene
				profile.LOW=Low
				profile.comment=Pick one
				"""));

		assertEquals("Post effects", lang.page("POST"));
		assertEquals(Optional.of("Everything after the scene"), lang.pageComment("POST"));
		assertEquals("Low", lang.profile("LOW"));
		assertEquals(Optional.of("Pick one"), lang.profileComment());
	}

	@Test
	void aValueTheLangNamesDropsTheSuffixAndOtherValuesKeepItWithThePrefix() throws IOException {
		PackLang lang = read(Shape.DIRECTORY, "en_us", Map.of("en_us.lang", """
				prefix.STRENGTH=x
				suffix.STRENGTH=%
				value.STRENGTH.0=OFF
				suffix.PLAIN=px
				"""));

		// Bliss calls its zero OFF and suffixes the rest with a per cent sign: "OFF%" would be wrong.
		assertEquals("xOFF", lang.value("STRENGTH", "0"));
		assertEquals("x50%", lang.value("STRENGTH", "50"));
		assertEquals("64px", lang.value("PLAIN", "64"));
		assertEquals("7", lang.value("UNLABELLED", "7"));
	}

	@Test
	void readsPropertiesSyntaxIncludingContinuationsAndEscapesAndDropsAByteOrderMark() throws IOException {
		PackLang lang = read(Shape.DIRECTORY, "en_us", Map.of("en_us.lang",
				"\uFEFFoption.LONG=first \\\n    second\noption.ESC=caf\\u00e9\n# comment=1\n! also=1\noption.COLON: value\n"));

		assertEquals("first second", lang.option("LONG"));
		assertEquals("caf\u00e9", lang.option("ESC"));
		// Java properties also splits on a colon, which the pack format's own file does not: a difference
		// of this table's reader that a pack's lang file has never been seen to depend on.
		assertEquals("value", lang.option("COLON"));
		assertEquals(3, lang.size());
	}

	@Test
	void refusesALangFileWithABrokenEscapeAsAnIoErrorNamingIt() throws IOException {
		IOException refused = assertThrows(IOException.class,
				() -> read(Shape.DIRECTORY, "en_us", Map.of("en_us.lang", "option.A=bad \\u12 escape\n")));

		assertEquals("lang/en_us.lang is not readable as a language file", refused.getMessage());
	}

	@Test
	void takesALanguageCodeThatWalksOutOfTheLangFolderAsAFileNameAndFindsNothing() throws IOException {
		PackLang lang = read(Shape.DIRECTORY, "../composite", Map.of("en_us.lang", "option.A=Alpha\n"));

		// Confined like any other path: the fallback English file is what answers.
		assertEquals("en_us.lang", lang.file());
	}
}
