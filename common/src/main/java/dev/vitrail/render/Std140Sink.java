package dev.vitrail.render;

import dev.vitrail.uniform.UniformSink;

import com.mojang.blaze3d.buffers.Std140Builder;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

/**
 * Puts a block member's bytes into the game's own std140 builder.
 * <p>
 * This is the whole of the contact between the values and the graphics API, and it is deliberately
 * this thin: the layout rules live in the builder, which the game uses for its own blocks, so there
 * is no second implementation of std140 to keep in step with the driver's.
 * <p>
 * The builder has no {@code putMat3}, and neither has its size calculator, so a mat3 is composed
 * from three vec3 puts. That is not a workaround, it is what a mat3 is in std140: three columns
 * each padded out to sixteen bytes.
 */
final class Std140Sink implements UniformSink {

	private final Std140Builder builder;

	Std140Sink(Std140Builder builder) {
		this.builder = builder;
	}

	@Override
	public UniformSink align(int alignment) {
		this.builder.align(alignment);

		return this;
	}

	@Override
	public UniformSink putFloat(float v) {
		this.builder.putFloat(v);

		return this;
	}

	@Override
	public UniformSink putInt(int v) {
		this.builder.putInt(v);

		return this;
	}

	@Override
	public UniformSink putVec2(float x, float y) {
		this.builder.putVec2(x, y);

		return this;
	}

	/**
	 * Aligned on sixteen and consuming TWELVE, which is not what {@code Std140Builder.putVec3}
	 * does: that one skips a fourth float on the way out, so a member written after it lands four
	 * bytes past where the shader reads it. Verified on the compiler: {@code vec3 a; float b;} puts
	 * b at twelve.
	 * <p>
	 * The builder's own padded form is the right one for a matrix column and for an array element,
	 * where the stride really is sixteen, and {@link #putMat3} still uses it.
	 */
	@Override
	public UniformSink putVec3(float x, float y, float z) {
		this.builder.align(16);
		this.builder.putFloat(x).putFloat(y).putFloat(z);

		return this;
	}

	@Override
	public UniformSink putVec4(float x, float y, float z, float w) {
		this.builder.putVec4(x, y, z, w);

		return this;
	}

	@Override
	public UniformSink putIVec2(int x, int y) {
		this.builder.putIVec2(x, y);

		return this;
	}

	/** Twelve consumed, for the reason {@link #putVec3} gives. */
	@Override
	public UniformSink putIVec3(int x, int y, int z) {
		this.builder.align(16);
		this.builder.putInt(x).putInt(y).putInt(z);

		return this;
	}

	@Override
	public UniformSink putIVec4(int x, int y, int z, int w) {
		this.builder.putIVec4(x, y, z, w);

		return this;
	}

	/**
	 * The builder's PADDED vec3 on purpose, and the one place it is right: a matrix is laid out as
	 * an array of its columns, and an array element's stride is sixteen whatever the element is. So
	 * the three columns sit at nought, sixteen and thirty two, and the matrix consumes forty eight,
	 * which is what the compiler reports as its MatrixStride and the offset of the member after it.
	 */
	@Override
	public UniformSink putMat3(Matrix3fc m) {
		this.builder.putVec3(m.m00(), m.m01(), m.m02())
				.putVec3(m.m10(), m.m11(), m.m12())
				.putVec3(m.m20(), m.m21(), m.m22());

		return this;
	}

	@Override
	public UniformSink putMat4(Matrix4fc m) {
		this.builder.putMat4f(m);

		return this;
	}
}
