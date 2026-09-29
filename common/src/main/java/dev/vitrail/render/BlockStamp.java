package dev.vitrail.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * What the bytes standing in a program's uniform block were written from, so that a second write of
 * the same bytes is told from a write of new ones.
 * <p>
 * A program's block lives in a ring that turns once a frame, and a program is prepared once for each
 * run of its draws, so a program a frame draws in three runs writes its block three times into one
 * buffer. When nothing the block is made of has changed between two of those writes, the second puts
 * back the bytes that are there: it evaluates every member of the block and maps and fills the
 * buffer for nothing. The block is made of three kinds of thing, and this holds the three.
 * <ul>
 * <li>The <strong>ring turn</strong>. A turn is another buffer, whose bytes are those of a frame
 * before, so nothing written before it stands in it.
 * <li>The <strong>version of the frame's values</strong>, which {@code PackValues.version} says
 * moves whenever the state a block reads from moves.
 * <li>The <strong>four values the writer hands in</strong>: the model view and the bob that placed
 * the pass's geometry, the projection and the colour. The hand is drawn under matrices of its own
 * and the sky's elements under the rotation and the colour the game pushed for each of them, so a
 * program that serves two of those is written for a different set of the four at its second run, and
 * what was written for the first is not what the second needs.
 * </ul>
 * Everything else a block is made of is a constant of the program: the depth convention, the render
 * stage, the alpha test.
 * <p>
 * <strong>The four values are held as copies and compared by their bits.</strong> The matrix the
 * hand hands in is a fresh copy of the game's every time it is asked and another writer may reuse one
 * object for every frame, so neither identity nor a held reference says whether the value moved. A
 * value that is not the same bits, a zero of the other sign included, counts as moved and is written
 * again; the cost of a wrong answer that way is one block written twice, and the cost of the other
 * way is a stale block.
 * <p>
 * Render thread only, like the writes it answers.
 */
final class BlockStamp {

	private boolean stamped;
	private long turn;
	private long version;

	private boolean hasModelView;
	private final Matrix4f modelView = new Matrix4f();
	private boolean hasBob;
	private final Matrix4f bob = new Matrix4f();
	private boolean hasProjection;
	private final Matrix4f projection = new Matrix4f();
	private boolean hasColour;
	private final Vector4f colour = new Vector4f();

	/**
	 * Whether the bytes standing in the buffer the ring holds now are the ones a write at this turn
	 * and version, with these four values, would put there. False until something is stamped, and
	 * again after {@link #clear}.
	 *
	 * @param turn       how many times the ring has turned
	 * @param version    {@code PackValues.version} at the write
	 * @param modelView  the pass's model view, or null for the frame's
	 * @param bob        the bob that placed the pass's geometry, or null for the frame's
	 * @param projection the pass's projection, or null for the frame's
	 * @param colour     the colour the pass is modulated by, or null for white
	 */
	boolean holds(long turn, long version, Matrix4fc modelView, Matrix4fc bob, Matrix4fc projection,
			Vector4fc colour) {
		return this.stamped && this.turn == turn && this.version == version
				&& same(this.hasModelView, this.modelView, modelView)
				&& same(this.hasBob, this.bob, bob)
				&& same(this.hasProjection, this.projection, projection)
				&& same(this.hasColour, this.colour, colour);
	}

	/** Records that the bytes standing in the buffer were written from these. */
	void stamp(long turn, long version, Matrix4fc modelView, Matrix4fc bob, Matrix4fc projection,
			Vector4fc colour) {
		this.stamped = true;
		this.turn = turn;
		this.version = version;
		this.hasModelView = modelView != null;
		if (this.hasModelView) {
			this.modelView.set(modelView);
		}

		this.hasBob = bob != null;
		if (this.hasBob) {
			this.bob.set(bob);
		}

		this.hasProjection = projection != null;
		if (this.hasProjection) {
			this.projection.set(projection);
		}

		this.hasColour = colour != null;
		if (this.hasColour) {
			this.colour.set(colour);
		}
	}

	/** Forgets what was written, for a block whose buffer is gone or was never written. */
	void clear() {
		this.stamped = false;
	}

	private static boolean same(boolean held, Matrix4f stored, Matrix4fc given) {
		return given == null ? !held : held && stored.equals(given);
	}

	private static boolean same(boolean held, Vector4f stored, Vector4fc given) {
		return given == null ? !held : held && stored.equals(given);
	}
}
