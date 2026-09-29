package dev.vitrail.pack.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.OptionPackFixture;
import dev.vitrail.pack.source.PackLang;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds how a pack's {@code screen=} keys become pages: the order of slots, blanks, links, the
 * {@code *} that pours out what no page names, columns, profiles, and what happens with a name the
 * pack does not declare or a page it never wrote.
 * <p>
 * Every input is a small synthetic pack written to a temporary directory and read through the same
 * source and properties readers a load uses.
 */
class PackMenuTest {

	/**
	 * Six settings a screen may offer, in declaration order: BLOOM FOG QUALITY HEADING shadowMapResolution
	 * shadowHardwareFiltering. STRENGTH is a value with no list and shadowDistance a constant with none, so
	 * neither is a setting.
	 */
	private static final String GLSL = """
			#define BLOOM
			//#define FOG
			#define QUALITY 2 // [1 2 3]
			#define STRENGTH 1.5
			#define HEADING 0 // [0]
			const int shadowMapResolution = 2048; // [1024 2048 4096]
			const int shadowDistance = 128;
			const bool shadowHardwareFiltering = true;
			#ifdef BLOOM
			#endif
			#ifdef FOG
			#endif
			#ifdef shadowHardwareFiltering
			#endif
			""";

	private static final Set<String> OFFERED = Set.of("BLOOM", "FOG", "QUALITY", "HEADING", "shadowMapResolution",
			"shadowHardwareFiltering");

	private static PackMenu menu(Path dir, String properties) throws IOException {
		return menu(dir, properties, GLSL);
	}

	private static PackMenu menu(Path dir, String properties, String glsl) throws IOException {
		return MenuFixture.menu(dir, properties, glsl);
	}

	/** A page as text, one word a slot, so an assertion can say what a page holds at a glance. */
	private static List<String> words(MenuPage page) {
		List<String> words = new ArrayList<>();
		for (MenuSlot slot : page.slots()) {
			words.add(switch (slot) {
				case MenuSlot.Blank _ -> "-";
				case MenuSlot.Option option -> option.option().name();
				case MenuSlot.Link link -> "[" + link.page() + (link.resolved() ? "" : "?") + "]";
				case MenuSlot.Profiles _ -> "<profile>";
			});
		}

		return words;
	}

	private static List<String> sorted(List<String> words) {
		return words.stream().sorted().toList();
	}

	// ---- slots -------------------------------------------------------------------------------

	@Test
	void everyTokenBecomesASlotInTheOrderTheyAreWritten(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM <empty> QUALITY [POST] <empty> FOG
				screen.POST=QUALITY HEADING
				""");

		assertEquals(List.of("BLOOM", "-", "QUALITY", "[POST]", "-", "FOG"), words(menu.main()));
		assertEquals(List.of("QUALITY", "HEADING"), words(menu.page("POST").orElseThrow()));
		assertEquals(List.of("", "POST"), menu.pages().stream().map(MenuPage::name).toList());
		assertEquals("test", menu.packName());
	}

	@Test
	void pagesComeInTheOrderThePackWritesThemAndAMissingMainScreenComesLast(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen.B=BLOOM
				screen.A=FOG
				""");

		assertEquals(List.of("B", "A", ""), menu.pages().stream().map(MenuPage::name).toList());
		assertSame(menu.page("").orElseThrow(), menu.main());
	}

	@Test
	void aBlankIsAsMuchALayoutSlotAsAnOption(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, "screen=<empty> <empty> BLOOM <empty>\n");

		assertEquals(List.of("-", "-", "BLOOM", "-"), words(menu.main()));
		assertEquals(4, menu.main().slots().size());
	}

	@Test
	void aSettingNamedTwiceIsTwoSlotsAndOneOption(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=QUALITY QUALITY [P]
				screen.P=QUALITY
				""");

		MenuSlot.Option first = (MenuSlot.Option) menu.main().slots().get(0);
		MenuSlot.Option second = (MenuSlot.Option) menu.main().slots().get(1);

		assertSame(first.option(), second.option());
		assertEquals(1, menu.optionCount());
		assertEquals(Set.of("QUALITY"), menu.optionNames());
	}

	@Test
	void theFormsAreTheOnesTheScreenDraws(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, "screen=BLOOM FOG QUALITY HEADING shadowMapResolution shadowHardwareFiltering\n");

		assertEquals(MenuOption.Form.TOGGLE, menu.option("BLOOM").orElseThrow().form());
		assertEquals("on", menu.option("BLOOM").orElseThrow().defaultValue());
		assertEquals("off", menu.option("FOG").orElseThrow().defaultValue());
		assertEquals(MenuOption.Form.CYCLE, menu.option("QUALITY").orElseThrow().form());
		assertEquals(MenuOption.Form.FIXED, menu.option("HEADING").orElseThrow().form());
		assertEquals(MenuOption.Form.CYCLE, menu.option("shadowMapResolution").orElseThrow().form());
		assertEquals(MenuOption.Form.TOGGLE, menu.option("shadowHardwareFiltering").orElseThrow().form());
		assertEquals("on", menu.option("shadowHardwareFiltering").orElseThrow().defaultValue());
		assertEquals(6, menu.optionCount());
	}

	@Test
	void theNamesInSlidersAreSlidersOnlyWhereTheyCycle(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM QUALITY HEADING shadowMapResolution
				sliders=QUALITY BLOOM HEADING GHOST
				""");

		assertTrue(menu.option("QUALITY").orElseThrow().slider());
		assertFalse(menu.option("BLOOM").orElseThrow().slider(), "a toggle is never a slider");
		assertFalse(menu.option("HEADING").orElseThrow().slider(), "a fixed value is never a slider");
		assertFalse(menu.option("shadowMapResolution").orElseThrow().slider(), "not named");
	}

	// ---- what does not resolve ---------------------------------------------------------------

	@Test
	void aSettingThePackDeclaresNowhereIsABlankAndALine(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM GHOST FOG [P]
				screen.P=NOWHERE QUALITY
				""");

		assertEquals(List.of("BLOOM", "-", "FOG", "[P]"), words(menu.main()));
		assertEquals(List.of("-", "QUALITY"), words(menu.page("P").orElseThrow()));
		assertEquals(List.of("screen names GHOST, which the pack does not declare",
				"screen.P names NOWHERE, which the pack does not declare"), menu.warnings());
		assertTrue(menu.option("GHOST").isEmpty());
		assertEquals(Set.of("BLOOM", "FOG", "QUALITY"), menu.optionNames());
	}

	@Test
	void aDeclarationThatIsNotASettingIsABlankWithItsOwnLine(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, "screen=STRENGTH shadowDistance BLOOM\n");

		assertEquals(List.of("-", "-", "BLOOM"), words(menu.main()));
		assertEquals(List.of("screen names STRENGTH, declared but not a setting",
				"screen names shadowDistance, declared but not a setting"), menu.warnings());
	}

	@Test
	void aLinkToAPageThePackNeverWroteIsKeptGreyedWithALine(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=[GONE] [P]
				screen.P=BLOOM
				""");

		assertEquals(List.of("[GONE?]", "[P]"), words(menu.main()));
		assertEquals(new MenuSlot.Link("GONE", false), menu.main().slots().get(0));
		assertEquals(new MenuSlot.Link("P", true), menu.main().slots().get(1));
		assertEquals(List.of("screen links to GONE, which the pack does not lay out"), menu.warnings());
		assertTrue(menu.page("GONE").isEmpty());
	}

	@Test
	void aPageWrittenEmptyIsAPageAndALinkToItResolves(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=[EMPTY]
				screen.EMPTY=
				""");

		assertEquals(new MenuSlot.Link("EMPTY", true), menu.main().slots().get(0));
		assertEquals(List.of(), words(menu.page("EMPTY").orElseThrow()));
		assertEquals(List.of(), menu.warnings());
	}

	@Test
	void linksMayFormACycleAndAPageMayLinkToItself(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=[A]
				screen.A=[B] [A] BLOOM
				screen.B=[A] [screen]
				""");

		assertEquals(List.of("[B]", "[A]", "BLOOM"), words(menu.page("A").orElseThrow()));
		assertEquals(List.of("[A]", "[screen?]"), words(menu.page("B").orElseThrow()));
		assertEquals(List.of("screen.B links to screen, which the pack does not lay out"), menu.warnings());
	}

	@Test
	void aLinkNamesAPageExactlyAndBracketsDoNotNest(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=[p] [[P]] [P
				screen.P=BLOOM
				""");

		assertEquals(List.of("[p?]", "[[P]?]", "-"), words(menu.main()));
		assertEquals(List.of("screen links to p, which the pack does not lay out",
				"screen links to [P], which the pack does not lay out",
				"screen names [P, which the pack does not declare"), menu.warnings());
	}

	@Test
	void theLastLineOfAPageIsThePage(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM
				screen=FOG
				""");

		assertEquals(List.of("FOG"), words(menu.main()));
	}

	// ---- the star ----------------------------------------------------------------------------

	@Test
	void aPackWithNoMainScreenGetsEverythingOnIt(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, "profile.X=BLOOM\n");

		assertEquals(OFFERED, Set.copyOf(words(menu.main())));
		assertEquals(OFFERED.size(), menu.main().slots().size());
		assertEquals(OFFERED.size(), menu.optionCount());
	}

	@Test
	void aPackWithNoPropertiesFileAtAllIsTheSame(@TempDir Path dir) throws IOException {
		OptionPackFixture.write(dir, Map.of("main.glsl", GLSL));

		try (ShaderPackSource source = OptionPackFixture.open(dir)) {
			PackMenu menu = PackMenu.build("bare", source.options(), ShaderProperties.parse(source),
					PackLang.empty());

			assertEquals(OFFERED, Set.copyOf(words(menu.main())));
			assertEquals(1, menu.pages().size());
			assertEquals(List.of(), menu.warnings());
		}
	}

	@Test
	void aStarPoursOutEverySettingNoSubPageNamesIncludingWhatTheMainScreenNamed(@TempDir Path dir)
			throws IOException {
		PackMenu menu = menu(dir, """
				screen=QUALITY * FOG
				screen.P=BLOOM
				""");

		List<String> main = words(menu.main());

		// QUALITY, the star, FOG: what the star pours is everything offered but BLOOM (on a sub page).
		assertEquals("QUALITY", main.get(0));
		assertEquals("FOG", main.get(main.size() - 1));
		List<String> poured = main.subList(1, main.size() - 1);
		assertEquals(sorted(List.of("FOG", "QUALITY", "HEADING", "shadowMapResolution", "shadowHardwareFiltering")),
				sorted(poured));
		assertEquals(2, main.stream().filter("QUALITY"::equals).count());
		assertFalse(main.contains("BLOOM"));
		assertEquals(OFFERED, menu.optionNames());
	}

	@Test
	void aStarLeavesOutWhatIsNotASetting(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, "screen=*\n");

		assertFalse(words(menu.main()).contains("STRENGTH"));
		assertFalse(words(menu.main()).contains("shadowDistance"));
		assertEquals(OFFERED, Set.copyOf(words(menu.main())));
	}

	@Test
	void onlyTheFirstStarPoursAndTheSecondTakesNothing(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=* <empty> *
				""");

		List<String> main = words(menu.main());

		assertEquals(OFFERED.size() + 1, main.size());
		assertEquals("-", main.get(OFFERED.size()), "the second star leaves the blank at its own position");
	}

	@Test
	void theFirstStarInTheOrderOfTheFileTakesThePour(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen.P=*
				screen=BLOOM * [P]
				""");

		assertEquals(OFFERED, Set.copyOf(words(menu.page("P").orElseThrow())));
		assertEquals(List.of("BLOOM", "[P]"), words(menu.main()));
	}

	@Test
	void aStarOnASubPageTakesWhatNoSubPageNamed(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM [REST] [P]
				screen.REST=*
				screen.P=FOG QUALITY
				""");

		assertEquals(Set.of("BLOOM", "HEADING", "shadowMapResolution", "shadowHardwareFiltering"),
				Set.copyOf(words(menu.page("REST").orElseThrow())));
	}

	/**
	 * Forty settings, because an order that changes from one start of the game to the next still
	 * lands on the declared one now and then for three or four.
	 */
	@Test
	void theStarPoursInDeclarationOrder(@TempDir Path dir) throws IOException {
		Random random = new Random(3);
		StringBuilder glsl = new StringBuilder();
		List<String> declared = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			String name = "OPT_" + Integer.toString(random.nextInt(Integer.MAX_VALUE), 36);
			declared.add(name);
			glsl.append("#define ").append(name).append(" 1 // [1 2]\n");
		}

		PackMenu menu = menu(dir, "screen=*\n", glsl.toString());

		assertEquals(declared, words(menu.main()));
	}

	// ---- columns -----------------------------------------------------------------------------

	@Test
	void aPageAsksForItsColumnsAndOtherwiseGetsTwoOrThreeByLength(@TempDir Path dir) throws IOException {
		String eighteen = "<empty> ".repeat(18).trim();
		String nineteen = "<empty> ".repeat(19).trim();
		PackMenu menu = menu(dir, """
				screen=BLOOM [ASKS] [EIGHTEEN] [NINETEEN] [ZERO] [HUGE]
				screen.columns=4
				screen.ASKS=BLOOM
				screen.ASKS.columns=5
				screen.EIGHTEEN=%s
				screen.NINETEEN=%s
				screen.ZERO=BLOOM
				screen.ZERO.columns=0
				screen.HUGE=BLOOM
				screen.HUGE.columns=99999999999
				""".formatted(eighteen, nineteen));

		assertEquals(4, menu.main().columns());
		assertEquals(5, menu.page("ASKS").orElseThrow().columns());
		assertEquals(2, menu.page("EIGHTEEN").orElseThrow().columns());
		assertEquals(3, menu.page("NINETEEN").orElseThrow().columns());
		assertEquals(2, menu.page("ZERO").orElseThrow().columns(), "a count of nought is dropped");
		assertEquals(2, menu.page("HUGE").orElseThrow().columns(), "a count past an int is dropped");
	}

	@Test
	void theDefaultColumnsAreDecidedAfterTheStarHasPoured(@TempDir Path dir) throws IOException {
		StringBuilder glsl = new StringBuilder();
		for (int i = 0; i < 20; i++) {
			glsl.append("#define O").append(i).append(" 1 // [1 2]\n");
		}

		assertEquals(3, menu(dir, "screen=*\n", glsl.toString()).main().columns());
	}

	@Test
	void aColumnCountForAPageNobodyWroteMakesNoPage(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM
				screen.GHOST.columns=3
				""");

		assertEquals(1, menu.pages().size());
		assertTrue(menu.page("GHOST").isEmpty());
	}

	// ---- profiles ----------------------------------------------------------------------------

	@Test
	void theProfileSelectorIsDroppedWhereThePackDeclaresNoProfile(@TempDir Path dir) throws IOException {
		PackMenu without = menu(dir, "screen=<profile> BLOOM <profile>\n");

		assertEquals(List.of("BLOOM"), words(without.main()));
		assertEquals(List.of(), without.profileNames());
	}

	@Test
	void theProfileSelectorIsKeptWhereThereIsAProfile(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=<profile> BLOOM <profile>
				profile.LOW=!BLOOM
				""");

		assertEquals(List.of("<profile>", "BLOOM", "<profile>"), words(menu.main()));
		assertEquals(new MenuSlot.Profiles(), menu.main().slots().get(0));
	}

	@Test
	void aProfileIsItsValuesInTheSpellingAWidgetCyclesThrough(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM
				profile.LOW=!BLOOM QUALITY=1 FOG
				""");

		Map<String, String> low = menu.profile("LOW");

		assertEquals(Map.of("BLOOM", "off", "QUALITY", "1", "FOG", "on"), low);
		assertEquals(List.of("BLOOM", "QUALITY", "FOG"), List.copyOf(low.keySet()));
		assertThrows(UnsupportedOperationException.class, () -> low.put("X", "y"));
		assertEquals(Map.of(), menu.profile("NOPE"));
	}

	@Test
	void aProfileNamedInsideAnotherIsExpandedFirstAndTheLaterChoiceWins(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM
				profile.LOW=!BLOOM QUALITY=1
				profile.HIGH=profile.LOW QUALITY=3 BLOOM
				""");

		assertEquals(Map.of("BLOOM", "on", "QUALITY", "3"), menu.profile("HIGH"));
	}

	@Test
	void aBareNameThePackDeclaresNowhereAsAToggleIsNotAConstraintAndSaysSo(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM
				profile.LOW=GHOST QUALITY BLOOM !GHOST2 STRENGTH=4 FOG
				""");

		// GHOST is declared nowhere and QUALITY is a value, so neither is a toggle. The negated and
		// the valued forms go through unchecked.
		assertEquals(Map.of("BLOOM", "on", "GHOST2", "off", "STRENGTH", "4", "FOG", "on"), menu.profile("LOW"));
		assertEquals(List.of(
				"profile.LOW names GHOST, which this pack declares nowhere as a toggle, so it is not a constraint",
				"profile.LOW names QUALITY, which this pack declares nowhere as a toggle, so it is not a constraint"),
				menu.warnings());
	}

	/**
	 * A {@code const bool} is a toggle on a screen and in the reference, but the profile check asks
	 * for a bare {@code #define}, so a bare token naming one is dropped with a line that says it is
	 * declared nowhere, and choosing the profile never turns it on.
	 */
	@Test
	void knownBug_aBareProfileTokenNamingAConstBoolIsDropped(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=shadowHardwareFiltering
				profile.LOW=shadowHardwareFiltering
				""");

		assertEquals(MenuOption.Form.TOGGLE, menu.option("shadowHardwareFiltering").orElseThrow().form());
		assertEquals(Map.of(), menu.profile("LOW"));
		assertEquals(1, menu.warnings().size());
	}

	@Test
	void aWarningNamesTheOutermostProfileEachTimeANestedOneIsWalked(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM
				profile.LOW=GHOST
				profile.HIGH=profile.LOW
				""");

		assertEquals(List.of(
				"profile.LOW names GHOST, which this pack declares nowhere as a toggle, so it is not a constraint",
				"profile.HIGH names GHOST, which this pack declares nowhere as a toggle, so it is not a constraint"),
				menu.warnings());
	}

	@Test
	void profilesAreOrderedFromTheMostConstrainedAndTiesKeepTheirOrder(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir, """
				screen=BLOOM
				profile.ONE=QUALITY=1
				profile.THREE=QUALITY=1 STRENGTH=2 FOG
				profile.TWO=QUALITY=1 STRENGTH=2
				profile.ALSO_TWO=QUALITY=3 STRENGTH=1
				profile.NONE=
				""");

		assertEquals(List.of("THREE", "TWO", "ALSO_TWO", "ONE", "NONE"), menu.profileNames());
		assertThrows(UnsupportedOperationException.class, () -> menu.profileNames().add("x"));
	}

	@Test
	void aWholeGeneratedPackKeepsEveryPromiseAtOnce(@TempDir Path dir) throws IOException {
		Random random = new Random(21);
		StringBuilder glsl = new StringBuilder();
		StringBuilder properties = new StringBuilder("screen=");
		List<String> expected = new ArrayList<>();
		int values = 0;
		for (int i = 0; i < 60; i++) {
			String name = "S" + i;
			// A toggle something tests, a value with a list, and a value with none, which is no setting.
			glsl.append(switch (i % 3) {
				case 0 -> "#define " + name + "\n#ifdef " + name + "\n#endif\n";
				case 1 -> "#define " + name + " 1 // [1 2 3]\n";
				default -> "#define " + name + " 1\n";
			});
			if (random.nextInt(4) == 0) {
				properties.append("<empty> ");
				expected.add("-");
			}
			properties.append(name).append(' ');
			if (i % 3 == 2) {
				expected.add("-");
				values++;
			} else {
				expected.add(name);
			}
		}

		PackMenu menu = menu(dir, properties.toString().trim() + "\n", glsl.toString());

		assertEquals(expected, words(menu.main()));
		assertEquals(values, menu.warnings().size());
		assertEquals(60 - values, menu.optionCount());
		assertEquals(1, menu.pages().size());
		assertEquals(3, menu.main().columns(), "seventy-odd slots is past eighteen");
	}
}
