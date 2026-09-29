package dev.vitrail.dh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Holds the two records that cross out of the bridge: what a far terrain draw is handed is one list
 * of sections, each holding its own copy of its pieces.
 * <p>
 * The bridge builds every section from ONE working list that it clears and refills, so a section
 * that kept the list it was handed would change under the pack when the next section is built.
 */
class DhLodsRecordsTest {

	@Test
	void aSectionKeepsItsOwnCopyOfThePiecesItWasHanded() {
		List<DhLods.Piece> working = new ArrayList<>();
		working.add(new DhLods.Piece(null, null, 6));

		DhLods.Section first = new DhLods.Section(1, 2, 3, working);
		working.clear();
		working.add(new DhLods.Piece(null, null, 9));
		DhLods.Section second = new DhLods.Section(4, 5, 6, working);

		assertEquals(List.of(new DhLods.Piece(null, null, 6)), first.pieces());
		assertEquals(List.of(new DhLods.Piece(null, null, 9)), second.pieces());
	}

	@Test
	void theCopyCannotBeChanged() {
		// Handed a list that can be changed, so that what refuses is the copy and not the argument.
		List<DhLods.Piece> working = new ArrayList<>(List.of(new DhLods.Piece(null, null, 1)));
		DhLods.Section section = new DhLods.Section(0, 0, 0, working);

		assertThrows(UnsupportedOperationException.class, () -> section.pieces().clear());
		assertThrows(UnsupportedOperationException.class,
				() -> section.pieces().add(new DhLods.Piece(null, null, 2)));
	}

	@Test
	void aSectionWithNoPiecesListIsRefused() {
		assertThrows(NullPointerException.class, () -> new DhLods.Section(0, 0, 0, null));

		List<DhLods.Piece> withNull = new ArrayList<>();
		withNull.add(null);
		assertThrows(NullPointerException.class, () -> new DhLods.Section(0, 0, 0, withNull));
	}

	@Test
	void sectionsAndPiecesCompareByTheirValues() {
		DhLods.Section one = new DhLods.Section(1, 2, 3, List.of(new DhLods.Piece(null, null, 6)));
		DhLods.Section same = new DhLods.Section(1, 2, 3, new ArrayList<>(List.of(new DhLods.Piece(null, null, 6))));

		assertEquals(one, same);
		assertEquals(one.hashCode(), same.hashCode());
		assertNotEquals(one, new DhLods.Section(1, 2, 4, List.of(new DhLods.Piece(null, null, 6))));
		assertNotEquals(one, new DhLods.Section(1, 2, 3, List.of(new DhLods.Piece(null, null, 7))));
		assertEquals(6, one.pieces().get(0).indexCount());
		assertEquals(2, one.y());
	}
}
