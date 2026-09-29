package dev.vitrail.render;

/**
 * What a run of draws has already told the pass it records into, so that a draw sends only what
 * differs from it.
 * <p>
 * A pass keeps what it is told until it closes: the pipeline bound, the scissor rectangle, and every
 * uniform and sampled image by name. Both games hold the names in a map that outlives a
 * {@code setPipeline}, and both mark the descriptors dirty on every {@code setPipeline} and every
 * set, whatever the value. So a draw that says the same thing again costs a pipeline bind, a
 * scissor command and, worse, a full descriptor push at the draw, all of them to end up where the
 * pass already stands. A run of draws of one program is most of an entity frame, and every draw of
 * it after the first would say all three again if nothing remembered what the run has said.
 * <p>
 * <strong>Each answer records what it is asked, so a caller asks once and sends when told
 * to.</strong> The record is made when the answer is given and before the send, so a send that
 * throws leaves it standing; the run it belongs to is ended by whoever catches the throw, and
 * {@link #forget} is part of ending it.
 * <p>
 * <strong>What this knows is only what this run said, and that is why it is forgotten whenever the
 * run is not the only thing that has touched the pass.</strong> A pass a hold carries across
 * families is bound by whichever family comes next, and the game's own draws are adopted into one
 * that is already open, so a run that begins on a held pass has no idea what the pass stands on.
 * The owner forgets when it opens a run and when it ends one; between those two nothing else is
 * recorded into the pass, since every draw the owner hands back to the game ends the run first.
 * <p>
 * Holds nothing of the game: pipelines are compared by identity and the rest by value, so that no
 * closed pass, pipeline or buffer is kept reachable past the run. Render thread only.
 */
final class SentState {

	private Object pipeline;

	private boolean scissorKnown;
	private boolean scissored;
	private int scissorX;
	private int scissorY;
	private int scissorWidth;
	private int scissorHeight;

	private Object transforms;

	/**
	 * Whether the pass has to be told this pipeline, which it does not when the last one said was
	 * this one.
	 *
	 * @param pipeline what the run wants bound, compared by identity
	 */
	@SuppressWarnings("ReferenceEquality")
	boolean pipeline(Object pipeline) {
		if (pipeline != null && pipeline == this.pipeline) {
			return false;
		}

		this.pipeline = pipeline;

		return true;
	}

	/**
	 * Whether the pass has to be told this scissor, which it does not when the last one said was the
	 * same rectangle, or when both switched it off. The four numbers of a disabled scissor mean
	 * nothing and are not compared.
	 */
	boolean scissor(boolean enabled, int x, int y, int width, int height) {
		boolean sameRectangle = x == this.scissorX && y == this.scissorY
				&& width == this.scissorWidth && height == this.scissorHeight;
		if (this.scissorKnown && this.scissored == enabled && (!enabled || sameRectangle)) {
			return false;
		}

		this.scissorKnown = true;
		this.scissored = enabled;
		this.scissorX = x;
		this.scissorY = y;
		this.scissorWidth = width;
		this.scissorHeight = height;

		return true;
	}

	/**
	 * Whether the pass has to be told this value for the game's per draw transforms, which it does
	 * not when the last one said was equal to it. Nothing is held for a null, which is always sent:
	 * the pass is the one to say what it makes of it.
	 */
	boolean transforms(Object transforms) {
		if (transforms == null) {
			this.transforms = null;

			return true;
		}

		if (transforms.equals(this.transforms)) {
			return false;
		}

		this.transforms = transforms;

		return true;
	}

	/** Drops everything, so that the next thing asked about is sent. */
	void forget() {
		this.pipeline = null;
		this.scissorKnown = false;
		this.transforms = null;
	}
}
