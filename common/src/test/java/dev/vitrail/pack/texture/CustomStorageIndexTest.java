package dev.vitrail.pack.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitrail.pack.model.BufferObject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the index a storage block's name is bound at to the answer the walk over the declared
 * buffers gave before the table replaced it.
 * <p>
 * The descriptor push asks it for every name of every pass once a pack declares a storage buffer,
 * and a name answered at another index binds another buffer, or none, under a layout that
 * declared one: the pack reads the wrong bytes, or a uniform placeholder where a storage buffer
 * stands. {@link CustomStorageTest} holds what a name answers; these are the orders of calls and
 * of lines a table built ahead of the question could get wrong where the walk could not.
 */
class CustomStorageIndexTest {

	@BeforeEach
	@AfterEach
	void forget() {
		CustomStorage.clear();
	}

	@Test
	void putsTheDeclaredNameBeforeABindingFiledAheadOfIt() {
		CustomStorage.declare("shared", 5);
		CustomStorage.install(reading(buffer(2, "shared")));

		assertEquals(2, CustomStorage.indexOf("shared"));
	}

	@Test
	void putsTheFirstLineDeclaringANameBeforeLaterOnes() {
		CustomStorage.install(reading(buffer(3, "twice"), buffer(1, "twice")));

		assertEquals(3, CustomStorage.indexOf("twice"));
	}

	@Test
	void takesTheLastBindingFiledForAName() {
		CustomStorage.declare("moved", 1);
		CustomStorage.declare("moved", 4);

		assertEquals(4, CustomStorage.indexOf("moved"));
	}

	@Test
	void forgetsTheNamesOfAReadingReplaced() {
		CustomStorage.install(reading(buffer(2, "gone")));
		CustomStorage.install(reading(buffer(2, "kept")));

		assertEquals(-1, CustomStorage.indexOf("gone"));
		assertEquals(2, CustomStorage.indexOf("kept"));
	}

	@Test
	void forgetsBothHalvesOnClear() {
		CustomStorage.install(reading(buffer(2, "declared")));
		CustomStorage.declare("filed", 3);
		CustomStorage.clear();

		assertEquals(-1, CustomStorage.indexOf("declared"));
		assertEquals(-1, CustomStorage.indexOf("filed"));
	}

	/**
	 * Every name of one crowded pack against the walk itself, written out below as it stood, so
	 * that an ordering the cases above do not name is caught all the same.
	 */
	@Test
	void answersEveryNameAsTheWalkDid() {
		BufferObject.Reading reading = reading(buffer(4, "a"), buffer(0, null), buffer(1, "b"),
				buffer(2, "a"), buffer(7, "c"));
		Map<String, Integer> filed = Map.of("b", 9, "c", -1, "d", 0, "e", -1, "f", 12);
		CustomStorage.install(reading);
		filed.forEach(CustomStorage::declare);

		for (String name : List.of("a", "b", "c", "d", "e", "f", "g", "Projection")) {
			assertEquals(walk(reading, filed, name), CustomStorage.indexOf(name), name);
		}
	}

	private static int walk(BufferObject.Reading reading, Map<String, Integer> filed, String name) {
		for (BufferObject buffer : reading.buffers()) {
			if (buffer.name().filter(name::equals).isPresent()) {
				return buffer.index();
			}
		}

		Integer index = filed.get(name);
		return index == null ? -1 : index;
	}

	private static BufferObject.Reading reading(BufferObject... buffers) {
		return new BufferObject.Reading(List.of(buffers), List.of());
	}

	private static BufferObject buffer(int index, String name) {
		return new BufferObject(index, 16L, false, 0.0F, 0.0F, Optional.ofNullable(name));
	}
}
