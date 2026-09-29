package dev.vitrail.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.option.OptionValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The top two layers of a pack's settings: what {@code vitrail/options.txt} forces, and how that is
 * laid over the pack's own file. The forced file is edited by hand while the game runs, so the
 * inputs here are the ones an editor produces as much as the ones the class comment describes.
 * <p>
 * Values are compared as text and as kind, since {@code OptionValue} has no equality of its own.
 */
class SettingsLayersTest {

	@TempDir
	Path game;

	private Map<String, OptionValue> forcedFrom(byte[] content) throws IOException {
		Path file = SettingsLayers.file(this.game);
		Files.createDirectories(file.getParent());
		Files.write(file, content);

		return SettingsLayers.forced(this.game);
	}

	private Map<String, OptionValue> forcedFrom(String content) throws IOException {
		return forcedFrom(content.getBytes(StandardCharsets.UTF_8));
	}

	/** Each entry as {@code name=asText/kind} in order, so one assertion says the order and the kinds. */
	private static List<String> describe(Map<String, OptionValue> map) {
		List<String> lines = new ArrayList<>();
		map.forEach((name, value) -> lines.add(name + "=" + value.asText()
				+ (value.isBoolean() ? "/bool" : "/text")));

		return lines;
	}

	// -- where the file is --------------------------------------------------------------------

	@Test
	void theFileIsOptionsTxtInTheModsFolderOfTheGameDirectory() {
		assertEquals(this.game.resolve("vitrail").resolve("options.txt"), SettingsLayers.file(this.game));
	}

	@Test
	void noFileForcesNothing() throws IOException {
		assertTrue(SettingsLayers.forced(this.game).isEmpty());
	}

	@Test
	void aDirectoryNamedLikeTheFileForcesNothing() throws IOException {
		Files.createDirectories(SettingsLayers.file(this.game));

		assertTrue(SettingsLayers.forced(this.game).isEmpty());
	}

	// -- reading the file ---------------------------------------------------------------------

	@Test
	void oneNameEqualsValuePerLineInTheOrderWritten() throws IOException {
		Map<String, OptionValue> forced = forcedFrom("""
				SHADOW_QUALITY=2
				WAVING = on
				Sky=Clear Blue
				profile=HIGH
				""");

		assertEquals(List.of("SHADOW_QUALITY=2/text", "WAVING=on/bool", "Sky=Clear Blue/text",
				"profile=HIGH/text"), describe(forced));
	}

	@Test
	void onlyOnTrueOffFalseAreBooleansInAnyCaseAndEverythingElseStaysText() throws IOException {
		Map<String, OptionValue> forced = forcedFrom("A=ON\nB=True\nC=off\nD=FALSE\nE=1\nF=0\nG=yes\nH=\n");

		assertEquals(List.of("A=on/bool", "B=on/bool", "C=off/bool", "D=off/bool", "E=1/text", "F=0/text",
				"G=yes/text", "H=/text"), describe(forced));
		assertTrue(forced.get("A").asBoolean());
		assertFalse(forced.get("C").asBoolean());
	}

	@Test
	void commentsBlankLinesLinesWithoutAnEqualsAndNamelessLinesAreSkipped() throws IOException {
		Map<String, OptionValue> forced = forcedFrom("""
				# a comment, GHOST=1
				   # an indented one, GHOST=2

				just words
				=novalue
				  =also nameless
				KEPT=1
				""");

		assertEquals(List.of("KEPT=1/text"), describe(forced));
	}

	@Test
	void everyLineIsTrimmedAndTheValueKeepsAnyEqualsAfterTheFirst() throws IOException {
		Map<String, OptionValue> forced = forcedFrom("  A  =  x = y  \n\tB=\tz\t\n");

		assertEquals(List.of("A=x = y/text", "B=z/text"), describe(forced));
	}

	@Test
	void aNameIsCaseSensitive() throws IOException {
		Map<String, OptionValue> forced = forcedFrom("Foo=1\nFOO=2\nfoo=3\n");

		assertEquals(List.of("Foo=1/text", "FOO=2/text", "foo=3/text"), describe(forced));
	}

	@Test
	void aRepeatedNameTakesTheLastValueAtTheFirstPosition() throws IOException {
		Map<String, OptionValue> forced = forcedFrom("A=1\nB=2\nA=3\n");

		assertEquals(List.of("A=3/text", "B=2/text"), describe(forced));
	}

	@Test
	void aByteOrderMarkDoesNotRideOnTheFirstName() throws IOException {
		Map<String, OptionValue> forced = forcedFrom("\uFEFFprofile=LOW\nA=1\n");

		assertEquals(List.of("profile=LOW/text", "A=1/text"), describe(forced));
		assertTrue(forced.containsKey("profile"));
	}

	@Test
	void everyKindOfLineEndSplitsALine() throws IOException {
		for (String ending : new String[] {"\n", "\r\n", "\r"}) {
			Map<String, OptionValue> forced = forcedFrom("A=1\nB=2\nC=3\n".replace("\n", ending));

			assertEquals(List.of("A=1/text", "B=2/text", "C=3/text"), describe(forced),
					"line end " + ending.replace("\r", "CR").replace("\n", "LF"));
		}
	}

	@Test
	void aByteThatIsNotUtf8CostsOneCharacterAndNotTheLoad() throws IOException {
		Map<String, OptionValue> forced =
				forcedFrom("SKY=caf\u00E9\nB=2\n".getBytes(StandardCharsets.ISO_8859_1));

		assertEquals(List.of("SKY=caf\uFFFD/text", "B=2/text"), describe(forced));
	}

	@Test
	void aFileOfNothingButNoiseForcesNothing() throws IOException {
		assertTrue(forcedFrom("").isEmpty());
		assertTrue(forcedFrom("\n\n\r\n").isEmpty());
		// Not UTF-8 at all, with one equals sign in it: one setting with a name made of replacement marks.
		assertEquals(1, forcedFrom(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0x3D, (byte) 0x80}).size());
	}

	@Test
	void theMapHandedBackCannotBeChanged() throws IOException {
		Map<String, OptionValue> forced = forcedFrom("A=1\n");

		assertThrows(UnsupportedOperationException.class, () -> forced.put("B", OptionValue.on()));
		assertThrows(UnsupportedOperationException.class, () -> SettingsLayers.forced(this.game.resolve("nope"))
				.put("B", OptionValue.on()));
	}

	// -- laying the forced file over the pack's own -------------------------------------------

	private static SettingsFile.Stored stored(String... pairs) {
		Map<String, String> values = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			values.put(pairs[i], pairs[i + 1]);
		}

		return new SettingsFile.Stored(values);
	}

	private static Map<String, OptionValue> forced(Object... pairs) {
		Map<String, OptionValue> values = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			values.put((String) pairs[i], OptionValue.parse((String) pairs[i + 1]));
		}

		return values;
	}

	@Test
	void nothingOnEitherSideResolvesToNothing() {
		SettingsLayers.Resolved resolved = SettingsLayers.resolve(SettingsFile.Stored.empty(), Map.of());

		assertTrue(resolved.chosen().isEmpty());
		assertEquals("", resolved.profile());
		assertTrue(resolved.forcedNames().isEmpty());
	}

	@Test
	void thePacksOwnFileIsReadAsValuesWithTheSameKindsAsTheForcedOne() {
		SettingsLayers.Resolved resolved =
				SettingsLayers.resolve(stored("A", "on", "B", "7", "C", "false"), Map.of());

		assertEquals(List.of("A=on/bool", "B=7/text", "C=off/bool"), describe(resolved.chosen()));
		assertTrue(resolved.forcedNames().isEmpty());
	}

	@Test
	void aForcedValueWinsOverTheSavedOneAndKeepsItsPlace() {
		SettingsLayers.Resolved resolved = SettingsLayers.resolve(stored("A", "1", "B", "2", "C", "3"),
				forced("B", "9", "D", "4"));

		assertEquals(List.of("A=1/text", "B=9/text", "C=3/text", "D=4/text"), describe(resolved.chosen()));
		assertEquals(List.of("B", "D"), List.copyOf(resolved.forcedNames()));
	}

	@Test
	void aForcedProfileIsTakenOutOfWhatIsChosenAndNamedApart() {
		SettingsLayers.Resolved resolved = SettingsLayers.resolve(stored("A", "1"),
				forced("profile", "HIGH", "A", "2"));

		assertEquals("HIGH", resolved.profile());
		assertEquals(List.of("A=2/text"), describe(resolved.chosen()));
		assertFalse(resolved.chosen().containsKey("profile"));
		assertTrue(resolved.forcedNames().contains("profile"), "so the screen greys the selector out too");
		assertEquals(List.of("profile", "A"), List.copyOf(resolved.forcedNames()));
	}

	@Test
	void aProfileInThePacksOwnFileIsNotAProfile() {
		// A profile can only come from the forced file; in the pack's own file the word is a setting name.
		SettingsLayers.Resolved resolved = SettingsLayers.resolve(stored("profile", "LOW", "A", "1"), Map.of());

		assertEquals("", resolved.profile());
		assertEquals(List.of("profile=LOW/text", "A=1/text"), describe(resolved.chosen()));
		assertFalse(resolved.forcedNames().contains("profile"));
	}

	@Test
	void anEmptyOrBooleanForcedProfileStillCountsAsForced() {
		SettingsLayers.Resolved empty = SettingsLayers.resolve(SettingsFile.Stored.empty(), forced("profile", ""));
		assertEquals("", empty.profile());
		assertTrue(empty.forcedNames().contains("profile"));

		SettingsLayers.Resolved word = SettingsLayers.resolve(SettingsFile.Stored.empty(), forced("profile", "on"));
		assertEquals("on", word.profile(), "the word on is read as a boolean and is written back as on");
	}

	@Test
	void theForcedProfileKeyIsMatchedByExactCase() {
		SettingsLayers.Resolved resolved = SettingsLayers.resolve(SettingsFile.Stored.empty(),
				forced("Profile", "HIGH"));

		assertEquals("", resolved.profile());
		assertEquals(List.of("Profile=HIGH/text"), describe(resolved.chosen()));
	}

	@Test
	void whatIsHandedBackCannotBeChangedAndIsNotTheCallersMap() {
		Map<String, OptionValue> forced = forced("A", "1");
		SettingsLayers.Resolved resolved = SettingsLayers.resolve(stored("B", "2"), forced);

		assertThrows(UnsupportedOperationException.class, () -> resolved.chosen().put("C", OptionValue.on()));
		assertThrows(UnsupportedOperationException.class, () -> resolved.forcedNames().add("C"));

		forced.put("Z", OptionValue.on());
		assertFalse(resolved.chosen().containsKey("Z"), "a later change to the caller's map is not seen");
		assertFalse(resolved.forcedNames().contains("Z"));
	}

	@Test
	void whatTheFileHoldsIsWhatResolveLaysOver() throws IOException {
		Map<String, OptionValue> fromFile = forcedFrom("profile=LOW\nSHADOWS=off\nQUALITY=2\n");

		SettingsLayers.Resolved resolved = SettingsLayers.resolve(stored("SHADOWS", "on", "OTHER", "x"), fromFile);

		assertEquals("LOW", resolved.profile());
		assertEquals(List.of("SHADOWS=off/bool", "OTHER=x/text", "QUALITY=2/text"), describe(resolved.chosen()));
		assertEquals(List.of("profile", "SHADOWS", "QUALITY"), List.copyOf(resolved.forcedNames()));
	}
}
