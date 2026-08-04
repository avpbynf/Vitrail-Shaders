package dev.vitrail.uniform;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

/**
 * A sink that writes nothing and only tracks the offset. The size is the result of a write.
 * <p>
 * Sizing a block by a second pass over the members is how the two halves drift apart: one of them
 * gains a case and the other does not, and the buffer is a few bytes short in a way no compiler
 * and no validation layer will mention. Here the size is what a write costs, obtained by doing the
 * write into something that keeps only the position, so there is nothing to keep in step.
 * <p>
 * The alignment rules are the game's own, from {@code Std140SizeCalculator}: four bytes for a
 * scalar, eight for a two component vector, sixteen for everything else, a mat4 being four
 * columns of sixteen and a mat3 three of them.
 */
public final class Std140Counter implements UniformSink {

	private int size;

	public int size() {
		return this.size;
	}

	@Override
	public UniformSink align(int alignment) {
		int remainder = this.size % alignment;
		if (remainder != 0) {
			this.size += alignment - remainder;
		}

		return this;
	}

	@Override
	public UniformSink putFloat(float v) {
		return advance(4, 4);
	}

	@Override
	public UniformSink putInt(int v) {
		return advance(4, 4);
	}

	@Override
	public UniformSink putVec2(float x, float y) {
		return advance(8, 8);
	}

	/**
	 * Sixteen to start on, TWELVE consumed, and the difference is the whole trap of std140.
	 * <p>
	 * A three component vector has a base alignment of sixteen and a size of twelve, so the member
	 * after it starts twelve bytes later at its own alignment rather than sixteen. Measured on the
	 * compiler rather than argued from the specification: {@code vec3 a; float b;} puts b at offset
	 * twelve, and {@code ivec3 a; int b;} does the same. Consuming sixteen here would put every
	 * member after the first vec3 four bytes past where the shader reads it, which is a block that
	 * slides rather than one that fails.
	 * <p>
	 * The padded form is real too and belongs to matrix columns and array elements, where the
	 * stride IS sixteen. It is spelled out where it is needed, by {@link #putMat3} and by the
	 * alignment the block writer puts between array elements, rather than folded in here.
	 */
	@Override
	public UniformSink putVec3(float x, float y, float z) {
		return advance(16, 12);
	}

	@Override
	public UniformSink putVec4(float x, float y, float z, float w) {
		return advance(16, 16);
	}

	@Override
	public UniformSink putIVec2(int x, int y) {
		return advance(8, 8);
	}

	/** Twelve consumed, for the reason {@link #putVec3} gives. */
	@Override
	public UniformSink putIVec3(int x, int y, int z) {
		return advance(16, 12);
	}

	@Override
	public UniformSink putIVec4(int x, int y, int z, int w) {
		return advance(16, 16);
	}

	@Override
	public UniformSink putMat3(Matrix3fc m) {
		return advance(16, 16).advance(16, 16).advance(16, 16);
	}

	@Override
	public UniformSink putMat4(Matrix4fc m) {
		return advance(16, 64);
	}

	private Std140Counter advance(int alignment, int bytes) {
		align(alignment);
		this.size += bytes;

		return this;
	}
}
