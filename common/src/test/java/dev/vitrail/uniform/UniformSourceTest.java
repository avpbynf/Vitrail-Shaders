package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Holds the two forms of {@link UniformSource} to their contract: a source that knows nothing of
 * elements answers every element with its one value, and one that does know answers the short form
 * with its first, so that a caller with no element to give never receives another unit's value.
 */
class UniformSourceTest {

	@Test
	void aSourceThatKnowsNoElementsAnswersEveryElementAlike() {
		UniformSource plain = (world, out) -> out.set(4.5F);
		Val val = new Val();

		for (int element = 0; element < 8; element++) {
			plain.read(new FakeWorld(), val, element);

			assertEquals(4.5F, val.f(0), "element " + element);
			assertEquals(1, val.rank());
		}
	}

	@Test
	void theElementFormOfAPlainSourceIsTheShortFormAndNothingMore() {
		int[] calls = new int[1];
		UniformSource counting = (world, out) -> {
			calls[0]++;
			out.set(7);
		};

		counting.read(new FakeWorld(), new Val(), 3);

		assertEquals(1, calls[0], "the default hands the element over to the short form once");
	}

	@Test
	void aSourceWhoseElementsDifferAnswersTheShortFormWithItsFirst() {
		UniformSource perElement = new UniformSource() {

			@Override
			public void read(WorldState world, Val out) {
				read(world, out, 0);
			}

			@Override
			public void read(WorldState world, Val out, int element) {
				out.set(10 + element);
			}
		};
		Val val = new Val();

		perElement.read(new FakeWorld(), val);
		assertEquals(10, val.i(0), "the short form is element nought");

		perElement.read(new FakeWorld(), val, 5);
		assertEquals(15, val.i(0));
	}
}
