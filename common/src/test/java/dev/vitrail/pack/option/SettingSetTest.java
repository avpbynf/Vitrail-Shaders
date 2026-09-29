package dev.vitrail.pack.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds what a reading of a pack is made of: the layers of chosen values, the two tables of defines
 * that come out of them and the two process-wide numbers a reading takes once when it is made.
 * <p>
 * {@link SettingSet#shadowMapScale} and {@link EngineDefines#machine} are process-global, so every
 * test puts both back where it found them.
 */
class SettingSetTest {

	private int savedScale;
	private EngineDefines.Environment savedMachine;

	@BeforeEach
	void remember() {
		this.savedScale = SettingSet.askedShadowMapScale();
		this.savedMachine = EngineDefines.machine();
		SettingSet.shadowMapScale(100);
		EngineDefines.machine(EngineDefines.Environment.of(123456));
	}

	@AfterEach
	void restore() {
		SettingSet.shadowMapScale(this.savedScale);
		EngineDefines.machine(this.savedMachine);
	}

	private static OptionIndex index(String... lines) {
		OptionIndex.Reader reader = new OptionIndex.Reader();
		reader.read("f.glsl", List.of(lines));

		return reader.index();
	}

	private static SettingSet with(Map<String, OptionValue> user) {
		return SettingSet.resolve(Map.of(), user, "test");
	}

	private static final OptionIndex PACK = index(
			"#define TOG_ON",
			"//#define TOG_OFF",
			"#define TOG_UNTESTED",
			"#define VAL 2 // [1 2 3]",
			"//#define VAL_OFF 3 // [1 3]",
			"#define VAL_BARE 5",
			"const int shadowMapResolution = 2048; // [1024 2048]",
			"const int shadowDistance = 128;",
			"const bool shadowHardwareFiltering = true;",
			"const bool shadowtex0Nearest = false;",
			"const bool shadowtex1Nearest = true;",
			"const int notListed = 1; // [1 2]",
			"const uint sunPathRotation = 1u; // [1u]",
			"#ifdef TOG_ON", "#ifdef TOG_OFF", "#ifdef shadowHardwareFiltering", "#ifdef shadowtex0Nearest");

	// ---- the layers --------------------------------------------------------------------------

	@Test
	void theUserLayerWinsOverTheProfileAndBothAreKept() {
		Map<String, OptionValue> profile = new LinkedHashMap<>();
		profile.put("A", OptionValue.on());
		profile.put("B", OptionValue.of("1"));
		Map<String, OptionValue> user = new LinkedHashMap<>();
		user.put("B", OptionValue.of("2"));
		user.put("C", OptionValue.off());

		SettingSet settings = SettingSet.resolve(profile, user, "chosen");

		assertEquals(3, settings.chosen().size());
		assertSame(OptionValue.on(), settings.chosen().get("A"));
		assertEquals("2", settings.chosen().get("B").text());
		assertSame(OptionValue.off(), settings.chosen().get("C"));
		assertEquals("chosen", settings.variantName());
	}

	@Test
	void theChosenLayerIsACopyThatCannotBeChanged() {
		Map<String, OptionValue> user = new LinkedHashMap<>();
		user.put("A", OptionValue.on());

		SettingSet settings = with(user);
		user.put("B", OptionValue.on());

		assertEquals(Map.of("A", OptionValue.on()), settings.chosen());
		assertThrows(UnsupportedOperationException.class, () -> settings.chosen().put("Z", OptionValue.on()));
	}

	@Test
	void theDefaultsChooseNothingAndAreCalledDefault() {
		SettingSet defaults = SettingSet.defaults();

		assertEquals(Map.of(), defaults.chosen());
		assertEquals("default", defaults.variantName());
	}

	// ---- the shadow map scale ----------------------------------------------------------------

	@Test
	void aReadingTakesTheScaleOnceWhenItIsMade() {
		SettingSet.shadowMapScale(50);
		SettingSet first = SettingSet.defaults();
		SettingSet.shadowMapScale(75);
		SettingSet second = SettingSet.defaults();

		assertEquals(50, first.scale());
		assertEquals(75, second.scale());
		assertEquals(75, SettingSet.askedShadowMapScale());
		assertTrue(first.scale() != SettingSet.askedShadowMapScale(), "the reading in hand is out of date");
	}

	@Test
	void theScaleStartsAtAHundredWhichDoesNothing() {
		assertEquals(100, SettingSet.defaults().scale());
		assertEquals(100, SettingSet.askedShadowMapScale());
	}

	@Test
	void anyPercentageIsHeldAsPushed() {
		SettingSet.shadowMapScale(0);
		assertEquals(0, SettingSet.defaults().scale());
		SettingSet.shadowMapScale(400);
		assertEquals(400, SettingSet.defaults().scale());
	}

	// ---- the table shaders.properties is read against ----------------------------------------

	@Test
	void everySettingIsDefinedAtItsDefaultInTheGlobalTable() {
		Map<String, String> table = SettingSet.defaults().globalDefines(PACK);

		assertEquals("", table.get("TOG_ON"));
		assertFalse(table.containsKey("TOG_OFF"), "commented out is not defined");
		assertEquals("", table.get("TOG_UNTESTED"), "a declaration, not an offered setting, is in the table");
		assertEquals("2", table.get("VAL"));
		assertFalse(table.containsKey("VAL_OFF"));
		assertEquals("5", table.get("VAL_BARE"), "a value with no list is in the table");
		assertEquals("2048", table.get("shadowMapResolution"));
		assertEquals("true", table.get("shadowHardwareFiltering"));
		assertFalse(table.containsKey("shadowtex0Nearest"), "a bool constant enters only while true");
	}

	@Test
	void aConstantThatIsNotASettingIsInNeitherTable() {
		Map<String, String> table = SettingSet.defaults().globalDefines(PACK);

		assertFalse(table.containsKey("shadowDistance"), "no list to cycle");
		assertFalse(table.containsKey("shadowtex1Nearest"), "true but nothing tests it");
		assertFalse(table.containsKey("notListed"), "off the closed list");
		assertFalse(table.containsKey("sunPathRotation"), "a uint");
	}

	@Test
	void aChoiceIsAppliedToTheGlobalTable() {
		Map<String, OptionValue> user = new LinkedHashMap<>();
		user.put("TOG_ON", OptionValue.off());
		user.put("TOG_OFF", OptionValue.on());
		user.put("VAL", OptionValue.of("3"));
		user.put("VAL_OFF", OptionValue.on());
		user.put("shadowMapResolution", OptionValue.of("1024"));
		user.put("shadowHardwareFiltering", OptionValue.off());
		user.put("shadowtex0Nearest", OptionValue.on());

		Map<String, String> table = with(user).globalDefines(PACK);

		assertFalse(table.containsKey("TOG_ON"));
		assertEquals("", table.get("TOG_OFF"));
		assertEquals("3", table.get("VAL"));
		assertEquals("3", table.get("VAL_OFF"), "a switch on a valued define is its default text");
		assertEquals("1024", table.get("shadowMapResolution"));
		assertFalse(table.containsKey("shadowHardwareFiltering"));
		assertEquals("true", table.get("shadowtex0Nearest"));
	}

	@Test
	void aTextChosenForABoolConstantReadsAsOffAndOneForAnUnofferedConstantIsIgnored() {
		Map<String, OptionValue> user = new LinkedHashMap<>();
		user.put("shadowHardwareFiltering", OptionValue.of("1"));
		user.put("shadowDistance", OptionValue.of("256"));

		Map<String, String> table = with(user).globalDefines(PACK);

		assertFalse(table.containsKey("shadowHardwareFiltering"));
		assertFalse(table.containsKey("shadowDistance"));
	}

	@Test
	void aNameThePackDeclaresNowhereIsInNoTable() {
		SettingSet settings = with(Map.of("GHOST", OptionValue.of("1"), "GHOST_ON", OptionValue.on()));

		assertFalse(settings.globalDefines(PACK).containsKey("GHOST"));
		assertFalse(settings.globalDefines(PACK).containsKey("GHOST_ON"));
		assertFalse(settings.unitDefines().containsKey("GHOST"));
		assertFalse(settings.unitDefines().containsKey("GHOST_ON"));
	}

	@Test
	void theGlobalTableHoldsTheEngineSymbolsAndTheSettingsOverrideAName() {
		OptionIndex clash = index("#define MC_VERSION 5", "#define OTHER 1");

		Map<String, String> table = SettingSet.defaults().globalDefines(clash);

		assertEquals("5", table.get("MC_VERSION"), "the pack's own line replaces the engine's number");
		assertEquals("1", table.get("OTHER"));
		assertEquals("11102", table.get("IRIS_VERSION"));
		assertTrue(table.containsKey("IS_IRIS"));
	}

	// ---- the table a source file starts with -------------------------------------------------

	@Test
	void aSourceFileStartsWithTheEngineSymbolsAlone() {
		Map<String, String> unit = with(Map.of("TOG_OFF", OptionValue.on(), "VAL", OptionValue.of("3"))).unitDefines();

		assertEquals(EngineDefines.table(123456), unit);
		assertEquals("123456", unit.get("MC_VERSION"));
		assertNull(unit.get("VAL"));
		assertNull(unit.get("TOG_OFF"));
	}

	@Test
	void theEngineTableIsTakenWhenTheReadingIsMade() {
		SettingSet before = SettingSet.defaults();
		EngineDefines.machine(EngineDefines.Environment.of(777));
		SettingSet after = SettingSet.defaults();

		assertEquals("123456", before.unitDefines().get("MC_VERSION"));
		assertEquals("777", after.unitDefines().get("MC_VERSION"));
		assertEquals("123456", before.globalDefines(PACK).get("MC_VERSION"));
	}
}
