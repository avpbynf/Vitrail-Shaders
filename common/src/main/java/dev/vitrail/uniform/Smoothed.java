package dev.vitrail.uniform;

/**
 * Exponential smoothing with a half life in tenths of a second.
 * <p>
 * Adapted in August 2026 from {@code net.irisshaders.iris.uniforms.transforms.SmoothedFloat}, Iris
 * commit b0ae41c, which is what the packs are written against. The unit of the half life is the
 * decisecond, so two ticks, and it is the pack that sets it through its own directives; getting the
 * unit wrong by a factor of ten produces a value that moves, looks smoothed, and is not.
 * <p>
 * Three details decide the result and none of them is obvious. The rise and the fall have separate
 * half lives, chosen on whether the new value is above the accumulator. There is no smoothing at
 * all on the first value, which is set outright. And a half life of zero gives an infinite decay
 * constant, hence a factor of one, hence no smoothing, which falls out of the arithmetic rather
 * than needing a case.
 * <p>
 * Modified: the value, the two half lives and the frame duration are handed in rather than pulled
 * from a supplier and a global frame clock, because the caller here already has all three and
 * nothing in this package is allowed to know what a frame is.
 */
public final class Smoothed {

	private static final double LN_OF_2 = Math.log(2.0);

	private float accumulator;
	private boolean started;

	/**
	 * @param halfLifeUp   half life in deciseconds while the value rises
	 * @param halfLifeDown half life in deciseconds while it falls
	 * @param dt           the previous frame's duration in seconds, quantised the way the frame
	 *                     clock quantises it
	 */
	public float updateAndGet(float value, float halfLifeUp, float halfLifeDown, float dt) {
		if (!this.started) {
			this.started = true;
			this.accumulator = value;

			return this.accumulator;
		}

		if (dt <= 0.0F) {
			return this.accumulator;
		}

		float decay = decayConstant(value > this.accumulator ? halfLifeUp : halfLifeDown);
		float factor = 1.0F - (float) Math.exp(-decay * dt);
		this.accumulator = this.accumulator + (value - this.accumulator) * factor;

		return this.accumulator;
	}

	public void reset() {
		this.started = false;
		this.accumulator = 0.0F;
	}

	private static float decayConstant(float halfLife) {
		return (float) (1.0 / (halfLife * 0.1 / LN_OF_2));
	}
}
