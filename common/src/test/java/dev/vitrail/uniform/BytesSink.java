package dev.vitrail.uniform;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

/**
 * A sink that writes real std140 bytes into an array, placing each put by the rules of the
 * specification and by nothing of {@link Std140Counter}'s.
 * <p>
 * The counter sizes a block and the game's builder fills it; this is the third party the tests put
 * between them, so that "the counter agrees with the builder" and "the block reads where the shader
 * looks" are two statements and not one. It allocates nothing after construction, so it can stand in
 * for the game's builder where a test measures what a write costs.
 */
public class BytesSink implements UniformSink {

	private final ByteBuffer bytes;

	public BytesSink(int capacity) {
		this.bytes = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
	}

	/** Back to the start with the bytes left as they are, which is what a reused buffer looks like. */
	public void rewind() {
		this.bytes.clear();
	}

	public int position() {
		return this.bytes.position();
	}

	public byte[] array() {
		return this.bytes.array();
	}

	public float floatAt(int offset) {
		return this.bytes.getFloat(offset);
	}

	public int intAt(int offset) {
		return this.bytes.getInt(offset);
	}

	@Override
	public UniformSink align(int alignment) {
		int remainder = this.bytes.position() % alignment;
		if (remainder != 0) {
			this.bytes.position(this.bytes.position() + alignment - remainder);
		}

		return this;
	}

	@Override
	public UniformSink putFloat(float v) {
		align(4);
		this.bytes.putFloat(v);

		return this;
	}

	@Override
	public UniformSink putInt(int v) {
		align(4);
		this.bytes.putInt(v);

		return this;
	}

	@Override
	public UniformSink putVec2(float x, float y) {
		align(8);
		this.bytes.putFloat(x).putFloat(y);

		return this;
	}

	@Override
	public UniformSink putVec3(float x, float y, float z) {
		align(16);
		this.bytes.putFloat(x).putFloat(y).putFloat(z);

		return this;
	}

	@Override
	public UniformSink putVec4(float x, float y, float z, float w) {
		align(16);
		this.bytes.putFloat(x).putFloat(y).putFloat(z).putFloat(w);

		return this;
	}

	@Override
	public UniformSink putIVec2(int x, int y) {
		align(8);
		this.bytes.putInt(x).putInt(y);

		return this;
	}

	@Override
	public UniformSink putIVec3(int x, int y, int z) {
		align(16);
		this.bytes.putInt(x).putInt(y).putInt(z);

		return this;
	}

	@Override
	public UniformSink putIVec4(int x, int y, int z, int w) {
		align(16);
		this.bytes.putInt(x).putInt(y).putInt(z).putInt(w);

		return this;
	}

	/** Three columns, each a vec3 at a stride of sixteen. */
	@Override
	public UniformSink putMat3(Matrix3fc m) {
		putVec3(m.m00(), m.m01(), m.m02()).align(16);
		putVec3(m.m10(), m.m11(), m.m12()).align(16);
		putVec3(m.m20(), m.m21(), m.m22()).align(16);

		return this;
	}

	/** Four columns of four, column major. */
	@Override
	public UniformSink putMat4(Matrix4fc m) {
		putVec4(m.m00(), m.m01(), m.m02(), m.m03());
		putVec4(m.m10(), m.m11(), m.m12(), m.m13());
		putVec4(m.m20(), m.m21(), m.m22(), m.m23());
		putVec4(m.m30(), m.m31(), m.m32(), m.m33());

		return this;
	}
}
