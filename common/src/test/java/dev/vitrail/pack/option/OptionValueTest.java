package dev.vitrail.pack.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Holds which words become a switch and which stay text, since the two are different edits to the
 * pack's source: a switch uncomments or comments a declaration, text rewrites it.
 */
class OptionValueTest {

	@Test
	void onlyFourWordsBecomeSwitchesInAnyCase() {
		for (String word : List.of("on", "ON", "On", "true", "TRUE", "True", " on ", "\ttrue\n")) {
			OptionValue value = OptionValue.parse(word);

			assertTrue(value.isBoolean(), word);
			assertTrue(value.asBoolean(), word);
			assertSame(OptionValue.on(), value, word);
		}

		for (String word : List.of("off", "OFF", "Off", "false", "FALSE", " false ")) {
			OptionValue value = OptionValue.parse(word);

			assertTrue(value.isBoolean(), word);
			assertFalse(value.asBoolean(), word);
			assertSame(OptionValue.off(), value, word);
		}
	}

	@Test
	void everythingElseIsTextEvenZeroAndOne() {
		for (String word : List.of("0", "1", "yes", "no", "enabled", "2", "-1", "onn", "of", "tru", "on off", "")) {
			OptionValue value = OptionValue.parse(word);

			assertFalse(value.isBoolean(), word);
			assertFalse(value.asBoolean(), word);
			assertEquals(word, value.text(), word);
		}
	}

	@Test
	void textIsTrimmedOnParseButNotOnOf() {
		assertEquals("2", OptionValue.parse("  2 ").text());
		assertEquals("  2 ", OptionValue.of("  2 ").text());
		assertFalse(OptionValue.of("on").isBoolean(), "of() never reads a word");
		assertFalse(OptionValue.of("true").isBoolean());
	}

	@Test
	void aBooleanHasNoText() {
		assertNull(OptionValue.on().text());
		assertNull(OptionValue.off().text());
	}

	@Test
	void asTextIsTheInverseOfParseAndSpellsSwitchesOnAndOff() {
		assertEquals("on", OptionValue.on().asText());
		assertEquals("off", OptionValue.off().asText());
		assertEquals("1.5", OptionValue.of("1.5").asText());

		for (String word : List.of("on", "off", "0", "1", "Medium", "0.25", "-3")) {
			assertEquals(word, OptionValue.parse(word).asText(), word);
		}

		// true and false read back as the pair a settings file is written with.
		assertEquals("on", OptionValue.parse("true").asText());
		assertEquals("off", OptionValue.parse("false").asText());
	}

	@Test
	void toStringSpellsSwitchesTrueAndFalse() {
		assertEquals("true", OptionValue.on().toString());
		assertEquals("false", OptionValue.off().toString());
		assertEquals("2048", OptionValue.of("2048").toString());
	}

	@Test
	void theTwoSwitchesAreSingletons() {
		assertSame(OptionValue.on(), OptionValue.on());
		assertSame(OptionValue.off(), OptionValue.off());
		assertFalse(OptionValue.on() == OptionValue.off());
	}
}
