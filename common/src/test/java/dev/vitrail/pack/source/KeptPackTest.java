package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds the one pack {@link KeptPack} leaves open between two loads: when the next load is handed the
 * same opening, when it is handed a new one, and what closes and what stays open at each step.
 * <p>
 * {@code KeptPack} keeps its opening in a process-wide static, and so do the two inputs it compares
 * against, the engine's define table and the shadow map scale. Every test here starts from nothing held
 * and puts all three back, or the order the tests run in would decide what they see.
 */
class KeptPackTest {

	private static final Map<String, OptionValue> NO_CHOICES = Map.of();

	@TempDir
	Path temp;

	private EngineDefines.Environment machine;
	private int scale;

	@BeforeEach
	void rememberProcessWideState() {
		OpenedPack.forgetKept();
		this.machine = EngineDefines.machine();
		this.scale = SettingSet.askedShadowMapScale();
	}

	@AfterEach
	void restoreProcessWideState() {
		OpenedPack.forgetKept();
		EngineDefines.machine(this.machine);
		SettingSet.shadowMapScale(this.scale);
	}

	private Path pack(Shape shape, String name) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/composite.fsh", "#version 330\n#define QUALITY 1 //[1 2 3]\nvoid main() {}\n");
		files.put("shaders/shaders.properties", "profile.LOW=QUALITY=1 !FOG\nprofile.HIGH=QUALITY=3\n");

		return shape.build(Files.createDirectories(this.temp.resolve(shape.name())), name, files);
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void handsTheSameOpeningToASecondLoadWithTheSameInputs(Shape shape) throws IOException {
		Path packPath = pack(shape, "one");

		OpenedPack first = OpenedPack.openKept(packPath, NO_CHOICES, "");
		first.close();
		OpenedPack second = OpenedPack.openKept(packPath, NO_CHOICES, "");

		assertSame(first, second);
		// Closing the held opening ends what a reader worked out of it and leaves the archive open, so
		// the two files the opening itself read, a source for the index and the properties, are still
		// there for the next load.
		assertEquals(2, second.source().filesRead());
		assertEquals(1, second.source().options().count());
		assertFalse(second.source().readLines(second.source().file("composite.fsh").orElseThrow()).isEmpty());
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void aKeptOpeningForgetsWhatAReaderDerivedWhenItsLoadEnds(Shape shape) throws IOException {
		Path packPath = pack(shape, "derived");
		OpenedPack opened = OpenedPack.openKept(packPath, NO_CHOICES, "");
		int[] computed = new int[1];
		opened.source().derived("plan", () -> ++computed[0]);
		opened.source().derived("plan", () -> ++computed[0]);
		assertEquals(1, computed[0]);

		opened.close();

		// A plan is a function of more than the archive, so the second load works it out again.
		assertEquals(2, opened.source().<Integer>derived("plan", () -> ++computed[0]));
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void anOpeningThatIsNotTheHeldOneIsClosedForGood(Shape shape) throws IOException {
		Path packPath = pack(shape, "plain");
		OpenedPack unkept = OpenedPack.open(packPath, NO_CHOICES, "");
		assertEquals(2, unkept.source().filesRead());

		unkept.close();

		assertEquals(0, unkept.source().filesRead());
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void opensAfreshAndClosesTheOldOneWhenTheChosenSettingsMove(Shape shape) throws IOException {
		Path packPath = pack(shape, "settings");
		OpenedPack first = OpenedPack.openKept(packPath, NO_CHOICES, "");
		assertEquals(2, first.source().filesRead());

		OpenedPack second = OpenedPack.openKept(packPath, Map.of("QUALITY", OptionValue.of("2")), "");

		assertNotSame(first, second);
		// The old one is let go: its memos are emptied and, for a zip, its archive is closed.
		assertEquals(0, first.source().filesRead());
		if (shape == Shape.ZIP) {
			assertThrows(ClosedFileSystemException.class,
					() -> first.source().readLines(first.source().file("composite.fsh").orElseThrow()));
		}

		// And the same choices again, written as a new map with new values, are the same key.
		assertSame(second, OpenedPack.openKept(packPath, Map.of("QUALITY", OptionValue.of("2")), ""));
		assertNotSame(second, OpenedPack.openKept(packPath, Map.of("QUALITY", OptionValue.of("3")), ""));
	}

	@Test
	void comparesChosenSettingsByWhatTheyAreWrittenAsAndNotByTheirIdentity() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "identity");
		Map<String, OptionValue> a = new LinkedHashMap<>();
		a.put("B", OptionValue.of("2"));
		a.put("A", OptionValue.on());
		Map<String, OptionValue> b = new LinkedHashMap<>();
		b.put("A", OptionValue.on());
		b.put("B", OptionValue.of("2"));

		OpenedPack first = OpenedPack.openKept(packPath, a, "");

		// Order of the map is not part of the key, and a boolean is not the text of a boolean.
		assertSame(first, OpenedPack.openKept(packPath, b, ""));
		Map<String, OptionValue> text = new LinkedHashMap<>(b);
		text.put("A", OptionValue.of("on"));
		assertNotSame(first, OpenedPack.openKept(packPath, text, ""));
	}

	@Test
	void opensAfreshWhenTheProfileMoves() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "profile");
		OpenedPack low = OpenedPack.openKept(packPath, NO_CHOICES, "LOW");

		assertSame(low, OpenedPack.openKept(packPath, NO_CHOICES, "LOW"));
		assertNotSame(low, OpenedPack.openKept(packPath, NO_CHOICES, "HIGH"));
	}

	@Test
	void holdsOnlyOnePackAndReplacesItWithTheNextOneAsked() throws IOException {
		Path one = pack(Shape.DIRECTORY, "first");
		Path two = pack(Shape.ZIP, "second");

		OpenedPack first = OpenedPack.openKept(one, NO_CHOICES, "");
		OpenedPack second = OpenedPack.openKept(two, NO_CHOICES, "");

		assertNotSame(first, second);
		assertEquals(0, first.source().filesRead());
		assertNotSame(first, OpenedPack.openKept(one, NO_CHOICES, ""));
	}

	@Test
	void opensAfreshWhenAFileOfThePackIsEditedOnTheDisk() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "edited");
		OpenedPack first = OpenedPack.openKept(packPath, NO_CHOICES, "");
		Path file = packPath.resolve("shaders/composite.fsh");
		FileTime stamp = Files.getLastModifiedTime(file);

		// A stamp that moved with the size left alone, which is what touch does.
		Files.setLastModifiedTime(file, FileTime.fromMillis(stamp.toMillis() + 5_000));
		OpenedPack touched = OpenedPack.openKept(packPath, NO_CHOICES, "");
		assertNotSame(first, touched);
		assertSame(touched, OpenedPack.openKept(packPath, NO_CHOICES, ""));

		// A size that moved.
		Files.writeString(file, "#version 330\n// longer than before, by a comment\n");
		OpenedPack grown = OpenedPack.openKept(packPath, NO_CHOICES, "");
		assertNotSame(touched, grown);

		// A file added, and a directory added, each moves the print.
		Files.writeString(packPath.resolve("shaders/extra.glsl"), "// x\n");
		OpenedPack added = OpenedPack.openKept(packPath, NO_CHOICES, "");
		assertNotSame(grown, added);
		Files.createDirectories(packPath.resolve("shaders/world0"));
		OpenedPack directory = OpenedPack.openKept(packPath, NO_CHOICES, "");
		assertNotSame(added, directory);
		assertSame(directory, OpenedPack.openKept(packPath, NO_CHOICES, ""));
	}

	@Test
	void opensAfreshWhenTheEnginesDefineTableMoves() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "machine");
		OpenedPack first = OpenedPack.openKept(packPath, NO_CHOICES, "");

		EngineDefines.machine(EngineDefines.Environment.of(EngineDefines.DEFAULT_MC_VERSION + 1));

		assertNotSame(first, OpenedPack.openKept(packPath, NO_CHOICES, ""));
	}

	@Test
	void opensAfreshWhenTheShadowMapScaleMovesBecauseTheFlattenedTextCarriesIt() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "scale");
		SettingSet.shadowMapScale(100);
		OpenedPack first = OpenedPack.openKept(packPath, NO_CHOICES, "");
		assertEquals(100, first.settings().scale());

		SettingSet.shadowMapScale(50);
		OpenedPack second = OpenedPack.openKept(packPath, NO_CHOICES, "");

		assertNotSame(first, second);
		assertEquals(50, second.settings().scale());
		assertSame(second, OpenedPack.openKept(packPath, NO_CHOICES, ""));
	}

	@Test
	void aPackThatCannotBeOpenedLeavesTheHeldOneStanding() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "held");
		OpenedPack held = OpenedPack.openKept(packPath, NO_CHOICES, "");
		Path broken = this.temp.resolve("broken.zip");
		Files.writeString(broken, "this is not a zip archive");

		assertThrows(IOException.class, () -> OpenedPack.openKept(broken, NO_CHOICES, ""));

		assertSame(held, OpenedPack.openKept(packPath, NO_CHOICES, ""));
	}

	@Test
	void forgettingReleasesTheHeldOpeningAndTheNextLoadOpensAfresh() throws IOException {
		Path packPath = pack(Shape.ZIP, "forgotten");
		OpenedPack held = OpenedPack.openKept(packPath, NO_CHOICES, "");

		OpenedPack.forgetKept();

		assertThrows(ClosedFileSystemException.class,
				() -> held.source().readLines(held.source().file("composite.fsh").orElseThrow()));
		assertNotSame(held, OpenedPack.openKept(packPath, NO_CHOICES, ""));
		// Nothing held is nothing to forget, and asking twice is not an error.
		OpenedPack.forgetKept();
		OpenedPack.forgetKept();
	}

	@Test
	void anOpeningTakenAfterForgettingIsNotTheHeldOneUntilItIsAskedForAgain() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "identity-of-held");
		OpenedPack held = OpenedPack.openKept(packPath, NO_CHOICES, "");
		OpenedPack plain = OpenedPack.open(packPath, NO_CHOICES, "");

		// A record answers equal for two openings of one pack, and only the identity says which is held.
		assertEquals(held.packPath(), plain.packPath());
		plain.close();
		assertEquals(0, plain.source().filesRead());
		held.close();
		assertEquals(2, held.source().filesRead());
	}

	// --- OpenedPack itself ----------------------------------------------------------------------

	@Test
	void opensAPackWithItsIndexPropertiesAndSettingsResolvedTogether() throws IOException {
		Path packPath = pack(Shape.DIRECTORY, "resolved");

		try (OpenedPack opened = OpenedPack.open(packPath, Map.of("QUALITY", OptionValue.of("2")), "LOW")) {
			assertEquals(1, opened.options().count());
			assertTrue(opened.properties().present());
			assertEquals("LOW", opened.settings().variantName());
			// The profile goes underneath and a chosen value goes on top of it.
			assertEquals("2", opened.settings().chosen().get("QUALITY").text());
			assertFalse(opened.settings().chosen().get("FOG").asBoolean());
			assertEquals(packPath, opened.packPath());
		}

		try (OpenedPack opened = OpenedPack.open(packPath, NO_CHOICES, "")) {
			assertEquals("chosen", opened.settings().variantName());
			assertEquals(Map.of(), opened.settings().chosen());
		}

		try (OpenedPack opened = OpenedPack.open(packPath, NO_CHOICES, "HIGH")) {
			assertEquals("3", opened.settings().chosen().get("QUALITY").text());
		}
	}

	@Test
	void aProfileTheFileDoesNotDeclareChoosesNothing() throws IOException {
		try (OpenedPack opened = OpenedPack.open(pack(Shape.DIRECTORY, "noprofile"), NO_CHOICES, "MISSING")) {
			assertEquals("MISSING", opened.settings().variantName());
			assertEquals(Map.of(), opened.settings().chosen());
		}
	}

	@Test
	void refusesAPackWhoseSourceCannotBeReadAndGivesTheArchiveBack() throws IOException {
		Path packPath = SyntheticPacks.zip(Files.createDirectories(this.temp.resolve("big")), "big",
				Map.of("shaders/huge.glsl", "a".repeat(8 * 1024 * 1024 + 1)));

		IOException refused = assertThrows(IOException.class, () -> OpenedPack.open(packPath, NO_CHOICES, ""));

		assertTrue(refused.getMessage().contains("huge.glsl"), refused.getMessage());
	}
}
