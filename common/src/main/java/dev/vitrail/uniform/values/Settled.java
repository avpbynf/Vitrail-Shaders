package dev.vitrail.uniform.values;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Whether the answer a value handed out last time is still the answer, decided on what that answer
 * was built from rather than on a clock.
 * <p>
 * The catalogue is asked once per PROGRAM that declares a name, and a pack has many programs, so a
 * name like {@code sunPosition} is asked for many times over inside one frame and every ask
 * rebuilds the same rotations out of the same numbers. Iris pays that same bill: its celestial
 * names are PER_FRAME uniforms ({@code uniforms/CelestialUniforms.java:80-91}) held on a
 * {@code ProgramUniforms} that belongs to one program and steps its own frame counter
 * ({@code gl/program/ProgramUniforms.java:231-234}), so there too the work is done once per program
 * and not once per frame. Nothing here is a divergence in either direction: what a pack reads is
 * the value that would have been rebuilt, and only the rebuilding goes.
 * <p>
 * <strong>Keyed on the inputs, and deliberately not on the frame number that
 * {@link FrameSmoothed} is keyed on.</strong> That class holds an accumulator which has to step
 * once per frame however many passes read it, so the frame IS its question. There is no
 * accumulator here: the answer is a pure function of a few matrices and angles, so asking whether
 * those moved is both the cheaper question and the one that cannot go stale. It needs no case for
 * a pack reload, a dimension change or a world leave, because none of those reaches a value except
 * through an input that moved. And it is right about the one boundary a frame key would have been
 * wrong about: the model view and the projection a PASS reads are that pass's own and change
 * several times inside a frame, the sky and the hand each setting theirs
 * ({@link dev.vitrail.uniform.ViewSource#passModelView()}).
 * <p>
 * This does remember between calls, which {@link dev.vitrail.uniform.UniformSource} tells a source
 * not to, and it keeps the rule that prohibition exists for: nothing here advances with time, so
 * two passes of one frame are handed the same number for the same reason they always were. It is
 * held statically like the scratch of the classes that use it, on the same single caller they
 * already assume.
 * <p>
 * One of these belongs to one call site. Each site uses the single overload whose shape is its own,
 * and the fields the other shapes use are never touched.
 */
final class Settled {

	private final Matrix4f first = new Matrix4f();
	private final Matrix4f second = new Matrix4f();

	private float a;
	private float b;

	/**
	 * False until a call has recorded a key. Not redundant: a matrix nothing has written yet is the
	 * identity, and the identity is a matrix a pass really does hand in.
	 */
	private boolean valid;

	/**
	 * @return whether the answer built last time still stands. A false also RECORDS what was handed
	 *         in, so a caller that gets one rebuilds and the call after it compares against these
	 */
	boolean holds(Matrix4fc matrix) {
		if (this.valid && same(this.first, matrix)) {
			return true;
		}

		this.first.set(matrix);
		this.valid = true;

		return false;
	}

	/**
	 * The same, for an answer that also turns on two numbers.
	 *
	 * @see #holds(Matrix4fc)
	 */
	boolean holds(Matrix4fc matrix, float a, float b) {
		if (this.valid && this.a == a && this.b == b && same(this.first, matrix)) {
			return true;
		}

		this.first.set(matrix);
		this.a = a;
		this.b = b;
		this.valid = true;

		return false;
	}

	/**
	 * The same, for an answer built out of two matrices.
	 *
	 * @see #holds(Matrix4fc)
	 */
	boolean holds(Matrix4fc first, Matrix4fc second) {
		if (this.valid && same(this.first, first) && same(this.second, second)) {
			return true;
		}

		this.first.set(first);
		this.second.set(second);
		this.valid = true;

		return false;
	}

	/**
	 * Exact, and it has to be. JOML's delta comparison tests the bits of each element first and
	 * falls through to {@code |a - b| <= delta}, so a delta of nought admits nothing a plain
	 * {@code ==} would not, and a NaN matches the NaN it was built from rather than forcing a
	 * rebuild for ever. Any tolerance at all would hand a pack the answer to a slightly different
	 * question, which is a changed image however small the tolerance is.
	 */
	private static boolean same(Matrix4f held, Matrix4fc matrix) {
		return held.equals(matrix, 0.0F);
	}
}
