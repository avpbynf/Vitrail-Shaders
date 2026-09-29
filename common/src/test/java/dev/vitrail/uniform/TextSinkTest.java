package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitrail.glsl.TranslatedUnit;

import java.util.List;
import java.util.Locale;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Holds the decoded dump, {@link TextSink}, to the text a reader of the log is promised: one member
 * a line as {@code name = value}, six significant digits, an array element by index, matrices
 * column by column, and a marker on the zeroes of a name nothing answers.
 * <p>
 * The dump is only worth reading if it is the same walk that fills the buffer, so the whole-block
 * cases run a real {@link UniformBlock} into it.
 */
class TextSinkTest {

	@Test
	void startsEmpty() {
		assertEquals("", new TextSink().text());
	}

	@Test
	void writesOneMemberALineAsNameEqualsValue() {
		TextSink sink = new TextSink();
		sink.member("frameTimeCounter", 1, true).putFloat(12.5F);
		sink.member("frameCounter", 1, true).putInt(-7);

		assertEquals("frameTimeCounter = 12.5000\nframeCounter = -7\n", sink.text());
	}

	@Test
	void writesVectorsAsParenthesisedComponentsInOrder() {
		TextSink sink = new TextSink();
		sink.member("a", 1, true).putVec2(1.0F, 2.5F);
		sink.member("b", 1, true).putVec3(1.0F, 2.5F, -3.0F);
		sink.member("c", 1, true).putVec4(1.0F, 2.5F, -3.0F, 0.0F);
		sink.member("d", 1, true).putIVec2(1, -2);
		sink.member("e", 1, true).putIVec3(1, -2, 3);
		sink.member("f", 1, true).putIVec4(1, -2, 3, 40000);

		assertEquals("""
				a = (1.00000, 2.50000)
				b = (1.00000, 2.50000, -3.00000)
				c = (1.00000, 2.50000, -3.00000, 0.00000)
				d = (1, -2)
				e = (1, -2, 3)
				f = (1, -2, 3, 40000)
				""", sink.text());
	}

	@Test
	void writesAMatrixColumnByColumnSoATransposedOneReadsAsTransposed() {
		// Column-major constructor: the first four numbers are the first column.
		Matrix4f m = new Matrix4f(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
		TextSink sink = new TextSink();
		sink.member("gbufferModelView", 1, true).putMat4(m);

		assertEquals("gbufferModelView = \n"
				+ "       (1.00000, 2.00000, 3.00000, 4.00000)\n"
				+ "       (5.00000, 6.00000, 7.00000, 8.00000)\n"
				+ "       (9.00000, 10.0000, 11.0000, 12.0000)\n"
				+ "       (13.0000, 14.0000, 15.0000, 16.0000)\n", sink.text());

		TextSink transposed = new TextSink();
		transposed.member("gbufferModelView", 1, true).putMat4(m.transpose(new Matrix4f()));
		assertEquals("gbufferModelView = \n"
				+ "       (1.00000, 5.00000, 9.00000, 13.0000)\n"
				+ "       (2.00000, 6.00000, 10.0000, 14.0000)\n"
				+ "       (3.00000, 7.00000, 11.0000, 15.0000)\n"
				+ "       (4.00000, 8.00000, 12.0000, 16.0000)\n", transposed.text());
	}

	@Test
	void writesAMat3AsThreeColumns() {
		TextSink sink = new TextSink();
		sink.member("normal", 1, true).putMat3(new Matrix3f(1, 2, 3, 4, 5, 6, 7, 8, 9));

		assertEquals("normal = \n"
				+ "       (1.00000, 2.00000, 3.00000)\n"
				+ "       (4.00000, 5.00000, 6.00000)\n"
				+ "       (7.00000, 8.00000, 9.00000)\n", sink.text());
	}

	@Test
	void namesAnArrayElementByItsIndexAndRestartsAtEachMember() {
		TextSink sink = new TextSink();
		sink.member("of_TextureMatrix", 3, true);
		sink.putFloat(1.0F).putFloat(2.0F).putFloat(3.0F);
		sink.member("next", 2, true);
		sink.putFloat(4.0F).putFloat(5.0F);

		assertEquals("""
				of_TextureMatrix[0] = 1.00000
				of_TextureMatrix[1] = 2.00000
				of_TextureMatrix[2] = 3.00000
				next[0] = 4.00000
				next[1] = 5.00000
				""", sink.text());
	}

	@Test
	void anArrayOfOneElementIsNamedLikeABareMember() {
		TextSink sink = new TextSink();
		sink.member("single", 1, true).putFloat(1.0F);

		assertEquals("single = 1.00000\n", sink.text());
	}

	@Test
	void marksTheZeroesOfAMemberNothingAnswersAtTheEndOfTheLine() {
		TextSink sink = new TextSink();
		sink.member("farPlane", 1, false).putFloat(0.0F);
		sink.member("dhProjection", 1, false).putMat3(new Matrix3f().zero());
		sink.member("answered", 1, true).putFloat(0.0F);

		assertEquals("farPlane = 0.00000   <- nothing supplies this\n"
				+ "dhProjection = \n"
				+ "       (0.00000, 0.00000, 0.00000)\n"
				+ "       (0.00000, 0.00000, 0.00000)\n"
				+ "       (0.00000, 0.00000, 0.00000)   <- nothing supplies this\n"
				+ "answered = 0.00000\n", sink.text());
	}

	@Test
	void ignoresAlignmentBecauseItPrintsValuesAndNotBytes() {
		TextSink sink = new TextSink();
		sink.member("a", 1, true).align(16).putFloat(1.0F).align(64);

		assertEquals("a = 1.00000\n", sink.text());
	}

	@Test
	void aMemberNeverNamedPrintsUnderNoName() {
		TextSink sink = new TextSink();
		sink.putFloat(1.0F);

		assertEquals(" = 1.00000\n", sink.text());
	}

	@Test
	void formatsSixSignificantDigitsTheWayJavaGDoes() {
		float[] in = { 0.0F, -0.0F, 1.0F, 0.5F, 0.1F, 100.0F, 123456.0F, 65536.0F, 1234567.0F, 0.0001F,
				0.00001F, -1.5F, 9.999995F, 1.0e10F, Float.NaN, Float.POSITIVE_INFINITY,
				Float.NEGATIVE_INFINITY };
		String[] out = { "0.00000", "-0.00000", "1.00000", "0.500000", "0.100000", "100.000", "123456",
				"65536.0", "1.23457e+06", "0.000100000", "1.00000e-05", "-1.50000", "10.0000",
				"1.00000e+10", "NaN", "Infinity", "-Infinity" };

		for (int i = 0; i < in.length; i++) {
			TextSink sink = new TextSink();
			sink.member("v", 1, true).putFloat(in[i]);

			assertEquals("v = " + out[i] + "\n", sink.text(), "for " + in[i]);
		}
	}

	@Test
	void sixDigitsDoNotSeparateTwoFloatsAFewUlpsApart() {
		// What the dump cannot tell apart, so a reader knows what it does not prove: 1.0 and the
		// float after it print alike.
		TextSink one = new TextSink();
		TextSink next = new TextSink();
		one.member("v", 1, true).putFloat(1.0F);
		next.member("v", 1, true).putFloat(Math.nextUp(1.0F));

		assertEquals(one.text(), next.text());
	}

	@Test
	void neverUsesTheLocalesDecimalComma() {
		Locale before = Locale.getDefault();
		try {
			Locale.setDefault(Locale.GERMANY);
			TextSink sink = new TextSink();
			sink.member("v", 1, true).putVec2(1.5F, 2.25F);

			assertEquals("v = (1.50000, 2.25000)\n", sink.text());
		} finally {
			Locale.setDefault(before);
		}
	}

	@Test
	void aFogStructPrintsAsFiveLinesUnderItsOneName() {
		UniformCatalog catalog = UniformCatalog.builder()
				.add("of_Fog", UniformShape.FOG,
						(world, out) -> out.setFog(0.5F, 0.25F, 1.0F, 1.0F, 0.0F, 10.0F, 60.0F, 0.02F))
				.build();
		TextSink sink = new TextSink();

		new UniformBlock(List.of(TranslatedUnit.Uniform.of("of_Fog", "OfFog of_Fog")), catalog)
				.write(sink, new FakeWorld());

		assertEquals("""
				of_Fog = (0.500000, 0.250000, 1.00000, 1.00000)
				of_Fog = 0.00000
				of_Fog = 10.0000
				of_Fog = 60.0000
				of_Fog = 0.0200000
				""", sink.text());
	}

	@Test
	void aMemberThatReachedTheBufferThroughACoercionIsPrintedAfterIt() {
		UniformCatalog catalog = UniformCatalog.builder()
				.add("view", UniformShape.MAT4, (world, out) -> out.set(new Matrix4f(
						1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)))
				.add("flag", UniformShape.INT, (world, out) -> out.set(true))
				.add("ratio", UniformShape.FLOAT, (world, out) -> out.set(2.75F))
				.build();
		TextSink sink = new TextSink();

		new UniformBlock(List.of(
				TranslatedUnit.Uniform.of("view", "mat3 view"),
				TranslatedUnit.Uniform.of("flag", "float flag"),
				TranslatedUnit.Uniform.of("ratio", "ivec2 ratio")), catalog)
				.write(sink, new FakeWorld());

		// The upper left three by three as three vec3 lines (the path a mat4 takes to a mat3), the
		// bool as the float the pack asked for, the float as the truncated integer pair.
		assertEquals("""
				view = (1.00000, 2.00000, 3.00000)
				view = (5.00000, 6.00000, 7.00000)
				view = (9.00000, 10.0000, 11.0000)
				flag = 1.00000
				ratio = (2, 0)
				""", sink.text());
	}

	@Test
	void dumpsAWholeBlockInDeclarationOrderWithArraysAndGaps() {
		UniformCatalog catalog = UniformCatalog.builder()
				.add("frameTimeCounter", UniformShape.FLOAT, (world, out) -> out.set(world.frameTimeCounter()))
				.add("sunPosition", UniformShape.VEC3, (world, out) -> out.set(0.0F, 100.0F, 0.0F))
				.add("of_TextureMatrix", UniformShape.MAT4, (world, out) -> out.set(new Matrix4f().scale(2.0F)))
				.build();
		FakeWorld world = new FakeWorld();
		world.frameTimeCounter = 3.5F;
		TextSink sink = new TextSink();

		new UniformBlock(List.of(
				TranslatedUnit.Uniform.of("frameTimeCounter", "float frameTimeCounter"),
				TranslatedUnit.Uniform.of("velocity", "float velocity"),
				TranslatedUnit.Uniform.of("sunPosition", "vec3 sunPosition"),
				TranslatedUnit.Uniform.of("of_TextureMatrix", "mat4 of_TextureMatrix[2]")), catalog)
				.write(sink, world);

		assertEquals("frameTimeCounter = 3.50000\n"
				+ "velocity = 0.00000   <- nothing supplies this\n"
				+ "sunPosition = (0.00000, 100.000, 0.00000)\n"
				+ "of_TextureMatrix[0] = \n"
				+ "       (2.00000, 0.00000, 0.00000, 0.00000)\n"
				+ "       (0.00000, 2.00000, 0.00000, 0.00000)\n"
				+ "       (0.00000, 0.00000, 2.00000, 0.00000)\n"
				+ "       (0.00000, 0.00000, 0.00000, 1.00000)\n"
				+ "of_TextureMatrix[1] = \n"
				+ "       (2.00000, 0.00000, 0.00000, 0.00000)\n"
				+ "       (0.00000, 2.00000, 0.00000, 0.00000)\n"
				+ "       (0.00000, 0.00000, 2.00000, 0.00000)\n"
				+ "       (0.00000, 0.00000, 0.00000, 1.00000)\n", sink.text());
	}

	@Test
	void knownBug_anArrayOfFogsNumbersItsLinesAndNotItsElements() {
		// The element counter steps once per printed line and a fog struct prints five, so the
		// second struct is labelled [5]. Diagnostic text only; the bytes are unaffected.
		UniformCatalog catalog = UniformCatalog.builder()
				.add("f", UniformShape.FOG, (world, out) -> out.setFog(1, 2, 3, 4, 5, 6, 7, 8))
				.build();
		TextSink sink = new TextSink();

		new UniformBlock(List.of(TranslatedUnit.Uniform.of("f", "OfFog f[2]")), catalog)
				.write(sink, new FakeWorld());

		assertEquals(List.of("f[0]", "f[1]", "f[2]", "f[3]", "f[4]", "f[5]", "f[6]", "f[7]", "f[8]", "f[9]"),
				sink.text().lines().map(line -> line.substring(0, line.indexOf(' '))).toList());
	}
}
