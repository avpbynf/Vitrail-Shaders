package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.IntStream;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Holds the rules {@link UniformCoercion} writes a value by, one declared shape against one value of
 * each rank, down to the bytes.
 * <p>
 * The expected words are worked out by hand from the rules the class documents and are written out
 * in full: a declared rank below the value's truncates, above it fills with zeroes, integer and
 * float convert plainly (an integer to float rounds, a float to integer truncates towards zero and
 * saturates), a mat3 from a mat4 is the upper left three by three and not the first nine numbers, a
 * mat4 from a mat3 keeps the columns and takes the identity's fourth, and the fog struct meets
 * nothing but itself. Each result is read back out of a block laid out by {@link BytesSink}, so
 * the padding a three vector or a matrix column leaves is part of what is compared.
 */
class UniformCoercionTest {

	private static final Val F1 = new Val().set(1.5F);
	private static final Val F2 = new Val().set(1.5F, 2.5F);
	private static final Val F3 = new Val().set(1.5F, 2.5F, 3.5F);
	private static final Val F4 = new Val().set(1.5F, 2.5F, 3.5F, 4.5F);
	private static final Val I1 = new Val().set(7);
	private static final Val I2 = new Val().set(7, 8);
	private static final Val I3 = new Val().set(7, 8, 9);
	private static final Val I4 = new Val().set(7, 8, 9, 10);

	/** Columns 1 2 3 4 | 5 6 7 8 | 9 10 11 12 | 13 14 15 16. */
	private static final Val M4 = new Val().set(new Matrix4f(
			1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16));

	/** Columns 1 2 3 | 4 5 6 | 7 8 9. */
	private static final Val M3 = new Val().set(new Matrix3f(1, 2, 3, 4, 5, 6, 7, 8, 9));

	private static final Val FOG = new Val().setFog(1, 2, 3, 4, 5, 6, 7, 8);

	/** The words a value takes when written as {@code shape}, {@code count} of them from the start. */
	private static int[] written(UniformShape shape, Val value, int count) {
		BytesSink sink = new BytesSink(256);
		UniformCoercion.write(sink, shape, value);

		return IntStream.range(0, count).map(word -> sink.intAt(word * 4)).toArray();
	}

	/** Float words, as the bits a buffer holds. */
	private static int[] f(float... values) {
		int[] bits = new int[values.length];
		for (int i = 0; i < values.length; i++) {
			bits[i] = Float.floatToRawIntBits(values[i]);
		}

		return bits;
	}

	private static void check(UniformShape shape, Val value, int[] expected, String what) {
		assertArrayEquals(expected, written(shape, value, expected.length), what);
	}

	@Test
	void aScalarTakesTheFirstComponentOfWhatItIsGiven() {
		for (Val value : new Val[] { F1, F2, F3, F4 }) {
			check(UniformShape.FLOAT, value, f(1.5F), "float from rank " + value.rank());
		}

		check(UniformShape.FLOAT, I1, f(7.0F), "an integer read as a float");
		check(UniformShape.FLOAT, I4, f(7.0F), "an int vec4 read as a float");
		check(UniformShape.FLOAT, M4, f(1.0F), "the first component of a mat4");
		check(UniformShape.FLOAT, M3, f(1.0F), "the first component of a mat3");
		check(UniformShape.INT, F1, new int[] { 1 }, "1.5 as an int");
		check(UniformShape.INT, F4, new int[] { 1 }, "a float vec4 as an int");
		check(UniformShape.INT, I2, new int[] { 7 }, "an int vec2 as an int");
		check(UniformShape.INT, M4, new int[] { 1 }, "a mat4 as an int");
	}

	@Test
	void aVectorTruncatesBelowTheValuesRankAndFillsWithZeroesAbove() {
		check(UniformShape.VEC2, F1, f(1.5F, 0.0F), "vec2 from a scalar");
		check(UniformShape.VEC2, F2, f(1.5F, 2.5F), "vec2 from a vec2");
		check(UniformShape.VEC2, F4, f(1.5F, 2.5F), "vec2 from a vec4");
		check(UniformShape.VEC3, F1, f(1.5F, 0.0F, 0.0F), "vec3 from a scalar");
		check(UniformShape.VEC3, F2, f(1.5F, 2.5F, 0.0F), "vec3 from a vec2");
		check(UniformShape.VEC3, F3, f(1.5F, 2.5F, 3.5F), "vec3 from a vec3");
		check(UniformShape.VEC3, F4, f(1.5F, 2.5F, 3.5F), "vec3 from a vec4");
		check(UniformShape.VEC4, F1, f(1.5F, 0.0F, 0.0F, 0.0F), "vec4 from a scalar");
		check(UniformShape.VEC4, F2, f(1.5F, 2.5F, 0.0F, 0.0F), "vec4 from a vec2");
		check(UniformShape.VEC4, F3, f(1.5F, 2.5F, 3.5F, 0.0F), "vec4 from a vec3");
		check(UniformShape.VEC4, F4, f(1.5F, 2.5F, 3.5F, 4.5F), "vec4 from a vec4");
	}

	@Test
	void anIntegerVectorTruncatesAFloatOneAndFillsWithZeroes() {
		check(UniformShape.IVEC2, F1, new int[] { 1, 0 }, "ivec2 from a float scalar");
		check(UniformShape.IVEC2, F2, new int[] { 1, 2 }, "ivec2 from a float vec2");
		check(UniformShape.IVEC2, F4, new int[] { 1, 2 }, "ivec2 from a float vec4");
		check(UniformShape.IVEC2, I3, new int[] { 7, 8 }, "ivec2 from an int vec3");
		check(UniformShape.IVEC3, F1, new int[] { 1, 0, 0 }, "ivec3 from a float scalar");
		check(UniformShape.IVEC3, F3, new int[] { 1, 2, 3 }, "ivec3 from a float vec3");
		check(UniformShape.IVEC3, I2, new int[] { 7, 8, 0 }, "ivec3 from an int vec2");
		check(UniformShape.IVEC4, F4, new int[] { 1, 2, 3, 4 }, "ivec4 from a float vec4");
		check(UniformShape.IVEC4, I1, new int[] { 7, 0, 0, 0 }, "ivec4 from an int scalar");
		check(UniformShape.IVEC4, I4, new int[] { 7, 8, 9, 10 }, "ivec4 from an int vec4");
	}

	@Test
	void anIntegerValueDeclaredAsFloatsIsConvertedComponentByComponent() {
		check(UniformShape.VEC2, I2, f(7.0F, 8.0F), "vec2 from an ivec2");
		check(UniformShape.VEC3, I1, f(7.0F, 0.0F, 0.0F), "vec3 from an int");
		check(UniformShape.VEC4, I4, f(7.0F, 8.0F, 9.0F, 10.0F), "vec4 from an ivec4");
	}

	@Test
	void aVectorReadOutOfAMatrixTakesTheFirstColumnFirst() {
		check(UniformShape.VEC2, M4, f(1.0F, 2.0F), "vec2 from a mat4");
		check(UniformShape.VEC3, M4, f(1.0F, 2.0F, 3.0F), "vec3 from a mat4");
		check(UniformShape.VEC4, M4, f(1.0F, 2.0F, 3.0F, 4.0F), "vec4 from a mat4");
		check(UniformShape.VEC4, M3, f(1.0F, 2.0F, 3.0F, 4.0F), "vec4 from a mat3 runs into the second column");
		check(UniformShape.IVEC4, M4, new int[] { 1, 2, 3, 4 }, "ivec4 from a mat4");
	}

	@Test
	void aMat3TakesTheUpperLeftThreeByThreeOfAMat4NotItsFirstNineNumbers() {
		// Columns of three, each padded to sixteen: (1 2 3 _) (5 6 7 _) (9 10 11 _). The first nine
		// numbers of the mat4 would put 4 into the first column's neighbour and mix a translation in.
		check(UniformShape.MAT3, M4, f(1, 2, 3, 0, 5, 6, 7, 0, 9, 10, 11, 0), "mat3 from a mat4");
		check(UniformShape.MAT3, M3, f(1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9, 0), "mat3 from a mat3");
	}

	@Test
	void aMat3FromAFlatValueTakesItsComponentsInOrderAndZeroFillsTheRest() {
		check(UniformShape.MAT3, F4, f(1.5F, 2.5F, 3.5F, 0, 4.5F, 0, 0, 0, 0, 0, 0, 0), "mat3 from a vec4");
		check(UniformShape.MAT3, F1, f(1.5F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), "mat3 from a scalar");
		check(UniformShape.MAT3, I3, f(7, 8, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0), "mat3 from an ivec3");
	}

	@Test
	void aMat4FromAMat3KeepsTheColumnsAndTakesTheIdentitysFourth() {
		check(UniformShape.MAT4, M3,
				f(1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9, 0, 0, 0, 0, 1), "mat4 from a mat3");
		check(UniformShape.MAT4, M4,
				f(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), "mat4 from a mat4");
	}

	@Test
	void aMat4FromAFlatValueTakesItsComponentsInOrderAndZeroFillsTheRest() {
		check(UniformShape.MAT4, F4, f(1.5F, 2.5F, 3.5F, 4.5F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), "mat4 from a vec4");
		check(UniformShape.MAT4, F1, f(1.5F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), "mat4 from a scalar");
		check(UniformShape.MAT4, F3, f(1.5F, 2.5F, 3.5F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), "mat4 from a vec3");
		check(UniformShape.MAT4, I2, f(7, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), "mat4 from an ivec2");
	}

	@Test
	void theFogStructMeetsNothingButItself() {
		check(UniformShape.FOG, FOG, f(1, 2, 3, 4, 5, 6, 7, 8), "fog from fog");

		// Rank eight belongs to the fog struct alone, so anything else declared as fog is zeroes...
		int[] zeroes = new int[8];
		check(UniformShape.FOG, F4, zeroes, "fog from a vec4");
		check(UniformShape.FOG, M3, zeroes, "fog from a mat3");
		check(UniformShape.FOG, M4, zeroes, "fog from a mat4");
		check(UniformShape.FOG, I4, zeroes, "fog from an ivec4");

		// ...and so is fog declared as anything else, not the first few numbers of it.
		for (UniformShape shape : UniformShape.values()) {
			if (shape == UniformShape.FOG) {
				continue;
			}

			check(shape, FOG, new int[16], shape + " from fog");
		}
	}

	@Test
	void aRefusedFogStillCostsTheStructsThirtyTwoBytes() {
		Std140Counter counter = new Std140Counter();
		UniformCoercion.write(counter, UniformShape.FOG, F4);

		assertEquals(32, counter.size());

		counter = new Std140Counter();
		UniformCoercion.write(counter, UniformShape.MAT4, FOG);
		assertEquals(64, counter.size());
	}

	@Test
	void aFloatToIntegerTruncatesTowardZeroAndNeverRounds() {
		float[] in = { 0.0F, 0.5F, 0.999F, 1.0F, 1.5F, 2.5F, -0.5F, -0.999F, -1.5F, -2.5F, 1.9999999F, -1.9999999F };
		int[] out = { 0, 0, 0, 1, 1, 2, 0, 0, -1, -2, 1, -1 };

		for (int i = 0; i < in.length; i++) {
			check(UniformShape.INT, new Val().set(in[i]), new int[] { out[i] }, "int from " + in[i]);
		}
	}

	@Test
	void aFloatToIntegerSaturatesAtTheEndsAndSendsNaNToNought() {
		check(UniformShape.INT, new Val().set(Float.NaN), new int[] { 0 }, "NaN");
		check(UniformShape.INT, new Val().set(Float.POSITIVE_INFINITY), new int[] { Integer.MAX_VALUE }, "+inf");
		check(UniformShape.INT, new Val().set(Float.NEGATIVE_INFINITY), new int[] { Integer.MIN_VALUE }, "-inf");
		check(UniformShape.INT, new Val().set(1.0e10F), new int[] { Integer.MAX_VALUE }, "1e10");
		check(UniformShape.INT, new Val().set(-1.0e10F), new int[] { Integer.MIN_VALUE }, "-1e10");
		check(UniformShape.INT, new Val().set(2_147_483_648.0F), new int[] { Integer.MAX_VALUE }, "2^31");
		check(UniformShape.INT, new Val().set(-2_147_483_648.0F), new int[] { Integer.MIN_VALUE }, "-2^31");
		check(UniformShape.INT, new Val().set(2_147_483_520.0F), new int[] { 2_147_483_520 }, "2^31 - 128");
		check(UniformShape.INT, new Val().set(Float.MIN_VALUE), new int[] { 0 }, "the smallest denormal");
		check(UniformShape.INT, new Val().set(Float.MAX_VALUE), new int[] { Integer.MAX_VALUE }, "the largest float");
	}

	@Test
	void negativeZeroIsIntegerZeroAsAnIntegerAndStaysNegativeZeroAsAFloat() {
		check(UniformShape.INT, new Val().set(-0.0F), new int[] { 0 }, "-0.0 as an int");
		check(UniformShape.FLOAT, new Val().set(-0.0F), new int[] { 0x80000000 }, "-0.0 as a float");
		check(UniformShape.VEC2, new Val().set(-0.0F, 0.0F), new int[] { 0x80000000, 0 }, "-0.0 in a vec2");
	}

	@Test
	void aFloatDeclaredAsFloatKeepsNaNInfinityAndTheDenormalsBitForBit() {
		float[] specials = { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
				Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE, Float.MIN_NORMAL, 1.0e-38F };

		for (float special : specials) {
			check(UniformShape.FLOAT, new Val().set(special), f(special), "float " + special);
		}
	}

	@Test
	void anIntegerToFloatRoundsToNearestEven() {
		check(UniformShape.FLOAT, new Val().set(16_777_217), f(16_777_216.0F), "2^24 + 1 ties to even, down");
		check(UniformShape.FLOAT, new Val().set(16_777_219), f(16_777_220.0F), "2^24 + 3 ties to even, up");
		check(UniformShape.FLOAT, new Val().set(Integer.MAX_VALUE), f(2_147_483_648.0F), "int max");
		check(UniformShape.FLOAT, new Val().set(Integer.MIN_VALUE), f(-2_147_483_648.0F), "int min");
		check(UniformShape.FLOAT, new Val().set(-7), f(-7.0F), "a negative integer");
	}

	@Test
	void anIntegerDeclaredAsIntegerIsExactBeyondWhatAFloatHolds() {
		check(UniformShape.INT, new Val().set(16_777_217), new int[] { 16_777_217 }, "2^24 + 1");
		check(UniformShape.IVEC2, new Val().set(Integer.MAX_VALUE, Integer.MIN_VALUE),
				new int[] { Integer.MAX_VALUE, Integer.MIN_VALUE }, "the ends");
	}

	@Test
	void aBoolIsAnIntegerAndTheFlagsAreServedWhicheverTypeThePackDeclared() {
		// The same flag declared bool (an int), int, and float: each gets the number in its own type.
		Val flag = new Val().set(true);

		check(UniformShape.of("bool"), flag, new int[] { 1 }, "bool");
		check(UniformShape.of("int"), flag, new int[] { 1 }, "int");
		check(UniformShape.of("float"), flag, f(1.0F), "float");
		check(UniformShape.of("bool"), new Val().set(false), new int[] { 0 }, "bool false");
		check(UniformShape.of("float"), new Val().set(false), f(0.0F), "float false");
	}

	@Test
	void aBoolDeclaredOverAFloatIsTheTruncatedIntegerAndNotZeroOrOne() {
		// Not normalised: a fraction is nought and two stays two. The flags the engine registers are
		// all integers, so this is only what a pack that declares a float name as a bool meets.
		check(UniformShape.of("bool"), new Val().set(0.5F), new int[] { 0 }, "0.5 as a bool");
		check(UniformShape.of("bool"), new Val().set(2.0F), new int[] { 2 }, "2.0 as a bool");
		check(UniformShape.of("bool"), new Val().set(-1.0F), new int[] { -1 }, "-1.0 as a bool");
	}

	@Test
	void everyWriteLandsAtTheOffsetTheShapeAlignsOn() {
		// float a; then each shape: the value must start where std140 says, not straight behind a.
		BytesSink sink = new BytesSink(256);
		sink.putFloat(9.0F);
		UniformCoercion.write(sink, UniformShape.VEC3, F3);
		sink.putFloat(8.0F);

		assertEquals(9.0F, sink.floatAt(0));
		assertEquals(1.5F, sink.floatAt(16), "a vec3 starts on sixteen");
		assertEquals(3.5F, sink.floatAt(24));
		assertEquals(8.0F, sink.floatAt(28), "and the float behind it starts on twenty eight");
	}
}
