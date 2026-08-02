package dev.vitrail.uniform;

import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3dc;
import org.joml.Vector3fc;

/**
 * A value on its way from the catalogue to the block. Reused between members, never allocated per
 * frame.
 * <p>
 * A block can carry two hundred members and the chain writes every one of them every frame, so a
 * carrier allocated per member is two hundred short-lived objects a frame for nothing. One of
 * these is held by the block and handed to each source in turn; a source fills it and the block
 * has already written it out before the next source is asked.
 * <p>
 * The rank is what tells the shapes apart, and it is unambiguous: 1, 2, 3 and 4 for the scalars
 * and vectors, 9 for a mat3, 16 for a mat4, and 8 only ever for a fog struct. Reading past the
 * rank gives zero, which is the zero fill half of the coercion rules and costs no branch anywhere
 * else.
 */
public final class Val {

	/** Enough for a mat4, which is the widest thing a member can be. */
	private final float[] floats = new float[16];

	private final int[] ints = new int[4];
	private final Matrix4f mat4 = new Matrix4f();
	private final Matrix3f mat3 = new Matrix3f();

	private int rank;
	private boolean integral;

	public Val set(float x) {
		return scalars(1, false, x, 0.0F, 0.0F, 0.0F);
	}

	public Val set(float x, float y) {
		return scalars(2, false, x, y, 0.0F, 0.0F);
	}

	public Val set(float x, float y, float z) {
		return scalars(3, false, x, y, z, 0.0F);
	}

	public Val set(float x, float y, float z, float w) {
		return scalars(4, false, x, y, z, w);
	}

	public Val set(int x) {
		return integers(1, x, 0, 0, 0);
	}

	public Val set(int x, int y) {
		return integers(2, x, y, 0, 0);
	}

	public Val set(int x, int y, int z) {
		return integers(3, x, y, z, 0);
	}

	public Val set(int x, int y, int z, int w) {
		return integers(4, x, y, z, w);
	}

	public Val set(boolean b) {
		return set(b ? 1 : 0);
	}

	public Val set(Vector3fc v) {
		return set(v.x(), v.y(), v.z());
	}

	/**
	 * The camera positions are doubles all the way to here, because the shift that keeps them
	 * inside a float is applied on the double and not after.
	 */
	public Val set(Vector3dc v) {
		return set((float) v.x(), (float) v.y(), (float) v.z());
	}

	public Val set(Matrix3fc m) {
		this.mat3.set(m);
		this.mat3.get(this.floats);
		this.rank = 9;
		this.integral = false;

		return this;
	}

	public Val set(Matrix4fc m) {
		this.mat4.set(m);
		this.mat4.get(this.floats);
		this.rank = 16;
		this.integral = false;

		return this;
	}

	/** The eight components of {@code OfFog}, in the order the struct declares them. */
	public Val setFog(float r, float g, float b, float a,
			float density, float start, float end, float scale) {
		this.floats[0] = r;
		this.floats[1] = g;
		this.floats[2] = b;
		this.floats[3] = a;
		this.floats[4] = density;
		this.floats[5] = start;
		this.floats[6] = end;
		this.floats[7] = scale;
		this.rank = 8;
		this.integral = false;

		return this;
	}

	public int rank() {
		return this.rank;
	}

	/** Whether the value was set as an integer, which is what makes {@link #i(int)} exact. */
	public boolean integral() {
		return this.integral;
	}

	public float f(int component) {
		return component < this.rank ? this.floats[component] : 0.0F;
	}

	public int i(int component) {
		if (component >= this.rank) {
			return 0;
		}

		return this.integral && component < this.ints.length
				? this.ints[component]
				: (int) this.floats[component];
	}

	/** Only meaningful at rank 16. */
	public Matrix4fc mat4() {
		return this.mat4;
	}

	/** Only meaningful at rank 9. */
	public Matrix3fc mat3() {
		return this.mat3;
	}

	private Val scalars(int rank, boolean integral, float x, float y, float z, float w) {
		this.floats[0] = x;
		this.floats[1] = y;
		this.floats[2] = z;
		this.floats[3] = w;
		this.rank = rank;
		this.integral = integral;

		return this;
	}

	private Val integers(int rank, int x, int y, int z, int w) {
		this.ints[0] = x;
		this.ints[1] = y;
		this.ints[2] = z;
		this.ints[3] = w;

		// Mirrored into the floats as well, so that a pack declaring one of the flags a float
		// reads it through the same path as everything else rather than a case of its own.
		return scalars(rank, true, x, y, z, w);
	}
}
