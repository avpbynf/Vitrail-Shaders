package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link Settled} to the question it answers: is the answer built last time still the answer,
 * decided on what it was built from and on nothing else.
 * <p>
 * Every overload has the same contract, so each is driven through the same steps. The two that a
 * wrong key would break most quietly are held hardest: a stale answer (an input moved and the key
 * did not notice) and a rebuild for ever (the key never matches what it recorded).
 */
class SettledTest {

	/** A matrix whose entry {@code entry} is {@code value} and whose others are those of the identity. */
	private static Matrix4f with(int entry, float value) {
		float[] columns = new Matrix4f().get(new float[16]);
		columns[entry] = value;

		return new Matrix4f().set(columns);
	}

	@Test
	void doesNotHoldBeforeAnythingWasRecordedNotEvenForTheIdentity() {
		// A matrix nothing has written is the identity, and the identity is a matrix a pass hands in.
		assertFalse(new Settled().holds(new Matrix4f()));
		assertFalse(new Settled().holds(new Matrix4f(), 0.0F, 0.0F));
		assertFalse(new Settled().holds(new Matrix4f(), new Matrix4f()));
	}

	@Test
	void holdsTheSecondTimeForTheSameMatrixAndRecordsWhatItWasGiven() {
		Settled settled = new Settled();
		Matrix4f matrix = with(5, 3.0F);

		assertFalse(settled.holds(matrix));
		assertTrue(settled.holds(matrix));
		assertTrue(settled.holds(new Matrix4f(matrix)), "an equal matrix, not the same object");

		matrix.m30(9.0F);
		assertFalse(settled.holds(matrix), "moved in place, which the key must not have aliased");
		assertTrue(settled.holds(matrix));
	}

	@Test
	void everyEntryOfTheMatrixIsPartOfTheKey() {
		for (int entry = 0; entry < 16; entry++) {
			Settled settled = new Settled();
			settled.holds(new Matrix4f());

			assertTrue(settled.holds(new Matrix4f()), "entry " + entry + " before");
			boolean diagonal = entry == 0 || entry == 5 || entry == 10 || entry == 15;

			assertFalse(settled.holds(with(entry, diagonal ? 2.0F : 0.5F)), "entry " + entry);
		}
	}

	@Test
	void anAnswerThatTurnsOnTwoNumbersBreaksOnEitherOfThem() {
		Settled settled = new Settled();
		Matrix4f matrix = with(12, 1.5F);

		assertFalse(settled.holds(matrix, 10.0F, 20.0F));
		assertTrue(settled.holds(matrix, 10.0F, 20.0F));
		assertFalse(settled.holds(matrix, 11.0F, 20.0F), "the first number moved");
		assertTrue(settled.holds(matrix, 11.0F, 20.0F));
		assertFalse(settled.holds(matrix, 11.0F, 21.0F), "the second number moved");
		assertTrue(settled.holds(matrix, 11.0F, 21.0F));
		assertFalse(settled.holds(with(12, 1.75F), 11.0F, 21.0F), "the matrix moved");
	}

	@Test
	void anAnswerBuiltOutOfTwoMatricesBreaksOnEitherOfThem() {
		Settled settled = new Settled();
		Matrix4f first = with(0, 2.0F);
		Matrix4f second = with(13, 4.0F);

		assertFalse(settled.holds(first, second));
		assertTrue(settled.holds(first, second));
		assertFalse(settled.holds(with(0, 3.0F), second), "the first moved");
		assertTrue(settled.holds(with(0, 3.0F), second));
		assertFalse(settled.holds(with(0, 3.0F), with(13, 5.0F)), "the second moved");
		assertFalse(settled.holds(second, with(0, 3.0F)), "and swapping them is a different question");
	}

	@Test
	void comparesExactlyAndNeverWithinATolerance() {
		Settled settled = new Settled();
		settled.holds(with(12, 1.0F));

		assertFalse(settled.holds(with(12, Math.nextUp(1.0F))), "one ulp is a different matrix");
		assertFalse(settled.holds(with(12, 1.0F)), "and the key now holds that other one");
	}

	@Test
	void aNaNMatchesTheNaNItWasRecordedFromSoItIsNotRebuiltForEver() {
		Settled settled = new Settled();
		Matrix4f broken = with(3, Float.NaN);

		assertFalse(settled.holds(broken));
		assertTrue(settled.holds(broken));
		assertTrue(settled.holds(with(3, Float.NaN)));
	}

	@Test
	void negativeZeroAndZeroAreTheSameKey() {
		// The comparison is JOML's with a delta of nought, which passes a difference of nought.
		Settled settled = new Settled();
		settled.holds(with(12, 0.0F));

		assertTrue(settled.holds(with(12, -0.0F)));
	}

	@Test
	void aNaNNumberIsNeverTheSameKeyAsItself() {
		// Compared with ==, so an angle that is NaN rebuilds every time. Harmless, since a rebuild is
		// only slower, and pinned so that nobody takes it for the matrix case.
		Settled settled = new Settled();
		Matrix4f matrix = new Matrix4f();

		assertFalse(settled.holds(matrix, Float.NaN, 0.0F));
		assertFalse(settled.holds(matrix, Float.NaN, 0.0F));
	}

	@Test
	void theFieldsOfOneShapeAreNotReadByAnother() {
		// One instance belongs to one call site. A second shape called on the same instance records
		// over the first and the first then finds its key gone: not a supported use, and the failure
		// is a rebuild and not a stale answer.
		Settled settled = new Settled();
		Matrix4f matrix = with(12, 1.0F);
		settled.holds(matrix, 5.0F, 6.0F);

		assertFalse(settled.holds(with(12, 2.0F)));
		assertFalse(settled.holds(matrix, 5.0F, 6.0F));
	}
}
