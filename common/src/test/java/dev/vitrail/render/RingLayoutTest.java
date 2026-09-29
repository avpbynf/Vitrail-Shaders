package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * How blocks of different lengths are laid out in one buffer: every offset a multiple of the
 * alignment, a length rounded up to it, a range handed back kept out of reach until the ring has
 * turned, and a full layout saying no rather than growing.
 * <p>
 * The cases are on the side where a wrong answer is two programs standing in the same bytes, which
 * draws one of them with the other's values and says nothing about it, and on the side where the
 * fallback to a ring of a block's own is decided: a layout that refuses too early costs a fence a
 * frame, and one that refuses too late has already overlapped.
 */
class RingLayoutTest {

	private static final int ALIGNMENT = 256;

	private final RingLayout layout = new RingLayout(4096, ALIGNMENT);

	// -- the arithmetic ------------------------------------------------------------------------

	@Test
	void aLengthIsRoundedUpToTheAlignment() {
		assertEquals(256, RingLayout.slotBytes(1, 256));
		assertEquals(256, RingLayout.slotBytes(256, 256));
		assertEquals(512, RingLayout.slotBytes(257, 256));
		assertEquals(16, RingLayout.slotBytes(16, 16));
		assertEquals(32, RingLayout.slotBytes(17, 16));
	}

	@Test
	void anAlignmentThatIsNotAPowerOfTwoStillRoundsUp() {
		assertEquals(192, RingLayout.slotBytes(129, 64));
		assertEquals(120, RingLayout.slotBytes(100, 40));
	}

	@Test
	void theCapacityIsTruncatedToWholeAlignments() {
		RingLayout odd = new RingLayout(1000, 256);

		assertEquals(768, odd.capacity());
		assertEquals(0, odd.claim(768));
		assertEquals(-1, odd.claim(1));
	}

	@Test
	void aLayoutThatCannotHoldOneAlignmentIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> new RingLayout(100, 256));
		assertThrows(IllegalArgumentException.class, () -> new RingLayout(4096, 0));
	}

	// -- claiming ------------------------------------------------------------------------------

	@Test
	void rangesAreLaidOutOneAfterAnotherAtMultiplesOfTheAlignment() {
		assertEquals(0, this.layout.claim(100));
		assertEquals(256, this.layout.claim(256));
		assertEquals(512, this.layout.claim(257));
		assertEquals(1024, this.layout.claim(1));
	}

	@Test
	void aBlockOfNoLengthStillTakesARange() {
		assertEquals(0, this.layout.claim(0));
		assertEquals(256, this.layout.claim(0));
	}

	@Test
	void whatIsClaimedIsWhatWasLaidOutAndThePeakRemembersTheMost() {
		int first = this.layout.claim(300);
		this.layout.claim(10);

		assertEquals(768, this.layout.claimed());

		this.layout.release(first, 300);

		assertEquals(256, this.layout.claimed());
		assertEquals(768, this.layout.peak());
		assertEquals(2, this.layout.claims());
	}

	@Test
	void aFullLayoutSaysNoAndIsLeftAsItWas() {
		for (int at = 0; at < 4096; at += 256) {
			assertEquals(at, this.layout.claim(200));
		}

		assertEquals(4096, this.layout.claimed());
		assertEquals(-1, this.layout.claim(1));
		assertEquals(4096, this.layout.claimed());
		assertEquals(16, this.layout.claims());
	}

	@Test
	void aBlockWiderThanTheWholeBufferSaysNoWhateverIsFree() {
		assertEquals(-1, this.layout.claim(4097));
		assertEquals(0, this.layout.claimed());
		assertEquals(0, this.layout.claim(4096));
	}

	@Test
	void aBlockTooWideForEveryStretchSaysNoWhileTheSumOfThemWouldHoldIt() {
		int[] at = new int[4];
		for (int i = 0; i < 4; i++) {
			at[i] = this.layout.claim(1024);
		}

		// Two stretches of a kilobyte with a claimed one between them: two kilobytes free, and no
		// range of two.
		this.layout.release(at[0], 1024);
		this.layout.release(at[2], 1024);
		this.layout.turned();

		assertEquals(-1, this.layout.claim(2048));
		assertEquals(0, this.layout.claim(1024));
	}

	@Test
	void aBlockIsPlacedInTheFirstStretchThatHoldsIt() {
		int a = this.layout.claim(256);
		int b = this.layout.claim(512);
		this.layout.claim(256);

		this.layout.release(a, 256);
		this.layout.release(b, 512);
		this.layout.turned();

		// The two stretches touch, so they are one of 768 bytes: a small block starts at its head, the
		// next one takes what is left of it, and the third finds it used up and goes past the block
		// that is still claimed after it.
		assertEquals(0, this.layout.claim(100));
		assertEquals(256, this.layout.claim(500));
		assertEquals(1024, this.layout.claim(300));
	}

	// -- giving back ---------------------------------------------------------------------------

	@Test
	void aRangeGivenBackIsNotHandedOutBeforeTheRingTurns() {
		for (int at = 0; at < 4096; at += 256) {
			this.layout.claim(256);
		}

		this.layout.release(1024, 256);

		assertEquals(-1, this.layout.claim(256));

		this.layout.turned();

		assertEquals(1024, this.layout.claim(256));
	}

	@Test
	void aRangeGivenBackTwiceInOneTurnIsRefused() {
		int at = this.layout.claim(256);
		this.layout.release(at, 256);

		assertThrows(IllegalArgumentException.class, () -> this.layout.release(at, 256));
	}

	@Test
	void aRangeGivenBackAfterItIsFreeIsRefused() {
		int at = this.layout.claim(256);
		this.layout.release(at, 256);
		this.layout.turned();

		assertThrows(IllegalArgumentException.class, () -> this.layout.release(at, 256));
	}

	@Test
	void aRangeThatWasNeverHandedOutIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> this.layout.release(0, 256));
		assertThrows(IllegalArgumentException.class, () -> this.layout.release(100, 256));
		assertThrows(IllegalArgumentException.class, () -> this.layout.release(4096, 256));
		assertThrows(IllegalArgumentException.class, () -> this.layout.release(-256, 256));
	}

	@Test
	void aRefusedReleaseChangesNothing() {
		int at = this.layout.claim(256);
		assertThrows(IllegalArgumentException.class, () -> this.layout.release(at + 100, 256));

		assertEquals(256, this.layout.claimed());
		assertEquals(256, this.layout.claim(256));
	}

	@Test
	void rangesGivenBackOnAllSidesOfOneJoinIntoOneStretch() {
		int[] at = new int[4];
		for (int i = 0; i < 4; i++) {
			at[i] = this.layout.claim(1024);
		}

		// The two ends first and the middle after, so that the middle is the one that has a free
		// neighbour on each side and has to join both.
		this.layout.release(at[0], 1024);
		this.layout.release(at[2], 1024);
		this.layout.turned();
		this.layout.release(at[1], 1024);
		this.layout.turned();

		assertEquals(0, this.layout.claim(3072));
	}

	@Test
	void everythingGivenBackInOneTurnComesBackWhole() {
		List<Integer> at = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			at.add(this.layout.claim(256));
		}

		// Out of order, which is how a chain's families release.
		for (int i : new int[] {5, 3, 4, 0, 15, 14, 1, 2, 9, 8, 7, 6, 13, 12, 10, 11}) {
			this.layout.release(at.get(i), 256);
		}

		this.layout.turned();

		assertEquals(0, this.layout.claimed());
		assertEquals(0, this.layout.claim(4096));
	}

	// -- what has to hold whatever the order ---------------------------------------------------

	@Test
	void noTwoRangesHandedOutAtOnceOverlapAndEachStartsOnTheAlignment() {
		Random random = new Random(20260929);
		RingLayout busy = new RingLayout(1 << 16, 64);
		List<int[]> live = new ArrayList<>();

		for (int step = 0; step < 20000; step++) {
			int roll = random.nextInt(10);
			if (roll < 5 || live.isEmpty()) {
				int bytes = 1 + random.nextInt(2000);
				int at = busy.claim(bytes);
				if (at >= 0) {
					int size = RingLayout.slotBytes(bytes, 64);
					assertEquals(0, at % 64);
					assertTrue(at + size <= busy.capacity());
					for (int[] other : live) {
						assertTrue(at + size <= other[0] || other[0] + other[1] <= at,
								"overlap at " + at + " of " + size + " with " + other[0] + " of "
										+ other[1]);
					}

					live.add(new int[] {at, size, bytes});
				}
			} else if (roll < 9) {
				int[] gone = live.remove(random.nextInt(live.size()));
				busy.release(gone[0], gone[2]);
			} else {
				busy.turned();
			}
		}

		int held = 0;
		for (int[] one : live) {
			held += one[1];
		}

		assertEquals(held, busy.claimed());
	}

	@Test
	void aLayoutThatIsEmptiedIsWholeAgainWhateverItWasThrough() {
		Random random = new Random(7);
		RingLayout busy = new RingLayout(1 << 14, 16);
		List<int[]> live = new ArrayList<>();

		for (int step = 0; step < 5000; step++) {
			if (random.nextBoolean() || live.isEmpty()) {
				int bytes = 1 + random.nextInt(900);
				int at = busy.claim(bytes);
				if (at >= 0) {
					live.add(new int[] {at, bytes});
				}
			} else {
				int[] gone = live.remove(random.nextInt(live.size()));
				busy.release(gone[0], gone[1]);
			}

			if (step % 7 == 0) {
				busy.turned();
			}
		}

		for (int[] gone : live) {
			busy.release(gone[0], gone[1]);
		}

		busy.turned();

		assertEquals(0, busy.claimed());
		assertEquals(0, busy.claim(busy.capacity()));
	}
}
