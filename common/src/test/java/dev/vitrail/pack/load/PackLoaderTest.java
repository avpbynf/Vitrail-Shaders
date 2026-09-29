package dev.vitrail.pack.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.program.ProgramSet.ProgramKey;
import dev.vitrail.pack.source.ExpansionStats;
import dev.vitrail.pack.source.SyntheticPacks;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds what a load of a pack reports and how the folder of packs is listed: the model the rest of the
 * engine starts from, read once and closed before it is returned, so that it holds nothing that outlives
 * the archive.
 */
class PackLoaderTest {

	@TempDir
	Path temp;

	private static Map<String, String> pack() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/shaders.properties", "program.composite2.enabled = false\nscreen = BLOOM QUALITY\nprofile.LOW = BLOOM=off\n");
		files.put("shaders/lib/settings.glsl", "#define BLOOM\n#define QUALITY 2 //[1 2 3]\n");
		files.put("shaders/gbuffers_terrain.vsh", "#version 330\n#include \"/lib/settings.glsl\"\nvoid main() {}\n");
		files.put("shaders/gbuffers_terrain.fsh",
				"#version 330\n#include \"/lib/settings.glsl\"\n#ifdef BLOOM\n#include \"/lib/missing.glsl\"\n#endif\nvoid main() {}\n");
		files.put("shaders/composite.fsh", "#version 330\nvoid main() {}\n");
		files.put("shaders/composite1.fsh", "#version 330\nvoid main() {}\n");
		files.put("shaders/composite2.fsh", "#version 330\nvoid main() {}\n");
		files.put("shaders/final.fsh", "#version 330\nvoid main() {}\n");
		files.put("shaders/world0/composite.fsh", "#version 330\nvoid main() {}\n");
		files.put("shaders/lib/bogus.fsh", "x\n");
		files.put("shaders/gbuffers_unknown.fsh", "x\n");

		return files;
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void loadsEveryPartOfAPackAndClosesItBeforeReturning(Shape shape) throws IOException {
		Path packPath = shape.build(Files.createDirectories(this.temp.resolve(shape.name())), "Demo", pack());

		LoadedPack loaded = PackLoader.load(packPath);

		assertEquals("Demo", loaded.packName());
		assertEquals(shape == Shape.ZIP, loaded.fromZip());
		assertEquals(List.of("world0"), loaded.dimensions().names());
		assertTrue(loaded.properties().present());
		assertEquals(2, loaded.options().count());
		assertEquals(7, loaded.programs().count());
		assertEquals(Map.of("lib", 1), loaded.programs().skippedDirectories());
		assertEquals(Map.of("gbuffers_unknown", 1), loaded.programs().skippedNames());
		// The pack's own toggle is read against the settings as shipped, and only that one is off.
		assertEquals(Set.of("composite2"), loaded.disabledPrograms());
		assertEquals(0, loaded.caseInsensitiveHits());
		assertTrue(loaded.loadMillis() >= 0);
		assertEquals(List.of(), loaded.looseDirectives());
	}

	@Test
	void resolvesProgramsAsThePackShipsWithNoToggleApplied() throws IOException {
		LoadedPack loaded = PackLoader.load(SyntheticPacks.directory(this.temp, "Demo", pack()));

		// composite2 is switched off and is still counted as shipped here: this report is the pack as it
		// is written, and the composites are no name of the fallback tree. gbuffers_terrain and final
		// serve themselves, and six programs have chains that lead through terrain; the world0 folder
		// ships no name of the tree.
		assertEquals(2, loaded.resolved().directCount(""));
		assertEquals(6, loaded.resolved().inheritedCount(""));
		assertEquals(0, loaded.resolved().resolutions("world0").size());
	}

	@Test
	void expandsEveryEntryPointOnceAndSumsWhatItCounted() throws IOException {
		LoadedPack loaded = PackLoader.load(SyntheticPacks.directory(this.temp, "Demo", pack()));

		assertEquals(7, loaded.expandedUnits());
		// terrain.vsh follows one include; terrain.fsh follows one and cannot find the second, which is
		// live under BLOOM because the first defined it; the other five files include nothing.
		assertEquals(new ExpansionStats(3, 2, 0, 1, 0, 0, 1, 0, 1, 0, 0), loaded.expansion());
		assertFalse(loaded.expansion().clean());
	}

	@Test
	void measuresTheLexicalNumbersOverTheSourceFilesOfThePack() throws IOException {
		LoadedPack loaded = PackLoader.load(SyntheticPacks.directory(this.temp, "Demo", pack()));

		assertEquals(10, loaded.stats().files());
		assertEquals(3, loaded.stats().includes());
		assertEquals(1, loaded.stats().conditionals());
		assertEquals(2, loaded.stats().options());
		assertEquals(Map.of("fsh", 8, "glsl", 1, "vsh", 1), loaded.stats().filesByExtension());
	}

	@Test
	void readsAPackWithNoPropertiesFileAndNoProgramAtAll() throws IOException {
		Path packPath = SyntheticPacks.directory(this.temp, "Bare", Map.of("shaders/lib/a.glsl", "// a\n"));

		LoadedPack loaded = PackLoader.load(packPath);

		assertFalse(loaded.properties().present());
		assertEquals(0, loaded.programs().count());
		assertEquals(0, loaded.expandedUnits());
		assertEquals(ExpansionStats.NONE, loaded.expansion());
		assertEquals(Set.of(), loaded.disabledPrograms());
	}

	@Test
	void refusesAFolderThatIsNotAPackWithAnIoError() throws IOException {
		Path empty = Files.createDirectories(this.temp.resolve("nothing"));
		Path text = this.temp.resolve("file.txt");
		Files.writeString(text, "x");

		assertThrows(IOException.class, () -> PackLoader.load(empty));
		assertThrows(IOException.class, () -> PackLoader.load(text));
		assertThrows(IOException.class, () -> PackLoader.properties(text));
	}

	@Test
	void readsJustThePropertiesFileWithoutEnumeratingAnything() throws IOException {
		Path packPath = SyntheticPacks.directory(this.temp, "Demo", pack());

		assertEquals(List.of("LOW"), List.copyOf(PackLoader.properties(packPath).profiles().keySet()));
		assertTrue(PackLoader.properties(packPath).present());
	}

	@Test
	void listsThePacksOfAGameFolderInIrisOrderIgnoringCaseAndFormattingCodes() throws IOException {
		Path game = Files.createDirectories(this.temp.resolve("game"));
		Path packs = PackLoader.directory(game);
		assertEquals(game.resolve("shaderpacks"), packs);
		Files.createDirectories(packs.resolve("Banana"));
		Files.createDirectories(packs.resolve("apple"));
		Files.createDirectories(packs.resolve("\u00a7aCherry"));
		Files.createDirectories(packs.resolve("apple"));
		Files.writeString(packs.resolve("Date.zip"), "not really a zip");
		Files.writeString(packs.resolve("notes.txt"), "x");
		Files.writeString(packs.resolve("ELDER.ZIP"), "x");

		List<String> names = PackLoader.candidates(game).stream().map(path -> path.getFileName().toString()).toList();

		// Case ignored first, so apple leads Banana where a plain sort would put the capital first; the colour code
		// and the character after it are left out of the comparison and not out of the name.
		assertEquals(List.of("apple", "Banana", "\u00a7aCherry", "Date.zip", "ELDER.ZIP"), names);
	}

	@Test
	void aGameFolderWithNoShaderpacksDirectoryHasNoCandidates() throws IOException {
		assertEquals(List.of(), PackLoader.candidates(Files.createDirectories(this.temp.resolve("game"))));
	}

	@Test
	void decidesAtAGlanceWhetherAPathLooksLikeAPack() throws IOException {
		Path directory = Files.createDirectories(this.temp.resolve("a-folder"));
		Path zip = Files.writeString(this.temp.resolve("x.zip"), "");
		Path upper = Files.writeString(this.temp.resolve("Y.ZIP"), "");
		Path text = Files.writeString(this.temp.resolve("z.txt"), "");

		assertTrue(PackLoader.looksLikeAPack(directory));
		assertTrue(PackLoader.looksLikeAPack(zip));
		assertTrue(PackLoader.looksLikeAPack(upper));
		assertFalse(PackLoader.looksLikeAPack(text));
		assertFalse(PackLoader.looksLikeAPack(this.temp.resolve("absent")));
	}

	@Test
	void reportsAPackWithoutThrowing() throws IOException {
		LoadedPack loaded = PackLoader.load(SyntheticPacks.directory(this.temp, "Demo", pack()));

		// The log is the whole result of this stage; what is checked is that every line of it can be
		// built from a real pack, the ignored keys and the eight-name sample included.
		PackReport.log(loaded);
		PackReport.couldNotRead("Demo", new IOException("unreadable"));
		assertEquals(7, loaded.programs().keys().stream().map(ProgramKey::file).count());
	}
}
