package dev.vitrail.pack.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.BufferObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the storage buffers the loaded pack declared and the block names its programs use for
 * them. The registry is process-global, so every test starts and ends with it empty.
 */
class CustomStorageTest {

	@BeforeEach
	@AfterEach
	void empty() {
		CustomStorage.clear();
	}

	private static BufferObject.Reading reading(String... indexAndValue) {
		Map<Integer, BufferObject> buffers = new LinkedHashMap<>();
		for (int i = 0; i < indexAndValue.length; i += 2) {
			assertNull(BufferObject.parse(indexAndValue[i], indexAndValue[i + 1], buffers), indexAndValue[i]);
		}

		return new BufferObject.Reading(new ArrayList<>(buffers.values()), List.of());
	}

	@Test
	void nothingIsNamedBeforeAPackIsInstalled() {
		assertFalse(CustomStorage.named("blockData"));
		assertEquals(-1, CustomStorage.indexOf("blockData"));
		assertTrue(CustomStorage.reading().buffers().isEmpty());
	}

	@Test
	void aBlockIsNamedWhenABufferObjectCarriesItsName() {
		CustomStorage.install(reading("0", "1024 blockData", "3", "64"));

		assertTrue(CustomStorage.named("blockData"));
		assertFalse(CustomStorage.named("other"));
		assertEquals(0, CustomStorage.indexOf("blockData"));
		assertEquals(-1, CustomStorage.indexOf("other"));
	}

	@Test
	void aBlockWithNoNameIsServedByTheBindingItsProgramDeclared() {
		CustomStorage.install(reading("0", "1024 blockData", "3", "64"));

		CustomStorage.declare("counters", 3);

		assertTrue(CustomStorage.named("counters"));
		assertEquals(3, CustomStorage.indexOf("counters"));
	}

	@Test
	void aBindingWithNoBufferBehindItOrNoBindingAtAllServesNothing() {
		CustomStorage.install(reading("0", "1024 blockData"));

		CustomStorage.declare("nobuffer", 7);
		CustomStorage.declare("unbound", -1);

		assertFalse(CustomStorage.named("nobuffer"));
		assertFalse(CustomStorage.named("unbound"));
		assertEquals(7, CustomStorage.indexOf("nobuffer"), "the binding is still the index it maps to");
		assertEquals(-1, CustomStorage.indexOf("unbound"));
	}

	@Test
	void aBlockThePackGaveNoBindingIsNeverServedEvenByABufferAtIndexMinusOne() {
		// The parser accepts -1 as an index (see BufferObjectTest), which is what gives the guard on
		// the binding something to refuse: -1 is what a program with no layout binding is recorded as.
		CustomStorage.install(reading("-1", "64"));

		CustomStorage.declare("unbound", -1);

		assertFalse(CustomStorage.named("unbound"));
	}

	@Test
	void aBufferNameWinsOverABindingOfTheSameBlock() {
		CustomStorage.install(reading("0", "1024 blockData", "5", "64"));

		CustomStorage.declare("blockData", 5);

		assertEquals(0, CustomStorage.indexOf("blockData"));
	}

	@Test
	void aNameThatIsNullOrEmptyIsNotRecorded() {
		CustomStorage.install(reading("2", "64"));

		CustomStorage.declare(null, 2);
		CustomStorage.declare("", 2);

		assertFalse(CustomStorage.named(""));
		assertEquals(-1, CustomStorage.indexOf(""));
	}

	@Test
	void installingReplacesTheBuffersAndLeavesTheBindingsWhichClearForgets() {
		CustomStorage.install(reading("1", "64"));
		CustomStorage.declare("counters", 1);
		assertTrue(CustomStorage.named("counters"));

		BufferObject.Reading other = reading("4", "64 second");
		CustomStorage.install(other);

		assertSame(other, CustomStorage.reading());
		assertFalse(CustomStorage.named("counters"), "index 1 is no longer declared");
		assertTrue(CustomStorage.named("second"));
		assertEquals(1, CustomStorage.indexOf("counters"), "the binding half is not replaced by an install");

		CustomStorage.clear();

		assertFalse(CustomStorage.named("second"));
		assertEquals(-1, CustomStorage.indexOf("counters"));
		assertTrue(CustomStorage.reading().buffers().isEmpty());
	}
}
