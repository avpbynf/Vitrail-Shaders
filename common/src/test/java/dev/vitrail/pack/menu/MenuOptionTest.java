package dev.vitrail.pack.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.option.PackOption;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Holds what a setting is to a widget: which of the three forms it takes, what it is allowed to
 * hold with the pack's own default always among it, and how cycling wraps.
 */
class MenuOptionTest {

	private static PackOption toggle(boolean off) {
		return new PackOption("T", PackOption.Kind.TOGGLE, "", List.of(), off, null, "f.glsl", 1);
	}

	private static PackOption value(String defaultText, String... values) {
		return new PackOption("V", PackOption.Kind.VALUE, defaultText, List.of(values), false, null, "f.glsl", 1);
	}

	private static PackOption constant(String type, String defaultText, String... values) {
		return new PackOption("C", PackOption.Kind.CONST, defaultText, List.of(values), false, type, "f.glsl", 1);
	}

	@Test
	void aToggleIsOnOrOffAndDefaultsToWhatItShipsAs() {
		MenuOption on = MenuOption.of(toggle(false), false);
		MenuOption off = MenuOption.of(toggle(true), false);

		assertEquals(MenuOption.Form.TOGGLE, on.form());
		assertEquals("on", on.defaultValue());
		assertEquals(List.of("on", "off"), on.values());
		assertEquals("off", off.defaultValue());
		assertEquals(List.of("on", "off"), off.values());
	}

	@Test
	void aToggleIsNeverASliderWhateverIsAsked() {
		assertFalse(MenuOption.of(toggle(false), true).slider());
		assertFalse(MenuOption.of(constant("bool", "true"), true).slider());
	}

	@Test
	void aConstBoolIsAToggleLikeABareDefineAndOnlyTheWordTrueIsOn() {
		assertEquals("on", MenuOption.of(constant("bool", "true"), false).defaultValue());
		assertEquals("off", MenuOption.of(constant("bool", "false"), false).defaultValue());
		assertEquals("off", MenuOption.of(constant("bool", "TRUE"), false).defaultValue());
		assertEquals("off", MenuOption.of(constant("bool", "!false"), false).defaultValue());
		assertEquals(MenuOption.Form.TOGGLE, MenuOption.of(constant("bool", "true"), false).form());
		assertEquals(List.of("on", "off"), MenuOption.of(constant("bool", "true"), false).values());
	}

	@Test
	void aValueWithAListIsACycleThroughIt() {
		MenuOption option = MenuOption.of(value("2", "1", "2", "3"), false);

		assertEquals(MenuOption.Form.CYCLE, option.form());
		assertEquals(List.of("1", "2", "3"), option.values());
		assertEquals("2", option.defaultValue());
		assertEquals(3, option.size());
	}

	@Test
	void theDefaultTheListForgotIsAddedAtTheEnd() {
		MenuOption option = MenuOption.of(value("5", "1", "2", "3"), false);

		assertEquals(List.of("1", "2", "3", "5"), option.values());
		assertEquals(3, option.indexOf("5"));
	}

	@Test
	void aConstantIsACycleToo() {
		MenuOption option = MenuOption.of(constant("int", "2048", "1024", "2048", "4096"), false);

		assertEquals(MenuOption.Form.CYCLE, option.form());
		assertEquals(List.of("1024", "2048", "4096"), option.values());
		assertEquals(MenuOption.Form.CYCLE, MenuOption.of(constant("float", "1.0", "0.5"), false).form(),
				"the default the list forgot makes two values");
	}

	@Test
	void aListOfOneValueIsAHeadingShownAndNotChangeable() {
		MenuOption heading = MenuOption.of(value("0", "0"), true);

		assertEquals(MenuOption.Form.FIXED, heading.form());
		assertEquals(List.of("0"), heading.values());
		assertFalse(heading.slider(), "a fixed value is never a slider");
	}

	@Test
	void aListOfOneValueThatIsNotTheDefaultHasTwoAndCycles() {
		MenuOption option = MenuOption.of(value("1", "0"), false);

		assertEquals(MenuOption.Form.CYCLE, option.form());
		assertEquals(List.of("0", "1"), option.values());
	}

	@Test
	void aValueWithNoListHasNoValuesAndIsFixed() {
		MenuOption option = MenuOption.of(value("1.5"), false);

		assertEquals(MenuOption.Form.FIXED, option.form());
		assertEquals(List.of(), option.values(), "the default is only added to a list that exists");
		assertEquals(0, option.size());
	}

	@Test
	void aDuplicateInTheListIsKeptAndFound() {
		MenuOption option = MenuOption.of(value("1", "1", "1", "2"), false);

		assertEquals(List.of("1", "1", "2"), option.values());
		assertEquals(0, option.indexOf("1"));
	}

	@Test
	void aSliderIsHonouredForACycleOnly() {
		assertTrue(MenuOption.of(value("2", "1", "2", "3"), true).slider());
		assertFalse(MenuOption.of(value("2", "1", "2", "3"), false).slider());
	}

	@Test
	void indexOfIsWhereAValueSitsOrWhereTheDefaultDoesAndNeverBelowZero() {
		MenuOption option = MenuOption.of(value("2", "1", "2", "3"), false);

		assertEquals(0, option.indexOf("1"));
		assertEquals(2, option.indexOf("3"));
		assertEquals(1, option.indexOf("nowhere"));
		assertEquals(1, option.indexOf(""));
		assertEquals(0, MenuOption.of(value("1.5"), false).indexOf("anything"));
		assertEquals(1, MenuOption.of(toggle(true), false).indexOf("true"));
		assertEquals(0, MenuOption.of(toggle(false), false).indexOf("true"));
	}

	@Test
	void atWrapsInBothDirectionsForAnyIndex() {
		MenuOption option = MenuOption.of(value("2", "1", "2", "3"), false);

		assertEquals("1", option.at(0));
		assertEquals("3", option.at(2));
		assertEquals("1", option.at(3));
		assertEquals("2", option.at(4));
		assertEquals("3", option.at(-1));
		assertEquals("1", option.at(-3));
		assertEquals("3", option.at(-4));
		assertEquals("1", option.at(3_000_000));
		// 2^31 is 2 more than a multiple of three, and floorMod takes a negative up to the next multiple.
		assertEquals("2", option.at(Integer.MIN_VALUE));
		assertEquals("3", option.at(Integer.MIN_VALUE + 1));
		assertEquals("1", option.at(Integer.MAX_VALUE - 1));
	}

	@Test
	void anOptionWithNoValuesAnswersItsDefaultToAnyIndex() {
		MenuOption option = MenuOption.of(value("1.5"), false);

		assertEquals("1.5", option.at(0));
		assertEquals("1.5", option.at(-7));
		assertEquals("1.5", option.at(Integer.MAX_VALUE));
	}

	@Test
	void cyclingAToggleAlwaysComesBackToWhereItStarted() {
		MenuOption option = MenuOption.of(toggle(false), false);

		String cursor = option.defaultValue();
		for (int click = 0; click < 2; click++) {
			cursor = option.at(option.indexOf(cursor) + 1);
		}

		assertEquals("on", cursor);
	}

	@Test
	void theValuesAreACopyThatCannotBeChanged() {
		List<String> list = new ArrayList<>(List.of("1", "2"));
		MenuOption option = new MenuOption("N", MenuOption.Form.CYCLE, "1", list, false);
		list.add("3");

		assertEquals(List.of("1", "2"), option.values());
		assertThrows(UnsupportedOperationException.class, () -> option.values().add("x"));
	}
}
