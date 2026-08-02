package dev.vitrail.uniform;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

/**
 * Where a block member's bytes go. Nothing on this side names a graphics API.
 * <p>
 * Two things implement it and they have to agree byte for byte: the one that writes into the
 * game's std140 builder, and the one that writes nothing and only counts. The counter is how the
 * buffer is sized, so a member measured one way and written another is not a rounding error, it
 * is every member after it landing at the wrong offset.
 */
public interface UniformSink {

	/**
	 * Says which member the puts that follow belong to, before the first of them.
	 * <p>
	 * Ignored by the two sinks that produce bytes, which is why it is a default: a name changes
	 * nothing about the layout, and a sink that had to be taught one in order to stay correct would
	 * be a sink that could get it wrong. Only {@link TextSink} keeps it.
	 *
	 * @param supplied whether a source answered this name, so that the zeroes a sink is about to be
	 *                 handed can be told from zeroes that are the real value
	 */
	default UniformSink member(String name, int elements, boolean supplied) {
		return this;
	}

	UniformSink align(int alignment);

	UniformSink putFloat(float v);

	UniformSink putInt(int v);

	UniformSink putVec2(float x, float y);

	UniformSink putVec3(float x, float y, float z);

	UniformSink putVec4(float x, float y, float z, float w);

	UniformSink putIVec2(int x, int y);

	UniformSink putIVec3(int x, int y, int z);

	UniformSink putIVec4(int x, int y, int z, int w);

	UniformSink putMat3(Matrix3fc m);

	UniformSink putMat4(Matrix4fc m);
}
