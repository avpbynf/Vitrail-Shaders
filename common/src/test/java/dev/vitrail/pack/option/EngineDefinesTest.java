package dev.vitrail.pack.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Holds the system symbols a pack is read with to what this engine really runs on.
 * <p>
 * A pack reads {@code MC_OS_MAC} as Apple's OpenGL driver and switches off what that driver lacks:
 * Complementary Reimagined turns its coloured lighting off and paints an error over the picture
 * where it was asked for. This engine draws a Mac through MoltenVK, which has all of it, so the
 * symbol is left out there, and the other systems keep OptiFine's.
 */
class EngineDefinesTest {

	@Test
	void posesNoSystemSymbolOnAMac() {
		List<String> systems = systemSymbols(EngineDefines.Os.MAC);

		assertTrue(systems.isEmpty(), "system symbols posed on a Mac: " + systems);
	}

	@Test
	void posesOptiFinesSymbolElsewhere() {
		assertEquals(List.of("MC_OS_WINDOWS"), systemSymbols(EngineDefines.Os.WINDOWS));
		assertEquals(List.of("MC_OS_LINUX"), systemSymbols(EngineDefines.Os.LINUX));
		assertEquals(List.of("MC_OS_UNKNOWN"), systemSymbols(EngineDefines.Os.OTHER));
	}

	private static List<String> systemSymbols(EngineDefines.Os os) {
		Map<String, String> table = EngineDefines.table(new EngineDefines.Environment(
				EngineDefines.DEFAULT_MC_VERSION, os, "", "", 4, false, Map.of(), List.of()));

		return table.keySet().stream().filter(name -> name.startsWith("MC_OS_")).toList();
	}
}
