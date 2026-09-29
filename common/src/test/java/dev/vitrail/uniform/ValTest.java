package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link Val}, the carrier one block reuses for every member, to what the coercion rules
 * lean on: the rank each setter leaves, zero past the rank, integers that stay exact, and matrices
 * that are flattened only when somebody reads them component by component.
 * <p>
 * The last of those is the one that can go stale. A matrix is copied into a flat array lazily, so a
 * value that reads the array after a different matrix (or a different rank) was set must see the
 * new numbers and not the ones the array still holds from before.
 */
class ValTest {

	/** Sixteen numbers, column by column, so that a component's value names its own place. */
	private static Matrix4f numbered(float first) {
		return new Matrix4f(
				first, first + 1, first + 2, first + 3,
				first + 4, first + 5, first + 6, first + 7,
				first + 8, first + 9, first + 10, first + 11,
				first + 12, first + 13, first + 14, first + 15);
	}

	@Test
	void eachSetterLeavesItsOwnRank() {
		Val val = new Val();

		assertEquals(1, val.set(1.0F).rank());
		assertEquals(2, val.set(1.0F, 2.0F).rank());
		assertEquals(3, val.set(1.0F, 2.0F, 3.0F).rank());
		assertEquals(4, val.set(1.0F, 2.0F, 3.0F, 4.0F).rank());
		assertEquals(1, val.set(1).rank());
		assertEquals(2, val.set(1, 2).rank());
		assertEquals(3, val.set(1, 2, 3).rank());
		assertEquals(4, val.set(1, 2, 3, 4).rank());
		assertEquals(1, val.set(true).rank());
		assertEquals(3, val.set(new Vector3f(1, 2, 3)).rank());
		assertEquals(3, val.set(new Vector3d(1, 2, 3)).rank());
		assertEquals(9, val.set(new Matrix3f()).rank());
		assertEquals(16, val.set(new Matrix4f()).rank());
		assertEquals(8, val.setFog(1, 2, 3, 4, 5, 6, 7, 8).rank());
	}

	@Test
	void integralIsTrueForTheIntegerSettersAndTheBoolAndFalseForEverythingElse() {
		Val val = new Val();

		assertTrue(val.set(3).integral());
		assertTrue(val.set(3, 4).integral());
		assertTrue(val.set(true).integral());
		assertFalse(val.set(3.0F).integral());
		assertFalse(val.set(new Vector3f()).integral());
		assertFalse(val.set(new Matrix4f()).integral());
		assertFalse(val.set(new Matrix3f()).integral());
		assertFalse(val.setFog(0, 0, 0, 0, 0, 0, 0, 0).integral());
		assertFalse(val.set(3).set(3.0F).integral(), "the last setter decides");
	}

	@Test
	void readingPastTheRankIsZeroWhateverTheCarrierHeldBefore() {
		Val val = new Val();
		val.set(1.0F, 2.0F, 3.0F, 4.0F);
		val.set(9.0F);

		assertEquals(9.0F, val.f(0));
		assertEquals(0.0F, val.f(1));
		assertEquals(0.0F, val.f(3));
		assertEquals(0.0F, val.f(15));
		assertEquals(0, val.i(1));

		val.set(numbered(1.0F));
		val.f(5);
		val.set(2.0F, 3.0F);
		assertEquals(0.0F, val.f(2), "a vec2 set over a flattened matrix");
		assertEquals(0, val.i(2));
	}

	@Test
	void aBoolIsAnIntegerOneOrNought() {
		Val val = new Val();

		assertEquals(1, val.set(true).i(0));
		assertEquals(1.0F, val.f(0));
		assertEquals(0, val.set(false).i(0));
		assertEquals(0.0F, val.f(0));
	}

	@Test
	void aVector3dIsNarrowedToFloatAndKeepsNothingElse() {
		Val val = new Val();
		val.set(new Vector3d(0.1, 16_777_217.0, -1e10));

		assertEquals(0.1F, val.f(0));
		assertEquals(16_777_216.0F, val.f(1), "float has no room for the odd integer past 2^24");
		assertEquals(-1e10F, val.f(2));
	}

	@Test
	void anIntegerSetIsExactAsAnIntegerAndRoundedAsAFloat() {
		Val val = new Val();
		val.set(16_777_217);

		assertEquals(16_777_217, val.i(0));
		assertEquals(16_777_216.0F, val.f(0), "the mirror in the floats rounds to even");

		val.set(Integer.MAX_VALUE, Integer.MIN_VALUE);
		assertEquals(Integer.MAX_VALUE, val.i(0));
		assertEquals(Integer.MIN_VALUE, val.i(1));
		assertEquals(2_147_483_648.0F, val.f(0));
		assertEquals(-2_147_483_648.0F, val.f(1));
	}

	@Test
	void aFloatReadAsAnIntegerTruncatesTowardZero() {
		Val val = new Val();

		assertEquals(2, val.set(2.9F).i(0));
		assertEquals(-2, val.set(-2.9F).i(0));
		assertEquals(0, val.set(0.5F).i(0));
		assertEquals(0, val.set(-0.5F).i(0));
		assertEquals(0, val.set(-0.0F).i(0));
		assertEquals(3, val.set(3.0F).i(0));
	}

	@Test
	void aFloatReadAsAnIntegerSaturatesAndSendsNaNToZero() {
		Val val = new Val();

		assertEquals(0, val.set(Float.NaN).i(0));
		assertEquals(Integer.MAX_VALUE, val.set(Float.POSITIVE_INFINITY).i(0));
		assertEquals(Integer.MIN_VALUE, val.set(Float.NEGATIVE_INFINITY).i(0));
		assertEquals(Integer.MAX_VALUE, val.set(3.0e9F).i(0));
		assertEquals(Integer.MIN_VALUE, val.set(-3.0e9F).i(0));
		assertEquals(2_147_483_520, val.set(2_147_483_520.0F).i(0), "the largest float below 2^31");
		assertEquals(Integer.MAX_VALUE, val.set(2_147_483_648.0F).i(0), "2^31 itself is out of range");
	}

	@Test
	void aMat4IsFlattenedColumnMajor() {
		Val val = new Val().set(numbered(1.0F));

		for (int i = 0; i < 16; i++) {
			assertEquals(1.0F + i, val.f(i), "component " + i);
			assertEquals(1 + i, val.i(i));
		}
	}

	@Test
	void aMat3IsFlattenedColumnMajor() {
		Val val = new Val().set(new Matrix3f(1, 2, 3, 4, 5, 6, 7, 8, 9));

		for (int i = 0; i < 9; i++) {
			assertEquals(1.0F + i, val.f(i), "component " + i);
		}

		assertEquals(0.0F, val.f(9), "past the nine of a mat3");
	}

	@Test
	void aMatrixSetAfterAFlattenedOneIsFlattenedAgain() {
		Val val = new Val().set(numbered(1.0F));
		assertEquals(6.0F, val.f(5));

		val.set(numbered(101.0F));
		assertEquals(106.0F, val.f(5), "not the array the first matrix left behind");
		assertEquals(101.0F, val.f(0));
	}

	@Test
	void aMat3SetAfterAFlattenedMat4ReadsTheMat3() {
		Val val = new Val().set(numbered(1.0F));
		val.f(0);

		val.set(new Matrix3f(21, 22, 23, 24, 25, 26, 27, 28, 29));

		assertEquals(21.0F, val.f(0));
		assertEquals(24.0F, val.f(3));
		assertEquals(29.0F, val.f(8));
	}

	@Test
	void aMatrixSetAfterAFogReadsTheMatrix() {
		Val val = new Val().setFog(1, 2, 3, 4, 5, 6, 7, 8);
		assertEquals(6.0F, val.f(5));

		val.set(numbered(50.0F));
		assertEquals(50.0F, val.f(0));
		assertEquals(55.0F, val.f(5));
	}

	@Test
	void aFogKeepsItsEightComponentsInTheOrderTheStructDeclaresThem() {
		Val val = new Val().setFog(1, 2, 3, 4, 5, 6, 7, 8);

		for (int i = 0; i < 8; i++) {
			assertEquals(1.0F + i, val.f(i));
		}

		assertEquals(0.0F, val.f(8));
	}

	@Test
	void theMatrixAccessorsHandBackACopyMadeAtTheSet() {
		Matrix4f source = numbered(1.0F);
		Val val = new Val().set(source);
		source.m00(-99.0F);

		assertEquals(1.0F, val.mat4().m00(), "later changes to the source do not reach the carrier");

		Matrix3f small = new Matrix3f(1, 2, 3, 4, 5, 6, 7, 8, 9);
		val.set(small);
		small.m00(-99.0F);
		assertEquals(1.0F, val.mat3().m00());
	}

	@Test
	void everySetterHandsBackTheCarrierSoCallsChain() {
		Val val = new Val();

		assertSame(val, val.set(1.0F));
		assertSame(val, val.set(1, 2));
		assertSame(val, val.set(new Matrix4f()));
		assertSame(val, val.set(new Matrix3f()));
		assertSame(val, val.setFog(0, 0, 0, 0, 0, 0, 0, 0));
	}
}
