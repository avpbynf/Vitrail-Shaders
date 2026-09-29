package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.LegacyGlsl;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.uniform.BytesSink;
import dev.vitrail.uniform.FakeWorld;
import dev.vitrail.uniform.UniformBlock;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.Val;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Holds the fixed function state of a pass drawn over the world, {@link GeometryValues}, to
 * matrices composed again in double precision: the model view projection is the pass's projection
 * on the left of the pass's model view, the normal matrix is the inverse transpose, and the eight
 * texture matrices are six identities and the light map's twice.
 * <p>
 * Everything a pass sets beside its block write (the model view, the projection) is moved
 * independently in the sequences below, because the answers are remembered by their inputs and a
 * memory that misses one hands a pass the previous pass's matrix.
 */
class GeometryValuesTest {

	private final UniformCatalog geometry = UniformCatalog.geometry();

	/** A rotation about all three axes followed by a translation and, if given, a scale on the way in. */
	private static Matrix4f transform(Random random, boolean scaled) {
		Matrix4f m = new Matrix4f()
				.translate(random.nextFloat(-50.0F, 50.0F), random.nextFloat(-50.0F, 50.0F),
						random.nextFloat(-50.0F, 50.0F))
				.rotateX(random.nextFloat(-3.0F, 3.0F))
				.rotateY(random.nextFloat(-3.0F, 3.0F))
				.rotateZ(random.nextFloat(-3.0F, 3.0F));
		if (scaled) {
			m.scale(random.nextFloat(0.5F, 3.0F), random.nextFloat(0.5F, 3.0F), random.nextFloat(0.5F, 3.0F));
		}

		return m;
	}

	private static void assertMatrix(double[] expected, Val actual, double tolerance, String what) {
		assertEquals(16, actual.rank(), what);
		for (int i = 0; i < 16; i++) {
			assertEquals(expected[i], actual.f(i), tolerance * Math.max(1.0, Math.abs(expected[i])),
					what + " entry " + i);
		}
	}

	// the composed matrices

	@Test
	void theModelViewProjectionIsTheProjectionOnTheLeftOfTheModelView() {
		Random random = new Random(21);
		for (int i = 0; i < 50; i++) {
			FakeWorld world = new FakeWorld();
			world.passProjection.set(transform(random, true));
			world.passModelView.set(transform(random, false));

			double[] expected = ValueReads.product(world.passProjection, world.passModelView);
			double[] neighbour = ValueReads.product(world.passModelView, world.passProjection);

			Val actual = ValueReads.read(this.geometry, "of_ModelViewProjectionMatrix", world);
			assertMatrix(expected, actual, 1.0e-4, "sample " + i);
			assertFalse(closeTo(neighbour, actual), "and it is not the product the other way round");
		}
	}

	private static boolean closeTo(double[] expected, Val actual) {
		for (int i = 0; i < 16; i++) {
			if (Math.abs(expected[i] - actual.f(i)) > 1.0e-3 * Math.max(1.0, Math.abs(expected[i]))) {
				return false;
			}
		}

		return true;
	}

	@Test
	void theModelViewProjectionFollowsWhicheverOfTheTwoMovesAlone() {
		// The sky, the hand and the entities each set a model view or a projection of their own
		// inside one frame, and the product is remembered by the pair it was built from.
		Random random = new Random(22);
		FakeWorld world = new FakeWorld();
		world.passProjection.set(transform(random, true));
		world.passModelView.set(transform(random, false));

		for (int step = 0; step < 200; step++) {
			switch (random.nextInt(3)) {
				case 0 -> world.passProjection.set(transform(random, true));
				case 1 -> world.passModelView.set(transform(random, false));
				default -> {
					// Neither moves: asked again, the answer must stand.
				}
			}

			assertMatrix(ValueReads.product(world.passProjection, world.passModelView),
					ValueReads.read(this.geometry, "of_ModelViewProjectionMatrix", world), 1.0e-4, "step " + step);
		}
	}

	@Test
	void theNormalMatrixIsTheInverseTransposeOfTheUpperLeftThreeByThree() {
		Random random = new Random(23);
		for (int i = 0; i < 50; i++) {
			// Half of them carry a scale, which is where an inverse transpose parts from the matrix
			// itself: the rotation alone would pass a transpose of the wrong kind.
			FakeWorld world = new FakeWorld();
			world.passModelView.set(transform(random, i % 2 == 1));

			double[][] expected = inverseTranspose(world.passModelView);
			Val actual = ValueReads.read(this.geometry, "of_NormalMatrix", world);

			assertEquals(9, actual.rank());
			for (int column = 0; column < 3; column++) {
				for (int row = 0; row < 3; row++) {
					assertEquals(expected[row][column], actual.f(column * 3 + row), 1.0e-4,
							"sample " + i + " " + column + row);
				}
			}
		}
	}

	/** The cofactor matrix over the determinant, which is the inverse transpose without inverting. */
	private static double[][] inverseTranspose(Matrix4f m) {
		double[][] a = new double[3][3];
		a[0][0] = m.m00();
		a[1][0] = m.m01();
		a[2][0] = m.m02();
		a[0][1] = m.m10();
		a[1][1] = m.m11();
		a[2][1] = m.m12();
		a[0][2] = m.m20();
		a[1][2] = m.m21();
		a[2][2] = m.m22();

		double[][] cofactor = new double[3][3];
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				int r0 = (row + 1) % 3;
				int r1 = (row + 2) % 3;
				int c0 = (column + 1) % 3;
				int c1 = (column + 2) % 3;
				cofactor[row][column] = a[r0][c0] * a[r1][c1] - a[r0][c1] * a[r1][c0];
			}
		}

		double determinant = a[0][0] * cofactor[0][0] + a[0][1] * cofactor[0][1] + a[0][2] * cofactor[0][2];
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				cofactor[row][column] /= determinant;
			}
		}

		return cofactor;
	}

	@Test
	void theNormalMatrixOfARotationIsTheRotationAndOfAScaleIsTheReciprocalScale() {
		FakeWorld world = new FakeWorld();
		world.passModelView.identity().scale(2.0F, 4.0F, 5.0F).translate(7.0F, 8.0F, 9.0F);

		Val actual = ValueReads.read(this.geometry, "of_NormalMatrix", world);

		assertArrayEquals(new float[] { 0.5F, 0, 0, 0, 0.25F, 0, 0, 0, 0.2F }, ValueReads.floats(actual, 9), 1.0e-6F);
	}

	@Test
	void theNormalMatrixFollowsTheModelViewAsItMoves() {
		Random random = new Random(24);
		FakeWorld world = new FakeWorld();

		for (int step = 0; step < 100; step++) {
			if (step % 3 != 0) {
				world.passModelView.set(transform(random, true));
			}

			double[][] expected = inverseTranspose(world.passModelView);
			Val actual = ValueReads.read(this.geometry, "of_NormalMatrix", world);
			for (int column = 0; column < 3; column++) {
				for (int row = 0; row < 3; row++) {
					assertEquals(expected[row][column], actual.f(column * 3 + row), 1.0e-4, "step " + step);
				}
			}
		}
	}

	@Test
	void thePassModelViewAndProjectionAndTheirInversesAreThePassesAndNotTheFrames() {
		FakeWorld world = FakeWorld.distinct();

		assertArrayEquals(ValueReads.columnMajor(world.passModelView),
				ValueReads.matrix(ValueReads.read(this.geometry, "of_ModelViewMatrix", world)));
		assertArrayEquals(ValueReads.columnMajor(world.passModelViewInverse),
				ValueReads.matrix(ValueReads.read(this.geometry, "of_ModelViewMatrixInverse", world)));
		assertArrayEquals(ValueReads.columnMajor(world.passProjection),
				ValueReads.matrix(ValueReads.read(this.geometry, "of_ProjectionMatrix", world)));
		assertArrayEquals(ValueReads.columnMajor(world.passProjectionInverse),
				ValueReads.matrix(ValueReads.read(this.geometry, "of_ProjectionMatrixInverse", world)));

		// And the frame's own pair is answered by the names that mean it, whichever the pass draws.
		assertArrayEquals(ValueReads.columnMajor(world.gbufferModelView),
				ValueReads.matrix(ValueReads.read(this.geometry, "gbufferModelView", world)));
		assertArrayEquals(ValueReads.columnMajor(world.gbufferProjection),
				ValueReads.matrix(ValueReads.read(this.geometry, "gbufferProjection", world)));
	}

	@Test
	void theBobTheGlintAndTheColourAreTheWorldsOwn() {
		FakeWorld world = FakeWorld.distinct();

		assertArrayEquals(ValueReads.columnMajor(world.cameraBob),
				ValueReads.matrix(ValueReads.read(this.geometry, LegacyGlsl.CAMERA_BOB, world)));
		assertEquals(world.glintAlpha, ValueReads.read(this.geometry, LegacyGlsl.GLINT_ALPHA, world).f(0));
		assertArrayEquals(
				new float[] { world.passColour.x, world.passColour.y, world.passColour.z, world.passColour.w },
				ValueReads.floats(ValueReads.read(this.geometry, "of_PassColour", world), 4));
	}

	@Test
	void theCenterDepthSmoothIsANoughtHereBecauseOnlyAFullScreenPassHasIt() {
		FakeWorld world = FakeWorld.distinct();

		assertEquals(0.0F, ValueReads.read(this.geometry, "centerDepthSmooth", world).f(0));
	}

	// the sprite shrink

	private static double shrink(double atlas) {
		return 3.0517578e-5 - 1.0 / (atlas * 256.0);
	}

	@Test
	void theTexCoordShrinkIsOneUnitOfTheFifteenBitEncodingLessAnEighthOfAsSubTexelPerAxis() {
		FakeWorld world = new FakeWorld();
		world.atlasWidth = 1024;
		world.atlasHeight = 512;

		float[] actual = ValueReads.floats(ValueReads.read(this.geometry, "of_TexShrink", world), 2);

		// 3.0517578e-5 less 1 / atlas / 256, each axis from its own atlas side and in that order.
		assertEquals(shrink(1024.0), actual[0], 1.0e-10);
		assertEquals(shrink(512.0), actual[1], 1.0e-10);
		assertTrue(actual[0] > actual[1], "the wider axis loses less of a texel: " + actual[0] + " " + actual[1]);
	}

	@Test
	void anAtlasOfSizeNoughtIsTakenForSizeOneAndNeverDividesByZero() {
		FakeWorld world = new FakeWorld();
		world.atlasWidth = 0;
		world.atlasHeight = -5;

		float[] actual = ValueReads.floats(ValueReads.read(this.geometry, "of_TexShrink", world), 2);

		assertEquals(shrink(1.0), actual[0], 1.0e-8);
		assertEquals(shrink(1.0), actual[1], 1.0e-8);
	}

	// the texture matrices

	/** The block of a program that reads the array, written through the real walk into real bytes. */
	private float[][] textureMatrices(UniformCatalog catalog) {
		List<TranslatedUnit.Uniform> members = List.of(
				TranslatedUnit.Uniform.of("of_TextureMatrix", "mat4 of_TextureMatrix[8]"));
		UniformBlock block = new UniformBlock(members, catalog);
		BytesSink sink = new BytesSink(block.size());
		block.write(sink, new FakeWorld());

		assertEquals(512, block.size());
		float[][] matrices = new float[8][16];
		for (int unit = 0; unit < 8; unit++) {
			for (int i = 0; i < 16; i++) {
				matrices[unit][i] = sink.floatAt(unit * 64 + i * 4);
			}
		}

		return matrices;
	}

	private static final float[] IDENTITY = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

	/** Scale 1/256 on the three axes and a translation of 1/32: both exact in a float. */
	private static final float[] LIGHT_MAP = { 0.00390625F, 0, 0, 0, 0, 0.00390625F, 0, 0, 0, 0, 0.00390625F, 0,
			0.03125F, 0.03125F, 0.03125F, 1 };

	@Test
	void unitsOneAndTwoAreTheLightMapsMatrixAndTheOtherSixAreTheIdentity() {
		float[][] matrices = textureMatrices(this.geometry);

		for (int unit = 0; unit < 8; unit++) {
			assertArrayEquals(unit == 1 || unit == 2 ? LIGHT_MAP : IDENTITY, matrices[unit], "unit " + unit);
		}
	}

	@Test
	void aFullScreenPassHasEightIdentitiesBecauseAQuadCarriesNoLightMap() {
		float[][] matrices = textureMatrices(UniformCatalog.engine());

		for (int unit = 0; unit < 8; unit++) {
			assertArrayEquals(IDENTITY, matrices[unit], "unit " + unit);
		}
	}

	@Test
	void theShadowPassInheritsTheTextureMatricesUnchanged() {
		assertTrue(Arrays.deepEquals(textureMatrices(this.geometry), textureMatrices(UniformCatalog.shadowGeometry())));
	}

	@Test
	void theLightMapMatrixPutsEachOfTheSixteenLevelsOnTheCentreOfItsTexel() {
		// The vertex carries a level as 16 * i, and the sixteen by sixteen texture wants (i + 1/2) / 16:
		// the centre of texel i. Level 15 lands at 0.96875 and not on the edge, where LINEAR filtering
		// would mix it with a neighbour that is not there.
		float[] map = textureMatrices(this.geometry)[1];

		for (int level = 0; level < 16; level++) {
			double x = map[0] * (16.0 * level) + map[12];
			double y = map[5] * (16.0 * level) + map[13];

			assertEquals((level + 0.5) / 16.0, x, 1.0e-9, "level " + level);
			assertEquals((level + 0.5) / 16.0, y, 1.0e-9, "level " + level);
		}

		assertEquals(0.96875, map[0] * 240.0 + map[12], 1.0e-9);
	}

	@Test
	void theIdentityAsTheLightMapWouldSendEverySurfaceToTheClampedCorner() {
		// The idiom every pack writes is (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy. Through the
		// identity a level of 240 stays 240 and the sampler clamps it to the far corner: full block
		// light and full sky light everywhere.
		float[] identity = textureMatrices(UniformCatalog.engine())[1];
		float[] map = textureMatrices(this.geometry)[1];

		assertEquals(240.0, identity[0] * 240.0 + identity[12], 1.0e-9);
		assertTrue(map[0] * 240.0 + map[12] < 1.0);
	}

	@Test
	void aBareReadOfTheMatrixIsUnitNoughtAndTheCoreSpellingIsThatSameUnit() {
		FakeWorld world = new FakeWorld();

		// The short form is element nought, the rule the interface documents, and the core spelling
		// names one matrix: the identity, since a bare member asks for element nought.
		assertArrayEquals(IDENTITY, ValueReads.matrix(ValueReads.read(this.geometry, "of_TextureMatrix", world)));
		assertArrayEquals(IDENTITY, ValueReads.matrix(ValueReads.read(this.geometry, "textureMatrix", world)));

		Val val = new Val();
		this.geometry.source("of_TextureMatrix").read(world, val);
		assertArrayEquals(IDENTITY, ValueReads.matrix(val), "the two argument form");

		assertArrayEquals(LIGHT_MAP, ValueReads.matrix(ValueReads.read(this.geometry, "of_TextureMatrix", world, 1)));
		assertArrayEquals(LIGHT_MAP, ValueReads.matrix(ValueReads.read(this.geometry, "of_TextureMatrix", world, 2)));
		assertArrayEquals(IDENTITY, ValueReads.matrix(ValueReads.read(this.geometry, "of_TextureMatrix", world, 3)));
	}
}
