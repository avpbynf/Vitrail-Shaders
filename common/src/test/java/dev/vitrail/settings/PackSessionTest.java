package dev.vitrail.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.option.OptionValue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The parts of one reading of one pack that are only maps: the forced values as the screen wants
 * them, the layers handed on to {@link SettingsLayers}, and the names the pack declares. The menu and
 * the pack archive behind {@code read} are the pack tree's and are not built here.
 */
class PackSessionTest {

	private static PackSession session(Set<String> declared, Map<String, String> saved,
			Map<String, OptionValue> forced) {
		return new PackSession(Path.of("game"), Path.of("game", "shaderpacks", "Pack.zip"), "Pack.zip", null,
				declared, null, new SettingsFile.Stored(saved), forced);
	}

	@Test
	void forcedTextIsTheValuesAsTheScreenWritesThemInTheOrderTheyWereForced() {
		Map<String, OptionValue> forced = new LinkedHashMap<>();
		forced.put("WAVING", OptionValue.parse("true"));
		forced.put("QUALITY", OptionValue.parse("High"));
		forced.put("BLOOM", OptionValue.parse("off"));
		forced.put("profile", OptionValue.parse("LOW"));

		Map<String, String> text = session(Set.of(), Map.of(), forced).forcedText();

		assertEquals(List.of("WAVING", "QUALITY", "BLOOM", "profile"), List.copyOf(text.keySet()));
		assertEquals("on", text.get("WAVING"), "a boolean reads back as on and not as true");
		assertEquals("High", text.get("QUALITY"));
		assertEquals("off", text.get("BLOOM"));
		assertEquals("LOW", text.get("profile"), "the profile key is a forced name like any other here");
	}

	@Test
	void forcedTextCannotBeChangedAndCarriesNothingWhenNothingIsForced() {
		assertTrue(session(Set.of(), Map.of(), Map.of()).forcedText().isEmpty());
		Map<String, String> text = session(Set.of(), Map.of(), Map.of("A", OptionValue.on())).forcedText();

		assertThrows(UnsupportedOperationException.class, () -> text.put("B", "x"));
	}

	@Test
	void theLayersAreTheSavedValuesWithTheForcedOnesOverThemAndTheProfileSplitOff() {
		Map<String, String> saved = new LinkedHashMap<>();
		saved.put("A", "1");
		saved.put("B", "on");
		Map<String, OptionValue> forced = new LinkedHashMap<>();
		forced.put("B", OptionValue.parse("off"));
		forced.put("profile", OptionValue.parse("HIGH"));

		SettingsLayers.Resolved settings = session(Set.of(), saved, forced).settings();

		assertEquals("HIGH", settings.profile());
		assertEquals(List.of("A", "B"), List.copyOf(settings.chosen().keySet()));
		assertEquals("1", settings.chosen().get("A").asText());
		assertEquals("off", settings.chosen().get("B").asText());
		assertEquals(Set.of("B", "profile"), settings.forcedNames());
	}

	@Test
	void theDeclaredNamesAreCopiedSoALaterChangeToTheCallersSetIsNotSeen() {
		Set<String> declared = new HashSet<>(Set.of("A", "B"));

		PackSession session = session(declared, Map.of(), Map.of());
		declared.add("C");

		assertEquals(Set.of("A", "B"), session.declared());
		assertThrows(UnsupportedOperationException.class, () -> session.declared().add("D"));
	}

	@Test
	void theSettingsFileIsTheOneTheScreenWritesAndIrisReads() {
		PackSession session = session(Set.of(), Map.of(), Map.of());

		assertEquals(SettingsFile.of(Path.of("game"), "Pack.zip"), session.settingsFile());
	}
}
