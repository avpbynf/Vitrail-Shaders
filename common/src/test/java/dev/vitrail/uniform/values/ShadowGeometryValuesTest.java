package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vitrail.uniform.FakeWorld;
import dev.vitrail.uniform.UniformCatalog;

import java.util.Arrays;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Holds the pass drawn from the light, {@link ShadowGeometryValues}, to reading the DRAWN pair of
 * this frame where the published shadow matrices are the previous frame's, and to the product and
 * the normal matrix it composes.
 * <p>
 * A world with a number of its own for every matrix tells the two pairs apart, since a shadow pass
 * that read the published pair would draw the map one frame late and the pack's
 * {@code shadowModelViewInverse * shadowProjectionInverse * ftransform()} would stop collapsing.
 */
class ShadowGeometryValuesTest {

	private final UniformCatalog shadow = UniformCatalog.shadowGeometry();
	private final FakeWorld world = FakeWorld.distinct();

	private float[] read(UniformCatalog catalog, String name) {
		return ValueReads.matrix(ValueReads.read(catalog, name, this.world));
	}

	@Test
	void theFixedFunctionMatricesAreTheDrawnPairOfThisFrame() {
		assertArrayEquals(ValueReads.columnMajor(this.world.drawnShadowModelView),
				read(this.shadow, "of_ModelViewMatrix"));
		assertArrayEquals(ValueReads.columnMajor(this.world.drawnShadowModelViewInverse),
				read(this.shadow, "of_ModelViewMatrixInverse"));
		assertArrayEquals(ValueReads.columnMajor(this.world.drawnShadowProjection),
				read(this.shadow, "of_ProjectionMatrix"));
		assertArrayEquals(ValueReads.columnMajor(this.world.drawnShadowProjectionInverse),
				read(this.shadow, "of_ProjectionMatrixInverse"));
	}

	@Test
	void theCoreSpellingsFollowThem() {
		assertArrayEquals(read(this.shadow, "of_ModelViewMatrix"), read(this.shadow, "modelViewMatrix"));
		assertArrayEquals(read(this.shadow, "of_ProjectionMatrix"), read(this.shadow, "projectionMatrix"));
	}

	@Test
	void theFourShadowNamesAreTheDrawnPairHereAndThePublishedPairEverywhereElse() {
		UniformCatalog engine = UniformCatalog.engine();
		UniformCatalog geometry = UniformCatalog.geometry();

		String[] names = { "shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse" };
		Matrix4f[] drawn = { this.world.drawnShadowModelView, this.world.drawnShadowModelViewInverse,
				this.world.drawnShadowProjection, this.world.drawnShadowProjectionInverse };
		Matrix4f[] published = { this.world.shadowModelView, this.world.shadowModelViewInverse,
				this.world.shadowProjection, this.world.shadowProjectionInverse };

		for (int i = 0; i < names.length; i++) {
			assertArrayEquals(ValueReads.columnMajor(drawn[i]), read(this.shadow, names[i]),
					names[i] + " from the light");
			assertArrayEquals(ValueReads.columnMajor(published[i]), read(engine, names[i]),
					names[i] + " from a quad");
			assertArrayEquals(ValueReads.columnMajor(published[i]), read(geometry, names[i]),
					names[i] + " over the world");
			assertFalse(Arrays.equals(read(this.shadow, names[i]), read(engine, names[i])),
					"the two pairs differ in this world, so the difference is visible");
		}
	}

	@Test
	void theModelViewProjectionIsTheDrawnProjectionOnTheLeftOfTheDrawnModelView() {
		this.world.drawnShadowProjection.identity().scale(0.5F, 0.25F, 0.125F).translate(1.0F, 2.0F, 3.0F);
		this.world.drawnShadowModelView.identity().rotateX(0.7F).translate(5.0F, -6.0F, 7.0F);

		double[] expected = ValueReads.product(this.world.drawnShadowProjection, this.world.drawnShadowModelView);
		double[] neighbour = ValueReads.product(this.world.drawnShadowModelView, this.world.drawnShadowProjection);

		float[] actual = read(this.shadow, "of_ModelViewProjectionMatrix");
		boolean isNeighbour = true;
		for (int i = 0; i < 16; i++) {
			assertEquals(expected[i], actual[i], 1.0e-5 * Math.max(1.0, Math.abs(expected[i])), "entry " + i);
			isNeighbour &= Math.abs(neighbour[i] - actual[i]) < 1.0e-5;
		}

		assertFalse(isNeighbour, "and it is not the product the other way round");
	}

	@Test
	void theNormalMatrixIsTheUpperLeftThreeByThreeAsItStandsAndNotAnInverseTranspose() {
		// A product of rotations: its inverse transpose is itself, and the translation of the snap to
		// the grid never enters a three by three.
		this.world.drawnShadowModelView.identity().rotateY(0.9F).rotateX(-0.4F).translate(3.0F, 4.0F, 5.0F);
		Matrix4f rotation = this.world.drawnShadowModelView;
		float[] actual = ValueReads.floats(ValueReads.read(this.shadow, "of_NormalMatrix", this.world), 9);

		assertArrayEquals(new float[] { rotation.m00(), rotation.m01(), rotation.m02(),
				rotation.m10(), rotation.m11(), rotation.m12(),
				rotation.m20(), rotation.m21(), rotation.m22() }, actual);
	}

	@Test
	void aScaleInTheDrawnModelViewIsNotUndoneBySuchANormalMatrix() {
		// What the class documents would break it, pinned as it stands: a scale of two comes out as two
		// where the geometry pass's inverse transpose would say a half. Shadow model views do not carry
		// one; if one ever does, shadow pass normals are wrong by that scale.
		this.world.drawnShadowModelView.identity().scale(2.0F);
		this.world.passModelView.identity().scale(2.0F);

		assertArrayEquals(new float[] { 2, 0, 0, 0, 2, 0, 0, 0, 2 },
				ValueReads.floats(ValueReads.read(this.shadow, "of_NormalMatrix", this.world), 9));
		assertArrayEquals(new float[] { 0.5F, 0, 0, 0, 0.5F, 0, 0, 0, 0.5F },
				ValueReads.floats(ValueReads.read(UniformCatalog.geometry(), "of_NormalMatrix", this.world), 9));
	}

	@Test
	void theTextureMatricesAreNotAnsweredAgainBecauseALightMapCoordinateIsTheSameFromEitherEnd() {
		assertEquals(UniformCatalog.geometry().source("of_TextureMatrix"), this.shadow.source("of_TextureMatrix"));
	}
}
