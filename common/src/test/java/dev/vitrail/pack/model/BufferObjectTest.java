package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds the grammar of {@code bufferObject.N}: an index of nought to twelve, a size in bytes and
 * an optional block name, or the four-word relative form; and the reasons a line is refused.
 */
class BufferObjectTest {

	private final Map<Integer, BufferObject> buffers = new LinkedHashMap<>();

	private String parse(String index, String value) {
		return BufferObject.parse(index, value, this.buffers);
	}

	@Test
	void aSizeAloneIsAnUnnamedAbsoluteBuffer() {
		assertNull(parse("0", "1024"));

		assertEquals(new BufferObject(0, 1024L, false, 0.0F, 0.0F, Optional.empty()), this.buffers.get(0));
	}

	@Test
	void aSecondWordIsTheBlockName() {
		assertNull(parse("1", "2048 blockData"));

		assertEquals(Optional.of("blockData"), this.buffers.get(1).name());
		assertEquals(2048L, this.buffers.get(1).size());
		assertFalse(this.buffers.get(1).relative());
	}

	@Test
	void anEmptySecondWordIsNoName() {
		assertNull(parse("2", "512 "));

		assertEquals(Optional.empty(), this.buffers.get(2).name());
	}

	@Test
	void aSizeIsALongSoABufferOfSeveralGigabytesIsRead() {
		assertNull(parse("3", "3000000000"));

		assertEquals(3_000_000_000L, this.buffers.get(3).size());
	}

	@Test
	void fourWordsOrMoreAreTheRelativeFormWithTheScaleOfEachAxis() {
		assertNull(parse("4", "16 true 0.5 0.25"));
		BufferObject relative = this.buffers.get(4);

		assertEquals(16L, relative.size());
		assertTrue(relative.relative());
		assertEquals(0.5F, relative.scaleX());
		assertEquals(0.25F, relative.scaleY());
		assertEquals(Optional.empty(), relative.name());

		// A fifth word is ignored.
		assertNull(parse("5", "16 TRUE 1 1 extra"));
		assertTrue(this.buffers.get(5).relative());
	}

	@Test
	void theRelativeFlagIsAnyWordButOnlyTrueIsTrue() {
		assertNull(parse("6", "4 false 2 2"));
		assertNull(parse("7", "4 yes 2 2"));

		// Not relative, and the scales are kept all the same.
		assertFalse(this.buffers.get(6).relative());
		assertEquals(2.0F, this.buffers.get(6).scaleX());
		assertFalse(this.buffers.get(7).relative());
	}

	@Test
	void anIndexPastTwelveIsRefusedAndTwelveIsTheLastAllowed() {
		assertEquals("only indices 0 to 12 are allowed", parse("13", "16"));
		assertEquals("only indices 0 to 12 are allowed", parse("2147483647", "16"));
		assertNull(parse("12", "16"));
		assertEquals(12, BufferObject.LIMIT);
		assertEquals(List.of(12), new ArrayList<>(this.buffers.keySet()));
	}

	@Test
	void anIndexThatIsNotAnIntIsNotANumber() {
		for (String index : new String[] {"", "x", "1.5", "99999999999", " 1", "0x1"}) {
			assertEquals("index is not a number", parse(index, "16"), "'" + index + "'");
		}
		assertTrue(this.buffers.isEmpty());
	}

	/** The properties reader only hands digits over, so the parser itself never had to look. */
	@Test
	void knownBug_aNegativeIndexIsAccepted() {
		assertNull(parse("-1", "64"));

		assertEquals(64L, this.buffers.get(-1).size());
	}

	@Test
	void aMissingOrUnreadableSizeIsRefused() {
		assertEquals("expected a size", parse("0", ""));
		assertEquals("expected a size", parse("0", " 5"));
		assertEquals("size is not a number", parse("0", "abc"));
		assertEquals("size is not a number", parse("0", "1.5"));
		assertEquals("size is not a number", parse("0", "1024b"));
		assertEquals("size is not a number", parse("0", "99999999999999999999"));
		assertTrue(this.buffers.isEmpty());
	}

	@Test
	void aSizeBelowOneDisablesTheBufferInsteadOfAllocatingNothing() {
		assertEquals("size below one disables the buffer", parse("0", "0"));
		assertEquals("size below one disables the buffer", parse("0", "-5"));
		assertEquals("size below one disables the buffer", parse("0", "0 name"));
		assertTrue(this.buffers.isEmpty());
	}

	@Test
	void threeWordsAreNeitherFormAndFourWithABadScaleAreRefused() {
		assertEquals("a relative buffer takes four words", parse("0", "10 a b"));
		assertEquals("a relative buffer takes four words", parse("0", "10 true 0.5"));
		assertEquals("relative scale is not a number", parse("0", "10 true x y"));
		assertEquals("relative scale is not a number", parse("0", "10 true 0.5 "));
		assertTrue(this.buffers.isEmpty());
	}

	@Test
	void theLastLineOfAnIndexWins() {
		assertNull(parse("0", "100 first"));
		assertNull(parse("0", "200 second"));

		assertEquals(1, this.buffers.size());
		assertEquals(200L, this.buffers.get(0).size());
		assertEquals(Optional.of("second"), this.buffers.get(0).name());
	}

	@Test
	void describesAnAbsoluteBufferAndARelativeOne() {
		assertNull(parse("0", "1024 blockData"));
		assertNull(parse("1", "2048"));
		assertNull(parse("3", "4 true 0.5 0.5"));

		assertEquals("0 as blockData 1024 bytes", this.buffers.get(0).describe());
		assertEquals("1 2048 bytes", this.buffers.get(1).describe());
		assertEquals("3 4 bytes per pixel at 0.5x0.5 of the screen", this.buffers.get(3).describe());
	}

	@Test
	void aReadingHoldsCopiesAndAnswersWhichIndicesAndNamesItHas() {
		List<BufferObject> list = new ArrayList<>();
		list.add(new BufferObject(2, 64L, false, 0.0F, 0.0F, Optional.of("blocks")));
		list.add(new BufferObject(5, 64L, false, 0.0F, 0.0F, Optional.empty()));
		List<String> dropped = new ArrayList<>(List.of("bufferObject.0 = 0: size below one disables the buffer"));

		BufferObject.Reading reading = new BufferObject.Reading(list, dropped);
		list.clear();
		dropped.clear();

		assertEquals(2, reading.buffers().size());
		assertEquals(1, reading.dropped().size());
		assertTrue(reading.hasIndex(2));
		assertTrue(reading.hasIndex(5));
		assertFalse(reading.hasIndex(3));
		assertTrue(reading.hasName("blocks"));
		assertFalse(reading.hasName("other"));
		assertFalse(reading.hasName(""));
		assertThrows(UnsupportedOperationException.class, () -> reading.buffers().clear());
		assertThrows(UnsupportedOperationException.class, () -> reading.dropped().clear());
	}

	@Test
	void anEmptyReadingHasNothing() {
		BufferObject.Reading empty = BufferObject.Reading.empty();

		assertTrue(empty.buffers().isEmpty());
		assertTrue(empty.dropped().isEmpty());
		assertFalse(empty.hasIndex(0));
		assertFalse(empty.hasName("x"));
	}
}
