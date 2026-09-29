package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vitrail.uniform.FakeWorld;
import dev.vitrail.uniform.UniformCatalog;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

/**
 * Holds what a pass drawn over a quad gets in place of fixed function state, {@link DrawValues}: an
 * identity model view, the matrix that carries the quad from (0,1) to clip space, and eight
 * identities where the texture matrices were.
 * <p>
 * The two quad matrices are held to what they do to points, since they are the ones with a reason
 * behind them: the projection has a zero third column, so it is singular, and its inverse is
 * written out because inverting it gives infinities.
 */
class DrawValuesTest {

	private static final float[] IDENTITY = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

	private final UniformCatalog engine = UniformCatalog.engine();

	private static Vector4f apply(Matrix4f m, float x, float y, float z) {
		return m.transform(new Vector4f(x, y, z, 1.0F));
	}

	@Test
	void theQuadProjectionCarriesTheUnitSquareToClipSpace() {
		assertEquals(new Vector4f(-1, -1, 0, 1), apply(DrawValues.QUAD_PROJECTION, 0, 0, 0));
		assertEquals(new Vector4f(1, -1, 0, 1), apply(DrawValues.QUAD_PROJECTION, 1, 0, 0));
		assertEquals(new Vector4f(-1, 1, 0, 1), apply(DrawValues.QUAD_PROJECTION, 0, 1, 0));
		assertEquals(new Vector4f(1, 1, 0, 1), apply(DrawValues.QUAD_PROJECTION, 1, 1, 0));
		assertEquals(new Vector4f(0, 0, 0, 1), apply(DrawValues.QUAD_PROJECTION, 0.5F, 0.5F, 0));
	}

	@Test
	void theQuadProjectionThrowsTheDepthAwayWhateverItIs() {
		for (float z : new float[] { -1000.0F, -1.0F, 0.0F, 0.5F, 1.0F, 1000.0F }) {
			assertEquals(0.0F, apply(DrawValues.QUAD_PROJECTION, 0.25F, 0.75F, z).z, "z " + z);
		}
	}

	@Test
	void theQuadProjectionIsSingularSoInvertingItGivesNoNumberAPackCouldUse() {
		assertEquals(0.0F, DrawValues.QUAD_PROJECTION.determinant());
		assertFalse(new Matrix4f(DrawValues.QUAD_PROJECTION).invert().isFinite());
	}

	@Test
	void theWrittenOutInverseTakesClipSpaceBackToTheUnitSquare() {
		assertEquals(new Vector4f(0, 0, 0, 1), apply(DrawValues.QUAD_PROJECTION_INVERSE, -1, -1, 0));
		assertEquals(new Vector4f(1, 1, 0, 1), apply(DrawValues.QUAD_PROJECTION_INVERSE, 1, 1, 0));
		assertEquals(new Vector4f(0.5F, 0.5F, 0, 1), apply(DrawValues.QUAD_PROJECTION_INVERSE, 0, 0, 0));
	}

	@Test
	void theTwoQuadMatricesUndoEachOtherInXAndYAndLoseTheZ() {
		Matrix4f both = new Matrix4f(DrawValues.QUAD_PROJECTION_INVERSE).mul(DrawValues.QUAD_PROJECTION);

		for (float x : new float[] { 0.0F, 0.125F, 0.5F, 1.0F }) {
			for (float y : new float[] { 0.0F, 0.375F, 0.75F, 1.0F }) {
				assertEquals(new Vector4f(x, y, 0, 1), apply(both, x, y, 42.0F));
			}
		}
	}

	@Test
	void aFullScreenPassHasNoModelViewNoNormalAndNoTextureTransform() {
		FakeWorld world = FakeWorld.distinct();

		assertArrayEquals(IDENTITY, ValueReads.matrix(ValueReads.read(this.engine, "of_ModelViewMatrix", world)));
		assertArrayEquals(IDENTITY,
				ValueReads.matrix(ValueReads.read(this.engine, "of_ModelViewMatrixInverse", world)));
		assertArrayEquals(new float[] { 1, 0, 0, 0, 1, 0, 0, 0, 1 },
				ValueReads.floats(ValueReads.read(this.engine, "of_NormalMatrix", world), 9));

		for (int unit = 0; unit < 8; unit++) {
			assertArrayEquals(IDENTITY,
					ValueReads.matrix(ValueReads.read(this.engine, "of_TextureMatrix", world, unit)), "unit " + unit);
		}
	}

	@Test
	void theProjectionAModelViewProjectionAndTheInverseAreTheQuadOnesWhateverTheWorld() {
		FakeWorld world = FakeWorld.distinct();
		float[] quad = ValueReads.columnMajor(DrawValues.QUAD_PROJECTION);

		assertArrayEquals(quad, ValueReads.matrix(ValueReads.read(this.engine, "of_ProjectionMatrix", world)));
		assertArrayEquals(quad, ValueReads.matrix(ValueReads.read(this.engine, "of_ModelViewProjectionMatrix", world)),
				"the model view is the identity, so the product is the projection");
		assertArrayEquals(ValueReads.columnMajor(DrawValues.QUAD_PROJECTION_INVERSE),
				ValueReads.matrix(ValueReads.read(this.engine, "of_ProjectionMatrixInverse", world)));
	}

	@Test
	void theCoreSpellingsAreTheFixedFunctionAnswers() {
		FakeWorld world = FakeWorld.distinct();

		assertArrayEquals(IDENTITY, ValueReads.matrix(ValueReads.read(this.engine, "modelViewMatrix", world)));
		assertArrayEquals(ValueReads.columnMajor(DrawValues.QUAD_PROJECTION),
				ValueReads.matrix(ValueReads.read(this.engine, "projectionMatrix", world)));
		assertArrayEquals(IDENTITY, ValueReads.matrix(ValueReads.read(this.engine, "textureMatrix", world)));
	}
}
